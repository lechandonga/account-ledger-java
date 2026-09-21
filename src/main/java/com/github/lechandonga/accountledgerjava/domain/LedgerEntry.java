package com.github.lechandonga.accountledgerjava.domain;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 资金流水实体（不可变视图） */
public record LedgerEntry(
        Long id,
        String requestId,
        String accountNo,
        String counterpartNo,
        Direction direction,
        BigDecimal amount,
        BigDecimal balanceAfter,
        EntryType entryType,
        LocalDateTime entryTime) {
}
