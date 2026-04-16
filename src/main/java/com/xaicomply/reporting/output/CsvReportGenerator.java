package com.xaicomply.reporting.output;

import com.opencsv.CSVWriter;
import com.xaicomply.reporting.batch.ReportRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Generates CSV compliance reports using OpenCSV.
 */
@Component
public class CsvReportGenerator {

    private static final Logger log = LoggerFactory.getLogger(CsvReportGenerator.class);

    private static final String[] HEADERS = {
            "TransactionId", "CustomerId", "Amount", "Currency",
            "RiskScore", "Flagged", "ExplainMethod", "TopFeature",
            "TopFeatureWeight", "CreatedAt"
    };

    /**
     * Generates a CSV report from the given records.
     *
     * @param records the list of report records
     * @return CSV content as byte array (UTF-8)
     */
    public byte[] generate(List<ReportRecord> records) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        try (OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
             CSVWriter csvWriter = new CSVWriter(osw)) {

            csvWriter.writeNext(HEADERS);

            for (ReportRecord r : records) {
                csvWriter.writeNext(new String[]{
                        r.transactionId().toString(),
                        r.customerId(),
                        r.amount().toPlainString(),
                        r.currency(),
                        String.format("%.6f", r.riskScore()),
                        String.valueOf(r.flagged()),
                        r.explainMethod() != null ? r.explainMethod() : "",
                        r.topFeatureName() != null ? r.topFeatureName() : "",
                        r.topFeatureWeight() != null ? String.format("%.6f", r.topFeatureWeight()) : "",
                        r.createdAt() != null ? r.createdAt().toString() : ""
                });
            }

            csvWriter.flush();
        } catch (Exception e) {
            log.error("Failed to generate CSV report: {}", e.getMessage(), e);
            throw new RuntimeException("CSV generation failed", e);
        }

        byte[] csvBytes = baos.toByteArray();
        log.info("Generated CSV report with {} records, size={}bytes", records.size(), csvBytes.length);
        return csvBytes;
    }
}
