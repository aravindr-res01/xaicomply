package com.xaicomply.common.enums;

/**
 * Compliance classification levels produced by the Regulatory Mapping Engine.
 *
 * Based on the paper's regulatory-mapping algorithm (Eq. 3):
 *   R = Σ ωᵢ|φᵢ|
 *
 * Thresholds:
 *   R < τ_low   → COMPLIANT   (no action needed)
 *   τ_low ≤ R < τ_high → REVIEW  (human review required)
 *   R ≥ τ_high  → VIOLATION   (immediate escalation)
 */
public enum ComplianceStatus {

    /**
     * Transaction risk score R is below the lower threshold τ_low.
     * No compliance action required.
     */
    COMPLIANT("Compliant", "No compliance risk detected", 0),

    /**
     * Transaction risk score R falls between τ_low and τ_high.
     * Requires human review before clearing.
     */
    REVIEW("Review Required", "Transaction flagged for manual compliance review", 1),

    /**
     * Transaction risk score R exceeds the upper threshold τ_high.
     * Immediate escalation required per EBA/Basel guidelines.
     */
    VIOLATION("Violation", "Compliance violation detected — immediate action required", 2);

    private final String displayName;
    private final String description;
    private final int severityLevel; // 0=low, 1=medium, 2=high

    ComplianceStatus(String displayName, String description, int severityLevel) {
        this.displayName = displayName;
        this.description = description;
        this.severityLevel = severityLevel;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public int getSeverityLevel() { return severityLevel; }

    public boolean requiresAction() {
        return this == REVIEW || this == VIOLATION;
    }

    public boolean isViolation() {
        return this == VIOLATION;
    }
}
