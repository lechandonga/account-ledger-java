package com.github.lechandonga.accountledgerjava.domain;

/** 业务请求种类：单边加减 / 跨账户划转。同一 requestId 不允许跨种类复用。 */
public enum OperationKind {
    SINGLE_CHANGE,
    TRANSFER
}
