package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.db.SchemaGuard;
import com.github.lechandonga.accountledgerjava.error.SchemaMismatchException;
import com.github.lechandonga.accountledgerjava.repo.AccountRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerEntryRepository;
import com.github.lechandonga.accountledgerjava.repo.LedgerRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 结构核对：人为删掉/改掉实际库里的约束（唯一、非空、取值范围、外键、索引）后，
 * SchemaGuard 必须明确识别并抛出 SchemaMismatchException；恢复后必须通过。
 * 所有篡改在每个用例后复原，避免污染同一上下文里的其他测试。
 */
@SpringBootTest
class SchemaGuardTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaGuardTest.class);

    @Autowired SchemaGuard schemaGuard;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired AccountRepository accountRepository;
    @Autowired LedgerEntryRepository ledgerEntryRepository;
    @Autowired LedgerRequestRepository ledgerRequestRepository;

    @BeforeEach
    void cleanData() {
        jdbcTemplate.execute("SET REFERENTIAL_INTEGRITY TRUE");
        ledgerEntryRepository.deleteAll();
        ledgerRequestRepository.deleteAll();
        accountRepository.deleteAll();
    }

    @AfterEach
    void restoreSchema() {
        // 无论用例把结构改成什么样，这里幂等复原到 V3 预期形态。
        safe("ALTER TABLE ledger_entry DROP CONSTRAINT IF EXISTS uk_ledger_account_request");
        safe("ALTER TABLE ledger_entry ADD CONSTRAINT uk_ledger_account_request "
                + "UNIQUE (account_id, request_id)");

        safe("ALTER TABLE ledger_entry DROP CONSTRAINT IF EXISTS chk_ledger_amount_positive");
        safe("ALTER TABLE ledger_entry ADD CONSTRAINT chk_ledger_amount_positive CHECK (amount > 0)");

        safe("ALTER TABLE ledger_entry DROP CONSTRAINT IF EXISTS fk_ledger_request");
        safe("ALTER TABLE ledger_entry ADD CONSTRAINT fk_ledger_request "
                + "FOREIGN KEY (request_id) REFERENCES ledger_request (id)");

        safe("ALTER TABLE ledger_entry ALTER COLUMN request_id SET NOT NULL");

        safe("CREATE INDEX IF NOT EXISTS idx_ledger_account_id ON ledger_entry (account_id, id)");

        // 复原后必须能通过，确认复原本身正确
        assertDoesNotThrow(() -> schemaGuard.verify());
    }

    @Test
    void cleanSchemaPassesVerification() {
        assertDoesNotThrow(() -> schemaGuard.verify());
        log.info("clean schema verification passed");
    }

    @Test
    void droppedUniqueConstraintIsDetected() {
        jdbcTemplate.execute("ALTER TABLE ledger_entry DROP CONSTRAINT uk_ledger_account_request");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, () -> schemaGuard.verify());
        log.info("dropped UNIQUE -> detected: {}", firstLine(ex.getMessage()));
        assertTrue(ex.getMessage().contains("UK_LEDGER_ACCOUNT_REQUEST"));
    }

    @Test
    void weakenedCheckConstraintIsDetectedByNameAndExpression() {
        // 同名约束、但语义被改成 amount >= 0（允许 0 金额）：表达式核对必须抓住
        jdbcTemplate.execute("ALTER TABLE ledger_entry DROP CONSTRAINT chk_ledger_amount_positive");
        jdbcTemplate.execute("ALTER TABLE ledger_entry ADD CONSTRAINT chk_ledger_amount_positive "
                + "CHECK (amount >= 0)");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, () -> schemaGuard.verify());
        log.info("weakened CHECK -> detected: {}", firstLine(ex.getMessage()));
        assertTrue(ex.getMessage().contains("CHK_LEDGER_AMOUNT_POSITIVE"));
        assertTrue(ex.getMessage().contains("unexpected expression"));
    }

    @Test
    void droppedCheckConstraintIsDetected() {
        jdbcTemplate.execute("ALTER TABLE ledger_entry DROP CONSTRAINT chk_ledger_amount_positive");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, () -> schemaGuard.verify());
        log.info("dropped CHECK -> detected: {}", firstLine(ex.getMessage()));
        assertTrue(ex.getMessage().contains("missing CHECK constraint CHK_LEDGER_AMOUNT_POSITIVE"));
    }

    @Test
    void droppedForeignKeyIsDetected() {
        jdbcTemplate.execute("ALTER TABLE ledger_entry DROP CONSTRAINT fk_ledger_request");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, () -> schemaGuard.verify());
        log.info("dropped FK -> detected: {}", firstLine(ex.getMessage()));
        assertTrue(ex.getMessage().contains("REQUEST_ID") && ex.getMessage().contains("LEDGER_REQUEST"));
    }

    @Test
    void nullabilityChangeIsDetected() {
        jdbcTemplate.execute("ALTER TABLE ledger_entry ALTER COLUMN request_id DROP NOT NULL");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, () -> schemaGuard.verify());
        log.info("dropped NOT NULL -> detected: {}", firstLine(ex.getMessage()));
        assertTrue(ex.getMessage().contains("LEDGER_ENTRY.REQUEST_ID must be NOT NULL"));
    }

    @Test
    void droppedIndexIsDetected() {
        jdbcTemplate.execute("DROP INDEX idx_ledger_account_id");
        SchemaMismatchException ex = assertThrows(SchemaMismatchException.class, () -> schemaGuard.verify());
        log.info("dropped INDEX -> detected: {}", firstLine(ex.getMessage()));
        assertTrue(ex.getMessage().contains("IDX_LEDGER_ACCOUNT_ID"));
    }

    private void safe(String sql) {
        try {
            jdbcTemplate.execute(sql);
        } catch (RuntimeException e) {
            log.warn("restore statement failed (ignored): {} -> {}", sql, e.getMessage());
        }
    }

    private String firstLine(String message) {
        int idx = message.indexOf(';');
        return idx < 0 ? message : message.substring(0, idx);
    }
}
