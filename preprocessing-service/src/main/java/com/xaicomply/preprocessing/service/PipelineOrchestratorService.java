package com.xaicomply.preprocessing.service;

import com.xaicomply.common.dto.*;
import com.xaicomply.common.exception.XaiComplyException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * Orchestrates the full XAI-Comply pipeline:
 *
 *   [1] PreprocessingService  — normalize transaction (this service)
 *   [2] Inference & XAI       — ONNX prediction + SHAP + LIME  (port 8082)
 *   [3] Regulatory Mapping    — Eq. 3: R = Σ ωᵢ|φᵢ|           (port 8083)
 *   [4] Reporting Service     — persist record to audit log     (port 8084)
 *
 * Paper Figure 1 (high-level processing pipeline):
 *   Raw Transaction → Preprocessing → Inference & XAI → Regulatory Mapping
 *                  → Reporting → Regulatory Dashboard
 *
 * Each stage is a synchronous REST call. The full response is returned
 * to the client as a PipelineResponseDTO containing all stage outputs.
 */
@Service
public class PipelineOrchestratorService {

    private static final Logger log = LoggerFactory.getLogger(PipelineOrchestratorService.class);

    private final PreprocessingService preprocessingService;
    private final RestTemplate restTemplate;

    @Value("${services.inference.url}")
    private String inferenceServiceUrl;

    @Value("${services.mapping.url}")
    private String mappingServiceUrl;

    @Value("${services.reporting.url}")
    private String reportingServiceUrl;

    // Micrometer metrics
    private final Counter pipelineSuccessCounter;
    private final Counter pipelineErrorCounter;
    private final Counter violationCounter;
    private final Timer   pipelineTimer;

    public PipelineOrchestratorService(PreprocessingService preprocessingService,
                                        RestTemplate restTemplate,
                                        MeterRegistry meterRegistry) {
        this.preprocessingService = preprocessingService;
        this.restTemplate = restTemplate;

        this.pipelineSuccessCounter = Counter.builder("xaicomply_pipeline_success_total")
                .description("Total successful end-to-end pipeline runs")
                .register(meterRegistry);

        this.pipelineErrorCounter = Counter.builder("xaicomply_pipeline_errors_total")
                .description("Total pipeline errors")
                .register(meterRegistry);

        this.violationCounter = Counter.builder("xaicomply_pipeline_violations_total")
                .description("Total compliance violations detected")
                .register(meterRegistry);

        this.pipelineTimer = Timer.builder("xaicomply_pipeline_duration_ms")
                .description("End-to-end pipeline duration")
                .register(meterRegistry);
    }

    /**
     * Execute the full 4-stage pipeline for a single transaction.
     *
     * @param transaction raw client input
     * @return PipelineResponseDTO with all stage results
     */
    public PipelineResponseDTO runPipeline(TransactionDTO transaction) {
        String txId = transaction.getTransactionId();
        MDC.put("transactionId", txId);
        MDC.put("pipeline", "full");

        log.info("[Pipeline] ══════════════════════════════════════════════");
        log.info("[Pipeline] START transaction={}", txId);
        log.info("[Pipeline] ══════════════════════════════════════════════");

        long pipelineStart = System.currentTimeMillis();

        return pipelineTimer.record(() -> {
            try {
                return executePipeline(transaction, txId, pipelineStart);
            } catch (Exception e) {
                pipelineErrorCounter.increment();
                log.error("[Pipeline] FAILED transaction={} error={}", txId, e.getMessage(), e);
                return PipelineResponseDTO.error(txId, e.getMessage());
            } finally {
                MDC.remove("transactionId");
                MDC.remove("pipeline");
            }
        });
    }

