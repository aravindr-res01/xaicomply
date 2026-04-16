package com.xaicomply.api;

import com.xaicomply.domain.AuditLogEntry;
import com.xaicomply.exception.ApiResponse;
import com.xaicomply.reporting.audit.AuditLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final Logger log = LoggerFactory.getLogger(AuditController.class);

    private final AuditLogService auditLogService;

    public AuditController(AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
    }

    /**
     * GET /api/v1/audit/{entityId}
     * Returns all audit entries for an entity with chain validity check.
     */
    @GetMapping("/{entityId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAuditLog(@PathVariable String entityId) {
        List<AuditLogEntry> entries = auditLogService.getEntries(entityId);
        boolean chainValid = auditLogService.verifyChain(entityId);

        List<Map<String, Object>> entryDtos = entries.stream()
                .map(e -> Map.<String, Object>of(
                        "id", e.getId().toString(),
                        "action", e.getAction(),
                        "entityType", e.getEntityType(),
                        "payloadJson", e.getPayloadJson() != null ? e.getPayloadJson() : "",
                        "sha256Hash", e.getSha256Hash(),
                        "previousHash", e.getPreviousHash() != null ? e.getPreviousHash() : "",
                        "createdAt", e.getCreatedAt().toString()
                ))
                .collect(Collectors.toList());

        Map<String, Object> response = Map.of(
                "entityId", entityId,
                "entries", entryDtos,
                "chainValid", chainValid,
                "entryCount", entries.size()
        );

        return ResponseEntity.ok(ApiResponse.ok(response));
    }
}
