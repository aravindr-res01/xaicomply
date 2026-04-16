package com.xaicomply.mapping;

import com.xaicomply.config.ModelConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Registry for regulatory mapping weights and threshold.
 * Loads from config, persists to DB for restart survival.
 * Allows runtime updates via AdminController.
 */
@Component
public class WeightRegistry {

    private static final Logger log = LoggerFactory.getLogger(WeightRegistry.class);
    private static final String TABLE_NAME = "weight_config";

    private final ModelConfig modelConfig;
    private final JdbcTemplate jdbcTemplate;

    private final AtomicReference<float[]> currentWeights = new AtomicReference<>();
    private final AtomicReference<Float> currentThreshold = new AtomicReference<>();
    private volatile String weightsVersion;

    public WeightRegistry(ModelConfig modelConfig, JdbcTemplate jdbcTemplate) {
        this.modelConfig = modelConfig;
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void initialize() {
        ensureTableExists();
        loadFromDbOrConfig();
    }

    private void ensureTableExists() {
        jdbcTemplate.execute("""
            CREATE TABLE IF NOT EXISTS weight_config (
                id INTEGER PRIMARY KEY DEFAULT 1,
                weights_csv VARCHAR(1000),
                threshold DOUBLE,
                version VARCHAR(50),
                updated_at TIMESTAMP
            )
            """);
    }

    private void loadFromDbOrConfig() {
        try {
            var rows = jdbcTemplate.queryForList("SELECT * FROM weight_config WHERE id = 1");
            if (!rows.isEmpty()) {
                var row = rows.get(0);
                String weightsCsv = (String) row.get("WEIGHTS_CSV");
                if (weightsCsv == null) weightsCsv = (String) row.get("weights_csv");
                Double threshold = null;
                Object tObj = row.get("THRESHOLD");
                if (tObj == null) tObj = row.get("threshold");
                if (tObj instanceof Number n) threshold = n.doubleValue();
                String version = (String) row.get("VERSION");
                if (version == null) version = (String) row.get("version");

                if (weightsCsv != null && threshold != null) {
                    String[] parts = weightsCsv.split(",");
                    float[] weights = new float[parts.length];
                    for (int i = 0; i < parts.length; i++) {
                        weights[i] = Float.parseFloat(parts[i].trim());
                    }
                    currentWeights.set(weights);
                    currentThreshold.set(threshold.floatValue());
                    weightsVersion = version;
                    log.info("Loaded weights from DB, version={}", version);
                    return;
                }
            }
        } catch (Exception e) {
            log.warn("Could not load weights from DB, using config defaults: {}", e.getMessage());
        }
        // Fallback to config
        loadFromConfig();
    }

    private void loadFromConfig() {
        float[] weights = modelConfig.getRiskWeights().getWeightsArray();
        float threshold = (float) modelConfig.getRiskWeights().getThreshold();

        validateWeights(weights);
        currentWeights.set(weights);
        currentThreshold.set(threshold);
        weightsVersion = "config-" + Instant.now().toEpochMilli();

        persistToDb(weights, threshold, weightsVersion);
        log.info("Loaded weights from config, version={}", weightsVersion);
    }

    @Transactional
    public void updateWeights(float[] newWeights, float newThreshold) {
        validateWeights(newWeights);
        String newVersion = "manual-" + Instant.now().toEpochMilli();

        currentWeights.set(newWeights);
        currentThreshold.set(newThreshold);
        weightsVersion = newVersion;

        persistToDb(newWeights, newThreshold, newVersion);
        log.info("Updated weights, version={}, threshold={}", newVersion, newThreshold);
    }

    private void persistToDb(float[] weights, float threshold, String version) {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < weights.length; i++) {
            if (i > 0) csv.append(",");
            csv.append(weights[i]);
        }

        try {
            int count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM weight_config WHERE id = 1", Integer.class);
            if (count != null && count > 0) {
                jdbcTemplate.update(
                    "UPDATE weight_config SET weights_csv=?, threshold=?, version=?, updated_at=? WHERE id=1",
                    csv.toString(), threshold, version, Instant.now().toString()
                );
            } else {
                jdbcTemplate.update(
                    "INSERT INTO weight_config(id, weights_csv, threshold, version, updated_at) VALUES(1, ?, ?, ?, ?)",
                    csv.toString(), threshold, version, Instant.now().toString()
                );
            }
        } catch (Exception e) {
            log.error("Failed to persist weights to DB: {}", e.getMessage());
        }
    }

    private void validateWeights(float[] weights) {
        if (weights.length != 20) {
            throw new IllegalArgumentException("Weights must have exactly 20 values, got " + weights.length);
        }
        float sum = 0;
        for (float w : weights) sum += w;
        if (Math.abs(sum - 1.0f) > 0.05f) {
            log.warn("Weights do not sum to 1.0 (sum={}). Proceeding anyway.", sum);
        }
    }

    public float[] getWeights() { return currentWeights.get(); }
    public float getThreshold() { return currentThreshold.get(); }
    public String getWeightsVersion() { return weightsVersion; }
}
