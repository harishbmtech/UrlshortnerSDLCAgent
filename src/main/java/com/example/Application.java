package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Single deployable hosting two bounded contexts:
 * <ul>
 *   <li>{@code com.example.shortener} - the URL shortener service (the system being engineered)</li>
 *   <li>{@code com.example.sdlc} - the agentic SDLC orchestrator (LangGraph4j + Spring AI) that plans,
 *       designs, implements, validates, documents and release-gates changes to it</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
