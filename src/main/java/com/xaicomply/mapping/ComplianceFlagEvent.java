package com.xaicomply.mapping;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

/**
 * Spring ApplicationEvent fired when a transaction is flagged as high-risk.
 */
public class ComplianceFlagEvent extends ApplicationEvent {

    private final UUID transactionId;
    private final float riskScore;
    private final boolean flagged;

    public ComplianceFlagEvent(Object source, UUID transactionId, float riskScore, boolean flagged) {
        super(source);
        this.transactionId = transactionId;
        this.riskScore = riskScore;
        this.flagged = flagged;
    }

    public UUID getTransactionId() { return transactionId; }
    public float getRiskScore() { return riskScore; }
    public boolean isFlagged() { return flagged; }
}
