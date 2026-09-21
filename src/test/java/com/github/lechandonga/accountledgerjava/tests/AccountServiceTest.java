package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;
import com.github.lechandonga.accountledgerjava.error.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.error.LedgerException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.service.ChangeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 余额变动的基础正确性：余额不足回滚、重复提交幂等。
 * 日志打印关键输入与判定依据。
 */
@SpringBootTest
class AccountServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceTest.class);

    @Autowired
    AccountService accountService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;

    @BeforeEach
    void clean() {
        ledgerEntryRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @Test
    void creditThenDebitKeepsBalanceAndEntriesConsistent() {
        accountService.createAccount("a1", 100);
        ChangeResult c = accountService.applyChange("a1", LedgerEntryType.CREDIT, 50, "req-c1");
        ChangeResult d = accountService.applyChange("a1", LedgerEntryType.DEBIT, 30, "req-d1");

        long balance = accountService.findAccount("a1").orElseThrow().getBalance();
        log.info("credit 50 then debit 30 -> balance={} (expect 120), entries={}",
                balance, ledgerEntryRepository.count());

        assertEquals(120, balance);
        assertEquals(150, c.balanceAfter());
        assertEquals(120, d.balanceAfter());
        assertEquals(2, ledgerEntryRepository.count());
        assertFalse(c.replayed());
    }

    @Test
    void insufficientFundsIsRejectedAndRollsBackCompletely() {
        accountService.createAccount("a2", 40);

        InsufficientFundsException ex = assertThrows(InsufficientFundsException.class,
                () -> accountService.applyChange("a2", LedgerEntryType.DEBIT, 100, "req-x1"));

        long balance = accountService.findAccount("a2").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        log.info("debit 100 from balance 40 -> category={} balance={} entries={} (expect unchanged/0)",
                ex.category(), balance, entries);

        // 判定依据：失败类别可区分，且余额与流水均未残留半更新状态
        assertEquals(LedgerException.Category.BUSINESS_REJECTION, ex.category());
        assertEquals(40, balance);
        assertEquals(0, entries);
    }

    @Test
    void duplicateSubmissionReturnsFirstResultWithoutSecondEntry() {
        accountService.createAccount("a3", 100);

        ChangeResult first = accountService.applyChange("a3", LedgerEntryType.DEBIT, 30, "req-dup");
        ChangeResult second = accountService.applyChange("a3", LedgerEntryType.DEBIT, 30, "req-dup");

        long balance = accountService.findAccount("a3").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        log.info("same requestId twice -> first(entryId={},replayed={}) second(entryId={},replayed={}) balance={} entries={}",
                first.entryId(), first.replayed(), second.entryId(), second.replayed(), balance, entries);

        // 判定依据：重复请求返回首次结果，余额只扣一次，流水只有一条
        assertFalse(first.replayed());
        assertTrue(second.replayed());
        assertEquals(first.entryId(), second.entryId());
        assertEquals(first.balanceAfter(), second.balanceAfter());
        assertEquals(70, balance);
        assertEquals(1, entries);
    }

    @Test
    void concurrentDuplicateSubmissionProducesSingleEntry() throws Exception {
        accountService.createAccount("a4", 100);
        int threads = 8;

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<ChangeResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> accountService.applyChange("a4", LedgerEntryType.DEBIT, 30, "req-race"));
        }
        List<Future<ChangeResult>> futures = pool.invokeAll(tasks);
        pool.shutdown();

        List<ChangeResult> results = new ArrayList<>();
        for (Future<ChangeResult> f : futures) {
            results.add(f.get());
        }
        long balance = accountService.findAccount("a4").orElseThrow().getBalance();
        long entries = ledgerEntryRepository.count();
        long distinctEntryIds = results.stream().map(ChangeResult::entryId).distinct().count();
        log.info("8 concurrent same-requestId -> distinctEntryIds={} balance={} entries={} (expect 1/70/1)",
                distinctEntryIds, balance, entries);

        // 判定依据：所有并发重复请求收敛到同一条流水，余额只扣一次
        assertEquals(1, distinctEntryIds);
        assertEquals(70, balance);
        assertEquals(1, entries);
    }
}
