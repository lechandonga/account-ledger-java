package com.github.lechandonga.accountledgerjava.error;

/** 实际数据库结构/约束与迁移预期不一致（Flyway validate 失败等）。 */
public class SchemaMismatchException extends LedgerException {

    public SchemaMismatchException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public Category category() {
        return Category.PERSISTENCE_FAILURE;
    }
}
