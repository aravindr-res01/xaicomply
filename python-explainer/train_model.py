"""
train_model.py — XAI-Comply Model Training Script
==================================================
Trains a RandomForestClassifier on the Kaggle Credit Card Fraud Detection
dataset, exports to ONNX format for Java ONNX Runtime inference, and saves
scaler statistics for the preprocessing service.

Paper reference: Section 2.4
  "All forecasting models are offline pre-trained in Python using scikit-learn,
   then serialized to ONNX format for language-agnostic deployment."

Usage:
  python train_model.py --data path/to/creditcard.csv

Output files (written to ../inference-xai-service/src/main/resources/model/):
  fraud_model.onnx       — ONNX model for Java inference
  scaler_stats.json      — mean/std for Amount and Time normalization
  feature_names.json     — feature order matching Java TransactionDTO
  model_metadata.json    — AUC, precision, recall for auditability

Note: The paper uses LightGBM. We use RandomForestClassifier here because it
has first-class skl2onnx support and SHAP TreeExplainer compatibility.
LightGBM can be substituted by replacing the classifier and re-exporting.
"""

import argparse
import json
import logging
import os
import sys
import time
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from imblearn.over_sampling import SMOTE
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (
    classification_report,
    f1_score,
    precision_score,
    recall_score,
    roc_auc_score,
)
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
from skl2onnx import convert_sklearn
from skl2onnx.common.data_types import FloatTensorType

# ── Logging setup ─────────────────────────────────────────────────────────────
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s — %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S",
)
log = logging.getLogger("train_model")

# ── Constants ─────────────────────────────────────────────────────────────────
FEATURE_COLS = ["Time"] + [f"V{i}" for i in range(1, 29)] + ["Amount"]
TARGET_COL   = "Class"
N_FEATURES   = len(FEATURE_COLS)   # 30

# Output directory — Java inference service resources
OUTPUT_DIR = Path(__file__).parent.parent / "inference-xai-service" \
             / "src" / "main" / "resources" / "model"

# Also write a local copy in python-explainer/model/ for the FastAPI sidecar
LOCAL_MODEL_DIR = Path(__file__).parent / "model"

# Also write scaler_stats to preprocessing service resources
PREPROCESSING_MODEL_DIR = Path(__file__).parent.parent / "preprocessing-service" \
                          / "src" / "main" / "resources" / "model"


def load_dataset(data_path: str) -> pd.DataFrame:
    """Load and validate the Kaggle credit card fraud dataset."""
    log.info("=" * 60)
    log.info("STEP 1/6 — Loading dataset from: %s", data_path)
    start = time.time()

    if not os.path.exists(data_path):
        log.error("Dataset not found at: %s", data_path)
        log.error("Download from: https://www.kaggle.com/datasets/mlg-ulb/creditcardfraud")
        log.error("Then run: python train_model.py --data /path/to/creditcard.csv")
        sys.exit(1)

    df = pd.read_csv(data_path)
    elapsed = time.time() - start

    log.info("Loaded %d rows, %d columns in %.2fs", len(df), len(df.columns), elapsed)
    log.info("Columns: %s", list(df.columns))
    log.info("Class distribution:\n%s", df[TARGET_COL].value_counts().to_string())
    log.info("Fraud rate: %.4f%%", df[TARGET_COL].mean() * 100)

    # Validate expected columns
    missing = set(FEATURE_COLS + [TARGET_COL]) - set(df.columns)
    if missing:
        log.error("Missing columns in dataset: %s", missing)
        sys.exit(1)

    # Check for nulls
    null_counts = df[FEATURE_COLS].isnull().sum()
    if null_counts.any():
        log.warning("Null values found:\n%s", null_counts[null_counts > 0])
        log.info("Filling nulls with column medians...")
        df[FEATURE_COLS] = df[FEATURE_COLS].fillna(df[FEATURE_COLS].median())
    else:
        log.info("No null values found — dataset is clean.")

    return df


