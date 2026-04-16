package com.xaicomply.preprocessing.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * Application configuration for the Preprocessing Service.
 * Configures RestTemplate (for downstream REST calls) and Jackson (for JSON).
 */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    /**
     * RestTemplate used to call Inference, Mapping, and Reporting services.
     * Configured with connect/read timeouts to avoid blocking indefinitely.
     */
    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        log.info("[AppConfig] Configuring RestTemplate: connectTimeout=5s, readTimeout=30s");
        RestTemplate restTemplate = builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .build();

        // Register Jackson converter with JSR310 support
        restTemplate.getMessageConverters().removeIf(
                c -> c instanceof MappingJackson2HttpMessageConverter);
        restTemplate.getMessageConverters().add(0,
                new MappingJackson2HttpMessageConverter(objectMapper()));

        log.info("[AppConfig] RestTemplate configured with JSR310 Jackson converter");
        return restTemplate;
    }

    /**
     * Jackson ObjectMapper configured for Java 8+ time types (Instant, LocalDate, etc.)
     */
    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        log.debug("[AppConfig] ObjectMapper configured with JavaTimeModule");
        return mapper;
    }
}
