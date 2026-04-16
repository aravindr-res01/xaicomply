package com.xaicomply;

import com.xaicomply.config.ModelConfig;
import com.xaicomply.domain.AttributionResult;
import com.xaicomply.inference.ModelScoringEngine;
import com.xaicomply.inference.shap.ShapExplainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ShapExplainerTest {

    private ShapExplainer shapExplainer;
    private ModelScoringEngine modelScoringEngine;
    private float[] testFeatures;
    private String[] testFeatureNames;

    @BeforeEach
    void setUp() {
        modelScoringEngine = new ModelScoringEngine();

        ModelConfig modelConfig = new ModelConfig();
        // Default config with zeros for background means
        shapExplainer = new ShapExplainer(modelScoringEngine, modelConfig);

        // Create known test features
        testFeatures = new float[20];
        testFeatures[0] = 1.5f;  // high amount
        testFeatures[1] = 2.0f;  // high velocity
        testFeatures[2] = 0.5f;  // hour
        testFeatures[19] = 1.0f; // international

        testFeatureNames = new String[20];
        for (int i = 0; i < 20; i++) {
            testFeatureNames[i] = "feature_" + i;
        }
    }

    @Test
    void explain_shouldSatisfyLocalAccuracy() {
        float modelScore = modelScoringEngine.score(testFeatures);
        AttributionResult result = shapExplainer.explain(testFeatures, modelScore, testFeatureNames);

        // |phi0 + sum(phis) - modelScore| < 0.001
        float sum = result.phi0();
        for (float phi : result.phis()) {
            sum += phi;
        }
        float error = Math.abs(sum - modelScore);
        assertThat(error).as("Local accuracy: |phi0 + sum(phis) - modelScore| < 0.001").isLessThan(0.001f);
    }

    @Test
    void explain_shouldReturn20Attributions() {
        float modelScore = modelScoringEngine.score(testFeatures);
        AttributionResult result = shapExplainer.explain(testFeatures, modelScore, testFeatureNames);

        assertThat(result.phis()).hasSize(20);
        assertThat(result.featureNames()).hasSize(20);
    }

    @Test
    void explain_shouldHaveNonNullFeatureNames() {
        float modelScore = modelScoringEngine.score(testFeatures);
        AttributionResult result = shapExplainer.explain(testFeatures, modelScore, testFeatureNames);

        for (String name : result.featureNames()) {
            assertThat(name).isNotNull().isNotBlank();
        }
    }

    @Test
    void explain_secondCallWithSameInput_shouldReturnCachedResult() {
        float modelScore = modelScoringEngine.score(testFeatures);

        AttributionResult first = shapExplainer.explain(testFeatures, modelScore, testFeatureNames);
        AttributionResult second = shapExplainer.explain(testFeatures, modelScore, testFeatureNames);

        // Same object reference indicates cache hit
        assertThat(second).isSameAs(first);
    }

    @Test
    void explain_shouldReturnMethodShap() {
        float modelScore = modelScoringEngine.score(testFeatures);
        AttributionResult result = shapExplainer.explain(testFeatures, modelScore, testFeatureNames);

        assertThat(result.method()).isEqualTo("SHAP");
    }

    @Test
    void explain_modelScoreInResult_shouldMatchInput() {
        float modelScore = modelScoringEngine.score(testFeatures);
        AttributionResult result = shapExplainer.explain(testFeatures, modelScore, testFeatureNames);

        assertThat(result.modelScore()).isEqualTo(modelScore);
    }
}