def preprocess(df: pd.DataFrame):
    """
    Scale Amount and Time using z-score normalization.
    V1–V28 are already PCA-scaled, passed through unchanged.

    Returns:
        X_scaled, y, scaler_stats (dict with mean/std for Java service)
    """
    log.info("=" * 60)
    log.info("STEP 2/6 — Preprocessing (z-score normalization)")

    X = df[FEATURE_COLS].copy()
    y = df[TARGET_COL].values

    # Z-score scale Amount and Time
    amount_mean = X["Amount"].mean()
    amount_std  = X["Amount"].std()
    time_mean   = X["Time"].mean()
    time_std    = X["Time"].std()

    X["Amount"] = (X["Amount"] - amount_mean) / amount_std
    X["Time"]   = (X["Time"]   - time_mean)   / time_std

    log.info("Amount: mean=%.4f, std=%.4f", amount_mean, amount_std)
    log.info("Time:   mean=%.4f, std=%.4f", time_mean, time_std)
    log.info("Feature matrix shape: %s", X.shape)

    scaler_stats = {
        "amount_mean": float(amount_mean),
        "amount_std":  float(amount_std),
        "time_mean":   float(time_mean),
        "time_std":    float(time_std),
        "feature_names": FEATURE_COLS,
        "n_features": N_FEATURES,
    }

    return X.values.astype(np.float32), y, scaler_stats


def balance_dataset(X, y, target_fraud_rate: float = 0.05):
    """
    Apply SMOTE to achieve ~5% fraud rate, matching paper's experimental setup.
    Paper: "Five percent of the records had manually annotated compliance violations."
    """
    log.info("=" * 60)
    log.info("STEP 3/6 — Balancing dataset with SMOTE (target fraud rate: %.0f%%)",
             target_fraud_rate * 100)
    log.info("Before SMOTE — Fraud: %d, Legit: %d (%.4f%%)",
             y.sum(), (y == 0).sum(), y.mean() * 100)

    # Calculate desired sampling strategy for 5% fraud rate
    n_legit = (y == 0).sum()
    n_fraud_target = int(n_legit * target_fraud_rate / (1 - target_fraud_rate))

    log.info("Target fraud count after SMOTE: %d", n_fraud_target)

    smote = SMOTE(
        sampling_strategy={1: n_fraud_target},
        random_state=42,
        k_neighbors=5
    )
    X_res, y_res = smote.fit_resample(X, y)

    log.info("After SMOTE  — Fraud: %d, Legit: %d (%.4f%%)",
             y_res.sum(), (y_res == 0).sum(), y_res.mean() * 100)
    log.info("Total samples after resampling: %d", len(y_res))

    return X_res, y_res


def train_model(X_train, y_train):
    """
    Train RandomForestClassifier.
    Paper uses LightGBM/XGBoost; RandomForest chosen here for:
      - First-class skl2onnx ONNX export support
      - SHAP TreeExplainer compatibility
      - No additional dependencies beyond sklearn
    """
    log.info("=" * 60)
    log.info("STEP 4/6 — Training RandomForestClassifier")
    log.info("Training samples: %d | Features: %d", X_train.shape[0], X_train.shape[1])

    start = time.time()

    model = RandomForestClassifier(
        n_estimators=100,
        max_depth=15,
        min_samples_split=10,
        min_samples_leaf=4,
        max_features="sqrt",
        class_weight="balanced",   # handles remaining class imbalance
        random_state=42,
        n_jobs=-1,
        verbose=0,
    )

    log.info("Model config: %s", model.get_params())
    model.fit(X_train, y_train)

    elapsed = time.time() - start
    log.info("Training completed in %.2fs", elapsed)
    log.info("Feature importances (top 10):")
    importances = sorted(
        zip(FEATURE_COLS, model.feature_importances_),
        key=lambda x: x[1], reverse=True
    )
    for feat, imp in importances[:10]:
        log.info("  %-10s: %.4f", feat, imp)

    return model


