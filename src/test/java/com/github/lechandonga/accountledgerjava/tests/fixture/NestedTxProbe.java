package com.github.lechandonga.accountledgerjava.tests.fixture;

import com.github.lechandonga.accountledgerjava.exception.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.repository.AccountRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 外层服务（跨 Bean 调用内层，确保事务代理生效）。
 */
@Component
public class NestedTxProbe {

    private final AccountRepository accountRepository;
    private final InnerTxService innerTxService;

    public NestedTxProbe(AccountRepository accountRepository, InnerTxService innerTxService) {
        this.accountRepository = accountRepository;
        this.innerTxService = innerTxService;
    }

    /**
     * REQUIRED 场景：外层更新 outerAccount（+50），内层加入同一事务更新 innerAccount（+30）
     * 后抛异常 -> 两个账户的变更整体回滚。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void outerThenFailingRequired(String outerAccount, long outerVersion,
                                         String innerAccount, long innerVersion,
                                         String requestId) {
        accountRepository.applyBalanceChange(outerAccount, outerVersion, 5000L);
        innerTxService.requiredFails(innerAccount, innerVersion, requestId);
    }

    /**
     * REQUIRES_NEW 场景：外层更新 outerAccount（+50），内层在独立事务中更新
     * innerAccount（+30）后抛异常 -> 内层回滚（innerAccount 不变），外层 +50 正常提交。
     */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void outerThenFailingRequiresNew(String outerAccount, long outerVersion,
                                            String innerAccount, long innerVersion,
                                            String requestId) {
        accountRepository.applyBalanceChange(outerAccount, outerVersion, 5000L);
        try {
            innerTxService.requiresNewFails(innerAccount, innerVersion, requestId);
        } catch (InsufficientFundsException expected) {
            // 内层独立事务已回滚；外层事务不受影响
        }
    }
}
