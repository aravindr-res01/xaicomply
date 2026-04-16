package com.xaicomply.api;

import com.xaicomply.api.dto.AttributionDto;
import com.xaicomply.api.dto.ExplainRequest;
import com.xaicomply.api.dto.ExplainResponse;
import com.xaicomply.domain.AttributionResult;
import com.xaicomply.domain.FeatureVector;
import com.xaicomply.domain.RiskScore;
import com.xaicomply.domain.Transaction;
import com.xaicomply.exception.ApiResponse;
import com.xaicomply.inference.InferenceService;
import com.xaicomply.mapping.RegulatoryMappingService;
import com.xaicomply.mapping.WeightRegistry;
import com.xaicomply.preprocessing.PreprocessingService;
import com.xaicomply.repository.TransactionRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transactions")
public class ExplainController {

    private static final Logger log = LoggerFactory.getLogger(ExplainController.class);

    private final TransactionRepository transactionRepository;
    private final PreprocessingService preprocessingService;
    private final InferenceService inferenceService;
    private final RegulatoryMappingService regulatoryMappingService;
    private final WeightRegistry weightRegistry;

    public ExplainController(TransactionRepository transactionRepository,
                             PreprocessingService preprocessingService,
                             InferenceService inferenceService,
                             RegulatoryMappingService regulatoryMappingService,
                             WeightRegistry weightRegistry) {
        this.transactionRepository = transactionRepository;
        this.preprocessingService = preprocessingService;
        this.inferenceService = inferenceService;
        this.regulatoryMappingService = regulatoryMappingService;
        this.weightRegistry = weightRegistry;
    }

    /**
     * POST /api/v1/transactions/{id}/explain
     * Re-explains a transaction using SHAP or LIME.
     */
    @PostMapping("/{id}/explain")
    public ResponseEntity<ApiResponse<ExplainResponse>> explain(
            @PathVariable UUID id,
            @Valid @RequestBody ExplainRequest request) {

        log.info("Explaining transaction {} with method {}", id, request.method());

        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Transaction not found: " + id));

        // Re-preprocess (read-only — don't update Welford stats)
        FeatureVector featureVector = preprocessingService.preprocessReadOnly(tx);

        // Run requested explainer
        AttributionResult attribution = inferenceService.scoreAndExplain(featureVector, request.method());

        // Compute risk score (re-compute with current weights)
        RiskScore riskScore = regulatoryMappingService.computeRiskScore(tx, attribution);

        // Sort attributions by |phi| DESC
        List<AttributionDto> sorted = buildSortedAttributions(attribution);

        // Build human-readable interpretation
        String interpretation = buildInterpretation(tx, attribution, riskScore, sorted);

        ExplainResponse response = new ExplainResponse(
                tx.getId(),
                attribution.method(),
                attribution.modelScore(),
                attribution.phi0(),
                riskScore.getRiskScore(),
                riskScore.isFlagged(),
                riskScore.getThreshold(),
                sorted,
                interpretation
        );

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    private List<AttributionDto> buildSortedAttributions(AttributionResult attribution) {
        float[] phis = attribution.phis();
        String[] names = attribution.featureNames();
        List<AttributionDto> list = new ArrayList<>();
        for (int i = 0; i < phis.length; i++) {
            String name = (names != null && i < names.length) ? names[i] : "feature_" + i;
            list.add(new AttributionDto(name, phis[i], Math.abs(phis[i]), 0));
        }
        list.sort(Comparator.comparingDouble(AttributionDto::absValue).reversed());
        List<AttributionDto> ranked = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            AttributionDto a = list.get(i);
            ranked.add(new AttributionDto(a.featureName(), a.phi(), a.absValue(), i + 1));
        }
        return ranked;
    }

    private String buildInterpretation(Transaction tx, AttributionResult attribution,
                                       RiskScore riskScore, List<AttributionDto> sorted) {
        StringBuilder sb = new StringBuilder();

        if (riskScore.isFlagged()) {
            sb.append("Transaction flagged. ");
        } else {
            sb.append("Transaction not flagged. ");
        }

        if (!sorted.isEmpty()) {
            sb.append("Primary drivers: ");
            int limit = Math.min(3, sorted.size());
            for (int i = 0; i < limit; i++) {
                AttributionDto a = sorted.get(i);
                sb.append(a.featureName())
                        .append(String.format(" (phi=%+.2f)", a.phi()));
                if (i < limit - 1) sb.append(", ");
            }
            sb.append(". ");
        }

        sb.append(String.format("Risk score %.2f %s threshold %.2f.",
                riskScore.getRiskScore(),
                riskScore.isFlagged() ? "exceeds" : "is below",
                riskScore.getThreshold()));

        return sb.toString();
    }
}
