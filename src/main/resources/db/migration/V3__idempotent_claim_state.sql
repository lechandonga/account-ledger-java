-- V3: 幂等记录增加“处理中”占位态。
-- 同 requestId 的并发请求先插入 CLAIMED 行抢占行锁：
--   抢注成功者在独立业务事务内完成余额变动+流水，事务提交后把行更新为终态；
--   抢注失败者阻塞/重读，待终态出现后原样重放。终态与业务数据同事务提交，无悬空窗口。
ALTER TABLE idempotent_requests ADD COLUMN finished_at TIMESTAMP NULL;
ALTER TABLE idempotent_requests DROP CONSTRAINT chk_idempotent_status;
ALTER TABLE idempotent_requests ADD CONSTRAINT chk_idempotent_status
    CHECK (status IN ('SUCCESS','REJECTED','CLAIMED'));
