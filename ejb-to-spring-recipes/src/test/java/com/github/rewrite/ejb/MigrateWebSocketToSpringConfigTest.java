package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Tests for MigrateWebSocketToSpringConfig recipe (MKR-004).
 * Verifies that Jakarta WebSocket endpoints are configured for Spring Boot
 * via ServerEndpointExporter without modifying the endpoint classes.
 */
class MigrateWebSocketToSpringConfigTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateWebSocketToSpringConfig())
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.websocket-api", "jakarta.websocket-client-api", "spring-context"));
    }

    @DocumentExample
    @Test
    void generatesConfigForServerEndpoint() {
        rewriteRun(
            // POM should get websocket starter
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-websocket</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            // Endpoint class remains unchanged
            java(
                """
                package com.example.ws;

                import jakarta.websocket.OnMessage;
                import jakarta.websocket.OnOpen;
                import jakarta.websocket.Session;
                import jakarta.websocket.server.ServerEndpoint;

                @ServerEndpoint("/chat")
                public class ChatEndpoint {

                    @OnOpen
                    public void onOpen(Session session) {
                    }

                    @OnMessage
                    public void onMessage(String message, Session session) {
                    }
                }
                """
            ),
            // Generated configuration
            java(
                null,
                """
                package com.example.ws;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.socket.server.standard.ServerEndpointExporter;

                /**
                 * WebSocket configuration for JSR-356 endpoints.
                 * <p>
                 * This configuration enables Spring Boot to recognize @ServerEndpoint classes:
                 * <li>com.example.ws.ChatEndpoint (/chat)</li>
                 * <p>
                 * Note: For Spring dependency injection in endpoints, a custom
                 * ServerEndpointConfig.Configurator may be required.
                 */
                @Configuration
                public class WebSocketConfiguration {

                    /**
                     * Registers @ServerEndpoint annotated classes with the WebSocket container.
                     * Required for embedded containers (Tomcat, Jetty, Undertow).
                     * <p>
                     * Note: Not needed for WAR deployments to external containers.
                     */
                    @Bean
                    public ServerEndpointExporter serverEndpointExporter() {
                        return new ServerEndpointExporter();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/ws/WebSocketConfiguration.java")
            )
        );
    }

    @Test
    void doesNotGenerateConfigWhenAlreadyExists() {
        rewriteRun(
            // Provide ServerEndpointExporter stub for test
            java(
                """
                package org.springframework.web.socket.server.standard;
                public class ServerEndpointExporter {
                }
                """
            ),
            // POM - dependency should still be added if missing
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-websocket</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            // Endpoint class remains unchanged
            java(
                """
                package com.example.ws;

                import jakarta.websocket.server.ServerEndpoint;

                @ServerEndpoint("/notifications")
                public class NotificationEndpoint {
                }
                """
            ),
            // Existing configuration - should not be modified or duplicated
            java(
                """
                package com.example.config;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.socket.server.standard.ServerEndpointExporter;

                @Configuration
                public class ExistingWebSocketConfig {

                    @Bean
                    public ServerEndpointExporter serverEndpointExporter() {
                        return new ServerEndpointExporter();
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotAddDependencyWhenAlreadyPresent() {
        rewriteRun(
            // POM already has the dependency
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-websocket</artifactId>
                            <version>3.4.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            // Endpoint class
            java(
                """
                package com.example.ws;

                import jakarta.websocket.server.ServerEndpoint;

                @ServerEndpoint("/status")
                public class StatusEndpoint {
                }
                """
            ),
            // Generated configuration
            java(
                null,
                """
                package com.example.ws;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.socket.server.standard.ServerEndpointExporter;

                /**
                 * WebSocket configuration for JSR-356 endpoints.
                 * <p>
                 * This configuration enables Spring Boot to recognize @ServerEndpoint classes:
                 * <li>com.example.ws.StatusEndpoint (/status)</li>
                 * <p>
                 * Note: For Spring dependency injection in endpoints, a custom
                 * ServerEndpointConfig.Configurator may be required.
                 */
                @Configuration
                public class WebSocketConfiguration {

                    /**
                     * Registers @ServerEndpoint annotated classes with the WebSocket container.
                     * Required for embedded containers (Tomcat, Jetty, Undertow).
                     * <p>
                     * Note: Not needed for WAR deployments to external containers.
                     */
                    @Bean
                    public ServerEndpointExporter serverEndpointExporter() {
                        return new ServerEndpointExporter();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/ws/WebSocketConfiguration.java")
            )
        );
    }

    @Test
    void handlesMultipleEndpoints() {
        rewriteRun(
            // POM
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-websocket</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            // First endpoint
            java(
                """
                package com.example.ws;

                import jakarta.websocket.server.ServerEndpoint;

                @ServerEndpoint("/chat")
                public class ChatEndpoint {
                }
                """
            ),
            // Second endpoint
            java(
                """
                package com.example.ws;

                import jakarta.websocket.server.ServerEndpoint;

                @ServerEndpoint("/notifications")
                public class NotificationEndpoint {
                }
                """
            ),
            // Generated configuration should list both endpoints
            java(
                null,
                """
                package com.example.ws;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.socket.server.standard.ServerEndpointExporter;

                /**
                 * WebSocket configuration for JSR-356 endpoints.
                 * <p>
                 * This configuration enables Spring Boot to recognize @ServerEndpoint classes:
                 * <li>com.example.ws.ChatEndpoint (/chat)</li>
                 * <li>com.example.ws.NotificationEndpoint (/notifications)</li>
                 * <p>
                 * Note: For Spring dependency injection in endpoints, a custom
                 * ServerEndpointConfig.Configurator may be required.
                 */
                @Configuration
                public class WebSocketConfiguration {

                    /**
                     * Registers @ServerEndpoint annotated classes with the WebSocket container.
                     * Required for embedded containers (Tomcat, Jetty, Undertow).
                     * <p>
                     * Note: Not needed for WAR deployments to external containers.
                     */
                    @Bean
                    public ServerEndpointExporter serverEndpointExporter() {
                        return new ServerEndpointExporter();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/ws/WebSocketConfiguration.java")
            )
        );
    }

    @Test
    void noChangeWithoutServerEndpoint() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            // POM should remain unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                    </dependencies>
                </project>
                """
            ),
            // Regular class, no @ServerEndpoint
            java(
                """
                package com.example.service;

                import org.springframework.stereotype.Service;

                @Service
                public class RegularService {
                }
                """
            )
        );
    }

    // Note: javax.websocket namespace support is tested via simple name matching
    // in the recipe (TypeValidation.none() allows this). The recipe uses
    // "ServerEndpoint".equals(annotation.getSimpleName()) as a fallback.

    @Test
    void placesConfigInSpringBootApplicationPackage() {
        rewriteRun(
            // POM
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-websocket</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            // Spring Boot Application stub
            java(
                """
                package org.springframework.boot.autoconfigure;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface SpringBootApplication {
                }
                """
            ),
            // Spring Boot Application class
            java(
                """
                package com.example;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class DemoApplication {
                }
                """
            ),
            // WebSocket endpoint in subpackage
            java(
                """
                package com.example.ws;

                import jakarta.websocket.server.ServerEndpoint;

                @ServerEndpoint("/data")
                public class DataEndpoint {
                }
                """
            ),
            // Config should be in SpringBootApplication package (com.example)
            java(
                null,
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.socket.server.standard.ServerEndpointExporter;

                /**
                 * WebSocket configuration for JSR-356 endpoints.
                 * <p>
                 * This configuration enables Spring Boot to recognize @ServerEndpoint classes:
                 * <li>com.example.ws.DataEndpoint (/data)</li>
                 * <p>
                 * Note: For Spring dependency injection in endpoints, a custom
                 * ServerEndpointConfig.Configurator may be required.
                 */
                @Configuration
                public class WebSocketConfiguration {

                    /**
                     * Registers @ServerEndpoint annotated classes with the WebSocket container.
                     * Required for embedded containers (Tomcat, Jetty, Undertow).
                     * <p>
                     * Note: Not needed for WAR deployments to external containers.
                     */
                    @Bean
                    public ServerEndpointExporter serverEndpointExporter() {
                        return new ServerEndpointExporter();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/WebSocketConfiguration.java")
            )
        );
    }

    @Test
    void skipsConfigWhenExporterInSpringBootApplication() {
        // Codex Review: @SpringBootApplication is meta-annotated with @Configuration
        // and should be detected when it contains ServerEndpointExporter bean
        rewriteRun(
            // Provide ServerEndpointExporter stub for test
            java(
                """
                package org.springframework.web.socket.server.standard;
                public class ServerEndpointExporter {
                }
                """
            ),
            // Spring Boot Application stub
            java(
                """
                package org.springframework.boot.autoconfigure;
                import java.lang.annotation.*;
                @Target(ElementType.TYPE)
                @Retention(RetentionPolicy.RUNTIME)
                public @interface SpringBootApplication {
                }
                """
            ),
            // POM - dependency should still be added
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-websocket</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            // WebSocket endpoint
            java(
                """
                package com.example.ws;

                import jakarta.websocket.server.ServerEndpoint;

                @ServerEndpoint("/events")
                public class EventEndpoint {
                }
                """
            ),
            // @SpringBootApplication with ServerEndpointExporter - should NOT generate new config
            java(
                """
                package com.example;

                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.context.annotation.Bean;
                import org.springframework.web.socket.server.standard.ServerEndpointExporter;

                @SpringBootApplication
                public class DemoApplication {

                    @Bean
                    public ServerEndpointExporter serverEndpointExporter() {
                        return new ServerEndpointExporter();
                    }
                }
                """
            )
        );
    }

    @Test
    void dependencyCheckIgnoresDependencyManagement() {
        // Codex Review: Dependency check should not match <dependencyManagement> entries
        // When dependency is in dependencyManagement, AddDependency adds it without version
        rewriteRun(
            // POM with websocket in dependencyManagement only (not direct dependency)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-websocket</artifactId>
                                <version>3.4.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-starter-websocket</artifactId>
                                <version>3.4.0</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-websocket</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """
            ),
            // Endpoint class
            java(
                """
                package com.example.ws;

                import jakarta.websocket.server.ServerEndpoint;

                @ServerEndpoint("/test")
                public class TestEndpoint {
                }
                """
            ),
            // Generated configuration
            java(
                null,
                """
                package com.example.ws;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.socket.server.standard.ServerEndpointExporter;

                /**
                 * WebSocket configuration for JSR-356 endpoints.
                 * <p>
                 * This configuration enables Spring Boot to recognize @ServerEndpoint classes:
                 * <li>com.example.ws.TestEndpoint (/test)</li>
                 * <p>
                 * Note: For Spring dependency injection in endpoints, a custom
                 * ServerEndpointConfig.Configurator may be required.
                 */
                @Configuration
                public class WebSocketConfiguration {

                    /**
                     * Registers @ServerEndpoint annotated classes with the WebSocket container.
                     * Required for embedded containers (Tomcat, Jetty, Undertow).
                     * <p>
                     * Note: Not needed for WAR deployments to external containers.
                     */
                    @Bean
                    public ServerEndpointExporter serverEndpointExporter() {
                        return new ServerEndpointExporter();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/ws/WebSocketConfiguration.java")
            )
        );
    }
}
