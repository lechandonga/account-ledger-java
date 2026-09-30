package com.github.lechandonga.accountledgerjava.error;

/**
 * 数据访问层对外暴露的唯一受检语义基类。
 * 底层持久化异常一律不得直接透出，必须映射为本类或其子类。
 */
public abstract class LedgerException extends RuntimeException {

    protected LedgerException(String message) {
        super(message);
    }

    protected LedgerException(String message, Throwable cause) {
        super(message, cause);
    }

    /** 对外可区分的失败类别。 */
    public abstract Category category();

    public enum Category {
        /** 余额不足等业务规则拒绝。 */
        BUSINESS_REJECTION,
        /** 乐观锁冲突，可安全重试。 */
        CONCURRENCY_CONFLICT,
        /** 非法参数（如非法游标/分页大小）。 */
        INVALID_ARGUMENT,
        /** 请求号已被另一种操作占用，按重复提交拒绝，不执行新操作。 */
        DUPLICATE_REQUEST,
        /** 底层持久化故障（连接、约束、超时等），已脱敏包装。 */
        PERSISTENCE_FAILURE
    }
}
