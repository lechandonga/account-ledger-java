-- V1: 账户、资金流水、幂等请求 初始结构
-- 金额一律使用 BIGINT 存储“分”，杜绝浮点误差；应用层以 BigDecimal 换算

CREATE TABLE accounts (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    account_no    VARCHAR(64)  NOT NULL,
    account_name  VARCHAR(128) NOT NULL,
    balance_cents BIGINT       NOT NULL,
    version       BIGINT       NOT NULL DEFAULT 0,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_accounts PRIMARY KEY (id),
    CONSTRAINT uq_accounts_account_no UNIQUE (account_no),
    CONSTRAINT chk_accounts_balance_non_negative CHECK (balance_cents >= 0)
);

CREATE TABLE ledger_entries (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    request_id       VARCHAR(64)  NOT NULL,
    account_no       VARCHAR(64)  NOT NULL,
    counterpart_no    VARCHAR(64),
    direction        VARCHAR(8)   NOT NULL,
    amount_cents     BIGINT       NOT NULL,
    balance_after    BIGINT       NOT NULL,
    entry_type       VARCHAR(16)  NOT NULL,
    entry_time       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
    CONSTRAINT uq_ledger_entries_request_account_direction
        UNIQUE (request_id, account_no, direction),
    CONSTRAINT chk_ledger_amount_positive CHECK (amount_cents > 0),
    CONSTRAINT chk_ledger_balance_after_non_negative CHECK (balance_after >= 0),
    CONSTRAINT chk_ledger_direction CHECK (direction IN ('DEBIT','CREDIT'))
);

CREATE TABLE idempotent_requests (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    request_id     VARCHAR(64)  NOT NULL,
    request_type   VARCHAR(32)  NOT NULL,
    request_digest VARCHAR(128) NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    response_body  CLOB,
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_idempotent_requests PRIMARY KEY (id),
    CONSTRAINT uq_idempotent_requests_request_id UNIQUE (request_id),
    CONSTRAINT chk_idempotent_status CHECK (status IN ('SUCCESS','REJECTED'))
);
