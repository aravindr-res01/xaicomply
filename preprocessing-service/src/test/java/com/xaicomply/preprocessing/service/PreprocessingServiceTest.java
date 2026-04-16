package com.xaicomply.preprocessing.service;

import com.xaicomply.common.dto.ProcessedTransactionDTO;
import com.xaicomply.common.dto.TransactionDTO;
import com.xaicomply.common.exception.XaiComplyException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PreprocessingService.
 *
 * Tests cover:
 *   - Happy path: valid transaction preprocessed correctly
 *   - Z-score normalization accuracy (Amount and Time)
 *   - Feature vector construction (30 elements, correct order)
 *   - Imputation of null V-features
 *   - Validation errors (null amount, negative amount, null time)
 *   - Edge cases (zero amount, very large amount, boundary time)
 *   - Fraud transaction (large amount, anomalous Vs)
 *
 * Paper reference: Section 2.3 preprocessing pipeline
 */
@DisplayName("PreprocessingService Unit Tests")
class PreprocessingServiceTest {

    private PreprocessingService preprocessingService;

    @Mock
    private FeatureScalerService scalerService;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);

        // Default scaler behavior using Kaggle dataset statistics
        when(scalerService.normalizeAmount(anyDouble()))
                .thenAnswer(inv -> (inv.getArgument(0, Double.class) - 88.35) / 250.12);
        when(scalerService.normalizeTime(anyDouble()))
                .thenAnswer(inv -> (inv.getArgument(0, Double.class) - 94813.86) / 47488.15);

        preprocessingService = new PreprocessingService(
                scalerService, new SimpleMeterRegistry());
    }

    @AfterEach
    void tearDown() throws Exception { mocks.close(); }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Valid transaction is preprocessed successfully")
    void preprocess_validTransaction_returnsProcessedDTO() {
        TransactionDTO tx = buildLegitTransaction("txn-001");

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result).isNotNull();
        assertThat(result.getTransactionId()).isEqualTo("txn-001");
        assertThat(result.getOriginalTransaction()).isEqualTo(tx);
        assertThat(result.getFeatureVector()).isNotNull().hasSize(30);
        assertThat(result.getImputedFields()).isEmpty();
        assertThat(result.getPreprocessingTimeMs()).isNotNull().isGreaterThanOrEqualTo(0);
        assertThat(result.getPreprocessedAt()).isNotNull();
        assertThat(result.getSchemaVersion()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("TransactionId is auto-generated when null")
    void preprocess_nullTransactionId_generatesUUID() {
        TransactionDTO tx = buildLegitTransaction(null);
        tx.setTransactionId(null);

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result.getTransactionId())
                .isNotNull()
                .isNotBlank()
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    // ── Z-score normalization ─────────────────────────────────────────────────

    @Test
    @DisplayName("Amount is z-score normalized correctly")
    void preprocess_normalizeAmount_usesScalerService() {
        TransactionDTO tx = buildLegitTransaction("txn-002");
        tx.setAmount(149.62);

        // Expected: (149.62 - 88.35) / 250.12 = 0.2449
        when(scalerService.normalizeAmount(149.62)).thenReturn(0.2449);

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result.getNormalizedAmount()).isCloseTo(0.2449, within(0.0001));
        verify(scalerService, times(1)).normalizeAmount(149.62);
    }

    @Test
    @DisplayName("Time is z-score normalized correctly")
    void preprocess_normalizeTime_usesScalerService() {
        TransactionDTO tx = buildLegitTransaction("txn-003");
        tx.setTime(406.0);

        // Expected: (406 - 94813.86) / 47488.15 = -1.9874
        when(scalerService.normalizeTime(406.0)).thenReturn(-1.9874);

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result.getNormalizedTime()).isCloseTo(-1.9874, within(0.0001));
        verify(scalerService, times(1)).normalizeTime(406.0);
    }

    @Test
    @DisplayName("Zero amount normalizes correctly")
    void preprocess_zeroAmount_normalizedCorrectly() {
        TransactionDTO tx = buildLegitTransaction("txn-004");
        tx.setAmount(0.0);
        when(scalerService.normalizeAmount(0.0)).thenReturn(-88.35 / 250.12);

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result.getNormalizedAmount()).isLessThan(0); // below mean
    }

    // ── Feature vector ────────────────────────────────────────────────────────

    @Test
    @DisplayName("Feature vector has exactly 30 elements")
    void preprocess_featureVector_hasCorrectLength() {
        ProcessedTransactionDTO result = preprocessingService.preprocess(
                buildLegitTransaction("txn-005"));
        assertThat(result.getFeatureVector()).hasSize(30);
    }

    @Test
    @DisplayName("Feature vector first element is normalizedTime")
    void preprocess_featureVector_firstElementIsNormalizedTime() {
        TransactionDTO tx = buildLegitTransaction("txn-006");
        when(scalerService.normalizeTime(anyDouble())).thenReturn(-1.987);

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result.getFeatureVector()[0]).isCloseTo(-1.987f, within(0.001f));
    }

    @Test
    @DisplayName("Feature vector last element is normalizedAmount")
    void preprocess_featureVector_lastElementIsNormalizedAmount() {
        TransactionDTO tx = buildLegitTransaction("txn-007");
        when(scalerService.normalizeAmount(anyDouble())).thenReturn(0.244);

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result.getFeatureVector()[29]).isCloseTo(0.244f, within(0.001f));
    }

    @Test
    @DisplayName("Feature vector V14 is at correct index (14)")
    void preprocess_featureVector_v14AtCorrectIndex() {
        TransactionDTO tx = buildLegitTransaction("txn-008");
        tx.setV14(-7.25);  // strong fraud signal

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        // Index 14: [Time(0), V1(1), V2(2), ..., V14(14)]
        assertThat(result.getFeatureVector()[14]).isCloseTo(-7.25f, within(0.001f));
    }

    @Test
    @DisplayName("Feature names match expected order")
    void preprocess_featureNames_correctOrder() {
        ProcessedTransactionDTO result = preprocessingService.preprocess(
                buildLegitTransaction("txn-009"));

        assertThat(result.getFeatureNames())
                .isNotNull()
                .hasSize(30)
                .startsWith("Time", "V1", "V2")
                .endsWith("V28", "Amount");
    }

    // ── Imputation ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Null V-features are imputed with 0.0")
    void preprocess_nullVFeatures_imputedWithZero() {
        TransactionDTO tx = buildLegitTransaction("txn-010");
        tx.setV1(null);
        tx.setV14(null);
        tx.setV28(null);

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result.getImputedFields())
                .containsExactlyInAnyOrder("V1", "V14", "V28");
        // Imputed values should be 0.0 in feature vector
        assertThat(result.getFeatureVector()[1]).isEqualTo(0.0f);  // V1
        assertThat(result.getFeatureVector()[14]).isEqualTo(0.0f); // V14
        assertThat(result.getFeatureVector()[28]).isEqualTo(0.0f); // V28
    }

    @Test
    @DisplayName("All V-features null results in 28 imputations")
    void preprocess_allVFeaturesNull_imputesAll28() {
        TransactionDTO tx = buildTransactionWithNullVFeatures("txn-011");

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result.getImputedFields()).hasSize(28);
    }

    @Test
    @DisplayName("No imputation needed for complete transaction")
    void preprocess_completeTransaction_noImputation() {
        ProcessedTransactionDTO result = preprocessingService.preprocess(
                buildLegitTransaction("txn-012"));

        assertThat(result.getImputedFields()).isEmpty();
    }

    // ── Validation errors ─────────────────────────────────────────────────────

    @Test
    @DisplayName("Null amount throws PreprocessingException")
    void preprocess_nullAmount_throwsException() {
        TransactionDTO tx = buildLegitTransaction("txn-013");
        tx.setAmount(null);

        assertThatThrownBy(() -> preprocessingService.preprocess(tx))
                .isInstanceOf(XaiComplyException.PreprocessingException.class)
                .hasMessageContaining("Amount");
    }

    @Test
    @DisplayName("Negative amount throws PreprocessingException")
    void preprocess_negativeAmount_throwsException() {
        TransactionDTO tx = buildLegitTransaction("txn-014");
        tx.setAmount(-50.0);

        assertThatThrownBy(() -> preprocessingService.preprocess(tx))
                .isInstanceOf(XaiComplyException.PreprocessingException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("Null time throws PreprocessingException")
    void preprocess_nullTime_throwsException() {
        TransactionDTO tx = buildLegitTransaction("txn-015");
        tx.setTime(null);

        assertThatThrownBy(() -> preprocessingService.preprocess(tx))
                .isInstanceOf(XaiComplyException.PreprocessingException.class)
                .hasMessageContaining("Time");
    }

    @Test
    @DisplayName("Negative time throws PreprocessingException")
    void preprocess_negativeTime_throwsException() {
        TransactionDTO tx = buildLegitTransaction("txn-016");
        tx.setTime(-1.0);

        assertThatThrownBy(() -> preprocessingService.preprocess(tx))
                .isInstanceOf(XaiComplyException.PreprocessingException.class)
                .hasMessageContaining("negative");
    }

    // ── Paper-specific test cases ─────────────────────────────────────────────

    @Test
    @DisplayName("Kaggle dataset row 1 preprocesses correctly (paper benchmark)")
    void preprocess_kaggleRow1_correctNormalization() {
        // First row of Kaggle creditcard.csv
        TransactionDTO tx = TransactionDTO.builder()
                .transactionId("kaggle-row-1")
                .time(0.0)
                .v1(-1.3598071336738).v2(-0.0727811733098497).v3(2.53634673796914)
                .v4(1.37815522427443).v5(-0.338320769942518).v6(0.462387777762292)
                .v7(0.239598554061257).v8(0.0986979012610507).v9(0.363786969611213)
                .v10(0.0907941719789316).v11(-0.551599533260813).v12(-0.617800855762348)
                .v13(-0.991389847235408).v14(-0.311169353699879).v15(1.46817697209427)
                .v16(-0.470400525259478).v17(0.207971241929242).v18(0.0257905801985591)
                .v19(0.403992960255733).v20(0.251412098239705).v21(-0.018306777944153)
                .v22(0.277837575558899).v23(-0.110473910188767).v24(0.0669280749146731)
                .v25(0.128539358273528).v26(-0.189114843888824).v27(0.133558376740387)
                .v28(-0.0210530534538215).amount(149.62).trueLabel(0)
                .build();

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result).isNotNull();
        assertThat(result.getFeatureVector()).hasSize(30);
        assertThat(result.getImputedFields()).isEmpty();
        // V1 should pass through unchanged (PCA already scaled)
        assertThat(result.getFeatureVector()[1]).isCloseTo(-1.3598f, within(0.0001f));
        // V14 should pass through unchanged
        assertThat(result.getFeatureVector()[14]).isCloseTo(-0.3112f, within(0.0001f));
    }

    @Test
    @DisplayName("High-risk fraud transaction preprocesses without error")
    void preprocess_fraudTransaction_preprocessesSuccessfully() {
        // Typical fraud pattern: very negative V14, high amount
        TransactionDTO tx = buildFraudTransaction("fraud-001");

        ProcessedTransactionDTO result = preprocessingService.preprocess(tx);

        assertThat(result).isNotNull();
        assertThat(result.getFeatureVector()).hasSize(30);
        // V14 = -7.25 (strong fraud signal) should be in vector unchanged
        assertThat(result.getFeatureVector()[14]).isCloseTo(-7.25f, within(0.001f));
    }

    @ParameterizedTest
    @ValueSource(doubles = {0.01, 1.0, 100.0, 999.99, 25691.16})
    @DisplayName("Various valid amounts are processed without error")
    void preprocess_variousAmounts_allSucceed(double amount) {
        TransactionDTO tx = buildLegitTransaction("txn-amount-" + amount);
        tx.setAmount(amount);

        assertThatCode(() -> preprocessingService.preprocess(tx))
                .doesNotThrowAnyException();
    }

    // ── Builders ──────────────────────────────────────────────────────────────

    private TransactionDTO buildLegitTransaction(String txId) {
        return TransactionDTO.builder()
                .transactionId(txId)
                .time(406.0)
                .v1(-1.36).v2(-0.07).v3(2.54).v4(1.38).v5(-0.34)
                .v6(0.46).v7(0.24).v8(0.10).v9(0.36).v10(0.09)
                .v11(-0.55).v12(-0.62).v13(-0.99).v14(-0.31).v15(1.47)
                .v16(-0.47).v17(0.21).v18(0.03).v19(0.40).v20(0.25)
                .v21(-0.02).v22(0.28).v23(-0.11).v24(0.07).v25(0.13)
                .v26(-0.19).v27(0.13).v28(-0.02).amount(149.62).trueLabel(0)
                .build();
    }

    private TransactionDTO buildFraudTransaction(String txId) {
        return TransactionDTO.builder()
                .transactionId(txId)
                .time(100.0)
                .v1(-3.04).v2(-3.16).v3(-1.29).v4(3.97).v5(-0.46)
                .v6(-1.56).v7(-1.55).v8(0.06).v9(-2.02).v10(-2.30)
                .v11(2.50).v12(-4.97).v13(1.80).v14(-7.25).v15(0.20)
                .v16(-3.22).v17(-4.89).v18(-2.05).v19(0.26).v20(0.09)
                .v21(0.27).v22(-0.72).v23(0.24).v24(0.29).v25(-0.07)
                .v26(-0.33).v27(0.07).v28(0.04).amount(2125.87).trueLabel(1)
                .build();
    }

    private TransactionDTO buildTransactionWithNullVFeatures(String txId) {
        TransactionDTO tx = buildLegitTransaction(txId);
        tx.setV1(null);  tx.setV2(null);  tx.setV3(null);  tx.setV4(null);
        tx.setV5(null);  tx.setV6(null);  tx.setV7(null);  tx.setV8(null);
        tx.setV9(null);  tx.setV10(null); tx.setV11(null); tx.setV12(null);
        tx.setV13(null); tx.setV14(null); tx.setV15(null); tx.setV16(null);
        tx.setV17(null); tx.setV18(null); tx.setV19(null); tx.setV20(null);
        tx.setV21(null); tx.setV22(null); tx.setV23(null); tx.setV24(null);
        tx.setV25(null); tx.setV26(null); tx.setV27(null); tx.setV28(null);
        return tx;
    }
}
