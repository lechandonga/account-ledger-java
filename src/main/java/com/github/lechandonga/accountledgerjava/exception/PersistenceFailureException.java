package com.github.lechandonga.accountledgerjava.exception;

/** 未归类的底层持久化失败（底层异常作为 cause 保留，不直接向外透出） */
public class PersistenceFailureException extends LedgerException {
    public PersistenceFailureException(String message, Throwable cause) {
        super("PERSISTENCE_FAILURE", message, cause);
    }
}
