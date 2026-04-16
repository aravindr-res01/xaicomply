"""
main.py — XAI-Comply Python Explainer Sidecar
=============================================
FastAPI service providing SHAP and LIME explanations for the Java
Inference & XAI Service.

Endpoints:
  POST /explain/shap  — SHAP TreeExplainer (Eq. 1 in paper)
  POST /explain/lime  — LIME Tabular       (Eq. 2 in paper)
  GET  /health        — health + model status check
  GET  /model/info    — model metadata for audit

Paper reference:
  "SHAP4J (Java) and a Dockerized Python LIME service, XAI-Comply allows
   comparing explanation latency, fidelity, and stability in production."
   (Section 2.1)

Start with: uvicorn main:app --host 0.0.0.0 --port 8085 --reload
"""

import json
import logging
import os
import time
from pathlib import Path
from typing import Dict, List, Optional

import joblib
import numpy as np
import shap
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from lime.lime_tabular import LimeTabularExplainer
from pydantic import BaseModel

# ── Logging setup ─────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("xai-explainer")

# ── Paths ─────────────────────────────────────────────────────────────────────
MODEL_DIR = Path(__file__).parent / "model"

# ── FastAPI app ───────────────────────────────────────────────────────────────
app = FastAPI(
    title="XAI-Comply Python Explainer",
    description="SHAP + LIME explanation endpoints for FinTech compliance pipeline",
    version="1.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# ── Global model state ────────────────────────────────────────────────────────
# Loaded once on startup, reused for all requests
_model = None
_shap_explainer = None
_lime_explainer = None
_feature_names: List[str] = []
_lime_background: Optional[np.ndarray] = None
_model_metadata: dict = {}


# ── Pydantic schemas ──────────────────────────────────────────────────────────

class ExplainRequest(BaseModel):
    transaction_id: str
    features: List[float]          # [Time, V1..V28, Amount] — 30 values
    feature_names: Optional[List[str]] = None

class FeatureAttribution(BaseModel):
    feature_name: str
    attribution: float

class ExplanationResponse(BaseModel):
    transaction_id: str
    explainer_type: str            # "SHAP" or "LIME"
    feature_attributions: Dict[str, float]
    base_value: float
    predicted_value: float
    top_features: List[str]        # top 5 by |attribution|
    computation_time_ms: float
    sample_count: int


# ── Startup: load model and initialize explainers ─────────────────────────────

@app.on_event("startup")
async def startup_event():
    """
    Load the sklearn model and initialize SHAP + LIME explainers.
    Called once when the FastAPI server starts.
    """
    global _model, _shap_explainer, _lime_explainer
    global _feature_names, _lime_background, _model_metadata

    log.info("╔══════════════════════════════════════════════════════╗")
    log.info("║     XAI-Comply Python Explainer — Starting Up       ║")
    log.info("╚══════════════════════════════════════════════════════╝")

    # ── Load sklearn model ────────────────────────────────────────────────────
    model_path = MODEL_DIR / "fraud_model.joblib"
    if not model_path.exists():
        log.error("Model not found at %s", model_path)
        log.error("Run train_model.py first: python train_model.py --data creditcard.csv")
        raise RuntimeError(f"Model not found: {model_path}")

    log.info("Loading sklearn model from: %s", model_path)
    _model = joblib.load(model_path)
    log.info("Model loaded: %s", type(_model).__name__)

    # ── Load feature names ────────────────────────────────────────────────────
    feature_path = MODEL_DIR / "feature_names.json"
    if feature_path.exists():
        with open(feature_path) as f:
            data = json.load(f)
        _feature_names = data["feature_names"]
        log.info("Feature names loaded: %d features", len(_feature_names))
    else:
        _feature_names = ["Time"] + [f"V{i}" for i in range(1, 29)] + ["Amount"]
        log.warning("feature_names.json not found — using default names")

    # ── Load model metadata ───────────────────────────────────────────────────
    meta_path = MODEL_DIR / "model_metadata.json"
    if meta_path.exists():
        with open(meta_path) as f:
            _model_metadata = json.load(f)
        log.info("Model metadata loaded: AUC=%.4f, F1=%.4f",
                 _model_metadata.get("auc_roc", 0), _model_metadata.get("f1_score", 0))

    # ── Load LIME background data ─────────────────────────────────────────────
    bg_path = MODEL_DIR / "lime_background.npy"
    if bg_path.exists():
        _lime_background = np.load(str(bg_path))
        log.info("LIME background data loaded: %s samples", _lime_background.shape[0])
    else:
        log.warning("lime_background.npy not found — LIME will use smaller background")
        _lime_background = np.zeros((100, len(_feature_names)))

    # ── Initialize SHAP TreeExplainer ─────────────────────────────────────────
    # Paper Eq. 1: f(x) = φ₀ + Σφᵢ
    # TreeExplainer achieves exact Shapley values in O(T·D·n) time
    log.info("Initializing SHAP TreeExplainer...")
    start = time.time()
    _shap_explainer = shap.TreeExplainer(
        _model,
        data=shap.sample(_lime_background, 100),   # background sample for φ₀
        feature_perturbation="interventional",
    )
    log.info("SHAP TreeExplainer ready in %.2fs", time.time() - start)

    # ── Initialize LIME Tabular Explainer ─────────────────────────────────────
    # Paper Eq. 2: argmin[ℒ(f,g,πₓ) + Ω(g)]
    log.info("Initializing LIME LimeTabularExplainer...")
    start = time.time()
    _lime_explainer = LimeTabularExplainer(
        training_data=_lime_background,
        feature_names=_feature_names,
        class_names=["Legitimate", "Fraud"],
        mode="classification",
        discretize_continuous=True,
        random_state=42,
    )
    log.info("LIME LimeTabularExplainer ready in %.2fs", time.time() - start)

    log.info("✅ All explainers initialized. Ready to serve requests.")
    log.info("   SHAP endpoint: POST /explain/shap")
    log.info("   LIME endpoint: POST /explain/lime")
    log.info("   Health check:  GET  /health")


# ── Health endpoint ───────────────────────────────────────────────────────────

@app.get("/health")
async def health():
    """
    Health check endpoint. Called by Java services on startup to verify sidecar is ready.
    """
    model_ready = _model is not None
    shap_ready  = _shap_explainer is not None
    lime_ready  = _lime_explainer is not None
    all_ready   = model_ready and shap_ready and lime_ready

    status = "UP" if all_ready else "DEGRADED"
    log.debug("Health check — status: %s", status)

    return {
        "status": status,
        "service": "xai-comply-python-explainer",
        "model_loaded": model_ready,
        "shap_ready":   shap_ready,
        "lime_ready":   lime_ready,
        "feature_count": len(_feature_names),
        "model_type": type(_model).__name__ if _model else None,
    }


@app.get("/model/info")
async def model_info():
    """Return model metadata for audit trail (paper Section 2.4)."""
    return _model_metadata


# ── SHAP explanation endpoint ─────────────────────────────────────────────────

@app.post("/explain/shap", response_model=ExplanationResponse)
async def explain_shap(request: ExplainRequest):
    """
    Compute SHAP attributions for a single transaction.

    Implements Eq. 1 (paper):
      f(x) = φ₀ + Σᵢφᵢ
      where φ₀ = base value (expected output over background data)
            φᵢ = Shapley value for feature i

    Uses TreeExplainer for exact attributions on RandomForest.
    Target: sub-200ms (paper Section 3.5: "≈15ms for SHAP at 100 txn/s").
    """
    if _shap_explainer is None:
        log.error("SHAP explainer not initialized — model not loaded")
        raise HTTPException(status_code=503, detail="SHAP explainer not ready")

    log.info("[SHAP] Request for transaction_id=%s", request.transaction_id)
    log.debug("[SHAP] Feature vector length: %d", len(request.features))

    if len(request.features) != len(_feature_names):
        log.error("[SHAP] Feature count mismatch: got %d, expected %d",
                  len(request.features), len(_feature_names))
        raise HTTPException(
            status_code=400,
            detail=f"Expected {len(_feature_names)} features, got {len(request.features)}"
        )

    start_time = time.time()

    # Reshape to 2D array [1, n_features] for SHAP
    X = np.array(request.features, dtype=np.float32).reshape(1, -1)
    log.debug("[SHAP] Input array shape: %s", X.shape)

    # Compute SHAP values
    # shap_values shape for binary RF: (n_samples, n_features, n_classes)
    # We want class 1 (fraud): shap_values[:, :, 1]
    log.debug("[SHAP] Computing Shapley values...")
    shap_output = _shap_explainer(X)

    # Handle different SHAP output formats
    if hasattr(shap_output, 'values'):
        values = shap_output.values
        base_values = shap_output.base_values
    else:
        values = _shap_explainer.shap_values(X)
        base_values = _shap_explainer.expected_value

    # For binary RF: values may be [2, n_samples, n_features] or [n_samples, n_features, 2]
    if isinstance(values, list):
        # Old-style SHAP: list of arrays per class
        shap_vals = values[1][0]    # class 1 (fraud), first sample
        base_val  = float(base_values[1]) if hasattr(base_values, '__len__') else float(base_values)
    elif values.ndim == 3:
        shap_vals = values[0, :, 1]  # [sample=0, all_features, class=1]
        base_val  = float(base_values[0, 1]) if base_values.ndim > 1 else float(base_values[0])
    elif values.ndim == 2:
        shap_vals = values[0]
        base_val  = float(base_values[0]) if hasattr(base_values, '__len__') else float(base_values)
    else:
        shap_vals = values.flatten()
        base_val  = float(base_values) if not hasattr(base_values, '__len__') else float(base_values[0])

    # Get prediction for this sample
    pred_proba = _model.predict_proba(X)[0][1]  # fraud probability

    # Build attribution dict
    attributions = {
        name: float(val)
        for name, val in zip(_feature_names, shap_vals)
    }

    # Top 5 features by |attribution|
    top_features = sorted(attributions, key=lambda k: abs(attributions[k]), reverse=True)[:5]

    elapsed_ms = (time.time() - start_time) * 1000

    log.info("[SHAP] transaction_id=%s | fraud_prob=%.4f | base=%.4f | "
             "top_feature=%s (%.4f) | time=%.1fms",
             request.transaction_id, pred_proba, base_val,
             top_features[0], attributions[top_features[0]], elapsed_ms)
    log.debug("[SHAP] All attributions: %s",
              {k: f"{v:.4f}" for k, v in sorted(attributions.items(),
               key=lambda x: abs(x[1]), reverse=True)[:10]})

    return ExplanationResponse(
        transaction_id=request.transaction_id,
        explainer_type="SHAP",
        feature_attributions=attributions,
        base_value=base_val,
        predicted_value=float(pred_proba),
        top_features=top_features,
        computation_time_ms=round(elapsed_ms, 2),
        sample_count=100,   # background sample count for TreeExplainer
    )


# ── LIME explanation endpoint ─────────────────────────────────────────────────

@app.post("/explain/lime", response_model=ExplanationResponse)
async def explain_lime(request: ExplainRequest):
    """
    Compute LIME attributions for a single transaction.

    Implements Eq. 2 (paper):
      argmin_{g∈G} [ℒ(f, g, πₓ) + Ω(g)]
      where g = sparse surrogate linear model (max 10 features)
            πₓ = locality kernel πₓ(z') = exp(-D(x,z')²/σ²)
            Ω  = complexity penalty (L1 regularization)

    Generates 500 perturbed samples around the input (paper Section 2.4).
    Target: sub-200ms (paper Section 3.5: "≈30ms for LIME at 100 txn/s").
    """
    if _lime_explainer is None:
        log.error("LIME explainer not initialized — model not loaded")
        raise HTTPException(status_code=503, detail="LIME explainer not ready")

    log.info("[LIME] Request for transaction_id=%s", request.transaction_id)
    log.debug("[LIME] Feature vector length: %d", len(request.features))

    if len(request.features) != len(_feature_names):
        log.error("[LIME] Feature count mismatch: got %d, expected %d",
                  len(request.features), len(_feature_names))
        raise HTTPException(
            status_code=400,
            detail=f"Expected {len(_feature_names)} features, got {len(request.features)}"
        )

    start_time = time.time()

    X_instance = np.array(request.features, dtype=np.float64)
    log.debug("[LIME] Instance shape: %s", X_instance.shape)

    # LIME explanation — 500 perturbation samples (paper Section 2.4)
    log.debug("[LIME] Generating 500 perturbation samples...")
    explanation = _lime_explainer.explain_instance(
        data_row=X_instance,
        predict_fn=_model.predict_proba,
        num_features=10,           # sparse: top 10 features in surrogate model
        num_samples=500,           # paper: "500 perturbed samples per transaction"
        labels=(1,),               # explain fraud class (class 1)
    )

    # Extract attributions for class 1 (fraud)
    lime_attributions_raw = dict(explanation.as_list(label=1))

    # Map LIME's discretized feature names back to original feature names
    # LIME may return "V14 > 0.50" style names — we normalize to feature names
    attributions = {}
    for feat_name, weight in lime_attributions_raw.items():
        # Extract base feature name (e.g., "V14 > 0.5" → "V14")
        base_name = feat_name.split(" ")[0].split(">")[0].split("<")[0].strip()
        # Find matching original feature name
        matched = next((f for f in _feature_names if f.lower() == base_name.lower()), base_name)
        attributions[matched] = float(weight)

    # Fill missing features with 0 attribution
    for name in _feature_names:
        if name not in attributions:
            attributions[name] = 0.0

    # Prediction for this instance
    pred_proba = _model.predict_proba(X_instance.reshape(1, -1))[0][1]

    # Intercept = LIME's base value (surrogate model intercept)
    base_val = float(explanation.intercept[1]) if hasattr(explanation, 'intercept') else 0.0

    # Top 5 features by |attribution|
    top_features = sorted(attributions, key=lambda k: abs(attributions[k]), reverse=True)[:5]

    elapsed_ms = (time.time() - start_time) * 1000

    log.info("[LIME] transaction_id=%s | fraud_prob=%.4f | intercept=%.4f | "
             "top_feature=%s (%.4f) | time=%.1fms",
             request.transaction_id, pred_proba, base_val,
             top_features[0] if top_features else "N/A",
             attributions.get(top_features[0], 0) if top_features else 0,
             elapsed_ms)

    return ExplanationResponse(
        transaction_id=request.transaction_id,
        explainer_type="LIME",
        feature_attributions=attributions,
        base_value=base_val,
        predicted_value=float(pred_proba),
        top_features=top_features,
        computation_time_ms=round(elapsed_ms, 2),
        sample_count=500,
    )


# ── Batch explain endpoint (optional optimization) ────────────────────────────

@app.post("/explain/shap/batch")
async def explain_shap_batch(requests: List[ExplainRequest]):
    """
    Batch SHAP explanations for multiple transactions.
    Paper Section 2.4: "requests within 10ms are batched into a single ONNX or LIME call,
    amortizing computation and I/O overhead."
    """
    log.info("[SHAP-BATCH] Processing batch of %d transactions", len(requests))
    results = []
    for req in requests:
        result = await explain_shap(req)
        results.append(result)
    log.info("[SHAP-BATCH] Completed batch of %d explanations", len(results))
    return results


# ── Run directly ──────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import uvicorn
    log.info("Starting XAI-Comply Python Explainer on port 8085")
    uvicorn.run(
        "main:app",
        host="0.0.0.0",
        port=8085,
        reload=True,
        log_level="info",
    )
