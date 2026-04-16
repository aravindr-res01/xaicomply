# XAI-Comply

**Explainable AI Compliance Framework for FinTech**

## What Is XAI-Comply

Financial institutions face increasing regulatory pressure to explain AI-driven decisions — particularly around transaction fraud detection and risk scoring. When a model flags a transaction, regulators now demand answers: *why* was this transaction flagged? Which factors contributed most? How was the risk threshold determined? Traditional black-box models cannot answer these questions, creating compliance gaps that can result in significant fines and reputational damage.

XAI-Comply is a production-ready Spring Boot 3.1 / Java 17 application that wraps any scoring model with a complete explainability and compliance reporting layer. It implements SHAP and LIME explanation algorithms to decompose each model prediction into per-feature contributions, maps those contributions to regulatory risk scores using configurable weights, generates audit-trailed compliance reports, and exposes a full REST API with structured logging. The framework ships with a realistic weighted scoring stub and a clear migration path to any ONNX-compatible ML model — with zero external dependencies required to run.

---

## The 3 Key Equations

### Eq.1 — SHAP: `f(x) = phi0 + sum(phi_i)`

We decompose the model score into the **baseline** (`phi0`) plus each feature's **individual contribution** (`phi_i`). The baseline is the model score when all features take their background (mean) values. Each `phi_i` measures how much that single feature pushes the score up or down from the baseline. This additive decomposition guarantees that: baseline + all contributions = final model score, making predictions locally faithful and auditable.

### Eq.2 — LIME: `argmin_g[L(f,g,pi_x) + Omega(g)]`

We fit a **simple linear model** locally around each prediction. For 500 perturbations near the input point (random binary masks of features), we score each with the original model, apply exponential kernel weights based on proximity, then solve a weighted ordinary least-squares regression. The resulting linear coefficients are interpretable feature attributions that approximate the complex model's behavior in the local neighbourhood of the transaction being explained.

### Eq.3 — Regulatory Risk Score: `R = sum(omega_i * |phi_i|)`

We weight each feature's **absolute contribution** (`|phi_i|`) by its **regulatory importance** (`omega_i`). These weights are configurable per-institution and sum to 1.0. A high-amount transaction in a high-risk country with unusual velocity will produce a high `R`. If `R` exceeds the configured threshold, the transaction is flagged, an audit event is recorded, and a compliance report can be triggered automatically.

---

## Architecture

```
HTTP Request
    |
    v
[SecurityFilter] (X-API-Key validation)
    |
    v
[TransactionController / ExplainController]
    |
    +---> [PreprocessingService]
    |         |-- SchemaValidator (field validation)
    |         |-- ZScoreNormalizer (Welford online, DB-persisted)
    |         +-- OneHotEncoder (MCC x10, Country x6, International x1)
    |
    +---> [InferenceService]
    |         |-- ModelScoringEngine (weighted stub / ONNX-ready)
    |         |-- ShapExplainer (sampling SHAP, LRU cache)
    |         +-- LimeExplainer (500 perturbations, OLS via commons-math3)
    |
    +---> [RegulatoryMappingService]  Eq.3: R = sum(omega_i * |phi_i|)
    |         |-- WeightRegistry (DB-persisted, runtime-updatable)
    |         +-- ApplicationEventPublisher --> ComplianceFlagEvent
    |
    v
[ApiResponse<T>] returned to caller


ComplianceFlagEvent (auto-trigger when flagged count >= threshold)
    |
    v
[ReportingService] --> [Spring Batch Job]
    |                       |
    |                       +---> Step1: JpaPagingItemReader<Transaction> (chunk=100)
    |                       +---> Step2: ReportRecordProcessor (enrich with RiskScore + top phi)
    |                       +---> Step3: CompositeReportWriter
    |                                       |-- PdfReportGenerator (iText7)
    |                                       +-- CsvReportGenerator (OpenCSV)
    |
    +---> [LocalFileStorageService] (./reports/)
    +---> [AuditLogService] (SHA-256 chain-hashed entries)
    +---> ComplianceReport entity updated (COMPLETED)
```

---

## Quick Start

