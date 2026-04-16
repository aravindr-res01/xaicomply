package com.xaicomply.common.dto;

import com.xaicomply.common.enums.ComplianceStatus;
import com.xaicomply.common.enums.RiskLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Output of the Regulatory Mapping Engine.
 *
 * Implements Eq. 3 from the paper:
 *   R = Σᵢ ωᵢ |φᵢ|
 *
 * where:
 *   ωᵢ = expert-defined weight for feature i (from WeightMatrixConfig)
 *   φᵢ = SHAP attribution for feature i
 *   R   = scalar regulatory compliance risk score
 *
 * If R > τ (threshold), the transaction is flagged for compliance review.
 * τ is tuned via grid search on held-out compliance labels (Section 3.3).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MappingResultDTO {

    /** Transaction ID from upstream services */
    private String transactionId;

    // ── Regulatory Risk Score (Eq. 3) ────────────────────────────────────────

    /**
     * Regulatory risk score R = Σ ωᵢ|φᵢ| ∈ [0, 1].
     * Computed using SHAP attributions (primary) with LIME for cross-validation.
     */
    private Double regulatoryRiskScore;

    /** Raw ONNX model risk score (pre-mapping) for comparison */
    private Double modelRiskScore;

    // ── Compliance Decision ───────────────────────────────────────────────────

    /** Threshold τ used for this classification */
    private Double threshold;

    /** Whether R > τ (transaction flagged for compliance action) */
    private Boolean flagged;

    /** Three-level compliance classification */
    private ComplianceStatus complianceStatus;

    /** Risk band derived from regulatory risk score */
    private RiskLevel riskLevel;

    // ── Explanation Summary ───────────────────────────────────────────────────

    /**
     * Top risk-driving features sorted by ωᵢ|φᵢ| descending.
     * Used in compliance report narrative.
     */
    private List<String> topRiskFeatures;

    /**
     * Per-feature weighted contribution: feature → ωᵢ|φᵢ|
     * Full breakdown for audit trail transparency.
     */
    private Map<String, Double> weightedContributions;

    /** Which regulatory taxonomy drove this flag (AML, KYC, CREDIT) */
    private String regulatoryCategory;

    // ── Audit metadata ───────────────────────────────────────────────────────

    /** Timestamp of mapping completion */
    @Builder.Default
    private Instant mappingTimestamp = Instant.now();

    /** Time taken for mapping computation in ms */
    private Long mappingTimeMs;

    /** Weight matrix version used */
    @Builder.Default
    private String weightMatrixVersion = "1.0";

    /** Optional: true label for performance evaluation */
    private Integer trueLabel;
}
