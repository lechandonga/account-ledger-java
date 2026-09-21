package com.github.lechandonga.accountledgerjava.service.impl;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.exception.AccountNotFoundException;
import com.github.lechandonga.accountledgerjava.exception.InvalidRequestException;
import com.github.lechandonga.accountledgerjava.repository.AccountRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.support.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class AccountServiceImpl implements AccountService {

    private final AccountRepository accountRepository;

    public AccountServiceImpl(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    @Override
    @Transactional
    public Account createAccount(String accountNo, String accountName, BigDecimal initialBalance, String currency) {
        if (accountNo == null || accountNo.isBlank()) {
            throw new InvalidRequestException("accountNo 不能为空");
        }
        if (accountName == null || accountName.isBlank()) {
            throw new InvalidRequestException("accountName 不能为空");
        }
        if (initialBalance == null || initialBalance.signum() < 0) {
            throw new InvalidRequestException("初始余额不能为空且不能为负");
        }
        Money.toCents(initialBalance); // 精度校验
        String ccy = (currency == null || currency.isBlank()) ? "CNY" : currency;
        return accountRepository.create(accountNo, accountName, initialBalance, ccy);
    }

    @Override
    public Account getAccount(String accountNo) {
        if (accountNo == null || accountNo.isBlank()) {
            throw new InvalidRequestException("accountNo 不能为空");
        }
        return accountRepository.findByAccountNo(accountNo)
                .orElseThrow(() -> new AccountNotFoundException(accountNo));
    }
}
