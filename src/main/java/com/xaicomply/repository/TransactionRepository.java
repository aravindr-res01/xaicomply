package com.xaicomply.repository;

import com.xaicomply.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Page<Transaction> findByCustomerId(String customerId, Pageable pageable);

    Page<Transaction> findByStatus(Transaction.Status status, Pageable pageable);

    Page<Transaction> findByCustomerIdAndStatus(String customerId, Transaction.Status status, Pageable pageable);

    @Query("SELECT t FROM Transaction t WHERE t.status = 'FLAGGED' AND t.createdAt BETWEEN :start AND :end")
    List<Transaction> findFlaggedInPeriod(@Param("start") Instant start, @Param("end") Instant end);

    long countByStatus(Transaction.Status status);
}
