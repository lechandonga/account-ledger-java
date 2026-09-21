package com.github.lechandonga.accountledgerjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口层异常归类：不同失败映射到不同 HTTP 状态与 errorCode；
 * 重复提交 200 且 replay=true；余额不足 422；非法分页 400。
 */
@SpringBootTest
@AutoConfigureMockMvc
class WebApiTest {

    private static final Logger log = LoggerFactory.getLogger(WebApiTest.class);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;

    private String accountNo;

    @BeforeEach
    void setup() throws Exception {
        accountNo = "WEB-" + UUID.randomUUID();
        mockMvc.perform(post("/api/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "accountNo", accountNo,
                                "accountName", "接口测试",
                                "initialBalance", new BigDecimal("100.00"),
                                "currency", "CNY"))))
                .andExpect(status().isCreated());
    }

    @Test
    void insufficient_funds_maps_to_422_with_distinguishable_code() throws Exception {
        String req = "REQ-" + UUID.randomUUID();
        MvcResult result = mockMvc.perform(post("/api/withdrawals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "requestId", req,
                                "accountNo", accountNo,
                                "amount", new BigDecimal("150.00")))))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        log.info("判定依据: HTTP=422 status={} rejectCode={}",
                body.get("status").asText(), body.get("rejectCode").asText());
        assertEquals("REJECTED", body.get("status").asText());
        assertEquals("INSUFFICIENT_FUNDS", body.get("rejectCode").asText());
    }

    @Test
    void duplicate_request_returns_200_replay_with_same_entry_id() throws Exception {
        String req = "REQ-" + UUID.randomUUID();
        String payload = objectMapper.writeValueAsString(Map.of(
                "requestId", req,
                "accountNo", accountNo,
                "amount", new BigDecimal("10.00")));

        MvcResult first = mockMvc.perform(post("/api/deposits")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn();
        MvcResult second = mockMvc.perform(post("/api/deposits")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk()).andReturn();

        JsonNode firstBody = objectMapper.readTree(first.getResponse().getContentAsString());
        JsonNode secondBody = objectMapper.readTree(second.getResponse().getContentAsString());
        log.info("判定依据: 首次replay={} 重放replay={} 流水id一致={}",
                firstBody.get("replay").asBoolean(),
                secondBody.get("replay").asBoolean(),
                firstBody.get("entries").get(0).get("id").asLong()
                        == secondBody.get("entries").get(0).get("id").asLong());
        assertFalse(firstBody.get("replay").asBoolean());
        assertTrue(secondBody.get("replay").asBoolean());
        assertEquals(firstBody.get("entries").get(0).get("id"),
                secondBody.get("entries").get(0).get("id"));
    }

    @Test
    void invalid_paging_and_unknown_account_are_mapped() throws Exception {
        MvcResult badSize = mockMvc.perform(get("/api/ledger-entries").param("size", "0"))
                .andExpect(status().isBadRequest()).andReturn();
        JsonNode sizeBody = objectMapper.readTree(badSize.getResponse().getContentAsString());
        assertEquals("INVALID_REQUEST", sizeBody.get("code").asText());

        MvcResult missing = mockMvc.perform(get("/api/accounts/NO-SUCH-ACCOUNT"))
                .andExpect(status().isNotFound()).andReturn();
        JsonNode missingBody = objectMapper.readTree(missing.getResponse().getContentAsString());
        log.info("判定依据: 非法分页code={} 未知账户code={}",
                sizeBody.get("code").asText(), missingBody.get("code").asText());
        assertEquals("ACCOUNT_NOT_FOUND", missingBody.get("code").asText());
    }
}
