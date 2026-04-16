package com.xaicomply.common.exception;

/**
 * Base runtime exception for all XAI-Comply pipeline errors.
 * Each microservice throws a specific subtype for traceability.
 */
public class XaiComplyException extends RuntimeException {

    private final String errorCode;
    private final String serviceOrigin;

    public XaiComplyException(String errorCode, String serviceOrigin, String message) {
        super(message);
        this.errorCode = errorCode;
        this.serviceOrigin = serviceOrigin;
    }

    public XaiComplyException(String errorCode, String serviceOrigin, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.serviceOrigin = serviceOrigin;
    }

    public String getErrorCode()    { return errorCode; }
    public String getServiceOrigin(){ return serviceOrigin; }

    @Override
    public String toString() {
        return String.format("XaiComplyException[code=%s, service=%s, message=%s]",
                errorCode, serviceOrigin, getMessage());
    }

    // ─── Specific subtypes ────────────────────────────────────────────────────

    public static class PreprocessingException extends XaiComplyException {
        public PreprocessingException(String message) {
            super("PREPROCESS_ERROR", "preprocessing-service", message);
        }
        public PreprocessingException(String message, Throwable cause) {
            super("PREPROCESS_ERROR", "preprocessing-service", message, cause);
        }
    }

    public static class InferenceException extends XaiComplyException {
        public InferenceException(String message) {
            super("INFERENCE_ERROR", "inference-xai-service", message);
        }
        public InferenceException(String message, Throwable cause) {
            super("INFERENCE_ERROR", "inference-xai-service", message, cause);
        }
    }

    public static class ExplainerException extends XaiComplyException {
        public ExplainerException(String message) {
            super("EXPLAINER_ERROR", "python-explainer", message);
        }
        public ExplainerException(String message, Throwable cause) {
            super("EXPLAINER_ERROR", "python-explainer", message, cause);
        }
    }

    public static class MappingException extends XaiComplyException {
        public MappingException(String message) {
            super("MAPPING_ERROR", "regulatory-mapping-service", message);
        }
        public MappingException(String message, Throwable cause) {
            super("MAPPING_ERROR", "regulatory-mapping-service", message, cause);
        }
    }

    public static class ReportingException extends XaiComplyException {
        public ReportingException(String message) {
            super("REPORTING_ERROR", "reporting-service", message);
        }
        public ReportingException(String message, Throwable cause) {
            super("REPORTING_ERROR", "reporting-service", message, cause);
        }
    }

    public static class ModelNotFoundException extends XaiComplyException {
        public ModelNotFoundException(String modelPath) {
            super("MODEL_NOT_FOUND", "inference-xai-service",
                    "ONNX model not found at path: " + modelPath +
                    ". Run python-explainer/train_model.py first.");
        }
    }
}
