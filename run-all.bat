@echo off
REM ============================================================
REM XAI-Comply — Start All Services (Windows)
REM Run from project root: run-all.bat
REM ============================================================

setlocal enabledelayedexpansion
set ROOT_DIR=%~dp0
set LOG_DIR=%ROOT_DIR%logs
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo.
echo ============================================================
echo    XAI-Comply - Starting All Services (Windows)
echo ============================================================
echo.

REM ── Prerequisite checks ──────────────────────────────────────
echo [0/5] Checking prerequisites...

java -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Java 17 not found. Install from https://adoptium.net
    pause & exit /b 1
)
echo   OK Java found

mvn -version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Maven not found. Install from https://maven.apache.org
    pause & exit /b 1
)
echo   OK Maven found

set ONNX_MODEL=%ROOT_DIR%inference-xai-service\src\main\resources\model\fraud_model.onnx
if not exist "%ONNX_MODEL%" (
    echo   WARNING: ONNX model not found at %ONNX_MODEL%
    echo   Run first: cd python-explainer ^&^& python train_model.py --data creditcard.csv
) else (
    echo   OK ONNX model found
)

REM ── Build ─────────────────────────────────────────────────────
echo.
echo [1/5] Building all Maven modules...
cd /d "%ROOT_DIR%"
call mvn clean install -DskipTests -q
if errorlevel 1 (
    echo ERROR: Maven build failed. Check output above.
    pause & exit /b 1
)
echo   OK Build successful

REM ── Start services ────────────────────────────────────────────
echo.
echo [2/5] Starting Preprocessing Service (port 8081)...
cd /d "%ROOT_DIR%preprocessing-service"
start "XAI-Preprocessing" /B cmd /c "mvn spring-boot:run > %LOG_DIR%\preprocessing-service.log 2>&1"

echo Waiting for Preprocessing Service...
timeout /t 20 /nobreak >nul

echo.
echo [3/5] Starting Inference + XAI Service (port 8082)...
cd /d "%ROOT_DIR%inference-xai-service"
start "XAI-Inference" /B cmd /c "mvn spring-boot:run > %LOG_DIR%\inference-xai-service.log 2>&1"

echo Waiting for Inference Service (model load takes ~30s)...
timeout /t 35 /nobreak >nul

echo.
echo [4/5] Starting Regulatory Mapping Service (port 8083)...
cd /d "%ROOT_DIR%regulatory-mapping-service"
start "XAI-Mapping" /B cmd /c "mvn spring-boot:run > %LOG_DIR%\regulatory-mapping-service.log 2>&1"

echo Waiting for Mapping Service...
timeout /t 20 /nobreak >nul

echo.
echo [5/5] Starting Reporting Service (port 8084)...
cd /d "%ROOT_DIR%reporting-service"
start "XAI-Reporting" /B cmd /c "mvn spring-boot:run > %LOG_DIR%\reporting-service.log 2>&1"

echo Waiting for Reporting Service...
timeout /t 20 /nobreak >nul

REM ── Summary ───────────────────────────────────────────────────
echo.
echo ============================================================
echo All Java services started!
echo.
echo   Preprocessing  -^> http://localhost:8081
echo   Inference+XAI  -^> http://localhost:8082
echo   Reg. Mapping   -^> http://localhost:8083
echo   Reporting      -^> http://localhost:8084
echo   H2 Console     -^> http://localhost:8084/h2-console
echo.
echo Python sidecar (open a NEW terminal and run):
echo   cd python-explainer
echo   uvicorn main:app --port 8085 --reload
echo.
echo Test the full pipeline:
echo   curl -X POST http://localhost:8081/api/v1/pipeline/run ^
echo        -H "Content-Type: application/json" ^
echo        -d @sample-transaction.json
echo.
echo Logs are in: %LOG_DIR%
echo ============================================================
echo.
pause
