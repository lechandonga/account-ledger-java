package com.github.lechandonga.accountledgerjava.web;

import java.math.BigDecimal;

public record CreateAccountRequest(
        String accountNo,
        String accountName,
        BigDecimal initialBalance,
        String currency) {
}
