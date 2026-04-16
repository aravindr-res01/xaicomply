package com.xaicomply.common.enums;

/**
 * Identifies which XAI explainer produced a given explanation.
 *
 * SHAP  (SHapley Additive exPlanations) — game-theoretic, consistent, exact for tree models.
 *        Implements Eq. 1: f(x) = φ₀ + Σφᵢ
 *
 * LIME  (Local Interpretable Model-agnostic Explanations) — surrogate linear model
 *        fitted in a locality kernel around the input.
 *        Implements Eq. 2: argmin[ℒ(f,g,πₓ) + Ω(g)]
 */
public enum ExplainerType {
    SHAP("SHAP", "SHapley Additive exPlanations", "TreeExplainer"),
    LIME("LIME", "Local Interpretable Model-agnostic Explanations", "LimeTabularExplainer");

    private final String shortName;
    private final String fullName;
    private final String implementation;

    ExplainerType(String shortName, String fullName, String implementation) {
        this.shortName = shortName;
        this.fullName = fullName;
        this.implementation = implementation;
    }

    public String getShortName()     { return shortName; }
    public String getFullName()      { return fullName; }
    public String getImplementation(){ return implementation; }
}
