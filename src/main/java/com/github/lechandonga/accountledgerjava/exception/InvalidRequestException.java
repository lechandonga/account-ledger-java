package com.github.lechandonga.accountledgerjava.exception;

/** 非法入参（含非法分页参数、非法金额等） */
public class InvalidRequestException extends LedgerException {
    public InvalidRequestException(String message) {
        super("INVALID_REQUEST", message);
    }
}
