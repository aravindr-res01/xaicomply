package com.xaicomply.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response for transaction explanation requests.
 */
public record ExplainResponse(
        UUID transactionId,
        String method,
        float modelScore,
        float phi0,
        float riskScore,
        boolean flagged,
        float threshold,
        List<AttributionDto> attributions,
        String interpretation
) {}
