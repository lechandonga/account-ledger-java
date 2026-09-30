package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;
import com.github.lechandonga.accountledgerjava.error.InvalidPaginationException;
import com.github.lechandonga.accountledgerjava.error.LedgerException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerOperationRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.service.LedgerPage;
import com.github.lechandonga.accountledgerjava.service.LedgerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游标分页：稳定性（分页期间写入不漏不重）、非法参数拒绝。
 */
@SpringBootTest
class LedgerPaginationTest {

    private static final Logger log = LoggerFactory.getLogger(LedgerPaginationTest.class);

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

    @Test
    void cursorPaginationCoversAllEntriesExactlyOnce() {
        accountService.createAccount("p1", 0);
        for (int i = 1; i <= 7; i++) {
            accountService.applyChange("p1", LedgerEntryType.CREDIT, 10, "req-p1-" + i);
        }

        List<Long> seen = new ArrayList<>();
        Long cursor = null;
        int pages = 0;
        while (true) {
            LedgerPage page = ledgerService.page("p1", cursor, 3);
            page.entries().forEach(e -> seen.add(e.getId()));
            pages++;
            log.info("page {} cursor={} -> ids={} nextCursor={}",
                    pages, cursor, page.entries().stream().map(LedgerEntry::getId).toList(), page.nextCursor());
            if (page.nextCursor() == null) {
                break;
            }
            cursor = page.nextCursor();
        }

        log.info("collected {} entries in {} pages, distinct={}", seen.size(), pages, new HashSet<>(seen).size());
        // 判定依据：7 条流水全部读到且无重复
        assertEquals(7, seen.size());
        assertEquals(7, new HashSet<>(seen).size());
    }

    @Test
    void entriesWrittenDuringPaginationAreNotMissedOrDuplicated() {
        accountService.createAccount("p2", 0);
        for (int i = 1; i <= 4; i++) {
            accountService.applyChange("p2", LedgerEntryType.CREDIT, 10, "req-p2-" + i);
        }

        // 读第一页（大小 2）
        LedgerPage first = ledgerService.page("p2", null, 2);
        log.info("first page ids={} nextCursor={}",
                first.entries().stream().map(LedgerEntry::getId).toList(), first.nextCursor());

        // 分页过程中写入 2 条新流水
        accountService.applyChange("p2", LedgerEntryType.CREDIT, 10, "req-p2-5");
        accountService.applyChange("p2", LedgerEntryType.CREDIT, 10, "req-p2-6");

        // 继续翻页直到结束
        Set<Long> seen = new HashSet<>();
        first.entries().forEach(e -> seen.add(e.getId()));
        Long cursor = first.nextCursor();
        while (cursor != null) {
            LedgerPage page = ledgerService.page("p2", cursor, 2);
            log.info("next page cursor={} ids={} nextCursor={}",
                    cursor, page.entries().stream().map(LedgerEntry::getId).toList(), page.nextCursor());
            page.entries().forEach(e -> seen.add(e.getId()));
            cursor = page.nextCursor();
        }

        log.info("total distinct entries read={} (expect 6: 原有 4 + 分页期间新增 2)", seen.size());
        // 判定依据：旧 4 条不漏，新 2 条在后续页读到，无任何重复
        assertEquals(6, seen.size());
        assertEquals(6, ledgerEntryRepository.count());
    }

    @Test
    void invalidPaginationParamsAreRejected() {
        accountService.createAccount("p3", 0);

        InvalidPaginationException negCursor = assertThrows(InvalidPaginationException.class,
                () -> ledgerService.page("p3", -1L, 10));
        InvalidPaginationException zeroSize = assertThrows(InvalidPaginationException.class,
                () -> ledgerService.page("p3", null, 0));
        InvalidPaginationException tooBig = assertThrows(InvalidPaginationException.class,
                () -> ledgerService.page("p3", null, LedgerService.MAX_PAGE_SIZE + 1));

        log.info("negCursor={} zeroSize={} tooBig={} category={}",
                negCursor.getMessage(), zeroSize.getMessage(), tooBig.getMessage(), negCursor.category());
        assertEquals(LedgerException.Category.INVALID_ARGUMENT, negCursor.category());
    }

    @Test
    void emptyAccountYieldsEmptyPageWithNullCursor() {
        accountService.createAccount("p4", 0);
        LedgerPage page = ledgerService.page("p4", null, 10);
        log.info("empty account page: size={} nextCursor={}", page.entries().size(), page.nextCursor());
        assertTrue(page.entries().isEmpty());
        assertNull(page.nextCursor());
    }
}
