package com.github.lechandonga.accountledgerjava.repo;

import com.github.lechandonga.accountledgerjava.domain.LedgerOperation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LedgerOperationRepository extends JpaRepository<LedgerOperation, String> {
}
