package com.xaicomply.reporting.controller;

import com.xaicomply.common.dto.ComplianceReportDTO;
import com.xaicomply.common.dto.MappingResultDTO;
import com.xaicomply.reporting.entity.ComplianceRecord;
import com.xaicomply.reporting.repository.ComplianceRecordRepository;
import com.xaicomply.reporting.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for the Reporting Service.
 *
 * Endpoints:
 *   POST /api/v1/records           — save a MappingResultDTO to audit log
 *   GET  /api/v1/reports           — current summary report
 *   POST /api/v1/reports/generate  — trigger Spring Batch report job
 *   GET  /api/v1/reports/records   — paginated list of all records
 *   GET  /api/v1/reports/flagged   — flagged records only
 *   GET  /api/v1/reports/metrics   — precision/recall/F1
 *   GET  /api/v1/reports/pending   — count of un-reported records
 */
@RestController
@RequestMapping("/api/v1")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportService reportService;
    private final ComplianceRecordRepository repository;

    public ReportController(ReportService reportService,
                             ComplianceRecordRepository repository) {
        this.reportService = reportService;
        this.repository    = repository;
    }

    // ── Save record ──────────────────────────────────────────────────────────

    @PostMapping("/records")
    public ResponseEntity<Map<String, Object>> saveRecord(
            @RequestBody MappingResultDTO mappingResult) {

        String txId = mappingResult.getTransactionId();
        MDC.put("transactionId", txId);
        log.info("[ReportController] POST /api/v1/records txId={} status={}",
                txId, mappingResult.getComplianceStatus());

        try {
            ComplianceRecord saved = reportService.saveRecord(mappingResult);
            return ResponseEntity.ok(Map.of(
                    "id",              saved.getId(),
                    "transactionId",   saved.getTransactionId(),
                    "complianceStatus", saved.getComplianceStatus().name(),
                    "flagged",         saved.getFlagged(),
                    "saved",           true
            ));
        } finally {
            MDC.remove("transactionId");
        }
    }

    // ── Reports ───────────────────────────────────────────────────────────────

    @GetMapping("/reports")
    public ResponseEntity<ComplianceReportDTO> getCurrentReport() {
        log.info("[ReportController] GET /api/v1/reports");
        ComplianceReportDTO report = reportService.buildCurrentSummary();
        log.info("[ReportController] Summary: total={} flagged={} violations={}",
                report.getTotalTransactions(),
                report.getFlaggedTransactions(),
                report.getViolationCount());
        return ResponseEntity.ok(report);
    }

    @PostMapping("/reports/generate")
    public ResponseEntity<ComplianceReportDTO> generateReport() {
        log.info("[ReportController] POST /api/v1/reports/generate — triggering Spring Batch job");
        long start = System.currentTimeMillis();
        ComplianceReportDTO report = reportService.generateReport();
        log.info("[ReportController] Report generated in {}ms reportId={}",
                System.currentTimeMillis() - start, report.getReportId());
        return ResponseEntity.ok(report);
    }

    @GetMapping("/reports/records")
    public ResponseEntity<List<ComplianceRecord>> getAllRecords(
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "50") int size) {
        log.info("[ReportController] GET /api/v1/reports/records page={} size={}", page, size);
        List<ComplianceRecord> records = repository
                .findAll(PageRequest.of(page, size, Sort.by("createdAt").descending()))
                .getContent();
        log.info("[ReportController] Returning {} records", records.size());
        return ResponseEntity.ok(records);
    }

    @GetMapping("/reports/flagged")
    public ResponseEntity<List<ComplianceRecord>> getFlaggedRecords() {
        log.info("[ReportController] GET /api/v1/reports/flagged");
        List<ComplianceRecord> flagged = repository.findByFlaggedTrue();
        log.info("[ReportController] Returning {} flagged records", flagged.size());
        return ResponseEntity.ok(flagged);
    }

    @GetMapping("/reports/metrics")
    public ResponseEntity<Map<String, Object>> getMetrics() {
        log.info("[ReportController] GET /api/v1/reports/metrics");

        long total     = repository.count();
        long flagged   = repository.countByFlaggedTrue();
        long tp        = repository.countTruePositives();
        long fp        = repository.countFalsePositives();
        long fn        = repository.countFalseNegatives();
        double avgRisk = repository.findAverageRegulatoryRiskScore() != null
                ? repository.findAverageRegulatoryRiskScore() : 0.0;

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0.0;
        double recall    = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0.0;
        double f1        = (precision + recall) > 0
                ? 2 * precision * recall / (precision + recall) : 0.0;

        log.info("[ReportController] Metrics: precision={} recall={} f1={}",
                precision, recall, f1);

        return ResponseEntity.ok(Map.of(
                "totalRecords",    total,
                "flaggedRecords",  flagged,
                "truePositives",   tp,
                "falsePositives",  fp,
                "falseNegatives",  fn,
                "precision",       Math.round(precision * 10000.0) / 10000.0,
                "recall",          Math.round(recall * 10000.0) / 10000.0,
                "f1Score",         Math.round(f1 * 10000.0) / 10000.0,
                "averageRiskScore", Math.round(avgRisk * 10000.0) / 10000.0,
                "paperBaseline",   Map.of("precision", 0.84, "recall", 0.08, "f1", 0.15),
                "paperXaiComply",  Map.of("precision", 0.87, "recall", 0.03, "f1", 0.06)
        ));
    }

    @GetMapping("/reports/pending")
    public ResponseEntity<Map<String, Object>> getPendingCount() {
        long pending = reportService.countPendingRecords();
        log.info("[ReportController] Pending un-reported records: {}", pending);
        return ResponseEntity.ok(Map.of("pendingRecords", pending));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        log.error("[ReportController] Error: {}", e.getMessage(), e);
        return ResponseEntity.internalServerError()
                .body(Map.of("error", e.getClass().getSimpleName(), "message", e.getMessage()));
    }
}
