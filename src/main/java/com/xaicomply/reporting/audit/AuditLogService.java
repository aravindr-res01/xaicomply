package com.xaicomply.reporting.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xaicomply.domain.AuditLogEntry;
import com.xaicomply.repository.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Manages immutable, chain-hashed audit log entries.
 * Each entry's SHA-256 hash incorporates the previous entry's hash, forming a blockchain-like chain.
 */
@Service
public class AuditLogService {

    private static final Logger log = LoggerFactory.getLogger(AuditLogService.class);
    private static final String GENESIS_HASH = "GENESIS";

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public AuditLogService(AuditLogRepository auditLogRepository, ObjectMapper objectMapper) {
        this.auditLogRepository = auditLogRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * Records an immutable audit log entry with chain hashing.
     *
     * @param entityType the type of entity (e.g., "TRANSACTION", "REPORT")
     * @param entityId   the entity's identifier
     * @param action     the action performed (e.g., "CREATED", "FLAGGED")
     * @param payload    the event payload (serialized to JSON)
     * @return the persisted AuditLogEntry
     */
    @Transactional
    public AuditLogEntry record(String entityType, String entityId, String action, Object payload) {
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize audit payload: {}", e.getMessage());
            payloadJson = payload.toString();
        }

        // Find previous hash for this entityId
        String previousHash = auditLogRepository
                .findTopByEntityIdOrderByCreatedAtDesc(entityId)
                .map(AuditLogEntry::getSha256Hash)
                .orElse(GENESIS_HASH);

        // Compute chain hash: SHA-256(entityId + action + payloadJson + previousHash)
        String hashInput = entityId + action + payloadJson + previousHash;
        String sha256Hash = computeSha256(hashInput);

        AuditLogEntry entry = new AuditLogEntry();
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setAction(action);
        entry.setPayloadJson(payloadJson);
        entry.setSha256Hash(sha256Hash);
        entry.setPreviousHash(previousHash);

        AuditLogEntry saved = auditLogRepository.save(entry);
        log.debug("Audit entry recorded: entityId={} action={} hash={}", entityId, action, sha256Hash);
        return saved;
    }

    /**
     * Verifies the integrity of the audit chain for a given entityId.
     * Recomputes each hash and checks that the chain is unbroken.
     *
     * @param entityId the entity's identifier
     * @return true if the chain is intact, false if any hash has been tampered with
     */
    public boolean verifyChain(String entityId) {
        List<AuditLogEntry> entries = auditLogRepository.findByEntityIdOrderByCreatedAtAsc(entityId);
        if (entries.isEmpty()) return true;

        String expectedPreviousHash = GENESIS_HASH;

        for (AuditLogEntry entry : entries) {
            // Verify previousHash matches expected
            if (!expectedPreviousHash.equals(entry.getPreviousHash())) {
                log.warn("Chain broken at entry={}: expected previousHash={} but got={}",
                        entry.getId(), expectedPreviousHash, entry.getPreviousHash());
                return false;
            }

            // Recompute hash
            String hashInput = entry.getEntityId() + entry.getAction()
                    + entry.getPayloadJson() + entry.getPreviousHash();
            String recomputed = computeSha256(hashInput);

            if (!recomputed.equals(entry.getSha256Hash())) {
                log.warn("Hash mismatch at entry={}: expected={} but stored={}",
                        entry.getId(), recomputed, entry.getSha256Hash());
                return false;
            }

            expectedPreviousHash = entry.getSha256Hash();
        }

        return true;
    }

    /**
     * Retrieves all audit entries for a given entityId, ordered by createdAt ASC.
     */
    public List<AuditLogEntry> getEntries(String entityId) {
        return auditLogRepository.findByEntityIdOrderByCreatedAtAsc(entityId);
    }

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
