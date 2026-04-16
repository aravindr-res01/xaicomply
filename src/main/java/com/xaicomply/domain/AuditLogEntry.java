package com.xaicomply.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log_entries",
        indexes = {
            @Index(name = "idx_audit_entity_id", columnList = "entityId"),
            @Index(name = "idx_audit_created_at", columnList = "createdAt")
        })
public class AuditLogEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String entityType;

    @Column(nullable = false)
    private String entityId;

    @Column(nullable = false)
    private String action;

    @Column(columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false, length = 64)
    private String sha256Hash;

    @Column(length = 64)
    private String previousHash;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    // Immutable enforcement: prevent updates and deletes
    @PreUpdate
    protected void onUpdate() {
        throw new UnsupportedOperationException("AuditLogEntry is immutable and cannot be updated");
    }

    @PreRemove
    protected void onRemove() {
        throw new UnsupportedOperationException("AuditLogEntry is immutable and cannot be deleted");
    }

    public AuditLogEntry() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public String getPayloadJson() { return payloadJson; }
    public void setPayloadJson(String payloadJson) { this.payloadJson = payloadJson; }

    public String getSha256Hash() { return sha256Hash; }
    public void setSha256Hash(String sha256Hash) { this.sha256Hash = sha256Hash; }

    public String getPreviousHash() { return previousHash; }
    public void setPreviousHash(String previousHash) { this.previousHash = previousHash; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
