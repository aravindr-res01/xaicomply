package com.xaicomply.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${security.api-key:dev-api-key-xai-comply-2024}")
    private String apiKey;

    /**
     * API Key Authentication Filter.
     * Reads header X-API-Key and validates against security.api-key property.
     *
     * To switch to OAuth2: replace this filter with spring-boot-starter-oauth2-resource-server
     * and configure:
     *   spring.security.oauth2.resourceserver.jwt.issuer-uri=https://your-keycloak/realms/your-realm
     * Then update SecurityFilterChain to use .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
     */
    @Bean
    public OncePerRequestFilter apiKeyAuthFilter() {
        return new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                            FilterChain filterChain) throws ServletException, IOException {
                String path = request.getRequestURI();

                // Exclude actuator and h2-console from API key check
                if (path.startsWith("/actuator") || path.startsWith("/h2-console")) {
                    filterChain.doFilter(request, response);
                    return;
                }

                String requestApiKey = request.getHeader("X-API-Key");
                if (requestApiKey == null || !requestApiKey.equals(apiKey)) {
                    log.warn("Invalid or missing API key from IP: {}", request.getRemoteAddr());
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\": \"Invalid or missing API key\"}");
                    return;
                }

                filterChain.doFilter(request, response);
            }
        };
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .headers(headers -> headers.frameOptions(frame -> frame.sameOrigin())) // for H2 console
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()
                .anyRequest().permitAll() // API key filter handles auth
            )
            .addFilterBefore(apiKeyAuthFilter(), UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
