package com.xaicomply;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xaicomply.config.ModelConfig;
import com.xaicomply.domain.AttributionResult;
import com.xaicomply.domain.RiskScore;
import com.xaicomply.domain.Transaction;
import com.xaicomply.mapping.RegulatoryMappingService;
import com.xaicomply.mapping.ThresholdCalibrator;
import com.xaicomply.mapping.WeightRegistry;
import com.xaicomply.repository.RiskScoreRepository;
import com.xaicomply.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RegulatoryMappingEngineTest {

    private RegulatoryMappingService mappingService;
    private RiskScoreRepository riskScoreRepository;
    private TransactionRepository transactionRepository;
    private WeightRegistry weightRegistry;
    private ThresholdCalibrator thresholdCalibrator;

    @BeforeEach
    void setUp() {
        riskScoreRepository = mock(RiskScoreRepository.class);
        transactionRepository = mock(TransactionRepository.class);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ObjectMapper objectMapper = new ObjectMapper();

        // Set up known weights
        float[] weights = new float[20];
        for (int i = 0; i < 20; i++) weights[i] = 0.05f; // all equal, sum = 1.0

        weightRegistry = mock(WeightRegistry.class);
        when(weightRegistry.getWeights()).thenReturn(weights);
        when(weightRegistry.getThreshold()).thenReturn(0.5f);
        when(weightRegistry.getWeightsVersion()).thenReturn("test-v1");

        when(riskScoreRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(transactionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        mappingService = new RegulatoryMappingService(
                weightRegistry, riskScoreRepository, transactionRepository, eventPublisher, objectMapper);

        thresholdCalibrator = new ThresholdCalibrator(riskScoreRepository);
    }

    @Test
    void computeRiskScore_shouldMatchManualCalculation() {
        // Known phi[] = all 0.1f for 20 features
        float[] phis = new float[20];
        for (int i = 0; i < 20; i++) phis[i] = 0.1f;

        float[] weights = weightRegistry.getWeights(); // all 0.05f
        // Expected R = sum(0.05 * |0.1|) * 20 = 0.05 * 0.1 * 20 = 0.1
        float expectedR = 0.0f;
        for (int i = 0; i < 20; i++) expectedR += weights[i] * Math.abs(phis[i]);

        Transaction tx = createTransaction();
        AttributionResult attribution = new AttributionResult(
                "SHAP", 0.3f, phis, new String[20], 0.6f, Instant.now());

        RiskScore result = mappingService.computeRiskScore(tx, attribution);

        assertThat(result.getRiskScore()).isCloseTo(expectedR, org.assertj.core.data.Offset.offset(0.001f));
    }

    @Test
    void computeRiskScore_aboveThreshold_shouldBeFlagged() {
        // phis with high values to push R above threshold 0.5
        float[] phis = new float[20];
        for (int i = 0; i < 20; i++) phis[i] = 5.0f; // R = 0.05 * 5 * 20 = 5.0 >> threshold 0.5

        Transaction tx = createTransaction();
        AttributionResult attribution = new AttributionResult(
                "SHAP", 0.3f, phis, new String[20], 0.9f, Instant.now());

        RiskScore result = mappingService.computeRiskScore(tx, attribution);

        assertThat(result.isFlagged()).isTrue();
        assertThat(result.getRiskScore()).isGreaterThan(result.getThreshold());
    }

    @Test
    void computeRiskScore_belowThreshold_shouldNotBeFlagged() {
        // phis with low values to keep R below threshold 0.5
        float[] phis = new float[20];
        for (int i = 0; i < 20; i++) phis[i] = 0.1f; // R = 0.05 * 0.1 * 20 = 0.1 < threshold 0.5

        Transaction tx = createTransaction();
        AttributionResult attribution = new AttributionResult(
                "SHAP", 0.3f, phis, new String[20], 0.3f, Instant.now());

        RiskScore result = mappingService.computeRiskScore(tx, attribution);

        assertThat(result.isFlagged()).isFalse();
    }

    @Test
    void calibrate_with30LabeledSamples_shouldReturnF1InValidRange() {
        // Setup mock: return risk scores for each transaction
        List<ThresholdCalibrator.LabeledSample> samples = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            UUID txId = UUID.randomUUID();
            boolean trueLabel = i % 3 == 0; // ~33% positive
            samples.add(new ThresholdCalibrator.LabeledSample(txId, trueLabel));

            // Mock risk score: positive samples get higher scores
            RiskScore rs = new RiskScore();
            rs.setTransactionId(txId);
            rs.setRiskScore(trueLabel ? 0.8f : 0.3f);
            when(riskScoreRepository.findTopByTransactionIdOrderByCreatedAtDesc(txId))
                    .thenReturn(Optional.of(rs));
        }

        ThresholdCalibrator.CalibrationResult result = thresholdCalibrator.calibrate(samples);

        assertThat(result.f1Score()).isGreaterThan(0.0f);
        assertThat(result.f1Score()).isLessThanOrEqualTo(1.0f);
        assertThat(result.samplesUsed()).isEqualTo(30);
        assertThat(result.optimalThreshold()).isBetween(0.0f, 1.0f);
    }

    private Transaction createTransaction() {
        Transaction tx = new Transaction();
        tx.setId(UUID.randomUUID());
        tx.setCustomerId("cust-001");
        tx.setAmount(BigDecimal.valueOf(500));
        tx.setCurrency("USD");
        tx.setMerchantCategoryCode("5411");
        tx.setCountryCode("US");
        tx.setTransactionVelocity(3);
        tx.setIsInternational(false);
        tx.setHourOfDay(12);
        tx.setStatus(Transaction.Status.PENDING);
        return tx;
    }
}