def evaluate_model(model, X_test, y_test):
    """
    Evaluate model performance against paper's reported metrics.
    Paper reports: Precision 0.87, Recall 0.03, F1 0.06 (conservative threshold).
    """
    log.info("=" * 60)
    log.info("STEP 5a/6 — Model Evaluation")

    y_pred  = model.predict(X_test)
    y_proba = model.predict_proba(X_test)[:, 1]

    auc       = roc_auc_score(y_test, y_proba)
    precision = precision_score(y_test, y_pred, zero_division=0)
    recall    = recall_score(y_test, y_pred, zero_division=0)
    f1        = f1_score(y_test, y_pred, zero_division=0)

    log.info("AUC-ROC:   %.4f", auc)
    log.info("Precision: %.4f  (paper: 0.87)", precision)
    log.info("Recall:    %.4f  (paper: 0.03 — conservative threshold)", recall)
    log.info("F1-Score:  %.4f  (paper: 0.06)", f1)
    log.info("Full classification report:\n%s", classification_report(y_test, y_pred))

    # Find optimal threshold via F1 grid search (paper Section 3.3)
    log.info("Tuning threshold τ via grid search...")
    best_f1, best_tau = 0.0, 0.5
    for tau in np.arange(0.1, 0.95, 0.05):
        y_pred_tau = (y_proba >= tau).astype(int)
        f = f1_score(y_test, y_pred_tau, zero_division=0)
        if f > best_f1:
            best_f1, best_tau = f, tau
    log.info("Optimal threshold τ = %.2f (F1 = %.4f)", best_tau, best_f1)

    return {
        "auc_roc":        round(float(auc), 4),
        "precision":      round(float(precision), 4),
        "recall":         round(float(recall), 4),
        "f1_score":       round(float(f1), 4),
        "optimal_threshold": round(float(best_tau), 2),
        "test_samples":   int(len(y_test)),
        "fraud_in_test":  int(y_test.sum()),
    }


def export_onnx(model, output_path: Path):
    """
    Export sklearn RandomForestClassifier to ONNX format.
    Java ONNX Runtime will load this for sub-50ms inference (paper Section 2.4).

    ONNX model inputs/outputs:
      Input:  'float_input'       — shape [batch, 30], dtype float32
      Output: 'output_label'      — shape [batch],     dtype int64
      Output: 'output_probability'— shape [batch, 2],  dtype float32
    """
    log.info("=" * 60)
    log.info("STEP 5b/6 — Exporting to ONNX format")
    log.info("Output path: %s", output_path)

    initial_type = [("float_input", FloatTensorType([None, N_FEATURES]))]

    onnx_model = convert_sklearn(
        model,
        initial_types=initial_type,
        options={"zipmap": False},   # output_probability as float[][] not dict
        target_opset=17,
    )

    output_path.parent.mkdir(parents=True, exist_ok=True)
    with open(output_path, "wb") as f:
        f.write(onnx_model.SerializeToString())

    size_kb = output_path.stat().st_size / 1024
    log.info("ONNX model saved: %.1f KB", size_kb)

    # Validate the ONNX model with onnxruntime
    log.info("Validating ONNX model with onnxruntime...")
    import onnxruntime as ort
    sess = ort.InferenceSession(str(output_path))
    input_name  = sess.get_inputs()[0].name
    output_names = [o.name for o in sess.get_outputs()]
    log.info("  Input  : name='%s' shape=%s", input_name, sess.get_inputs()[0].shape)
    for out in sess.get_outputs():
        log.info("  Output : name='%s' shape=%s type=%s", out.name, out.shape, out.type)

    # Quick smoke test
    dummy = np.random.randn(1, N_FEATURES).astype(np.float32)
    result = sess.run(None, {input_name: dummy})
    log.info("  Smoke test — predicted class: %s, fraud prob: %.4f",
             result[0][0], result[1][0][1])
    log.info("ONNX validation passed!")

    return output_names


