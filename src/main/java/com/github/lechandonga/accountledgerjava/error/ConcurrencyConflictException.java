package com.github.lechandonga.accountledgerjava.error;

/** 乐观锁版本冲突：同一账户被并发修改。调用方可安全重试。 */
public class ConcurrencyConflictException extends LedgerException {

    public ConcurrencyConflictException(String accountId, Throwable cause) {
        super("concurrent modification on account=" + accountId, cause);
    }

    @Override
    public Category category() {
        return Category.CONCURRENCY_CONFLICT;
    }
}
