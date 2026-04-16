package com.xaicomply.inference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Model scoring engine — computes risk score from feature vector.
 * Currently implements a weighted linear stub with sigmoid activation.
 *
 * TODO: Replace this method body with ONNX Runtime:
 * <pre>
 * // OrtEnvironment env = OrtEnvironment.getEnvironment();
 * // OrtSession session = env.createSession(modelPath, new OrtSession.SessionOptions());
 * // float[][] input = new float[][]{features};
 * // OnnxTensor tensor = OnnxTensor.createTensor(env, input);
 * // OrtSession.Result result = session.run(Collections.singletonMap("input", tensor));
 * // return ((float[][]) result.get(0).getValue())[0][0];
 * </pre>
 */
@Component
public class ModelScoringEngine {

    private static final Logger log = LoggerFactory.getLogger(ModelScoringEngine.class);

    /**
     * Scores a feature vector, returning a probability in [0.0, 1.0].
     *
     * @param features float[20]: [amount_z, velocity_z, hour_z, mcc(x10), country(x6), international]
     * @return risk probability
     */
    public float score(float[] features) {
        if (features == null || features.length < 20) {
            log.warn("Invalid feature vector length: {}", features == null ? "null" : features.length);
            return 0.5f;
        }

        // Weighted linear combination of key features
        float raw = 0.35f * features[0]                      // normalized amount (index 0)
                  + 0.28f * features[1]                      // normalized velocity (index 1)
                  + 0.05f * Math.abs(features[2] - 1.5f)     // off-hours penalty (index 2)
                  + 0.15f * features[19]                     // international flag (index 19)
                  + 0.17f * features[13];                    // country risk (index 13 = first country one-hot)

        // Sigmoid activation to produce probability in [0, 1]
        float score = (float) (1.0 / (1.0 + Math.exp(-raw)));

        log.debug("Model scored features: raw={:.4f}, score={:.4f}", raw, score);
        return score;
    }
}