def save_artifacts(scaler_stats: dict, metrics: dict, output_dir: Path, local_dir: Path):
    """Save scaler stats, feature names, and model metadata for Java services."""
    log.info("=" * 60)
    log.info("STEP 6/6 — Saving supporting artifacts")

    for directory in [output_dir, local_dir, PREPROCESSING_MODEL_DIR]:
        directory.mkdir(parents=True, exist_ok=True)

        # Scaler stats for preprocessing service
        scaler_path = directory / "scaler_stats.json"
        with open(scaler_path, "w") as f:
            json.dump(scaler_stats, f, indent=2)
        log.info("Saved scaler_stats.json → %s", scaler_path)

        # Feature names for Java ExplainerClientService
        feature_path = directory / "feature_names.json"
        with open(feature_path, "w") as f:
            json.dump({"feature_names": FEATURE_COLS, "n_features": N_FEATURES}, f, indent=2)
        log.info("Saved feature_names.json → %s", feature_path)

        # Model metadata for auditability (paper Section 2.4)
        meta = {
            "model_type": "RandomForestClassifier",
            "paper_model": "LightGBM/XGBoost (substituted for ONNX compatibility)",
            "n_estimators": 100,
            "max_depth": 15,
            "n_features": N_FEATURES,
            "feature_names": FEATURE_COLS,
            "onnx_input_name": "float_input",
            "onnx_output_label": "output_label",
            "onnx_output_probability": "output_probability",
            "fraud_class_index": 1,
            **metrics,
        }
        meta_path = directory / "model_metadata.json"
        with open(meta_path, "w") as f:
            json.dump(meta, f, indent=2)
        log.info("Saved model_metadata.json → %s", meta_path)


def main():
    parser = argparse.ArgumentParser(description="Train XAI-Comply fraud detection model")
    parser.add_argument(
        "--data",
        type=str,
        default="creditcard.csv",
        help="Path to Kaggle creditcard.csv (default: ./creditcard.csv)",
    )
    args = parser.parse_args()

    log.info("╔══════════════════════════════════════════════════════╗")
    log.info("║       XAI-Comply — Model Training Pipeline          ║")
    log.info("║  Paper: Explainable AI for FinTech Compliance        ║")
    log.info("╚══════════════════════════════════════════════════════╝")
    log.info("Dataset: %s", args.data)
    log.info("Output:  %s", OUTPUT_DIR)

    pipeline_start = time.time()

    # ── Pipeline ──────────────────────────────────────────────────────────────
    df = load_dataset(args.data)
    X, y, scaler_stats = preprocess(df)
    X_res, y_res = balance_dataset(X, y, target_fraud_rate=0.05)

    # Train/test split (80/20, stratified)
    X_train, X_test, y_train, y_test = train_test_split(
        X_res, y_res, test_size=0.2, random_state=42, stratify=y_res
    )
    log.info("Train: %d samples | Test: %d samples", len(X_train), len(X_test))

    model   = train_model(X_train, y_train)
    metrics = evaluate_model(model, X_test, y_test)

    # Export ONNX to both Java resources and local Python directory
    onnx_java_path  = OUTPUT_DIR / "fraud_model.onnx"
    onnx_local_path = LOCAL_MODEL_DIR / "fraud_model.onnx"

    export_onnx(model, onnx_java_path)
    # Copy to local model dir for FastAPI sidecar
    import shutil
    LOCAL_MODEL_DIR.mkdir(parents=True, exist_ok=True)
    shutil.copy(onnx_java_path, onnx_local_path)
    log.info("Copied model to local sidecar directory: %s", onnx_local_path)

    # Also save the sklearn model for SHAP/LIME (Python sidecar uses original model)
    joblib_path = LOCAL_MODEL_DIR / "fraud_model.joblib"
    joblib.dump(model, joblib_path)
    log.info("Saved sklearn model for SHAP/LIME: %s", joblib_path)

    # Save training data sample for LIME background (500 samples)
    lime_background = X_train[:500]
    np.save(str(LOCAL_MODEL_DIR / "lime_background.npy"), lime_background)
    log.info("Saved LIME background data (500 samples)")

    save_artifacts(scaler_stats, metrics, OUTPUT_DIR, LOCAL_MODEL_DIR)

    elapsed = time.time() - pipeline_start
    log.info("=" * 60)
    log.info("Training pipeline completed in %.2fs", elapsed)
    log.info("")
    log.info("Next steps:")
    log.info("  1. Start Python sidecar:  uvicorn main:app --port 8085 --reload")
    log.info("  2. Build Java services:   cd .. && mvn clean install")
    log.info("  3. Run all services:      ./run-all.sh")
    log.info("  4. Test pipeline:         curl -X POST http://localhost:8081/api/v1/pipeline/run \\")
    log.info("                                 -H 'Content-Type: application/json' \\")
    log.info("                                 -d @../sample-transaction.json")


if __name__ == "__main__":
    main()
