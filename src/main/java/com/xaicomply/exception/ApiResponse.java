package com.xaicomply.exception;

import java.time.Instant;

/**
 * Generic API response envelope.
 */
public record ApiResponse<T>(
        boolean success,
        T data,
        String error,
        String timestamp
) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, Instant.now().toString());
    }

    public static <T> ApiResponse<T> error(String errorMessage) {
        return new ApiResponse<>(false, null, errorMessage, Instant.now().toString());
    }
}
