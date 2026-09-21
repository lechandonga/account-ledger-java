package com.github.lechandonga.accountledgerjava.web;

import java.math.BigDecimal;

public record TransferRequest(
        String requestId,
        String fromAccountNo,
        String toAccountNo,
        BigDecimal amount) {
}
