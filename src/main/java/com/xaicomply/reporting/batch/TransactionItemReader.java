package com.xaicomply.reporting.batch;

import com.xaicomply.domain.Transaction;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.batch.item.database.JpaPagingItemReader;
import org.springframework.batch.item.database.builder.JpaPagingItemReaderBuilder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * JpaPagingItemReader for FLAGGED transactions within a reporting period.
 * Chunk size = 100.
 */
@Component
public class TransactionItemReader {

    private final EntityManagerFactory entityManagerFactory;

    public TransactionItemReader(EntityManagerFactory entityManagerFactory) {
        this.entityManagerFactory = entityManagerFactory;
    }

    /**
     * Creates a reader for FLAGGED transactions in the given year/month.
     *
     * @param year  the reporting year
     * @param month the reporting month (1-12)
     * @return configured JpaPagingItemReader
     */
    public JpaPagingItemReader<Transaction> createReader(int year, int month) {
        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.plusMonths(1);

        Instant startInstant = start.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant endInstant = end.atStartOfDay(ZoneOffset.UTC).toInstant();

        return new JpaPagingItemReaderBuilder<Transaction>()
                .name("flaggedTransactionReader")
                .entityManagerFactory(entityManagerFactory)
                .queryString("SELECT t FROM Transaction t WHERE t.status = 'FLAGGED' " +
                        "AND t.createdAt >= :start AND t.createdAt < :end ORDER BY t.createdAt ASC")
                .parameterValues(Map.of("start", startInstant, "end", endInstant))
                .pageSize(100)
                .build();
    }
}
