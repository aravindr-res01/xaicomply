package com.xaicomply.preprocessing.service;

import com.xaicomply.common.dto.ProcessedTransactionDTO;
import com.xaicomply.common.dto.TransactionDTO;
import com.xaicomply.common.exception.XaiComplyException.PreprocessingException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Core preprocessing logic for the XAI-Comply pipeline.
 *
 * Paper Section 2.3:
 *   "This service validates JSON schemas, imputes missing values via mean
 *    substitution or k-nearest neighbours imputation, and scales numerical
 *    features via z-score scaling. Categorical features are converted via
 *    one-hot encoding."
 *
 * Steps:
 *   1. Validate input (null checks, range checks)
 *   2. Impute any missing feature values
 *   3. Z-score normalize Amount and Time
 *   4. Build feature vector [Time, V1-V28, Amount] for ONNX model
 *   5. Wrap into ProcessedTransactionDTO with audit metadata
 */
@Service
public class PreprocessingService {

    private static final Logger log = LoggerFactory.getLogger(PreprocessingService.class);

    private final FeatureScalerService scalerService;

    // Micrometer metrics (scraped by Prometheus)
    private final Counter processedCounter;
    private final Counter imputedCounter;
    private final Counter errorCounter;
    private final Timer   processingTimer;

    public PreprocessingService(FeatureScalerService scalerService, MeterRegistry meterRegistry) {
        this.scalerService = scalerService;

        this.processedCounter = Counter.builder("xaicomply_preprocessing_transactions_total")
                .description("Total transactions successfully preprocessed")
                .register(meterRegistry);

        this.imputedCounter = Counter.builder("xaicomply_preprocessing_imputed_total")
                .description("Total fields imputed due to missing values")
                .register(meterRegistry);

        this.errorCounter = Counter.builder("xaicomply_preprocessing_errors_total")
                .description("Total preprocessing errors")
                .register(meterRegistry);

        this.processingTimer = Timer.builder("xaicomply_preprocessing_duration_ms")
                .description("Preprocessing duration in milliseconds")
                .register(meterRegistry);
    }

    /**
     * Main preprocessing entry point.
     *
     * @param transaction raw transaction from client
     * @return ProcessedTransactionDTO ready for ONNX inference
     */
    public ProcessedTransactionDTO preprocess(TransactionDTO transaction) {
        String txId = transaction.getTransactionId() != null
                ? transaction.getTransactionId()
                : UUID.randomUUID().toString();
        transaction.setTransactionId(txId);

        // MDC for request-scoped logging (all downstream log lines include transactionId)
        MDC.put("transactionId", txId);

        log.info("[Preprocessing] ─── BEGIN transaction={}", txId);
        log.debug("[Preprocessing] Raw input: amount={}, time={}, v1={}, v14={}",
                transaction.getAmount(), transaction.getTime(),
                transaction.getV1(), transaction.getV14());

        return processingTimer.record(() -> {
            try {
                return doPreprocess(transaction, txId);
            } finally {
                MDC.remove("transactionId");
            }
        });
    }

