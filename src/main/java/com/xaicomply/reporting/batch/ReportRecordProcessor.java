package com.xaicomply.reporting.batch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xaicomply.domain.RiskScore;
import com.xaicomply.domain.Transaction;
import com.xaicomply.repository.RiskScoreRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Enriches each Transaction with its RiskScore and top 3 feature attributions.
 */
@Component
public class ReportRecordProcessor implements ItemProcessor<Transaction, ReportRecord> {

    private static final Logger log = LoggerFactory.getLogger(ReportRecordProcessor.class);

    private final RiskScoreRepository riskScoreRepository;
    private final ObjectMapper objectMapper;

    public ReportRecordProcessor(RiskScoreRepository riskScoreRepository, ObjectMapper objectMapper) {
        this.riskScoreRepository = riskScoreRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReportRecord process(Transaction transaction) throws Exception {
        Optional<RiskScore> riskScoreOpt = riskScoreRepository
                .findTopByTransactionIdOrderByCreatedAtDesc(transaction.getId());

        if (riskScoreOpt.isEmpty()) {
            log.warn("No risk score found for transaction {}", transaction.getId());
            return new ReportRecord(
                    transaction.getId(),
                    transaction.getCustomerId(),
                    transaction.getAmount(),
                    transaction.getCurrency(),
                    0.0f, false, "UNKNOWN", null, null,
                    transaction.getCreatedAt()
            );
        }

        RiskScore riskScore = riskScoreOpt.get();

        // Parse phisJson to find top feature by absolute value
        String topFeatureName = null;
        Float topFeatureWeight = null;

        if (riskScore.getPhisJson() != null) {
            try {
                float[] phis = objectMapper.readValue(riskScore.getPhisJson(), float[].class);
                int topIdx = 0;
                float maxAbs = 0.0f;
                for (int i = 0; i < phis.length; i++) {
                    float abs = Math.abs(phis[i]);
                    if (abs > maxAbs) {
                        maxAbs = abs;
                        topIdx = i;
                    }
                }
                if (phis.length > 0) {
                    topFeatureName = "feature_" + topIdx;
                    topFeatureWeight = phis[topIdx];
                }
            } catch (Exception e) {
                log.warn("Failed to parse phisJson for tx {}: {}", transaction.getId(), e.getMessage());
            }
        }

        return new ReportRecord(
                transaction.getId(),
                transaction.getCustomerId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                riskScore.getRiskScore(),
                riskScore.isFlagged(),
                riskScore.getExplainMethod(),
                topFeatureName,
                topFeatureWeight,
                transaction.getCreatedAt()
        );
    }
}
