package com.xaicomply.mapping.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI config for Regulatory Mapping Service.
 * Swagger UI:  http://localhost:8083/swagger-ui.html
 * OpenAPI JSON: http://localhost:8083/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI mappingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("XAI-Comply Regulatory Mapping Service")
                        .version("1.0.0")
                        .description("""
                                ## Regulatory Mapping Service
                                
                                Third stage of the XAI-Comply compliance pipeline.
                                Implements the core regulatory mapping algorithm from the paper.
                                
                                **Algorithm (paper Eq.3):**
                                ```
                                R = Σᵢ ωᵢ |φᵢ|
                                ```
                                Where:
                                - ωᵢ = expert-defined regulatory weight (see `/api/v1/weights`)
                                - φᵢ = SHAP attribution for feature i
                                - R   = scalar compliance risk score
                                
                                **Compliance thresholds:**
                                - τ_review    = 0.20 → triggers REVIEW status
                                - τ_violation = 0.35 → triggers VIOLATION status
                                
                                **Regulatory taxonomies supported:**
                                - AML_STRUCTURING (Amount feature)
                                - BEHAVIORAL_ANOMALY (V1, V2 features)
                                - TEMPORAL_VELOCITY (Time feature)
                                - PCA_ANOMALY (V3-V28 features)
                                
                                **Paper results (Section 3.3):**
                                Conservative τ=0.35 → Precision=0.87, Recall=0.03,
                                70% reduction in manual review workload vs baseline.
                                """)
                        .contact(new Contact().name("XAI-Comply"))
                        .license(new License().name("MIT")));
    }

    @Bean
    public GroupedOpenApi mappingApi() {
        return GroupedOpenApi.builder()
                .group("mapping")
                .displayName("Regulatory Mapping Algorithm")
                .pathsToMatch("/api/v1/**")
                .build();
    }
}
