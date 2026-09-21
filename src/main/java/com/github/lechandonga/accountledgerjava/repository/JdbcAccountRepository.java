package com.github.lechandonga.accountledgerjava.repository;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.exception.AccountAlreadyExistsException;
import com.github.lechandonga.accountledgerjava.exception.AccountNotFoundException;
import com.github.lechandonga.accountledgerjava.exception.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.exception.OptimisticLockConflictException;
import com.github.lechandonga.accountledgerjava.support.Money;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcAccountRepository implements AccountRepository {

    private static final String COLUMN_LIST =
            "id, account_no, account_name, balance_cents, currency, version, created_at, updated_at";

    private static final RowMapper<Account> ROW_MAPPER = (rs, rowNum) -> new Account(
            rs.getLong("id"),
            rs.getString("account_no"),
            rs.getString("account_name"),
            Money.toYuan(rs.getLong("balance_cents")),
            rs.getString("currency"),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public JdbcAccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Account create(String accountNo, String accountName, BigDecimal initialBalance, String currency) {
        long initialCents = Money.toCents(initialBalance);
        String sql = "INSERT INTO accounts (account_no, account_name, balance_cents, currency) "
                + "VALUES (?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(con -> {
                PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
                ps.setString(1, accountNo);
                ps.setString(2, accountName);
                ps.setLong(3, initialCents);
                ps.setString(4, currency);
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            throw new AccountAlreadyExistsException(accountNo);
        }
        long id = keyHolder.getKey().longValue();
        return findByAccountNo(accountNo).orElseThrow();
    }

    @Override
    public Optional<Account> findByAccountNo(String accountNo) {
        String sql = "SELECT " + COLUMN_LIST + " FROM accounts WHERE account_no = ?";
        List<Account> rows = jdbcTemplate.query(sql, ROW_MAPPER, accountNo);
        return rows.stream().findFirst();
    }

    /**
     * 乐观锁条件更新：同时带 version 与“扣减后不为负”两个条件。
     * 0 行命中时先在当前事务内重读，依据重读结果区分“余额不足”与“版本冲突”，
     * 两个失败原因必须可区分。账户行存在更新锁，故 0 行命中不会来自锁等待。
     */
    @Override
    public long applyBalanceChange(String accountNo, long expectedVersion, long deltaCents) {
        String sql = "UPDATE accounts "
                + "SET balance_cents = balance_cents + ?, version = version + 1, updated_at = CURRENT_TIMESTAMP "
                + "WHERE account_no = ? AND version = ? AND balance_cents + ? >= 0";
        int updated = jdbcTemplate.update(sql, deltaCents, accountNo, expectedVersion, deltaCents);
        if (updated == 1) {
            Long newBalance = jdbcTemplate.queryForObject(
                    "SELECT balance_cents FROM accounts WHERE account_no = ?", Long.class, accountNo);
            return newBalance;
        }
        Account current = findByAccountNo(accountNo)
                .orElseThrow(() -> new AccountNotFoundException(accountNo));
        if (current.version() != expectedVersion) {
            throw new OptimisticLockConflictException(accountNo, expectedVersion);
        }
        BigDecimal balanceYuan = current.balance();
        throw new InsufficientFundsException(accountNo, balanceYuan,
                Money.toYuan(Math.abs(deltaCents)));
    }
}
