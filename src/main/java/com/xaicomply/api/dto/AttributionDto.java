package com.xaicomply.api.dto;

/**
 * Feature attribution detail for API responses.
 */
public record AttributionDto(
        String featureName,
        float phi,
        float absValue,
        int rank
) {}
