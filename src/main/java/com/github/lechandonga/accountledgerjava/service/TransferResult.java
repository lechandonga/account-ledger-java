package com.github.lechandonga.accountledgerjava.service;

/**
 * 一次跨账户划转的对外结果。两边各有一条流水与变动后余额快照，
 * 共享同一个 requestId。replayed=true 表示重复提交返回首次结果。
 */
public record TransferResult(
        String requestId,
        String fromAccountId,
        String toAccountId,
        long amount,
        long fromEntryId,
        long toEntryId,
        long fromBalanceAfter,
        long toBalanceAfter,
        boolean replayed) {
}
