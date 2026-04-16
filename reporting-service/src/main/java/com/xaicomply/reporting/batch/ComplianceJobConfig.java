package com.xaicomply.reporting.batch;

import com.xaicomply.common.dto.ComplianceReportDTO;
import com.xaicomply.common.dto.MappingResultDTO;
import com.xaicomply.common.enums.ComplianceStatus;
import com.xaicomply.reporting.entity.ComplianceRecord;
import com.xaicomply.reporting.repository.ComplianceRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Spring Batch configuration for compliance report generation.
 *
 * Paper Section 2.3:
 *   "automated audit-ready report generation using Spring Batch"
 *   "60% reduction in report generation delay" (Table 2: 120s → 48s)
 *
 * Job: complianceReportJob
 * Step: generateReportStep
 *   Reader    → reads all un-reported ComplianceRecords from H2
 *   Processor → computes aggregate statistics
 *   Writer    → writes CSV/JSON report to local filesystem (replaces S3)
 *
 * Chunk size: 1000 records (paper: "default 1000 rows per batch")
 */
@Configuration
public class ComplianceJobConfig {

    private static final Logger log = LoggerFactory.getLogger(ComplianceJobConfig.class);

    private static final int CHUNK_SIZE = 1000;

    @Value("${reporting.output.dir:reports}")
    private String outputDir;

    private final ComplianceRecordRepository repository;
    private final MeterRegistry meterRegistry;

    public ComplianceJobConfig(ComplianceRecordRepository repository,
                                MeterRegistry meterRegistry) {
        this.repository = repository;
        this.meterRegistry = meterRegistry;
    }

    // ── Job ───────────────────────────────────────────────────────────────────

    @Bean
    public Job complianceReportJob(JobRepository jobRepository,
                                    Step generateReportStep) {
        log.info("[BatchConfig] Configuring complianceReportJob");
        return new JobBuilder("complianceReportJob", jobRepository)
                .incrementer(new RunIdIncrementer())
                .listener(jobExecutionListener())
                .start(generateReportStep)
                .build();
    }

    // ── Step ──────────────────────────────────────────────────────────────────

    @Bean
    public Step generateReportStep(JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager,
                                    ItemReader<ComplianceRecord> reportItemReader,
                                    ItemProcessor<ComplianceRecord, ComplianceRecord> reportItemProcessor,
                                    ItemWriter<ComplianceRecord> reportItemWriter) {
        log.info("[BatchConfig] Configuring generateReportStep chunkSize={}", CHUNK_SIZE);
        return new StepBuilder("generateReportStep", jobRepository)
                .<ComplianceRecord, ComplianceRecord>chunk(CHUNK_SIZE, transactionManager)
                .reader(reportItemReader)
                .processor(reportItemProcessor)
                .writer(reportItemWriter)
                .listener(stepExecutionListener())
                .build();
    }

    // ── Reader ────────────────────────────────────────────────────────────────

    @Bean
    @StepScope
    public ItemReader<ComplianceRecord> reportItemReader() {
        log.info("[BatchReader] Loading un-reported ComplianceRecords from H2...");
        List<ComplianceRecord> records = repository.findByReportGeneratedFalseOrderByCreatedAtAsc();
        log.info("[BatchReader] Found {} un-reported records to process", records.size());
        return new ListItemReader<>(records);
    }

    // ── Processor ─────────────────────────────────────────────────────────────

    @Bean
    @StepScope
    public ItemProcessor<ComplianceRecord, ComplianceRecord> reportItemProcessor() {
        return record -> {
            log.debug("[BatchProcessor] Processing record txId={} status={}",
                    record.getTransactionId(), record.getComplianceStatus());

            // Compute correctPrediction if true label available
            if (record.getTrueLabel() != null) {
                boolean predicted = Boolean.TRUE.equals(record.getFlagged());
                boolean actual    = record.getTrueLabel() == 1;
                record.setCorrectPrediction(predicted == actual);
                log.debug("[BatchProcessor] txId={} trueLabel={} flagged={} correct={}",
                        record.getTransactionId(), record.getTrueLabel(),
                        record.getFlagged(), record.getCorrectPrediction());
            }
            return record;
        };
    }

    // ── Writer ────────────────────────────────────────────────────────────────

    @Bean
    @StepScope
    public ItemWriter<ComplianceRecord> reportItemWriter() {
        return chunk -> {
            List<? extends ComplianceRecord> records = chunk.getItems();
            log.info("[BatchWriter] Writing chunk of {} records", records.size());

            if (records.isEmpty()) {
                log.info("[BatchWriter] No records in chunk — skipping");
                return;
            }

            // Compute aggregate stats for this chunk
            long totalRecords   = records.size();
            long flaggedCount   = records.stream().filter(r -> Boolean.TRUE.equals(r.getFlagged())).count();
            long violationCount = records.stream().filter(r -> r.getComplianceStatus() == ComplianceStatus.VIOLATION).count();
            long reviewCount    = records.stream().filter(r -> r.getComplianceStatus() == ComplianceStatus.REVIEW).count();
            long compliantCount = records.stream().filter(r -> r.getComplianceStatus() == ComplianceStatus.COMPLIANT).count();
            double avgRisk      = records.stream().mapToDouble(ComplianceRecord::getRegulatoryRiskScore).average().orElse(0.0);
            double maxRisk      = records.stream().mapToDouble(ComplianceRecord::getRegulatoryRiskScore).max().orElse(0.0);

            log.info("[BatchWriter] Chunk stats: total={} flagged={} violations={} reviews={} "
                            + "compliant={} avgRisk={} maxRisk={}",
                    totalRecords, flaggedCount, violationCount, reviewCount,
                    compliantCount, avgRisk, maxRisk);

            // Write CSV report
            String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
                    .withZone(ZoneId.systemDefault())
                    .format(Instant.now());

            writeCsvReport(records, timestamp, totalRecords, flaggedCount,
                    violationCount, avgRisk);

            // Prometheus metrics
            Counter.builder("xaicomply_batch_records_written_total")
                    .register(meterRegistry)
                    .increment(totalRecords);

            log.info("[BatchWriter] Chunk write complete: {} records → reports/compliance_{}*.csv",
                    totalRecords, timestamp);
        };
    }

