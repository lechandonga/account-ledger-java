package com.github.lechandonga.accountledgerjava.web;

import java.math.BigDecimal;

public record DepositRequest(
        String requestId,
        String accountNo,
        BigDecimal amount) {
}
