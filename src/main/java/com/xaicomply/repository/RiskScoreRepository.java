package com.xaicomply.repository;

import com.xaicomply.domain.RiskScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RiskScoreRepository extends JpaRepository<RiskScore, UUID> {

    Optional<RiskScore> findTopByTransactionIdOrderByCreatedAtDesc(UUID transactionId);

    List<RiskScore> findByTransactionId(UUID transactionId);
}
