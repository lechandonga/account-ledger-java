package com.github.lechandonga.accountledgerjava.error;

/** 非法分页参数（负游标、越界页大小等）。 */
public class InvalidPaginationException extends LedgerException {

    public InvalidPaginationException(String message) {
        super(message);
    }

    @Override
    public Category category() {
        return Category.INVALID_ARGUMENT;
    }
}
