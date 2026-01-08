/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Test for GenerateCxfJaxwsConfig recipe
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.test.SourceSpecs.text;

class GenerateCxfJaxwsConfigTest implements RewriteTest {

    @BeforeEach
    void setUp() {
        // Clear any cached configurations before each test
        ProjectConfigurationLoader.clearCache();
        ProjectConfigurationLoader.clearTestInjections();
    }

    @AfterEach
    void tearDown() {
        // Clean up test injections after each test
        ProjectConfigurationLoader.clearTestInjections();
        ProjectConfigurationLoader.clearCache();
    }

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new GenerateCxfJaxwsConfig())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context"));
    }

    @Test
    @DocumentExample
    void generatesConfigurationForSimpleWebService() {
        rewriteRun(
            // The WebService class
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class HelloService {
                    public String sayHello(String name) {
                        return "Hello, " + name;
                    }
                }
                """
            ),
            // The generated configuration
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    @Bean
                    public Endpoint helloServiceEndpoint(Bus bus, HelloService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/HelloService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            ),
            // The POM with CXF dependency
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>webservice-demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.xml.ws</groupId>
                            <artifactId>jakarta.xml.ws-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>webservice-demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.xml.ws</groupId>
                            <artifactId>jakarta.xml.ws-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.apache.cxf</groupId>
                            <artifactId>cxf-spring-boot-starter-jaxws</artifactId>
                            <version>4.1.4</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void generatesConfigurationForMultipleWebServices() {
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class UserService {
                    public String getUser(String id) {
                        return id;
                    }
                }
                """
            ),
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class OrderService {
                    public String getOrder(String id) {
                        return id;
                    }
                }
                """
            ),
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    @Bean
                    public Endpoint orderServiceEndpoint(Bus bus, OrderService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/OrderService");
                        return endpoint;
                    }

                    @Bean
                    public Endpoint userServiceEndpoint(Bus bus, UserService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/UserService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    @Test
    void usesWebServiceNameAttributeWhenUrlSafe() {
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService(name = "UserAPI")
                public class UserService {
                    public String getUser(String id) {
                        return id;
                    }
                }
                """
            ),
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    @Bean
                    public Endpoint userServiceEndpoint(Bus bus, UserService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/UserAPI");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    @Test
    void handlesNonUrlSafeNameWithNeedsReview() {
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService(name = "urn:example:UserService")
                public class UserService {
                    public String getUser(String id) {
                        return id;
                    }
                }
                """
            ),
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    // @NeedsReview: WebService name attribute contains non-URL-safe characters: urn:example:UserService
                    @Bean
                    public Endpoint userServiceEndpoint(Bus bus, UserService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/UserService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    @Test
    void doesNotGenerateIfNoWebServices() {
        rewriteRun(
            java(
                """
                package com.example.service;

                public class RegularService {
                    public String hello() {
                        return "hello";
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotAddDuplicateDependency() {
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class HelloService {
                    public String sayHello(String name) {
                        return "Hello, " + name;
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>webservice-demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.cxf</groupId>
                            <artifactId>cxf-spring-boot-starter-jaxws</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void addsConflictMarkerWhenSpringWsPresent() {
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class HelloService {
                    public String sayHello(String name) {
                        return "Hello, " + name;
                    }
                }
                """
            ),
            // POM with Spring-WS dependency - should add conflict marker comment inside <project>, NOT CXF
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>webservice-demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web-services</artifactId>
                            <version>3.2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <!--
                    @NeedsReview: JAX-WS Migration Conflict
                    Spring-WS dependency detected - CXF configuration was NOT generated.
                   \s
                    Found @WebService classes:
                      - com.example.service.HelloService
                   \s
                    Resolution options:
                      1. Remove Spring-WS dependency and re-run migration for CXF support
                      2. Configure JAX-WS endpoints manually using Spring-WS
                      3. Keep both frameworks with careful servlet mapping separation
                    -->
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>webservice-demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-web-services</artifactId>
                            <version>3.2.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void addsNeedsReviewForServiceName() {
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService(serviceName = "MyCustomService")
                public class HelloService {
                }
                """
            ),
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    // @NeedsReview: serviceName='MyCustomService' is used for WSDL service element, not endpoint path
                    @Bean
                    public Endpoint helloServiceEndpoint(Bus bus, HelloService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/HelloService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    @Test
    void handlesPathCollisionsWithSuffix() {
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService(name = "API")
                public class UserService {
                }
                """
            ),
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService(name = "API")
                public class OrderService {
                }
                """
            ),
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    // @NeedsReview: Path collision resolved with suffix. Original path: /API
                    @Bean
                    public Endpoint orderServiceEndpoint(Bus bus, OrderService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/API_1");
                        return endpoint;
                    }

                    // @NeedsReview: Path collision resolved with suffix. Original path: /API
                    @Bean
                    public Endpoint userServiceEndpoint(Bus bus, UserService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/API_2");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    @Test
    void preservesExistingApplicationProperties() {
        // When application.properties exists, it should NOT be overwritten
        // (default basePath is /services, so no cxf.path is needed)
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class HelloService {
                    public String sayHello(String name) {
                        return "Hello, " + name;
                    }
                }
                """
            ),
            // Existing application.properties - should be preserved unchanged
            text(
                """
                spring.application.name=myapp
                server.port=8080
                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            // The generated configuration (default basePath, no cxf.path comment)
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    @Bean
                    public Endpoint helloServiceEndpoint(Bus bus, HelloService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/HelloService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    @Test
    void detectsCxfPathWithColonDelimiter() {
        // When cxf.path is already configured with colon delimiter (:),
        // the recipe should NOT add duplicate configuration.
        // Properties files allow both '=' and ':' as delimiters.
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class HelloService {
                    public String sayHello(String name) {
                        return "Hello, " + name;
                    }
                }
                """
            ),
            // Existing application.properties with cxf.path using ':' delimiter
            // Should be preserved unchanged (no duplicate cxf.path added)
            text(
                """
                spring.application.name=myapp
                cxf.path: /api
                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            // The generated configuration (default basePath, no cxf.path comment)
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    @Bean
                    public Endpoint helloServiceEndpoint(Bus bus, HelloService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/HelloService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    @Test
    void detectsCxfPathInYamlNestedStyle() {
        // When cxf.path is configured in YAML nested style (cxf:\n  path:),
        // the recipe should detect it and NOT add duplicate configuration.
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class HelloService {
                    public String sayHello(String name) {
                        return "Hello, " + name;
                    }
                }
                """
            ),
            // YAML with nested cxf.path - should be detected (not duplicated)
            text(
                """
                spring:
                  application:
                    name: myapp
                cxf:
                  path: /api/v2
                server:
                  port: 8080
                """,
                spec -> spec.path("src/main/resources/application.yml")
            ),
            // The generated configuration (no duplicate cxf.path needed)
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    @Bean
                    public Endpoint helloServiceEndpoint(Bus bus, HelloService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/HelloService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    @Test
    void detectsCxfPathInProfileSpecificProperties() {
        // When cxf.path is configured in application-prod.properties,
        // the recipe should detect it and NOT add duplicate configuration.
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class HelloService {
                    public String sayHello(String name) {
                        return "Hello, " + name;
                    }
                }
                """
            ),
            // Main application.properties without cxf.path
            text(
                """
                spring.application.name=myapp
                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            // Profile-specific properties with cxf.path - should be detected
            text(
                """
                cxf.path=/api/v1
                """,
                spec -> spec.path("src/main/resources/application-prod.properties")
            ),
            // The generated configuration (no duplicate cxf.path needed)
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    @Bean
                    public Endpoint helloServiceEndpoint(Bus bus, HelloService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/HelloService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    @Test
    void doesNotFalsePositiveOnUnrelatedYamlPath() {
        // When YAML has a 'path:' key outside of 'cxf:' block,
        // it should NOT be detected as cxf.path (no false positive).
        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class HelloService {
                    public String sayHello(String name) {
                        return "Hello, " + name;
                    }
                }
                """
            ),
            // YAML with path: in different sections - should NOT trigger false positive
            text(
                """
                spring:
                  application:
                    name: myapp
                server:
                  path: /context
                logging:
                  path: /var/log
                ---
                # Second document with path
                another:
                  path: /unrelated
                """,
                spec -> spec.path("src/main/resources/application.yml")
            ),
            // The generated configuration (default basePath, no cxf.path detected)
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JaxwsConfiguration {

                    @Bean
                    public Endpoint helloServiceEndpoint(Bus bus, HelloService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/HelloService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    @Test
    void generatesApplicationPropertiesForNonDefaultBasePath() {
        // Inject a test configuration with non-default basePath
        Path moduleRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        ProjectConfiguration config = new ProjectConfiguration(
            null, null, null, null, // source roots (use defaults)
            null, null,             // timer strategy, cluster mode
            null, null, null, null, // jaxrs client settings
            null,                   // jms provider
            null, null, null,       // remote, inject, jsf strategies
            false, false,           // keepWildFlyPlugins, bootPluginInProfiles
            null,                   // allowedEjbTypes
            "cxf",                  // jaxwsProvider
            "/api/v2",              // non-default basePath
            null, null              // jaxrs server settings (JRS-002)
        );
        ProjectConfigurationLoader.injectForTest(moduleRoot, config);

        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class HelloService {
                    public String sayHello(String name) {
                        return "Hello, " + name;
                    }
                }
                """
            ),
            // Generated configuration with basePath comment
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                // CXF base path: /api/v2 (configured in application.properties)
                @Configuration
                public class JaxwsConfiguration {

                    @Bean
                    public Endpoint helloServiceEndpoint(Bus bus, HelloService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/HelloService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            ),
            // Generated application.properties with cxf.path
            text(
                null,
                """
                # CXF JAX-WS Configuration
                # Generated by EJB-to-Spring migration
                cxf.path=/api/v2
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void appendsCxfPathToExistingApplicationProperties() {
        // Inject a test configuration with non-default basePath
        Path moduleRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        ProjectConfiguration config = new ProjectConfiguration(
            null, null, null, null, // source roots (use defaults)
            null, null,             // timer strategy, cluster mode
            null, null, null, null, // jaxrs client settings
            null,                   // jms provider
            null, null, null,       // remote, inject, jsf strategies
            false, false,           // keepWildFlyPlugins, bootPluginInProfiles
            null,                   // allowedEjbTypes
            "cxf",                  // jaxwsProvider
            "/custom/path",         // non-default basePath
            null, null              // jaxrs server settings (JRS-002)
        );
        ProjectConfigurationLoader.injectForTest(moduleRoot, config);

        rewriteRun(
            java(
                """
                package com.example.service;

                import jakarta.jws.WebService;

                @WebService
                public class HelloService {
                    public String sayHello(String name) {
                        return "Hello, " + name;
                    }
                }
                """
            ),
            // Existing application.properties - should have cxf.path appended
            properties(
                """
                spring.application.name=myapp
                server.port=8080
                """,
                """
                spring.application.name=myapp
                server.port=8080
                # CXF JAX-WS base path (auto-generated)
                cxf.path=/custom/path
                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            // Generated configuration with basePath comment
            java(
                null,
                """
                package com.example.service;

                import jakarta.xml.ws.Endpoint;
                import org.apache.cxf.Bus;
                import org.apache.cxf.jaxws.EndpointImpl;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                // CXF base path: /custom/path (configured in application.properties)
                @Configuration
                public class JaxwsConfiguration {

                    @Bean
                    public Endpoint helloServiceEndpoint(Bus bus, HelloService service) {
                        EndpointImpl endpoint = new EndpointImpl(bus, service);
                        endpoint.publish("/HelloService");
                        return endpoint;
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/JaxwsConfiguration.java")
            )
        );
    }

    // Safety guarantees implemented in the recipe:
    // 1. Scanner checks all config files: application.properties, application-*.properties, application.yml
    // 2. Both '=' and ':' delimiters are recognized in .properties files
    // 3. YAML nested style (cxf:\n  path:) detection with proper indentation tracking
    // 4. YAML document markers (---/...) are ignored to prevent false positives
    // 5. Properties.File AST is used when appending to preserve type for downstream recipes
    // 6. Resource roots are resolved relative to module root for multi-module projects
}
