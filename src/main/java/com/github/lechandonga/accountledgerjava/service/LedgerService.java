package com.github.lechandonga.accountledgerjava.service;

import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.LedgerOperationResult;
import com.github.lechandonga.accountledgerjava.domain.PageResult;

/**
 * 流水驱动的余额变动服务。
 * 每次余额变动与流水写入在同一事务内原子完成；
 * requestId 提供应用层幂等，重复请求返回与首次一致的结果。
 */
public interface LedgerService {

    LedgerOperationResult deposit(String requestId, String accountNo, java.math.BigDecimal amount);

    LedgerOperationResult withdraw(String requestId, String accountNo, java.math.BigDecimal amount);

    /** 转账：同一事务内扣转出方、记转入方，两条流水一次落库 */
    LedgerOperationResult transfer(String requestId,
                                   String fromAccountNo,
                                   String toAccountNo,
                                   java.math.BigDecimal amount);

    /** 游标分页读取流水：accountNo 可空表示全量 */
    PageResult<LedgerEntry> pageEntries(String accountNo, String cursor, Integer size);
}
