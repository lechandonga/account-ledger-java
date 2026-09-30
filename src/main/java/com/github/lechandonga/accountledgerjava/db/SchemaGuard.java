package com.github.lechandonga.accountledgerjava.db;

import com.github.lechandonga.accountledgerjava.error.SchemaMismatchException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 结构核对（两层，均不得静默）：
 * <ol>
 *   <li>Flyway validate：校验和不符、迁移缺失/错位时启动失败；</li>
 *   <li>逐约束核对实际数据库：非空、取值范围（CHECK）、唯一、主键、外键、
 *       索引只要与预期不一致（被人删掉/改掉/重命名），就明确抛出
 *       {@link SchemaMismatchException}，而不是继续运行在残缺结构上。</li>
 * </ol>
 * 核对结果按信息架构（INFORMATION_SCHEMA）实时读取，约束被删改都会被抓到。
 */
@Component
public class SchemaGuard implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SchemaGuard.class);

    private final Flyway flyway;
    private final JdbcTemplate jdbcTemplate;

    public SchemaGuard(Flyway flyway, JdbcTemplate jdbcTemplate) {
        this.flyway = flyway;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) {
        verify();
    }

    /** 执行全部结构核对；不一致时抛出 SchemaMismatchException（测试中可随时调用）。 */
    public void verify() {
        ValidateResult result = flyway.validateWithResult();
        if (!result.validationSuccessful) {
            String detail = result.errorDetails != null ? result.errorDetails.errorMessage : "unknown";
            log.error("flyway validation failed: {}", detail);
            throw new SchemaMismatchException("database schema does not match expected migrations: " + detail, null);
        }

        List<String> problems = new ArrayList<>();
        checkTablesExist(problems);
        if (problems.isEmpty()) {
            checkNonNullable(problems);
            checkCheckConstraints(problems);
            checkUniqueConstraints(problems);
            checkPrimaryKeys(problems);
            checkForeignKeys(problems);
            checkIndexes(problems);
        }

        if (!problems.isEmpty()) {
            log.error("live schema verification failed with {} mismatch(es): {}", problems.size(), problems);
            throw new SchemaMismatchException(
                    "live database schema mismatch: " + String.join("; ", problems), null);
        }
        log.info("schema verification passed: flyway validate ok, {} migrations applied, live constraints match",
                flyway.info().applied().length);
    }

    private void checkTablesExist(List<String> problems) {
        Set<String> tables = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = SCHEMA()",
                String.class));
        for (String expected : List.of("ACCOUNT", "LEDGER_ENTRY", "LEDGER_REQUEST")) {
            if (!tables.contains(expected)) {
                problems.add("missing table " + expected);
            }
        }
    }

    /** 预期列必须存在且 NOT NULL（信息架构 IS_NULLABLE = 'NO'）。 */
    private void checkNonNullable(List<String> problems) {
        Map<String, Set<String>> notNull = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT TABLE_NAME, COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS "
                        + "WHERE TABLE_SCHEMA = SCHEMA() AND IS_NULLABLE = 'NO'",
                (RowCallbackHandler) rs -> notNull.computeIfAbsent(rs.getString("TABLE_NAME"), k -> new HashSet<>())
                        .add(rs.getString("COLUMN_NAME")));
        expectColumns(problems, notNull, "ACCOUNT", List.of("ID", "BALANCE", "VERSION", "CREATED_AT"));
        expectColumns(problems, notNull, "LEDGER_ENTRY",
                List.of("ID", "ACCOUNT_ID", "TYPE", "AMOUNT", "BALANCE_AFTER", "REQUEST_ID", "CREATED_AT"));
        expectColumns(problems, notNull, "LEDGER_REQUEST", List.of("ID", "OP_KIND", "CREATED_AT"));
    }

    private void expectColumns(List<String> problems, Map<String, Set<String>> actual,
                               String table, List<String> columns) {
        Set<String> present = actual.getOrDefault(table, Set.of());
        for (String column : columns) {
            if (!present.contains(column)) {
                problems.add(table + "." + column + " must be NOT NULL");
            }
        }
    }

    /**
     * CHECK 约束核对：约束名必须存在，并规范化表达式比对关键取值范围，
     * 防止“名字还在、语义被改”。表达式统一大写并去除全部空白后比较。
     */
    private void checkCheckConstraints(List<String> problems) {
        Map<String, String> checks = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT CONSTRAINT_NAME, CHECK_CLAUSE FROM INFORMATION_SCHEMA.CHECK_CONSTRAINTS "
                        + "WHERE CONSTRAINT_SCHEMA = SCHEMA()",
                (RowCallbackHandler) rs -> checks.put(rs.getString("CONSTRAINT_NAME"), rs.getString("CHECK_CLAUSE")));

        expectCheck(problems, checks, "CHK_ACCOUNT_BALANCE_NONNEGATIVE", "BALANCE>=0");
        expectCheck(problems, checks, "CHK_LEDGER_AMOUNT_POSITIVE", "AMOUNT>0");
        expectCheck(problems, checks, "CHK_LEDGER_TYPE_VALID", "TYPEIN('CREDIT','DEBIT')");
        expectCheck(problems, checks, "CHK_LEDGER_BALANCE_AFTER_NONNEGATIVE", "BALANCE_AFTER>=0");
        expectCheck(problems, checks, "CHK_LEDGER_REQUEST_KIND", "OP_KINDIN('SINGLE_CHANGE','TRANSFER')");
    }

    private void expectCheck(List<String> problems, Map<String, String> actual,
                             String name, String... expectedExpressions) {
        String expression = actual.get(name);
        if (expression == null) {
            problems.add("missing CHECK constraint " + name);
        } else if (Arrays.stream(expectedExpressions).noneMatch(e -> normalize(expression).contains(e))) {
            problems.add("CHECK constraint " + name + " has unexpected expression: " + expression);
        }
    }

    /** 唯一性按“约束名 + 列集合（顺序无关）”比对。 */
    private void checkUniqueConstraints(List<String> problems) {
        Map<String, Set<String>> constrainedColumns = constrainedColumns();

        expectUnique(problems, constrainedColumns, "UK_LEDGER_ACCOUNT_REQUEST",
                Set.of("ACCOUNT_ID", "REQUEST_ID"));

        // 旧的单列唯一约束必须已不存在，否则同一次划转的两条流水无法共存。
        if (constrainedColumns.containsKey("UK_LEDGER_REQUEST_ID")) {
            problems.add("obsolete unique constraint UK_LEDGER_REQUEST_ID must not exist after V3");
        }
    }

    private void expectUnique(List<String> problems, Map<String, Set<String>> actual,
                              String name, Set<String> columns) {
        Set<String> actualColumns = actual.get(name);
        if (actualColumns == null) {
            problems.add("missing UNIQUE constraint " + name + " on LEDGER_ENTRY");
        } else if (!actualColumns.equals(columns)) {
            problems.add("UNIQUE constraint " + name + " columns mismatch: expected "
                    + columns + " but was " + actualColumns);
        }
    }

    /** 取全部 UNIQUE / PRIMARY KEY 约束名到其列集合。 */
    private Map<String, Set<String>> constrainedColumns() {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT kcu.CONSTRAINT_NAME, kcu.COLUMN_NAME "
                        + "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu "
                        + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                        + "ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME AND tc.TABLE_NAME = kcu.TABLE_NAME "
                        + "AND tc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA "
                        + "WHERE kcu.CONSTRAINT_SCHEMA = SCHEMA() "
                        + "AND tc.CONSTRAINT_TYPE IN ('UNIQUE', 'PRIMARY KEY')",
                (RowCallbackHandler) rs -> result.computeIfAbsent(rs.getString("CONSTRAINT_NAME"), k -> new HashSet<>())
                        .add(rs.getString("COLUMN_NAME")));
        return result;
    }

    private void checkPrimaryKeys(List<String> problems) {
        // H2 对未显式命名的主键自动生成 PRIMARY_KEY_x 名称，因此按“表 -> 主键列集合”语义核对。
        Map<String, Set<String>> primaryKeyColumns = new LinkedHashMap<>();
        jdbcTemplate.query(
                "SELECT kcu.TABLE_NAME, kcu.COLUMN_NAME "
                        + "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu "
                        + "JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc "
                        + "ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME AND tc.TABLE_NAME = kcu.TABLE_NAME "
                        + "AND tc.CONSTRAINT_SCHEMA = kcu.CONSTRAINT_SCHEMA "
                        + "WHERE kcu.CONSTRAINT_SCHEMA = SCHEMA() AND tc.CONSTRAINT_TYPE = 'PRIMARY KEY'",
                (RowCallbackHandler) rs ->
                        primaryKeyColumns.computeIfAbsent(rs.getString("TABLE_NAME"), k -> new HashSet<>())
                                .add(rs.getString("COLUMN_NAME")));
        for (String table : List.of("ACCOUNT", "LEDGER_ENTRY", "LEDGER_REQUEST")) {
            if (!primaryKeyColumns.getOrDefault(table, Set.of()).equals(Set.of("ID"))) {
                problems.add("primary key on " + table + " must be exactly column ID but was "
                        + primaryKeyColumns.get(table));
            }
        }
    }

    /** 外键按 (表, 列, 引用表, 引用列) 语义比对，列上必须存在引用关系。 */
    private void checkForeignKeys(List<String> problems) {
        List<FkRef> fks = loadFkRefs();
        expectFk(problems, fks, "LEDGER_ENTRY", "ACCOUNT_ID", "ACCOUNT", "ID");
        expectFk(problems, fks, "LEDGER_ENTRY", "REQUEST_ID", "LEDGER_REQUEST", "ID");
    }

    private List<FkRef> loadFkRefs() {
        // H2 2.x：REFERENTIAL_CONSTRAINTS 给出外键约束与其引用的唯一约束，
        // KEY_COLUMN_USAGE 给出外键列；引用表/列由被引用的主键约束反查。
        List<FkRef> fks = new ArrayList<>();
        jdbcTemplate.query(
                "SELECT rc.CONSTRAINT_NAME, rc.UNIQUE_CONSTRAINT_NAME "
                        + "FROM INFORMATION_SCHEMA.REFERENTIAL_CONSTRAINTS rc "
                        + "WHERE rc.CONSTRAINT_SCHEMA = SCHEMA()",
                (RowCallbackHandler) rs -> {
                    String fkName = rs.getString("CONSTRAINT_NAME");
                    String uniqueName = rs.getString("UNIQUE_CONSTRAINT_NAME");
                    String fkTable = single(
                            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                                    + "WHERE CONSTRAINT_SCHEMA = SCHEMA() AND CONSTRAINT_NAME = ?", fkName);
                    String refTable = single(
                            "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                                    + "WHERE CONSTRAINT_SCHEMA = SCHEMA() AND CONSTRAINT_NAME = ?", uniqueName);
                    List<String> fkColumns = jdbcTemplate.queryForList(
                            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                                    + "WHERE CONSTRAINT_SCHEMA = SCHEMA() AND CONSTRAINT_NAME = ? ORDER BY ORDINAL_POSITION",
                            String.class, fkName);
                    List<String> refColumns = jdbcTemplate.queryForList(
                            "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE "
                                    + "WHERE CONSTRAINT_SCHEMA = SCHEMA() AND CONSTRAINT_NAME = ? ORDER BY ORDINAL_POSITION",
                            String.class, uniqueName);
                    for (int i = 0; i < Math.min(fkColumns.size(), refColumns.size()); i++) {
                        fks.add(new FkRef(fkTable, fkColumns.get(i), refTable, refColumns.get(i)));
                    }
                });
        return fks;
    }

    private String single(String sql, String arg) {
        List<String> rows = jdbcTemplate.queryForList(sql, String.class, arg);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void expectFk(List<String> problems, List<FkRef> fks,
                          String table, String column, String refTable, String refColumn) {
        boolean exists = fks.stream().anyMatch(f -> table.equals(f.table()) && column.equals(f.column())
                && refTable.equals(f.refTable()) && refColumn.equals(f.refColumn()));
        if (!exists) {
            problems.add("missing FOREIGN KEY " + table + "." + column + " -> " + refTable + "." + refColumn);
        }
    }

    private void checkIndexes(List<String> problems) {
        Set<String> indexes = new HashSet<>(jdbcTemplate.queryForList(
                "SELECT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES "
                        + "WHERE TABLE_SCHEMA = SCHEMA() AND TABLE_NAME = 'LEDGER_ENTRY'",
                String.class));
        if (!indexes.contains("IDX_LEDGER_ACCOUNT_ID")) {
            problems.add("missing index IDX_LEDGER_ACCOUNT_ID on LEDGER_ENTRY");
        }
    }

    private static String normalize(String expression) {
        // H2 会规范化成 "BALANCE" >= CAST(0 AS BIGINT)：去掉标识符引号与类型转换噪声后比对语义。
        String compact = expression.replaceAll("\\s+", "").replace("\"", "").toUpperCase(Locale.ROOT);
        compact = compact.replaceAll("CAST\\(([^)]*)\\)", "0");
        return compact;
    }

    private record FkRef(String table, String column, String refTable, String refColumn) {
    }
}
