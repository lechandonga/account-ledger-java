package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.error.ConcurrencyConflictException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerRequestRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * 双向并发划转：不丢钱、不覆盖余额、双向同时互转不死锁；
 * 冲突映射为 CONCURRENCY_CONFLICT 并可安全重试，全部划转最终成功。
 */
@SpringBootTest
class ConcurrentTransferTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentTransferTest.class);

    @Autowired TransferService transferService;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired LedgerRequestRepository ledgerRequestRepository;

    @BeforeEach
    void clean() {
        ledgerEntryRepository.deleteAll();
        ledgerRequestRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void bidirectionalConcurrentTransfersConserveMoneyAndNeverDeadlock() throws Exception {
        int eachDirection = 10;
        long amount = 5;
        accountService.createAccount("ca", 1_000);
        accountService.createAccount("cb", 1_000);

        List<Callable<String>> tasks = new CopyOnWriteArrayList<>();
        for (int i = 0; i < eachDirection; i++) {
            final int idx = i;
            tasks.add(() -> transferWithRetry("ca", "cb", amount, "ctx-ab-" + idx));
            tasks.add(() -> transferWithRetry("cb", "ca", amount, "ctx-ba-" + idx));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        long conflicts = 0;
        long successes = 0;
        for (Future<String> f : futures) {
            String outcome = f.get();
            if ("SUCCESS".equals(outcome)) {
                successes++;
            } else {
                conflicts++;
            }
        }

        long balanceA = accountService.findAccount("ca").orElseThrow().getBalance();
        long balanceB = accountService.findAccount("cb").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        long requests = ledgerRequestRepository.count();
        log.info("bidirectional transfers: total={} success={} retriedAfterConflict={} "
                        + "balances ca={} cb={} sum={} entries={} requests={} "
                        + "(expect sum 2000, balances both 1000, entries 40, requests 20, no deadlock)",
                tasks.size(), successes, conflicts, balanceA, balanceB, balanceA + balanceB, entries, requests);

        // 判定依据：所有划转（含冲突重试后）成功；总额守恒；每个请求恰好两条流水
        assertEquals(tasks.size(), successes);
        assertEquals(2_000, balanceA + balanceB);
        assertEquals(1_000, balanceA);
        assertEquals(1_000, balanceB);
        assertEquals(2L * tasks.size(), entries);
        assertEquals(tasks.size(), requests);
    }

    @Test
    void conflictingTransferIsRetriableAndLeavesNoPartialState() throws Exception {
        // 两个不同请求号、方向相反的划转并发：可能产生冲突，冲突方可重试成功；
        // 任何冲突失败都不得留下单边流水。
        accountService.createAccount("cc", 100);
        accountService.createAccount("cd", 100);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> f1 = pool.submit(() -> transferWithRetry("cc", "cd", 10, "conf-1"));
        Future<String> f2 = pool.submit(() -> transferWithRetry("cd", "cc", 30, "conf-2"));
        pool.shutdown();

        String o1 = f1.get();
        String o2 = f2.get();
        long balanceC = accountService.findAccount("cc").orElseThrow().getBalance();
        long balanceD = accountService.findAccount("cd").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        log.info("two opposite transfers outcomes={},{} balances=({},{}) entries={} (expect 120/80/4)",
                o1, o2, balanceC, balanceD, entries);

        assertEquals("SUCCESS", o1);
        assertEquals("SUCCESS", o2);
        assertEquals(120, balanceC);
        assertEquals(80, balanceD);
        assertEquals(4, entries);
        // 每个请求号都必须能同时查到两条流水，不存在只扣不出的半截请求
        assertEquals(2, ledgerEntryRepository.findByRequestIdOrderByIdAsc("conf-1").size());
        assertEquals(2, ledgerEntryRepository.findByRequestIdOrderByIdAsc("conf-2").size());
    }

    private String transferWithRetry(String from, String to, long amount, String requestId) {
        int maxAttempts = 50;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                transferService.transfer(from, to, amount, requestId);
                return "SUCCESS";
            } catch (ConcurrencyConflictException e) {
                // 乐观锁冲突：事务已回滚，安全重试（同 requestId 重试还会顺带处理幂等回放）
                if (attempt == maxAttempts) {
                    throw e;
                }
                Thread.yield();
            }
        }
        return "FAILED";
    }

    @Test
    void oppositeTransfersOverlappingOnLockedRowsDoNotDeadlock() {
        // 两个方向相反的划转同时启动（屏障保证两线程在同一刻竞争这对账户）。
        // 若加锁顺序随划转方向而变，两个事务会各持一边再等另一边，形成等待环
        // （死锁/锁超时）；统一按账户 id 升序更新则等待图无环，两笔都能完成，
        // 落败方即使遇到乐观锁冲突也能安全重试。
        accountService.createAccount("ce", 100);
        accountService.createAccount("cf", 100);
        CyclicBarrier startTogether = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            Future<String> f1 = pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return transferWithRetry("ce", "cf", 10, "opp-f");
            });
            Future<String> f2 = pool.submit(() -> {
                startTogether.await(10, TimeUnit.SECONDS);
                return transferWithRetry("cf", "ce", 20, "opp-b");
            });

            // 不死锁：两笔最终都应成功；冲突方重试后收敛。
            assertEquals("SUCCESS", f1.get(30, TimeUnit.SECONDS));
            assertEquals("SUCCESS", f2.get(30, TimeUnit.SECONDS));
        });
        pool.shutdown();

        long balanceE = accountService.findAccount("ce").orElseThrow().getBalance();
        long balanceF = accountService.findAccount("cf").orElseThrow().getBalance();
        log.info("simultaneous opposite transfers completed without deadlock: "
                + "balances ce={} cf={} (expect 110/90)", balanceE, balanceF);
        assertEquals(110, balanceE);
        assertEquals(90, balanceF);
    }
}
