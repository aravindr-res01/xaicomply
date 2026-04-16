package com.xaicomply.api.dto;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;

/**
 * Request body for creating a new transaction.
 */
public record TransactionRequest(
        @NotBlank(message = "customerId is required")
        String customerId,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be greater than 0")
        BigDecimal amount,

        @NotBlank(message = "currency is required")
        @Size(min = 3, max = 3, message = "currency must be a 3-character ISO code")
        String currency,

        @NotBlank(message = "merchantCategoryCode is required")
        String merchantCategoryCode,

        @NotBlank(message = "countryCode is required")
        String countryCode,

        @NotNull(message = "transactionVelocity is required")
        @Min(value = 0, message = "transactionVelocity must be non-negative")
        Integer transactionVelocity,

        @NotNull(message = "isInternational is required")
        Boolean isInternational,

        @NotNull(message = "hourOfDay is required")
        @Min(value = 0, message = "hourOfDay must be between 0 and 23")
        @Max(value = 23, message = "hourOfDay must be between 0 and 23")
        Integer hourOfDay
) {}
