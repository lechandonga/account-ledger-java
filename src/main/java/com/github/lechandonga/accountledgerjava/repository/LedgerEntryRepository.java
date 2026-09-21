package com.github.lechandonga.accountledgerjava.repository;

import com.github.lechandonga.accountledgerjava.domain.Direction;
import com.github.lechandonga.accountledgerjava.domain.EntryType;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;

import java.util.List;
import java.util.Optional;

/** 资金流水仓储 */
public interface LedgerEntryRepository {

    /**
     * 插入一条流水，返回带自增主键与变更后余额的完整记录。
     * 调用方必须保证余额变更与本插入在同一事务内完成。
     */
    LedgerEntry insert(String requestId,
                       String accountNo,
                       String counterpartNo,
                       Direction direction,
                       long amountCents,
                       long balanceAfterCents,
                       EntryType entryType);

    Optional<LedgerEntry> findById(long id);

    /**
     * 游标分页：严格按 id 升序，返回 id > afterId 的前 limit 条。
     * accountNo 为 null 时查全量。
     */
    List<LedgerEntry> findPage(String accountNo, long afterId, int limit);
}
