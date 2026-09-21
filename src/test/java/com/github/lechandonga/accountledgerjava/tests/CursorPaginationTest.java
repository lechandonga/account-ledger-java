package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.PageResult;
import com.github.lechandonga.accountledgerjava.exception.InvalidRequestException;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.service.LedgerService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 游标分页：严格按 id 升序、不重不漏；翻页期间有新写入不影响已发布窗口；
 * 非法 size / 游标 / accountNo 必须被明确拒绝。
 */
@SpringBootTest
class CursorPaginationTest {

    private static final Logger log = LoggerFactory.getLogger(CursorPaginationTest.class);

    @Autowired
    private LedgerService ledgerService;
    @Autowired
    private AccountService accountService;

    private String newAccount(String initial) {
        String no = "ACC-" + UUID.randomUUID();
        accountService.createAccount(no, "分页账户", new BigDecimal(initial), "CNY");
        return no;
    }

    private void depositN(String no, int n) {
        for (int i = 0; i < n; i++) {
            ledgerService.deposit("REQ-" + UUID.randomUUID(), no, BigDecimal.ONE);
        }
    }

    @Test
    void pages_through_all_entries_without_gap_or_duplicate_even_with_concurrent_writes() {
        String no = newAccount("0.00");
        depositN(no, 25);

        List<Long> seenIds = new ArrayList<>();
        String cursor = null;
        int pageNo = 0;
        // 翻到第 2 页之后制造新写入，验证其不会让既有窗口漏读/重复
        while (true) {
            PageResult<LedgerEntry> page = ledgerService.pageEntries(no, cursor, 10);
            pageNo++;
            page.items().forEach(e -> seenIds.add(e.id()));
            log.info("第{}页 size={} hasMore={} nextCursor={}",
                    pageNo, page.items().size(), page.hasMore(), page.nextCursor() == null ? "null" : "...");
            if (pageNo == 2) {
                depositN(no, 5);
            }
            if (!page.hasMore()) {
                assertNull(page.nextCursor());
                break;
            }
            cursor = page.nextCursor();
            assertTrue(pageNo < 10, "分页未收敛");
        }

        Set<Long> unique = new HashSet<>(seenIds);
        log.info("判定依据: 累计读取={} 去重后={} 期望至少=30（翻页期间新增5条）", seenIds.size(), unique.size());
        assertEquals(seenIds.size(), unique.size(), "不得重复读取");
        assertTrue(seenIds.size() >= 30, "翻页期间的新写入不得漏读");
        // id 严格升序
        for (int i = 1; i < seenIds.size(); i++) {
            assertTrue(seenIds.get(i - 1) < seenIds.get(i), "游标分页必须严格按 id 升序");
        }
    }

    @Test
    void global_pagination_includes_multiple_accounts() {
        String a = newAccount("0.00");
        String b = newAccount("0.00");
        depositN(a, 3);
        depositN(b, 3);

        // 共享测试库中可能存在其他测试更早写入的流水，因此翻完全部分页再统计本用例数据，
        // 同时验证全量游标分页不重不漏
        List<LedgerEntry> all = new ArrayList<>();
        String cursor = null;
        while (true) {
            PageResult<LedgerEntry> page = ledgerService.pageEntries(null, cursor, 100);
            all.addAll(page.items());
            if (!page.hasMore()) {
                break;
            }
            cursor = page.nextCursor();
        }
        long fromA = all.stream().filter(e -> e.accountNo().equals(a)).count();
        long fromB = all.stream().filter(e -> e.accountNo().equals(b)).count();
        long distinct = all.stream().map(LedgerEntry::id).distinct().count();
        log.info("判定依据: 全量翻页共{}条（去重后{}），其中 A={} 条 B={} 条",
                all.size(), distinct, fromA, fromB);
        assertEquals(all.size(), distinct, "全量分页不得重复");
        assertEquals(3, fromA);
        assertEquals(3, fromB);
    }

    @Test
    void invalid_paging_parameters_are_rejected() {
        InvalidRequestException e1 = assertThrows(InvalidRequestException.class,
                () -> ledgerService.pageEntries(null, null, 0));
        InvalidRequestException e2 = assertThrows(InvalidRequestException.class,
                () -> ledgerService.pageEntries(null, null, 101));
        InvalidRequestException e3 = assertThrows(InvalidRequestException.class,
                () -> ledgerService.pageEntries(null, "not-a-base64-cursor!", 10));
        InvalidRequestException e4 = assertThrows(InvalidRequestException.class,
                () -> ledgerService.pageEntries("   ", null, 10));
        log.info("判定依据: 非法参数分别被拒: {} | {} | {} | {}",
                e1.getErrorCode(), e2.getErrorCode(), e3.getMessage(), e4.getMessage());
        assertEquals("INVALID_REQUEST", e1.getErrorCode());
        assertEquals("INVALID_REQUEST", e2.getErrorCode());
        assertEquals("INVALID_REQUEST", e3.getErrorCode());
        assertEquals("INVALID_REQUEST", e4.getErrorCode());
    }
}
