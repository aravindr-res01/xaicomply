package com.xaicomply.reporting.entity;

import com.xaicomply.common.enums.ComplianceStatus;
import com.xaicomply.common.enums.RiskLevel;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * JPA entity representing one transaction's compliance record in the H2 audit log.
 *
 * Maps to table COMPLIANCE_RECORDS.
 * In production this would map to an immutable blockchain ledger or S3 parquet
 * (paper Figure 2 shows "Audit Log Repository" and "Blockchain Ledger").
 *
 * Paper Section 2.3:
 *   "high-volume reports with immutable audit trails"
 */
@Entity
@Table(name = "COMPLIANCE_RECORDS",
       indexes = {
           @Index(name = "idx_transaction_id", columnList = "transactionId"),
           @Index(name = "idx_status",         columnList = "complianceStatus"),
           @Index(name = "idx_flagged",        columnList = "flagged"),
           @Index(name = "idx_created_at",     columnList = "createdAt")
       })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ComplianceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Transaction ID from upstream pipeline */
    @Column(nullable = false, unique = true)
    private String transactionId;

    // ── Risk Scores ───────────────────────────────────────────────────────────

    /** Regulatory risk score R from Eq.3 */
    @Column(nullable = false)
    private Double regulatoryRiskScore;

    /** Raw ONNX model risk score (fraud probability) */
    @Column(nullable = false)
    private Double modelRiskScore;

    /** Threshold τ used for this classification */
    @Column(nullable = false)
    private Double threshold;

    // ── Compliance Decision ───────────────────────────────────────────────────

    @Column(nullable = false)
    private Boolean flagged;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ComplianceStatus complianceStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskLevel riskLevel;

    /** Top risk-driving feature name (from SHAP attributions) */
    private String topRiskFeature;

    /** Regulatory category: AML_STRUCTURING, BEHAVIORAL_ANOMALY, etc. */
    private String regulatoryCategory;

    /** Weight matrix version used (for reproducibility) */
    private String weightMatrixVersion;

    // ── Evaluation fields ─────────────────────────────────────────────────────

    /** Ground-truth label (0=legit, 1=fraud) — for evaluation only */
    private Integer trueLabel;

    /** Whether our classification matched true label */
    private Boolean correctPrediction;

    // ── Audit metadata ────────────────────────────────────────────────────────

    /** When this record was created in the audit log */
    @Column(nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** When the mapping was performed */
    private Instant mappingTimestamp;

    /** Whether this record has been included in a report */
    @Column(nullable = false)
    @Builder.Default
    private Boolean reportGenerated = false;

    /** Batch job ID that generated the report containing this record */
    private Long batchJobId;
}
