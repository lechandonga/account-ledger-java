package com.github.lechandonga.accountledgerjava.repository;

import com.github.lechandonga.accountledgerjava.domain.Direction;
import com.github.lechandonga.accountledgerjava.domain.EntryType;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.support.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;

@Repository
public class JdbcLedgerEntryRepository implements LedgerEntryRepository {

    private static final String COLUMN_LIST =
            "id, request_id, account_no, counterpart_no, direction, amount_cents, "
                    + "balance_after, entry_type, entry_time";

    private static final RowMapper<LedgerEntry> ROW_MAPPER = (rs, rowNum) -> new LedgerEntry(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("account_no"),
            rs.getString("counterpart_no"),
            Direction.valueOf(rs.getString("direction")),
            Money.toYuan(rs.getLong("amount_cents")),
            Money.toYuan(rs.getLong("balance_after")),
            EntryType.valueOf(rs.getString("entry_type")),
            rs.getTimestamp("entry_time").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public JdbcLedgerEntryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public LedgerEntry insert(String requestId, String accountNo, String counterpartNo,
                              Direction direction, long amountCents, long balanceAfterCents,
                              EntryType entryType) {
        String sql = "INSERT INTO ledger_entries "
                + "(request_id, account_no, counterpart_no, direction, amount_cents, balance_after, entry_type) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setString(1, requestId);
            ps.setString(2, accountNo);
            ps.setString(3, counterpartNo);
            ps.setString(4, direction.name());
            ps.setLong(5, amountCents);
            ps.setLong(6, balanceAfterCents);
            ps.setString(7, entryType.name());
            return ps;
        }, keyHolder);
        long id = keyHolder.getKey().longValue();
        return findById(id).orElseThrow();
    }

    @Override
    public Optional<LedgerEntry> findById(long id) {
        String sql = "SELECT " + COLUMN_LIST + " FROM ledger_entries WHERE id = ?";
        List<LedgerEntry> rows = jdbcTemplate.query(sql, ROW_MAPPER, id);
        return rows.stream().findFirst();
    }

    /**
     * 游标分页：id 为单调自增主键，id > afterId 严格升序取 limit 条。
     * 新写入的流水只会出现在尾部，不影响已发布游标之前的窗口，因此不重不漏。
     */
    @Override
    public List<LedgerEntry> findPage(String accountNo, long afterId, int limit) {
        if (accountNo == null) {
            String sql = "SELECT " + COLUMN_LIST + " FROM ledger_entries "
                    + "WHERE id > ? ORDER BY id ASC LIMIT ?";
            return jdbcTemplate.query(sql, ROW_MAPPER, afterId, limit);
        }
        String sql = "SELECT " + COLUMN_LIST + " FROM ledger_entries "
                + "WHERE account_no = ? AND id > ? ORDER BY id ASC LIMIT ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, accountNo, afterId, limit);
    }
}
