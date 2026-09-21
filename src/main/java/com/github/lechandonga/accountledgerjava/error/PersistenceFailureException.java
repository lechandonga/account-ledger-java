package com.github.lechandonga.accountledgerjava.error;

/**
 * 底层持久化异常的脱敏包装。保留 cause 便于排查，
 * 但对外只暴露统一类别，不透出 SQL/驱动细节。
 */
public class PersistenceFailureException extends LedgerException {

    public PersistenceFailureException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public Category category() {
        return Category.PERSISTENCE_FAILURE;
    }
}
