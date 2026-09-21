package com.github.lechandonga.accountledgerjava.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;

/**
 * 账户聚合根。余额只允许通过流水驱动的 Service 变更，
 * version 字段用于乐观并发控制。
 */
@Entity
@Table(name = "account")
public class Account {

    @Id
    @Column(name = "id", length = 64)
    private String id;

    /** 余额，单位：分（避免浮点误差）。 */
    @Column(name = "balance", nullable = false)
    private long balance;

    /** 乐观锁版本号。 */
    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Account() {
    }

    public Account(String id, long balance, Instant createdAt) {
        this.id = id;
        this.balance = balance;
        this.createdAt = createdAt;
    }

    public String getId() {
        return id;
    }

    public long getBalance() {
        return balance;
    }

    public void setBalance(long balance) {
        this.balance = balance;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