```bash
# Clone and build
git clone <repo-url>
cd xai-comply
mvn clean package -DskipTests

# Run
java -jar target/xai-comply-1.0.0.jar

# Or with Maven
mvn spring-boot:run
```

- **API**: http://localhost:8080/api/v1
- **H2 Console**: http://localhost:8080/h2-console
  - JDBC URL: `jdbc:h2:mem:xaicomply`
  - Username: `sa`, Password: *(empty)*
- **Health**: http://localhost:8080/actuator/health
- **Prometheus**: http://localhost:8080/actuator/prometheus
- **Default API Key**: `dev-api-key-xai-comply-2024`

---

## API Reference — Complete curl Examples

All requests require header: `-H "X-API-Key: dev-api-key-xai-comply-2024"`

### POST /api/v1/transactions — Create & Score Transaction

```bash
curl -s -X POST http://localhost:8080/api/v1/transactions \
  -H "X-API-Key: dev-api-key-xai-comply-2024" \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "CUST-12345",
    "amount": 4999.99,
    "currency": "USD",
    "merchantCategoryCode": "5999",
    "countryCode": "CN",
    "transactionVelocity": 12,
    "isInternational": true,
    "hourOfDay": 3
  }' | python3 -m json.tool
```

**Sample Response:**
```json
{
  "success": true,
  "data": {
    "transactionId": "550e8400-e29b-41d4-a716-446655440000",
    "customerId": "CUST-12345",
    "amount": 4999.99,
    "currency": "USD",
    "status": "FLAGGED",
    "modelScore": 0.8234,
    "riskScore": 0.7123,
    "flagged": true,
    "top5Attributions": [
      { "featureName": "amount_z", "phi": 0.3112, "absValue": 0.3112, "rank": 1 },
      { "featureName": "velocity_z", "phi": 0.2891, "absValue": 0.2891, "rank": 2 },
      { "featureName": "is_international", "phi": 0.1450, "absValue": 0.1450, "rank": 3 },
      { "featureName": "country_CN", "phi": 0.1321, "absValue": 0.1321, "rank": 4 },
      { "featureName": "hour_z", "phi": 0.0412, "absValue": 0.0412, "rank": 5 }
    ],
    "processingTimeMs": 23,
    "createdAt": "2024-01-15T03:00:00Z"
  },
  "timestamp": "2024-01-15T03:00:00.123Z"
}
```

### GET /api/v1/transactions/{id}

```bash
curl -s http://localhost:8080/api/v1/transactions/550e8400-e29b-41d4-a716-446655440000 \
  -H "X-API-Key: dev-api-key-xai-comply-2024" | python3 -m json.tool
```

### GET /api/v1/transactions — List with Filters

```bash
# All transactions
curl -s "http://localhost:8080/api/v1/transactions?page=0&size=20" \
  -H "X-API-Key: dev-api-key-xai-comply-2024"

# By customer
curl -s "http://localhost:8080/api/v1/transactions?customerId=CUST-12345" \
  -H "X-API-Key: dev-api-key-xai-comply-2024"

# Flagged only
curl -s "http://localhost:8080/api/v1/transactions?status=FLAGGED" \
  -H "X-API-Key: dev-api-key-xai-comply-2024"
```

### POST /api/v1/transactions/{id}/explain — SHAP Explanation

```bash
curl -s -X POST http://localhost:8080/api/v1/transactions/550e8400-e29b-41d4-a716-446655440000/explain \
  -H "X-API-Key: dev-api-key-xai-comply-2024" \
  -H "Content-Type: application/json" \
  -d '{ "method": "SHAP" }' | python3 -m json.tool
```

**Sample Response:**
```json
{
  "success": true,
  "data": {
    "transactionId": "550e8400-e29b-41d4-a716-446655440000",
    "method": "SHAP",
    "modelScore": 0.8234,
    "phi0": 0.5,
    "riskScore": 0.7123,
    "flagged": true,
    "threshold": 0.65,
    "attributions": [
      { "featureName": "amount_z", "phi": 0.3112, "absValue": 0.3112, "rank": 1 }
    ],
    "interpretation": "Transaction flagged. Primary drivers: amount_z (phi=+0.31), velocity_z (phi=+0.29), is_international (phi=+0.15). Risk score 0.71 exceeds threshold 0.65."
  }
}
```

