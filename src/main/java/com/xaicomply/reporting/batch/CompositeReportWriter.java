package com.xaicomply.reporting.batch;

import com.xaicomply.domain.ComplianceReport;
import com.xaicomply.repository.ComplianceReportRepository;
import com.xaicomply.reporting.audit.AuditLogService;
import com.xaicomply.reporting.output.CsvReportGenerator;
import com.xaicomply.reporting.output.PdfReportGenerator;
import com.xaicomply.reporting.output.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes report records by delegating to PDF and CSV generators.
 * Accumulates all records, then generates and stores reports at end.
 */
@Component
public class CompositeReportWriter implements ItemWriter<ReportRecord> {

    private static final Logger log = LoggerFactory.getLogger(CompositeReportWriter.class);

    private final PdfReportGenerator pdfReportGenerator;
    private final CsvReportGenerator csvReportGenerator;
    private final StorageService storageService;
    private final ComplianceReportRepository complianceReportRepository;
    private final AuditLogService auditLogService;

    // State shared per job execution (set externally)
    private volatile UUID reportId;
    private volatile String period;
    private final List<ReportRecord> allRecords = new ArrayList<>();

    public CompositeReportWriter(PdfReportGenerator pdfReportGenerator,
                                 CsvReportGenerator csvReportGenerator,
                                 StorageService storageService,
                                 ComplianceReportRepository complianceReportRepository,
                                 AuditLogService auditLogService) {
        this.pdfReportGenerator = pdfReportGenerator;
        this.csvReportGenerator = csvReportGenerator;
        this.storageService = storageService;
        this.complianceReportRepository = complianceReportRepository;
        this.auditLogService = auditLogService;
    }

    public void setReportContext(UUID reportId, String period) {
        this.reportId = reportId;
        this.period = period;
        this.allRecords.clear();
    }

    @Override
    public void write(Chunk<? extends ReportRecord> chunk) throws Exception {
        allRecords.addAll(chunk.getItems());
        log.debug("Writer accumulated {} total records", allRecords.size());
    }

    /**
     * Called after all chunks have been written — generates final PDF and CSV.
     */
    public void finalizeReport() {
        if (reportId == null || period == null) {
            log.warn("No report context set — skipping finalization");
            return;
        }

        try {
            // Generate PDF
            byte[] pdfBytes = pdfReportGenerator.generate(period, allRecords);
            String pdfHash = pdfReportGenerator.computeHash(pdfBytes);
            String pdfFilename = "compliance-report-" + period + "-" + reportId + ".pdf";
            String pdfPath = storageService.store(pdfFilename, pdfBytes);

            // Generate CSV
            byte[] csvBytes = csvReportGenerator.generate(allRecords);
            String csvFilename = "compliance-report-" + period + "-" + reportId + ".csv";
            storageService.store(csvFilename, csvBytes);

            // Update ComplianceReport entity
            complianceReportRepository.findById(reportId).ifPresent(report -> {
                report.setStatus(ComplianceReport.Status.COMPLETED);
                report.setFilePath(pdfPath);
                report.setSha256Hash(pdfHash);
                report.setRecordCount(allRecords.size());
                report.setFlaggedCount((int) allRecords.stream().filter(ReportRecord::flagged).count());
                report.setCompletedAt(Instant.now());
                complianceReportRepository.save(report);
            });

            // Audit log
            auditLogService.record("REPORT", reportId.toString(), "GENERATED",
                    Map.of("period", period, "recordCount", allRecords.size(),
                            "flaggedCount", allRecords.stream().filter(ReportRecord::flagged).count(),
                            "pdfPath", pdfPath, "sha256Hash", pdfHash));

            log.info("Report finalized: reportId={} period={} records={} hash={}",
                    reportId, period, allRecords.size(), pdfHash);

        } catch (Exception e) {
            log.error("Failed to finalize report {}: {}", reportId, e.getMessage(), e);
            complianceReportRepository.findById(reportId).ifPresent(report -> {
                report.setStatus(ComplianceReport.Status.FAILED);
                report.setCompletedAt(Instant.now());
                complianceReportRepository.save(report);
            });
        }
    }
}
