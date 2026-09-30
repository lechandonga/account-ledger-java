package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 事务边界正确性：嵌套调用不得部分提交，失败后不残留半更新状态、不泄漏连接。
 */
@SpringBootTest
class TransactionRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionRollbackTest.class);

    @Autowired
    AccountService accountService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    TransactionTemplate txTemplate;
    @Autowired
    DataSource dataSource;

    @BeforeEach
    void clean() {
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void nestedFailureRollsBackEntireOuterTransaction() {
        accountService.createAccount("t1", 100);

        // 外层事务内先成功扣减 30，随后抛出异常：
        // 内层（默认 REQUIRED 传播）加入外层事务，必须随外层整体回滚。
        RuntimeException boom = assertThrows(RuntimeException.class, () ->
                txTemplate.executeWithoutResult(tx -> {
                    accountService.applyChange("t1", LedgerEntryType.DEBIT, 30, "req-nested-1");
                    throw new IllegalStateException("simulated downstream failure");
                }));

        long balance = accountService.findAccount("t1").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        log.info("nested failure ({}) -> balance={} entries={} (expect 100/0, 无部分提交)",
                boom.getMessage(), balance, entries);

        // 判定依据：扣减与流水都未残留，说明内层没有独立提交
        assertEquals(100, balance);
        assertEquals(0, entries);
    }

    @Test
    void failedChangeLeavesNoPartialStateAndNoConnectionLeak() {
        accountService.createAccount("t2", 10);
        HikariDataSource hikari = (HikariDataSource) dataSource;

        // 连续触发多次失败（余额不足）
        for (int i = 0; i < 5; i++) {
            String reqId = "req-leak-" + i;
            assertThrows(Exception.class,
                    () -> accountService.applyChange("t2", LedgerEntryType.DEBIT, 999, reqId));
        }

        long balance = accountService.findAccount("t2").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        int active = hikari.getHikariPoolMXBean().getActiveConnections();
        int idle = hikari.getHikariPoolMXBean().getIdleConnections();
        int total = hikari.getHikariPoolMXBean().getTotalConnections();
        log.info("after 5 failed debits -> balance={} entries={} pool[active={} idle={} total={}]",
                balance, entries, active, idle, total);

        // 判定依据：无半更新状态；连接全部归还连接池（active=0）
        assertEquals(10, balance);
        assertEquals(0, entries);
        assertEquals(0, active);
    }

    @Test
    void successfulChangeCommitsAtomically() {
        accountService.createAccount("t3", 50);
        accountService.applyChange("t3", LedgerEntryType.DEBIT, 20, "req-atomic-1");

        long balance = accountService.findAccount("t3").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        long balanceAfter = ledgerEntryRepository
                .findByAccountIdAndRequestId("t3", "req-atomic-1").orElseThrow().getBalanceAfter();
        log.info("debit 20 from 50 -> balance={} entries={} entry.balanceAfter={} (expect 30/1/30)",
                balance, entries, balanceAfter);

        // 判定依据：余额与流水同事务落库，且流水快照与账户余额一致
        assertEquals(30, balance);
        assertEquals(1, entries);
        assertEquals(30, balanceAfter);
    }
}
