package com.github.lechandonga.accountledgerjava.exception;

/**
 * 乐观锁冲突：账户版本已被其他事务推进，本次更新未命中任何行。
 * 调用方可据此重试或向客户端返回 409；不得当作成功处理，也不会产生任何部分写入。
 */
public class OptimisticLockConflictException extends LedgerException {
    public OptimisticLockConflictException(String accountNo, long expectedVersion) {
        super("OPTIMISTIC_LOCK_CONFLICT",
                "乐观锁冲突: accountNo=" + accountNo + ", expectedVersion=" + expectedVersion);
    }
}
