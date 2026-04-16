package com.xaicomply.inference.shap;

import com.xaicomply.config.ModelConfig;
import com.xaicomply.domain.AttributionResult;
import com.xaicomply.inference.ModelScoringEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SHAP explainer using sampling approximation.
 * Implements Eq.1: f(x) = phi0 + sum(phi_i)
 *
 * Algorithm: For each feature i, compute marginal contribution by replacing
 * background mean with actual value, measuring score difference from baseline.
 */
@Component
public class ShapExplainer {

    private static final Logger log = LoggerFactory.getLogger(ShapExplainer.class);

    private final ModelScoringEngine modelScoringEngine;
    private final float[] backgroundMeans;
    private final int cacheSize;

    // LRU Cache: ConcurrentHashMap backed by LinkedHashMap
    private final Map<String, AttributionResult> cache;

    public ShapExplainer(ModelScoringEngine modelScoringEngine, ModelConfig modelConfig) {
        this.modelScoringEngine = modelScoringEngine;
        this.backgroundMeans = modelConfig.getShap().getBackgroundMeansArray();
        this.cacheSize = modelConfig.getShap().getCacheSize();

        // LRU cache backed by LinkedHashMap with access-order
        Map<String, AttributionResult> lruMap = new LinkedHashMap<>(cacheSize, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, AttributionResult> eldest) {
                return size() > cacheSize;
            }
        };
        this.cache = Collections.synchronizedMap(lruMap);
    }

    /**
     * Explains model prediction using SHAP sampling approximation.
     *
     * @param features   the feature vector for the transaction
     * @param modelScore the model's prediction score
     * @param featureNames the names of each feature
     * @return AttributionResult with phi0 and phi[i] for each feature
     */
    public AttributionResult explain(float[] features, float modelScore, String[] featureNames) {
        String cacheKey = Arrays.toString(features);

        AttributionResult cached = cache.get(cacheKey);
        if (cached != null) {
            log.debug("SHAP cache hit for key length {}", cacheKey.length());
            return cached;
        }

        int numFeatures = features.length;

        // 1. Compute phi0 = score of background means vector
        float phi0 = modelScoringEngine.score(backgroundMeans);

        // 2. For each feature i, compute marginal contribution
        float[] rawPhi = new float[numFeatures];
        for (int i = 0; i < numFeatures; i++) {
            float[] perturbed = Arrays.copyOf(backgroundMeans, backgroundMeans.length);
            perturbed[i] = features[i];
            rawPhi[i] = modelScoringEngine.score(perturbed) - phi0;
        }

        // 3. Normalize for local accuracy: phi0 + sum(phi) == modelScore
        float sumRaw = 0.0f;
        for (float v : rawPhi) sumRaw += v;

        float factor = (Math.abs(sumRaw) < 1e-10f) ? 1.0f : (modelScore - phi0) / sumRaw;
        float[] phi = new float[numFeatures];
        for (int i = 0; i < numFeatures; i++) {
            phi[i] = rawPhi[i] * factor;
        }

        // 4. Assert local accuracy
        float sumPhi = 0.0f;
        for (float v : phi) sumPhi += v;
        float localAccuracyError = Math.abs(phi0 + sumPhi - modelScore);
        if (localAccuracyError >= 0.001f) {
            log.warn("SHAP local accuracy assertion failed: |phi0({}) + sum(phi)({}) - modelScore({})| = {} >= 0.001",
                    phi0, sumPhi, modelScore, localAccuracyError);
        }

        AttributionResult result = new AttributionResult("SHAP", phi0, phi, featureNames, modelScore, Instant.now());
        cache.put(cacheKey, result);
        return result;
    }
}
