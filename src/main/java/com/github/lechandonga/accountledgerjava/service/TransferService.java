package com.github.lechandonga.accountledgerjava.service;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;
import com.github.lechandonga.accountledgerjava.domain.LedgerRequest;
import com.github.lechandonga.accountledgerjava.domain.OperationKind;
import com.github.lechandonga.accountledgerjava.error.ConcurrencyConflictException;
import com.github.lechandonga.accountledgerjava.error.DuplicateRequestException;
import com.github.lechandonga.accountledgerjava.error.LedgerException;
import com.github.lechandonga.accountledgerjava.error.PersistenceFailureException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * 跨账户划转服务。一笔划转在单个事务内完成：登记请求 →
 * 转出方扣减 → 转入方增加 → 两边各写一条方向相反的流水。
 * 任何一步失败整体回滚，不存在只扣不出或只进不扣。
 *
 * <p>防死锁：无论划转方向如何，两个账户的更新一律按账户 id 升序
 * 获取行锁/触发版本校验，因此双向同时互转不会互相等待形成环。
 * 落败一方得到乐观锁冲突（可安全重试），而不是死锁回滚。
 */
@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final LedgerRequestRepository ledgerRequestRepository;
    private final TransactionTemplate txTemplate;

    public TransferService(AccountRepository accountRepository,
                           LedgerEntryRepository ledgerEntryRepository,
                           LedgerRequestRepository ledgerRequestRepository,
                           TransactionTemplate txTemplate) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.ledgerRequestRepository = ledgerRequestRepository;
        this.txTemplate = txTemplate;
    }

    /**
     * 从 fromAccountId 划转 amount 到 toAccountId（幂等）。
     * 余额不足、账户不存在、金额非正、自转均明确失败且不留任何痕迹；
     * 同一 requestId 重复到达返回与首次一致的结果。
     */
    public TransferResult transfer(String fromAccountId, String toAccountId, long amount, String requestId) {
        validateInput(fromAccountId, toAccountId, amount);

        LedgerRequest existing = ledgerRequestRepository.findById(requestId).orElse(null);
        if (existing != null) {
            return handleRegistered(existing, fromAccountId, toAccountId, amount);
        }
        try {
            return txTemplate.execute(tx ->
                    doTransfer(fromAccountId, toAccountId, amount, requestId));
        } catch (OptimisticLockingFailureException | CannotAcquireLockException e) {
            log.info("transfer conflict from={} to={} requestId={}", fromAccountId, toAccountId, requestId);
            throw new ConcurrencyConflictException(fromAccountId + "->" + toAccountId, e);
        } catch (DataIntegrityViolationException e) {
            // 并发重复提交撞上 ledger_request 主键：本事务已回滚，回放首次结果。
            LedgerRequest winner = ledgerRequestRepository.findById(requestId)
                    .orElseThrow(() -> new PersistenceFailureException(
                            "integrity violation without matching requestId " + requestId, e));
            log.info("transfer replay-after-race requestId={}", requestId);
            return handleRegistered(winner, fromAccountId, toAccountId, amount);
        } catch (InsufficientFundsPassthrough e) {
            throw e.toException(fromAccountId);
        } catch (DataAccessException e) {
            throw new PersistenceFailureException(
                    "failed to transfer " + amount + " from " + fromAccountId + " to " + toAccountId, e);
        }
    }

    private void validateInput(String fromAccountId, String toAccountId, long amount) {
        if (amount <= 0) {
            throw invalidArgument("transfer amount must be positive: " + amount);
        }
        if (fromAccountId == null || fromAccountId.isBlank()) {
            throw invalidArgument("fromAccountId must not be blank");
        }
        if (toAccountId == null || toAccountId.isBlank()) {
            throw invalidArgument("toAccountId must not be blank");
        }
        if (fromAccountId.equals(toAccountId)) {
            throw invalidArgument("cannot transfer to the same account: " + fromAccountId);
        }
        if (accountRepository.findById(fromAccountId).isEmpty()) {
            throw invalidArgument("from account not found: " + fromAccountId);
        }
        if (accountRepository.findById(toAccountId).isEmpty()) {
            throw invalidArgument("to account not found: " + toAccountId);
        }
    }

    private LedgerException invalidArgument(String message) {
        log.info("transfer rejected: {}", message);
        return new LedgerException(message) {
            @Override
            public Category category() {
                return Category.INVALID_ARGUMENT;
            }
        };
    }

    /** 已登记请求：同种类回放首次结果；跨种类（单边/划转串用）按重复提交拒绝。 */
    private TransferResult handleRegistered(LedgerRequest registered,
                                            String fromAccountId, String toAccountId, long amount) {
        if (registered.getOpKind() != OperationKind.TRANSFER) {
            log.info("requestId={} already registered as {} for a different operation kind",
                    registered.getId(), registered.getOpKind());
            throw new DuplicateRequestException(registered.getId(), registered.getOpKind());
        }
        List<LedgerEntry> entries = ledgerEntryRepository.findByRequestIdOrderByIdAsc(registered.getId());
        LedgerEntry debit = entries.stream()
                .filter(e -> e.getAccountId().equals(fromAccountId) && e.getType() == LedgerEntryType.DEBIT)
                .findFirst()
                .orElseThrow(() -> new PersistenceFailureException(
                        "registered transfer missing debit side for requestId " + registered.getId(), null));
        LedgerEntry credit = entries.stream()
                .filter(e -> e.getAccountId().equals(toAccountId) && e.getType() == LedgerEntryType.CREDIT)
                .findFirst()
                .orElseThrow(() -> new PersistenceFailureException(
                        "registered transfer missing credit side for requestId " + registered.getId(), null));
        if (debit.getAmount() != amount || credit.getAmount() != amount) {
            // 请求号相同但参数与首次不一致：不执行任何新操作，明确拒绝。
            log.info("transfer replay parameter mismatch requestId={} submittedAmount={} firstAmount={}",
                    registered.getId(), amount, debit.getAmount());
            throw new DuplicateRequestException(registered.getId(), OperationKind.TRANSFER);
        }
        log.info("transfer replay requestId={} fromEntryId={} toEntryId={}",
                registered.getId(), debit.getId(), credit.getId());
        return new TransferResult(registered.getId(), fromAccountId, toAccountId, amount,
                debit.getId(), credit.getId(), debit.getBalanceAfter(), credit.getBalanceAfter(), true);
    }

    private TransferResult doTransfer(String fromAccountId, String toAccountId, long amount, String requestId) {
        // 先登记：主键冲突让并发重复请求在写任何余额/流水之前失败回滚。
        ledgerRequestRepository.saveAndFlush(
                new LedgerRequest(requestId, OperationKind.TRANSFER, Instant.now()));

        Account from = accountRepository.findById(fromAccountId)
                .orElseThrow(() -> new PersistenceFailureException("account not found: " + fromAccountId, null));
        Account to = accountRepository.findById(toAccountId)
                .orElseThrow(() -> new PersistenceFailureException("account not found: " + toAccountId, null));

        if (from.getBalance() < amount) {
            // 事务整体回滚：登记、余额、流水全部无痕。
            throw new InsufficientFundsPassthrough(from.getBalance(), amount);
        }

        // 按账户 id 定序更新：双向互转也以相同顺序申请锁，杜绝死锁环。
        Account first = fromAccountId.compareTo(toAccountId) < 0 ? from : to;
        Account second = first == from ? to : from;
        applySide(first, first == from, amount);
        applySide(second, second == from, amount);

        Instant now = Instant.now();
        LedgerEntry debit = ledgerEntryRepository.saveAndFlush(new LedgerEntry(
                fromAccountId, LedgerEntryType.DEBIT, amount, from.getBalance(), requestId, now));
        LedgerEntry credit = ledgerEntryRepository.saveAndFlush(new LedgerEntry(
                toAccountId, LedgerEntryType.CREDIT, amount, to.getBalance(), requestId, now));

        log.info("transfer from={} to={} amount={} fromBalanceAfter={} toBalanceAfter={} "
                        + "requestId={} fromEntryId={} toEntryId={}",
                fromAccountId, toAccountId, amount, from.getBalance(), to.getBalance(),
                requestId, debit.getId(), credit.getId());
        return new TransferResult(requestId, fromAccountId, toAccountId, amount,
                debit.getId(), credit.getId(), debit.getBalanceAfter(), credit.getBalanceAfter(), false);
    }

    private void applySide(Account account, boolean debitSide, long amount) {
        long newBalance = debitSide ? account.getBalance() - amount : Math.addExact(account.getBalance(), amount);
        account.setBalance(newBalance);
        // 立即 flush：按统一顺序触发行锁与 version 校验，冲突在本事务内明确暴露。
        accountRepository.saveAndFlush(account);
    }

    /** 事务lambda内抛出的余额不足信号，出事务后转换为标准业务异常。 */
    private static class InsufficientFundsPassthrough extends RuntimeException {
        private final long balance;
        private final long requested;

        InsufficientFundsPassthrough(long balance, long requested) {
            this.balance = balance;
            this.requested = requested;
        }

        com.github.lechandonga.accountledgerjava.error.InsufficientFundsException toException(String accountId) {
            return new com.github.lechandonga.accountledgerjava.error.InsufficientFundsException(
                    accountId, balance, requested);
        }
    }
}
