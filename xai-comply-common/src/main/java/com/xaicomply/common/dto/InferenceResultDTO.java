package com.xaicomply.common.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Output of the Inference & XAI Service.
 *
 * Contains:
 *   1. ONNX model prediction (raw risk score 0–1)
 *   2. SHAP explanation  (Eq. 1: f(x) = φ₀ + Σφᵢ)
 *   3. LIME explanation  (Eq. 2: sparse surrogate model)
 *
 * Both explanations are computed per-transaction and forwarded to
 * the Regulatory Mapping Engine to compute compliance risk score R (Eq. 3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InferenceResultDTO {

    /** Transaction ID from preprocessing */
    private String transactionId;

    /**
     * Raw model output: probability of fraud ∈ [0, 1].
     * Produced by ONNX Runtime invoking the pre-trained RandomForest/LightGBM.
     * Sub-50 ms target per paper (Section 2.3).
     */
    private Double riskScore;

    /** Predicted class: 0 = legitimate, 1 = fraud */
    private Integer predictedClass;

    /** Confidence: max(riskScore, 1-riskScore) */
    private Double confidence;

    // ── XAI Explanations ─────────────────────────────────────────────────────

    /**
     * SHAP explanation.
     * Uses TreeExplainer (O(T·D·n) exact Shapley values for tree models).
     * φᵢ values here feed into Eq. 3: R = Σ ωᵢ|φᵢ|
     */
    private ExplanationDTO shapExplanation;

    /**
     * LIME explanation.
     * Sparse surrogate linear model fitted on 500 perturbed samples around input.
     * Provides secondary validation of SHAP attributions.
     */
    private ExplanationDTO limeExplanation;

    // ── Timing metadata ───────────────────────────────────────────────────────

    /** ONNX inference time in ms */
    private Long inferenceTimeMs;

    /** Total time including both SHAP + LIME in ms */
    private Long totalExplanationTimeMs;

    /** Timestamp of inference completion */
    @Builder.Default
    private Instant inferenceTimestamp = Instant.now();

    /** ONNX model version used */
    @Builder.Default
    private String modelVersion = "1.0.0";

    /** Optional: forwarded true label for evaluation */
    private Integer trueLabel;
}
