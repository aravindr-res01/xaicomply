package com.xaicomply.preprocessing.controller;

import com.xaicomply.common.dto.PipelineResponseDTO;
import com.xaicomply.common.dto.ProcessedTransactionDTO;
import com.xaicomply.common.dto.TransactionDTO;
import com.xaicomply.common.exception.XaiComplyException;
import com.xaicomply.preprocessing.service.PipelineOrchestratorService;
import com.xaicomply.preprocessing.service.PreprocessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "XAI-Comply Pipeline",
     description = "Preprocessing and full pipeline orchestration. Main entry: POST /api/v1/pipeline/run")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);
    private final PreprocessingService preprocessingService;
    private final PipelineOrchestratorService orchestratorService;

    public TransactionController(PreprocessingService preprocessingService,
                                  PipelineOrchestratorService orchestratorService) {
        this.preprocessingService = preprocessingService;
        this.orchestratorService  = orchestratorService;
    }

    @Operation(summary = "Preprocess a transaction (isolation test)",
               description = "Validates, imputes, and z-score normalizes a transaction. Does NOT call downstream services.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Preprocessed successfully",
            content = @Content(schema = @Schema(implementation = ProcessedTransactionDTO.class))),
        @ApiResponse(responseCode = "400", description = "Invalid input")
    })
    @PostMapping(value = "/transactions", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ProcessedTransactionDTO> preprocessTransaction(
            @Valid @RequestBody TransactionDTO transaction) {
        String txId = ensureTransactionId(transaction);
        MDC.put("transactionId", txId);
        log.info("[Controller] POST /api/v1/transactions txId={}", txId);
        try {
            return ResponseEntity.ok(preprocessingService.preprocess(transaction));
        } catch (XaiComplyException.PreprocessingException e) {
            log.error("[Controller] Preprocessing failed: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } finally { MDC.remove("transactionId"); }
    }

    @Operation(
        summary = "Run full XAI-Comply pipeline (MAIN ENTRY POINT)",
        description = """
            Executes all 4 pipeline stages:
            1. Preprocessing — z-score normalize, validate
            2. Inference + XAI — ONNX prediction + SHAP (Eq.1) + LIME (Eq.2)
            3. Regulatory Mapping — R = Σ ωᵢ|φᵢ| (Eq.3), apply threshold τ=0.35
            4. Reporting — persist to H2 audit log
            
            Returns compliance status: COMPLIANT | REVIEW | VIOLATION
            
            Paper results: Precision=0.87, Recall=0.03, F1=0.06, 70% fewer exceptions.
            """)
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Pipeline complete",
            content = @Content(schema = @Schema(implementation = PipelineResponseDTO.class))),
        @ApiResponse(responseCode = "500", description = "Pipeline error")
    })
    @PostMapping(value = "/pipeline/run", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<PipelineResponseDTO> runPipeline(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                description = "Transaction to process",
                content = @Content(examples = {
                    @ExampleObject(name = "Legitimate (COMPLIANT)", value = """
                        {"transactionId":"demo-legit-001","time":406.0,
                         "v1":-1.36,"v2":-0.07,"v3":2.54,"v4":1.38,"v5":-0.34,
                         "v6":0.46,"v7":0.24,"v8":0.10,"v9":0.36,"v10":0.09,
                         "v11":-0.55,"v12":-0.62,"v13":-0.99,"v14":-0.31,"v15":1.47,
                         "v16":-0.47,"v17":0.21,"v18":0.03,"v19":0.40,"v20":0.25,
                         "v21":-0.02,"v22":0.28,"v23":-0.11,"v24":0.07,"v25":0.13,
                         "v26":-0.19,"v27":0.13,"v28":-0.02,"amount":149.62,"true_label":0}
                        """),
                    @ExampleObject(name = "Fraud (VIOLATION)", value = """
                        {"transactionId":"demo-fraud-001","time":100.0,
                         "v1":-3.04,"v2":-3.16,"v3":-1.29,"v4":3.97,"v5":-0.46,
                         "v6":-1.56,"v7":-1.55,"v8":0.06,"v9":-2.02,"v10":-2.30,
                         "v11":2.50,"v12":-4.97,"v13":1.80,"v14":-7.25,"v15":0.20,
                         "v16":-3.22,"v17":-4.89,"v18":-2.05,"v19":0.26,"v20":0.09,
                         "v21":0.27,"v22":-0.72,"v23":0.24,"v24":0.29,"v25":-0.07,
                         "v26":-0.33,"v27":0.07,"v28":0.04,"amount":2125.87,"true_label":1}
                        """)
                }))
            @Valid @RequestBody TransactionDTO transaction) {
        String txId = ensureTransactionId(transaction);
        MDC.put("transactionId", txId);
        log.info("[Controller] POST /api/v1/pipeline/run txId={}", txId);
        try {
            PipelineResponseDTO result = orchestratorService.runPipeline(transaction);
            return ResponseEntity.status(result.getSuccess() ? HttpStatus.OK
                    : HttpStatus.INTERNAL_SERVER_ERROR).body(result);
        } catch (Exception e) {
            log.error("[Controller] Pipeline error: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(PipelineResponseDTO.error(txId, e.getMessage()));
        } finally { MDC.remove("transactionId"); }
    }

    @Operation(summary = "Check all downstream services health")
    @GetMapping("/pipeline/health")
    public ResponseEntity<Map<String, String>> pipelineHealth() {
        Map<String, String> health = orchestratorService.checkDownstreamHealth();
        boolean allUp = health.values().stream().noneMatch(s -> s.startsWith("DOWN"));
        return ResponseEntity.status(allUp ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(health);
    }

    @Operation(summary = "Service info")
    @GetMapping("/info")
    public ResponseEntity<Map<String, String>> info() {
        return ResponseEntity.ok(Map.of("service","preprocessing-service","port","8081",
                "swagger","http://localhost:8081/swagger-ui.html"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        return ResponseEntity.internalServerError().body(Map.of("error",e.getClass().getSimpleName(),"message",e.getMessage()));
    }

    private String ensureTransactionId(TransactionDTO tx) {
        if (tx.getTransactionId() == null || tx.getTransactionId().isBlank())
            tx.setTransactionId(UUID.randomUUID().toString());
        return tx.getTransactionId();
    }
}
