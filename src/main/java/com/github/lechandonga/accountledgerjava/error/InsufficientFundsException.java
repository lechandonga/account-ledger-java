package com.github.lechandonga.accountledgerjava.error;

/** 扣减时余额不足，业务规则拒绝。 */
public class InsufficientFundsException extends LedgerException {

    private final String accountId;
    private final long balance;
    private final long requested;

    public InsufficientFundsException(String accountId, long balance, long requested) {
        super("insufficient funds: account=" + accountId
                + " balance=" + balance + " requested=" + requested);
        this.accountId = accountId;
        this.balance = balance;
        this.requested = requested;
    }

    public String getAccountId() {
        return accountId;
    }

    public long getBalance() {
        return balance;
    }

    public long getRequested() {
        return requested;
    }

    @Override
    public Category category() {
        return Category.BUSINESS_REJECTION;
    }
}
