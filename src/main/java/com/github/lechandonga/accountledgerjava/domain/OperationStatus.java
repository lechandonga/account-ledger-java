package com.github.lechandonga.accountledgerjava.domain;

/** 幂等请求状态：CLAIMED 处理中占位；SUCCESS/REJECTED 为终态 */
public enum OperationStatus {
    CLAIMED,
    SUCCESS,
    REJECTED
}
