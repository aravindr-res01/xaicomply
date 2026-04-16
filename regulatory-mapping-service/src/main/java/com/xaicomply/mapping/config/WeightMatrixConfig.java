package com.xaicomply.mapping.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Weight matrix ω for the Regulatory Mapping Algorithm (Eq. 3):
 *
 *   R = Σᵢ ωᵢ |φᵢ|
 *
 * where ωᵢ = regulatory risk weight for feature i,
 *       φᵢ = SHAP attribution for feature i.
 *
 * Weights are derived from expert-defined taxonomies per EBA Opinion 2021/01
 * and Basel BCBS 239 guidelines (paper Section 2.2):
 *
 * ┌──────────┬────────┬────────────────────────────────────────────────────┐
 * │ Feature  │  ωᵢ   │ Regulatory Rationale                               │
 * ├──────────┼────────┼────────────────────────────────────────────────────┤
 * │ Amount   │ 0.300  │ AML structuring risk — highest weight              │
 * │ V1       │ 0.100  │ PCA1 — velocity/frequency proxy                    │
 * │ V2       │ 0.080  │ PCA2 — merchant category anomaly proxy             │
 * │ V3       │ 0.070  │ PCA3 — geographic risk proxy                       │
 * │ V4       │ 0.060  │ PCA4 — counterparty risk                           │
 * │ V5–V10   │ 0.025  │ Supporting risk signals                            │
 * │ V11–V20  │ 0.015  │ Secondary indicators                               │
 * │ V21–V28  │ 0.008  │ Background features                                │
 * │ Time     │ 0.020  │ Off-hours / velocity temporal flag                 │
 * └──────────┴────────┴────────────────────────────────────────────────────┘
 *
 * Total weights sum to ≈ 1.0 (normalized).
 *
 * Threshold τ is tuned via grid search on held-out compliance labels.
 * Paper reports conservative τ = 0.35 achieving Precision=0.87, Recall=0.03.
 */
@Configuration
public class WeightMatrixConfig {

    private static final Logger log = LoggerFactory.getLogger(WeightMatrixConfig.class);

    @Value("${mapping.threshold.tau:0.35}")
    private double tau;

    @Value("${mapping.threshold.review:0.20}")
    private double reviewThreshold;

    @Value("${mapping.weight.matrix.version:1.0}")
    private String version;

    private Map<String, Double> weights;

    @PostConstruct
    public void initializeWeights() {
        log.info("[WeightMatrix] Initializing regulatory weight matrix v{}", version);
        log.info("[WeightMatrix] Violation threshold τ = {}", tau);
        log.info("[WeightMatrix] Review threshold    = {}", reviewThreshold);

        Map<String, Double> w = new LinkedHashMap<>();

        // High-weight regulatory features
        w.put("Amount", 0.300);  // AML structuring risk
        w.put("V1",     0.100);  // Velocity proxy
        w.put("V2",     0.080);  // Merchant category anomaly
        w.put("V3",     0.070);  // Geographic risk
        w.put("V4",     0.060);  // Counterparty risk
        w.put("Time",   0.020);  // Off-hours / temporal velocity

        // Supporting risk signals
        w.put("V5",  0.025);
        w.put("V6",  0.025);
        w.put("V7",  0.025);
        w.put("V8",  0.025);
        w.put("V9",  0.025);
        w.put("V10", 0.025);

        // Secondary indicators
        w.put("V11", 0.015); w.put("V12", 0.015); w.put("V13", 0.015);
        w.put("V14", 0.015); w.put("V15", 0.015); w.put("V16", 0.015);
        w.put("V17", 0.015); w.put("V18", 0.015); w.put("V19", 0.015);
        w.put("V20", 0.015);

        // Background features
        w.put("V21", 0.008); w.put("V22", 0.008); w.put("V23", 0.008);
        w.put("V24", 0.008); w.put("V25", 0.008); w.put("V26", 0.008);
        w.put("V27", 0.008); w.put("V28", 0.008);

        this.weights = Collections.unmodifiableMap(w);

        // Log weight matrix summary
        double totalWeight = weights.values().stream().mapToDouble(Double::doubleValue).sum();
        log.info("[WeightMatrix] Weight matrix loaded: {} features, total weight = {}",
                weights.size(), totalWeight);

        if (Math.abs(totalWeight - 1.0) > 0.05) {
            log.warn("[WeightMatrix] Total weight {} deviates >5% from 1.0 — "
                    + "consider normalizing", totalWeight);
        }

        log.info("[WeightMatrix] Top 5 weights:");
        weights.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(5)
                .forEach(e -> log.info("[WeightMatrix]   {} = {}", e.getKey(), e.getValue()));
    }

    public Map<String, Double> getWeights() { return weights; }
    public double getTau()             { return tau; }
    public double getReviewThreshold() { return reviewThreshold; }
    public String getVersion()         { return version; }

    /**
     * Get weight for a specific feature.
     * Returns a small default weight if feature is not in the matrix.
     */
    public double getWeight(String featureName) {
        return weights.getOrDefault(featureName, 0.005);
    }
}
