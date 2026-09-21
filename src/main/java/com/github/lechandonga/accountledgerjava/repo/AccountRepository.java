package com.github.lechandonga.accountledgerjava.repo;

import com.github.lechandonga.accountledgerjava.domain.Account;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccountRepository extends JpaRepository<Account, String> {
}
