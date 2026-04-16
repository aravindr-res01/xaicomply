package com.xaicomply.mapping;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xaicomply.domain.AttributionResult;
import com.xaicomply.domain.RiskScore;
import com.xaicomply.domain.Transaction;
import com.xaicomply.repository.RiskScoreRepository;
import com.xaicomply.repository.TransactionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes regulatory risk score using weighted attribution magnitudes.
 * Implements Eq.3: R = sum(omega_i * |phi_i|)
 *
 * If R > threshold, transaction is flagged and ComplianceFlagEvent is fired.
 */
@Service
public class RegulatoryMappingService {

    private static final Logger log = LoggerFactory.getLogger(RegulatoryMappingService.class);

    private final WeightRegistry weightRegistry;
    private final RiskScoreRepository riskScoreRepository;
    private final TransactionRepository transactionRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public RegulatoryMappingService(WeightRegistry weightRegistry,
                                    RiskScoreRepository riskScoreRepository,
                                    TransactionRepository transactionRepository,
                                    ApplicationEventPublisher eventPublisher,
                                    ObjectMapper objectMapper) {
        this.weightRegistry = weightRegistry;
        this.riskScoreRepository = riskScoreRepository;
        this.transactionRepository = transactionRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Computes risk score R = sum(omega_i * |phi_i|), persists RiskScore, flags transaction if needed.
     *
     * @param transaction     the transaction entity
     * @param attributionResult SHAP or LIME attribution result
     * @return persisted RiskScore entity
     */
    @Transactional
    public RiskScore computeRiskScore(Transaction transaction, AttributionResult attributionResult) {
        float[] weights = weightRegistry.getWeights();
        float threshold = weightRegistry.getThreshold();
        float[] phis = attributionResult.phis();

        // Eq.3: R = sum(omega_i * |phi_i|)
        float R = 0.0f;
        int limit = Math.min(weights.length, phis.length);
        for (int i = 0; i < limit; i++) {
            R += weights[i] * Math.abs(phis[i]);
        }

        boolean flagged = R > threshold;

        // Update transaction status if flagged
        if (flagged) {
            transaction.setStatus(Transaction.Status.FLAGGED);
            transactionRepository.save(transaction);
        } else if (transaction.getStatus() == Transaction.Status.PENDING) {
            transaction.setStatus(Transaction.Status.PROCESSED);
            transactionRepository.save(transaction);
        }

        // Serialize phis to JSON
        String phisJson;
        try {
            phisJson = objectMapper.writeValueAsString(phis);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize phis to JSON: {}", e.getMessage());
            phisJson = "[]";
        }

        // Persist RiskScore
        RiskScore riskScore = new RiskScore();
        riskScore.setTransactionId(transaction.getId());
        riskScore.setRiskScore(R);
        riskScore.setThreshold(threshold);
        riskScore.setFlagged(flagged);
        riskScore.setExplainMethod(attributionResult.method());
        riskScore.setWeightsVersion(weightRegistry.getWeightsVersion());
        riskScore.setPhi0(attributionResult.phi0());
        riskScore.setPhisJson(phisJson);
        RiskScore saved = riskScoreRepository.save(riskScore);

        // Structured log
        log.info("Risk scored: txId={} R={} flagged={} threshold={} method={}",
                transaction.getId(), R, flagged, threshold, attributionResult.method());

        // Fire compliance event
        eventPublisher.publishEvent(new ComplianceFlagEvent(this, transaction.getId(), R, flagged));

        return saved;
    }
}