### POST /api/v1/transactions/{id}/explain — LIME Explanation

```bash
curl -s -X POST http://localhost:8080/api/v1/transactions/550e8400-e29b-41d4-a716-446655440000/explain \
  -H "X-API-Key: dev-api-key-xai-comply-2024" \
  -H "Content-Type: application/json" \
  -d '{ "method": "LIME" }' | python3 -m json.tool
```

### POST /api/v1/reports/generate — Trigger Compliance Report

```bash
curl -s -X POST http://localhost:8080/api/v1/reports/generate \
  -H "X-API-Key: dev-api-key-xai-comply-2024" \
  -H "Content-Type: application/json" \
  -d '{
    "period": "MONTHLY",
    "year": 2024,
    "month": 1
  }' | python3 -m json.tool
```

**Sample Response (202 Accepted):**
```json
{
  "success": true,
  "data": {
    "reportId": "7f000001-8c67-198c-818c-678d3c900000",
    "period": "MONTHLY-2024-01",
    "status": "QUEUED",
    "message": "Report generation started"
  }
}
```

### GET /api/v1/reports/{reportId}

```bash
curl -s http://localhost:8080/api/v1/reports/7f000001-8c67-198c-818c-678d3c900000 \
  -H "X-API-Key: dev-api-key-xai-comply-2024" | python3 -m json.tool
```

### GET /api/v1/reports/{reportId}/download — Download PDF

```bash
curl -s -o report.pdf \
  "http://localhost:8080/api/v1/reports/7f000001-8c67-198c-818c-678d3c900000/download" \
  -H "X-API-Key: dev-api-key-xai-comply-2024"
```

### GET /api/v1/reports — List All Reports

```bash
curl -s http://localhost:8080/api/v1/reports \
  -H "X-API-Key: dev-api-key-xai-comply-2024" | python3 -m json.tool
```

### GET /api/v1/audit/{entityId} — Audit Chain

```bash
curl -s http://localhost:8080/api/v1/audit/550e8400-e29b-41d4-a716-446655440000 \
  -H "X-API-Key: dev-api-key-xai-comply-2024" | python3 -m json.tool
```

**Sample Response:**
```json
{
  "success": true,
  "data": {
    "entityId": "550e8400-e29b-41d4-a716-446655440000",
    "entries": [
      {
        "action": "CREATED",
        "sha256Hash": "a3f5c8...",
        "previousHash": "GENESIS",
        "createdAt": "2024-01-15T03:00:00Z"
      }
    ],
    "chainValid": true,
    "entryCount": 1
  }
}
```

### POST /api/v1/admin/calibrate — Threshold Calibration

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/calibrate \
  -H "X-API-Key: dev-api-key-xai-comply-2024" \
  -H "Content-Type: application/json" \
  -d '[
    { "transactionId": "550e8400-e29b-41d4-a716-446655440000", "trueLabel": true },
    { "transactionId": "550e8400-e29b-41d4-a716-446655440001", "trueLabel": false }
  ]' | python3 -m json.tool
```

### GET /api/v1/admin/weights — Get Current Weights

```bash
curl -s http://localhost:8080/api/v1/admin/weights \
  -H "X-API-Key: dev-api-key-xai-comply-2024" | python3 -m json.tool
```

### POST /api/v1/admin/weights — Update Weights

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/weights \
  -H "X-API-Key: dev-api-key-xai-comply-2024" \
  -H "Content-Type: application/json" \
  -d '{
    "weights": [0.15,0.15,0.05,0.04,0.04,0.04,0.04,0.04,0.04,0.04,0.04,0.04,0.04,0.04,0.04,0.03,0.03,0.03,0.03,0.05],
    "threshold": 0.70
  }' | python3 -m json.tool
```

### Actuator Endpoints (No API Key Required)

```bash
curl http://localhost:8080/actuator/health
curl http://localhost:8080/actuator/metrics
curl http://localhost:8080/actuator/prometheus
```

