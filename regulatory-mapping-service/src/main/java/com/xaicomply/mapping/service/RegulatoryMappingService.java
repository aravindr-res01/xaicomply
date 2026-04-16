package com.xaicomply.mapping.service;

import com.xaicomply.common.dto.ExplanationDTO;
import com.xaicomply.common.dto.InferenceResultDTO;
import com.xaicomply.common.dto.MappingResultDTO;
import com.xaicomply.common.enums.ComplianceStatus;
import com.xaicomply.common.enums.RiskLevel;
import com.xaicomply.mapping.config.WeightMatrixConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core implementation of the Regulatory Mapping Algorithm.
 *
 * Paper Eq. 3:
 *   R = Σᵢ ωᵢ |φᵢ|
 *
 * where:
 *   ωᵢ  = expert-defined regulatory weight for feature i (from WeightMatrixConfig)
 *   φᵢ  = SHAP attribution value for feature i
 *   |·|  = absolute value (both positive & negative attributions elevate risk)
 *   R    = scalar regulatory compliance risk score ∈ [0, ∞) normalized to [0, 1]
 *
 * Compliance decision:
 *   R < τ_review   → COMPLIANT
 *   τ_review ≤ R < τ_violation → REVIEW
 *   R ≥ τ_violation → VIOLATION
 *
 * Paper Section 3.3 reports τ = 0.35 (conservative, Precision=0.87)
 * which reduces manual review workload by 70% vs baseline.
 */
@Service
public class RegulatoryMappingService {

    private static final Logger log = LoggerFactory.getLogger(RegulatoryMappingService.class);

    private final WeightMatrixConfig weightConfig;

    // Micrometer metrics
    private final Timer              mappingTimer;
    private final Counter            violationCounter;
    private final Counter            reviewCounter;
    private final Counter            compliantCounter;
    private final DistributionSummary riskScoreDistribution;

