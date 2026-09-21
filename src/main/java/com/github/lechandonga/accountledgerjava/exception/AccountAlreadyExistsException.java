package com.github.lechandonga.accountledgerjava.exception;

/** 账户号已存在 */
public class AccountAlreadyExistsException extends LedgerException {
    public AccountAlreadyExistsException(String accountNo) {
        super("ACCOUNT_ALREADY_EXISTS", "账户号已存在: " + accountNo);
    }
}
