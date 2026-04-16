# xAI-Compliance

**Explainable AI for Automated Compliance and Regulatory Reporting in FinTech**

Java Spring Boot microservices implementation of the paper:
> *"Explainable AI for Automated Compliance and Regulatory Reporting in FinTech: A Java Spring Boot Microservices Framework"* — Aravind Raghu, Independent Researcher

---

## Architecture

```
POST /api/v1/pipeline/run
         │
         ▼
[1] Preprocessing Service  :8081  — z-score normalize, impute, validate
         │
         ▼
[2] Inference & XAI Service :8082  — ONNX prediction + SHAP (Eq.1) + LIME (Eq.2)
         │                    ↕ calls Python sidecar :8085
         ▼
[3] Regulatory Mapping     :8083  — R = Σ ωᵢ|φᵢ| (Eq.3) → COMPLIANT/REVIEW/VIOLATION
         │
         ▼
[4] Reporting Service      :8084  — Spring Batch → CSV report → H2 audit log
```

---

## Prerequisites

| Tool | Version | Install |
|------|---------|---------|
| Java | 17+ | https://adoptium.net |
| Maven | 3.9+ | https://maven.apache.org |
| Python | 3.10+ | https://python.org |
| Docker | Any | https://docker.com (optional, for monitoring) |

---

## Quick Start

### Step 1 — Download Kaggle Dataset

1. Go to https://www.kaggle.com/datasets/mlg-ulb/creditcardfraud
2. Download `creditcard.csv`
3. Place it in `python-explainer/creditcard.csv`

### Step 2 — Train Model & Start Python Sidecar

```bash
cd python-explainer
pip install -r requirements.txt
python train_model.py --data creditcard.csv
# Outputs: model/fraud_model.onnx, model/scaler_stats.json

# Keep this running in Terminal 1:
uvicorn main:app --host 0.0.0.0 --port 8085 --reload
```

### Step 3 — Build & Start Java Services

```bash
# Terminal 2 (from project root):
chmod +x run-all.sh stop-all.sh
mvn clean install -DskipTests
./run-all.sh
```

### Step 4 — Test the Pipeline

```bash
# Full end-to-end pipeline:
curl -X POST http://localhost:8081/api/v1/pipeline/run \
     -H "Content-Type: application/json" \
     -d @sample-transaction.json | python -m json.tool

# Check compliance report:
curl http://localhost:8084/api/v1/reports | python -m json.tool

# Get evaluation metrics (Precision/Recall/F1):
curl http://localhost:8084/api/v1/reports/metrics | python -m json.tool

# Generate batch CSV report:
curl -X POST http://localhost:8084/api/v1/reports/generate | python -m json.tool

# View weight matrix ω:
curl http://localhost:8083/api/v1/weights | python -m json.tool
```

### Step 5 — Optional Monitoring

```bash
docker-compose up -d
# Grafana:    http://localhost:3000  (admin / admin)
# Prometheus: http://localhost:9090
```

---

## Key Endpoints

| Service | Port | Endpoint | Description |
|---------|------|----------|-------------|
| Preprocessing | 8081 | `POST /api/v1/pipeline/run` | **Main entry point** — full pipeline |
| Preprocessing | 8081 | `POST /api/v1/transactions` | Preprocess only |
| Preprocessing | 8081 | `GET /api/v1/pipeline/health` | Check all services |
| Inference+XAI | 8082 | `POST /api/v1/predict` | ONNX + SHAP + LIME |
| Inference+XAI | 8082 | `GET /api/v1/model/info` | Model metadata |
| Reg. Mapping  | 8083 | `POST /api/v1/map` | Apply Eq.3 |
| Reg. Mapping  | 8083 | `GET /api/v1/weights` | Weight matrix ω |
| Reporting     | 8084 | `GET /api/v1/reports` | Summary report |
| Reporting     | 8084 | `POST /api/v1/reports/generate` | Trigger batch job |
| Reporting     | 8084 | `GET /api/v1/reports/metrics` | Precision/Recall/F1 |
| Reporting     | 8084 | `GET /h2-console` | H2 DB browser |
| Python        | 8085 | `POST /explain/shap` | SHAP attributions |
| Python        | 8085 | `POST /explain/lime` | LIME attributions |
| Python        | 8085 | `GET /health` | Sidecar health |

---

## Paper Algorithm Reference

| Equation | Description | Implementation |
|----------|-------------|----------------|
| Eq.1: `f(x) = φ₀ + Σφᵢ` | SHAP attribution | `python-explainer/main.py` → `/explain/shap` |
| Eq.2: `argmin[ℒ(f,g,πₓ) + Ω(g)]` | LIME surrogate | `python-explainer/main.py` → `/explain/lime` |
| Eq.3: `R = Σ ωᵢ\|φᵢ\|` | Regulatory risk score | `RegulatoryMappingService.java` |

---

## Expected Results

| Metric | Paper (Baseline) | Paper (XAI-Comply) | Our Target |
|--------|-----------------|-------------------|------------|
| Precision | 0.84 | 0.87 | Check `/api/v1/reports/metrics` |
| Recall | 0.08 | 0.03 | Check `/api/v1/reports/metrics` |
| F1-Score | 0.15 | 0.06 | Check `/api/v1/reports/metrics` |
| Report Latency (mean) | 120s | 48s | Check batch job logs |
| Exceptions/Quarter | 50 | 15 | Check `/api/v1/reports` |
| Throughput | 300 txn/s | 500 txn/s | JMeter load test |

---

## Stopping Services

```bash
./stop-all.sh
docker-compose down   # if monitoring was started
```

---

## Project Structure

```
xai-comply/
├── pom.xml                          Parent POM
├── xai-comply-common/               Shared DTOs, enums, exceptions
├── preprocessing-service/           Port 8081 — normalize transactions
├── inference-xai-service/           Port 8082 — ONNX + SHAP + LIME
├── regulatory-mapping-service/      Port 8083 — Eq.3 compliance scoring
├── reporting-service/               Port 8084 — Spring Batch + H2
├── python-explainer/                Port 8085 — FastAPI SHAP/LIME
├── monitoring/                      Prometheus + Grafana configs
├── docker-compose.yml               Monitoring stack
├── run-all.sh                       Start all services (Mac/Linux)
├── stop-all.sh                      Stop all services
└── sample-transaction.json          Test input
```