    // ── CSV Report Writer ─────────────────────────────────────────────────────

    private void writeCsvReport(List<? extends ComplianceRecord> records,
                                  String timestamp,
                                  long total, long flagged,
                                  long violations, double avgRisk) {
        try {
            Path dir = Paths.get(outputDir);
            Files.createDirectories(dir);
            String filename = String.format("%s/compliance_%s.csv", outputDir, timestamp);

            log.info("[BatchWriter] Writing CSV report: {}", filename);
            long writeStart = System.currentTimeMillis();

            try (FileWriter fw = new FileWriter(filename)) {
                // Header
                fw.write("# XAI-Comply Compliance Report\n");
                fw.write(String.format("# Generated: %s\n", Instant.now()));
                fw.write(String.format("# Total: %d | Flagged: %d | Violations: %d | AvgRisk: %.4f\n",
                        total, flagged, violations, avgRisk));
                fw.write("#\n");
                fw.write("transactionId,regulatoryRiskScore,modelRiskScore,complianceStatus,"
                        + "riskLevel,flagged,topRiskFeature,regulatoryCategory,"
                        + "trueLabel,correctPrediction,createdAt\n");

                // Data rows
                for (ComplianceRecord r : records) {
                    fw.write(String.format("%s,%.4f,%.4f,%s,%s,%s,%s,%s,%s,%s,%s\n",
                            r.getTransactionId(),
                            r.getRegulatoryRiskScore(),
                            r.getModelRiskScore(),
                            r.getComplianceStatus(),
                            r.getRiskLevel(),
                            r.getFlagged(),
                            nvl(r.getTopRiskFeature()),
                            nvl(r.getRegulatoryCategory()),
                            nvl(r.getTrueLabel()),
                            nvl(r.getCorrectPrediction()),
                            r.getCreatedAt()
                    ));
                }
            }

            long writeMs = System.currentTimeMillis() - writeStart;
            long sizeKb  = Files.size(Paths.get(filename)) / 1024;
            log.info("[BatchWriter] CSV written: {} ({} KB) in {}ms", filename, sizeKb, writeMs);

            if (writeMs > 5000) {
                log.warn("[BatchWriter] CSV write took {}ms — consider async I/O for large reports",
                        writeMs);
            }

        } catch (IOException e) {
            log.error("[BatchWriter] Failed to write CSV report: {}", e.getMessage(), e);
            throw new RuntimeException("Report write failed: " + e.getMessage(), e);
        }
    }

    private String nvl(Object val) {
        return val != null ? val.toString() : "";
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    @Bean
    public JobExecutionListener jobExecutionListener() {
        return new JobExecutionListener() {
            @Override
            public void beforeJob(JobExecution jobExecution) {
                log.info("[BatchJob] ══════════════════════════════════════════");
                log.info("[BatchJob] START complianceReportJob id={}",
                        jobExecution.getJobId());
                log.info("[BatchJob] Parameters: {}", jobExecution.getJobParameters());
                Timer.builder("xaicomply_batch_job_duration_ms")
                        .register(meterRegistry);
            }

            @Override
            public void afterJob(JobExecution jobExecution) {
                BatchStatus status = jobExecution.getStatus();
                long durationMs = jobExecution.getEndTime() != null
                        ? jobExecution.getEndTime().toEpochMilli()
                          - jobExecution.getStartTime().toEpochMilli()
                        : -1;

                log.info("[BatchJob] COMPLETE complianceReportJob id={} status={} durationMs={}",
                        jobExecution.getJobId(), status, durationMs);

                if (status == BatchStatus.COMPLETED) {
                    log.info("[BatchJob] ✅ Report generation successful in {}ms "
                            + "(paper target: 48s mean latency)", durationMs);
                    // Mark records as reported
                    int updated = repository.markAllAsReported(jobExecution.getJobId());
                    log.info("[BatchJob] Marked {} records as reported", updated);
                } else {
                    log.error("[BatchJob] ❌ Job failed with status={} exceptions={}",
                            status, jobExecution.getAllFailureExceptions());
                }
                log.info("[BatchJob] ══════════════════════════════════════════");
            }
        };
    }

    @Bean
    public StepExecutionListener stepExecutionListener() {
        return new StepExecutionListener() {
            @Override
            public void beforeStep(StepExecution stepExecution) {
                log.info("[BatchStep] START step={} jobId={}",
                        stepExecution.getStepName(),
                        stepExecution.getJobExecutionId());
            }

            @Override
            public ExitStatus afterStep(StepExecution stepExecution) {
                log.info("[BatchStep] DONE step={} readCount={} writeCount={} "
                                + "skipCount={} commitCount={} status={}",
                        stepExecution.getStepName(),
                        stepExecution.getReadCount(),
                        stepExecution.getWriteCount(),
                        stepExecution.getSkipCount(),
                        stepExecution.getCommitCount(),
                        stepExecution.getStatus());
                return stepExecution.getExitStatus();
            }
        };
    }
}
