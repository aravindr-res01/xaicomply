package com.xaicomply.inference.service;

import com.xaicomply.common.dto.ExplanationDTO;
import com.xaicomply.common.dto.InferenceResultDTO;
import com.xaicomply.common.dto.ProcessedTransactionDTO;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * Orchestrates the inference pipeline within this service:
 *   1. ONNX prediction (risk score 0–1)
 *   2. SHAP explanation (calls Python sidecar)
 *   3. LIME explanation (calls Python sidecar)
 *
 * Paper Section 2.3 (target latencies):
 *   - ONNX inference: sub-50ms
 *   - SHAP:           ~15ms at 100 txn/s
 *   - LIME:           ~30ms at 100 txn/s
 *   - Combined p95:   <200ms at 500 txn/s
 */
@Service
public class InferenceOrchestrationService {

    private static final Logger log = LoggerFactory.getLogger(InferenceOrchestrationService.class);

    private final OnnxInferenceService   onnxService;
    private final ExplainerClientService explainerClient;

    private final Timer   orchestrationTimer;
    private final Counter highRiskCounter;
    private final Counter inferenceCounter;

    public InferenceOrchestrationService(OnnxInferenceService onnxService,
                                          ExplainerClientService explainerClient,
                                          MeterRegistry meterRegistry) {
        this.onnxService = onnxService;
        this.explainerClient = explainerClient;

        this.orchestrationTimer = Timer.builder("xaicomply_inference_xai_total_duration_ms")
                .description("Total inference + explanation duration").register(meterRegistry);
        this.highRiskCounter = Counter.builder("xaicomply_inference_high_risk_total")
                .description("Transactions with risk score > 0.5").register(meterRegistry);
        this.inferenceCounter = Counter.builder("xaicomply_inference_total")
                .register(meterRegistry);
    }

    /**
     * Run full inference + explanation for a preprocessed transaction.
     *
     * @param processed normalized transaction from Preprocessing Service
     * @return InferenceResultDTO with risk score, SHAP and LIME explanations
     */
    public InferenceResultDTO runInferenceAndExplain(ProcessedTransactionDTO processed) {
        String txId = processed.getTransactionId();
        MDC.put("transactionId", txId);

        log.info("[InferenceOrchestration] ─── BEGIN txId={}", txId);
        log.debug("[InferenceOrchestration] Feature vector length={} imputedFields={}",
                processed.getFeatureVector() != null ? processed.getFeatureVector().length : 0,
                processed.getImputedFields());

        long totalStart = System.currentTimeMillis();

        return orchestrationTimer.record(() -> {
            try {
                return doInferenceAndExplain(processed, txId, totalStart);
            } finally {
                MDC.remove("transactionId");
            }
        });
    }

    private InferenceResultDTO doInferenceAndExplain(ProcessedTransactionDTO processed,
                                                      String txId, long totalStart) {
        float[] features = processed.getFeatureVector();

        // ── Step 1: ONNX Inference ────────────────────────────────────────────
        log.debug("[InferenceOrchestration] Step 1/3 — ONNX inference txId={}", txId);
        long step1Start = System.currentTimeMillis();

        double riskScore = onnxService.predict(features);
        int predictedClass = riskScore >= 0.5 ? 1 : 0;
        double confidence = Math.max(riskScore, 1.0 - riskScore);

        long inferenceMs = System.currentTimeMillis() - step1Start;
        log.info("[InferenceOrchestration] ONNX result: txId={} riskScore={} "
                        + "class={} confidence={} timeMs={}",
                txId, riskScore, predictedClass, confidence, inferenceMs);

        if (riskScore > 0.5) {
            highRiskCounter.increment();
            log.warn("[InferenceOrchestration] HIGH RISK transaction txId={} riskScore={}",
                    txId, riskScore);
        }
        if (inferenceMs > 50) {
            log.warn("[InferenceOrchestration] ONNX inference exceeded 50ms target: {}ms", inferenceMs);
        }

        // ── Step 2: SHAP Explanation ──────────────────────────────────────────
        log.debug("[InferenceOrchestration] Step 2/3 — SHAP explanation txId={}", txId);
        long step2Start = System.currentTimeMillis();

        ExplanationDTO shapExplanation = explainerClient.getShapExplanation(txId, features);

        long shapMs = System.currentTimeMillis() - step2Start;
        log.info("[InferenceOrchestration] SHAP complete: txId={} topFeature={} ({}) "
                        + "baseValue={} fromCache={} timeMs={}",
                txId,
                !shapExplanation.getTopFeatures().isEmpty()
                        ? shapExplanation.getTopFeatures().get(0) : "N/A",
                !shapExplanation.getTopFeatures().isEmpty()
                        ? shapExplanation.getFeatureAttributions()
                           .getOrDefault(shapExplanation.getTopFeatures().get(0), 0.0) : 0.0,
                shapExplanation.getBaseValue(),
                shapExplanation.getFromCache(),
                shapMs);

        // ── Step 3: LIME Explanation ──────────────────────────────────────────
        log.debug("[InferenceOrchestration] Step 3/3 — LIME explanation txId={}", txId);
        long step3Start = System.currentTimeMillis();

        ExplanationDTO limeExplanation = explainerClient.getLimeExplanation(txId, features);

        long limeMs = System.currentTimeMillis() - step3Start;
        log.info("[InferenceOrchestration] LIME complete: txId={} topFeature={} timeMs={}",
                txId,
                !limeExplanation.getTopFeatures().isEmpty()
                        ? limeExplanation.getTopFeatures().get(0) : "N/A",
                limeMs);

        // ── Compare SHAP vs LIME top features ─────────────────────────────────
        if (!shapExplanation.getTopFeatures().isEmpty()
                && !limeExplanation.getTopFeatures().isEmpty()) {
            String shapTop = shapExplanation.getTopFeatures().get(0);
            String limeTop = limeExplanation.getTopFeatures().get(0);
            if (shapTop.equals(limeTop)) {
                log.info("[InferenceOrchestration] SHAP & LIME AGREE on top feature: {} txId={}",
                        shapTop, txId);
            } else {
                log.warn("[InferenceOrchestration] SHAP/LIME disagreement: SHAP top={} LIME top={} txId={}",
                        shapTop, limeTop, txId);
            }
        }

        // ── Assemble result ────────────────────────────────────────────────────
        long totalMs = System.currentTimeMillis() - totalStart;
        inferenceCounter.increment();

        InferenceResultDTO result = InferenceResultDTO.builder()
                .transactionId(txId)
                .riskScore(riskScore)
                .predictedClass(predictedClass)
                .confidence(confidence)
                .shapExplanation(shapExplanation)
                .limeExplanation(limeExplanation)
                .inferenceTimeMs(inferenceMs)
                .totalExplanationTimeMs(totalMs)
                .inferenceTimestamp(Instant.now())
                .modelVersion("1.0.0")
                .trueLabel(processed.getTrueLabel())
                .build();

        log.info("[InferenceOrchestration] ─── DONE txId={} | riskScore={} | "
                        + "shapMs={} | limeMs={} | totalMs={}",
                txId, riskScore, shapMs, limeMs, totalMs);

        if (totalMs > 200) {
            log.warn("[InferenceOrchestration] Total inference+explanation {}ms exceeds "
                    + "200ms p95 SLA (paper target)", totalMs);
        }

        return result;
    }
}
