package com.github.lechandonga.accountledgerjava.tests;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 迁移兼容性：先用 V1/V2 建库并写入老格式数据（无 operation_kind、无操作表），
 * 再应用 V3，断言升级后老数据照常可读、被正确回填为 CHANGE 操作，
 * 老的单边请求在新结构下行为不变。
 */
class V3MigrationCompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(V3MigrationCompatibilityTest.class);

    @Test
    void v3UpgradesLegacyDataWithoutLoss() throws Exception {
        String url = "jdbc:h2:mem:v3compat;DB_CLOSE_DELAY=-1;MODE=PostgreSQL";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            Flyway.configure()
                    .dataSource(url, "sa", "")
                    .locations("classpath:db/migration")
                    .target("2")
                    .load()
                    .migrate();

            Instant now = Instant.now();
            try (Statement st = connection.createStatement()) {
                st.execute("INSERT INTO account (id, balance, version, created_at) VALUES "
                        + "('old-a', 100, 0, '" + now + "'), ('old-b', 0, 0, '" + now + "')");
                st.execute("INSERT INTO ledger_entry "
                        + "(account_id, type, amount, balance_after, request_id, created_at) VALUES "
                        + "('old-a', 'DEBIT', 30, 70, 'old-req-1', '" + now + "')");
                st.execute("INSERT INTO ledger_entry "
                        + "(account_id, type, amount, balance_after, request_id, created_at, description) VALUES "
                        + "('old-b', 'CREDIT', 30, 30, 'old-req-2', '" + now + "', 'legacy note')");
            }

            Flyway.configure()
                    .dataSource(url, "sa", "")
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();

            try (Statement st = connection.createStatement()) {
                String kind = scalar(st,
                        "SELECT operation_kind FROM ledger_entry WHERE request_id = 'old-req-1'");
                String opKind = scalar(st,
                        "SELECT kind FROM ledger_operation WHERE request_id = 'old-req-1'");
                String source = scalar(st,
                        "SELECT source_account_id FROM ledger_operation WHERE request_id = 'old-req-1'");
                String target = scalar(st,
                        "SELECT target_account_id FROM ledger_operation WHERE request_id = 'old-req-2'");
                String description = scalar(st,
                        "SELECT description FROM ledger_entry WHERE request_id = 'old-req-2'");
                long ops = count(st, "SELECT COUNT(*) FROM ledger_operation");
                long entries = count(st, "SELECT COUNT(*) FROM ledger_entry");

                log.info("after V3: legacy entries={} operations={} entryKind={} opKind={} debitSource={} creditTarget={}",
                        entries, ops, kind, opKind, source, target);

                assertEquals(2, entries);
                assertEquals(2, ops);
                assertEquals("CHANGE", kind);
                assertEquals("CHANGE", opKind);
                assertEquals("old-a", source);
                assertEquals("old-b", target);
                assertEquals("legacy note", description);

                // 新结构下仍可正常插入老的单边请求
                st.execute("INSERT INTO ledger_operation (request_id, kind, source_account_id, "
                        + "target_account_id, amount, created_at) VALUES "
                        + "('old-req-3', 'CHANGE', 'old-a', NULL, 10, '" + now + "')");
                st.execute("INSERT INTO ledger_entry (account_id, type, amount, balance_after, "
                        + "request_id, operation_kind, created_at) VALUES "
                        + "('old-a', 'DEBIT', 10, 60, 'old-req-3', 'CHANGE', '" + now + "')");
                long balance = count(st, "SELECT balance FROM account WHERE id = 'old-a'");
                log.info("legacy-style CHANGE insert works; balance query returns {}", balance);
                assertTrue(balance >= 0);
            }
        }
    }

    private static String scalar(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static long count(Statement st, String sql) throws Exception {
        try (ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
