package com.xaicomply.api;

import com.xaicomply.api.dto.CalibrateRequest;
import com.xaicomply.api.dto.WeightsRequest;
import com.xaicomply.exception.ApiResponse;
import com.xaicomply.mapping.ThresholdCalibrator;
import com.xaicomply.mapping.WeightRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private static final Logger log = LoggerFactory.getLogger(AdminController.class);

    private final WeightRegistry weightRegistry;
    private final ThresholdCalibrator thresholdCalibrator;

    public AdminController(WeightRegistry weightRegistry, ThresholdCalibrator thresholdCalibrator) {
        this.weightRegistry = weightRegistry;
        this.thresholdCalibrator = thresholdCalibrator;
    }

    /**
     * POST /api/v1/admin/calibrate
     * Performs threshold calibration using labeled samples.
     */
    @PostMapping("/calibrate")
    public ResponseEntity<ApiResponse<Map<String, Object>>> calibrate(
            @RequestBody List<CalibrateRequest> requests) {

        log.info("Calibration requested with {} samples", requests.size());

        List<ThresholdCalibrator.LabeledSample> samples = requests.stream()
                .map(r -> new ThresholdCalibrator.LabeledSample(r.transactionId(), r.trueLabel()))
                .collect(Collectors.toList());

        ThresholdCalibrator.CalibrationResult result = thresholdCalibrator.calibrate(samples);

        Map<String, Object> response = Map.of(
                "optimalThreshold", result.optimalThreshold(),
                "precision", result.precision(),
                "recall", result.recall(),
                "f1Score", result.f1Score(),
                "samplesUsed", result.samplesUsed()
        );

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * GET /api/v1/admin/weights
     * Returns current regulatory mapping weights.
     */
    @GetMapping("/weights")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getWeights() {
        float[] weights = weightRegistry.getWeights();
        float threshold = weightRegistry.getThreshold();

        float sum = 0;
        for (float w : weights) sum += w;

        // Convert float[] to List for JSON serialization
        List<Float> weightList = new java.util.ArrayList<>();
        for (float w : weights) weightList.add(w);

        Map<String, Object> response = Map.of(
                "weights", weightList,
                "threshold", threshold,
                "weightsVersion", weightRegistry.getWeightsVersion(),
                "sumOfWeights", sum
        );

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * POST /api/v1/admin/weights
     * Updates regulatory mapping weights and threshold.
     */
    @PostMapping("/weights")
    public ResponseEntity<ApiResponse<Map<String, Object>>> updateWeights(
            @RequestBody WeightsRequest request) {

        log.info("Updating weights: threshold={}", request.threshold());

        if (request.weights() == null || request.weights().length != 20) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("weights must have exactly 20 values"));
        }

        float threshold = request.threshold() != null ? request.threshold().floatValue() : weightRegistry.getThreshold();
        weightRegistry.updateWeights(request.weights(), threshold);

        Map<String, Object> response = Map.of(
                "success", true,
                "newThreshold", threshold,
                "weightsVersion", weightRegistry.getWeightsVersion()
        );

        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
