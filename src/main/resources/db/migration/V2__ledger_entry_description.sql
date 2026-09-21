-- 结构演进示例：新增可空列，对已有数据安全（无锁重写、无默认值回填）。
ALTER TABLE ledger_entry ADD COLUMN description VARCHAR(255) NULL;
