package com.github.lechandonga.accountledgerjava.exception;

/**
 * 数据访问层对外暴露的统一异常基类。
 * 底层持久化异常一律转换为本体系下的子类，不直接透出到接口层。
 */
public abstract class LedgerException extends RuntimeException {

    private final String errorCode;

    protected LedgerException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    protected LedgerException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
