package com.xaicomply.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

/**
 * Output of the Preprocessing Service.
 *
 * Holds the original transaction plus normalization metadata:
 *   - z-score scaled Amount and Time (mean/std from training data)
 *   - The final feature float[] ready for ONNX model input
 *   - Imputation flags indicating which fields (if any) were filled
 *
 * Passed downstream to the Inference & XAI Service.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessedTransactionDTO {

    /** ID carried from the original TransactionDTO */
    private String transactionId;

    /** Original raw transaction for audit trail */
    private TransactionDTO originalTransaction;

    // ── Normalized values ────────────────────────────────────────────────────

    /** Z-score normalized Amount: (amount - mean_amount) / std_amount */
    private Double normalizedAmount;

    /** Z-score normalized Time: (time - mean_time) / std_time */
    private Double normalizedTime;

    /**
     * Final feature vector for ONNX inference.
     * Order: [normalizedTime, V1..V28, normalizedAmount] — 30 features.
     * V1–V28 are already PCA-scaled so passed through unchanged.
     */
    private float[] featureVector;

    /** Feature names parallel to featureVector for explanation labelling */
    private String[] featureNames;

    // ── Preprocessing metadata ───────────────────────────────────────────────

    /** Fields that were imputed (should be empty in normal operation) */
    private List<String> imputedFields;

    /** Timestamp when preprocessing completed */
    @Builder.Default
    private Instant preprocessedAt = Instant.now();

    /** Wall-clock time taken by preprocessing step in milliseconds */
    private Long preprocessingTimeMs;

    /** Schema version used for this preprocessing run */
    @Builder.Default
    private String schemaVersion = "1.0";

    /** Optional: true label forwarded for evaluation purposes */
    private Integer trueLabel;
}
