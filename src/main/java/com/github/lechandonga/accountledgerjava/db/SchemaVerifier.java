package com.github.lechandonga.accountledgerjava.db;

import com.github.lechandonga.accountledgerjava.error.SchemaMismatchException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 结构核对：直接读取数据库的实际元数据（非空、CHECK 取值范围、唯一约束、
 * 外键引用、索引、主键），与预期逐项比对。任何一项缺失或被改动都明确失败，
 * 不依赖 Flyway 校验和——即使有人在线把约束删掉/改掉，也能被抓到。
 */
@Component
public class SchemaVerifier {

    private static final Logger log = LoggerFactory.getLogger(SchemaVerifier.class);

    private final JdbcTemplate jdbcTemplate;

    public SchemaVerifier(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 核对全部预期结构。不通过时抛出 {@link SchemaMismatchException}，
     * message 中列出所有不一致项，调用方不得继续在该结构上读写。
     */
    public void verify() {
        List<String> problems = new ArrayList<>();

        verifyTables(problems);
        Map<String, Map<String, String>> columns = loadColumns();
        Map<String, String> checks = loadCheckConstraints();

        verifyAccount(problems, columns, checks);
        verifyLedgerEntry(problems, columns, checks);
        verifyLedgerOperation(problems, columns, checks);

        if (!problems.isEmpty()) {
            problems.forEach(p -> log.error("schema mismatch: {}", p));
            throw new SchemaMismatchException(
                    "database schema verification failed with " + problems.size() + " problem(s): " + problems,
                    null);
        }
        log.info("schema verification passed: tables/columns/uniqueness/checks/foreign-keys/indexes all match");
    }

    private void verifyTables(List<String> problems) {
        for (String table : List.of("ACCOUNT", "LEDGER_ENTRY", "LEDGER_OPERATION")) {
            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = ?",
                    Integer.class, table);
            if (count == null || count == 0) {
                problems.add("missing table " + table.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** @return table -> (column -> "YES"/"NO" 可空性) */
    private Map<String, Map<String, String>> loadColumns() {
        Map<String, Map<String, String>> result = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                        "SELECT TABLE_NAME, COLUMN_NAME, IS_NULLABLE FROM INFORMATION_SCHEMA.COLUMNS")
                .forEach(row -> result
                        .computeIfAbsent((String) row.get("TABLE_NAME"), t -> new LinkedHashMap<>())
                        .put((String) row.get("COLUMN_NAME"), (String) row.get("IS_NULLABLE")));
        return result;
    }

    /** @return 约束名 -> 归一化后的 CHECK 表达式（大写、去空白） */
    private Map<String, String> loadCheckConstraints() {
        Map<String, String> result = new LinkedHashMap<>();
        jdbcTemplate.queryForList(
                        "SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS")
                .forEach(row -> result.put((String) row.get("CONSTRAINT_NAME"),
                        normalize((String) row.get("CHECK_CLAUSE"))));
        return result;
    }

    private void verifyAccount(List<String> problems,
                               Map<String, Map<String, String>> columns,
                               Map<String, String> checks) {
        Map<String, String> cols = columns.get("ACCOUNT");
        if (cols == null) {
            return;
        }
        expectNotNull(problems, "account", cols,
                List.of("ID", "BALANCE", "VERSION", "CREATED_AT"));
        expectCheck(problems, "account", checks, "CHK_ACCOUNT_BALANCE_NONNEGATIVE", "BALANCE>=0");
        expectPrimaryKey(problems, "ACCOUNT", List.of("ID"));
    }

    private void verifyLedgerEntry(List<String> problems,
                                   Map<String, Map<String, String>> columns,
                                   Map<String, String> checks) {
        Map<String, String> cols = columns.get("LEDGER_ENTRY");
        if (cols == null) {
            return;
        }
        expectNotNull(problems, "ledger_entry", cols, List.of(
                "ID", "ACCOUNT_ID", "TYPE", "AMOUNT", "BALANCE_AFTER",
                "REQUEST_ID", "OPERATION_KIND", "CREATED_AT"));
        if (!"YES".equals(cols.get("DESCRIPTION"))) {
            problems.add("ledger_entry.description must remain nullable for backward compatibility");
        }
        expectCheck(problems, "ledger_entry", checks, "CHK_LEDGER_AMOUNT_POSITIVE", "AMOUNT>0");
        expectCheck(problems, "ledger_entry", checks, "CHK_LEDGER_TYPE_VALID",
                "TYPEIN('CREDIT','DEBIT')");
        expectCheck(problems, "ledger_entry", checks, "CHK_LEDGER_OPERATION_KIND_VALID",
                "OPERATION_KINDIN('CHANGE','TRANSFER')");
        expectPrimaryKey(problems, "LEDGER_ENTRY", List.of("ID"));
        expectUnique(problems, "LEDGER_ENTRY", "UK_LEDGER_REQUEST_ACCOUNT",
                List.of("REQUEST_ID", "ACCOUNT_ID"));
        expectForeignKey(problems, "LEDGER_ENTRY", "ACCOUNT_ID", "ACCOUNT", "ID");
        expectIndex(problems, "IDX_LEDGER_ACCOUNT_ID", List.of("ACCOUNT_ID", "ID"));
    }

    private void verifyLedgerOperation(List<String> problems,
                                       Map<String, Map<String, String>> columns,
                                       Map<String, String> checks) {
        Map<String, String> cols = columns.get("LEDGER_OPERATION");
        if (cols == null) {
            return;
        }
        expectNotNull(problems, "ledger_operation", cols, List.of(
                "REQUEST_ID", "KIND", "AMOUNT", "CREATED_AT"));
        expectCheck(problems, "ledger_operation", checks, "CHK_OPERATION_KIND_VALID",
                "KINDIN('CHANGE','TRANSFER')");
        expectCheck(problems, "ledger_operation", checks, "CHK_OPERATION_AMOUNT_POSITIVE", "AMOUNT>0");
        expectPrimaryKey(problems, "LEDGER_OPERATION", List.of("REQUEST_ID"));
        expectForeignKey(problems, "LEDGER_OPERATION", "SOURCE_ACCOUNT_ID", "ACCOUNT", "ID");
        expectForeignKey(problems, "LEDGER_OPERATION", "TARGET_ACCOUNT_ID", "ACCOUNT", "ID");
    }

    private void expectNotNull(List<String> problems, String table,
                               Map<String, String> cols, List<String> required) {
        for (String column : required) {
            String nullable = cols.get(column);
            if (nullable == null) {
                problems.add(table + "." + column.toLowerCase(Locale.ROOT) + " column is missing");
            } else if (!"NO".equals(nullable)) {
                problems.add(table + "." + column.toLowerCase(Locale.ROOT)
                        + " must be NOT NULL but actual IS_NULLABLE=" + nullable);
            }
        }
    }

    private void expectCheck(List<String> problems, String table, Map<String, String> checks,
                             String constraintName, String expectedExpressionFragment) {
        String expression = checks.get(constraintName);
        if (expression == null) {
            problems.add(table + " check constraint "
                    + constraintName.toLowerCase(Locale.ROOT) + " (" + expectedExpressionFragment
                    + ") is missing or has been renamed");
        } else if (!expression.contains(expectedExpressionFragment)) {
            problems.add(table + " check constraint "
                    + constraintName.toLowerCase(Locale.ROOT) + " expression differs from expected '"
                    + expectedExpressionFragment + "', actual='" + expression + "'");
        }
    }

    private void expectPrimaryKey(List<String> problems, String table, List<String> expectedColumns) {
        List<String> actual = jdbcTemplate.queryForList(
                "SELECT INDEX_COLUMNS.COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                        + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "ON TABLE_CONSTRAINTS.INDEX_NAME = INDEX_COLUMNS.INDEX_NAME "
                        + "WHERE TABLE_CONSTRAINTS.TABLE_NAME = ? "
                        + "AND TABLE_CONSTRAINTS.CONSTRAINT_TYPE = 'PRIMARY KEY' "
                        + "ORDER BY INDEX_COLUMNS.ORDINAL_POSITION",
                String.class, table);
        if (!actual.equals(expectedColumns)) {
            problems.add("primary key of " + table.toLowerCase(Locale.ROOT)
                    + " must be " + expectedColumns + " but actual columns are " + actual);
        }
    }

    private void expectUnique(List<String> problems, String table, String constraintName,
                              List<String> expectedColumns) {
        List<String> indexNames = jdbcTemplate.queryForList(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE CONSTRAINT_NAME = ? AND CONSTRAINT_TYPE = 'UNIQUE'",
                String.class, constraintName);
        List<String> actual = new ArrayList<>();
        for (String indexName : indexNames) {
            actual.addAll(jdbcTemplate.queryForList(
                    "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                            + "WHERE INDEX_NAME = ? ORDER BY ORDINAL_POSITION",
                    String.class, indexName));
        }
        if (!actual.equals(expectedColumns)) {
            problems.add("unique constraint " + constraintName.toLowerCase(Locale.ROOT) + " on "
                    + table.toLowerCase(Locale.ROOT) + " must cover " + expectedColumns
                    + " but actual columns are " + actual
                    + (actual.isEmpty() ? " (constraint missing, dropped or renamed)" : ""));
        }
    }

    private void expectIndex(List<String> problems, String indexName, List<String> expectedColumns) {
        List<String> actual = jdbcTemplate.queryForList(
                "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.INDEX_COLUMNS "
                        + "WHERE INDEX_NAME = ? ORDER BY ORDINAL_POSITION",
                String.class, indexName);
        if (!actual.equals(expectedColumns)) {
            problems.add("index " + indexName.toLowerCase(Locale.ROOT) + " must cover "
                    + expectedColumns + " but actual columns are " + actual);
        }
    }

    private void expectForeignKey(List<String> problems, String table, String column,
                                  String referencedTable, String referencedColumn) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT KCU.COLUMN_NAME AS COLUMN_NAME, "
                        + "REFC.TABLE_NAME AS REFERENCED_TABLE_NAME, "
                        + "REFC.COLUMN_NAME AS REFERENCED_COLUMN_NAME "
                        + "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE KCU "
                        + "JOIN INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS RC "
                        + "ON KCU.CONSTRAINT_NAME = RC.CONSTRAINT_NAME "
                        + "JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE REFC "
                        + "ON RC.UNIQUE_CONSTRAINT_NAME = REFC.CONSTRAINT_NAME "
                        + "WHERE KCU.TABLE_NAME = ? AND KCU.COLUMN_NAME = ?",
                table, column);
        boolean matches = rows.stream().anyMatch(r ->
                referencedTable.equals(r.get("REFERENCED_TABLE_NAME"))
                        && referencedColumn.equals(r.get("REFERENCED_COLUMN_NAME")));
        if (!matches) {
            problems.add("foreign key " + table.toLowerCase(Locale.ROOT) + "."
                    + column.toLowerCase(Locale.ROOT) + " -> "
                    + referencedTable.toLowerCase(Locale.ROOT) + "."
                    + referencedColumn.toLowerCase(Locale.ROOT) + " is missing or points elsewhere: " + rows);
        }
    }

    private static String normalize(String expression) {
        if (expression == null) {
            return "";
        }
        String normalized = expression.replaceAll("\\s+", "").replace("\"", "").toUpperCase(Locale.ROOT);
        // H2 会把带类型的字面量重写为 CAST(0 AS BIGINT)，去掉包装只留语义。
        String previous;
        do {
            previous = normalized;
            normalized = normalized.replaceAll("CAST\\(([^()]*)AS[A-Z]+\\)", "$1");
        } while (!normalized.equals(previous));
        return normalized;
    }
}
