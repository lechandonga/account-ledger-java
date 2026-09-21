package com.github.lechandonga.accountledgerjava.web;

import java.time.OffsetDateTime;

/** 统一错误响应体 */
public record ApiError(
        String code,
        String message,
        OffsetDateTime time) {
}
