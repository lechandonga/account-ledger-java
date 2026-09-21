package com.github.lechandonga.accountledgerjava.web;

import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.LedgerOperationResult;
import com.github.lechandonga.accountledgerjava.domain.OperationStatus;
import com.github.lechandonga.accountledgerjava.domain.PageResult;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.service.LedgerService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class LedgerController {

    private final AccountService accountService;
    private final LedgerService ledgerService;

    public LedgerController(AccountService accountService, LedgerService ledgerService) {
        this.accountService = accountService;
        this.ledgerService = ledgerService;
    }

    @PostMapping("/accounts")
    public ResponseEntity<Account> createAccount(@RequestBody CreateAccountRequest request) {
        Account account = accountService.createAccount(
                request.accountNo(), request.accountName(),
                request.initialBalance(), request.currency());
        return ResponseEntity.status(HttpStatus.CREATED).body(account);
    }

    @GetMapping("/accounts/{accountNo}")
    public Account getAccount(@PathVariable String accountNo) {
        return accountService.getAccount(accountNo);
    }

    @PostMapping("/deposits")
    public ResponseEntity<LedgerOperationResult> deposit(@RequestBody DepositRequest request) {
        return respond(ledgerService.deposit(request.requestId(), request.accountNo(), request.amount()));
    }

    @PostMapping("/withdrawals")
    public ResponseEntity<LedgerOperationResult> withdraw(@RequestBody WithdrawRequest request) {
        return respond(ledgerService.withdraw(request.requestId(), request.accountNo(), request.amount()));
    }

    @PostMapping("/transfers")
    public ResponseEntity<LedgerOperationResult> transfer(@RequestBody TransferRequest request) {
        return respond(ledgerService.transfer(request.requestId(),
                request.fromAccountNo(), request.toAccountNo(), request.amount()));
    }

    /**
     * 业务拒绝（如余额不足）映射为 422，与 409 乐观锁冲突、400 参数错误可区分；
     * 成功（含幂等重放）为 200。
     */
    private ResponseEntity<LedgerOperationResult> respond(LedgerOperationResult result) {
        if (result.status() == OperationStatus.REJECTED) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(result);
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/ledger-entries")
    public PageResult<LedgerEntry> pageEntries(@RequestParam(required = false) String accountNo,
                                               @RequestParam(required = false) String cursor,
                                               @RequestParam(required = false) Integer size) {
        return ledgerService.pageEntries(accountNo, cursor, size);
    }
}
