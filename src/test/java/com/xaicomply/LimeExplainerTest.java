package com.xaicomply;

import com.xaicomply.config.ModelConfig;
import com.xaicomply.domain.AttributionResult;
import com.xaicomply.inference.ModelScoringEngine;
import com.xaicomply.inference.lime.LimeExplainer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LimeExplainerTest {

    private LimeExplainer limeExplainer;
    private ModelScoringEngine modelScoringEngine;
    private float[] testFeatures;
    private String[] testFeatureNames;

    @BeforeEach
    void setUp() {
        modelScoringEngine = new ModelScoringEngine();

        ModelConfig modelConfig = new ModelConfig();
        // Set small num samples for fast tests
        modelConfig.getLime().setNumSamples(100);

        limeExplainer = new LimeExplainer(modelScoringEngine, modelConfig);

        testFeatures = new float[20];
        testFeatures[0] = 1.5f;
        testFeatures[1] = 2.0f;
        testFeatures[2] = 0.5f;
        testFeatures[19] = 1.0f;

        testFeatureNames = new String[20];
        for (int i = 0; i < 20; i++) {
            testFeatureNames[i] = "feature_" + i;
        }
    }

    @Test
    void explain_shouldReturn20Attributions() {
        float modelScore = modelScoringEngine.score(testFeatures);
        AttributionResult result = limeExplainer.explain(testFeatures, modelScore, testFeatureNames);

        assertThat(result.phis()).hasSize(20);
        assertThat(result.featureNames()).hasSize(20);
    }

    @Test
    void explain_shouldHaveNoNaNOrInfinity() {
        float modelScore = modelScoringEngine.score(testFeatures);
        AttributionResult result = limeExplainer.explain(testFeatures, modelScore, testFeatureNames);

        for (float phi : result.phis()) {
            assertThat(Float.isNaN(phi)).as("phi should not be NaN").isFalse();
            assertThat(Float.isInfinite(phi)).as("phi should not be Infinite").isFalse();
        }
        assertThat(Float.isNaN(result.phi0())).as("phi0 should not be NaN").isFalse();
        assertThat(Float.isInfinite(result.phi0())).as("phi0 should not be Infinite").isFalse();
    }

    @Test
    void explain_modelScoreInResult_shouldMatchModelEngineOutput() {
        float expectedScore = modelScoringEngine.score(testFeatures);
        AttributionResult result = limeExplainer.explain(testFeatures, expectedScore, testFeatureNames);

        assertThat(result.modelScore()).isEqualTo(expectedScore);
    }

    @Test
    void explain_shouldReturnMethodLime() {
        float modelScore = modelScoringEngine.score(testFeatures);
        AttributionResult result = limeExplainer.explain(testFeatures, modelScore, testFeatureNames);

        assertThat(result.method()).isEqualTo("LIME");
    }

    @Test
    void explain_computedAt_shouldNotBeNull() {
        float modelScore = modelScoringEngine.score(testFeatures);
        AttributionResult result = limeExplainer.explain(testFeatures, modelScore, testFeatureNames);

        assertThat(result.computedAt()).isNotNull();
    }
}
