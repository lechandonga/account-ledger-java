package com.github.lechandonga.accountledgerjava.exception;

import java.math.BigDecimal;

/** 余额不足（可区分于并发冲突、参数错误等其他失败） */
public class InsufficientFundsException extends LedgerException {

    private final String accountNo;
    private final BigDecimal balance;
    private final BigDecimal requested;

    public InsufficientFundsException(String accountNo, BigDecimal balance, BigDecimal requested) {
        super("INSUFFICIENT_FUNDS",
                "余额不足: accountNo=" + accountNo + ", balance=" + balance + ", requested=" + requested);
        this.accountNo = accountNo;
        this.balance = balance;
        this.requested = requested;
    }

    public String getAccountNo() {
        return accountNo;
    }

    public BigDecimal getBalance() {
        return balance;
    }

    public BigDecimal getRequested() {
        return requested;
    }
}
