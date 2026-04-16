package com.xaicomply.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request to update regulatory mapping weights.
 */
public record WeightsRequest(
        @NotNull(message = "weights is required")
        @Size(min = 20, max = 20, message = "weights must have exactly 20 values")
        float[] weights,

        @NotNull(message = "threshold is required")
        Double threshold
) {}
