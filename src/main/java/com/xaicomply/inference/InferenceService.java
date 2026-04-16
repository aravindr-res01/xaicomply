package com.xaicomply.inference;

import com.xaicomply.domain.AttributionResult;
import com.xaicomply.domain.FeatureVector;
import com.xaicomply.inference.lime.LimeExplainer;
import com.xaicomply.inference.shap.ShapExplainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrates model scoring and explanation.
 * Supports SHAP and LIME explanation methods.
 */
@Service
public class InferenceService {

    private static final Logger log = LoggerFactory.getLogger(InferenceService.class);

    private final ModelScoringEngine modelScoringEngine;
    private final ShapExplainer shapExplainer;
    private final LimeExplainer limeExplainer;

    public InferenceService(ModelScoringEngine modelScoringEngine,
                            ShapExplainer shapExplainer,
                            LimeExplainer limeExplainer) {
        this.modelScoringEngine = modelScoringEngine;
        this.shapExplainer = shapExplainer;
        this.limeExplainer = limeExplainer;
    }

    /**
     * Scores and explains using SHAP (default method).
     */
    public AttributionResult scoreAndExplain(FeatureVector featureVector) {
        return scoreAndExplain(featureVector, "SHAP");
    }

    /**
     * Scores a feature vector and explains using the specified method.
     *
     * @param featureVector the preprocessed feature vector
     * @param method        "SHAP" or "LIME"
     * @return AttributionResult containing score and attributions
     */
    public AttributionResult scoreAndExplain(FeatureVector featureVector, String method) {
        float modelScore = modelScoringEngine.score(featureVector.features());
        log.info("Model scored transactionId={} score={} method={}", featureVector.transactionId(), modelScore, method);

        return switch (method.toUpperCase()) {
            case "LIME" -> limeExplainer.explain(featureVector.features(), modelScore, featureVector.featureNames());
            default -> shapExplainer.explain(featureVector.features(), modelScore, featureVector.featureNames());
        };
    }

    /**
     * Scores only without explanation.
     */
    public float score(FeatureVector featureVector) {
        return modelScoringEngine.score(featureVector.features());
    }
}