    private PipelineResponseDTO executePipeline(TransactionDTO transaction,
                                                  String txId,
                                                  long pipelineStart) {
        // ── Stage 1: Preprocessing ────────────────────────────────────────────
        log.info("[Pipeline] ─── Stage 1/4: Preprocessing");
        long stageStart = System.currentTimeMillis();

        ProcessedTransactionDTO preprocessed = preprocessingService.preprocess(transaction);

        log.info("[Pipeline] Stage 1 DONE | normalizedAmount={} | normalizedTime={} | "
                        + "imputedFields={} | timeMs={}",
                preprocessed.getNormalizedAmount(), preprocessed.getNormalizedTime(),
                preprocessed.getImputedFields().size(),
                System.currentTimeMillis() - stageStart);

        // ── Stage 2: Inference & XAI ──────────────────────────────────────────
        log.info("[Pipeline] ─── Stage 2/4: Inference & XAI Service");
        log.info("[Pipeline] Calling: POST {}/api/v1/predict", inferenceServiceUrl);
        stageStart = System.currentTimeMillis();

        InferenceResultDTO inferenceResult = callInferenceService(txId, preprocessed);

        log.info("[Pipeline] Stage 2 DONE | riskScore={} | predictedClass={} | "
                        + "shapTopFeature={} | limeTopFeature={} | timeMs={}",
                inferenceResult.getRiskScore(),
                inferenceResult.getPredictedClass(),
                inferenceResult.getShapExplanation() != null
                        && !inferenceResult.getShapExplanation().getTopFeatures().isEmpty()
                        ? inferenceResult.getShapExplanation().getTopFeatures().get(0) : "N/A",
                inferenceResult.getLimeExplanation() != null
                        && !inferenceResult.getLimeExplanation().getTopFeatures().isEmpty()
                        ? inferenceResult.getLimeExplanation().getTopFeatures().get(0) : "N/A",
                System.currentTimeMillis() - stageStart);

        // ── Stage 3: Regulatory Mapping ────────────────────────────────────────
        log.info("[Pipeline] ─── Stage 3/4: Regulatory Mapping Service");
        log.info("[Pipeline] Calling: POST {}/api/v1/map", mappingServiceUrl);
        stageStart = System.currentTimeMillis();

        MappingResultDTO mappingResult = callMappingService(txId, inferenceResult);

        log.info("[Pipeline] Stage 3 DONE | regulatoryRiskScore={} | status={} | "
                        + "flagged={} | topRiskFeature={} | timeMs={}",
                mappingResult.getRegulatoryRiskScore(),
                mappingResult.getComplianceStatus(),
                mappingResult.getFlagged(),
                mappingResult.getTopRiskFeatures() != null
                        && !mappingResult.getTopRiskFeatures().isEmpty()
                        ? mappingResult.getTopRiskFeatures().get(0) : "N/A",
                System.currentTimeMillis() - stageStart);

        if (Boolean.TRUE.equals(mappingResult.getFlagged())) {
            violationCounter.increment();
            log.warn("[Pipeline] ⚠️  COMPLIANCE FLAG — txId={} status={} riskScore={}",
                    txId, mappingResult.getComplianceStatus(),
                    mappingResult.getRegulatoryRiskScore());
        }

        // ── Stage 4: Reporting ─────────────────────────────────────────────────
        log.info("[Pipeline] ─── Stage 4/4: Reporting Service");
        log.info("[Pipeline] Calling: POST {}/api/v1/records", reportingServiceUrl);
        stageStart = System.currentTimeMillis();

        saveToReportingService(txId, mappingResult);

        log.info("[Pipeline] Stage 4 DONE | record saved | timeMs={}",
                System.currentTimeMillis() - stageStart);

        // ── Assemble final response ────────────────────────────────────────────
        long totalMs = System.currentTimeMillis() - pipelineStart;
        pipelineSuccessCounter.increment();

        PipelineResponseDTO response = PipelineResponseDTO.builder()
                .transactionId(txId)
                .preprocessingResult(preprocessed)
                .inferenceResult(inferenceResult)
                .mappingResult(mappingResult)
                .complianceStatus(mappingResult.getComplianceStatus().name())
                .regulatoryRiskScore(mappingResult.getRegulatoryRiskScore())
                .flagged(mappingResult.getFlagged())
                .totalPipelineTimeMs(totalMs)
                .success(true)
                .build();

        log.info("[Pipeline] ══════════════════════════════════════════════");
        log.info("[Pipeline] COMPLETE txId={} | status={} | flagged={} | "
                        + "riskScore={} | totalTimeMs={}",
                txId, mappingResult.getComplianceStatus(),
                mappingResult.getFlagged(),
                mappingResult.getRegulatoryRiskScore(),
                totalMs);
        log.info("[Pipeline] ══════════════════════════════════════════════");

        return response;
    }

