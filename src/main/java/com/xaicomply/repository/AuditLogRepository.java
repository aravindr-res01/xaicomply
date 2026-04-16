package com.xaicomply.repository;

import com.xaicomply.domain.AuditLogEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLogEntry, UUID> {

    List<AuditLogEntry> findByEntityIdOrderByCreatedAtAsc(String entityId);

    Optional<AuditLogEntry> findTopByEntityIdOrderByCreatedAtDesc(String entityId);
}
