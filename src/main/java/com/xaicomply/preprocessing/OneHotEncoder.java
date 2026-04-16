package com.xaicomply.preprocessing;

import com.xaicomply.config.ModelConfig;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * One-hot encodes categorical features.
 * MCC: top 10 categories → float[10]
 * CountryCode: US,GB,DE,FR,CN,OTHER → float[6]
 * isInternational → float[1]
 */
@Component
public class OneHotEncoder {

    private final List<String> mccCategories;
    private final List<String> countryCodes;

    public OneHotEncoder(ModelConfig modelConfig) {
        this.mccCategories = modelConfig.getPreprocessing().getMccCategoriesList();
        this.countryCodes = modelConfig.getPreprocessing().getCountryCodesList();
    }

    /**
     * Encodes MCC to a float[10] one-hot vector.
     */
    public float[] encodeMcc(String mcc) {
        float[] encoded = new float[mccCategories.size()];
        int idx = mccCategories.indexOf(mcc);
        if (idx >= 0) {
            encoded[idx] = 1.0f;
        }
        // unknown MCC = all zeros (no match)
        return encoded;
    }

    /**
     * Encodes country code to a float[6] one-hot vector.
     * Unknown countries map to "OTHER".
     */
    public float[] encodeCountry(String countryCode) {
        float[] encoded = new float[countryCodes.size()];
        int idx = countryCodes.indexOf(countryCode);
        if (idx >= 0) {
            encoded[idx] = 1.0f;
        } else {
            // Map unknown to "OTHER" (last index)
            int otherIdx = countryCodes.indexOf("OTHER");
            if (otherIdx >= 0) {
                encoded[otherIdx] = 1.0f;
            }
        }
        return encoded;
    }

    /**
     * Encodes isInternational to float[1].
     */
    public float[] encodeInternational(Boolean isInternational) {
        return new float[]{(isInternational != null && isInternational) ? 1.0f : 0.0f};
    }

    /**
     * Returns feature names for MCC one-hot.
     */
    public String[] getMccFeatureNames() {
        return mccCategories.stream()
                .map(mcc -> "mcc_" + mcc)
                .toArray(String[]::new);
    }

    /**
     * Returns feature names for country one-hot.
     */
    public String[] getCountryFeatureNames() {
        return countryCodes.stream()
                .map(cc -> "country_" + cc)
                .toArray(String[]::new);
    }
}
