#!/bin/bash
# ============================================================
# XAI-Comply — Start All Services
# Run from project root: ./run-all.sh
# ============================================================

set -e
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT_DIR/logs"
mkdir -p "$LOG_DIR"

GREEN='\033[0;32m'; YELLOW='\033[1;33m'; RED='\033[0;31m'; NC='\033[0m'

echo -e "${GREEN}"
echo "╔══════════════════════════════════════════════════════╗"
echo "║          XAI-Comply — Starting All Services         ║"
echo "╚══════════════════════════════════════════════════════╝"
echo -e "${NC}"

# ── Prerequisite checks ──────────────────────────────────────────────────────
echo -e "${YELLOW}[0/5] Checking prerequisites...${NC}"

if ! command -v java &> /dev/null; then
    echo -e "${RED}ERROR: Java 17 not found. Install from https://adoptium.net${NC}"; exit 1
fi
JAVA_VERSION=$(java -version 2>&1 | awk -F '"' '/version/ {print $2}' | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ] 2>/dev/null; then
    echo -e "${RED}ERROR: Java 17+ required (found $JAVA_VERSION)${NC}"; exit 1
fi
echo "  ✅ Java: $(java -version 2>&1 | head -1)"

if ! command -v mvn &> /dev/null; then
    echo -e "${RED}ERROR: Maven not found. Install from https://maven.apache.org${NC}"; exit 1
fi
echo "  ✅ Maven: $(mvn -version | head -1)"

# Check ONNX model exists
ONNX_MODEL="$ROOT_DIR/inference-xai-service/src/main/resources/model/fraud_model.onnx"
if [ ! -f "$ONNX_MODEL" ]; then
    echo -e "${YELLOW}  ⚠️  ONNX model not found at: $ONNX_MODEL${NC}"
    echo -e "${YELLOW}  → Run first: cd python-explainer && python train_model.py --data creditcard.csv${NC}"
    echo -e "${YELLOW}  → Continuing without model — inference service will log an error${NC}"
else
    echo "  ✅ ONNX model found: $(du -h "$ONNX_MODEL" | cut -f1)"
fi

# ── Build ────────────────────────────────────────────────────────────────────
echo ""
echo -e "${YELLOW}[1/5] Building all Maven modules...${NC}"
cd "$ROOT_DIR"
mvn clean install -DskipTests -q
echo -e "${GREEN}  ✅ Build successful${NC}"

# ── Start services ────────────────────────────────────────────────────────────

wait_for_service() {
    local name=$1 url=$2 max_wait=${3:-60}
    echo -n "  Waiting for $name to be ready"
    for i in $(seq 1 $max_wait); do
        if curl -sf "$url" > /dev/null 2>&1; then
            echo -e " ${GREEN}✅ UP${NC}"
            return 0
        fi
        echo -n "."
        sleep 1
    done
    echo -e " ${RED}❌ TIMEOUT after ${max_wait}s${NC}"
    return 1
}

echo ""
echo -e "${YELLOW}[2/5] Starting Preprocessing Service (port 8081)...${NC}"
cd "$ROOT_DIR/preprocessing-service"
nohup mvn spring-boot:run > "$LOG_DIR/preprocessing-service.log" 2>&1 &
echo $! > "$LOG_DIR/preprocessing-service.pid"
wait_for_service "preprocessing-service" "http://localhost:8081/actuator/health"

echo ""
echo -e "${YELLOW}[3/5] Starting Inference & XAI Service (port 8082)...${NC}"
cd "$ROOT_DIR/inference-xai-service"
nohup mvn spring-boot:run > "$LOG_DIR/inference-xai-service.log" 2>&1 &
echo $! > "$LOG_DIR/inference-xai-service.pid"
wait_for_service "inference-xai-service" "http://localhost:8082/actuator/health" 90

echo ""
echo -e "${YELLOW}[4/5] Starting Regulatory Mapping Service (port 8083)...${NC}"
cd "$ROOT_DIR/regulatory-mapping-service"
nohup mvn spring-boot:run > "$LOG_DIR/regulatory-mapping-service.log" 2>&1 &
echo $! > "$LOG_DIR/regulatory-mapping-service.pid"
wait_for_service "regulatory-mapping-service" "http://localhost:8083/actuator/health"

echo ""
echo -e "${YELLOW}[5/5] Starting Reporting Service (port 8084)...${NC}"
cd "$ROOT_DIR/reporting-service"
nohup mvn spring-boot:run > "$LOG_DIR/reporting-service.log" 2>&1 &
echo $! > "$LOG_DIR/reporting-service.pid"
wait_for_service "reporting-service" "http://localhost:8084/actuator/health"

# ── Summary ───────────────────────────────────────────────────────────────────
echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}All Java services are UP!${NC}"
echo ""
echo "  📥 Preprocessing  → http://localhost:8081"
echo "  🤖 Inference+XAI  → http://localhost:8082"
echo "  ⚖️  Reg. Mapping   → http://localhost:8083"
echo "  📋 Reporting       → http://localhost:8084"
echo "  🗄️  H2 Console      → http://localhost:8084/h2-console"
echo ""
echo -e "${YELLOW}Python sidecar (start manually in another terminal):${NC}"
echo "  cd python-explainer && uvicorn main:app --port 8085 --reload"
echo ""
echo -e "${YELLOW}Test the full pipeline:${NC}"
echo "  curl -X POST http://localhost:8081/api/v1/pipeline/run \\"
echo "       -H 'Content-Type: application/json' \\"
echo "       -d @sample-transaction.json"
echo ""
echo -e "${YELLOW}Optional monitoring (requires Docker):${NC}"
echo "  docker-compose up -d"
echo "  → Grafana: http://localhost:3000  (admin/admin)"
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
