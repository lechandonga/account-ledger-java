package com.github.lechandonga.accountledgerjava.exception;

/** 账户不存在 */
public class AccountNotFoundException extends LedgerException {
    public AccountNotFoundException(String accountNo) {
        super("ACCOUNT_NOT_FOUND", "账户不存在: " + accountNo);
    }
}
