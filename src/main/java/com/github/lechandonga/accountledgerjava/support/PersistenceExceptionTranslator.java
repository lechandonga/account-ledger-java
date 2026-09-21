package com.github.lechandonga.accountledgerjava.support;

import com.github.lechandonga.accountledgerjava.exception.LedgerException;
import com.github.lechandonga.accountledgerjava.exception.LockConflictException;
import com.github.lechandonga.accountledgerjava.exception.PersistenceFailureException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.stereotype.Component;

/**
 * 把底层持久化异常（Spring DataAccessException 体系）统一翻译为
 * LedgerException 体系；底层异常仅作为 cause 保留，不直接透出接口层。
 *
 * <p>说明：DuplicateKeyException 在业务流程中被用于“账户号唯一 / 幂等键唯一”的判定，
 * 由服务层就地捕获处理；若逃逸到本翻译器，则按未预期持久化失败处理。</p>
 */
@Component
public class PersistenceExceptionTranslator {

    private static final Logger log = LoggerFactory.getLogger(PersistenceExceptionTranslator.class);

    /**
     * @return 应向外抛出的异常；若输入已经是 LedgerException 则原样返回
     */
    public RuntimeException translate(RuntimeException e) {
        if (e instanceof LedgerException le) {
            return le;
        }
        if (e instanceof DuplicateKeyException) {
            log.warn("未预期的唯一键冲突，按持久化失败处理", e);
            return new PersistenceFailureException("数据唯一键冲突，请检查请求", e);
        }
        if (e instanceof DeadlockLoserDataAccessException
                || e instanceof CannotAcquireLockException
                || (e instanceof QueryTimeoutException && isLockTimeout(e))) {
            // 死锁牺牲 / 锁超时：事务已回滚，可安全重试；单独归类为 LOCK_CONFLICT
            log.warn("数据库锁冲突（死锁/超时），事务已回滚: {}", e.getMessage());
            return new LockConflictException("数据库锁冲突，本次操作已整体回滚，请重试", e);
        }
        if (e instanceof ConcurrencyFailureException || e instanceof QueryTimeoutException) {
            log.warn("数据库并发/锁失败，事务已回滚", e);
            return new PersistenceFailureException("数据库繁忙（锁冲突/超时），本次操作已整体回滚，请重试", e);
        }
        if (e instanceof DataAccessException) {
            log.error("数据访问失败，事务已回滚", e);
            return new PersistenceFailureException("数据访问失败，本次操作已整体回滚", e);
        }
        return e;
    }

    private boolean isLockTimeout(RuntimeException e) {
        return e.getMessage() != null && e.getMessage().toLowerCase().contains("lock");
    }
}
