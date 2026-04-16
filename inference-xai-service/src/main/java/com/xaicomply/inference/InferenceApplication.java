package com.xaicomply.inference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class InferenceApplication {
    private static final Logger log = LoggerFactory.getLogger(InferenceApplication.class);
    private final Environment env;
    public InferenceApplication(Environment env) { this.env = env; }

    public static void main(String[] args) {
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║    XAI-Comply Inference & XAI Service Starting       ║");
        log.info("╚══════════════════════════════════════════════════════╝");
        SpringApplication.run(InferenceApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        String port = env.getProperty("server.port", "8082");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("Inference & XAI Service is UP on port {}", port);
        log.info("  POST /api/v1/predict  — ONNX inference + SHAP + LIME");
        log.info("  GET  /api/v1/model/info — ONNX model metadata");
        log.info("  Python sidecar: {}", env.getProperty("services.python-explainer.url"));
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
}
