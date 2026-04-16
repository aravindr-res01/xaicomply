package com.xaicomply.inference.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.xaicomply.common.dto.ExplanationDTO;
import com.xaicomply.common.enums.ExplainerType;
import com.xaicomply.common.exception.XaiComplyException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * HTTP client that calls the Python FastAPI explainer sidecar (port 8085).
 *
 * Endpoints called:
 *   POST http://localhost:8085/explain/shap — SHAP TreeExplainer (Eq. 1)
 *   POST http://localhost:8085/explain/lime — LIME Tabular        (Eq. 2)
 *
 * Paper Section 2.1:
 *   "By packaging both SHAP4J (Java) and a Dockerized Python LIME service,
 *    XAI-Comply allows comparing explanation latency, fidelity, and stability
 *    in production environments."
 *
 * On Python sidecar failure, falls back to zero attributions to keep
 * the pipeline running (graceful degradation).
 */
@Service
public class ExplainerClientService {

    private static final Logger log = LoggerFactory.getLogger(ExplainerClientService.class);

    @Value("${services.python-explainer.url:http://localhost:8085}")
    private String pythonExplainerUrl;

    private final RestTemplate restTemplate;
    private final Timer   shapTimer;
    private final Timer   limeTimer;
    private final Counter shapSuccessCounter;
    private final Counter shapFallbackCounter;
    private final Counter limeFallbackCounter;

    // Feature names in ONNX input order
    private static final String[] FEATURE_NAMES = {
            "Time",
            "V1","V2","V3","V4","V5","V6","V7","V8","V9","V10",
            "V11","V12","V13","V14","V15","V16","V17","V18","V19","V20",
            "V21","V22","V23","V24","V25","V26","V27","V28",
            "Amount"
    };

    public ExplainerClientService(RestTemplate restTemplate, MeterRegistry meterRegistry) {
        this.restTemplate = restTemplate;
        this.shapTimer = Timer.builder("xaicomply_shap_duration_ms")
                .description("SHAP explanation duration ms").register(meterRegistry);
        this.limeTimer = Timer.builder("xaicomply_lime_duration_ms")
                .description("LIME explanation duration ms").register(meterRegistry);
        this.shapSuccessCounter = Counter.builder("xaicomply_shap_success_total")
                .register(meterRegistry);
        this.shapFallbackCounter = Counter.builder("xaicomply_shap_fallback_total")
                .description("SHAP fallback (Python sidecar unavailable)").register(meterRegistry);
        this.limeFallbackCounter = Counter.builder("xaicomply_lime_fallback_total")
                .description("LIME fallback (Python sidecar unavailable)").register(meterRegistry);
    }

    // ─── SHAP ─────────────────────────────────────────────────────────────────

    /**
     * Get SHAP attributions from Python sidecar.
     *
     * Implements Eq. 1: f(x) = φ₀ + Σφᵢ
     * Target latency: ~15ms at low load (paper Section 3.5)
     */
    public ExplanationDTO getShapExplanation(String transactionId, float[] features) {
        log.info("[ExplainerClient] Requesting SHAP explanation txId={}", transactionId);
        log.debug("[ExplainerClient] SHAP feature vector length={}", features.length);

        return shapTimer.record(() -> {
            try {
                return fetchExplanation(transactionId, features, "/explain/shap",
                        ExplainerType.SHAP);
            } catch (ResourceAccessException e) {
                log.warn("[ExplainerClient] Python sidecar unreachable for SHAP txId={} — "
                        + "using fallback. Start with: uvicorn main:app --port 8085",
                        transactionId);
                shapFallbackCounter.increment();
                return buildFallbackExplanation(transactionId, features, ExplainerType.SHAP);
            } catch (Exception e) {
                log.error("[ExplainerClient] SHAP error txId={}: {}", transactionId, e.getMessage(), e);
                shapFallbackCounter.increment();
                return buildFallbackExplanation(transactionId, features, ExplainerType.SHAP);
            }
        });
    }

    // ─── LIME ─────────────────────────────────────────────────────────────────

    /**
     * Get LIME attributions from Python sidecar.
     *
     * Implements Eq. 2: argmin[ℒ(f,g,πₓ) + Ω(g)]
     * Target latency: ~30ms at low load (paper Section 3.5)
     */
    public ExplanationDTO getLimeExplanation(String transactionId, float[] features) {
        log.info("[ExplainerClient] Requesting LIME explanation txId={}", transactionId);
        log.debug("[ExplainerClient] LIME feature vector length={}", features.length);

        return limeTimer.record(() -> {
            try {
                return fetchExplanation(transactionId, features, "/explain/lime",
                        ExplainerType.LIME);
            } catch (ResourceAccessException e) {
                log.warn("[ExplainerClient] Python sidecar unreachable for LIME txId={} — "
                        + "using fallback.", transactionId);
                limeFallbackCounter.increment();
                return buildFallbackExplanation(transactionId, features, ExplainerType.LIME);
            } catch (Exception e) {
                log.error("[ExplainerClient] LIME error txId={}: {}", transactionId, e.getMessage(), e);
                limeFallbackCounter.increment();
                return buildFallbackExplanation(transactionId, features, ExplainerType.LIME);
            }
        });
    }