    private ProcessedTransactionDTO doPreprocess(TransactionDTO tx, String txId) {
        long start = System.currentTimeMillis();

        // ── Step 1: Validate ────────────────────────────────────────────────
        log.debug("[Preprocessing] Step 1/4 — Validating input for txId={}", txId);
        validateInput(tx);

        // ── Step 2: Impute missing values ────────────────────────────────────
        log.debug("[Preprocessing] Step 2/4 — Checking for missing/null features");
        List<String> imputedFields = imputeMissingValues(tx);

        if (!imputedFields.isEmpty()) {
            log.warn("[Preprocessing] txId={} — Imputed {} field(s): {}",
                    txId, imputedFields.size(), imputedFields);
            imputedCounter.increment(imputedFields.size());
        } else {
            log.debug("[Preprocessing] txId={} — No imputation needed", txId);
        }

        // ── Step 3: Z-score normalize Amount and Time ─────────────────────────
        log.debug("[Preprocessing] Step 3/4 — Z-score normalization");
        double normalizedAmount = scalerService.normalizeAmount(tx.getAmount());
        double normalizedTime   = scalerService.normalizeTime(tx.getTime());

        log.debug("[Preprocessing] txId={} normalizations: amount={}→{}, time={}→{}",
                txId, tx.getAmount(), normalizedAmount, tx.getTime(), normalizedTime);

        // ── Step 4: Build feature vector ─────────────────────────────────────
        // Feature order MUST match Python training: [Time, V1..V28, Amount]
        log.debug("[Preprocessing] Step 4/4 — Building feature vector (30 features)");
        float[] featureVector = buildFeatureVector(tx, normalizedAmount, normalizedTime);

        log.debug("[Preprocessing] txId={} feature vector built: length={}",
                txId, featureVector.length);
        logFeatureVectorSummary(txId, featureVector);

        // ── Assemble result ────────────────────────────────────────────────────
        long elapsed = System.currentTimeMillis() - start;

        ProcessedTransactionDTO result = ProcessedTransactionDTO.builder()
                .transactionId(txId)
                .originalTransaction(tx)
                .normalizedAmount(normalizedAmount)
                .normalizedTime(normalizedTime)
                .featureVector(featureVector)
                .featureNames(TransactionDTO.featureNames())
                .imputedFields(imputedFields)
                .preprocessedAt(Instant.now())
                .preprocessingTimeMs(elapsed)
                .trueLabel(tx.getTrueLabel())
                .schemaVersion("1.0")
                .build();

        processedCounter.increment();

        log.info("[Preprocessing] ─── DONE txId={} | imputedFields={} | "
                        + "normalizedAmount={} | normalizedTime={} | timeMs={}",
                txId, imputedFields.size(), normalizedAmount, normalizedTime, elapsed);

        return result;
    }

    // ─── Validation ───────────────────────────────────────────────────────────

    private void validateInput(TransactionDTO tx) {
        log.debug("[Preprocessing] Validating: amount={}, time={}", tx.getAmount(), tx.getTime());

        if (tx.getAmount() == null) {
            log.error("[Preprocessing] Validation failed: Amount is null");
            errorCounter.increment();
            throw new PreprocessingException("Amount field is required and cannot be null");
        }
        if (tx.getAmount() < 0) {
            log.error("[Preprocessing] Validation failed: Amount is negative: {}", tx.getAmount());
            errorCounter.increment();
            throw new PreprocessingException("Amount cannot be negative: " + tx.getAmount());
        }
        if (tx.getTime() == null) {
            log.error("[Preprocessing] Validation failed: Time is null");
            errorCounter.increment();
            throw new PreprocessingException("Time field is required and cannot be null");
        }
        if (tx.getTime() < 0) {
            log.error("[Preprocessing] Validation failed: Time is negative: {}", tx.getTime());
            errorCounter.increment();
            throw new PreprocessingException("Time cannot be negative: " + tx.getTime());
        }

        log.debug("[Preprocessing] Validation passed");
    }

    // ─── Imputation ───────────────────────────────────────────────────────────

