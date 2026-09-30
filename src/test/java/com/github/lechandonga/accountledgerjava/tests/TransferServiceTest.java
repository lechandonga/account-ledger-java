package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;
import com.github.lechandonga.accountledgerjava.error.DuplicateRequestException;
import com.github.lechandonga.accountledgerjava.error.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.error.LedgerException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerRequestRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.service.TransferResult;
import com.github.lechandonga.accountledgerjava.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨账户划转：原子性、双边流水、余额不足整体无痕、重复提交收敛、
 * 收款方缺失/非法金额明确失败、单边与划转请求号不串用。
 */
@SpringBootTest
class TransferServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceTest.class);

    @Autowired TransferService transferService;
    @Autowired AccountService accountService;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired LedgerRequestRepository ledgerRequestRepository;

    @BeforeEach
    void clean() {
        ledgerEntryRepository.deleteAll();
        ledgerRequestRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void transferMovesMoneyAndWritesOppositeEntriesForBothSides() {
        accountService.createAccount("ta", 100);
        accountService.createAccount("tb", 20);

        TransferResult r = transferService.transfer("ta", "tb", 30, "tx-1");

        long balanceA = accountService.findAccount("ta").orElseThrow().getBalance();
        long balanceB = accountService.findAccount("tb").orElseThrow().getBalance();
        List<LedgerEntry> sides = ledgerEntryRepository.findByRequestIdOrderByIdAsc("tx-1");
        LedgerEntry debit = ledgerEntryRepository.findByAccountIdAndRequestId("ta", "tx-1").orElseThrow();
        LedgerEntry credit = ledgerEntryRepository.findByAccountIdAndRequestId("tb", "tx-1").orElseThrow();
        log.info("transfer 30 ta->tb: balances ta={} tb={} (expect 70/50); sides={} "
                        + "debit(id={},after={}) credit(id={},after={}) result(fromAfter={},toAfter={})",
                balanceA, balanceB, sides.size(), debit.getId(), debit.getBalanceAfter(),
                credit.getId(), credit.getBalanceAfter(), r.fromBalanceAfter(), r.toBalanceAfter());

        // 判定依据：两边余额正确；同一请求号恰好两条流水，方向/金额/变动后余额齐全且不同
        assertEquals(70, balanceA);
        assertEquals(50, balanceB);
        assertEquals(2, sides.size());
        assertEquals(LedgerEntryType.DEBIT, debit.getType());
        assertEquals(LedgerEntryType.CREDIT, credit.getType());
        assertEquals(30, debit.getAmount());
        assertEquals(70, debit.getBalanceAfter());
        assertEquals(50, credit.getBalanceAfter());
        assertFalse(debit.getId().equals(credit.getId()));
        assertEquals(70, r.fromBalanceAfter());
        assertEquals(50, r.toBalanceAfter());
        assertFalse(r.replayed());
    }

    @Test
    void insufficientFundsRejectsWholeTransferWithoutAnyTrace() {
        accountService.createAccount("tc", 10);
        accountService.createAccount("td", 100);

        InsufficientFundsException ex = assertThrows(InsufficientFundsException.class,
                () -> transferService.transfer("tc", "td", 50, "tx-ins"));

        long balanceC = accountService.findAccount("tc").orElseThrow().getBalance();
        long balanceD = accountService.findAccount("td").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        long requests = ledgerRequestRepository.count();
        log.info("overdraw transfer tc->td 50 (balance 10): category={} balances=({},{}) entries={} requests={} "
                        + "(expect BUSINESS_REJECTION, 10/100, 0, 0)",
                ex.category(), balanceC, balanceD, entries, requests);

        // 判定依据：业务拒绝类别明确；两边余额、流水、请求登记均无残留
        assertEquals(LedgerException.Category.BUSINESS_REJECTION, ex.category());
        assertEquals(10, balanceC);
        assertEquals(100, balanceD);
        assertEquals(0, entries);
        assertEquals(0, requests);
    }

    @Test
    void duplicateTransferReturnsIdenticalFirstResultAndSinglePairOfEntries() {
        accountService.createAccount("te", 100);
        accountService.createAccount("tf", 0);

        TransferResult first = transferService.transfer("te", "tf", 40, "tx-dup");
        TransferResult second = transferService.transfer("te", "tf", 40, "tx-dup");

        long balanceE = accountService.findAccount("te").orElseThrow().getBalance();
        long balanceF = accountService.findAccount("tf").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        log.info("duplicate transfer -> first(replayed={},fromId={},toId={}) second(replayed={},fromId={},toId={}) "
                        + "balances=({},{}) entries={} (expect 60/40/2)",
                first.replayed(), first.fromEntryId(), first.toEntryId(),
                second.replayed(), second.fromEntryId(), second.toEntryId(),
                balanceE, balanceF, entries);

        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.fromEntryId(), second.fromEntryId());
        assertEquals(first.toEntryId(), second.toEntryId());
        assertEquals(first.fromBalanceAfter(), second.fromBalanceAfter());
        assertEquals(first.toBalanceAfter(), second.toBalanceAfter());
        assertEquals(60, balanceE);
        assertEquals(40, balanceF);
        assertEquals(2, entries);
    }

    @Test
    void concurrentDuplicateTransferConvergesToOneTransfer() throws Exception {
        accountService.createAccount("tg", 100);
        accountService.createAccount("th", 0);
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<TransferResult>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> transferService.transfer("tg", "th", 25, "tx-race"));
        }
        List<Future<TransferResult>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        List<TransferResult> results = new CopyOnWriteArrayList<>();
        for (Future<TransferResult> f : futures) {
            results.add(f.get());
        }
        long balanceG = accountService.findAccount("tg").orElseThrow().getBalance();
        long balanceH = accountService.findAccount("th").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        long distinctFromIds = results.stream().map(TransferResult::fromEntryId).distinct().count();
        long distinctToIds = results.stream().map(TransferResult::toEntryId).distinct().count();
        log.info("8 concurrent same transfer request -> distinctFromIds={} distinctToIds={} "
                        + "balances=({},{}) entries={} (expect 1/1, 75/25, 2)",
                distinctFromIds, distinctToIds, balanceG, balanceH, entries);

        assertEquals(1, distinctFromIds);
        assertEquals(1, distinctToIds);
        assertEquals(75, balanceG);
        assertEquals(25, balanceH);
        assertEquals(2, entries);
    }

    @Test
    void missingPayeeAndNonPositiveAmountAreExplicitArgumentFailures() {
        accountService.createAccount("ti", 100);

        LedgerException missing = assertThrows(LedgerException.class,
                () -> transferService.transfer("ti", "nope", 10, "tx-missing"));
        LedgerException zero = assertThrows(LedgerException.class,
                () -> transferService.transfer("ti", "nope2", 0, "tx-zero"));
        LedgerException negative = assertThrows(LedgerException.class,
                () -> transferService.transfer("ti", "nope3", -5, "tx-neg"));
        accountService.createAccount("tj", 100);
        LedgerException self = assertThrows(LedgerException.class,
                () -> transferService.transfer("ti", "ti", 10, "tx-self"));

        log.info("missing={} category={}; zero category={}; negative category={}; self category={}",
                missing.getMessage(), missing.category(), zero.category(), negative.category(), self.category());
        assertEquals(LedgerException.Category.INVALID_ARGUMENT, missing.category());
        assertEquals(LedgerException.Category.INVALID_ARGUMENT, zero.category());
        assertEquals(LedgerException.Category.INVALID_ARGUMENT, negative.category());
        assertEquals(LedgerException.Category.INVALID_ARGUMENT, self.category());
        assertEquals(0, ledgerEntryRepository.count());
        assertEquals(0, ledgerRequestRepository.count());
    }

    @Test
    void requestIdCannotCrossOperationKinds() {
        accountService.createAccount("tk", 100);
        accountService.createAccount("tl", 100);

        // 单边操作先占用请求号
        accountService.applyChange("tk", LedgerEntryType.DEBIT, 10, "shared-req");
        // 同请求号用于划转：按重复提交拒绝，不执行划转
        DuplicateRequestException asTransfer = assertThrows(DuplicateRequestException.class,
                () -> transferService.transfer("tk", "tl", 10, "shared-req"));

        // 划转占用另一个请求号后再用于单边：同样拒绝
        transferService.transfer("tk", "tl", 10, "shared-req-2");
        DuplicateRequestException asSingle = assertThrows(DuplicateRequestException.class,
                () -> accountService.applyChange("tl", LedgerEntryType.CREDIT, 10, "shared-req-2"));

        long balanceK = accountService.findAccount("tk").orElseThrow().getBalance();
        long balanceL = accountService.findAccount("tl").orElseThrow().getBalance();
        log.info("cross-kind reuse rejected: {} | {}; balances tk={} tl={} (expect 80/110)",
                asTransfer.getMessage(), asSingle.getMessage(), balanceK, balanceL);

        assertEquals(LedgerException.Category.DUPLICATE_REQUEST, asTransfer.category());
        assertEquals(LedgerException.Category.DUPLICATE_REQUEST, asSingle.category());
        // tk: 单边 10 + 划转 10 = 80；tl: 仅划转入账 = 110；串用请求均未生效
        assertEquals(80, balanceK);
        assertEquals(110, balanceL);
        // shared-req 1 条单边流水；shared-req-2 两条划转流水
        assertEquals(3, ledgerEntryRepository.count());
    }
}
