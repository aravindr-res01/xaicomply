package com.xaicomply.preprocessing.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for FeatureScalerService z-score normalization.
 * Uses Kaggle dataset statistics as reference values.
 * Paper Section 2.3: "scales numerical features via z-score scaling"
 */
@DisplayName("FeatureScalerService Unit Tests")
class FeatureScalerServiceTest {

    private FeatureScalerService scalerService;

    @BeforeEach
    void setUp() {
        scalerService = new FeatureScalerService();
        // Inject known stats directly (bypasses file loading)
        ReflectionTestUtils.setField(scalerService, "amountMean", 88.35);
        ReflectionTestUtils.setField(scalerService, "amountStd",  250.12);
        ReflectionTestUtils.setField(scalerService, "timeMean",   94813.86);
        ReflectionTestUtils.setField(scalerService, "timeStd",    47488.15);
        ReflectionTestUtils.setField(scalerService, "statsLoaded", true);
    }

    @Test
    @DisplayName("Amount at mean normalizes to 0.0")
    void normalizeAmount_atMean_returnsZero() {
        double result = scalerService.normalizeAmount(88.35);
        assertThat(result).isCloseTo(0.0, within(0.0001));
    }

    @Test
    @DisplayName("Amount one std above mean normalizes to 1.0")
    void normalizeAmount_oneStdAboveMean_returnsOne() {
        double result = scalerService.normalizeAmount(88.35 + 250.12);
        assertThat(result).isCloseTo(1.0, within(0.0001));
    }

    @Test
    @DisplayName("Amount one std below mean normalizes to -1.0")
    void normalizeAmount_oneStdBelowMean_returnsNegativeOne() {
        double result = scalerService.normalizeAmount(88.35 - 250.12);
        assertThat(result).isCloseTo(-1.0, within(0.0001));
    }

    @ParameterizedTest(name = "amount={0} → expected={1}")
    @CsvSource({
        "0.0,    -0.3532",
        "149.62,  0.2449",
        "2125.87, 8.1468",
        "88.35,   0.0000"
    })
    @DisplayName("Amount normalization matches expected values")
    void normalizeAmount_knownValues_matchExpected(double amount, double expected) {
        double result = scalerService.normalizeAmount(amount);
        assertThat(result).isCloseTo(expected, within(0.001));
    }

    @Test
    @DisplayName("Time at mean normalizes to 0.0")
    void normalizeTime_atMean_returnsZero() {
        double result = scalerService.normalizeTime(94813.86);
        assertThat(result).isCloseTo(0.0, within(0.0001));
    }

    @ParameterizedTest(name = "time={0} → expected={1}")
    @CsvSource({
        "0.0,      -1.9966",
        "406.0,    -1.9881",
        "94813.86,  0.0000",
        "172792.0,  1.6420"
    })
    @DisplayName("Time normalization matches expected values")
    void normalizeTime_knownValues_matchExpected(double time, double expected) {
        double result = scalerService.normalizeTime(time);
        assertThat(result).isCloseTo(expected, within(0.001));
    }

    @Test
    @DisplayName("Normalization formula is reversible (x_norm * std + mean = x)")
    void normalizeAmount_isReversible() {
        double original = 523.75;
        double normalized = scalerService.normalizeAmount(original);
        double recovered = normalized * 250.12 + 88.35;
        assertThat(recovered).isCloseTo(original, within(0.01));
    }
}
