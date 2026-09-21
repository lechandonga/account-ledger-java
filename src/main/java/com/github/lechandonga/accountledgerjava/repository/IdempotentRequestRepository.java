package com.github.lechandonga.accountledgerjava.repository;

import com.github.lechandonga.accountledgerjava.domain.IdempotentRecord;
import com.github.lechandonga.accountledgerjava.domain.OperationStatus;

import java.util.Optional;

/** 幂等请求仓储（唯一键 request_id） */
public interface IdempotentRequestRepository {

    Optional<IdempotentRecord> findByRequestId(String requestId);

    /**
     * 抢占 requestId：插入一条 CLAIMED 占位记录（应在独立短事务中调用）。
     * 唯一键冲突时抛 DuplicateKeyException，由协调层决定等待重放还是报错。
     */
    void claim(String requestId, String requestType, String requestDigest);

    /**
     * 抢占成功后，在业务事务内把占位行更新为终态（与余额/流水同事务提交）。
     */
    void completeTerminal(String requestId, OperationStatus status, String responseBody);

    /** 业务事务因非终态异常回滚后，清理可能残留的 CLAIMED 占位行（独立事务执行）。 */
    void releaseClaim(String requestId);
}
