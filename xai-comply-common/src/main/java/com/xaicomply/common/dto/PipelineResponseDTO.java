package com.xaicomply.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Full end-to-end pipeline response returned to the client.
 *
 * Aggregates outputs from all 4 pipeline stages:
 *   1. ProcessedTransactionDTO  (Preprocessing Service)
 *   2. InferenceResultDTO       (Inference & XAI Service)
 *   3. MappingResultDTO         (Regulatory Mapping Service)
 *   4. Report save confirmation (Reporting Service)
 *
 * This is the response to POST /api/v1/pipeline/run
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PipelineResponseDTO {

    private String transactionId;

    // ── Stage outputs ─────────────────────────────────────────────────────────
    private ProcessedTransactionDTO preprocessingResult;
    private InferenceResultDTO      inferenceResult;
    private MappingResultDTO        mappingResult;

    // ── Final verdict ─────────────────────────────────────────────────────────
    private String  complianceStatus;  // "COMPLIANT" | "REVIEW" | "VIOLATION"
    private Double  regulatoryRiskScore;
    private Boolean flagged;

    // ── Pipeline timing ───────────────────────────────────────────────────────
    /** Total wall-clock time from ingestion to compliance decision (ms) */
    private Long totalPipelineTimeMs;

    @Builder.Default
    private Instant completedAt = Instant.now();

    /** Whether this response was fully successful */
    @Builder.Default
    private Boolean success = true;

    private String errorMessage;

    // ── Convenience factory for error responses ───────────────────────────────
    public static PipelineResponseDTO error(String transactionId, String message) {
        return PipelineResponseDTO.builder()
                .transactionId(transactionId)
                .success(false)
                .errorMessage(message)
                .build();
    }
}
