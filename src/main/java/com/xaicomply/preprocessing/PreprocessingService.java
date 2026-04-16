package com.xaicomply.preprocessing;

import com.xaicomply.domain.FeatureVector;
import com.xaicomply.domain.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates the full preprocessing pipeline:
 * validate → normalize (z-score) → one-hot encode → assemble FeatureVector
 *
 * Final float[20]: [amount_z, velocity_z, hour_z, mcc(x10), country(x6), international]
 */
@Service
public class PreprocessingService {

    private static final Logger log = LoggerFactory.getLogger(PreprocessingService.class);

    private final SchemaValidator schemaValidator;
    private final ZScoreNormalizer zScoreNormalizer;
    private final OneHotEncoder oneHotEncoder;

    public PreprocessingService(SchemaValidator schemaValidator,
                                ZScoreNormalizer zScoreNormalizer,
                                OneHotEncoder oneHotEncoder) {
        this.schemaValidator = schemaValidator;
        this.zScoreNormalizer = zScoreNormalizer;
        this.oneHotEncoder = oneHotEncoder;
    }

    /**
     * Preprocesses a Transaction into a FeatureVector.
     * Updates Welford stats in DB.
     */
    public FeatureVector preprocess(Transaction transaction) {
        schemaValidator.validate(
                transaction.getCustomerId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getMerchantCategoryCode(),
                transaction.getCountryCode(),
                transaction.getTransactionVelocity(),
                transaction.getIsInternational(),
                transaction.getHourOfDay()
        );

        return buildFeatureVector(transaction, true);
    }

    /**
     * Re-preprocesses without updating Welford stats (for explain-only requests).
     */
    public FeatureVector preprocessReadOnly(Transaction transaction) {
        return buildFeatureVector(transaction, false);
    }

    private FeatureVector buildFeatureVector(Transaction transaction, boolean updateStats) {
        // 1. Numeric features (z-score normalized)
        double amountVal = transaction.getAmount().doubleValue();
        double velocityVal = transaction.getTransactionVelocity() != null ? transaction.getTransactionVelocity() : 0;
        double hourVal = transaction.getHourOfDay() != null ? transaction.getHourOfDay() : 12;

        float amountZ, velocityZ, hourZ;
        if (updateStats) {
            amountZ = zScoreNormalizer.normalize("amount", amountVal);
            velocityZ = zScoreNormalizer.normalize("transactionVelocity", velocityVal);
            hourZ = zScoreNormalizer.normalize("hourOfDay", hourVal);
        } else {
            amountZ = zScoreNormalizer.normalizeReadOnly("amount", amountVal);
            velocityZ = zScoreNormalizer.normalizeReadOnly("transactionVelocity", velocityVal);
            hourZ = zScoreNormalizer.normalizeReadOnly("hourOfDay", hourVal);
        }

        // 2. One-hot encode categoricals
        float[] mccEncoded = oneHotEncoder.encodeMcc(transaction.getMerchantCategoryCode());
        float[] countryEncoded = oneHotEncoder.encodeCountry(transaction.getCountryCode());
        float[] internationalEncoded = oneHotEncoder.encodeInternational(transaction.getIsInternational());

        // 3. Assemble feature vector: [amount_z, velocity_z, hour_z, mcc(x10), country(x6), international]
        float[] features = new float[FeatureVector.FEATURE_COUNT];
        features[0] = amountZ;
        features[1] = velocityZ;
        features[2] = hourZ;
        System.arraycopy(mccEncoded, 0, features, 3, mccEncoded.length);            // indices 3-12
        System.arraycopy(countryEncoded, 0, features, 13, countryEncoded.length);   // indices 13-18
        features[19] = internationalEncoded[0];                                       // index 19

        // 4. Build feature names
        List<String> names = new ArrayList<>();
        names.add("amount_z");
        names.add("velocity_z");
        names.add("hour_z");
        for (String n : oneHotEncoder.getMccFeatureNames()) names.add(n);
        for (String n : oneHotEncoder.getCountryFeatureNames()) names.add(n);
        names.add("is_international");

        String[] featureNames = names.toArray(new String[0]);

        log.debug("Preprocessed transaction {} into {} features", transaction.getId(), features.length);
        return new FeatureVector(transaction.getId(), features, featureNames, Instant.now());
    }
}
