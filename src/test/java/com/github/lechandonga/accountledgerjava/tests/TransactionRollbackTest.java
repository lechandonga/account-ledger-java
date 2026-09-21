package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.exception.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.repository.AccountRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.tests.fixture.NestedTxProbe;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事务边界：
 * 1) 嵌套 REQUIRED 内层失败 -> 外层已做变更一并回滚，杜绝部分提交；
 * 2) 嵌套 REQUIRES_NEW 内层独立回滚 -> 外层不受影响，无半更新状态；
 * 3) 异常后连接必须归还连接池，不残留半更新状态。
 */
@SpringBootTest
class TransactionRollbackTest {

    private static final Logger log = LoggerFactory.getLogger(TransactionRollbackTest.class);

    @Autowired
    private AccountService accountService;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private NestedTxProbe nestedTxProbe;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private HikariDataSource hikariDataSource;

    private String newAccount(String initial) {
        String no = "ACC-" + UUID.randomUUID();
        accountService.createAccount(no, "事务回滚账户", new BigDecimal(initial), "CNY");
        return no;
    }

    @Test
    void nested_required_failure_rolls_back_outer_changes_too() {
        String outer = newAccount("100.00");
        String inner = newAccount("200.00");
        long outerVersion = accountService.getAccount(outer).version();
        long innerVersion = accountService.getAccount(inner).version();
        String req = "REQ-" + UUID.randomUUID();

        assertThrows(InsufficientFundsException.class,
                () -> nestedTxProbe.outerThenFailingRequired(
                        outer, outerVersion, inner, innerVersion, req));

        Account outerAfter = accountService.getAccount(outer);
        Account innerAfter = accountService.getAccount(inner);
        Long entries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE request_id = ?", Long.class, req);
        log.info("判定依据(REQUIRED): 外层账户余额={}/版本={} 内层账户余额={}/版本={} 流水数={}（期望 100/0, 200/0, 0）",
                outerAfter.balance(), outerAfter.version(),
                innerAfter.balance(), innerAfter.version(), entries);
        assertEquals(0, new BigDecimal("100.00").compareTo(outerAfter.balance()), "外层 +50 必须一并回滚");
        assertEquals(outerVersion, outerAfter.version());
        assertEquals(0, new BigDecimal("200.00").compareTo(innerAfter.balance()), "内层 +30 必须回滚");
        assertEquals(innerVersion, innerAfter.version());
        assertEquals(0L, entries, "内层失败不得残留流水");
    }

    @Test
    void nested_requires_new_failure_does_not_rollback_outer_commit() {
        String outer = newAccount("100.00");
        String inner = newAccount("200.00");
        long outerVersion = accountService.getAccount(outer).version();
        long innerVersion = accountService.getAccount(inner).version();
        String req = "REQ-" + UUID.randomUUID();

        nestedTxProbe.outerThenFailingRequiresNew(
                outer, outerVersion, inner, innerVersion, req);

        Account outerAfter = accountService.getAccount(outer);
        Account innerAfter = accountService.getAccount(inner);
        Long entries = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE request_id = ?", Long.class, req);
        log.info("判定依据(REQUIRES_NEW): 外层账户余额={}/版本={} 内层账户余额={}/版本={} 流水数={}（期望 150/1, 200/0, 0）",
                outerAfter.balance(), outerAfter.version(),
                innerAfter.balance(), innerAfter.version(), entries);
        assertEquals(0, new BigDecimal("150.00").compareTo(outerAfter.balance()), "外层 +50 应正常提交");
        assertEquals(outerVersion + 1, outerAfter.version());
        assertEquals(0, new BigDecimal("200.00").compareTo(innerAfter.balance()), "内层独立回滚，余额不变");
        assertEquals(innerVersion, innerAfter.version());
        assertEquals(0L, entries, "内层独立回滚不得残留流水");
    }

    @Test
    void failed_operations_return_connections_to_pool() {
        String no = newAccount("10.00");
        for (int i = 0; i < 30; i++) {
            try {
                accountRepository.applyBalanceChange(no, 0L, -100_000L);
            } catch (RuntimeException expected) {
                // 预期内异常：余额不足；底层连接必须仍被正常归还
            }
        }
        int active = hikariDataSource.getHikariPoolMXBean().getActiveConnections();
        int idle = hikariDataSource.getHikariPoolMXBean().getIdleConnections();
        int total = hikariDataSource.getHikariPoolMXBean().getTotalConnections();
        log.info("判定依据(连接池): 异常风暴后 active={} idle={} total={}", active, idle, total);
        assertEquals(0, active, "异常回滚后不得有连接被占用（泄漏）");
        assertTrue(total >= 1 && idle >= 1, "连接应被归还到池中");
        assertEquals(0, new BigDecimal("10.00").compareTo(accountService.getAccount(no).balance()));
    }
}
