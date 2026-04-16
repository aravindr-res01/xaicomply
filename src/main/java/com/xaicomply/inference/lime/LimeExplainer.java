package com.xaicomply.inference.lime;

import com.xaicomply.config.ModelConfig;
import com.xaicomply.domain.AttributionResult;
import com.xaicomply.inference.ModelScoringEngine;
import org.apache.commons.math3.stat.regression.OLSMultipleLinearRegression;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Random;

/**
 * LIME explainer using perturbation-based local linear approximation.
 * Implements Eq.2: argmin_g[L(f,g,pi_x) + Omega(g)]
 *
 * Algorithm:
 * 1. Generate numSamples binary perturbations
 * 2. Score each perturbed sample with the model
 * 3. Apply exponential kernel weights based on cosine distance
 * 4. Fit weighted OLS regression to get feature attributions
 */
@Component
public class LimeExplainer {

    private static final Logger log = LoggerFactory.getLogger(LimeExplainer.class);

    private final ModelScoringEngine modelScoringEngine;
    private final float[] backgroundMeans;
    private final int numSamples;
    private final double sigmaFactor;

    public LimeExplainer(ModelScoringEngine modelScoringEngine, ModelConfig modelConfig) {
        this.modelScoringEngine = modelScoringEngine;
        this.backgroundMeans = modelConfig.getShap().getBackgroundMeansArray();
        this.numSamples = modelConfig.getLime().getNumSamples();
        this.sigmaFactor = modelConfig.getLime().getSigmaFactor();
    }

    /**
     * Explains model prediction using LIME local linear approximation.
     *
     * @param features     the feature vector for the transaction
     * @param modelScore   the model's prediction score
     * @param featureNames the names of each feature
     * @return AttributionResult with phi0 (intercept) and phi[i] (coefficients)
     */
    public AttributionResult explain(float[] features, float modelScore, String[] featureNames) {
        int numFeatures = features.length;
        Random random = new Random(42); // fixed seed for reproducibility

        // 1. Generate perturbations
        float[][] Z = new float[numSamples][numFeatures]; // binary masks
        float[][] X = new float[numSamples][numFeatures]; // actual perturbed values
        float[] yHat = new float[numSamples];

        for (int s = 0; s < numSamples; s++) {
            boolean allZero = true;
            for (int i = 0; i < numFeatures; i++) {
                if (random.nextFloat() < 0.5f) {
                    Z[s][i] = 1.0f;
                    X[s][i] = features[i];
                    allZero = false;
                } else {
                    Z[s][i] = 0.0f;
                    X[s][i] = backgroundMeans[i];
                }
            }
            // Edge case: if all Z[s] = 0, set Z[s][0] = 1
            if (allZero) {
                Z[s][0] = 1.0f;
                X[s][0] = features[0];
            }
            yHat[s] = modelScoringEngine.score(X[s]);
        }

        // 2. Kernel weights using cosine distance
        float sigma = (float) (Math.sqrt(numFeatures) * sigmaFactor);
        float[] weights = new float[numSamples];
        float[] onesVector = new float[numFeatures];
        for (int i = 0; i < numFeatures; i++) onesVector[i] = 1.0f;

        for (int s = 0; s < numSamples; s++) {
            float distance = cosineDistance(Z[s], onesVector);
            weights[s] = (float) Math.exp(-(distance * distance) / (sigma * sigma));
            if (Float.isNaN(weights[s]) || Float.isInfinite(weights[s])) {
                weights[s] = 1e-6f; // safe fallback
            }
        }

        // 3. Weighted OLS using commons-math3
        double[] yHatDouble = new double[numSamples];
        double[][] ZDouble = new double[numSamples][numFeatures];
        double[] weightsDouble = new double[numSamples];

        for (int s = 0; s < numSamples; s++) {
            yHatDouble[s] = yHat[s];
            weightsDouble[s] = weights[s];
            for (int i = 0; i < numFeatures; i++) {
                ZDouble[s][i] = Z[s][i];
            }
        }

        double[] coefficients;
        try {
            OLSMultipleLinearRegression regression = new OLSMultipleLinearRegression();
            regression.newSampleData(applyWeights(yHatDouble, weightsDouble),
                    applyWeightsToMatrix(ZDouble, weightsDouble));
            coefficients = regression.estimateRegressionParameters();
        } catch (Exception e) {
            log.warn("LIME OLS regression failed, falling back to zero attributions: {}", e.getMessage());
            coefficients = new double[numFeatures + 1]; // intercept + features
        }

        // coefficients[0] = intercept (phi0), coefficients[1..n] = feature attributions
        float phi0 = (float) coefficients[0];
        float[] phi = new float[numFeatures];
        for (int i = 0; i < numFeatures; i++) {
            float val = (i + 1 < coefficients.length) ? (float) coefficients[i + 1] : 0.0f;
            phi[i] = Float.isNaN(val) || Float.isInfinite(val) ? 0.0f : val;
        }

        log.debug("LIME explained {} features with {} samples", numFeatures, numSamples);
        return new AttributionResult("LIME", phi0, phi, featureNames, modelScore, Instant.now());
    }

    /**
     * Computes cosine distance between two vectors.
     * distance = 1 - (a · b) / (|a| * |b|)
     */
    private float cosineDistance(float[] a, float[] b) {
        double dot = 0.0, normA = 0.0, normB = 0.0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0 || normB <= 0) return 1.0f;
        double cosine = dot / (Math.sqrt(normA) * Math.sqrt(normB));
        // Clamp to [-1, 1] to avoid NaN from floating point errors
        cosine = Math.max(-1.0, Math.min(1.0, cosine));
        return (float) (1.0 - cosine);
    }

    /**
     * Applies sqrt(weights) to y for weighted OLS.
     * Weighted OLS: multiply both X and y by sqrt(w)
     */
    private double[] applyWeights(double[] y, double[] weights) {
        double[] result = new double[y.length];
        for (int i = 0; i < y.length; i++) {
            result[i] = y[i] * Math.sqrt(weights[i]);
        }
        return result;
    }

    private double[][] applyWeightsToMatrix(double[][] X, double[] weights) {
        double[][] result = new double[X.length][X[0].length];
        for (int i = 0; i < X.length; i++) {
            double sqrtW = Math.sqrt(weights[i]);
            for (int j = 0; j < X[0].length; j++) {
                result[i][j] = X[i][j] * sqrtW;
            }
        }
        return result;
    }
}
