package com.xaicomply.reporting.output;

/**
 * Interface for storing generated report files.
 * Default implementation: LocalFileStorageService (saves to ./reports/)
 * For AWS S3: implement S3StorageService, annotate @Primary @ConditionalOnProperty
 */
public interface StorageService {

    /**
     * Stores a file with the given filename and content.
     *
     * @param filename the name of the file (without path)
     * @param content  the file content as bytes
     * @return the full file path or URL where the file was stored
     */
    String store(String filename, byte[] content);
}
