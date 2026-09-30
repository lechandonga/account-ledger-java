-- V3：支持跨账户划转。
-- 一次划转在两个账户各产生一条流水，但业务上只对应一个请求号，
-- 因此 request_id 的唯一约束从 ledger_entry 单列，
-- 迁移为“请求登记表唯一 + 流水表 (account_id, request_id) 唯一”。
-- 只追加式变更：老数据先登记后换约束，单边流水与新结构完全兼容。

-- 1. 请求登记表：每个业务请求号恰好一行，记录操作种类（SINGLE_CHANGE / TRANSFER）。
CREATE TABLE ledger_request (
    id          VARCHAR(128) NOT NULL PRIMARY KEY,
    op_kind     VARCHAR(16)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT chk_ledger_request_kind CHECK (op_kind IN ('SINGLE_CHANGE', 'TRANSFER'))
);

-- 2. 把历史流水的请求号登记为单边操作（一个请求号一行）。
INSERT INTO ledger_request (id, op_kind, created_at)
SELECT request_id, 'SINGLE_CHANGE', MIN(created_at)
FROM ledger_entry
GROUP BY request_id;

-- 3. 流水表改为按 (账户, 请求号) 唯一：划转的两条流水请求号相同、账户不同。
ALTER TABLE ledger_entry DROP CONSTRAINT uk_ledger_request_id;
ALTER TABLE ledger_entry
    ADD CONSTRAINT uk_ledger_account_request UNIQUE (account_id, request_id);

-- 4. 流水请求号必须引用登记请求，保证“先登记后记账”与请求种类可核对。
ALTER TABLE ledger_entry
    ADD CONSTRAINT fk_ledger_request FOREIGN KEY (request_id) REFERENCES ledger_request (id);

-- 5. 取值范围兜底：流水方向只能是入账/出账；变动后余额快照不得为负。
ALTER TABLE ledger_entry
    ADD CONSTRAINT chk_ledger_type_valid CHECK (type IN ('CREDIT', 'DEBIT'));
ALTER TABLE ledger_entry
    ADD CONSTRAINT chk_ledger_balance_after_nonnegative CHECK (balance_after >= 0);
