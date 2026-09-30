package com.github.lechandonga.accountledgerjava.repo;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** 单边操作幂等查询：每个 (账户, 请求号) 至多一条。 */
    Optional<LedgerEntry> findByAccountIdAndRequestId(String accountId, String requestId);

    /** 按业务请求号取出该请求产生的全部流水（单边 1 条，划转 2 条）。 */
    List<LedgerEntry> findByRequestIdOrderByIdAsc(String requestId);

    /** 游标分页：id 严格递增，cursor 之后的一段，保证不漏不重。 */
    List<LedgerEntry> findByAccountIdAndIdGreaterThanOrderByIdAsc(String accountId, long cursor, Pageable pageable);

    List<LedgerEntry> findByAccountIdOrderByIdAsc(String accountId, Pageable pageable);
}
