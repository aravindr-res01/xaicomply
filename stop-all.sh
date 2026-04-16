#!/bin/bash
# XAI-Comply — Stop All Services
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOG_DIR="$ROOT_DIR/logs"

echo "Stopping XAI-Comply services..."
for svc in preprocessing-service inference-xai-service regulatory-mapping-service reporting-service; do
    PID_FILE="$LOG_DIR/$svc.pid"
    if [ -f "$PID_FILE" ]; then
        PID=$(cat "$PID_FILE")
        if kill -0 "$PID" 2>/dev/null; then
            kill "$PID"
            echo "  ✅ Stopped $svc (PID $PID)"
        else
            echo "  ⚠️  $svc was not running"
        fi
        rm -f "$PID_FILE"
    else
        echo "  ⚠️  No PID file for $svc"
    fi
done

# Also kill any lingering mvn spring-boot:run processes
pkill -f "spring-boot:run" 2>/dev/null && echo "  ✅ Killed remaining spring-boot:run processes" || true
echo "All services stopped."
