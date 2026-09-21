package com.github.lechandonga.accountledgerjava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * 资金流水。任何余额变动都必须有一条对应流水，
 * requestId 唯一约束用于识别重复提交（幂等）。
 */
@Entity
@Table(name = "ledger_entry")
public class LedgerEntry {

    /** 数据库自增主键，同时作为游标分页的稳定排序键。 */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "account_id", nullable = false, length = 64)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 16)
    private LedgerEntryType type;

    /** 变动金额（分），恒为正数，方向由 type 表达。 */
    @Column(name = "amount", nullable = false)
    private long amount;

    /** 变动后余额快照，便于审计与对账。 */
    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    /** 业务请求唯一标识（幂等键），数据库唯一约束。 */
    @Column(name = "request_id", nullable = false, length = 128)
    private String requestId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected LedgerEntry() {
    }

    public LedgerEntry(String accountId, LedgerEntryType type, long amount,
                       long balanceAfter, String requestId, Instant createdAt) {
        this.accountId = accountId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.requestId = requestId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getAccountId() {
        return accountId;
    }

    public LedgerEntryType getType() {
        return type;
    }

    public long getAmount() {
        return amount;
    }

    public long getBalanceAfter() {
        return balanceAfter;
    }

    public String getRequestId() {
        return requestId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
