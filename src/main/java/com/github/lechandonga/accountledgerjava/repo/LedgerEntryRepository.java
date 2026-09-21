package com.github.lechandonga.accountledgerjava.repo;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    Optional<LedgerEntry> findByRequestId(String requestId);

    /** 游标分页：id 严格递增，cursor 之后的一段，保证不漏不重。 */
    List<LedgerEntry> findByAccountIdAndIdGreaterThanOrderByIdAsc(String accountId, long cursor, Pageable pageable);

    List<LedgerEntry> findByAccountIdOrderByIdAsc(String accountId, Pageable pageable);
}
