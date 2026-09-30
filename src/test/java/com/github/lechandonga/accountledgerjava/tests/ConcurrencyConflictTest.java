package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;
import com.github.lechandonga.accountledgerjava.error.ConcurrencyConflictException;
import com.github.lechandonga.accountledgerjava.error.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.error.LedgerException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerOperationRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 乐观并发控制：冲突可检测、可区分，冲突后数据保持一致，无丢失更新。
 */
@SpringBootTest
class ConcurrencyConflictTest {

    private static final Logger log = LoggerFactory.getLogger(ConcurrencyConflictTest.class);

    @Autowired
    AccountService accountService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    LedgerOperationRepository ledgerOperationRepository;
    @Autowired
    TransactionTemplate txTemplate;

    @BeforeEach
    void clean() {
        ledgerEntryRepository.deleteAll();
        ledgerOperationRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void staleVersionUpdateIsDetectedAsOptimisticLockConflict() {
        accountService.createAccount("c1", 100);

        // 两个事务分别加载同一账户（version=0 的快照）
        Account txA = txTemplate.execute(tx -> accountRepository.findById("c1").orElseThrow());
        Account txB = txTemplate.execute(tx -> accountRepository.findById("c1").orElseThrow());
        log.info("loaded two snapshots: A.version={} B.version={}", txA.getVersion(), txB.getVersion());

        // 事务 A 先提交
        txTemplate.executeWithoutResult(tx -> {
            txA.setBalance(80);
            accountRepository.saveAndFlush(txA);
        });

        // 事务 B 基于过期版本提交，必须被乐观锁拒绝
        OptimisticLockingFailureException ex = assertThrows(OptimisticLockingFailureException.class,
                () -> txTemplate.executeWithoutResult(tx -> {
                    txB.setBalance(60);
                    accountRepository.saveAndFlush(txB);
                }));

        long balance = accountService.findAccount("c1").orElseThrow().getBalance();
        log.info("stale update rejected ({}), balance={} (expect 80, A 的更新未丢失)",
                ex.getClass().getSimpleName(), balance);
        assertEquals(80, balance);
    }

    @Test
    void concurrentDebitsNeverLoseUpdatesAndFailuresAreDistinguishable() throws Exception {
        // 余额 100，两个线程各扣 60：只能成功一笔，另一笔必须是
        // 可区分的失败（乐观锁冲突 或 余额不足），绝不允许两笔都成功导致透支。
        accountService.createAccount("c2", 100);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<String>> tasks = List.of(
                () -> runDebit("c2", 60, "req-t1"),
                () -> runDebit("c2", 60, "req-t2"));
        List<Future<String>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        List<String> outcomes = new CopyOnWriteArrayList<>();
        for (Future<String> f : futures) {
            outcomes.add(f.get());
        }
        long balance = accountService.findAccount("c2").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        long successes = outcomes.stream().filter("SUCCESS"::equals).count();
        log.info("two concurrent debits of 60 on balance 100 -> outcomes={} balance={} entries={}",
                outcomes, balance, entries);

        // 判定依据：恰好一笔成功；余额=40 且只有一条流水；失败原因属于两类可区分失败之一
        assertEquals(1, successes);
        assertEquals(40, balance);
        assertEquals(1, entries);
        assertTrue(outcomes.stream().allMatch(o -> o.equals("SUCCESS")
                || o.equals("CONFLICT") || o.equals("INSUFFICIENT")));
    }

    private String runDebit(String accountId, long amount, String requestId) {
        try {
            accountService.applyChange(accountId, LedgerEntryType.DEBIT, amount, requestId);
            return "SUCCESS";
        } catch (ConcurrencyConflictException e) {
            log.info("debit {} -> CONFLICT (category={})", requestId, e.category());
            return "CONFLICT";
        } catch (InsufficientFundsException e) {
            log.info("debit {} -> INSUFFICIENT (category={})", requestId, e.category());
            return "INSUFFICIENT";
        }
    }

    @Test
    void conflictExceptionCategoryIsDistinguishable() {
        ConcurrencyConflictException ex = new ConcurrencyConflictException("c9", null);
        log.info("conflict category={} (expect CONCURRENCY_CONFLICT)", ex.category());
        assertEquals(LedgerException.Category.CONCURRENCY_CONFLICT, ex.category());
    }
}
