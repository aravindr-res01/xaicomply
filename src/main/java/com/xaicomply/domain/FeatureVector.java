package com.xaicomply.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents the preprocessed feature vector for a transaction.
 * float[20]: [amount_z, velocity_z, hour_z, mcc(x10), country(x6), international]
 */
public record FeatureVector(
        UUID transactionId,
        float[] features,
        String[] featureNames,
        Instant processedAt
) {
    public static final int FEATURE_COUNT = 20;
}
