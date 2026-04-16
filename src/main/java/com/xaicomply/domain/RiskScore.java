package com.xaicomply.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "risk_scores")
public class RiskScore {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID transactionId;

    @Column(nullable = false)
    private float riskScore;

    @Column(nullable = false)
    private float threshold;

    @Column(nullable = false)
    private boolean flagged;

    @Column(nullable = false)
    private String explainMethod;

    @Column
    private String weightsVersion;

    @Column(nullable = false)
    private float phi0;

    @Column(columnDefinition = "TEXT")
    private String phisJson;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public RiskScore() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getTransactionId() { return transactionId; }
    public void setTransactionId(UUID transactionId) { this.transactionId = transactionId; }

    public float getRiskScore() { return riskScore; }
    public void setRiskScore(float riskScore) { this.riskScore = riskScore; }

    public float getThreshold() { return threshold; }
    public void setThreshold(float threshold) { this.threshold = threshold; }

    public boolean isFlagged() { return flagged; }
    public void setFlagged(boolean flagged) { this.flagged = flagged; }

    public String getExplainMethod() { return explainMethod; }
    public void setExplainMethod(String explainMethod) { this.explainMethod = explainMethod; }

    public String getWeightsVersion() { return weightsVersion; }
    public void setWeightsVersion(String weightsVersion) { this.weightsVersion = weightsVersion; }

    public float getPhi0() { return phi0; }
    public void setPhi0(float phi0) { this.phi0 = phi0; }

    public String getPhisJson() { return phisJson; }
    public void setPhisJson(String phisJson) { this.phisJson = phisJson; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
