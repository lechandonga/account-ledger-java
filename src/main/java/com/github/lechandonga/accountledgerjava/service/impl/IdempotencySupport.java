package com.github.lechandonga.accountledgerjava.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.lechandonga.accountledgerjava.domain.LedgerEntry;
import com.github.lechandonga.accountledgerjava.domain.LedgerOperationResult;
import com.github.lechandonga.accountledgerjava.exception.PersistenceFailureException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 幂等辅助：
 * <ul>
 *   <li>对“幂等键 + 规范化业务入参”计算 SHA-256 摘要，识别同键不同体的误用；</li>
 *   <li>把首发终态（成功/业务拒绝）序列化为 JSON 存库，重放时原样还原，
 *       保证重复请求返回与首次一致的结果。</li>
 * </ul>
 */
@Component
public class IdempotencySupport {

    private final ObjectMapper objectMapper;

    public IdempotencySupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 规范化摘要：字段名固定、顺序固定（LinkedHashMap），避免键序差异 */
    public String digest(String requestType, Map<String, ?> payload) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("type", requestType);
        canonical.put("payload", new LinkedHashMap<>(payload));
        String json;
        try {
            json = objectMapper.writeValueAsString(canonical);
        } catch (JsonProcessingException e) {
            throw new PersistenceFailureException("幂等摘要计算失败", e);
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(json.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new PersistenceFailureException("SHA-256 不可用", e);
        }
    }

    public String serializeResult(LedgerOperationResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new PersistenceFailureException("幂等结果序列化失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    public LedgerOperationResult deserializeResult(String requestId, String body) {
        try {
            Map<String, Object> map = objectMapper.readValue(body, Map.class);
            java.util.List<LedgerEntry> entries = objectMapper.convertValue(
                    map.getOrDefault("entries", java.util.List.of()),
                    objectMapper.getTypeFactory().constructCollectionType(
                            java.util.List.class, LedgerEntry.class));
            return new LedgerOperationResult(
                    com.github.lechandonga.accountledgerjava.domain.OperationStatus
                            .valueOf((String) map.get("status")),
                    true,
                    requestId,
                    (String) map.get("rejectCode"),
                    (String) map.get("rejectReason"),
                    entries);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new PersistenceFailureException("幂等结果反序列化失败: requestId=" + requestId, e);
        }
    }
}
