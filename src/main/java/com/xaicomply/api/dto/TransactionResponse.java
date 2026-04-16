package com.xaicomply.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response for transaction creation and retrieval.
 */
public record TransactionResponse(
        UUID transactionId,
        String customerId,
        BigDecimal amount,
        String currency,
        String merchantCategoryCode,
        String countryCode,
        Integer transactionVelocity,
        Boolean isInternational,
        Integer hourOfDay,
        String status,
        Float modelScore,
        Float riskScore,
        Boolean flagged,
        List<AttributionDto> top5Attributions,
        Long processingTimeMs,
        Instant createdAt
) {}
