package com.github.lechandonga.accountledgerjava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 业务请求登记。每个 requestId 恰好一行，先登记后记账：
 * 主键唯一约束保证同一请求（含并发重复）最多被执行一次；
 * opKind 记录请求种类，防止单边操作与划转共用请求号。
 */
@Entity
@Table(name = "ledger_request")
public class LedgerRequest {

    @Id
    @Column(name = "id", length = 128)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "op_kind", nullable = false, length = 16)
    private OperationKind opKind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerRequest() {
    }

    public LedgerRequest(String id, OperationKind opKind, Instant createdAt) {
        this.id = id;
        this.opKind = opKind;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public OperationKind getOpKind() {
        return opKind;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
