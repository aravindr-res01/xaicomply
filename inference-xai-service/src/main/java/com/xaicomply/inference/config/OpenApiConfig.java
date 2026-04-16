package com.xaicomply.inference.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI config for Inference & XAI Service.
 * Swagger UI: http://localhost:8082/swagger-ui.html
 * OpenAPI JSON: http://localhost:8082/v3/api-docs
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI inferenceOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("XAI-Comply Inference & XAI Service")
                        .version("1.0.0")
                        .description("""
                                ## Inference & XAI Service
                                
                                Second stage of the XAI-Comply compliance pipeline.
                                
                                **Responsibilities (paper Section 2.3):**
                                - Load `fraud_model.onnx` via ONNX Runtime Java API
                                - Sub-50ms inference latency for ensemble models
                                - Call Python sidecar for SHAP explanations (Eq.1: f(x) = φ₀ + Σφᵢ)
                                - Call Python sidecar for LIME explanations (Eq.2: argmin[ℒ+Ω])
                                - Batch-mode: requests within 10ms window are grouped
                                
                                **Target latencies (paper Section 3.5):**
                                - SHAP: ~15ms at 100 txn/s
                                - LIME: ~30ms at 100 txn/s  
                                - Combined p95: <200ms at 500 txn/s
                                
                                **Python sidecar must be running on port 8085**
                                """)
                        .contact(new Contact().name("XAI-Comply"))
                        .license(new License().name("MIT")));
    }

    @Bean
    public GroupedOpenApi inferenceApi() {
        return GroupedOpenApi.builder()
                .group("inference")
                .displayName("ONNX Inference + XAI Explanations")
                .pathsToMatch("/api/v1/**")
                .build();
    }
}
