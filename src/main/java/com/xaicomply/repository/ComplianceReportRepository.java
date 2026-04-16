package com.xaicomply.repository;

import com.xaicomply.domain.ComplianceReport;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ComplianceReportRepository extends JpaRepository<ComplianceReport, UUID> {

    List<ComplianceReport> findByPeriodOrderByCreatedAtDesc(String period);

    List<ComplianceReport> findAllByOrderByCreatedAtDesc();
}