    public RegulatoryMappingService(WeightMatrixConfig weightConfig,
                                     MeterRegistry meterRegistry) {
        this.weightConfig = weightConfig;

        this.mappingTimer = Timer.builder("xaicomply_mapping_duration_ms")
                .description("Regulatory mapping computation time").register(meterRegistry);
        this.violationCounter = Counter.builder("xaicomply_mapping_violations_total")
                .register(meterRegistry);
        this.reviewCounter = Counter.builder("xaicomply_mapping_reviews_total")
                .register(meterRegistry);
        this.compliantCounter = Counter.builder("xaicomply_mapping_compliant_total")
                .register(meterRegistry);
        this.riskScoreDistribution = DistributionSummary
                .builder("xaicomply_mapping_risk_score")
                .description("Distribution of regulatory risk scores R")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meterRegistry);
    }

    /**
     * Apply the regulatory mapping algorithm to produce a compliance decision.
     *
     * @param inferenceResult ONNX prediction + SHAP/LIME explanations
     * @return MappingResultDTO with risk score R and compliance status
     */
    public MappingResultDTO mapToComplianceRisk(InferenceResultDTO inferenceResult) {
        String txId = inferenceResult.getTransactionId();
        MDC.put("transactionId", txId);

        log.info("[RegulatoryMapping] ─── BEGIN txId={}", txId);
        log.debug("[RegulatoryMapping] Model riskScore={} predictedClass={}",
                inferenceResult.getRiskScore(), inferenceResult.getPredictedClass());

        return mappingTimer.record(() -> {
            try {
                return doMapping(inferenceResult, txId);
            } finally {
                MDC.remove("transactionId");
            }
        });
    }

    private MappingResultDTO doMapping(InferenceResultDTO inferenceResult, String txId) {
        long start = System.currentTimeMillis();

        // ── Step 1: Select attribution source (SHAP primary, LIME secondary) ──
        ExplanationDTO primaryExplanation = selectPrimaryExplanation(inferenceResult, txId);
        Map<String, Double> attributions = primaryExplanation.getFeatureAttributions();

        log.debug("[RegulatoryMapping] Using {} attributions ({} features)",
                primaryExplanation.getExplainerType(), attributions.size());

        // ── Step 2: Compute Eq. 3 — R = Σ ωᵢ|φᵢ| ─────────────────────────────
        log.debug("[RegulatoryMapping] Computing Eq.3: R = Σ ωᵢ|φᵢ|");

        Map<String, Double> weightedContributions = new LinkedHashMap<>();
        double rawRiskScore = 0.0;

        for (Map.Entry<String, Double> entry : attributions.entrySet()) {
            String featureName = entry.getKey();
            double phi_i  = entry.getValue();          // φᵢ (SHAP attribution)
            double omega_i = weightConfig.getWeight(featureName); // ωᵢ
            double contribution = omega_i * Math.abs(phi_i);      // ωᵢ|φᵢ|

            weightedContributions.put(featureName, contribution);
            rawRiskScore += contribution;

            log.trace("[RegulatoryMapping] Feature {}: φᵢ={} ωᵢ={} contribution={}",
                    featureName, phi_i, omega_i, contribution);
        }

        // ── Step 3: Normalize R to [0, 1] ─────────────────────────────────────
        // Use sigmoid normalization to map unbounded R into [0,1]
        double regulatoryRiskScore = sigmoid(rawRiskScore);

        log.info("[RegulatoryMapping] Eq.3 result: rawR={} normalizedR={} txId={}",
                rawRiskScore, regulatoryRiskScore, txId);

        riskScoreDistribution.record(regulatoryRiskScore);

        // ── Step 4: Compliance decision ────────────────────────────────────────
        double tau        = weightConfig.getTau();
        double tauReview  = weightConfig.getReviewThreshold();

        ComplianceStatus status  = determineStatus(regulatoryRiskScore, tauReview, tau);
        RiskLevel        level   = RiskLevel.fromScore(regulatoryRiskScore);
        boolean          flagged = regulatoryRiskScore >= tauReview;

        log.info("[RegulatoryMapping] Decision: txId={} R={} τ_review={} τ_violation={} "
                        + "status={} riskLevel={} flagged={}",
                txId, regulatoryRiskScore, tauReview, tau, status, level, flagged);

        // Update counters
        switch (status) {
            case VIOLATION -> { violationCounter.increment();
                log.warn("[RegulatoryMapping] ❌ VIOLATION txId={} R={}", txId, regulatoryRiskScore); }
            case REVIEW    -> { reviewCounter.increment();
                log.warn("[RegulatoryMapping] ⚠️  REVIEW txId={} R={}", txId, regulatoryRiskScore); }
            case COMPLIANT -> { compliantCounter.increment();
                log.info("[RegulatoryMapping] ✅ COMPLIANT txId={} R={}", txId, regulatoryRiskScore); }
        }

        // ── Step 5: Determine top risk features ────────────────────────────────
        List<String> topRiskFeatures = weightedContributions.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        log.info("[RegulatoryMapping] Top risk features: {}", topRiskFeatures);

        // ── Log detailed feature breakdown at DEBUG ────────────────────────────
        if (log.isDebugEnabled()) {
            log.debug("[RegulatoryMapping] Full weighted contributions (top 10):");
            weightedContributions.entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(10)
                    .forEach(e -> log.debug("[RegulatoryMapping]   {}: {}", e.getKey(), e.getValue()));
        }

        long elapsed = System.currentTimeMillis() - start;

        MappingResultDTO result = MappingResultDTO.builder()
                .transactionId(txId)
                .regulatoryRiskScore(regulatoryRiskScore)
                .modelRiskScore(inferenceResult.getRiskScore())
                .threshold(tau)
                .flagged(flagged)
                .complianceStatus(status)
                .riskLevel(level)
                .topRiskFeatures(topRiskFeatures)
                .weightedContributions(weightedContributions)
                .regulatoryCategory(determineRegulatoryCategory(topRiskFeatures))
                .mappingTimestamp(Instant.now())
                .mappingTimeMs(elapsed)
                .weightMatrixVersion(weightConfig.getVersion())
                .trueLabel(inferenceResult.getTrueLabel())
                .build();

        log.info("[RegulatoryMapping] ─── DONE txId={} status={} R={} timeMs={}",
                txId, status, regulatoryRiskScore, elapsed);

        return result;
    }

    // ─── Algorithm helpers ────────────────────────────────────────────────────

    /**
     * Select primary explanation source.
     * SHAP is preferred (exact attributions); LIME used as fallback.
     */
    private ExplanationDTO selectPrimaryExplanation(InferenceResultDTO result, String txId) {
        ExplanationDTO shap = result.getShapExplanation();
        ExplanationDTO lime = result.getLimeExplanation();

        if (shap != null && shap.getFeatureAttributions() != null
                && !shap.getFeatureAttributions().isEmpty()
                && !isFallback(shap)) {
            log.debug("[RegulatoryMapping] Using SHAP as primary explanation txId={}", txId);
            return shap;
        }

        if (lime != null && lime.getFeatureAttributions() != null
                && !lime.getFeatureAttributions().isEmpty()
                && !isFallback(lime)) {
            log.warn("[RegulatoryMapping] SHAP unavailable — falling back to LIME txId={}", txId);
            return lime;
        }

        // Both unavailable — create minimal attribution from model risk score
        log.warn("[RegulatoryMapping] Both SHAP and LIME unavailable for txId={} "
                + "— using model score as proxy", txId);
        return buildProxyAttribution(result.getRiskScore());
    }

    private boolean isFallback(ExplanationDTO exp) {
        return exp.getTopFeatures() != null
                && exp.getTopFeatures().size() == 1
                && "FALLBACK".equals(exp.getTopFeatures().get(0));
    }

    /**
     * When explainers are unavailable, create a proxy attribution
     * using just the Amount feature and model risk score.
     */
    private ExplanationDTO buildProxyAttribution(double modelRiskScore) {
        Map<String, Double> proxyAttribs = new LinkedHashMap<>();
        proxyAttribs.put("Amount", modelRiskScore);
        // All other features get 0
        String[] allFeatures = {"Time","V1","V2","V3","V4","V5","V6","V7","V8","V9","V10",
                "V11","V12","V13","V14","V15","V16","V17","V18","V19","V20",
                "V21","V22","V23","V24","V25","V26","V27","V28"};
        for (String f : allFeatures) proxyAttribs.put(f, 0.0);

        return ExplanationDTO.builder()
                .featureAttributions(proxyAttribs)
                .topFeatures(List.of("Amount"))
                .build();
    }

    /**
     * Determine compliance status using two thresholds.
     */
    private ComplianceStatus determineStatus(double r, double tauReview, double tauViolation) {
        if (r >= tauViolation) return ComplianceStatus.VIOLATION;
        if (r >= tauReview)    return ComplianceStatus.REVIEW;
        return ComplianceStatus.COMPLIANT;
    }

    /**
     * Sigmoid normalization: maps R ∈ [0,∞) to [0,1].
     * σ(x) = 1 / (1 + e^(-x))
     * Centers at 0.5 when R = 0.
     */
    private double sigmoid(double r) {
        return 1.0 / (1.0 + Math.exp(-r));
    }

    /**
     * Map top risk features to a regulatory taxonomy category.
     * Amount → AML, V1/V2 → behavioral anomaly, etc.
     */
    private String determineRegulatoryCategory(List<String> topFeatures) {
        if (topFeatures.isEmpty()) return "UNKNOWN";
        String top = topFeatures.get(0);
        if ("Amount".equals(top)) return "AML_STRUCTURING";
        if (top.startsWith("V1") || top.startsWith("V2")) return "BEHAVIORAL_ANOMALY";
        if ("Time".equals(top))   return "TEMPORAL_VELOCITY";
        return "PCA_ANOMALY";
    }
}
