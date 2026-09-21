package com.github.lechandonga.accountledgerjava.service.impl;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.domain.Direction;
import com.github.lechandonga.accountledgerjava.domain.EntryType;
import com.github.lechandonga.accountledgerjava.domain.IdempotentRecord;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.LedgerOperationResult;
import com.github.lechandonga.accountledgerjava.domain.OperationStatus;
import com.github.lechandonga.accountledgerjava.domain.PageResult;
import com.github.lechandonga.accountledgerjava.exception.AccountNotFoundException;
import com.github.lechandonga.accountledgerjava.exception.IdempotentRequestConflictException;
import com.github.lechandonga.accountledgerjava.exception.InvalidRequestException;
import com.github.lechandonga.accountledgerjava.exception.LedgerException;
import com.github.lechandonga.accountledgerjava.exception.OptimisticLockConflictException;
import com.github.lechandonga.accountledgerjava.exception.PersistenceFailureException;
import com.github.lechandonga.accountledgerjava.repository.AccountRepository;
import com.github.lechandonga.accountledgerjava.repository.IdempotentRequestRepository;
import com.github.lechandonga.accountledgerjava.repository.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.service.LedgerService;
import com.github.lechandonga.accountledgerjava.support.Money;
import com.github.lechandonga.accountledgerjava.support.PageCursor;
import com.github.lechandonga.accountledgerjava.support.PersistenceExceptionTranslator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LedgerServiceImpl implements LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerServiceImpl.class);

    /** 等待首发请求完成的轮询间隔与超时（占位行被首发事务持有，终态与其业务数据同事务可见） */
    private static final long WAIT_SLEEP_MS = 5L;
    private static final long WAIT_TIMEOUT_MS = 10_000L;

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;
    private final IdempotentRequestRepository idempotentRequestRepository;
    private final OperationTxExecutor txExecutor;
    private final IdempotencySupport idempotencySupport;
    private final PersistenceExceptionTranslator exceptionTranslator;

    @Value("${ledger.page.default-size:20}")
    private int defaultPageSize;

    @Value("${ledger.page.max-size:100}")
    private int maxPageSize;

    public LedgerServiceImpl(AccountRepository accountRepository,
                             LedgerEntryRepository ledgerEntryRepository,
                             IdempotentRequestRepository idempotentRequestRepository,
                             OperationTxExecutor txExecutor,
                             IdempotencySupport idempotencySupport,
                             PersistenceExceptionTranslator exceptionTranslator) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
        this.idempotentRequestRepository = idempotentRequestRepository;
        this.txExecutor = txExecutor;
        this.idempotencySupport = idempotencySupport;
        this.exceptionTranslator = exceptionTranslator;
    }

    @Override
    public LedgerOperationResult deposit(String requestId, String accountNo, BigDecimal amount) {
        validateRequestId(requestId);
        validateAccountNo(accountNo, "accountNo");
        Money.requirePositive(amount);
        long amountCents = Money.toCents(amount);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountNo", accountNo);
        payload.put("amountCents", amountCents);
        String digest = idempotencySupport.digest("DEPOSIT", payload);

        return runIdempotent(requestId, "DEPOSIT", digest, () -> {
            Account account = requireAccount(accountNo);
            long newBalance = accountRepository.applyBalanceChange(
                    accountNo, account.version(), amountCents);
            LedgerEntry entry = ledgerEntryRepository.insert(
                    requestId, accountNo, null, Direction.CREDIT,
                    amountCents, newBalance, EntryType.DEPOSIT);
            log.info("存款入账 requestId={} accountNo={} amount={} prevVersion={} newBalance={}",
                    requestId, accountNo, amount, account.version(), Money.toYuan(newBalance));
            return LedgerOperationResult.success(requestId, List.of(entry));
        });
    }

    @Override
    public LedgerOperationResult withdraw(String requestId, String accountNo, BigDecimal amount) {
        validateRequestId(requestId);
        validateAccountNo(accountNo, "accountNo");
        Money.requirePositive(amount);
        long amountCents = Money.toCents(amount);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("accountNo", accountNo);
        payload.put("amountCents", amountCents);
        String digest = idempotencySupport.digest("WITHDRAW", payload);

        return runIdempotent(requestId, "WITHDRAW", digest, () -> {
            Account account = requireAccount(accountNo);
            long newBalance = accountRepository.applyBalanceChange(
                    accountNo, account.version(), -amountCents);
            LedgerEntry entry = ledgerEntryRepository.insert(
                    requestId, accountNo, null, Direction.DEBIT,
                    amountCents, newBalance, EntryType.WITHDRAW);
            log.info("取款入账 requestId={} accountNo={} amount={} prevVersion={} newBalance={}",
                    requestId, accountNo, amount, account.version(), Money.toYuan(newBalance));
            return LedgerOperationResult.success(requestId, List.of(entry));
        });
    }

    @Override
    public LedgerOperationResult transfer(String requestId, String fromAccountNo,
                                          String toAccountNo, BigDecimal amount) {
        validateRequestId(requestId);
        validateAccountNo(fromAccountNo, "fromAccountNo");
        validateAccountNo(toAccountNo, "toAccountNo");
        if (fromAccountNo.equals(toAccountNo)) {
            throw new InvalidRequestException("转出账户与转入账户不能相同");
        }
        Money.requirePositive(amount);
        long amountCents = Money.toCents(amount);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromAccountNo", fromAccountNo);
        payload.put("toAccountNo", toAccountNo);
        payload.put("amountCents", amountCents);
        String digest = idempotencySupport.digest("TRANSFER", payload);

        return runIdempotent(requestId, "TRANSFER", digest, () -> {
            // 固定顺序加锁，避免两个账户间并发转账时交叉持锁形成死锁
            String first = fromAccountNo.compareTo(toAccountNo) < 0 ? fromAccountNo : toAccountNo;
            String second = first.equals(fromAccountNo) ? toAccountNo : fromAccountNo;
            Account lockedFirst = requireAccount(first);
            Account lockedSecond = requireAccount(second);
            Account from = fromAccountNo.equals(first) ? lockedFirst : lockedSecond;
            Account to = toAccountNo.equals(first) ? lockedFirst : lockedSecond;

            long fromNewBalance = accountRepository.applyBalanceChange(
                    fromAccountNo, from.version(), -amountCents);
            long toNewBalance = accountRepository.applyBalanceChange(
                    toAccountNo, to.version(), amountCents);
            LedgerEntry out = ledgerEntryRepository.insert(
                    requestId, fromAccountNo, toAccountNo, Direction.DEBIT,
                    amountCents, fromNewBalance, EntryType.TRANSFER_OUT);
            LedgerEntry in = ledgerEntryRepository.insert(
                    requestId, toAccountNo, fromAccountNo, Direction.CREDIT,
                    amountCents, toNewBalance, EntryType.TRANSFER_IN);
            log.info("转账入账 requestId={} from={} to={} amount={} fromBalance={} toBalance={}",
                    requestId, fromAccountNo, toAccountNo, amount,
                    Money.toYuan(fromNewBalance), Money.toYuan(toNewBalance));
            return LedgerOperationResult.success(requestId, List.of(out, in));
        });
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<LedgerEntry> pageEntries(String accountNo, String cursor, Integer size) {
        if (accountNo != null && accountNo.isBlank()) {
            throw new InvalidRequestException("非法分页参数: accountNo 不能为空白");
        }
        int limit = resolvePageSize(size);
        long afterId = PageCursor.decodeOrZero(cursor);
        // 多取 1 条判断是否还有下一页，但不返回给调用方
        List<LedgerEntry> rows = ledgerEntryRepository.findPage(accountNo, afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        String nextCursor = hasMore && !rows.isEmpty()
                ? PageCursor.encode(rows.get(rows.size() - 1).id())
                : null;
        return new PageResult<>(List.copyOf(rows), nextCursor, hasMore);
    }

    // ---- 幂等协调（CLAIM 抢占 + 终态随业务事务提交 + 等待重放）----

    /** 乐观锁冲突可重试：业务事务回滚后用最新版本再试（同 requestId 占位仍归本线程持有） */
    private static final int MAX_OPTIMISTIC_RETRIES = 200;

    private LedgerOperationResult runIdempotent(String requestId, String requestType,
                                                String digest,
                                                OperationTxExecutor.LedgerOperation operation) {
        // 不做入口预读：直接尝试抢占，由 request_id 唯一键裁决首发/重复，
        // 这样终态可立即重放、他人处理中则等待，也不会被历史 CLAIMED 残留误导
        boolean claimed = false;
        try {
            txExecutor.claimInNewTransaction(requestId, requestType, digest);
            claimed = true;
        } catch (DuplicateKeyException e) {
            // 与并发首发者竞速失败，或首发早已落终态：读现有记录决定重放/等待
            IdempotentRecord record = idempotentRequestRepository.findByRequestId(requestId)
                    .orElseThrow(() -> new PersistenceFailureException(
                            "唯一键冲突但查不到幂等记录: " + requestId, e));
            if (record.status() != OperationStatus.CLAIMED) {
                return replay(requestId, digest, record);
            }
            return waitForTerminalAndReplay(requestId, digest);
        }

        int attempt = 0;
        while (true) {
            attempt++;
            try {
                return txExecutor.executeInNewTransaction(() -> {
                    LedgerOperationResult executed;
                    try {
                        executed = operation.execute();
                    } catch (LedgerException e) {
                        // 业务拒绝（如余额不足）是确定终态：在同一业务事务内落终态并随事务提交，
                        // 不产生任何余额/流水变更，重复请求将重放到一致的拒绝结果
                        if (e.getErrorCode().equals("INSUFFICIENT_FUNDS")) {
                            LedgerOperationResult rejected = LedgerOperationResult.rejected(
                                    requestId, e.getErrorCode(), e.getMessage());
                            txExecutor.completeTerminal(requestId, OperationStatus.REJECTED,
                                    idempotencySupport.serializeResult(rejected));
                            log.info("业务拒绝终态已登记 requestId={} code={}", requestId, e.getErrorCode());
                            return rejected;
                        }
                        throw e;
                    }
                    txExecutor.completeTerminal(requestId, OperationStatus.SUCCESS,
                            idempotencySupport.serializeResult(executed));
                    return executed;
                });
            } catch (RuntimeException e) {
                RuntimeException translated = exceptionTranslator.translate(e);
                boolean retryable = (translated instanceof OptimisticLockConflictException
                        || translated instanceof com.github.lechandonga.accountledgerjava
                                .exception.LockConflictException)
                        && attempt <= MAX_OPTIMISTIC_RETRIES;
                if (retryable) {
                    // 业务事务已回滚（余额/流水未落），占位行仍属本请求；退让后用新版本重试
                    log.info("并发冲突({})，使用最新版本重试 requestId={} attempt={}",
                            ((LedgerException) translated).getErrorCode(), requestId, attempt);
                    backoff(attempt);
                    continue;
                }
                if (claimed) {
                    // 非终态失败且不再重试：清理占位，允许后续相同请求重新发起
                    safeRelease(requestId);
                }
                throw translated;
            }
        }
    }

    private void backoff(int attempt) {
        try {
            long base = Math.min(20L, attempt);
            // 0~5ms 抖动，避免冲突线程同步重试再次撞锁
            long jitter = java.util.concurrent.ThreadLocalRandom.current().nextLong(6);
            Thread.sleep(base + jitter);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private LedgerOperationResult waitForTerminalAndReplay(String requestId, String digest) {
        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            IdempotentRecord record = idempotentRequestRepository.findByRequestId(requestId)
                    .orElseThrow(() -> new PersistenceFailureException(
                            "等待首发结果期间占位记录消失: " + requestId, null));
            if (record.status() != OperationStatus.CLAIMED) {
                log.info("首发完成，重放终态 requestId={} status={}", requestId, record.status());
                return replay(requestId, digest, record);
            }
            try {
                Thread.sleep(WAIT_SLEEP_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new PersistenceFailureException("等待首发结果时被中断: " + requestId, ie);
            }
        }
        throw new PersistenceFailureException(
                "等待首发请求处理超时（可能首发事务长时间未结束）: " + requestId, null);
    }

    private void safeRelease(String requestId) {
        try {
            txExecutor.releaseClaimInNewTransaction(requestId);
        } catch (RuntimeException releaseError) {
            log.error("清理幂等占位失败 requestId={}", requestId, releaseError);
        }
    }

    private LedgerOperationResult replay(String requestId, String expectedDigest, IdempotentRecord record) {
        if (!record.requestDigest().equals(expectedDigest)) {
            log.warn("幂等键被复用于不同请求 requestId={} storedType={}", requestId, record.requestType());
            throw new IdempotentRequestConflictException(requestId);
        }
        LedgerOperationResult replayed = idempotencySupport.deserializeResult(
                requestId, record.responseBody());
        log.info("幂等命中 requestId={} status={} entryCount={}",
                requestId, replayed.status(), replayed.entries().size());
        return replayed;
    }

    // ---- 校验与查询辅助 ----

    private Account requireAccount(String accountNo) {
        return accountRepository.findByAccountNo(accountNo)
                .orElseThrow(() -> new AccountNotFoundException(accountNo));
    }

    private void validateRequestId(String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw new InvalidRequestException("requestId 不能为空");
        }
        if (requestId.length() > 64) {
            throw new InvalidRequestException("requestId 长度不能超过 64");
        }
    }

    private void validateAccountNo(String accountNo, String field) {
        if (accountNo == null || accountNo.isBlank()) {
            throw new InvalidRequestException(field + " 不能为空");
        }
    }

    private int resolvePageSize(Integer size) {
        if (size == null) {
            return defaultPageSize;
        }
        if (size <= 0) {
            throw new InvalidRequestException("非法分页参数: size 必须为正整数");
        }
        if (size > maxPageSize) {
            throw new InvalidRequestException("非法分页参数: size 不能超过 " + maxPageSize);
        }
        return size;
    }
}
