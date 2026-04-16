package com.xaicomply.preprocessing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

/**
 * Preprocessing Service — Entry point
 *
 * Responsibilities (paper Section 2.3):
 *   - Subscribe to raw transaction stream
 *   - Validate JSON schemas
 *   - Impute missing values via mean substitution
 *   - Scale numerics via z-score (Amount, Time)
 *   - One-hot encode categoricals
 *   - Publish normalized transactions downstream
 *
 * Also acts as pipeline orchestrator for the end-to-end
 * POST /api/v1/pipeline/run endpoint.
 */
@SpringBootApplication
public class PreprocessingApplication {

    private static final Logger log = LoggerFactory.getLogger(PreprocessingApplication.class);

    private final Environment env;

    public PreprocessingApplication(Environment env) {
        this.env = env;
    }

    public static void main(String[] args) {
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║     XAI-Comply Preprocessing Service Starting        ║");
        log.info("╚══════════════════════════════════════════════════════╝");
        SpringApplication.run(PreprocessingApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String port = env.getProperty("server.port", "8081");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("Preprocessing Service is UP on port {}", port);
        log.info("  Endpoints:");
        log.info("    POST /api/v1/transactions        — preprocess a transaction");
        log.info("    POST /api/v1/pipeline/run        — run full XAI-Comply pipeline");
        log.info("    GET  /api/v1/pipeline/health     — check all downstream services");
        log.info("    GET  /actuator/health            — Spring health check");
        log.info("    GET  /actuator/prometheus        — Prometheus metrics");
        log.info("  Downstream services:");
        log.info("    Inference & XAI : {}", env.getProperty("services.inference.url"));
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
}
