package com.github.lechandonga.accountledgerjava.service;

import com.github.lechandonga.accountledgerjava.domain.Account;

import java.math.BigDecimal;

/** 账户与余额操作服务（事务边界在本层） */
public interface AccountService {

    Account createAccount(String accountNo, String accountName, BigDecimal initialBalance, String currency);

    Account getAccount(String accountNo);
}
