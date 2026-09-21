package com.github.lechandonga.accountledgerjava.service.impl;

import com.github.lechandonga.accountledgerjava.domain.LedgerOperationResult;
import com.github.lechandonga.accountledgerjava.domain.OperationStatus;
import com.github.lechandonga.accountledgerjava.repository.IdempotentRequestRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 幂等协调涉及的三类独立事务执行器。
 *
 * <p>独立成 Bean 的原因：{@code @Transactional} 基于代理生效，同类内部自调用会绕过代理，
 * 导致事务/回滚语义静默失效。</p>
 *
 * <p>全部使用 REQUIRES_NEW：每个方法都开启全新、独立的物理事务，
 * 任何失败都只回滚该事务本身，不污染调用方事务，杜绝部分提交。</p>
 */
@Component
public class OperationTxExecutor {

    private final IdempotentRequestRepository idempotentRequestRepository;

    public OperationTxExecutor(IdempotentRequestRepository idempotentRequestRepository) {
        this.idempotentRequestRepository = idempotentRequestRepository;
    }

    @FunctionalInterface
    public interface LedgerOperation {
        LedgerOperationResult execute();
    }

    /** 短事务：抢占 requestId（CLAIMED 占位）。唯一键冲突随异常回滚本短事务。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void claimInNewTransaction(String requestId, String requestType, String digest) {
        idempotentRequestRepository.claim(requestId, requestType, digest);
    }

    /**
     * 业务事务：余额变动 + 流水写入 + 幂等占位行置终态，同一事务原子提交。
     * 任何异常整体回滚（余额、流水、终态更新全部撤销）。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public LedgerOperationResult executeInNewTransaction(LedgerOperation operation) {
        return operation.execute();
    }

    /** 短事务：业务事务非终态回滚后，删除残留的 CLAIMED 占位行。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void releaseClaimInNewTransaction(String requestId) {
        idempotentRequestRepository.releaseClaim(requestId);
    }

    /** 供业务事务内部调用（同事务内把占位行置为终态）。 */
    public void completeTerminal(String requestId, OperationStatus status, String responseBody) {
        idempotentRequestRepository.completeTerminal(requestId, status, responseBody);
    }
}
