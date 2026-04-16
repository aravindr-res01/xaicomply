package com.xaicomply.preprocessing.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.examples.Example;
import io.swagger.v3.oas.models.info.Info;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Swagger/OpenAPI 3.0 configuration for the Preprocessing Service.
 *
 * Swagger UI:  http://localhost:8081/swagger-ui.html
 * OpenAPI JSON: http://localhost:8081/v3/api-docs
 *
 * Paper reference: Section 2.3 — Preprocessing Service responsibilities:
 *   "validates JSON schemas, imputes missing values, z-score scales numerics,
 *    one-hot encodes categoricals, publishes to Feature Store"
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI preprocessingOpenAPI() {
        return new OpenAPI()
                .info(new io.swagger.v3.oas.models.info.Info()
                        .title("XAI-Comply Preprocessing Service")
                        .version("1.0.0")
                        .description("""
                                ## Preprocessing Service
                                
                                First stage of the XAI-Comply compliance pipeline.
                                
                                **Responsibilities (paper Section 2.3):**
                                - Validate incoming transaction JSON schemas
                                - Impute missing values via mean substitution
                                - Z-score normalize `Amount` and `Time` features
                                - Build 30-element feature vector for ONNX model
                                - Orchestrate full 4-stage pipeline via `/pipeline/run`
                                
                                **Paper:** *Explainable AI for Automated Compliance and Regulatory
                                Reporting in FinTech: A Java Spring Boot Microservices Framework*
                                (Raghu, 2024)
                                
                                **Dataset:** Kaggle Credit Card Fraud Detection
                                (284,807 transactions, V1-V28 PCA features + Amount + Time)
                                
                                ### Quick Start
                                1. POST `/api/v1/pipeline/run` with a transaction JSON
                                2. Receives full pipeline response with SHAP + LIME explanations
                                   and compliance decision (COMPLIANT / REVIEW / VIOLATION)
                                """)
                        .contact(new io.swagger.v3.oas.models.info.Contact()
                                .name("XAI-Comply")
                                .url("https://github.com/your-repo/xai-comply"))
                        .license(new io.swagger.v3.oas.models.info.License()
                                .name("MIT")
                                .url("https://opensource.org/licenses/MIT")));
    }

    @Bean
    public GroupedOpenApi pipelineApi() {
        return GroupedOpenApi.builder()
                .group("pipeline")
                .displayName("Pipeline Orchestration")
                .pathsToMatch("/api/v1/pipeline/**")
                .build();
    }

    @Bean
    public GroupedOpenApi transactionApi() {
        return GroupedOpenApi.builder()
                .group("transactions")
                .displayName("Transaction Preprocessing")
                .pathsToMatch("/api/v1/transactions/**")
                .build();
    }
}
