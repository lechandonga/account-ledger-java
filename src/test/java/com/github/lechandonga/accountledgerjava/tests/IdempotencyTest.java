package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.domain.LedgerOperationResult;
import com.github.lechandonga.accountledgerjava.domain.OperationStatus;
import com.github.lechandonga.accountledgerjava.exception.IdempotentRequestConflictException;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 重复提交：同一 requestId 重复请求必须返回与首次一致的结果且只产生一条/一组流水；
 * 并发首发同样只入账一次；同键不同体必须被识别并拒绝。
 */
@SpringBootTest
class IdempotencyTest {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyTest.class);

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String newAccount(String initial) {
        String no = "ACC-" + UUID.randomUUID();
        accountService.createAccount(no, "幂等账户", new BigDecimal(initial), "CNY");
        return no;
    }

    private long countEntries(String requestId) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE request_id = ?", Long.class, requestId);
        return c == null ? 0 : c;
    }

    @Test
    void repeated_deposit_returns_first_result_and_single_entry() {
        String no = newAccount("100.00");
        String req = "REQ-" + UUID.randomUUID();

        LedgerOperationResult first = ledgerService.deposit(req, no, new BigDecimal("30.00"));
        LedgerOperationResult second = ledgerService.deposit(req, no, new BigDecimal("30.00"));
        LedgerOperationResult third = ledgerService.deposit(req, no, new BigDecimal("30.00"));

        Account account = accountService.getAccount(no);
        long entryCount = countEntries(req);
        log.info("判定依据: requestId={} 首次replay={} 二次replay={} 三次replay={} 流水数={} 余额={}",
                req, first.replay(), second.replay(), third.replay(), entryCount, account.balance());
        assertEquals(0, new BigDecimal("130.00").compareTo(account.balance()), "重复请求不得重复入账");
        assertEquals(1, entryCount, "只允许一条流水");
        assertEquals(false, first.replay());
        assertTrue(second.replay() && third.replay(), "重复请求必须标记为重放");
        assertEquals(first.entries().get(0).id(), second.entries().get(0).id(),
                "重放结果必须与首次为同一条流水");
    }

    @Test
    void concurrent_first_submissions_only_account_once() throws Exception {
        String no = newAccount("0.00");
        String req = "REQ-" + UUID.randomUUID();
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    LedgerOperationResult r = ledgerService.deposit(req, no, new BigDecimal("50.00"));
                    if (r.status() == OperationStatus.SUCCESS) {
                        ok.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure.incrementAndGet();
                } catch (RuntimeException e) {
                    failure.incrementAndGet();
                    log.error("并发幂等非预期失败", e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS));
        pool.shutdown();

        Account account = accountService.getAccount(no);
        long entryCount = countEntries(req);
        log.info("判定依据: requestId={} 成功返回={} 失败={} 流水数={} 余额={}",
                req, ok.get(), failure.get(), entryCount, account.balance());
        assertEquals(threads, ok.get(), "所有并发请求都应拿到成功结果（首发或重放）");
        assertEquals(0, failure.get());
        assertEquals(0, new BigDecimal("50.00").compareTo(account.balance()), "并发首发只入账一次");
        assertEquals(1, entryCount);
    }

    @Test
    void same_request_id_with_different_payload_is_rejected() {
        String no = newAccount("100.00");
        String req = "REQ-" + UUID.randomUUID();
        ledgerService.deposit(req, no, new BigDecimal("10.00"));

        String other = newAccount("100.00");
        log.info("关键输入: 复用 requestId={} 但账户由 {} 换为 {}", req, no, other);
        IdempotentRequestConflictException ex = assertThrows(IdempotentRequestConflictException.class,
                () -> ledgerService.deposit(req, other, new BigDecimal("10.00")));
        log.info("判定依据: errorCode={}", ex.getErrorCode());
        assertEquals("IDEMPOTENT_REQUEST_CONFLICT", ex.getErrorCode());
    }

    @Test
    void rejected_request_is_replayed_with_same_outcome() {
        String no = newAccount("5.00");
        String req = "REQ-" + UUID.randomUUID();

        LedgerOperationResult first = ledgerService.withdraw(req, no, new BigDecimal("9.00"));
        LedgerOperationResult second = ledgerService.withdraw(req, no, new BigDecimal("9.00"));

        log.info("判定依据: requestId={} 首次status={}/{} 重放status={}/{} replay={}",
                req, first.status(), first.rejectCode(),
                second.status(), second.rejectCode(), second.replay());
        assertEquals(OperationStatus.REJECTED, first.status());
        assertEquals("INSUFFICIENT_FUNDS", first.rejectCode());
        assertEquals(first.status(), second.status());
        assertEquals(first.rejectCode(), second.rejectCode());
        assertTrue(second.replay());
        assertEquals(0, countEntries(req), "业务拒绝不得产生流水");
        assertEquals(0, accountService.getAccount(no).version(), "拒绝不得推进版本");
    }
}
