package com.github.lechandonga.accountledgerjava.service;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;

/**
 * 一次余额变动的对外结果。replayed=true 表示这是重复提交，
 * 返回的是首次请求落库的结果，未产生新流水。
 */
public record ChangeResult(
        long entryId,
        String accountId,
        LedgerEntryType type,
        long amount,
        long balanceAfter,
        String requestId,
        boolean replayed) {
}