---

## Configuration Reference

| Property | Default | Description | Override |
|---|---|---|---|
| `server.port` | `8080` | HTTP server port | `--server.port=9090` |
| `security.api-key` | `dev-api-key-xai-comply-2024` | API authentication key | Env: `SECURITY_API_KEY` |
| `xai.shap.background-means` | 20 zeros | Background feature values for SHAP baseline | Update in yml |
| `xai.shap.cache-size` | `1000` | LRU cache size for SHAP results | Update in yml |
| `xai.lime.num-samples` | `500` | Number of perturbation samples for LIME | Update in yml |
| `xai.lime.sigma-factor` | `0.75` | Kernel bandwidth factor for LIME weights | Update in yml |
| `xai.risk-weights.weights` | 20 equal weights | Comma-separated regulatory weights (must sum ≈ 1.0) | POST /admin/weights |
| `xai.risk-weights.threshold` | `0.65` | Risk score flagging threshold | POST /admin/weights |
| `xai.preprocessing.mcc-categories` | 10 categories | MCC codes for one-hot encoding | Update in yml |
| `xai.preprocessing.country-codes` | `US,GB,DE,FR,CN,OTHER` | Countries for one-hot encoding | Update in yml |
| `xai.reporting.output-dir` | `./reports` | Directory for generated PDF/CSV files | Update in yml |
| `xai.reporting.flags-trigger-count` | `10` | Auto-trigger report after N flags | Update in yml |
| `xai.model.path` | `classpath:model.onnx` | ONNX model path (for future use) | Update in yml |
| `spring.datasource.url` | H2 in-memory | Database connection | See postgres profile |
| `spring.batch.job.enabled` | `false` | Prevent auto-start of batch jobs | Keep false |

---

## Switching to Production Components

### a. Real ONNX ML Model

1. Train your model and export to ONNX format
2. Copy `model.onnx` to `src/main/resources/` (or any path)
3. Update `xai.model.path=classpath:model.onnx` in config
4. In `ModelScoringEngine.java`, replace the stub body with the ONNX Runtime code shown in the TODO comment block

### b. PostgreSQL Database

```bash
java -jar target/xai-comply-1.0.0.jar \
  --spring.profiles.active=postgres \
  --DB_HOST=your-db-host \
  --DB_PORT=5432 \
  --DB_NAME=xaicomply \
  --DB_USER=xaicomply \
  --DB_PASSWORD=secret
```

Or set environment variables: `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`.
Run Flyway/Liquibase migrations before first run with `validate` DDL.

### c. AWS S3 Storage

Implement `S3StorageService implements StorageService`:
```java
@Service
@Primary
@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
public class S3StorageService implements StorageService {
    // Use AWS SDK v2: S3Client.putObject(...)
}
```
Add `software.amazon.awssdk:s3` dependency and `storage.type=s3` to config.

### d. Apache Kafka Event Bus

Add `spring-boot-starter-kafka` dependency. Create a Kafka profile and replace:
```java
// Replace: eventPublisher.publishEvent(new ComplianceFlagEvent(...))
// With:    kafkaTemplate.send("compliance-flags", event);
// And add: @KafkaListener in ReportingService
```

### e. OAuth2 / Keycloak Authentication

Replace `ApiKeyAuthFilter` in `SecurityConfig.java`. The commented migration block is already in the file:
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: https://your-keycloak/realms/your-realm
```
Add `spring-boot-starter-oauth2-resource-server` dependency.

### f. HashiCorp Vault for Secrets

```xml
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-vault-config</artifactId>
</dependency>
```
Then replace `@Value("${security.api-key}")` references with Vault-backed properties via `bootstrap.yml` Vault configuration.

---

## Running Tests

```bash
# All tests
mvn test

# Specific test class
mvn test -Dtest=ShapExplainerTest

# Skip tests during build
mvn clean package -DskipTests
```

---

## Docker

```dockerfile
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY target/xai-comply-1.0.0.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

