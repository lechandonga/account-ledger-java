package com.github.lechandonga.accountledgerjava.domain;

import java.util.List;

/**
 * 余额操作结果。replay=true 表示命中幂等记录，结果与首次完全一致，未产生新流水。
 */
public record LedgerOperationResult(
        OperationStatus status,
        boolean replay,
        String requestId,
        String rejectCode,
        String rejectReason,
        List<LedgerEntry> entries) {

    public static LedgerOperationResult success(String requestId, List<LedgerEntry> entries) {
        return new LedgerOperationResult(OperationStatus.SUCCESS, false, requestId, null, null, entries);
    }

    public static LedgerOperationResult rejected(String requestId, String code, String reason) {
        return new LedgerOperationResult(OperationStatus.REJECTED, false, requestId, code, reason, List.of());
    }
}
