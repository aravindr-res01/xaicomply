package com.xaicomply;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * XAI-Comply: Explainable AI Compliance Framework for FinTech
 *
 * Implements the framework from:
 * "Explainable AI for Automated Compliance and Regulatory Reporting in FinTech"
 *
 * Key equations implemented:
 * - Eq.1 SHAP: f(x) = phi0 + sum(phi_i)
 * - Eq.2 LIME: argmin_g[L(f,g,pi_x) + Omega(g)]
 * - Eq.3 Risk: R = sum(omega_i * |phi_i|)
 *
 * Run: mvn spring-boot:run
 * API docs: http://localhost:8080/actuator/health
 * H2 console: http://localhost:8080/h2-console
 */
@SpringBootApplication
public class XaiComplyApplication {

    private static final Logger log = LoggerFactory.getLogger(XaiComplyApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(XaiComplyApplication.class, args);
        log.info("=== XAI-Comply started successfully ===");
        log.info("API: http://localhost:8080/api/v1");
        log.info("H2 Console: http://localhost:8080/h2-console");
        log.info("Health: http://localhost:8080/actuator/health");
        log.info("Default API key: dev-api-key-xai-comply-2024");
    }
}
