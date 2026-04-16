package com.xaicomply.mapping;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class MappingApplication {
    private static final Logger log = LoggerFactory.getLogger(MappingApplication.class);
    private final Environment env;
    public MappingApplication(Environment env) { this.env = env; }

    public static void main(String[] args) {
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║   XAI-Comply Regulatory Mapping Service Starting     ║");
        log.info("╚══════════════════════════════════════════════════════╝");
        SpringApplication.run(MappingApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("Regulatory Mapping Service is UP on port {}",
                env.getProperty("server.port", "8083"));
        log.info("  POST /api/v1/map         — apply Eq.3 R = Σ ωᵢ|φᵢ|");
        log.info("  GET  /api/v1/weights     — view current weight matrix ω");
        log.info("  τ_violation = {}", env.getProperty("mapping.threshold.tau", "0.35"));
        log.info("  τ_review    = {}", env.getProperty("mapping.threshold.review", "0.20"));
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
}
