package com.xaicomply.mapping.controller;

import com.xaicomply.common.dto.InferenceResultDTO;
import com.xaicomply.common.dto.MappingResultDTO;
import com.xaicomply.mapping.config.WeightMatrixConfig;
import com.xaicomply.mapping.service.RegulatoryMappingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class MappingController {

    private static final Logger log = LoggerFactory.getLogger(MappingController.class);

    private final RegulatoryMappingService mappingService;
    private final WeightMatrixConfig weightConfig;

    public MappingController(RegulatoryMappingService mappingService,
                              WeightMatrixConfig weightConfig) {
        this.mappingService = mappingService;
        this.weightConfig = weightConfig;
    }

    /**
     * Apply regulatory mapping algorithm (Eq. 3) to an inference result.
     * Returns compliance risk score R and status.
     */
    @PostMapping("/map")
    public ResponseEntity<MappingResultDTO> map(
            @RequestBody InferenceResultDTO inferenceResult) {

        String txId = inferenceResult.getTransactionId();
        MDC.put("transactionId", txId);

        log.info("[MappingController] POST /api/v1/map txId={} riskScore={}",
                txId, inferenceResult.getRiskScore());

        try {
            MappingResultDTO result = mappingService.mapToComplianceRisk(inferenceResult);

            log.info("[MappingController] Response: txId={} R={} status={} flagged={}",
                    txId, result.getRegulatoryRiskScore(),
                    result.getComplianceStatus(), result.getFlagged());

            return ResponseEntity.ok(result);
        } finally {
            MDC.remove("transactionId");
        }
    }

    /** Return the current weight matrix ω for audit transparency. */
    @GetMapping("/weights")
    public ResponseEntity<Map<String, Object>> getWeightMatrix() {
        log.info("[MappingController] GET /api/v1/weights — returning weight matrix v{}",
                weightConfig.getVersion());
        return ResponseEntity.ok(Map.of(
                "version",          weightConfig.getVersion(),
                "tauViolation",     weightConfig.getTau(),
                "tauReview",        weightConfig.getReviewThreshold(),
                "weights",          weightConfig.getWeights(),
                "algorithm",        "R = Σ ωᵢ|φᵢ| (paper Eq.3)",
                "normalization",    "sigmoid(R)"
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        log.error("[MappingController] Error: {}", e.getMessage(), e);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getClass().getSimpleName(), "message", e.getMessage()));
    }
}
