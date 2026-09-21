package com.github.lechandonga.accountledgerjava.exception;

/**
 * 数据库锁冲突（死锁牺牲者 / 锁等待超时）。
 * 事务已被数据库回滚；与乐观锁冲突一样属于“可安全重试”的瞬时失败，
 * 也与业务拒绝（余额不足）明确区分。
 */
public class LockConflictException extends LedgerException {
    public LockConflictException(String message, Throwable cause) {
        super("LOCK_CONFLICT", message, cause);
    }
}
