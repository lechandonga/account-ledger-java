package com.github.lechandonga.accountledgerjava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 业务操作：一个业务请求号恰好一行，是请求级幂等的最终防线。
 * <ul>
 *   <li>{@link LedgerOperationKind#CHANGE} 单边加减：source（DEBIT）或 target（CREDIT）之一有值；</li>
 *   <li>{@link LedgerOperationKind#TRANSFER} 跨账户划转：两个账户都有值，
 *       流水侧各有一条方向相反的行共享同一 requestId。</li>
 * </ul>
 */
@Entity
@Table(name = "ledger_operation")
public class LedgerOperation {

    @Id
    @Column(name = "request_id", length = 128)
    private String requestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private LedgerOperationKind kind;

    @Column(name = "source_account_id", length = 64)
    private String sourceAccountId;

    @Column(name = "target_account_id", length = 64)
    private String targetAccountId;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerOperation() {
    }

    public LedgerOperation(String requestId, LedgerOperationKind kind,
                           String sourceAccountId, String targetAccountId,
                           long amount, Instant createdAt) {
        this.requestId = requestId;
        this.kind = kind;
        this.sourceAccountId = sourceAccountId;
        this.targetAccountId = targetAccountId;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public String getRequestId() {
        return requestId;
    }

    public LedgerOperationKind getKind() {
        return kind;
    }

    public String getSourceAccountId() {
        return sourceAccountId;
    }

    public String getTargetAccountId() {
        return targetAccountId;
    }

    public long getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
