package com.github.lechandonga.accountledgerjava.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 账户实体（不可变视图） */
public record Account(
        Long id,
        String accountNo,
        String accountName,
        BigDecimal balance,
        String currency,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
