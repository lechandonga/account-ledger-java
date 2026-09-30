package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;
import com.github.lechandonga.accountledgerjava.error.ConcurrencyConflictException;
import com.github.lechandonga.accountledgerjava.error.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.error.LedgerException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerOperationRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.service.LedgerService;
import com.github.lechandonga.accountledgerjava.service.TransferResult;
import com.github.lechandonga.accountledgerjava.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 跨账户划转：原子双流水、余额不足整体无痕、幂等（含跨操作类型）、
 * 双向并发不丢钱不死锁、流水按账户可查。
 */
@SpringBootTest
class TransferServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TransferServiceTest.class);

    @Autowired
    TransferService transferService;
    @Autowired
    AccountService accountService;
    @Autowired
    LedgerService ledgerService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    LedgerOperationRepository ledgerOperationRepository;

    @BeforeEach
    void clean() {
        ledgerEntryRepository.deleteAll();
        ledgerOperationRepository.deleteAll();
        accountRepository.deleteAll();
    }

    private void accounts(String... balances) {
        for (String spec : balances) {
            String[] parts = spec.split("=");
            accountRepository.save(new Account(parts[0], Long.parseLong(parts[1]), Instant.now()));
        }
    }

    @Test
    void transferUpdatesBothBalancesAndWritesTwoOppositeEntries() {
        accounts("ta=100", "tb=200");

        TransferResult result = transferService.transfer("ta", "tb", 30, "req-tr-1");

        long balA = accountRepository.findById("ta").orElseThrow().getBalance();
        long balB = accountRepository.findById("tb").orElseThrow().getBalance();
        long totalEntries = ledgerEntryRepository.count();
        log.info("transfer 30 ta->tb -> balances ta={} tb={} (expect 70/230), entries={}, legs=({}, {})",
                balA, balB, totalEntries, result.debitLeg().entryId(), result.creditLeg().entryId());

        assertEquals(70, balA);
        assertEquals(230, balB);
        assertEquals(2, totalEntries);
        assertFalse(result.replayed());
        assertEquals("ta", result.debitLeg().accountId());
        assertEquals("tb", result.creditLeg().accountId());
        assertEquals(LedgerEntryType.DEBIT, result.debitLeg().type());
        assertEquals(LedgerEntryType.CREDIT, result.creditLeg().type());
        assertEquals(70, result.sourceBalanceAfter());
        assertEquals(230, result.targetBalanceAfter());
        assertNotEquals(result.debitLeg().entryId(), result.creditLeg().entryId());
    }

    @Test
    void eachAccountSeesOnlyItsOwnLegWhenPagingLedger() {
        accounts("la=100", "lb=50");

        TransferResult result = transferService.transfer("la", "lb", 40, "req-page-1");

        List<LedgerEntry> legA = ledgerService.page("la", null, 50).entries();
        List<LedgerEntry> legB = ledgerService.page("lb", null, 50).entries();
        List<LedgerEntry> byRequest = ledgerEntryRepository.findByRequestIdOrderByIdAsc("req-page-1");
        log.info("per-account legs: la sees {} entry (id={}), lb sees {} entry (id={}); request lookup -> {} entries",
                legA.size(), legA.get(0).getId(), legB.size(), legB.get(0).getId(), byRequest.size());

        assertEquals(1, legA.size());
        assertEquals(1, legB.size());
        assertEquals(result.debitLeg().entryId(), legA.get(0).getId());
        assertEquals(result.creditLeg().entryId(), legB.get(0).getId());
        assertEquals(60, legA.get(0).getBalanceAfter());
        assertEquals(90, legB.get(0).getBalanceAfter());
        assertEquals(2, byRequest.size());
    }

    @Test
    void insufficientFundsRejectsWholeTransferWithoutAnyTrace() {
        accounts("pa=20", "pb=100");

        InsufficientFundsException ex = assertThrows(InsufficientFundsException.class,
                () -> transferService.transfer("pa", "pb", 50, "req-poor"));

        long balA = accountRepository.findById("pa").orElseThrow().getBalance();
        long balB = accountRepository.findById("pb").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        long operations = ledgerOperationRepository.count();
        log.info("transfer 50 from balance 20 -> category={} balances {}/{} entries={} operations={} (expect 20/100/0/0)",
                ex.category(), balA, balB, entries, operations);

        assertEquals(LedgerException.Category.BUSINESS_REJECTION, ex.category());
        assertEquals(20, balA);
        assertEquals(100, balB);
        assertEquals(0, entries);
        assertEquals(0, operations);
    }

    @Test
    void invalidInputsAreRejectedBeforeAnyWrite() {
        accounts("ia=100", "ib=100");

        assertInvalidArg(() -> transferService.transfer("ia", "ib", 0, "req-z"));
        assertInvalidArg(() -> transferService.transfer("ia", "ib", -5, "req-z"));
        assertInvalidArg(() -> transferService.transfer("ia", "ia", 10, "req-z"));
        assertInvalidArg(() -> transferService.transfer("missing", "ib", 10, "req-z"));
        assertInvalidArg(() -> transferService.transfer("ia", "missing", 10, "req-z"));

        log.info("5 invalid transfer inputs rejected, entries={} operations={} (expect 0/0)",
                ledgerEntryRepository.count(), ledgerOperationRepository.count());
        assertEquals(0, ledgerEntryRepository.count());
        assertEquals(0, ledgerOperationRepository.count());
    }

    @Test
    void duplicateTransferReturnsFirstResultAndMovesMoneyOnce() {
        accounts("da=100", "db=100");

        TransferResult first = transferService.transfer("da", "db", 30, "req-dup-tr");
        TransferResult second = transferService.transfer("da", "db", 99, "req-dup-tr");

        log.info("repeated transfer (different amount) -> replayed={} sameDebitId={} sameCreditId={}",
                second.replayed(),
                first.debitLeg().entryId() == second.debitLeg().entryId(),
                first.creditLeg().entryId() == second.creditLeg().entryId());

        assertTrue(second.replayed());
        assertEquals(first.debitLeg().entryId(), second.debitLeg().entryId());
        assertEquals(first.creditLeg().entryId(), second.creditLeg().entryId());
        assertEquals(70, accountRepository.findById("da").orElseThrow().getBalance());
        assertEquals(130, accountRepository.findById("db").orElseThrow().getBalance());
        assertEquals(2, ledgerEntryRepository.count());
    }

    @Test
    void concurrentDuplicateTransferConvergesToSingleTransfer() throws Exception {
        accounts("ca=100", "cb=100");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<TransferResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> transferService.transfer("ca", "cb", 30, "req-dup-race"));
        }
        List<Future<TransferResult>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        long debitIds = futures.stream().map(f -> {
            try {
                return f.get().debitLeg().entryId();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).distinct().count();
        log.info("8 concurrent duplicate transfers -> distinct debitLeg ids={} balances {}/{} entries={} (expect 1, 70/130, 2)",
                debitIds,
                accountRepository.findById("ca").orElseThrow().getBalance(),
                accountRepository.findById("cb").orElseThrow().getBalance(),
                ledgerEntryRepository.count());

        assertEquals(1, debitIds);
        assertEquals(70, accountRepository.findById("ca").orElseThrow().getBalance());
        assertEquals(130, accountRepository.findById("cb").orElseThrow().getBalance());
        assertEquals(2, ledgerEntryRepository.count());
        assertEquals(1, ledgerOperationRepository.count());
    }

    @Test
    void requestIdCannotCrossKindsBetweenChangeAndTransfer() {
        accounts("xa=100", "xb=100");

        transferService.transfer("xa", "xb", 10, "req-cross");
        LedgerException reusedByChange = assertThrows(LedgerException.class,
                () -> accountService.applyChange("xa", LedgerEntryType.DEBIT, 10, "req-cross"));

        accountService.applyChange("xa", LedgerEntryType.DEBIT, 10, "req-cross-2");
        LedgerException reusedByTransfer = assertThrows(LedgerException.class,
                () -> transferService.transfer("xa", "xb", 10, "req-cross-2"));

        log.info("cross-kind reuse rejected: transfer->change category={}, change->transfer category={}",
                reusedByChange.category(), reusedByTransfer.category());
        assertEquals(LedgerException.Category.INVALID_ARGUMENT, reusedByChange.category());
        assertEquals(LedgerException.Category.INVALID_ARGUMENT, reusedByTransfer.category());
        assertEquals(80, accountRepository.findById("xa").orElseThrow().getBalance());
        assertEquals(110, accountRepository.findById("xb").orElseThrow().getBalance());
    }

    @Test
    void oppositeDirectionConcurrentTransfersConserveMoneyAndNeverDeadlock() throws Exception {
        accounts("ba=1000", "bb=1000");

        int roundsPerDirection = 20;
        AtomicInteger conflicts = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Callable<Void> aToB = () -> {
            for (int i = 0; i < roundsPerDirection; i++) {
                runWithRetry("ba", "bb", 5, "req-ab-" + i, conflicts);
            }
            return null;
        };
        Callable<Void> bToA = () -> {
            for (int i = 0; i < roundsPerDirection; i++) {
                runWithRetry("bb", "ba", 3, "req-ba-" + i, conflicts);
            }
            return null;
        };
        List<Future<Void>> futures = pool.invokeAll(List.of(aToB, bToA));
        pool.shutdown();
        for (Future<Void> f : futures) {
            f.get();
        }

        long balA = accountRepository.findById("ba").orElseThrow().getBalance();
        long balB = accountRepository.findById("bb").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        log.info("40 opposite-direction transfers (retry-conflicts={}) -> balances {}/{} sum={} entries={} (expect sum 2000, 80)",
                conflicts.get(), balA, balB, balA + balB, entries);

        assertEquals(2_000, balA + balB);
        assertEquals(1_000 - 20L * 5 + 20L * 3, balA);
        assertEquals(80, entries);
    }

    @Test
    void concurrentTransfersOnSameDebitNeverLoseMoneyAndConflictsAreDistinguishable() throws Exception {
        accounts("sa=100", "s1=0", "s2=0");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Callable<String>> tasks = List.of(
                () -> runOutcome("sa", "s1", 60, "req-s1", new AtomicInteger()),
                () -> runOutcome("sa", "s2", 60, "req-s2", new AtomicInteger()));
        List<Future<String>> futures = pool.invokeAll(tasks);
        pool.shutdown();
        List<String> outcomes = futures.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).toList();

        long source = accountRepository.findById("sa").orElseThrow().getBalance();
        long credited = accountRepository.findById("s1").orElseThrow().getBalance()
                + accountRepository.findById("s2").orElseThrow().getBalance();
        log.info("two concurrent 60 transfers from balance 100 -> outcomes={} source={} credited={}",
                outcomes, source, credited);

        assertEquals(1, outcomes.stream().filter("SUCCESS"::equals).count());
        assertEquals(100, source + credited);
        assertTrue(outcomes.stream().allMatch(o ->
                o.equals("SUCCESS") || o.equals("CONFLICT") || o.equals("INSUFFICIENT")));
    }

    private String runOutcome(String from, String to, long amount, String requestId,
                              AtomicInteger conflicts) {
        try {
            transferService.transfer(from, to, amount, requestId);
            return "SUCCESS";
        } catch (ConcurrencyConflictException e) {
            conflicts.incrementAndGet();
            return "CONFLICT";
        } catch (InsufficientFundsException e) {
            return "INSUFFICIENT";
        }
    }

    private void runWithRetry(String from, String to, long amount, String requestId,
                              AtomicInteger conflicts) {
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                transferService.transfer(from, to, amount, requestId);
                return;
            } catch (ConcurrencyConflictException e) {
                conflicts.incrementAndGet();
                Thread.onSpinWait();
            }
        }
        throw new AssertionError("transfer did not succeed after retries: " + requestId);
    }

    private void assertInvalidArg(Executable call) {
        LedgerException ex = assertThrows(LedgerException.class, call::execute);
        assertEquals(LedgerException.Category.INVALID_ARGUMENT, ex.category(),
                "expected INVALID_ARGUMENT but got " + ex.category() + ": " + ex.getMessage());
    }

    @FunctionalInterface
    interface Executable {
        void execute();
    }
}
