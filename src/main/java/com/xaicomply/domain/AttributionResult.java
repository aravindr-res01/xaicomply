package com.xaicomply.domain;

import java.time.Instant;

/**
 * Result of SHAP or LIME explanation.
 * Satisfies Eq.1: f(x) = phi0 + sum(phi_i)
 */
public record AttributionResult(
        String method,
        float phi0,
        float[] phis,
        String[] featureNames,
        float modelScore,
        Instant computedAt
) {}
