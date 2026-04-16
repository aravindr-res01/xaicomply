package com.xaicomply.common.enums;

/**
 * Quantitative risk level derived from the raw regulatory risk score R.
 * Maps the continuous score [0,1] to a categorical risk band.
 */
public enum RiskLevel {
    LOW(0.0, 0.3, "Low Risk"),
    MEDIUM(0.3, 0.6, "Medium Risk"),
    HIGH(0.6, 0.85, "High Risk"),
    CRITICAL(0.85, 1.0, "Critical Risk");

    private final double lowerBound;
    private final double upperBound;
    private final String label;

    RiskLevel(double lowerBound, double upperBound, String label) {
        this.lowerBound = lowerBound;
        this.upperBound = upperBound;
        this.label = label;
    }

    public String getLabel() { return label; }
    public double getLowerBound() { return lowerBound; }
    public double getUpperBound() { return upperBound; }

    /**
     * Resolve a risk level from a continuous score.
     * @param score regulatory risk score R ∈ [0, 1]
     * @return corresponding RiskLevel band
     */
    public static RiskLevel fromScore(double score) {
        for (RiskLevel level : values()) {
            if (score >= level.lowerBound && score < level.upperBound) {
                return level;
            }
        }
        return CRITICAL; // score == 1.0 edge case
    }
}
