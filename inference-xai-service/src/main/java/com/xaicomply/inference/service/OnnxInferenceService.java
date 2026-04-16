package com.xaicomply.inference.service;

import ai.onnxruntime.*;
import com.xaicomply.common.exception.XaiComplyException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * ONNX Runtime Java inference service.
 *
 * Loads the RandomForest ONNX model (trained by train_model.py) at startup
 * and provides per-transaction risk score predictions.
 *
 * Paper Section 2.3:
 *   "the Inference & XAI Service loads ONNX models via the ONNX Runtime Java
 *    API with prediction latencies of less than 50ms even for ensemble models."
 *
 * ONNX model contracts:
 *   Input:  "float_input"        shape=[batch, 30]   dtype=float32
 *   Output: "output_label"       shape=[batch]        dtype=int64
 *   Output: "output_probability" shape=[batch, 2]     dtype=float32
 *             index 0 = P(legitimate), index 1 = P(fraud)
 */
@Service
public class OnnxInferenceService {

    private static final Logger log = LoggerFactory.getLogger(OnnxInferenceService.class);

    private static final String INPUT_NAME       = "float_input";
    private static final String OUTPUT_LABEL     = "output_label";
    private static final String OUTPUT_PROB      = "output_probability";
    private static final int    FRAUD_CLASS_IDX  = 1;
    private static final int    N_FEATURES       = 30;

    @Value("${onnx.model.path:classpath:model/fraud_model.onnx}")
    private String modelPath;

    private final ResourceLoader resourceLoader;
    private final Timer inferenceTimer;

    private OrtEnvironment ortEnvironment;
    private OrtSession    ortSession;
    private boolean       modelLoaded = false;

    public OnnxInferenceService(ResourceLoader resourceLoader, MeterRegistry meterRegistry) {
        this.resourceLoader = resourceLoader;
        this.inferenceTimer = Timer.builder("xaicomply_inference_duration_ms")
                .description("ONNX inference duration in milliseconds")
                .register(meterRegistry);
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @PostConstruct
    public void loadModel() {
        log.info("[OnnxInference] ═══════════════════════════════════════════");
        log.info("[OnnxInference] Loading ONNX model from: {}", modelPath);
        log.info("[OnnxInference] ONNX Runtime version: {}", OrtEnvironment.VERSION);

        try {
            ortEnvironment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
            opts.setIntraOpNumThreads(4);
            opts.setInterOpNumThreads(2);
            opts.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            log.info("[OnnxInference] Session options: intraOpThreads=4, interOpThreads=2, OptLevel=ALL");

            // Resolve model path (supports classpath: and file: prefixes)
            byte[] modelBytes = loadModelBytes();
            ortSession = ortEnvironment.createSession(modelBytes, opts);

            // Log model I/O metadata for audit
            logModelMetadata();
            modelLoaded = true;

            log.info("[OnnxInference] ✅ ONNX model loaded successfully");
            log.info("[OnnxInference] ═══════════════════════════════════════════");

        } catch (OrtException e) {
            log.error("[OnnxInference] ❌ Failed to load ONNX model: {}", e.getMessage(), e);
            log.error("[OnnxInference] Ensure train_model.py has been run: python train_model.py --data creditcard.csv");
            throw new XaiComplyException.ModelNotFoundException(modelPath);
        } catch (IOException e) {
            log.error("[OnnxInference] ❌ Cannot read model file: {}", modelPath, e);
            throw new XaiComplyException.ModelNotFoundException(modelPath);
        }
    }

    @PreDestroy
    public void shutdown() {
        log.info("[OnnxInference] Shutting down ONNX Runtime session...");
        try {
            if (ortSession != null) ortSession.close();
            if (ortEnvironment != null) ortEnvironment.close();
            log.info("[OnnxInference] ONNX Runtime shutdown complete");
        } catch (OrtException e) {
            log.warn("[OnnxInference] Error during ONNX shutdown: {}", e.getMessage());
        }
    }

    // ─── Inference ────────────────────────────────────────────────────────────

    /**
     * Run ONNX inference on a single transaction feature vector.
     *
     * @param features float array of length 30 in order [Time, V1..V28, Amount]
     * @return fraud probability ∈ [0, 1]
     */
    public double predict(float[] features) {
        validateFeatures(features);

        log.debug("[OnnxInference] Running inference on feature vector length={}",
                features.length);

        return inferenceTimer.record(() -> {
            try {
                return runInference(features);
            } catch (OrtException e) {
                log.error("[OnnxInference] Inference failed: {}", e.getMessage(), e);
                throw new XaiComplyException.InferenceException(
                        "ONNX Runtime inference error: " + e.getMessage(), e);
            }
        });
    }

    private double runInference(float[] features) throws OrtException {
        long start = System.currentTimeMillis();

        // Reshape to [1, 30] for batch=1
        float[][] inputData = new float[1][N_FEATURES];
        System.arraycopy(features, 0, inputData[0], 0, N_FEATURES);

        // Create input tensor
        OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnvironment, inputData);
        Map<String, OnnxTensor> inputs = Map.of(INPUT_NAME, inputTensor);

        log.debug("[OnnxInference] Calling session.run with input shape [1, {}]", N_FEATURES);

        // Run inference
        try (OrtSession.Result result = ortSession.run(inputs)) {

            // Extract fraud probability from output_probability[0][1]
            double fraudProbability = extractFraudProbability(result);
            long elapsed = System.currentTimeMillis() - start;

            log.debug("[OnnxInference] Inference complete: fraudProb={} timeMs={}",
                    fraudProbability, elapsed);

            if (elapsed > 100) {
                log.warn("[OnnxInference] Inference latency {}ms exceeds 100ms warning threshold", elapsed);
            }

            return fraudProbability;

        } finally {
            inputTensor.close();
        }
    }

