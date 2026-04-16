package com.xaicomply;

import com.xaicomply.domain.NormalizerStats;
import com.xaicomply.preprocessing.ZScoreNormalizer;
import com.xaicomply.repository.NormalizerStatsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ZScoreNormalizerTest {

    private NormalizerStatsRepository statsRepository;
    private ZScoreNormalizer normalizer;

    @BeforeEach
    void setUp() {
        statsRepository = mock(NormalizerStatsRepository.class);
        when(statsRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        normalizer = new ZScoreNormalizer(statsRepository);
    }

    @Test
    void feedKnownValues_shouldProduceNormalizedOutputNearZeroMeanAndUnitStd() {
        // Use a known distribution: mean=50, std=10 (100 values)
        // Initialize with known stats to avoid cold-start defaults
        NormalizerStats existing = new NormalizerStats("testFeature", 30, 50.0, 10.0 * 10.0 * 29);
        when(statsRepository.findById("testFeature")).thenReturn(Optional.of(existing));
        when(statsRepository.save(any(NormalizerStats.class))).thenAnswer(inv -> inv.getArgument(0));

        float[] results = new float[100];
        double[] inputValues = new double[100];

        // Generate 100 values with known mean=50, std=10
        for (int i = 0; i < 100; i++) {
            inputValues[i] = 50.0 + (i - 50); // -50 to +49 around mean 50
        }

        for (int i = 0; i < 100; i++) {
            // For this test, use normalizeReadOnly which uses persisted stats
            results[i] = normalizer.normalizeReadOnly("testFeature", inputValues[i]);
        }

        // Mean of normalized values should be ≈ 0
        double mean = 0;
        for (float v : results) mean += v;
        mean /= results.length;

        // Std of normalized values should be ≈ 1
        double variance = 0;
        for (float v : results) variance += (v - mean) * (v - mean);
        variance /= results.length;
        double std = Math.sqrt(variance);

        assertThat(Math.abs(mean)).as("Normalized mean should be ≈ 0").isLessThan(0.1);
        assertThat(Math.abs(std - 1.0)).as("Normalized std should be ≈ 1").isLessThan(0.1);
    }

    @Test
    void normalize_withDefaultsOnColdStart_shouldReturnReasonableValue() {
        // On first call with no stats, should use defaults
        when(statsRepository.findById("amount")).thenReturn(Optional.empty());
        when(statsRepository.save(any(NormalizerStats.class))).thenAnswer(inv -> inv.getArgument(0));

        // amount=500 with default mean=500, std=300 → z≈0
        float result = normalizer.normalizeReadOnly("amount", 500.0);

        // z-score of 500 with mean=500,std=300 = 0
        assertThat(result).isCloseTo(0.0f, org.assertj.core.data.Offset.offset(0.01f));
    }

    @Test
    void normalize_highAmount_shouldProducePositiveZScore() {
        NormalizerStats existing = new NormalizerStats("amount", 30, 500.0, 300.0 * 300.0 * 29);
        when(statsRepository.findById("amount")).thenReturn(Optional.of(existing));
        when(statsRepository.save(any(NormalizerStats.class))).thenAnswer(inv -> inv.getArgument(0));

        // amount=800, mean=500, std=300 → z = (800-500)/300 = 1.0
        float result = normalizer.normalizeReadOnly("amount", 800.0);
        assertThat(result).isCloseTo(1.0f, org.assertj.core.data.Offset.offset(0.05f));
    }
}