    /**
     * Impute null V-features with 0.0 (mean of PCA-normalized data).
     * Paper: "imputes missing values via mean substitution".
     */
    private List<String> imputeMissingValues(TransactionDTO tx) {
        List<String> imputed = new ArrayList<>();

        // Check each PCA feature — impute with 0.0 (mean of standardized PCA features)
        if (tx.getV1()  == null) { tx.setV1(0.0);  imputed.add("V1");  }
        if (tx.getV2()  == null) { tx.setV2(0.0);  imputed.add("V2");  }
        if (tx.getV3()  == null) { tx.setV3(0.0);  imputed.add("V3");  }
        if (tx.getV4()  == null) { tx.setV4(0.0);  imputed.add("V4");  }
        if (tx.getV5()  == null) { tx.setV5(0.0);  imputed.add("V5");  }
        if (tx.getV6()  == null) { tx.setV6(0.0);  imputed.add("V6");  }
        if (tx.getV7()  == null) { tx.setV7(0.0);  imputed.add("V7");  }
        if (tx.getV8()  == null) { tx.setV8(0.0);  imputed.add("V8");  }
        if (tx.getV9()  == null) { tx.setV9(0.0);  imputed.add("V9");  }
        if (tx.getV10() == null) { tx.setV10(0.0); imputed.add("V10"); }
        if (tx.getV11() == null) { tx.setV11(0.0); imputed.add("V11"); }
        if (tx.getV12() == null) { tx.setV12(0.0); imputed.add("V12"); }
        if (tx.getV13() == null) { tx.setV13(0.0); imputed.add("V13"); }
        if (tx.getV14() == null) { tx.setV14(0.0); imputed.add("V14"); }
        if (tx.getV15() == null) { tx.setV15(0.0); imputed.add("V15"); }
        if (tx.getV16() == null) { tx.setV16(0.0); imputed.add("V16"); }
        if (tx.getV17() == null) { tx.setV17(0.0); imputed.add("V17"); }
        if (tx.getV18() == null) { tx.setV18(0.0); imputed.add("V18"); }
        if (tx.getV19() == null) { tx.setV19(0.0); imputed.add("V19"); }
        if (tx.getV20() == null) { tx.setV20(0.0); imputed.add("V20"); }
        if (tx.getV21() == null) { tx.setV21(0.0); imputed.add("V21"); }
        if (tx.getV22() == null) { tx.setV22(0.0); imputed.add("V22"); }
        if (tx.getV23() == null) { tx.setV23(0.0); imputed.add("V23"); }
        if (tx.getV24() == null) { tx.setV24(0.0); imputed.add("V24"); }
        if (tx.getV25() == null) { tx.setV25(0.0); imputed.add("V25"); }
        if (tx.getV26() == null) { tx.setV26(0.0); imputed.add("V26"); }
        if (tx.getV27() == null) { tx.setV27(0.0); imputed.add("V27"); }
        if (tx.getV28() == null) { tx.setV28(0.0); imputed.add("V28"); }

        return imputed;
    }

    // ─── Feature vector assembly ──────────────────────────────────────────────

    /**
     * Build the 30-element float array for ONNX model input.
     * Order MUST match Python training: [normalizedTime, V1..V28, normalizedAmount]
     */
    private float[] buildFeatureVector(TransactionDTO tx,
                                        double normalizedAmount,
                                        double normalizedTime) {
        return new float[]{
                (float) normalizedTime,
                tx.getV1().floatValue(),  tx.getV2().floatValue(),
                tx.getV3().floatValue(),  tx.getV4().floatValue(),
                tx.getV5().floatValue(),  tx.getV6().floatValue(),
                tx.getV7().floatValue(),  tx.getV8().floatValue(),
                tx.getV9().floatValue(),  tx.getV10().floatValue(),
                tx.getV11().floatValue(), tx.getV12().floatValue(),
                tx.getV13().floatValue(), tx.getV14().floatValue(),
                tx.getV15().floatValue(), tx.getV16().floatValue(),
                tx.getV17().floatValue(), tx.getV18().floatValue(),
                tx.getV19().floatValue(), tx.getV20().floatValue(),
                tx.getV21().floatValue(), tx.getV22().floatValue(),
                tx.getV23().floatValue(), tx.getV24().floatValue(),
                tx.getV25().floatValue(), tx.getV26().floatValue(),
                tx.getV27().floatValue(), tx.getV28().floatValue(),
                (float) normalizedAmount
        };
    }

    private void logFeatureVectorSummary(String txId, float[] fv) {
        if (log.isDebugEnabled()) {
            float min = fv[0], max = fv[0], sum = 0;
            for (float v : fv) { min = Math.min(min, v); max = Math.max(max, v); sum += v; }
            log.debug("[Preprocessing] txId={} feature stats: length={}, min={}, max={}, mean={}",
                    txId, fv.length, min, max, sum / fv.length);
        }
    }
}
