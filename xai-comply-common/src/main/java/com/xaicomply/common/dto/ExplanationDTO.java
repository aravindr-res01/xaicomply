package com.xaicomply.common.dto;

import com.xaicomply.common.enums.ExplainerType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Feature attribution explanation produced by SHAP or LIME.
 *
 * SHAP implements Eq. 1 (paper):
 *   f(x) = φ₀ + Σᵢφᵢ
 *   where φ₀ = base value (expected model output over background)
 *         φᵢ = Shapley value for feature i
 *
 * LIME implements Eq. 2 (paper):
 *   argmin_{g∈G} [ℒ(f, g, πₓ) + Ω(g)]
 *   where g = sparse surrogate linear model
 *         πₓ = locality kernel weighting perturbed samples
 *
 * featureAttributions maps feature name → attribution value φᵢ.
 * Positive φᵢ increases fraud probability; negative φᵢ decreases it.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExplanationDTO {

    /** Which explainer produced this explanation */
    private ExplainerType explainerType;

    /**
     * Feature attributions φᵢ for each input feature.
     * Stored as LinkedHashMap to preserve feature order.
     * Key = feature name (e.g. "Amount", "V14")
     * Value = attribution φᵢ (can be negative)
     */
    @Builder.Default
    private Map<String, Double> featureAttributions = new LinkedHashMap<>();

    /**
     * SHAP: base value φ₀ = E[f(x)] over background dataset.
     * LIME: intercept of the surrogate linear model.
     */
    private Double baseValue;

    /**
     * Model output for this specific transaction f(x).
     * Should equal baseValue + Σ|featureAttributions| for SHAP (local accuracy).
     */
    private Double predictedValue;

    /**
     * Top-N features sorted by |φᵢ| descending.
     * Useful for human-readable compliance reports.
     */
    private List<String> topFeatures;

    /** Wall-clock time to compute this explanation (ms) */
    private Long computationTimeMs;

    /** Timestamp when explanation was computed */
    @Builder.Default
    private Instant computedAt = Instant.now();

    /**
     * For SHAP: number of background samples used.
     * For LIME: number of perturbation samples generated.
     */
    private Integer sampleCount;

    /** Whether this explanation was served from cache */
    @Builder.Default
    private Boolean fromCache = false;

    /**
     * Compute the sum of absolute attributions Σ|φᵢ|.
     * Used as input to the Regulatory Mapping Algorithm (Eq. 3).
     */
    public double sumAbsoluteAttributions() {
        return featureAttributions.values().stream()
                .mapToDouble(Math::abs)
                .sum();
    }
}
