package com.github.lechandonga.accountledgerjava.service;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;
import com.github.lechandonga.accountledgerjava.error.ConcurrencyConflictException;
import com.github.lechandonga.accountledgerjava.error.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.error.LedgerException;
import com.github.lechandonga.accountledgerjava.error.PersistenceFailureException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

/**
 * 账户余额服务。核心约束：
 * <ul>
 *   <li>余额变动与流水写入在同一事务内，要么同时落库要么整体回滚；</li>
 *   <li>乐观锁（@Version）防止并发丢失更新，冲突映射为可区分的异常；</li>
 *   <li>requestId 唯一约束 + 先查后写实现幂等，重复提交返回首次结果。</li>
 * </ul>
 */
@Service
public class AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountService.class);

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final TransactionTemplate txTemplate;

    public AccountService(AccountRepository accountRepository,
                          LedgerEntryRepository ledgerEntryRepository,
                          TransactionTemplate txTemplate) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.txTemplate = txTemplate;
    }

    /** 创建账户。重复 id 映射为持久化失败（可区分），不静默覆盖。 */
    public Account createAccount(String accountId, long initialBalance) {
        if (initialBalance < 0) {
            throw new LedgerException("initial balance must be >= 0: " + initialBalance) {
                @Override
                public Category category() {
                    return Category.INVALID_ARGUMENT;
                }
            };
        }
        try {
            Account saved = accountRepository.saveAndFlush(new Account(accountId, initialBalance, Instant.now()));
            log.info("createAccount account={} initialBalance={}", accountId, initialBalance);
            return saved;
        } catch (DataAccessException e) {
            throw new PersistenceFailureException("failed to create account " + accountId, e);
        }
    }

    public Optional<Account> findAccount(String accountId) {
        return accountRepository.findById(accountId);
    }

    /**
     * 应用一次余额变动（幂等）。同一 requestId 重复到达时返回首次结果，
     * 不会重复扣减或产生第二条流水。
     */
    public ChangeResult applyChange(String accountId, LedgerEntryType type, long amount, String requestId) {
        if (amount <= 0) {
            throw new LedgerException("amount must be positive: " + amount) {
                @Override
                public Category category() {
                    return Category.INVALID_ARGUMENT;
                }
            };
        }
        // 快路径：已处理过的请求直接返回首次结果（只读，无需事务写）。
        Optional<LedgerEntry> existing = ledgerEntryRepository.findByRequestId(requestId);
        if (existing.isPresent()) {
            log.info("applyChange replay requestId={} entryId={}", requestId, existing.get().getId());
            return toResult(existing.get(), true);
        }
        try {
            return txTemplate.execute(tx -> doApplyChange(accountId, type, amount, requestId));
        } catch (OptimisticLockingFailureException e) {
            // 并发修改同一账户：版本冲突，事务已整体回滚，调用方可重试。
            log.info("applyChange conflict account={} requestId={}", accountId, requestId);
            throw new ConcurrencyConflictException(accountId, e);
        } catch (DataIntegrityViolationException e) {
            // 并发重复提交撞上 request_id 唯一约束：本事务回滚，返回首次结果。
            return ledgerEntryRepository.findByRequestId(requestId)
                    .map(entry -> {
                        log.info("applyChange replay-after-race requestId={} entryId={}", requestId, entry.getId());
                        return toResult(entry, true);
                    })
                    .orElseThrow(() -> new PersistenceFailureException(
                            "integrity violation without matching requestId " + requestId, e));
        } catch (InsufficientFundsException e) {
            throw e;
        } catch (DataAccessException e) {
            throw new PersistenceFailureException("failed to apply change on account " + accountId, e);
        }
    }

    /** 事务内：余额变动与流水写入同生共死。 */
    private ChangeResult doApplyChange(String accountId, LedgerEntryType type, long amount, String requestId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new PersistenceFailureException("account not found: " + accountId, null));

        long newBalance = switch (type) {
            case CREDIT -> Math.addExact(account.getBalance(), amount);
            case DEBIT -> {
                if (account.getBalance() < amount) {
                    throw new InsufficientFundsException(accountId, account.getBalance(), amount);
                }
                yield account.getBalance() - amount;
            }
        };

        account.setBalance(newBalance);
        accountRepository.save(account); // 乐观锁在 flush 时校验 version

        LedgerEntry entry = new LedgerEntry(accountId, type, amount, newBalance, requestId, Instant.now());
        LedgerEntry saved = ledgerEntryRepository.saveAndFlush(entry);

        log.info("applyChange account={} type={} amount={} balanceAfter={} requestId={} entryId={}",
                accountId, type, amount, newBalance, requestId, saved.getId());
        return toResult(saved, false);
    }

    private ChangeResult toResult(LedgerEntry entry, boolean replayed) {
        return new ChangeResult(entry.getId(), entry.getAccountId(), entry.getType(),
                entry.getAmount(), entry.getBalanceAfter(), entry.getRequestId(), replayed);
    }
}
