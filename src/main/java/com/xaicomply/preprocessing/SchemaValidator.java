package com.xaicomply.preprocessing;

import com.xaicomply.exception.ValidationException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Validates transaction request fields before preprocessing.
 */
@Component
public class SchemaValidator {

    /**
     * Validates all required fields.
     * Throws ValidationException with field-level errors map on failure.
     */
    public void validate(String customerId, BigDecimal amount, String currency,
                         String merchantCategoryCode, String countryCode,
                         Integer transactionVelocity, Boolean isInternational, Integer hourOfDay) {
        Map<String, String> errors = new HashMap<>();

        if (customerId == null || customerId.isBlank()) {
            errors.put("customerId", "Customer ID is required");
        }

        if (amount == null) {
            errors.put("amount", "Amount is required");
        } else if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            errors.put("amount", "Amount must be greater than 0");
        }

        if (currency == null || currency.isBlank()) {
            errors.put("currency", "Currency is required");
        } else if (currency.length() != 3) {
            errors.put("currency", "Currency must be a 3-character ISO code");
        }

        if (merchantCategoryCode == null || merchantCategoryCode.isBlank()) {
            errors.put("merchantCategoryCode", "Merchant Category Code is required");
        }

        if (countryCode == null || countryCode.isBlank()) {
            errors.put("countryCode", "Country code is required");
        }

        if (transactionVelocity == null) {
            errors.put("transactionVelocity", "Transaction velocity is required");
        } else if (transactionVelocity < 0) {
            errors.put("transactionVelocity", "Transaction velocity must be non-negative");
        }

        if (isInternational == null) {
            errors.put("isInternational", "isInternational is required");
        }

        if (hourOfDay == null) {
            errors.put("hourOfDay", "Hour of day is required");
        } else if (hourOfDay < 0 || hourOfDay > 23) {
            errors.put("hourOfDay", "Hour of day must be between 0 and 23");
        }

        if (!errors.isEmpty()) {
            throw new ValidationException("Request validation failed", errors);
        }
    }
}