    private double extractFraudProbability(OrtSession.Result result) throws OrtException {
        // Get output_probability tensor
        OnnxValue probValue = result.get(OUTPUT_PROB)
                .orElseThrow(() -> new XaiComplyException.InferenceException(
                        "ONNX output '" + OUTPUT_PROB + "' not found in model output"));

        // Cast to float[][] — shape [1, 2]
        // Index [0][0] = P(legitimate), [0][1] = P(fraud)
        Object rawValue = probValue.getValue();

        if (rawValue instanceof float[][]) {
            float[][] probs = (float[][]) rawValue;
            double fraudProb = probs[0][FRAUD_CLASS_IDX];
            log.debug("[OnnxInference] Probabilities: legit={} fraud={}",
                    probs[0][0], probs[0][1]);
            return fraudProb;
        } else {
            // Fallback: attempt OnnxSequence/OnnxMap handling
            log.warn("[OnnxInference] Unexpected output type: {} — attempting fallback",
                    rawValue.getClass().getName());
            return extractProbabilityFallback(probValue);
        }
    }

    private double extractProbabilityFallback(OnnxValue probValue) throws OrtException {
        // Some skl2onnx versions output Sequence<Map<Long, Float>>
        // Try getting label output instead
        log.warn("[OnnxInference] Using label output as fallback — accuracy may be reduced");
        OnnxValue labelValue = ortSession.run(Map.of()).get(OUTPUT_LABEL)
                .orElse(null);
        if (labelValue != null) {
            Object labelRaw = labelValue.getValue();
            if (labelRaw instanceof long[]) {
                return ((long[]) labelRaw)[0] == 1L ? 0.8 : 0.2;
            }
        }
        return 0.5; // Unknown — return neutral
    }

    // ─── Model metadata ───────────────────────────────────────────────────────

    private void logModelMetadata() throws OrtException {
        log.info("[OnnxInference] Model inputs:");
        for (NodeInfo input : ortSession.getInputInfo().values()) {
            log.info("[OnnxInference]   Input: name='{}' type={} shape={}",
                    input.getName(), input.getInfo(), getShapeStr(input));
        }
        log.info("[OnnxInference] Model outputs:");
        for (NodeInfo output : ortSession.getOutputInfo().values()) {
            log.info("[OnnxInference]   Output: name='{}' type={}",
                    output.getName(), output.getInfo());
        }

        OrtModelMetadata metadata = ortSession.getMetadata();
        log.info("[OnnxInference] Model metadata: domain='{}' description='{}'",
                metadata.getDomain(), metadata.getDescription());
    }

    private String getShapeStr(NodeInfo info) {
        try {
            return info.getInfo().toString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private void validateFeatures(float[] features) {
        if (features == null) {
            throw new XaiComplyException.InferenceException("Feature vector cannot be null");
        }
        if (features.length != N_FEATURES) {
            log.error("[OnnxInference] Feature vector length mismatch: got {}, expected {}",
                    features.length, N_FEATURES);
            throw new XaiComplyException.InferenceException(
                    "Feature vector must have exactly " + N_FEATURES + " elements. Got: " + features.length);
        }
        // Check for NaN/Infinity
        for (int i = 0; i < features.length; i++) {
            if (Float.isNaN(features[i]) || Float.isInfinite(features[i])) {
                log.warn("[OnnxInference] Feature[{}] has invalid value: {} — replacing with 0.0",
                        i, features[i]);
                features[i] = 0.0f;
            }
        }
    }

    private byte[] loadModelBytes() throws IOException {
        log.debug("[OnnxInference] Loading model bytes from: {}", modelPath);
        Resource resource = resourceLoader.getResource(modelPath);
        if (!resource.exists()) {
            throw new IOException("ONNX model not found: " + modelPath);
        }
        byte[] bytes = resource.getInputStream().readAllBytes();
        log.info("[OnnxInference] Model file loaded: {} KB", bytes.length / 1024.0);
        return bytes;
    }

    public boolean isModelLoaded() { return modelLoaded; }
}
