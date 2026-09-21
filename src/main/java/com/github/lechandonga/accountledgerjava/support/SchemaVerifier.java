package com.github.lechandonga.accountledgerjava.support;

import com.github.lechandonga.accountledgerjava.exception.SchemaMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 启动期结构/约束自检：表、关键列、CHECK/UNIQUE 约束缺失即判定结构漂移，
 * 抛出 SchemaMismatchException 拒绝启动，绝不静默忽略。
 *
 * <p>校验依据 INFORMATION_SCHEMA（H2 标准目录视图），覆盖：
 * 必需表、必需列（含可空性）、唯一约束、检查约束。</p>
 */
@Component
public class SchemaVerifier implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaVerifier.class);

    private final JdbcTemplate jdbcTemplate;

    @Value("${ledger.schema.verify:true}")
    private boolean verifyEnabled;

    public SchemaVerifier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!verifyEnabled) {
            log.info("ledger.schema.verify=false，跳过数据库结构自检");
            return;
        }
        verifyTableExists("accounts");
        verifyTableExists("ledger_entries");
        verifyTableExists("idempotent_requests");

        verifyColumns("accounts", Map.of(
                "account_no", "NO",
                "balance_cents", "NO",
                "currency", "NO",
                "version", "NO"));
        verifyColumns("ledger_entries", Map.of(
                "request_id", "NO",
                "account_no", "NO",
                "direction", "NO",
                "amount_cents", "NO",
                "balance_after", "NO"));
        verifyColumns("idempotent_requests", Map.of(
                "request_id", "NO",
                "status", "NO"));

        verifyUniqueConstraint("accounts", "uq_accounts_account_no");
        verifyUniqueConstraint("idempotent_requests", "uq_idempotent_requests_request_id");

        verifyCheckConstraint("accounts", "chk_accounts_balance_non_negative");
        verifyCheckConstraint("ledger_entries", "chk_ledger_amount_positive");
        verifyCheckConstraint("ledger_entries", "chk_ledger_direction");
        verifyCheckConstraint("idempotent_requests", "chk_idempotent_status");

        log.info("数据库结构与约束自检通过");
    }

    private void verifyTableExists(String table) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES "
                        + "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = ?",
                Integer.class, table.toUpperCase());
        if (count == null || count == 0) {
            throw new SchemaMismatchException("缺少必需的数据表: " + table);
        }
    }

    /** expectedNullable: "NO" 表示必须 NOT NULL */
    private void verifyColumns(String table, Map<String, String> expectedNullable) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = ?",
                table.toUpperCase());
        Map<String, String> actual = rows.stream()
                .collect(Collectors.toMap(
                        r -> ((String) r.get("COLUMN_NAME")).toLowerCase(),
                        r -> (String) r.get("IS_NULLABLE")));
        expectedNullable.forEach((col, nullable) -> {
            String actualNullable = actual.get(col.toLowerCase());
            if (actualNullable == null) {
                throw new SchemaMismatchException(
                        "表 " + table + " 缺少必需列: " + col);
            }
            if (!nullable.equalsIgnoreCase(actualNullable)) {
                throw new SchemaMismatchException(
                        "表 " + table + " 列 " + col + " 可空性与预期不符，期望 IS_NULLABLE=" + nullable
                                + "，实际=" + actualNullable);
            }
        });
    }

    private void verifyUniqueConstraint(String table, String constraint) {
        // H2 2.x：唯一约束登记在 INFORMATION_SCHEMA.TABLE_CONSTRAINTS（CONSTRAINT_TYPE='UNIQUE'）
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = ? "
                        + "AND CONSTRAINT_NAME = ? AND CONSTRAINT_TYPE = 'UNIQUE'",
                Integer.class, table.toUpperCase(), constraint.toUpperCase());
        if (count == null || count == 0) {
            throw new SchemaMismatchException(
                    "表 " + table + " 缺少必需的唯一约束: " + constraint);
        }
    }

    private void verifyCheckConstraint(String table, String constraint) {
        // H2 2.x：CHECK_CONSTRAINTS 自带 CONSTRAINT_NAME（CONSTRAINT_SCHEMA 区分目录）
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = SCHEMA() AND CONSTRAINT_NAME = ?",
                Integer.class, constraint.toUpperCase());
        if (count == null || count == 0) {
            throw new SchemaMismatchException(
                    "表 " + table + " 缺少必需的检查约束: " + constraint);
        }
    }
}
