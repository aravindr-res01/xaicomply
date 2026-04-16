package com.xaicomply.api;

import com.xaicomply.api.dto.AttributionDto;
import com.xaicomply.api.dto.TransactionRequest;
import com.xaicomply.api.dto.TransactionResponse;
import com.xaicomply.domain.AttributionResult;
import com.xaicomply.domain.FeatureVector;
import com.xaicomply.domain.RiskScore;
import com.xaicomply.domain.Transaction;
import com.xaicomply.exception.ApiResponse;
import com.xaicomply.inference.InferenceService;
import com.xaicomply.mapping.RegulatoryMappingService;
import com.xaicomply.preprocessing.PreprocessingService;
import com.xaicomply.repository.RiskScoreRepository;
import com.xaicomply.repository.TransactionRepository;
import com.xaicomply.reporting.audit.AuditLogService;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private static final Logger log = LoggerFactory.getLogger(TransactionController.class);

    private final TransactionRepository transactionRepository;
    private final PreprocessingService preprocessingService;
    private final InferenceService inferenceService;
    private final RegulatoryMappingService regulatoryMappingService;
    private final RiskScoreRepository riskScoreRepository;
    private final AuditLogService auditLogService;

    public TransactionController(TransactionRepository transactionRepository,
                                 PreprocessingService preprocessingService,
                                 InferenceService inferenceService,
                                 RegulatoryMappingService regulatoryMappingService,
                                 RiskScoreRepository riskScoreRepository,
                                 AuditLogService auditLogService) {
        this.transactionRepository = transactionRepository;
        this.preprocessingService = preprocessingService;
        this.inferenceService = inferenceService;
        this.regulatoryMappingService = regulatoryMappingService;
        this.riskScoreRepository = riskScoreRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * POST /api/v1/transactions
     * Creates a transaction, runs full pipeline, and returns risk assessment.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<TransactionResponse>> createTransaction(
            @Valid @RequestBody TransactionRequest request) {

        long startTime = System.currentTimeMillis();
        log.info("Processing transaction for customerId={}", request.customerId());

        // 1. Create and persist transaction entity
        Transaction tx = new Transaction();
        tx.setCustomerId(request.customerId());
        tx.setAmount(request.amount());
        tx.setCurrency(request.currency());
        tx.setMerchantCategoryCode(request.merchantCategoryCode());
        tx.setCountryCode(request.countryCode());
        tx.setTransactionVelocity(request.transactionVelocity());
        tx.setIsInternational(request.isInternational());
        tx.setHourOfDay(request.hourOfDay());
        Transaction saved = transactionRepository.save(tx);

        // 2. Preprocess → FeatureVector (validates + normalizes)
        FeatureVector featureVector = preprocessingService.preprocess(saved);

        // 3. Score + SHAP explain
        AttributionResult attribution = inferenceService.scoreAndExplain(featureVector, "SHAP");

        // 4. Compute regulatory risk score
        RiskScore riskScore = regulatoryMappingService.computeRiskScore(saved, attribution);

        // 5. Audit
        auditLogService.record("TRANSACTION", saved.getId().toString(), "CREATED",
                java.util.Map.of("customerId", saved.getCustomerId(),
                        "amount", saved.getAmount().toPlainString(),
                        "riskScore", riskScore.getRiskScore(),
                        "flagged", riskScore.isFlagged()));

        // 6. Build top-5 attributions sorted by |phi| DESC
        List<AttributionDto> top5 = buildTop5Attributions(attribution);

        long processingTime = System.currentTimeMillis() - startTime;

        TransactionResponse response = new TransactionResponse(
                saved.getId(),
                saved.getCustomerId(),
                saved.getAmount(),
                saved.getCurrency(),
                saved.getMerchantCategoryCode(),
                saved.getCountryCode(),
                saved.getTransactionVelocity(),
                saved.getIsInternational(),
                saved.getHourOfDay(),
                saved.getStatus().name(),
                attribution.modelScore(),
                riskScore.getRiskScore(),
                riskScore.isFlagged(),
                top5,
                processingTime,
                saved.getCreatedAt()
        );

        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * GET /api/v1/transactions/{id}
     * Returns full transaction details with risk score.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TransactionResponse>> getTransaction(@PathVariable UUID id) {
        Transaction tx = transactionRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("Transaction not found: " + id));

        RiskScore riskScore = riskScoreRepository.findTopByTransactionIdOrderByCreatedAtDesc(id).orElse(null);

        TransactionResponse response = new TransactionResponse(
                tx.getId(),
                tx.getCustomerId(),
                tx.getAmount(),
                tx.getCurrency(),
                tx.getMerchantCategoryCode(),
                tx.getCountryCode(),
                tx.getTransactionVelocity(),
                tx.getIsInternational(),
                tx.getHourOfDay(),
                tx.getStatus().name(),
                riskScore != null ? riskScore.getPhi0() : null,
                riskScore != null ? riskScore.getRiskScore() : null,
                riskScore != null ? riskScore.isFlagged() : null,
                null,
                null,
                tx.getCreatedAt()
        );
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * GET /api/v1/transactions?customerId=X&status=FLAGGED&page=0&size=20
     */
    @GetMapping
    public ResponseEntity<ApiResponse<Page<TransactionResponse>>> listTransactions(
            @RequestParam(required = false) String customerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

        Pageable pageable = PageRequest.of(page, size);
        Page<Transaction> txPage;

        if (customerId != null && status != null) {
            Transaction.Status txStatus = Transaction.Status.valueOf(status.toUpperCase());
            txPage = transactionRepository.findByCustomerIdAndStatus(customerId, txStatus, pageable);
        } else if (customerId != null) {
            txPage = transactionRepository.findByCustomerId(customerId, pageable);
        } else if (status != null) {
            Transaction.Status txStatus = Transaction.Status.valueOf(status.toUpperCase());
            txPage = transactionRepository.findByStatus(txStatus, pageable);
        } else {
            txPage = transactionRepository.findAll(pageable);
        }

        Page<TransactionResponse> responsePage = txPage.map(tx -> new TransactionResponse(
                tx.getId(), tx.getCustomerId(), tx.getAmount(), tx.getCurrency(),
                tx.getMerchantCategoryCode(), tx.getCountryCode(),
                tx.getTransactionVelocity(), tx.getIsInternational(), tx.getHourOfDay(),
                tx.getStatus().name(), null, null, null, null, null, tx.getCreatedAt()
        ));

        return ResponseEntity.ok(ApiResponse.ok(responsePage));
    }

    private List<AttributionDto> buildTop5Attributions(AttributionResult attribution) {
        float[] phis = attribution.phis();
        String[] names = attribution.featureNames();

        List<AttributionDto> all = new ArrayList<>();
        for (int i = 0; i < phis.length; i++) {
            all.add(new AttributionDto(
                    names != null && i < names.length ? names[i] : "feature_" + i,
                    phis[i],
                    Math.abs(phis[i]),
                    0
            ));
        }

        all.sort(Comparator.comparingDouble(AttributionDto::absValue).reversed());

        List<AttributionDto> top5 = new ArrayList<>();
        for (int i = 0; i < Math.min(5, all.size()); i++) {
            AttributionDto a = all.get(i);
            top5.add(new AttributionDto(a.featureName(), a.phi(), a.absValue(), i + 1));
        }
        return top5;
    }
}
