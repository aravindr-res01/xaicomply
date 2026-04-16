package com.xaicomply;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xaicomply.domain.AuditLogEntry;
import com.xaicomply.repository.AuditLogRepository;
import com.xaicomply.reporting.audit.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class AuditLogServiceTest {

    private AuditLogService auditLogService;
    private AuditLogRepository auditLogRepository;
    private final List<AuditLogEntry> storedEntries = new ArrayList<>();

    @BeforeEach
    void setUp() {
        auditLogRepository = mock(AuditLogRepository.class);
        ObjectMapper objectMapper = new ObjectMapper();

        // Simulate repository behavior
        when(auditLogRepository.save(any(AuditLogEntry.class))).thenAnswer(inv -> {
            AuditLogEntry entry = inv.getArgument(0);
            if (entry.getId() == null) {
                setField(entry, "id", UUID.randomUUID());
            }
            if (entry.getCreatedAt() == null) {
                setField(entry, "createdAt", Instant.now());
            }
            storedEntries.add(entry);
            return entry;
        });

        when(auditLogRepository.findTopByEntityIdOrderByCreatedAtDesc(anyString())).thenAnswer(inv -> {
            String entityId = inv.getArgument(0);
            return storedEntries.stream()
                    .filter(e -> entityId.equals(e.getEntityId()))
                    .reduce((a, b) -> b)
                    .map(Optional::of)
                    .orElse(Optional.empty());
        });

        when(auditLogRepository.findByEntityIdOrderByCreatedAtAsc(anyString())).thenAnswer(inv -> {
            String entityId = inv.getArgument(0);
            return storedEntries.stream()
                    .filter(e -> entityId.equals(e.getEntityId()))
                    .collect(java.util.stream.Collectors.toList());
        });

        auditLogService = new AuditLogService(auditLogRepository, objectMapper);
    }

    @Test
    void record3Entries_eachHashShouldDifferFromPrevious() {
        String entityId = "tx-" + UUID.randomUUID();

        AuditLogEntry entry1 = auditLogService.record("TRANSACTION", entityId, "CREATED", "payload1");
        AuditLogEntry entry2 = auditLogService.record("TRANSACTION", entityId, "SCORED", "payload2");
        AuditLogEntry entry3 = auditLogService.record("TRANSACTION", entityId, "FLAGGED", "payload3");

        assertThat(entry1.getSha256Hash()).isNotEqualTo(entry2.getSha256Hash());
        assertThat(entry2.getSha256Hash()).isNotEqualTo(entry3.getSha256Hash());
        assertThat(entry1.getSha256Hash()).isNotEqualTo(entry3.getSha256Hash());
    }

    @Test
    void verifyChain_withUnmodifiedEntries_shouldReturnTrue() {
        String entityId = "tx-" + UUID.randomUUID();

        auditLogService.record("TRANSACTION", entityId, "CREATED", "payload1");
        auditLogService.record("TRANSACTION", entityId, "PROCESSED", "payload2");
        auditLogService.record("TRANSACTION", entityId, "FLAGGED", "payload3");

        boolean valid = auditLogService.verifyChain(entityId);

        assertThat(valid).isTrue();
    }

    @Test
    void verifyChain_withTamperedEntry_shouldReturnFalse() {
        String entityId = "tx-" + UUID.randomUUID();

        auditLogService.record("TRANSACTION", entityId, "CREATED", "payload1");
        auditLogService.record("TRANSACTION", entityId, "PROCESSED", "payload2");
        auditLogService.record("TRANSACTION", entityId, "FLAGGED", "payload3");

        // Tamper with entry 2 (index 1)
        AuditLogEntry tampered = storedEntries.stream()
                .filter(e -> entityId.equals(e.getEntityId()))
                .skip(1)
                .findFirst()
                .orElseThrow();
        tampered.setPayloadJson("{\"tampered\": true}"); // modify payload without recomputing hash

        boolean valid = auditLogService.verifyChain(entityId);

        assertThat(valid).isFalse();
    }

    @Test
    void record_firstEntry_shouldUsePreviousHashGenesis() {
        String entityId = "tx-" + UUID.randomUUID();

        AuditLogEntry entry = auditLogService.record("TRANSACTION", entityId, "CREATED", "payload");

        assertThat(entry.getPreviousHash()).isEqualTo("GENESIS");
    }

    @Test
    void record_secondEntry_shouldUsePreviousHash() {
        String entityId = "tx-" + UUID.randomUUID();

        AuditLogEntry entry1 = auditLogService.record("TRANSACTION", entityId, "CREATED", "payload1");
        AuditLogEntry entry2 = auditLogService.record("TRANSACTION", entityId, "PROCESSED", "payload2");

        assertThat(entry2.getPreviousHash()).isEqualTo(entry1.getSha256Hash());
    }

    // Helper to set private fields for testing
    private void setField(Object obj, String fieldName, Object value) {
        try {
            var field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(obj, value);
        } catch (Exception e) {
            // ignore for test setup
        }
    }
}