```bash
# Build
docker build -t xai-comply:1.0.0 .

# Run (default H2, dev key)
docker run -p 8080:8080 xai-comply:1.0.0

# Run with PostgreSQL
docker run -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=postgres \
  -e DB_HOST=host.docker.internal \
  -e DB_PORT=5432 \
  -e DB_NAME=xaicomply \
  -e DB_USER=xaicomply \
  -e DB_PASSWORD=secret \
  -e SECURITY_API_KEY=prod-key-here \
  xai-comply:1.0.0
```

---

## Project Structure

```
src/main/java/com/xaicomply/
├── XaiComplyApplication.java      Entry point, Spring Boot main class
├── config/
│   ├── SecurityConfig.java        API key filter, Spring Security config
│   ├── BatchConfig.java           Spring Batch configuration
│   ├── AsyncConfig.java           Thread pool config for async jobs
│   └── ModelConfig.java           Binds all xai.* configuration properties
├── domain/
│   ├── Transaction.java           JPA entity: main transaction record
│   ├── RiskScore.java             JPA entity: computed risk scores with phi JSON
│   ├── ComplianceReport.java      JPA entity: report job state
│   ├── AuditLogEntry.java         JPA entity: immutable chain-hashed audit entries
│   ├── NormalizerStats.java       JPA entity: Welford running mean/std per feature
│   ├── FeatureVector.java         Record: preprocessed float[20] feature array
│   └── AttributionResult.java     Record: SHAP/LIME output with phi values
├── preprocessing/
│   ├── PreprocessingService.java  Orchestrates validation + normalization + encoding
│   ├── SchemaValidator.java       Field-level validation with error map
│   ├── ZScoreNormalizer.java      Welford online algorithm, DB-persisted stats
│   └── OneHotEncoder.java         MCC, country, and international encoding
├── inference/
│   ├── InferenceService.java      Orchestrates score + explain selection
│   ├── ModelScoringEngine.java    Weighted stub with ONNX TODO migration block
│   ├── shap/ShapExplainer.java    Sampling SHAP with LRU cache
│   └── lime/LimeExplainer.java    500-perturbation LIME with weighted OLS
├── mapping/
│   ├── RegulatoryMappingService.java  Eq.3 R computation, flagging, event firing
│   ├── WeightRegistry.java            Config+DB weight management, runtime updates
│   ├── ThresholdCalibrator.java       Grid search F1 maximization
│   └── ComplianceFlagEvent.java       Spring ApplicationEvent for flagged transactions
├── reporting/
│   ├── ReportingService.java          Job trigger via API or auto ComplianceFlagEvent
│   ├── batch/
│   │   ├── ComplianceReportJob.java   Spring Batch job definition + JobParameters
│   │   ├── TransactionItemReader.java JpaPagingItemReader, chunk=100
│   │   ├── ReportRecordProcessor.java Enriches with RiskScore + top attribution
│   │   ├── CompositeReportWriter.java Delegates to PDF + CSV generators
│   │   └── ReportRecord.java          DTO for batch pipeline
│   ├── output/
│   │   ├── StorageService.java        Interface for file storage (local / S3)
│   │   ├── LocalFileStorageService.java Writes to ./reports/ @PostConstruct init
│   │   ├── PdfReportGenerator.java    iText7: title, summary table, detail table
│   │   └── CsvReportGenerator.java    OpenCSV flat file output
│   └── audit/
│       └── AuditLogService.java       SHA-256 chain: hash(entityId+action+payload+prevHash)
├── api/
│   ├── TransactionController.java  POST + GET transaction endpoints
│   ├── ExplainController.java      POST /explain with SHAP/LIME + interpretation
│   ├── ReportController.java       POST generate + GET status/download/list
│   ├── AuditController.java        GET audit chain with integrity verification
│   ├── AdminController.java        Calibrate + get/update weights
│   └── dto/                        Request/Response records for all endpoints
├── repository/                     Spring Data JPA interfaces (5 repositories)
└── exception/
    ├── GlobalExceptionHandler.java  @RestControllerAdvice for 400/404/500
    ├── ValidationException.java     Field-error-map validation exception
    └── ApiResponse.java             Generic success/error envelope record
```
