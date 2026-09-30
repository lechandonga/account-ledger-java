package com.github.lechandonga.accountledgerjava.error;

import com.github.lechandonga.accountledgerjava.domain.OperationKind;

/**
 * 请求号已被另一种操作使用（如单边操作的 requestId 又用于划转）。
 * 按重复提交处理：明确拒绝本次请求，绝不执行第二种语义。
 */
public class DuplicateRequestException extends LedgerException {

    private final String requestId;
    private final OperationKind existingKind;

    public DuplicateRequestException(String requestId, OperationKind existingKind) {
        super("requestId already used by a different operation kind: requestId="
                + requestId + " existingKind=" + existingKind);
        this.requestId = requestId;
        this.existingKind = existingKind;
    }

    public String getRequestId() {
        return requestId;
    }

    public OperationKind getExistingKind() {
        return existingKind;
    }

    @Override
    public Category category() {
        return Category.DUPLICATE_REQUEST;
    }
}
