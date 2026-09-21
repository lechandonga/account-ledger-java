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

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 基础业务流：建户、存款、取款、余额不足拒绝。
 * 日志打印关键输入与判定依据。
 */
@SpringBootTest
class LedgerBasicFlowTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerBasicFlowTest.class);

    @Autowired
    private AccountService accountService;
    @Autowired
    private LedgerService ledgerService;

    private String newAccount(String initial) {
        String no = "ACC-" + UUID.randomUUID();
        accountService.createAccount(no, "测试账户", new BigDecimal(initial), "CNY");
        return no;
    }

    @Test
    void deposit_and_withdraw_change_balance_driven_by_entries() {
        String no = newAccount("100.00");
        String req1 = "REQ-" + UUID.randomUUID();
        String req2 = "REQ-" + UUID.randomUUID();

        LedgerOperationResult deposit = ledgerService.deposit(req1, no, new BigDecimal("50.00"));
        LedgerOperationResult withdraw = ledgerService.withdraw(req2, no, new BigDecimal("30.00"));

        Account account = accountService.getAccount(no);
        log.info("判定依据: requestId={} 存款流水数={}, requestId={} 取款流水数={}, 最终余额={}",
                req1, deposit.entries().size(), req2, withdraw.entries().size(), account.balance());
        assertEquals(0, new BigDecimal("120.00").compareTo(account.balance()));
        assertEquals(1, deposit.entries().size());
        assertEquals(1, withdraw.entries().size());
        assertEquals(2L, account.version());
    }

    @Test
    void withdraw_over_balance_is_rejected_with_distinguishable_code() {
        String no = newAccount("10.00");
        String req = "REQ-" + UUID.randomUUID();
        log.info("关键输入: accountNo={} 余额=10.00 请求取款=20.00 requestId={}", no, req);

        LedgerOperationResult result = ledgerService.withdraw(req, no, new BigDecimal("20.00"));

        Account account = accountService.getAccount(no);
        log.info("判定依据: status={} rejectCode={} 拒绝后余额={} 版本={}",
                result.status(), result.rejectCode(), account.balance(), account.version());
        assertEquals(OperationStatus.REJECTED, result.status());
        assertEquals("INSUFFICIENT_FUNDS", result.rejectCode());
        assertEquals(0, new BigDecimal("10.00").compareTo(account.balance()), "拒绝不得改动余额");
        assertEquals(0L, account.version(), "拒绝不得推进版本号");
    }
}
