package com.github.lechandonga.accountledgerjava.service;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.error.InvalidPaginationException;
import com.github.lechandonga.accountledgerjava.error.PersistenceFailureException;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 流水读取服务。只提供基于游标的稳定分页：
 * 按自增主键升序取 cursor 之后的记录，新写入的流水 id 更大，
 * 只会出现在后续页，不会导致已读区间漏读或重复。
 */
@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    public static final int MAX_PAGE_SIZE = 200;

    private final LedgerEntryRepository ledgerEntryRepository;

    public LedgerService(LedgerEntryRepository ledgerEntryRepository) {
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /**
     * @param cursor 上一页返回的 nextCursor；null 表示从头开始
     * @param size   页大小，1..MAX_PAGE_SIZE
     */
    @Transactional(readOnly = true)
    public LedgerPage page(String accountId, Long cursor, int size) {
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidPaginationException("page size must be in [1," + MAX_PAGE_SIZE + "]: " + size);
        }
        if (cursor != null && cursor < 0) {
            throw new InvalidPaginationException("cursor must be >= 0: " + cursor);
        }
        try {
            // 多取一条用于判断是否还有下一页，避免依赖不稳定的 count。
            PageRequest req = PageRequest.of(0, size + 1);
            List<LedgerEntry> rows = (cursor == null)
                    ? ledgerEntryRepository.findByAccountIdOrderByIdAsc(accountId, req)
                    : ledgerEntryRepository.findByAccountIdAndIdGreaterThanOrderByIdAsc(accountId, cursor, req);

            boolean hasMore = rows.size() > size;
            List<LedgerEntry> page = hasMore ? rows.subList(0, size) : rows;
            Long nextCursor = hasMore ? page.get(page.size() - 1).getId() : null;

            log.info("page account={} cursor={} size={} fetched={} hasMore={}",
                    accountId, cursor, size, page.size(), hasMore);
            return new LedgerPage(List.copyOf(page), nextCursor);
        } catch (DataAccessException e) {
            throw new PersistenceFailureException("failed to read ledger page for account " + accountId, e);
        }
    }
}
