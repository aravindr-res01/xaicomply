package com.xaicomply.preprocessing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xaicomply.common.exception.XaiComplyException;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Map;

/**
 * Z-score normalization for Amount and Time features.
 *
 * Statistics (mean, std) are loaded from scaler_stats.json generated
 * by the Python training script (train_model.py).
 *
 * Paper Section 2.3:
 *   "scales numerical features via z-score scaling"
 *
 * Formula: x_scaled = (x - μ) / σ
 *
 * V1–V28 are already PCA-scaled and passed through unchanged.
 */
@Service
@Getter
public class FeatureScalerService {

    private static final Logger log = LoggerFactory.getLogger(FeatureScalerService.class);

    @Value("${scaler.stats.path:classpath:model/scaler_stats.json}")
    private Resource scalerStatsResource;

    // Scaler statistics loaded from training data
    private double amountMean;
    private double amountStd;
    private double timeMean;
    private double timeStd;

    // Fallback values from Kaggle credit card fraud dataset statistics
    // (used when scaler_stats.json is not available)
    private static final double DEFAULT_AMOUNT_MEAN = 88.35;
    private static final double DEFAULT_AMOUNT_STD  = 250.12;
    private static final double DEFAULT_TIME_MEAN   = 94813.86;
    private static final double DEFAULT_TIME_STD    = 47488.15;

    private boolean statsLoaded = false;

    @PostConstruct
    public void loadScalerStats() {
        log.info("[FeatureScalerService] Loading scaler statistics...");
        log.info("[FeatureScalerService] Looking for: {}", scalerStatsResource);

        try {
            if (scalerStatsResource.exists()) {
                ObjectMapper mapper = new ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> stats = mapper.readValue(
                        scalerStatsResource.getInputStream(), Map.class);

                amountMean = toDouble(stats.get("amount_mean"));
                amountStd  = toDouble(stats.get("amount_std"));
                timeMean   = toDouble(stats.get("time_mean"));
                timeStd    = toDouble(stats.get("time_std"));
                statsLoaded = true;

                log.info("[FeatureScalerService] ✅ Scaler stats loaded from file:");
                log.info("[FeatureScalerService]   Amount: mean={}, std={}",
                        amountMean, amountStd);
                log.info("[FeatureScalerService]   Time:   mean={}, std={}",
                        timeMean, timeStd);
            } else {
                log.warn("[FeatureScalerService] scaler_stats.json not found at: {}",
                        scalerStatsResource);
                log.warn("[FeatureScalerService] Using default Kaggle dataset statistics.");
                log.warn("[FeatureScalerService] Run train_model.py to generate proper stats.");
                useDefaultStats();
            }
        } catch (IOException e) {
            log.error("[FeatureScalerService] Failed to load scaler_stats.json: {}", e.getMessage());
            log.warn("[FeatureScalerService] Falling back to default statistics.");
            useDefaultStats();
        }
    }

    private void useDefaultStats() {
        amountMean = DEFAULT_AMOUNT_MEAN;
        amountStd  = DEFAULT_AMOUNT_STD;
        timeMean   = DEFAULT_TIME_MEAN;
        timeStd    = DEFAULT_TIME_STD;

        log.info("[FeatureScalerService] Default Amount: mean={}, std={}", amountMean, amountStd);
        log.info("[FeatureScalerService] Default Time:   mean={}, std={}", timeMean, timeStd);
    }

    /**
     * Z-score normalize transaction Amount.
     * Formula: (amount - μ_amount) / σ_amount
     *
     * @param rawAmount raw transaction amount in EUR
     * @return normalized amount
     */
    public double normalizeAmount(double rawAmount) {
        if (amountStd == 0) {
            log.warn("[FeatureScalerService] Amount std is 0 — returning raw value");
            return rawAmount;
        }
        double normalized = (rawAmount - amountMean) / amountStd;
        log.debug("[FeatureScalerService] Amount normalization: raw={} → normalized={}",
                rawAmount, normalized);
        return normalized;
    }

    /**
     * Z-score normalize transaction Time.
     * Formula: (time - μ_time) / σ_time
     *
     * @param rawTime raw seconds since dataset reference
     * @return normalized time
     */
    public double normalizeTime(double rawTime) {
        if (timeStd == 0) {
            log.warn("[FeatureScalerService] Time std is 0 — returning raw value");
            return rawTime;
        }
        double normalized = (rawTime - timeMean) / timeStd;
        log.debug("[FeatureScalerService] Time normalization: raw={} → normalized={}",
                rawTime, normalized);
        return normalized;
    }

    private double toDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return Double.parseDouble(value.toString());
    }
}