    // ─── REST calls to downstream services ───────────────────────────────────

    private InferenceResultDTO callInferenceService(String txId,
                                                     ProcessedTransactionDTO preprocessed) {
        String url = inferenceServiceUrl + "/api/v1/predict";
        log.debug("[Pipeline] POST {} txId={}", url, txId);
        try {
            ResponseEntity<InferenceResultDTO> response =
                    restTemplate.postForEntity(url, preprocessed, InferenceResultDTO.class);

            if (response.getBody() == null) {
                throw new XaiComplyException.InferenceException(
                        "Inference service returned empty response for txId=" + txId);
            }
            log.debug("[Pipeline] Inference response received: riskScore={}",
                    response.getBody().getRiskScore());
            return response.getBody();

        } catch (ResourceAccessException e) {
            log.error("[Pipeline] Cannot reach Inference service at {} — is it running?", url);
            log.error("[Pipeline] Start it with: cd inference-xai-service && mvn spring-boot:run");
            throw new XaiComplyException.InferenceException(
                    "Inference service unreachable at " + url + ": " + e.getMessage(), e);
        } catch (HttpServerErrorException | HttpClientErrorException e) {
            log.error("[Pipeline] Inference service error: status={} body={}",
                    e.getStatusCode(), e.getResponseBodyAsString());
            throw new XaiComplyException.InferenceException(
                    "Inference service returned error: " + e.getMessage(), e);
        }
    }

    private MappingResultDTO callMappingService(String txId,
                                                  InferenceResultDTO inferenceResult) {
        String url = mappingServiceUrl + "/api/v1/map";
        log.debug("[Pipeline] POST {} txId={}", url, txId);
        try {
            ResponseEntity<MappingResultDTO> response =
                    restTemplate.postForEntity(url, inferenceResult, MappingResultDTO.class);

            if (response.getBody() == null) {
                throw new XaiComplyException.MappingException(
                        "Mapping service returned empty response for txId=" + txId);
            }
            return response.getBody();

        } catch (ResourceAccessException e) {
            log.error("[Pipeline] Cannot reach Mapping service at {} — is it running?", url);
            throw new XaiComplyException.MappingException(
                    "Mapping service unreachable at " + url + ": " + e.getMessage(), e);
        }
    }

    private void saveToReportingService(String txId, MappingResultDTO mappingResult) {
        String url = reportingServiceUrl + "/api/v1/records";
        log.debug("[Pipeline] POST {} txId={}", url, txId);
        try {
            restTemplate.postForEntity(url, mappingResult, Void.class);
            log.debug("[Pipeline] Record saved to Reporting service for txId={}", txId);
        } catch (ResourceAccessException e) {
            // Non-fatal: reporting failure should not fail the pipeline
            log.warn("[Pipeline] Reporting service unreachable — record not persisted. " +
                            "txId={} error={}", txId, e.getMessage());
        }
    }

    /**
     * Check health of all downstream services.
     * Called by GET /api/v1/pipeline/health
     */
    public java.util.Map<String, String> checkDownstreamHealth() {
        log.info("[Pipeline] Checking health of all downstream services");
        java.util.Map<String, String> health = new java.util.LinkedHashMap<>();

        health.put("preprocessing", "UP (this service)");
        health.put("inference-xai",  checkServiceHealth(inferenceServiceUrl));
        health.put("regulatory-mapping", checkServiceHealth(mappingServiceUrl));
        health.put("reporting",       checkServiceHealth(reportingServiceUrl));

        log.info("[Pipeline] Health check results: {}", health);
        return health;
    }

    private String checkServiceHealth(String baseUrl) {
        try {
            String url = baseUrl + "/actuator/health";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            log.debug("[Pipeline] Health {} → {}", url, response.getStatusCode());
            return response.getStatusCode().is2xxSuccessful() ? "UP" : "DEGRADED";
        } catch (Exception e) {
            log.warn("[Pipeline] Health check failed for {}: {}", baseUrl, e.getMessage());
            return "DOWN — " + e.getMessage();
        }
    }
}
