package com.github.lechandonga.accountledgerjava.web;

import java.math.BigDecimal;

public record WithdrawRequest(
        String requestId,
        String accountNo,
        BigDecimal amount) {
}
