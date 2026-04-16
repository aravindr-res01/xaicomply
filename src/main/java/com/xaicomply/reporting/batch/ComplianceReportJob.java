package com.xaicomply.reporting.batch;

import com.xaicomply.domain.ComplianceReport;
import com.xaicomply.domain.Transaction;
import com.xaicomply.repository.ComplianceReportRepository;
import jakarta.persistence.EntityManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.UUID;

/**
 * Spring Batch compliance report job.
 * NOT auto-started; triggered via REST API or ComplianceFlagEvent.
 */
@Configuration
public class ComplianceReportJob {

    private static final Logger log = LoggerFactory.getLogger(ComplianceReportJob.class);

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;
    private final EntityManagerFactory entityManagerFactory;
    private final TransactionItemReader transactionItemReader;
    private final ReportRecordProcessor reportRecordProcessor;
    private final CompositeReportWriter compositeReportWriter;
    private final ComplianceReportRepository complianceReportRepository;

    public ComplianceReportJob(JobRepository jobRepository,
                               PlatformTransactionManager transactionManager,
                               EntityManagerFactory entityManagerFactory,
                               TransactionItemReader transactionItemReader,
                               ReportRecordProcessor reportRecordProcessor,
                               CompositeReportWriter compositeReportWriter,
                               ComplianceReportRepository complianceReportRepository) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
        this.entityManagerFactory = entityManagerFactory;
        this.transactionItemReader = transactionItemReader;
        this.reportRecordProcessor = reportRecordProcessor;
        this.compositeReportWriter = compositeReportWriter;
        this.complianceReportRepository = complianceReportRepository;
    }

    @Bean
    public Job complianceReportBatchJob() {
        return new JobBuilder("complianceReportJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .start(complianceReportStep())
                .listener(new JobExecutionListener() {
                    @Override
                    public void afterJob(JobExecution jobExecution) {
                        compositeReportWriter.finalizeReport();
                        log.info("Compliance report job completed with status: {}", jobExecution.getStatus());
                    }
                })
                .build();
    }

    @Bean
    public Step complianceReportStep() {
        return new StepBuilder("complianceReportStep", jobRepository)
                .<Transaction, ReportRecord>chunk(100, transactionManager)
                .reader(dummyReader())  // replaced at runtime via ReportingService
                .processor(reportRecordProcessor)
                .writer(compositeReportWriter)
                .build();
    }

    /**
     * Default reader — replaced at runtime with parameterized reader.
     */
    private JpaPagingItemReader<Transaction> dummyReader() {
        return transactionItemReader.createReader(
                Instant.now().atZone(java.time.ZoneOffset.UTC).getYear(),
                Instant.now().atZone(java.time.ZoneOffset.UTC).getMonthValue()
        );
    }

    /**
     * Launches the job with specified parameters.
     */
    public JobParameters buildJobParameters(String period, int year, int month, UUID reportId) {
        return new JobParametersBuilder()
                .addString("period", period)
                .addLong("year", (long) year)
                .addLong("month", (long) month)
                .addString("reportId", reportId.toString())
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();
    }
}
