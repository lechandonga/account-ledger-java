package com.github.lechandonga.accountledgerjava.service;

import com.github.lechandonga.accountledgerjava.db.SchemaVerifier;
import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;
import com.github.lechandonga.accountledgerjava.domain.LedgerOperation;
import com.github.lechandonga.accountledgerjava.domain.LedgerOperationKind;
import com.github.lechandonga.accountledgerjava.error.ConcurrencyConflictException;
import com.github.lechandonga.accountledgerjava.error.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.error.PersistenceFailureException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerOperationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 跨账户划转服务。核心约束：
 * <ul>
 *   <li>一个划转在单事务内完成：校验双方账户与余额 → 更新两个余额 →
 *       写 1 行操作 + 2 行方向相反的流水，任何一步失败整体回滚；</li>
 *   <li>两个账户一律按 accountId 升序锁定（更新），双向并发互转加锁顺序一致，
 *       不会互相死等；乐观锁版本冲突或锁超时统一映射为可重试冲突；</li>
 *   <li>requestId 命名空间与单边加减共用（ledger_operation 主键），
 *       重复提交（含并发、跨操作类型）返回首次结果，不重复划转。</li>
 * </ul>
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerOperationRepository ledgerOperationRepository;
    private final AccountService accountService;
    private final SchemaVerifier schemaVerifier;
    private final TransactionTemplate txTemplate;

    public TransferService(AccountRepository accountRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           LedgerOperationRepository ledgerOperationRepository,
                           AccountService accountService,
                           SchemaVerifier schemaVerifier,
                           PlatformTransactionManager transactionManager) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.ledgerOperationRepository = ledgerOperationRepository;
        this.accountService = accountService;
        this.schemaVerifier = schemaVerifier;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.txTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 从 source 向 target 划转 amount（分，必须为正）。
     * 幂等：同一 requestId 重复到达（包括并发重复、与单边加减交叉复用）
     * 返回与首次一致的结果，不会重复划转。
     */
    public TransferResult transfer(String sourceAccountId, String targetAccountId,
                                   long amount, String requestId) {
        if (amount <= 0) {
            throw AccountService.invalidArgument("transfer amount must be positive: " + amount);
        }
        if (sourceAccountId == null || targetAccountId == null) {
            throw AccountService.invalidArgument("source and target account id are required");
        }
        if (sourceAccountId.equals(targetAccountId)) {
            throw AccountService.invalidArgument(
                    "transfer source and target must differ: " + sourceAccountId);
        }
        schemaVerifier.verify();

        Optional<LedgerOperation> existing = ledgerOperationRepository.findById(requestId);
        if (existing.isPresent()) {
            return replayTransfer(existing.get());
        }
        try {
            return txTemplate.execute(tx ->
                    doTransfer(sourceAccountId, targetAccountId, amount, requestId));
        } catch (InsufficientFundsException e) {
            throw e;
        } catch (OptimisticLockingFailureException | PessimisticLockingFailureException e) {
            Optional<LedgerOperation> winner = ledgerOperationRepository.findById(requestId);
            if (winner.isPresent()) {
                log.info("transfer conflict resolved as replay requestId={}", requestId);
                return replayTransfer(winner.get());
            }
            log.info("transfer conflict {}->{} requestId={}: {}",
                    sourceAccountId, targetAccountId, requestId, e.getClass().getSimpleName());
            throw new ConcurrencyConflictException(sourceAccountId + "," + targetAccountId, e);
        } catch (DataIntegrityViolationException e) {
            // 并发重复提交（驱动可能抛 DuplicateKey 或更宽泛的完整性违规）：整体回滚后重读首次结果。
            return replayAfterRace(requestId, e);
        } catch (DataAccessException e) {
            if (isLockFailure(e)) {
                Optional<LedgerOperation> winner = ledgerOperationRepository.findById(requestId);
                if (winner.isPresent()) {
                    log.info("transfer lock failure resolved as replay requestId={}", requestId);
                    return replayTransfer(winner.get());
                }
                log.info("transfer lock failure {}->{} requestId={}: {}",
                        sourceAccountId, targetAccountId, requestId, e.getClass().getSimpleName());
                throw new ConcurrencyConflictException(sourceAccountId + "," + targetAccountId, e);
            }
            throw new PersistenceFailureException(
                    "failed to transfer " + amount + " from " + sourceAccountId
                            + " to " + targetAccountId, e);
        }
    }

    private TransferResult doTransfer(String sourceAccountId, String targetAccountId,
                                      long amount, String requestId) {
        Account source = accountRepository.findById(sourceAccountId)
                .orElseThrow(() -> AccountService.invalidArgument(
                        "source account not found: " + sourceAccountId));
        Account target = accountRepository.findById(targetAccountId)
                .orElseThrow(() -> AccountService.invalidArgument(
                        "target account not found: " + targetAccountId));
        if (source.getBalance() < amount) {
            throw new InsufficientFundsException(sourceAccountId, source.getBalance(), amount);
        }

        long sourceAfter = source.getBalance() - amount;
        long targetAfter = Math.addExact(target.getBalance(), amount);

        // 统一按 accountId 升序更新：双向互转时两个事务持锁顺序一致，不会循环等待。
        List<Account> ordered = new ArrayList<>(List.of(source, target));
        ordered.sort(Comparator.comparing(Account::getId));
        for (Account account : ordered) {
            long newBalance = account.getId().equals(sourceAccountId) ? sourceAfter : targetAfter;
            account.setBalance(newBalance);
            accountRepository.saveAndFlush(account);
        }

        Instant now = Instant.now();
        ledgerOperationRepository.saveAndFlush(new LedgerOperation(
                requestId, LedgerOperationKind.TRANSFER,
                sourceAccountId, targetAccountId, amount, now));

        LedgerEntry debit = ledgerEntryRepository.saveAndFlush(new LedgerEntry(
                sourceAccountId, LedgerEntryType.DEBIT, amount, sourceAfter,
                requestId, LedgerOperationKind.TRANSFER, now));
        LedgerEntry credit = ledgerEntryRepository.saveAndFlush(new LedgerEntry(
                targetAccountId, LedgerEntryType.CREDIT, amount, targetAfter,
                requestId, LedgerOperationKind.TRANSFER, now));

        log.info("transfer {}->{} amount={} balances {}->{}, {}->{} requestId={} entries=({},{})",
                sourceAccountId, targetAccountId, amount,
                source.getBalance(), sourceAfter, target.getBalance(), targetAfter,
                requestId, debit.getId(), credit.getId());

        return new TransferResult(requestId, toResult(debit), toResult(credit), false);
    }

    private TransferResult replayAfterRace(String requestId, RuntimeException e) {
        return ledgerOperationRepository.findById(requestId)
                .map(op -> {
                    log.info("transfer replay-after-race requestId={}", requestId);
                    return replayTransfer(op);
                })
                .orElseThrow(() -> new PersistenceFailureException(
                        "integrity violation without matching requestId " + requestId, e));
    }

    private TransferResult replayTransfer(LedgerOperation operation) {
        if (operation.getKind() != LedgerOperationKind.TRANSFER) {
            throw AccountService.invalidArgument("requestId " + operation.getRequestId()
                    + " was already used by a CHANGE and cannot be reused for a TRANSFER");
        }
        List<LedgerEntry> legs =
                ledgerEntryRepository.findByRequestIdOrderByIdAsc(operation.getRequestId());
        LedgerEntry debit = legs.stream()
                .filter(e -> e.getType() == LedgerEntryType.DEBIT).findFirst()
                .orElseThrow(() -> new PersistenceFailureException(
                        "transfer operation " + operation.getRequestId() + " missing debit leg", null));
        LedgerEntry credit = legs.stream()
                .filter(e -> e.getType() == LedgerEntryType.CREDIT).findFirst()
                .orElseThrow(() -> new PersistenceFailureException(
                        "transfer operation " + operation.getRequestId() + " missing credit leg", null));
        log.info("transfer replay requestId={} entries=({},{})",
                operation.getRequestId(), debit.getId(), credit.getId());
        return new TransferResult(operation.getRequestId(),
                toReplayedResult(debit), toReplayedResult(credit), true);
    }

    private ChangeResult toResult(LedgerEntry entry) {
        return new ChangeResult(entry.getId(), entry.getAccountId(), entry.getType(),
                entry.getAmount(), entry.getBalanceAfter(), entry.getRequestId(), false);
    }

    private ChangeResult toReplayedResult(LedgerEntry entry) {
        return new ChangeResult(entry.getId(), entry.getAccountId(), entry.getType(),
                entry.getAmount(), entry.getBalanceAfter(), entry.getRequestId(), true);
    }

    private boolean isLockFailure(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            if (t instanceof CannotAcquireLockException
                    || t instanceof PessimisticLockingFailureException
                    || t instanceof OptimisticLockingFailureException) {
                return true;
            }
            String name = t.getClass().getName().toLowerCase();
            String message = t.getMessage() == null ? "" : t.getMessage().toLowerCase();
            if (name.contains("deadlock") || name.contains("locktimeout")
                    || message.contains("deadlock") || message.contains("lock timeout")) {
                return true;
            }
        }
        return false;
    }

    /** 暴露单边服务，便于调用方在同一入口下复用。 */
    public AccountService getAccountService() {
        return accountService;
    }
}
