package com.github.lechandonga.accountledgerjava.tests.fixture;

import com.github.lechandonga.accountledgerjava.domain.Direction;
import com.github.lechandonga.accountledgerjava.domain.EntryType;
import com.github.lechandonga.accountledgerjava.exception.InsufficientFundsException;
import com.github.lechandonga.accountledgerjava.repository.AccountRepository;
import com.github.lechandonga.accountledgerjava.repository.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.support.Money;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内层服务（独立 Bean，保证 @Transactional 经代理生效）。
 * 内层操作的账户与外层不同——REQUIRES_NEW 挂起外层事务后，
 * 更新同一行会等待外层未释放的行锁（数据库正确行为），
 * 独立事务的典型正当用途是操作另一组资源。
 */
@Component
public class InnerTxService {

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository ledgerEntryRepository;

    public InnerTxService(AccountRepository accountRepository,
                          LedgerEntryRepository ledgerEntryRepository) {
        this.accountRepository = accountRepository;
        this.ledgerEntryRepository = ledgerEntryRepository;
    }

    /** REQUIRED：加入外层事务；失败会把整个事务标记回滚 */
    @Transactional(propagation = Propagation.REQUIRED, rollbackFor = Exception.class)
    public void requiredFails(String accountNo, long version, String requestId) {
        work(accountNo, version, requestId);
    }

    /** REQUIRES_NEW：另开物理事务；失败只回滚内层，外层捕获后可继续提交 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void requiresNewFails(String accountNo, long version, String requestId) {
        work(accountNo, version, requestId);
    }

    private void work(String accountNo, long version, String requestId) {
        long newBalance = accountRepository.applyBalanceChange(accountNo, version, 3000L);
        ledgerEntryRepository.insert(requestId, accountNo, null, Direction.CREDIT,
                3000L, newBalance, EntryType.DEPOSIT);
        throw new InsufficientFundsException(accountNo, Money.toYuan(newBalance),
                Money.toYuan(999999L));
    }
}
