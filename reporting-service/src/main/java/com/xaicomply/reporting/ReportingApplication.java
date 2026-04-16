package com.xaicomply.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class ReportingApplication {
    private static final Logger log = LoggerFactory.getLogger(ReportingApplication.class);
    private final Environment env;
    public ReportingApplication(Environment env) { this.env = env; }

    public static void main(String[] args) {
        log.info("╔══════════════════════════════════════════════════════╗");
        log.info("║      XAI-Comply Reporting Service Starting           ║");
        log.info("╚══════════════════════════════════════════════════════╝");
        SpringApplication.run(ReportingApplication.class, args);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("Reporting Service is UP on port {}",
                env.getProperty("server.port", "8084"));
        log.info("  POST /api/v1/records         — save compliance record");
        log.info("  GET  /api/v1/reports          — get current report summary");
        log.info("  POST /api/v1/reports/generate — trigger Spring Batch job");
        log.info("  GET  /api/v1/reports/records  — list all records");
        log.info("  GET  /h2-console              — H2 DB browser (dev only)");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
}
