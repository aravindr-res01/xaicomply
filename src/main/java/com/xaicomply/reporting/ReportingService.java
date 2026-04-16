package com.xaicomply.reporting;

import com.xaicomply.config.ModelConfig;
import com.xaicomply.domain.ComplianceReport;
import com.xaicomply.mapping.ComplianceFlagEvent;
import com.xaicomply.repository.ComplianceReportRepository;
import com.xaicomply.repository.TransactionRepository;
import com.xaicomply.reporting.batch.ComplianceReportJob;
import com.xaicomply.reporting.batch.CompositeReportWriter;
import com.xaicomply.reporting.batch.TransactionItemReader;
import com.xaicomply.domain.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Triggers and manages compliance report generation.
 * Listens for ComplianceFlagEvent to auto-trigger reports when flagged count threshold is reached.
 */
@Service
public class ReportingService {

    private static final Logger log = LoggerFactory.getLogger(ReportingService.class);

    private final ComplianceReportRepository complianceReportRepository;
    private final ComplianceReportJob complianceReportJob;
    private final CompositeReportWriter compositeReportWriter;
    private final TransactionItemReader transactionItemReader;
    private final JobLauncher jobLauncher;
    private final Job job;
    private final ModelConfig modelConfig;
    private final TransactionRepository transactionRepository;

    private final AtomicInteger flaggedSinceLastReport = new AtomicInteger(0);

    public ReportingService(ComplianceReportRepository complianceReportRepository,
                            ComplianceReportJob complianceReportJob,
                            CompositeReportWriter compositeReportWriter,
                            TransactionItemReader transactionItemReader,
                            JobLauncher jobLauncher,
                            Job complianceReportBatchJob,
                            ModelConfig modelConfig,
                            TransactionRepository transactionRepository) {
        this.complianceReportRepository = complianceReportRepository;
        this.complianceReportJob = complianceReportJob;
        this.compositeReportWriter = compositeReportWriter;
        this.transactionItemReader = transactionItemReader;
        this.jobLauncher = jobLauncher;
        this.job = complianceReportBatchJob;
        this.modelConfig = modelConfig;
        this.transactionRepository = transactionRepository;
    }

    /**
     * Creates a ComplianceReport entity and triggers the batch job asynchronously.
     *
     * @param period the reporting period string (e.g., "MONTHLY")
     * @param year   the year
     * @param month  the month (1-12)
     * @return the created ComplianceReport with status=QUEUED
     */
    @Transactional
    public ComplianceReport startReport(String period, int year, int month, String triggerType) {
        String periodKey = period + "-" + year + "-" + String.format("%02d", month);

        ComplianceReport report = new ComplianceReport();
        report.setPeriod(periodKey);
        report.setStatus(ComplianceReport.Status.QUEUED);
        report.setTriggerType(triggerType);
        ComplianceReport saved = complianceReportRepository.save(report);

        launchJobAsync(saved.getId(), periodKey, year, month);

        return saved;
    }

    @Async("batchExecutor")
    public void launchJobAsync(UUID reportId, String period, int year, int month) {
        try {
            // Set writer context before launching
            compositeReportWriter.setReportContext(reportId, period);

            // Update status to RUNNING
            complianceReportRepository.findById(reportId).ifPresent(r -> {
                r.setStatus(ComplianceReport.Status.RUNNING);
                complianceReportRepository.save(r);
            });

            JobParameters params = complianceReportJob.buildJobParameters(period, year, month, reportId);
            JobExecution execution = jobLauncher.run(job, params);
            log.info("Launched compliance report job: reportId={} jobExecutionId={}", reportId, execution.getId());

        } catch (Exception e) {
            log.error("Failed to launch compliance report job for reportId={}: {}", reportId, e.getMessage(), e);
            complianceReportRepository.findById(reportId).ifPresent(r -> {
                r.setStatus(ComplianceReport.Status.FAILED);
                complianceReportRepository.save(r);
            });
        }
    }

    /**
     * Auto-triggers a report when the flagged count threshold is reached.
     */
    @EventListener
    public void onComplianceFlagEvent(ComplianceFlagEvent event) {
        if (!event.isFlagged()) return;

        int count = flaggedSinceLastReport.incrementAndGet();
        int threshold = modelConfig.getReporting().getFlagsTriggerCount();

        if (count >= threshold) {
            flaggedSinceLastReport.set(0);
            log.info("Auto-triggering compliance report after {} flagged transactions", count);

            java.time.ZonedDateTime now = java.time.Instant.now().atZone(java.time.ZoneOffset.UTC);
            startReport("AUTO", now.getYear(), now.getMonthValue(), "AUTO_FLAG_THRESHOLD");
        }
    }
}
