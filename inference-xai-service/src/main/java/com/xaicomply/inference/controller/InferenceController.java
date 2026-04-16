package com.xaicomply.inference.controller;

import com.xaicomply.common.dto.InferenceResultDTO;
import com.xaicomply.common.dto.ProcessedTransactionDTO;
import com.xaicomply.common.exception.XaiComplyException;
import com.xaicomply.inference.service.InferenceOrchestrationService;
import com.xaicomply.inference.service.OnnxInferenceService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST controller for the Inference & XAI Service.
 *
 * Endpoints:
 *   POST /api/v1/predict   — run ONNX + SHAP + LIME for a processed transaction
 *   GET  /api/v1/model/info — ONNX model status
 */
@RestController
@RequestMapping("/api/v1")
public class InferenceController {

    private static final Logger log = LoggerFactory.getLogger(InferenceController.class);

    private final InferenceOrchestrationService orchestrationService;
    private final OnnxInferenceService onnxService;

    public InferenceController(InferenceOrchestrationService orchestrationService,
                                OnnxInferenceService onnxService) {
        this.orchestrationService = orchestrationService;
        this.onnxService = onnxService;
    }

    /**
     * Run ONNX inference + SHAP + LIME explanation on a preprocessed transaction.
     * Called by the Preprocessing Service pipeline orchestrator.
     */
    @PostMapping("/predict")
    public ResponseEntity<InferenceResultDTO> predict(
            @Valid @RequestBody ProcessedTransactionDTO processedTransaction) {

        String txId = processedTransaction.getTransactionId();
        MDC.put("transactionId", txId);

        log.info("[InferenceController] POST /api/v1/predict txId={}", txId);
        log.debug("[InferenceController] Received ProcessedTransactionDTO: "
                        + "normalizedAmount={} normalizedTime={}",
                processedTransaction.getNormalizedAmount(),
                processedTransaction.getNormalizedTime());

        try {
            InferenceResultDTO result = orchestrationService.runInferenceAndExplain(processedTransaction);

            log.info("[InferenceController] Response: txId={} riskScore={} class={} totalMs={}",
                    txId, result.getRiskScore(), result.getPredictedClass(),
                    result.getTotalExplanationTimeMs());

            return ResponseEntity.ok(result);

        } catch (XaiComplyException e) {
            log.error("[InferenceController] XAI error txId={}: {}", txId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } finally {
            MDC.remove("transactionId");
        }
    }

    @GetMapping("/model/info")
    public ResponseEntity<Map<String, Object>> modelInfo() {
        log.info("[InferenceController] GET /api/v1/model/info");
        return ResponseEntity.ok(Map.of(
                "modelLoaded", onnxService.isModelLoaded(),
                "modelType",   "RandomForestClassifier",
                "onnxRuntime", ai.onnxruntime.OrtEnvironment.VERSION,
                "nFeatures",   30,
                "inputName",   "float_input",
                "outputProbName", "output_probability",
                "fraudClassIndex", 1
        ));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        log.error("[InferenceController] Unhandled error: {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", e.getClass().getSimpleName(), "message", e.getMessage()));
    }
}
