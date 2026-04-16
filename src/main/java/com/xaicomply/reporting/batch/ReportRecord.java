package com.xaicomply.reporting.batch;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Enriched record for compliance report generation.
 * Contains transaction data combined with risk score and top attribution.
 */
public record ReportRecord(
        UUID transactionId,
        String customerId,
        BigDecimal amount,
        String currency,
        float riskScore,
        boolean flagged,
        String explainMethod,
        String topFeatureName,
        Float topFeatureWeight,
        Instant createdAt
) {}
