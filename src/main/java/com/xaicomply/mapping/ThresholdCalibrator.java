package com.xaicomply.mapping;

import com.xaicomply.repository.RiskScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Calibrates the risk threshold by grid search to maximize F1 score.
 */
@Component
public class ThresholdCalibrator {

    private static final Logger log = LoggerFactory.getLogger(ThresholdCalibrator.class);

    private final RiskScoreRepository riskScoreRepository;

    public ThresholdCalibrator(RiskScoreRepository riskScoreRepository) {
        this.riskScoreRepository = riskScoreRepository;
    }

    /**
     * Sample with a transaction ID and known ground-truth label.
     */
    public record LabeledSample(UUID transactionId, boolean trueLabel) {}

    /**
     * Calibration result from grid search.
     */
    public record CalibrationResult(float optimalThreshold, float precision, float recall, float f1Score, int samplesUsed) {}

    /**
     * Performs grid search on threshold to maximize F1 score.
     *
     * @param samples list of labeled samples with known fraud labels
     * @return CalibrationResult with optimal threshold and metrics
     */
    public CalibrationResult calibrate(List<LabeledSample> samples) {
        if (samples == null || samples.isEmpty()) {
            log.warn("No samples provided for calibration");
            return new CalibrationResult(0.65f, 0.0f, 0.0f, 0.0f, 0);
        }

        // Load risk scores from DB for each transactionId
        Map<UUID, Float> scoreMap = samples.stream()
                .collect(Collectors.toMap(
                        LabeledSample::transactionId,
                        s -> riskScoreRepository
                                .findTopByTransactionIdOrderByCreatedAtDesc(s.transactionId())
                                .map(rs -> rs.getRiskScore())
                                .orElse(-1.0f)
                ));

        // Filter out samples with no score in DB
        List<LabeledSample> validSamples = samples.stream()
                .filter(s -> scoreMap.getOrDefault(s.transactionId(), -1.0f) >= 0)
                .collect(Collectors.toList());

        log.info("Calibrating with {}/{} valid samples", validSamples.size(), samples.size());

        if (validSamples.isEmpty()) {
            return new CalibrationResult(0.65f, 0.0f, 0.0f, 0.0f, 0);
        }

        // Grid search: tau in [0.0, 1.0] step 0.01
        float bestThreshold = 0.5f;
        float bestF1 = -1.0f;
        float bestPrecision = 0.0f;
        float bestRecall = 0.0f;

        for (int step = 0; step <= 100; step++) {
            float tau = step / 100.0f;
            float[] metrics = computeMetrics(validSamples, scoreMap, tau);
            float precision = metrics[0];
            float recall = metrics[1];
            float f1 = metrics[2];

            if (f1 > bestF1) {
                bestF1 = f1;
                bestThreshold = tau;
                bestPrecision = precision;
                bestRecall = recall;
            }
        }

        log.info("Calibration complete: threshold={} precision={} recall={} f1={}",
                bestThreshold, bestPrecision, bestRecall, bestF1);

        return new CalibrationResult(bestThreshold, bestPrecision, bestRecall,
                Math.max(0.0f, bestF1), validSamples.size());
    }

    private float[] computeMetrics(List<LabeledSample> samples, Map<UUID, Float> scoreMap, float threshold) {
        int tp = 0, fp = 0, fn = 0;

        for (LabeledSample s : samples) {
            float score = scoreMap.get(s.transactionId());
            boolean predicted = score >= threshold;
            boolean actual = s.trueLabel();

            if (predicted && actual) tp++;
            else if (predicted && !actual) fp++;
            else if (!predicted && actual) fn++;
        }

        float precision = (tp + fp > 0) ? (float) tp / (tp + fp) : 1.0f;
        float recall = (tp + fn > 0) ? (float) tp / (tp + fn) : 0.0f;
        float f1 = (precision + recall > 0) ? 2 * precision * recall / (precision + recall) : 0.0f;

        return new float[]{precision, recall, f1};
    }
}
