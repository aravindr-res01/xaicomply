package com.xaicomply.config;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Batch configuration.
 * Jobs are NOT auto-started (spring.batch.job.enabled=false in application.yml).
 * Jobs are triggered manually via REST API or via ComplianceFlagEvent.
 */
@Configuration
@EnableBatchProcessing(dataSourceRef = "dataSource", transactionManagerRef = "transactionManager")
public class BatchConfig {
    // Spring Boot 3.x auto-configures batch datasource and transaction manager
    // when @EnableBatchProcessing is present with explicit references.
    // All job beans are defined in ComplianceReportJob.
}