    // ─── Common fetch logic ───────────────────────────────────────────────────

    private ExplanationDTO fetchExplanation(String transactionId, float[] features,
                                             String endpoint, ExplainerType type) {
        String url = pythonExplainerUrl + endpoint;
        log.debug("[ExplainerClient] POST {} txId={}", url, transactionId);

        long start = System.currentTimeMillis();

        // Build request body
        ExplainRequest request = new ExplainRequest();
        request.setTransactionId(transactionId);
        request.setFeatures(toFloatList(features));
        request.setFeatureNames(Arrays.asList(FEATURE_NAMES));

        ResponseEntity<ExplainResponse> response = restTemplate.postForEntity(
                url, request, ExplainResponse.class);

        long elapsed = System.currentTimeMillis() - start;

        if (response.getBody() == null) {
            throw new XaiComplyException.ExplainerException(
                    "Empty response from " + type + " explainer for txId=" + transactionId);
        }

        ExplainResponse body = response.getBody();
        shapSuccessCounter.increment();

        log.info("[ExplainerClient] {} explanation received txId={} | "
                        + "topFeature={} ({}) | baseValue={} | timeMs={}",
                type, transactionId,
                body.getTopFeatures() != null && !body.getTopFeatures().isEmpty()
                        ? body.getTopFeatures().get(0) : "N/A",
                body.getTopFeatures() != null && !body.getTopFeatures().isEmpty()
                        && body.getFeatureAttributions() != null
                        ? body.getFeatureAttributions().getOrDefault(body.getTopFeatures().get(0), 0.0)
                        : 0.0,
                body.getBaseValue() != null ? body.getBaseValue() : 0.0,
                elapsed);

        if (log.isDebugEnabled()) {
            log.debug("[ExplainerClient] {} top 5 attributions: {}",
                    type, getTop5Attribution(body.getFeatureAttributions()));
        }

        return ExplanationDTO.builder()
                .explainerType(type)
                .featureAttributions(body.getFeatureAttributions())
                .baseValue(body.getBaseValue())
                .predictedValue(body.getPredictedValue())
                .topFeatures(body.getTopFeatures())
                .computationTimeMs(elapsed)
                .computedAt(Instant.now())
                .sampleCount(body.getSampleCount())
                .fromCache(false)
                .build();
    }

    // ─── Fallback when Python sidecar is not available ────────────────────────

    /**
     * Zero-attribution fallback used when Python sidecar is unreachable.
     * Keeps the pipeline running; mapping service will still work with
     * model risk score, but SHAP-based R computation will be 0.
     */
    private ExplanationDTO buildFallbackExplanation(String transactionId,
                                                      float[] features,
                                                      ExplainerType type) {
        log.warn("[ExplainerClient] Building FALLBACK {} explanation for txId={} "
                        + "— all attributions set to 0.0", type, transactionId);

        Map<String, Double> zeroAttributions = new LinkedHashMap<>();
        for (String name : FEATURE_NAMES) {
            zeroAttributions.put(name, 0.0);
        }

        return ExplanationDTO.builder()
                .explainerType(type)
                .featureAttributions(zeroAttributions)
                .baseValue(0.0)
                .predictedValue(0.0)
                .topFeatures(List.of("FALLBACK"))
                .computationTimeMs(0L)
                .computedAt(Instant.now())
                .sampleCount(0)
                .fromCache(false)
                .build();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private List<Float> toFloatList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }

    private String getTop5Attribution(Map<String, Double> attributions) {
        if (attributions == null) return "{}";
        return attributions.entrySet().stream()
                .sorted((a, b) -> Double.compare(Math.abs(b.getValue()), Math.abs(a.getValue())))
                .limit(5)
                .map(e -> String.format("%s=%.4f", e.getKey(), e.getValue()))
                .collect(Collectors.joining(", ", "{", "}"));
    }

    // ─── Request/Response POJOs ───────────────────────────────────────────────

    @Data
    static class ExplainRequest {
        @JsonProperty("transaction_id") private String transactionId;
        private List<Float> features;
        @JsonProperty("feature_names")  private List<String> featureNames;
    }

    @Data
    static class ExplainResponse {
        @JsonProperty("transaction_id")      private String transactionId;
        @JsonProperty("explainer_type")      private String explainerType;
        @JsonProperty("feature_attributions") private Map<String, Double> featureAttributions;
        @JsonProperty("base_value")          private Double baseValue;
        @JsonProperty("predicted_value")     private Double predictedValue;
        @JsonProperty("top_features")        private List<String> topFeatures;
        @JsonProperty("computation_time_ms") private Double computationTimeMs;
        @JsonProperty("sample_count")        private Integer sampleCount;
    }
}
