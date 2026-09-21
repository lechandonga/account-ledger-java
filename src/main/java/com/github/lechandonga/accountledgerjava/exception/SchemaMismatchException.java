package com.github.lechandonga.accountledgerjava.exception;

/** 实际数据库结构/约束与预期不一致（校验失败，拒绝静默继续） */
public class SchemaMismatchException extends LedgerException {
    public SchemaMismatchException(String message) {
        super("SCHEMA_MISMATCH", message);
    }
}
