package com.github.lechandonga.accountledgerjava.domain;

/** 幂等请求记录（不可变视图） */
public record IdempotentRecord(
        Long id,
        String requestId,
        String requestType,
        String requestDigest,
        OperationStatus status,
        String responseBody) {
}
