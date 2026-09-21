package com.github.lechandonga.accountledgerjava.tests;

import com.github.lechandonga.accountledgerjava.exception.SchemaMismatchException;
import com.github.lechandonga.accountledgerjava.support.SchemaVerifier;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 结构演进校验：
 *  - 正常迁移后的库必须通过自检；
 *  - 关键约束被人为移除后必须被明确识别（SchemaMismatchException），不得静默忽略。
 */
@SpringBootTest
class SchemaVerificationTest {

    private static final Logger log = LoggerFactory.getLogger(SchemaVerificationTest.class);

    @Autowired
    private SchemaVerifier schemaVerifier;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void healthy_schema_passes_verification() {
        assertDoesNotThrow(() -> schemaVerifier.run(new DefaultApplicationArguments()));
    }

    @Test
    void missing_check_constraint_is_detected_not_ignored() {
        jdbcTemplate.execute("ALTER TABLE accounts DROP CONSTRAINT chk_accounts_balance_non_negative");
        try {
            SchemaMismatchException ex = assertThrows(SchemaMismatchException.class,
                    () -> schemaVerifier.run(new DefaultApplicationArguments()));
            log.info("判定依据: 删除余额非负 CHECK 后自检结果 code={} message={}",
                    ex.getErrorCode(), ex.getMessage());
            assertEquals("SCHEMA_MISMATCH", ex.getErrorCode());
            assertTrue(ex.getMessage().contains("chk_accounts_balance_non_negative"));
        } finally {
            // 恢复约束，避免污染同上下文内的其他测试
            jdbcTemplate.execute("ALTER TABLE accounts ADD CONSTRAINT "
                    + "chk_accounts_balance_non_negative CHECK (balance_cents >= 0)");
        }
    }

    @Test
    void missing_unique_constraint_is_detected_not_ignored() {
        jdbcTemplate.execute("ALTER TABLE idempotent_requests DROP CONSTRAINT uq_idempotent_requests_request_id");
        try {
            SchemaMismatchException ex = assertThrows(SchemaMismatchException.class,
                    () -> schemaVerifier.run(new DefaultApplicationArguments()));
            log.info("判定依据: 删除幂等唯一约束后自检结果 code={} message={}",
                    ex.getErrorCode(), ex.getMessage());
            assertTrue(ex.getMessage().contains("uq_idempotent_requests_request_id"));
        } finally {
            jdbcTemplate.execute("ALTER TABLE idempotent_requests ADD CONSTRAINT "
                    + "uq_idempotent_requests_request_id UNIQUE (request_id)");
        }
    }
}
