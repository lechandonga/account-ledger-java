package com.github.lechandonga.accountledgerjava.repository;

import com.github.lechandonga.accountledgerjava.domain.IdempotentRecord;
import com.github.lechandonga.accountledgerjava.domain.OperationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JdbcIdempotentRequestRepository implements IdempotentRequestRepository {

    private static final RowMapper<IdempotentRecord> ROW_MAPPER = (rs, rowNum) -> new IdempotentRecord(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("request_type"),
            rs.getString("request_digest"),
            OperationStatus.valueOf(rs.getString("status")),
            rs.getString("response_body"));

    private final JdbcTemplate jdbcTemplate;

    public JdbcIdempotentRequestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<IdempotentRecord> findByRequestId(String requestId) {
        String sql = "SELECT id, request_id, request_type, request_digest, status, response_body "
                + "FROM idempotent_requests WHERE request_id = ?";
        List<IdempotentRecord> rows = jdbcTemplate.query(sql, ROW_MAPPER, requestId);
        return rows.stream().findFirst();
    }

    @Override
    public void claim(String requestId, String requestType, String requestDigest) {
        String sql = "INSERT INTO idempotent_requests "
                + "(request_id, request_type, request_digest, status, response_body) "
                + "VALUES (?, ?, ?, 'CLAIMED', NULL)";
        // request_id 唯一键冲突 -> DuplicateKeyException，由协调层进入等待重放
        jdbcTemplate.update(sql, requestId, requestType, requestDigest);
    }

    @Override
    public void completeTerminal(String requestId, OperationStatus status, String responseBody) {
        String sql = "UPDATE idempotent_requests SET status = ?, response_body = ?, "
                + "finished_at = CURRENT_TIMESTAMP WHERE request_id = ? AND status = 'CLAIMED'";
        int updated = jdbcTemplate.update(sql, status.name(), responseBody, requestId);
        if (updated != 1) {
            throw new IllegalStateException("幂等占位行状态异常，无法置为终态: " + requestId);
        }
    }

    @Override
    public void releaseClaim(String requestId) {
        jdbcTemplate.update("DELETE FROM idempotent_requests WHERE request_id = ? AND status = 'CLAIMED'",
                requestId);
    }
}
