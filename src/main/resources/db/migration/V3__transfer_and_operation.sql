-- V3：支持跨账户划转，并把"业务操作"从流水行中独立出来。
-- 背景：一次划转在两个账户各产生一条方向相反的流水（两条行共享同一个 request_id），
-- 旧的 ledger_entry.uk_ledger_request_id（request_id 全局唯一）无法表达。
-- 兼容策略：
--   * 只追加结构，不删除任何业务列；老的单边流水全部回填 operation_kind='CHANGE'；
--   * 新增 ledger_operation 以 request_id 为主键，承载操作类型（CHANGE/TRANSFER）
--     与双方账户，保证"单边加减与划转共用同一请求号命名空间"；
--   * 流水侧唯一约束从 (request_id) 放宽为 (request_id, account_id)，
--     操作幂等的最终防线收敛到 ledger_operation 的主键；
--   * 所有回填都从已有数据派生，默认值兜底，不覆盖、不删除老数据。

-- 业务操作表：一个业务请求号恰好一行
CREATE TABLE ledger_operation (
    request_id        VARCHAR(128) NOT NULL PRIMARY KEY,
    kind              VARCHAR(16)  NOT NULL,
    source_account_id VARCHAR(64)  NULL,
    target_account_id VARCHAR(64)  NULL,
    amount            BIGINT       NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_operation_kind_valid CHECK (kind IN ('CHANGE', 'TRANSFER')),
    CONSTRAINT chk_operation_amount_positive CHECK (amount > 0),
    CONSTRAINT fk_operation_source_account FOREIGN KEY (source_account_id) REFERENCES account (id),
    CONSTRAINT fk_operation_target_account FOREIGN KEY (target_account_id) REFERENCES account (id)
);

-- 回填：旧结构下 request_id 全局唯一，每个请求号只对应一条流水。
-- 单边 DEBIT 记为 source，单边 CREDIT 记为 target，便于按方向还原。
INSERT INTO ledger_operation (request_id, kind, source_account_id, target_account_id, amount, created_at)
SELECT request_id,
       'CHANGE',
       CASE WHEN MIN(type) = 'DEBIT'  THEN MIN(account_id) END,
       CASE WHEN MIN(type) = 'CREDIT' THEN MIN(account_id) END,
       MIN(amount),
       MIN(created_at)
FROM ledger_entry
GROUP BY request_id;

-- 流水补充操作类型列：老数据全部视为单边操作
ALTER TABLE ledger_entry ADD COLUMN operation_kind VARCHAR(16) NOT NULL DEFAULT 'CHANGE';
ALTER TABLE ledger_entry ADD CONSTRAINT chk_ledger_operation_kind_valid
    CHECK (operation_kind IN ('CHANGE', 'TRANSFER'));

-- 补齐 V1 缺失的方向取值范围约束
ALTER TABLE ledger_entry ADD CONSTRAINT chk_ledger_type_valid
    CHECK (type IN ('CREDIT', 'DEBIT'));

-- 一个请求在同一个账户上至多一条流水；划转的两条行分属两个账户，不再冲突
ALTER TABLE ledger_entry DROP CONSTRAINT uk_ledger_request_id;
ALTER TABLE ledger_entry ADD CONSTRAINT uk_ledger_request_account
    UNIQUE (request_id, account_id);
