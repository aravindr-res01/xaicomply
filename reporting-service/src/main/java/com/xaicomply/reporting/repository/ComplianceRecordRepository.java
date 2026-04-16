package com.xaicomply.reporting.repository;

import com.xaicomply.common.enums.ComplianceStatus;
import com.xaicomply.reporting.entity.ComplianceRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for ComplianceRecord audit log.
 *
 * Provides queries used by:
 *   - ReportService (aggregate stats)
 *   - Spring Batch ItemReader (fetch unprocessed records)
 *   - ReportController (paginated list views)
 */
@Repository
public interface ComplianceRecordRepository extends JpaRepository<ComplianceRecord, Long> {

    // ── Lookups ───────────────────────────────────────────────────────────────

    Optional<ComplianceRecord> findByTransactionId(String transactionId);

    boolean existsByTransactionId(String transactionId);

    // ── Flagged / status queries ──────────────────────────────────────────────

    List<ComplianceRecord> findByFlaggedTrue();

    List<ComplianceRecord> findByComplianceStatus(ComplianceStatus status);

    List<ComplianceRecord> findByComplianceStatusIn(List<ComplianceStatus> statuses);

    Page<ComplianceRecord> findByFlaggedTrue(Pageable pageable);

    // ── Batch report queries ──────────────────────────────────────────────────

    /** Records not yet included in any report — used by Spring Batch ItemReader */
    List<ComplianceRecord> findByReportGeneratedFalseOrderByCreatedAtAsc();

    /** Records in a time window — for periodic compliance reports */
    List<ComplianceRecord> findByCreatedAtBetweenOrderByCreatedAtAsc(
            Instant from, Instant to);

    // ── Aggregate statistics ──────────────────────────────────────────────────

    long countByFlaggedTrue();

    long countByComplianceStatus(ComplianceStatus status);

    @Query("SELECT AVG(r.regulatoryRiskScore) FROM ComplianceRecord r")
    Double findAverageRegulatoryRiskScore();

    @Query("SELECT MAX(r.regulatoryRiskScore) FROM ComplianceRecord r")
    Double findMaxRegulatoryRiskScore();

    @Query("SELECT r.complianceStatus, COUNT(r) FROM ComplianceRecord r GROUP BY r.complianceStatus")
    List<Object[]> countByComplianceStatusGrouped();

    @Query("SELECT r.topRiskFeature, COUNT(r) FROM ComplianceRecord r "
         + "WHERE r.flagged = true "
         + "GROUP BY r.topRiskFeature ORDER BY COUNT(r) DESC")
    List<Object[]> findTopRiskFeaturesByFrequency();

    // ── Evaluation metrics (when true labels available) ───────────────────────

    @Query("SELECT COUNT(r) FROM ComplianceRecord r "
         + "WHERE r.trueLabel = 1 AND r.flagged = true")
    long countTruePositives();

    @Query("SELECT COUNT(r) FROM ComplianceRecord r "
         + "WHERE r.trueLabel = 0 AND r.flagged = true")
    long countFalsePositives();

    @Query("SELECT COUNT(r) FROM ComplianceRecord r "
         + "WHERE r.trueLabel = 1 AND r.flagged = false")
    long countFalseNegatives();

    // ── Batch update ──────────────────────────────────────────────────────────

    @Modifying
    @Transactional
    @Query("UPDATE ComplianceRecord r SET r.reportGenerated = true, r.batchJobId = :jobId "
         + "WHERE r.reportGenerated = false")
    int markAllAsReported(@Param("jobId") Long jobId);
}
