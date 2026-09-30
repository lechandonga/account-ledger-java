package com.github.lechandonga.accountledgerjava.service;

/**
 * 一次跨账户划转的对外结果：转出/转入两侧各一条流水结果，共享同一 requestId。
 * replayed=true 表示重复提交，返回的是首次划转落库的两条流水。
 */
public record TransferResult(
        String requestId,
        ChangeResult debitLeg,
        ChangeResult creditLeg,
        boolean replayed) {

    public long sourceBalanceAfter() {
        return debitLeg.balanceAfter();
    }

    public long targetBalanceAfter() {
        return creditLeg.balanceAfter();
    }
}
