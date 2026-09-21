package com.github.lechandonga.accountledgerjava.exception;

/** 同一幂等键被用于语义不同的请求体 */
public class IdempotentRequestConflictException extends LedgerException {
    public IdempotentRequestConflictException(String requestId) {
        super("IDEMPOTENT_REQUEST_CONFLICT",
                "幂等键冲突: requestId=" + requestId + " 已绑定不同的请求内容");
    }
}
