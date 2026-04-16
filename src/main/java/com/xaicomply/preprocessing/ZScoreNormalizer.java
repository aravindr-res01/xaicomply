package com.xaicomply.preprocessing;

import com.xaicomply.domain.NormalizerStats;
import com.xaicomply.repository.NormalizerStatsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Z-Score normalizer using Welford's online algorithm.
 * Persists running mean/std to DB via NormalizerStats JPA entity.
 * On first run (count=0), uses defaults: amount(mean=500,std=300), velocity(mean=3,std=2), hour(mean=12,std=6)
 */
@Component
public class ZScoreNormalizer {

    private static final Logger log = LoggerFactory.getLogger(ZScoreNormalizer.class);

    // Default values for cold start
    private static final double DEFAULT_AMOUNT_MEAN = 500.0;
    private static final double DEFAULT_AMOUNT_STD = 300.0;
    private static final double DEFAULT_VELOCITY_MEAN = 3.0;
    private static final double DEFAULT_VELOCITY_STD = 2.0;
    private static final double DEFAULT_HOUR_MEAN = 12.0;
    private static final double DEFAULT_HOUR_STD = 6.0;

    private final NormalizerStatsRepository statsRepository;

    public ZScoreNormalizer(NormalizerStatsRepository statsRepository) {
        this.statsRepository = statsRepository;
    }

    /**
     * Normalizes a value using Welford's online algorithm.
     * Updates the running statistics in the DB after each call.
     */
    @Transactional
    public float normalize(String featureName, double value) {
        NormalizerStats stats = statsRepository.findById(featureName).orElse(null);

        if (stats == null || stats.getCount() == 0) {
            // First run: use defaults and initialize
            stats = initializeWithDefaults(featureName);
        }

        // Welford update
        long newCount = stats.getCount() + 1;
        double delta = value - stats.getMean();
        double newMean = stats.getMean() + delta / newCount;
        double delta2 = value - newMean;
        double newM2 = stats.getM2() + delta * delta2;

        stats.setCount(newCount);
        stats.setMean(newMean);
        stats.setM2(newM2);
        statsRepository.save(stats);

        // z-score using the stats BEFORE this update (for consistency)
        double mean = (newCount == 1) ? getDefaultMean(featureName) : (newMean - delta / newCount);
        double std = getStdDev(featureName, stats, newCount);

        if (std <= 0) std = 1.0;
        return (float) ((value - mean) / std);
    }

    /**
     * Normalizes without updating stats (for re-explain operations).
     */
    public float normalizeReadOnly(String featureName, double value) {
        NormalizerStats stats = statsRepository.findById(featureName).orElse(null);
        double mean;
        double std;
        if (stats == null || stats.getCount() == 0) {
            mean = getDefaultMean(featureName);
            std = getDefaultStd(featureName);
        } else {
            mean = stats.getMean();
            std = stats.getStdDev();
        }
        if (std <= 0) std = 1.0;
        return (float) ((value - mean) / std);
    }

    private NormalizerStats initializeWithDefaults(String featureName) {
        double mean = getDefaultMean(featureName);
        double std = getDefaultStd(featureName);
        // M2 = variance * (count - 1) = std^2 * (n-1), init with synthetic n=30
        double m2 = std * std * 29;
        NormalizerStats stats = new NormalizerStats(featureName, 30, mean, m2);
        return statsRepository.save(stats);
    }

    private double getDefaultMean(String featureName) {
        return switch (featureName) {
            case "amount" -> DEFAULT_AMOUNT_MEAN;
            case "transactionVelocity" -> DEFAULT_VELOCITY_MEAN;
            case "hourOfDay" -> DEFAULT_HOUR_MEAN;
            default -> 0.0;
        };
    }

    private double getDefaultStd(String featureName) {
        return switch (featureName) {
            case "amount" -> DEFAULT_AMOUNT_STD;
            case "transactionVelocity" -> DEFAULT_VELOCITY_STD;
            case "hourOfDay" -> DEFAULT_HOUR_STD;
            default -> 1.0;
        };
    }

    private double getStdDev(String featureName, NormalizerStats stats, long count) {
        if (count < 2) {
            return getDefaultStd(featureName);
        }
        double variance = stats.getM2() / (count - 1);
        return variance <= 0 ? getDefaultStd(featureName) : Math.sqrt(variance);
    }
}
