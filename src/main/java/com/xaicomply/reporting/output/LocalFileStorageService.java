package com.xaicomply.reporting.output;

import com.xaicomply.config.ModelConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Stores report files on the local filesystem.
 * Creates the output directory on startup if it doesn't exist.
 */
@Service
public class LocalFileStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(LocalFileStorageService.class);

    private final Path outputDir;

    public LocalFileStorageService(ModelConfig modelConfig) {
        this.outputDir = Paths.get(modelConfig.getReporting().getOutputDir());
    }

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(outputDir);
            log.info("Report output directory initialized: {}", outputDir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Failed to create report output directory: {}", e.getMessage());
        }
    }

    @Override
    public String store(String filename, byte[] content) {
        Path filePath = outputDir.resolve(filename);
        try {
            Files.write(filePath, content);
            log.info("Stored report file: {}", filePath.toAbsolutePath());
            return filePath.toAbsolutePath().toString();
        } catch (IOException e) {
            log.error("Failed to store file {}: {}", filename, e.getMessage());
            throw new RuntimeException("Failed to store report file: " + filename, e);
        }
    }

    public Path getOutputDir() {
        return outputDir;
    }
}
