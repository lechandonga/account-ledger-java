package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.db.SchemaVerifier;
import com.github.lechandonga.accountledgerjava.domain.Account;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntryType;
import com.github.lechandonga.accountledgerjava.error.LedgerException;
import com.github.lechandonga.accountledgerjava.error.SchemaMismatchException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerOperationRepository;
import com.github.lechandonga.accountledgerjava.service.AccountService;
import com.github.lechandonga.accountledgerjava.service.TransferService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构核对：故意删除/改动库里的唯一性、非空、取值范围、引用关系约束后，
 * SchemaVerifier 必须明确识别并失败；同时验证每次写操作前都会复核，
 * 约束在运行期被人改动也能立即被抓住。每个用例结束恢复原始结构。
 */
@SpringBootTest
class SchemaVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaVerificationTest.class);

    @Autowired
    SchemaVerifier schemaVerifier;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    AccountService accountService;
    @Autowired
    TransferService transferService;
    @Autowired
    AccountRepository accountRepository;
    @Autowired
    LedgerEntryRepository ledgerEntryRepository;
    @Autowired
    LedgerOperationRepository ledgerOperationRepository;

    @BeforeEach
    void clean() {
        ledgerEntryRepository.deleteAll();
        ledgerOperationRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @AfterEach
    void restoreSchema() {
        // 无条件重建：除了"被删除"，还要覆盖"被改名/改弱"的情况，避免污染共享的内存库。
        resetCheck("account", "CHK_ACCOUNT_BALANCE_NONNEGATIVE",
                "ALTER TABLE account ADD CONSTRAINT chk_account_balance_nonnegative CHECK (balance >= 0)");
        resetCheck("ledger_entry", "CHK_LEDGER_AMOUNT_POSITIVE",
                "ALTER TABLE ledger_entry ADD CONSTRAINT chk_ledger_amount_positive CHECK (amount > 0)");
        resetCheck("ledger_entry", "CHK_LEDGER_TYPE_VALID",
                "ALTER TABLE ledger_entry ADD CONSTRAINT chk_ledger_type_valid "
                        + "CHECK (type IN ('CREDIT','DEBIT'))");
        resetCheck("ledger_entry", "CHK_LEDGER_OPERATION_KIND_VALID",
                "ALTER TABLE ledger_entry ADD CONSTRAINT chk_ledger_operation_kind_valid "
                        + "CHECK (operation_kind IN ('CHANGE','TRANSFER'))");
        resetCheck("ledger_operation", "CHK_OPERATION_KIND_VALID",
                "ALTER TABLE ledger_operation ADD CONSTRAINT chk_operation_kind_valid "
                        + "CHECK (kind IN ('CHANGE','TRANSFER'))");
        resetCheck("ledger_operation", "CHK_OPERATION_AMOUNT_POSITIVE",
                "ALTER TABLE ledger_operation ADD CONSTRAINT chk_operation_amount_positive CHECK (amount > 0)");
        resetUnique();
        resetFk("LEDGER_ENTRY", "ACCOUNT_ID",
                "ALTER TABLE ledger_entry ADD CONSTRAINT fk_ledger_entry_account "
                        + "FOREIGN KEY (account_id) REFERENCES account (id)");
        resetFk("LEDGER_OPERATION", "SOURCE_ACCOUNT_ID",
                "ALTER TABLE ledger_operation ADD CONSTRAINT fk_operation_source_account "
                        + "FOREIGN KEY (source_account_id) REFERENCES account (id)");
        resetFk("LEDGER_OPERATION", "TARGET_ACCOUNT_ID",
                "ALTER TABLE ledger_operation ADD CONSTRAINT fk_operation_target_account "
                        + "FOREIGN KEY (target_account_id) REFERENCES account (id)");
        resetOperationPkAndNullability();
        assertDoesNotThrow(() -> schemaVerifier.verify());
    }

    @Test
    void pristineSchemaPassesVerification() {
        assertDoesNotThrow(() -> schemaVerifier.verify());
    }

    @Test
    void droppingRequestAccountUniqueConstraintIsDetected() {
        jdbcTemplate.execute("ALTER TABLE ledger_entry DROP CONSTRAINT uk_ledger_request_account");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, schemaVerifier::verify);
        log.info("dropped uk_ledger_request_account -> category={} message={}",
                ex.category(), ex.getMessage());
        assertTrue(ex.getMessage().contains("UK_LEDGER_REQUEST_ACCOUNT".toLowerCase()));
    }

    @Test
    void renamingUniqueConstraintToWrongColumnsIsDetected() {
        jdbcTemplate.execute("ALTER TABLE ledger_entry DROP CONSTRAINT uk_ledger_request_account");
        // 同名约束仍在，但只覆盖 request_id：语义已变（划转两条流水会被误拒）
        jdbcTemplate.execute("ALTER TABLE ledger_entry ADD CONSTRAINT uk_ledger_request_account UNIQUE (request_id)");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, schemaVerifier::verify);
        log.info("unique constraint narrowed to (request_id) -> message={}", ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("unique constraint"));
        assertTrue(ex.getMessage().toLowerCase().contains("request_id"));
    }

    @Test
    void droppingBalanceRangeCheckIsDetected() {
        jdbcTemplate.execute("ALTER TABLE account DROP CONSTRAINT chk_account_balance_nonnegative");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, schemaVerifier::verify);
        log.info("dropped balance check -> message={}", ex.getMessage());
        assertTrue(ex.getMessage().contains("chk_account_balance_nonnegative"));
    }

    @Test
    void weakeningAmountRangeCheckIsDetected() {
        jdbcTemplate.execute("ALTER TABLE ledger_entry DROP CONSTRAINT chk_ledger_amount_positive");
        // 同名但语义被放宽：允许 0 元流水
        jdbcTemplate.execute("ALTER TABLE ledger_entry ADD CONSTRAINT chk_ledger_amount_positive CHECK (amount >= 0)");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, schemaVerifier::verify);
        log.info("weakened amount check (>=0) -> message={}", ex.getMessage());
        assertTrue(ex.getMessage().contains("EXPRESSION DIFFERS".toLowerCase()));
    }

    @Test
    void droppingTypeDomainCheckIsDetected() {
        jdbcTemplate.execute("ALTER TABLE ledger_entry DROP CONSTRAINT chk_ledger_type_valid");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, schemaVerifier::verify);
        assertTrue(ex.getMessage().contains("chk_ledger_type_valid"));
    }

    @Test
    void droppingForeignKeyOnLedgerEntryIsDetected() {
        jdbcTemplate.execute(
                "ALTER TABLE ledger_entry DROP CONSTRAINT " + findFkName("LEDGER_ENTRY", "ACCOUNT_ID"));
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, schemaVerifier::verify);
        log.info("dropped ledger_entry FK -> message={}", ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("foreign key"));
    }

    @Test
    void droppingForeignKeyOnOperationIsDetected() {
        jdbcTemplate.execute("ALTER TABLE ledger_operation DROP CONSTRAINT fk_operation_target_account");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, schemaVerifier::verify);
        assertTrue(ex.getMessage().contains("LEDGER_OPERATION.TARGET_ACCOUNT_ID".toLowerCase()));
    }

    @Test
    void nullabilityRelaxationOnRequestIdIsDetected() {
        // request_id 同时是主键列，需要先临时摘除主键才能放开非空。
        jdbcTemplate.execute("ALTER TABLE ledger_operation DROP PRIMARY KEY");
        jdbcTemplate.execute("ALTER TABLE ledger_operation ALTER COLUMN request_id SET NULL");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, schemaVerifier::verify);
        log.info("request_id made nullable -> message={}", ex.getMessage());
        assertTrue(ex.getMessage().contains("REQUEST_ID".toLowerCase()));
    }

    @Test
    void runtimeConstraintTamperingIsRejectedBeforeAnyWrite() {
        accountRepository.save(new Account("ga", 100, Instant.now()));
        accountRepository.save(new Account("gb", 100, Instant.now()));
        accountService.applyChange("ga", LedgerEntryType.CREDIT, 5, "req-guard-1");

        // 运行期把余额非负约束删掉：下一次写操作前的复核必须抓住
        jdbcTemplate.execute("ALTER TABLE account DROP CONSTRAINT chk_account_balance_nonnegative");
        SchemaMismatchException changeEx = assertThrows(SchemaMismatchException.class,
                () -> accountService.applyChange("ga", LedgerEntryType.DEBIT, 5, "req-guard-2"));
        SchemaMismatchException transferEx = assertThrows(SchemaMismatchException.class,
                () -> transferService.transfer("ga", "gb", 5, "req-guard-3"));
        log.info("runtime tampering rejected before change/transfer: {} | {}",
                changeEx.category(), transferEx.category());

        assertEqualsCategory(changeEx);
        // 失败的请求不留任何操作痕迹
        org.junit.jupiter.api.Assertions.assertEquals(1, ledgerOperationRepository.count());
    }

    private void assertEqualsCategory(LedgerException ex) {
        org.junit.jupiter.api.Assertions.assertEquals(LedgerException.Category.PERSISTENCE_FAILURE,
                ex.category());
    }

    private String findFkName(String table, String column) {
        return jdbcTemplate.queryForObject(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_NAME = ? AND COLUMN_NAME = ? "
                        + "AND CONSTRAINT_NAME IN (SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS)",
                String.class, table, column);
    }

    private void resetCheck(String table, String name, String addDdl) {
        dropAllMatching(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = '" + table.toUpperCase() + "' AND CONSTRAINT_TYPE = 'CHECK' "
                        + "AND (CONSTRAINT_NAME = '" + name + "' OR CONSTRAINT_NAME = '"
                        + name.toLowerCase() + "')",
                "ALTER TABLE " + table + " DROP CONSTRAINT ");
        jdbcTemplate.execute(addDdl);
    }

    private void resetUnique() {
        dropAllMatching(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'LEDGER_ENTRY' AND CONSTRAINT_TYPE = 'UNIQUE'",
                "ALTER TABLE ledger_entry DROP CONSTRAINT ");
        jdbcTemplate.execute(
                "ALTER TABLE ledger_entry ADD CONSTRAINT uk_ledger_request_account UNIQUE (request_id, account_id)");
    }

    private void resetFk(String table, String column, String addDdl) {
        dropAllMatching(
                "SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                        + "WHERE TABLE_NAME = '" + table + "' AND COLUMN_NAME = '" + column + "' "
                        + "AND CONSTRAINT_NAME IN "
                        + "(SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS)",
                "ALTER TABLE " + table.toLowerCase() + " DROP CONSTRAINT ");
        jdbcTemplate.execute(addDdl);
    }

    private void resetOperationPkAndNullability() {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_NAME = 'LEDGER_OPERATION' AND COLUMN_NAME = 'REQUEST_ID'",
                String.class);
        if ("YES".equals(nullable)) {
            jdbcTemplate.execute("ALTER TABLE ledger_operation ALTER COLUMN request_id SET NOT NULL");
        }
        Integer pkCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME = 'LEDGER_OPERATION' AND CONSTRAINT_TYPE = 'PRIMARY KEY'",
                Integer.class);
        if (pkCount == null || pkCount == 0) {
            jdbcTemplate.execute("ALTER TABLE ledger_operation ADD PRIMARY KEY (request_id)");
        }
    }

    private void dropAllMatching(String query, String dropPrefix) {
        for (String name : jdbcTemplate.queryForList(query, String.class)) {
            jdbcTemplate.execute(dropPrefix + name);
        }
    }
}
