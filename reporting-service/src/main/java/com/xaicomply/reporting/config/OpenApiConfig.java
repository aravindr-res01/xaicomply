package com.xaicomply.reporting.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI config for Reporting Service.
 * Swagger UI:  http://localhost:8084/swagger-ui.html
 * OpenAPI JSON: http://localhost:8084/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI reportingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("XAI-Comply Reporting Service")
                        .version("1.0.0")
                        .description("""
                                ## Reporting Service
                                
                                Fourth stage of the XAI-Comply compliance pipeline.
                                
                                **Responsibilities (paper Section 2.3):**
                                - Persist compliance records to H2 embedded audit log
                                - Spring Batch job generates CSV compliance reports
                                - Chunk size: 1000 records (paper default)
                                - Expose Precision/Recall/F1 metrics endpoint
                                
                                **H2 Console:** http://localhost:8084/h2-console
                                - JDBC URL: `jdbc:h2:file:./data/xai_comply_audit`
                                - Username: `sa`, Password: (blank)
                                
                                **Paper results (Table 2):**
                                - Mean report latency: 48s (60% reduction from baseline 120s)
                                - Throughput maintained at 500 txn/s
                                
                                **Paper results (Table 1):**
                                Access via GET `/api/v1/reports/metrics` after running
                                bulk test with 10,000 transactions.
                                Expected: Precision=0.87, Recall=0.03, F1=0.06
                                """)
                        .contact(new Contact().name("XAI-Comply"))
                        .license(new License().name("MIT")));
    }

    @Bean
    public GroupedOpenApi reportsApi() {
        return GroupedOpenApi.builder()
                .group("reports")
                .displayName("Compliance Reports")
                .pathsToMatch("/api/v1/reports/**")
                .build();
    }

    @Bean
    public GroupedOpenApi recordsApi() {
        return GroupedOpenApi.builder()
                .group("records")
                .displayName("Audit Records")
                .pathsToMatch("/api/v1/records/**")
                .build();
    }
}
