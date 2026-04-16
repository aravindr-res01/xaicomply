package com.xaicomply.api.dto;

import jakarta.validation.constraints.*;

/**
 * Request body for triggering compliance report generation.
 */
public record ReportRequest(
        @NotBlank(message = "period is required")
        String period,

        @NotNull(message = "year is required")
        @Min(value = 2000, message = "year must be >= 2000")
        @Max(value = 2100, message = "year must be <= 2100")
        Integer year,

        @NotNull(message = "month is required")
        @Min(value = 1, message = "month must be between 1 and 12")
        @Max(value = 12, message = "month must be between 1 and 12")
        Integer month
) {}
