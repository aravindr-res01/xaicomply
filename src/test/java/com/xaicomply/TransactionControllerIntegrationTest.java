package com.xaicomply;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class TransactionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static final String API_KEY = "dev-api-key-xai-comply-2024";

    @Test
    void postValidTransaction_shouldReturn200WithRiskScoreAndAttributions() throws Exception {
        String body = """
            {
              "customerId": "cust-001",
              "amount": 1500.00,
              "currency": "USD",
              "merchantCategoryCode": "5411",
              "countryCode": "US",
              "transactionVelocity": 5,
              "isInternational": false,
              "hourOfDay": 14
            }
            """;

        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.transactionId").exists())
                .andExpect(jsonPath("$.data.riskScore").exists())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        Map<?, ?> response = objectMapper.readValue(responseBody, Map.class);
        Map<?, ?> data = (Map<?, ?>) response.get("data");

        assertThat(data.get("riskScore")).isNotNull();
        assertThat(data.get("top5Attributions")).isNotNull();
        assertThat((java.util.List<?>) data.get("top5Attributions")).hasSize(5);
    }

    @Test
    void postTransactionMissingAmount_shouldReturn400WithFieldErrors() throws Exception {
        String body = """
            {
              "customerId": "cust-002",
              "currency": "USD",
              "merchantCategoryCode": "5411",
              "countryCode": "US",
              "transactionVelocity": 3,
              "isInternational": false,
              "hourOfDay": 10
            }
            """;

        MvcResult result = mockMvc.perform(post("/api/v1/transactions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn();

        String responseBody = result.getResponse().getContentAsString();
        assertThat(responseBody).containsIgnoringCase("amount");
    }

    @Test
    void postValidTransaction_thenExplainWithShap_shouldReturn200MethodShap() throws Exception {
        // First create a transaction
        String body = """
            {
              "customerId": "cust-003",
              "amount": 250.00,
              "currency": "EUR",
              "merchantCategoryCode": "5912",
              "countryCode": "GB",
              "transactionVelocity": 2,
              "isInternational": true,
              "hourOfDay": 22
            }
            """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/transactions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        Map<?, ?> createResponse = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), Map.class);
        Map<?, ?> data = (Map<?, ?>) createResponse.get("data");
        String txId = (String) data.get("transactionId");

        // Then explain with SHAP
        String explainBody = """
            { "method": "SHAP" }
            """;

        mockMvc.perform(post("/api/v1/transactions/" + txId + "/explain")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(explainBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.method").value("SHAP"))
                .andExpect(jsonPath("$.data.transactionId").value(txId));
    }

    @Test
    void postValidTransaction_thenExplainWithLime_shouldReturn200MethodLime() throws Exception {
        // First create a transaction
        String body = """
            {
              "customerId": "cust-004",
              "amount": 5000.00,
              "currency": "USD",
              "merchantCategoryCode": "5999",
              "countryCode": "CN",
              "transactionVelocity": 10,
              "isInternational": true,
              "hourOfDay": 3
            }
            """;

        MvcResult createResult = mockMvc.perform(post("/api/v1/transactions")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        Map<?, ?> createResponse = objectMapper.readValue(
                createResult.getResponse().getContentAsString(), Map.class);
        Map<?, ?> data = (Map<?, ?>) createResponse.get("data");
        String txId = (String) data.get("transactionId");

        // Explain with LIME
        String explainBody = """
            { "method": "LIME" }
            """;

        mockMvc.perform(post("/api/v1/transactions/" + txId + "/explain")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(explainBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.method").value("LIME"))
                .andExpect(jsonPath("$.data.transactionId").value(txId));
    }

    @Test
    void missingApiKey_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/transactions"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongApiKey_shouldReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/transactions")
                        .header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }
}
