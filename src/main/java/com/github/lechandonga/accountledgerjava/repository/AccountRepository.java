package com.github.lechandonga.accountledgerjava.repository;

import com.github.lechandonga.accountledgerjava.domain.Account;

import java.math.BigDecimal;
import java.util.Optional;

/** 账户仓储 */
public interface AccountRepository {

    /** 新建账户；账户号重复时抛出 AccountAlreadyExistsException */
    Account create(String accountNo, String accountName, BigDecimal initialBalance, String currency);

    Optional<Account> findByAccountNo(String accountNo);

    /**
     * 基于版本号的条件更新（乐观锁）。
     *
     * @return 更新后的新余额；未命中任何行时抛出 OptimisticLockConflictException
     */
    long applyBalanceChange(String accountNo, long expectedVersion, long deltaCents);
}
