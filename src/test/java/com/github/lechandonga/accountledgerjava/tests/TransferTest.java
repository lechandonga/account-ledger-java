package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.domain.LedgerOperationResult;
import com.github.lechandonga.accountledgerjava.domain.OperationStatus;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

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
 * 转账：双向两条流水原子落库；余额不足时整体不落任何痕迹；
 * 两账户双向并发转账不死锁，总额守恒。
 */
@SpringBootTest
class TransferTest {

    private static final Logger log = LoggerFactory.getLogger(TransferTest.class);

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String newAccount(String initial) {
        String no = "ACC-" + UUID.randomUUID();
        accountService.createAccount(no, "转账账户", new BigDecimal(initial), "CNY");
        return no;
    }

    @Test
    void transfer_produces_two_entries_and_moves_balance_atomically() {
        String a = newAccount("100.00");
        String b = newAccount("20.00");
        String req = "REQ-" + UUID.randomUUID();

        LedgerOperationResult result = ledgerService.transfer(req, a, b, new BigDecimal("30.00"));

        log.info("判定依据: requestId={} status={} 流水数={} A余额={} B余额={}",
                req, result.status(), result.entries().size(),
                accountService.getAccount(a).balance(), accountService.getAccount(b).balance());
        assertEquals(OperationStatus.SUCCESS, result.status());
        assertEquals(2, result.entries().size());
        assertEquals(0, new BigDecimal("70.00").compareTo(accountService.getAccount(a).balance()));
        assertEquals(0, new BigDecimal("50.00").compareTo(accountService.getAccount(b).balance()));
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE request_id = ?", Long.class, req);
        assertEquals(2L, count);
    }

    @Test
    void transfer_with_insufficient_funds_leaves_nothing_behind() {
        String a = newAccount("10.00");
        String b = newAccount("100.00");
        String req = "REQ-" + UUID.randomUUID();

        LedgerOperationResult result = ledgerService.transfer(req, a, b, new BigDecimal("50.00"));

        log.info("判定依据: requestId={} status={} code={} A余额={} B余额={} A版本={}",
                req, result.status(), result.rejectCode(),
                accountService.getAccount(a).balance(), accountService.getAccount(b).balance(),
                accountService.getAccount(a).version());
        assertEquals(OperationStatus.REJECTED, result.status());
        assertEquals("INSUFFICIENT_FUNDS", result.rejectCode());
        assertEquals(0, new BigDecimal("10.00").compareTo(accountService.getAccount(a).balance()));
        assertEquals(0, new BigDecimal("100.00").compareTo(accountService.getAccount(b).balance()));
        assertEquals(0L, accountService.getAccount(a).version());
        Long entries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE request_id = ?", Long.class, req);
        assertEquals(0L, entries, "整体回滚：不得残留任何流水");
    }

    @Test
    void bidirectional_concurrent_transfers_do_not_deadlock_and_conserve_total() throws Exception {
        String a = newAccount("500.00");
        String b = newAccount("500.00");
        int pairs = 20;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(pairs * 2);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        for (int i = 0; i < pairs; i++) {
            final int idx = i;
            // 方向 A->B
            pool.submit(() -> runTransfer(a, b, "10.00", "AB-" + idx, start, done,
                    success, rejected, failure));
            // 方向 B->A
            pool.submit(() -> runTransfer(b, a, "10.00", "BA-" + idx, start, done,
                    success, rejected, failure));
        }
        start.countDown();
        assertTrue(done.await(60, TimeUnit.SECONDS), "双向并发转账必须在时限内完成（不得死锁）");
        pool.shutdown();

        Account accA = accountService.getAccount(a);
        Account accB = accountService.getAccount(b);
        long total = accA.balance().add(accB.balance()).movePointRight(2).longValue();
        log.info("判定依据: 成功={} 拒绝={} 其他失败={} A={} B={} 总额(分)={}",
                success.get(), rejected.get(), failure.get(), accA.balance(), accB.balance(), total);
        assertEquals(0, failure.get(), "不得出现死锁/回滚以外的未预期失败");
        assertEquals(pairs * 2, success.get() + rejected.get(), "每笔请求都必须得到确定结论");
        assertEquals(100000L, total, "资金总额必须守恒");
    }

    private void runTransfer(String from, String to, String amount, String reqPrefix,
                             CountDownLatch start, CountDownLatch done,
                             AtomicInteger success, AtomicInteger rejected, AtomicInteger failure) {
        try {
            start.await();
            LedgerOperationResult r = ledgerService.transfer(
                    "REQ-" + reqPrefix + "-" + UUID.randomUUID(), from, to, new BigDecimal(amount));
            if (r.status() == OperationStatus.SUCCESS) {
                success.incrementAndGet();
            } else {
                rejected.incrementAndGet();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure.incrementAndGet();
        } catch (RuntimeException e) {
            failure.incrementAndGet();
            log.error("并发转账非预期失败", e);
        } finally {
            done.countDown();
        }
    }
}
