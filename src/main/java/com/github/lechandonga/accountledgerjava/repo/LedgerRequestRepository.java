package com.github.lechandonga.accountledgerjava.repo;

import com.github.lechandonga.accountledgerjava.domain.LedgerRequest;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerRequestRepository extends JpaRepository<LedgerRequest, String> {
}
