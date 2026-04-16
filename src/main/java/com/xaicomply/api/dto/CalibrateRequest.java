package com.xaicomply.api.dto;

import java.util.UUID;

/**
 * A single labeled sample for threshold calibration.
 */
public record CalibrateRequest(
        UUID transactionId,
        boolean trueLabel
) {}
