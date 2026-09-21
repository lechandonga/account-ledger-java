package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.exception.OptimisticLockConflictException;
import com.github.lechandonga.accountledgerjava.repository.AccountRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 并发冲突：同一账户基于同一版本号并发更新时，仅一个成功，其余必须以
 * OPTIMISTIC_LOCK_CONFLICT 失败（可区分于余额不足），且余额绝不被覆盖。
 */
@SpringBootTest
class OptimisticLockConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(OptimisticLockConcurrencyTest.class);

    @Autowired
    private AccountService accountService;
    @Autowired
    private AccountRepository accountRepository;

    @Test
    void concurrent_updates_on_same_version_exactly_one_wins_no_lost_update() throws Exception {
        String no = "ACC-" + UUID.randomUUID();
        accountService.createAccount(no, "并发账户", new BigDecimal("1000.00"), "CNY");
        Account snapshot = accountService.getAccount(no);
        long sharedVersion = snapshot.version();
        log.info("关键输入: accountNo={} 起始余额={} 所有线程共用版本号={}",
                no, snapshot.balance(), sharedVersion);

        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger otherFailure = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    // 全部线程使用同一版本号 +100 元：除首胜外都应命中乐观锁冲突
                    accountRepository.applyBalanceChange(no, sharedVersion, 10000L);
                    success.incrementAndGet();
                } catch (OptimisticLockConflictException e) {
                    conflict.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    otherFailure.incrementAndGet();
                } catch (RuntimeException e) {
                    otherFailure.incrementAndGet();
                    log.error("非预期失败", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务未在时限内完成");
        pool.shutdown();

        Account after = accountService.getAccount(no);
        log.info("判定依据: 成功={} 乐观锁冲突={} 其他失败={} 最终余额={} 最终版本={}",
                success.get(), conflict.get(), otherFailure.get(), after.balance(), after.version());
        assertEquals(1, success.get(), "同一版本并发更新只能有一个成功");
        assertEquals(threads - 1, conflict.get(), "其余必须全部是乐观锁冲突");
        assertEquals(0, otherFailure.get(), "不得出现其他类型失败");
        assertEquals(0, new BigDecimal("1100.00").compareTo(after.balance()),
                "丢失更新防护：余额只能增加一次 100");
        assertEquals(sharedVersion + 1, after.version(), "版本号只能推进一次");
    }
}
