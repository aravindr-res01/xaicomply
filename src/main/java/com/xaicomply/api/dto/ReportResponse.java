package com.xaicomply.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Response for compliance report queries.
 */
public record ReportResponse(
        UUID reportId,
        String period,
        String status,
        int flaggedCount,
        int recordCount,
        String filePath,
        String sha256Hash,
        String message,
        Instant createdAt,
        Instant completedAt
) {}
