-- V2: 结构演进示例（对已有数据安全、可重复执行）
-- 1) 账户增加币种列：先加列（带默认值回填存量行），再补约束
ALTER TABLE accounts ADD COLUMN currency VARCHAR(3) NOT NULL DEFAULT 'CNY';
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_currency CHECK (currency IN ('CNY','USD'));

-- 2) 账户维度游标分页索引（IF NOT EXISTS 保证重复执行不报错）
CREATE INDEX IF NOT EXISTS idx_ledger_account_id ON ledger_entries (account_no, id);
