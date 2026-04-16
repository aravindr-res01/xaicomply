package com.xaicomply.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Request to explain a transaction using SHAP or LIME.
 */
public record ExplainRequest(
        @NotBlank(message = "method is required")
        @Pattern(regexp = "SHAP|LIME", message = "method must be SHAP or LIME")
        String method
) {}
