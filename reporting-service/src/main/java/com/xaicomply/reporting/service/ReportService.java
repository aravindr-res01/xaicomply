package com.xaicomply.reporting.service;

import com.xaicomply.common.dto.ComplianceReportDTO;
import com.xaicomply.common.dto.MappingResultDTO;
import com.xaicomply.common.enums.ComplianceStatus;
import com.xaicomply.common.enums.RiskLevel;
import com.xaicomply.common.exception.XaiComplyException;
import com.xaicomply.reporting.entity.ComplianceRecord;
import com.xaicomply.reporting.repository.ComplianceRecordRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.batch.core.*;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobExecutionAlreadyRunningException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Core business logic for the Reporting Service.
 *
 * Responsibilities:
 *   1. Persist incoming MappingResultDTOs to H2 audit log
 *   2. Trigger Spring Batch compliance report generation job
 *   3. Query aggregate statistics for dashboard/API responses
 *   4. Compute evaluation metrics (Precision, Recall, F1) when true labels available
 *
 * Paper Section 3.4 (Table 2):
 *   Baseline mean latency: 120s
 *   XAI-Comply mean latency: 48s (60% reduction)
 *   Throughput: 500 txn/s
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    private final ComplianceRecordRepository repository;
    private final JobLauncher jobLauncher;
    private final Job complianceReportJob;

    private final Counter recordSavedCounter;
    private final Counter reportGeneratedCounter;
    private final Counter duplicateSkippedCounter;

    public ReportService(ComplianceRecordRepository repository,
                          JobLauncher jobLauncher,
                          Job complianceReportJob,
                          MeterRegistry meterRegistry) {
        this.repository          = repository;
        this.jobLauncher         = jobLauncher;
        this.complianceReportJob = complianceReportJob;

        this.recordSavedCounter = Counter.builder("xaicomply_reporting_records_saved_total")
                .register(meterRegistry);
        this.reportGeneratedCounter = Counter.builder("xaicomply_reporting_reports_generated_total")
                .register(meterRegistry);
        this.duplicateSkippedCounter = Counter.builder("xaicomply_reporting_duplicates_skipped_total")
                .register(meterRegistry);
    }

    // ─── Save incoming mapping result ─────────────────────────────────────────

    /**
     * Persist a MappingResultDTO to the H2 audit log.
     * Called after every successful regulatory mapping decision.
     *
     * @param mappingResult compliance decision from Regulatory Mapping Service
     */
    @Transactional
    public ComplianceRecord saveRecord(MappingResultDTO mappingResult) {
        String txId = mappingResult.getTransactionId();
        MDC.put("transactionId", txId);

        log.info("[ReportService] Saving compliance record txId={} status={} R={}",
                txId, mappingResult.getComplianceStatus(),
                mappingResult.getRegulatoryRiskScore());

        // Idempotency: skip if already recorded
        if (repository.existsByTransactionId(txId)) {
            log.warn("[ReportService] Duplicate txId={} — skipping save (idempotent)", txId);
            duplicateSkippedCounter.increment();
            MDC.remove("transactionId");
            return repository.findByTransactionId(txId).orElseThrow();
        }

        // Build entity
        String topFeature = mappingResult.getTopRiskFeatures() != null
                && !mappingResult.getTopRiskFeatures().isEmpty()
                ? mappingResult.getTopRiskFeatures().get(0) : null;

        ComplianceRecord record = ComplianceRecord.builder()
                .transactionId(txId)
                .regulatoryRiskScore(mappingResult.getRegulatoryRiskScore())
                .modelRiskScore(mappingResult.getModelRiskScore() != null
                        ? mappingResult.getModelRiskScore() : 0.0)
                .threshold(mappingResult.getThreshold())
                .flagged(mappingResult.getFlagged())
                .complianceStatus(mappingResult.getComplianceStatus())
                .riskLevel(mappingResult.getRiskLevel())
                .topRiskFeature(topFeature)
                .regulatoryCategory(mappingResult.getRegulatoryCategory())
                .weightMatrixVersion(mappingResult.getWeightMatrixVersion())
                .trueLabel(mappingResult.getTrueLabel())
                .mappingTimestamp(mappingResult.getMappingTimestamp())
                .createdAt(Instant.now())
                .reportGenerated(false)
                .build();

        ComplianceRecord saved = repository.save(record);
        recordSavedCounter.increment();

        log.info("[ReportService] Record saved id={} txId={} flagged={}",
                saved.getId(), txId, saved.getFlagged());
        log.debug("[ReportService] Record details: status={} riskLevel={} "
                        + "topFeature={} category={}",
                saved.getComplianceStatus(), saved.getRiskLevel(),
                saved.getTopRiskFeature(), saved.getRegulatoryCategory());

        MDC.remove("transactionId");
        return saved;
    }

    // ─── Trigger batch report generation ─────────────────────────────────────

    /**
     * Trigger the Spring Batch complianceReportJob.
     * Reads all un-reported records and writes a CSV compliance report.
     *
     * @return report summary DTO
     */
    public ComplianceReportDTO generateReport() {
        log.info("[ReportService] ═══════════════════════════════════════════");
        log.info("[ReportService] Triggering complianceReportJob...");

        long pendingCount = repository.findByReportGeneratedFalseOrderByCreatedAtAsc().size();
        log.info("[ReportService] Un-reported records pending: {}", pendingCount);

        if (pendingCount == 0) {
            log.info("[ReportService] No pending records — skipping job trigger");
            return buildSummaryReport(List.of());
        }

        long start = System.currentTimeMillis();

        try {
            JobParameters params = new JobParametersBuilder()
                    .addLong("run.id", System.currentTimeMillis())
                    .addString("generated.by", "ReportService")
                    .toJobParameters();

            log.info("[ReportService] Launching job with params: {}", params);
            JobExecution execution = jobLauncher.run(complianceReportJob, params);

            long elapsed = System.currentTimeMillis() - start;
            log.info("[ReportService] Job launched: id={} status={} durationMs={}",
                    execution.getJobId(), execution.getStatus(), elapsed);

            if (elapsed > 48_000) {
                log.warn("[ReportService] Report generation {}ms exceeds paper target of 48s", elapsed);
            }

            reportGeneratedCounter.increment();

        } catch (JobExecutionAlreadyRunningException e) {
            log.warn("[ReportService] Job already running — skipping duplicate trigger");
        } catch (Exception e) {
            log.error("[ReportService] Failed to launch report job: {}", e.getMessage(), e);
            throw new XaiComplyException.ReportingException(
                    "Failed to launch compliance report job: " + e.getMessage(), e);
        }

        log.info("[ReportService] ═══════════════════════════════════════════");
        return buildCurrentSummary();
    }

    // ─── Summary statistics ───────────────────────────────────────────────────

    /**
     * Build a ComplianceReportDTO from current H2 state.
     * Used for dashboard / API response.
     */
    public ComplianceReportDTO buildCurrentSummary() {
        log.info("[ReportService] Building current compliance summary...");
        List<ComplianceRecord> all = repository.findAll();
        return buildSummaryReport(all);
    }

    private ComplianceReportDTO buildSummaryReport(List<ComplianceRecord> records) {
        long total     = records.size();
        long flagged   = records.stream().filter(r -> Boolean.TRUE.equals(r.getFlagged())).count();
        long violation = records.stream().filter(r -> r.getComplianceStatus() == ComplianceStatus.VIOLATION).count();
        long review    = records.stream().filter(r -> r.getComplianceStatus() == ComplianceStatus.REVIEW).count();
        long compliant = records.stream().filter(r -> r.getComplianceStatus() == ComplianceStatus.COMPLIANT).count();

        double avgRisk = records.stream().mapToDouble(ComplianceRecord::getRegulatoryRiskScore)
                .average().orElse(0.0);
        double maxRisk = records.stream().mapToDouble(ComplianceRecord::getRegulatoryRiskScore)
                .max().orElse(0.0);

        double flaggedRate = total > 0 ? (double) flagged / total : 0.0;

        // Top risk features across flagged transactions
        List<String> topFeatures = records.stream()
                .filter(r -> Boolean.TRUE.equals(r.getFlagged()) && r.getTopRiskFeature() != null)
                .collect(Collectors.groupingBy(ComplianceRecord::getTopRiskFeature,
                        Collectors.counting()))
                .entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(5)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());

        // Status breakdown
        Map<ComplianceStatus, Integer> statusBreakdown = new LinkedHashMap<>();
        statusBreakdown.put(ComplianceStatus.COMPLIANT, (int) compliant);
        statusBreakdown.put(ComplianceStatus.REVIEW,    (int) review);
        statusBreakdown.put(ComplianceStatus.VIOLATION, (int) violation);

        // Evaluation metrics
        logEvaluationMetrics(records);

        String reportId = "RPT-" + System.currentTimeMillis();
        log.info("[ReportService] Summary: reportId={} total={} flagged={} ({}%) "
                        + "violations={} avgRisk={}",
                reportId, total, flagged, flaggedRate * 100, violation, avgRisk);

        return ComplianceReportDTO.builder()
                .reportId(reportId)
                .generatedAt(Instant.now())
                .totalTransactions((int) total)
                .flaggedTransactions((int) flagged)
                .violationCount((int) violation)
                .reviewCount((int) review)
                .compliantCount((int) compliant)
                .flaggedRate(flaggedRate)
                .averageRiskScore(avgRisk)
                .maxRiskScore(maxRisk)
                .topRiskFeatures(topFeatures)
                .statusBreakdown(statusBreakdown)
                .build();
    }

    /**
     * Compute and log Precision, Recall, F1 against true labels.
     * Paper Table 1: Baseline Precision=0.84, F1=0.15 vs XAI-Comply Precision=0.87, F1=0.06
     */
    private void logEvaluationMetrics(List<ComplianceRecord> records) {
        List<ComplianceRecord> labeled = records.stream()
                .filter(r -> r.getTrueLabel() != null)
                .collect(Collectors.toList());

        if (labeled.isEmpty()) {
            log.debug("[ReportService] No labeled records — skipping evaluation metrics");
            return;
        }

        long tp = labeled.stream().filter(r -> r.getTrueLabel() == 1 && Boolean.TRUE.equals(r.getFlagged())).count();
        long fp = labeled.stream().filter(r -> r.getTrueLabel() == 0 && Boolean.TRUE.equals(r.getFlagged())).count();
        long fn = labeled.stream().filter(r -> r.getTrueLabel() == 1 && !Boolean.TRUE.equals(r.getFlagged())).count();

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
        double recall    = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
        double f1        = (precision + recall) > 0
                ? 2 * precision * recall / (precision + recall) : 0.0;

        log.info("[ReportService] ── Evaluation Metrics (labeled={}) ──", labeled.size());
        log.info("[ReportService]   TP={} FP={} FN={}", tp, fp, fn);
        log.info("[ReportService]   Precision: {}  (paper XAI-Comply: 0.87)", precision);
        log.info("[ReportService]   Recall:    {}  (paper XAI-Comply: 0.03)", recall);
        log.info("[ReportService]   F1-Score:  {}  (paper XAI-Comply: 0.06)", f1);
        log.info("[ReportService] ───────────────────────────────────────────");
    }

    /**
     * Count records not yet included in a batch report.
     * Exposed for monitoring.
     */
    public long countPendingRecords() {
        return repository.findByReportGeneratedFalseOrderByCreatedAtAsc().size();
    }


}
