package com.xaicomply.api;

import com.xaicomply.api.dto.ReportRequest;
import com.xaicomply.api.dto.ReportResponse;
import com.xaicomply.domain.ComplianceReport;
import com.xaicomply.exception.ApiResponse;
import com.xaicomply.reporting.ReportingService;
import com.xaicomply.repository.ComplianceReportRepository;
import jakarta.persistence.EntityNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);

    private final ReportingService reportingService;
    private final ComplianceReportRepository complianceReportRepository;

    public ReportController(ReportingService reportingService,
                            ComplianceReportRepository complianceReportRepository) {
        this.reportingService = reportingService;
        this.complianceReportRepository = complianceReportRepository;
    }

    /**
     * POST /api/v1/reports/generate
     * Triggers asynchronous compliance report generation.
     */
    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<ReportResponse>> generateReport(
            @Valid @RequestBody ReportRequest request) {

        log.info("Report generation requested: period={} year={} month={}",
                request.period(), request.year(), request.month());

        ComplianceReport report = reportingService.startReport(
                request.period(), request.year(), request.month(), "API");

        ReportResponse response = toDto(report, "Report generation started");
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.ok(response));
    }

    /**
     * GET /api/v1/reports/{reportId}
     */
    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<ReportResponse>> getReport(@PathVariable UUID reportId) {
        ComplianceReport report = complianceReportRepository.findById(reportId)
                .orElseThrow(() -> new EntityNotFoundException("Report not found: " + reportId));
        return ResponseEntity.ok(ApiResponse.ok(toDto(report, null)));
    }

    /**
     * GET /api/v1/reports/{reportId}/download
     * Downloads the PDF file for a completed report.
     */
    @GetMapping("/{reportId}/download")
    public ResponseEntity<Resource> downloadReport(@PathVariable UUID reportId) {
        ComplianceReport report = complianceReportRepository.findById(reportId)
                .orElseThrow(() -> new EntityNotFoundException("Report not found: " + reportId));

        if (report.getStatus() != ComplianceReport.Status.COMPLETED || report.getFilePath() == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(null);
        }

        File file = new File(report.getFilePath());
        if (!file.exists()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(null);
        }

        Resource resource = new FileSystemResource(file);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + file.getName() + "\"")
                .body(resource);
    }

    /**
     * GET /api/v1/reports
     * Lists all compliance reports.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<ReportResponse>>> listReports() {
        List<ReportResponse> reports = complianceReportRepository.findAllByOrderByCreatedAtDesc()
                .stream()
                .map(r -> toDto(r, null))
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(reports));
    }

    private ReportResponse toDto(ComplianceReport report, String message) {
        return new ReportResponse(
                report.getId(),
                report.getPeriod(),
                report.getStatus().name(),
                report.getFlaggedCount(),
                report.getRecordCount(),
                report.getFilePath(),
                report.getSha256Hash(),
                message,
                report.getCreatedAt(),
                report.getCompletedAt()
        );
    }
}
