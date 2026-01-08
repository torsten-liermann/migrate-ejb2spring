/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Test for GenerateJaxRsServerConfig recipe (JRS-002)
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

import com.github.rewrite.ejb.config.ClusterMode;
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
import java.util.List;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

class GenerateJaxRsServerConfigTest implements RewriteTest {

    @BeforeEach
    void setUp() {
        ProjectConfigurationLoader.clearCache();
        ProjectConfigurationLoader.clearTestInjections();
    }

    @AfterEach
    void tearDown() {
        ProjectConfigurationLoader.clearTestInjections();
        ProjectConfigurationLoader.clearCache();
    }

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new GenerateJaxRsServerConfig())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context"));
    }

    private ProjectConfiguration createConfig(ProjectConfiguration.JaxRsStrategy strategy,
                                               String provider, String basePath) {
        return new ProjectConfiguration(
            null, null, null, null,  // source roots
            null, null,  // timer settings
            strategy,  // jaxRsStrategy
            null, null, null,  // client settings
            null,  // jmsProvider
            null,  // remoteStrategy
            null,  // injectStrategy
            null,  // jsfStrategy
            false, false,  // build flags
            null,  // allowedEjbTypes
            null, null,  // jaxws settings
            provider, basePath  // jaxrs server settings (JRS-002)
        );
    }

    @Test
    @DocumentExample
    void generatesJerseyConfigurationByDefault() {
        // No config injection - rely on defaults (KEEP_JAXRS strategy, jersey provider)
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.GET;

                @Path("/users")
                public class UserResource {
                    @GET
                    public String getUsers() {
                        return "users";
                    }
                }
                """
            ),
            java(
                null,
                """
                package com.example.api;

                import jakarta.ws.rs.ApplicationPath;
                import org.glassfish.jersey.server.ResourceConfig;
                import org.springframework.stereotype.Component;

                @Component
                @ApplicationPath("/api")
                public class JerseyConfiguration extends ResourceConfig {

                    public JerseyConfiguration() {
                        register(com.example.api.UserResource.class);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/api/JerseyConfiguration.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-jersey</artifactId>
                            <version>3.4.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void generatesResteasyConfigurationWhenConfigured() {
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        ProjectConfigurationLoader.injectForTest(projectRoot,
            createConfig(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS, "resteasy", "/rest"));

        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.GET;

                @Path("/orders")
                public class OrderResource {
                    @GET
                    public String getOrders() {
                        return "orders";
                    }
                }
                """
            ),
            java(
                null,
                """
                package com.example.api;

                import jakarta.ws.rs.ApplicationPath;
                import jakarta.ws.rs.core.Application;
                import java.util.Set;

                @ApplicationPath("/rest")
                public class ResteasyConfiguration extends Application {

                    @Override
                    public Set<Class<?>> getClasses() {
                        return Set.of(
                            com.example.api.OrderResource.class
                        );
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/api/ResteasyConfiguration.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.jboss.resteasy</groupId>
                            <artifactId>resteasy-servlet-spring-boot-starter</artifactId>
                            <version>6.3.0.Final</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void generatesCxfConfigurationWhenConfigured() {
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        ProjectConfigurationLoader.injectForTest(projectRoot,
            createConfig(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS, "cxf", "/services"));

        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.GET;

                @Path("/products")
                public class ProductResource {
                    @GET
                    public String getProducts() {
                        return "products";
                    }
                }
                """
            ),
            java(
                null,
                """
                package com.example.api;

                import org.apache.cxf.Bus;
                import org.apache.cxf.endpoint.Server;
                import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import java.util.List;

                @Configuration
                public class CxfJaxrsConfiguration {

                    @Bean
                    public Server jaxrsServer(Bus bus, com.example.api.ProductResource productResource) {
                        JAXRSServerFactoryBean factory = new JAXRSServerFactoryBean();
                        factory.setBus(bus);
                        factory.setAddress("/services");
                        factory.setServiceBeans(List.of(
                            productResource
                        ));
                        return factory.create();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/api/CxfJaxrsConfiguration.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.cxf</groupId>
                            <artifactId>cxf-spring-boot-starter-jaxrs</artifactId>
                            <version>4.1.4</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void handlesMultipleResources() {
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        ProjectConfigurationLoader.injectForTest(projectRoot,
            createConfig(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS, "jersey", "/api"));

        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.GET;

                @Path("/users")
                public class UserResource {
                    @GET
                    public String getUsers() {
                        return "users";
                    }
                }
                """
            ),
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.GET;

                @Path("/orders")
                public class OrderResource {
                    @GET
                    public String getOrders() {
                        return "orders";
                    }
                }
                """
            ),
            java(
                null,
                """
                package com.example.api;

                import jakarta.ws.rs.ApplicationPath;
                import org.glassfish.jersey.server.ResourceConfig;
                import org.springframework.stereotype.Component;

                @Component
                @ApplicationPath("/api")
                public class JerseyConfiguration extends ResourceConfig {

                    public JerseyConfiguration() {
                        register(com.example.api.OrderResource.class);
                        register(com.example.api.UserResource.class);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/api/JerseyConfiguration.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-jersey</artifactId>
                            <version>3.4.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void skipsWhenExistingConfigPresent() {
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        ProjectConfigurationLoader.injectForTest(projectRoot,
            createConfig(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS, null, "/api"));

        rewriteRun(
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;

                @Path("/users")
                public class UserResource {
                }
                """
            ),
            // Existing config with matching name pattern - should prevent generation
            java(
                """
                package com.example.api;

                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class JerseyConfig {
                    // Existing JAX-RS configuration
                }
                """
            ),
            // No new config should be generated, POM unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            )
        );
    }

    @Test
    void skipsTestSourceFiles() {
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        ProjectConfigurationLoader.injectForTest(projectRoot,
            createConfig(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS, null, "/api"));

        rewriteRun(
            // This is a test file - should be ignored
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;

                @Path("/test")
                public class TestResource {
                }
                """,
                spec -> spec.path("src/test/java/com/example/api/TestResource.java")
            ),
            // No config should be generated, POM unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            )
        );
    }

    @Test
    void skipsWhenStrategyNotKeepJaxrs() {
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        // Using MIGRATE_TO_SPRING_MVC strategy which is not KEEP_JAXRS
        ProjectConfigurationLoader.injectForTest(projectRoot,
            createConfig(ProjectConfiguration.JaxRsStrategy.MIGRATE_TO_SPRING_MVC, null, "/api"));

        rewriteRun(
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;

                @Path("/users")
                public class UserResource {
                }
                """
            ),
            // No config should be generated, POM unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            )
        );
    }

    @Test
    void usesCustomBasePath() {
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        ProjectConfigurationLoader.injectForTest(projectRoot,
            createConfig(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS, "cxf", "/custom/path"));

        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;

                @Path("/items")
                public class ItemResource {
                }
                """
            ),
            java(
                null,
                """
                package com.example.api;

                import org.apache.cxf.Bus;
                import org.apache.cxf.endpoint.Server;
                import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import java.util.List;

                @Configuration
                public class CxfJaxrsConfiguration {

                    @Bean
                    public Server jaxrsServer(Bus bus, com.example.api.ItemResource itemResource) {
                        JAXRSServerFactoryBean factory = new JAXRSServerFactoryBean();
                        factory.setBus(bus);
                        factory.setAddress("/custom/path");
                        factory.setServiceBeans(List.of(
                            itemResource
                        ));
                        return factory.create();
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/api/CxfJaxrsConfiguration.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.apache.cxf</groupId>
                            <artifactId>cxf-spring-boot-starter-jaxrs</artifactId>
                            <version>4.1.4</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void skipsWhenApplicationSubclassExists() {
        // Test: Existing Application subclass should prevent generation (Codex fix MEDIUM #2)
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        ProjectConfigurationLoader.injectForTest(projectRoot,
            createConfig(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS, null, "/api"));

        rewriteRun(
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;

                @Path("/users")
                public class UserResource {
                }
                """
            ),
            // Existing Application subclass - should prevent generation
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.core.Application;

                public class RestApplication extends Application {
                    // Custom JAX-RS Application
                }
                """
            ),
            // No new config should be generated, POM unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            )
        );
    }

    @Test
    void skipsWhenApplicationPathAnnotationExists() {
        // Test: Existing @ApplicationPath annotation should prevent generation (Codex fix MEDIUM #2)
        Path projectRoot = Paths.get("").toAbsolutePath().normalize();
        ProjectConfigurationLoader.injectForTest(projectRoot,
            createConfig(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS, null, "/api"));

        rewriteRun(
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.Path;

                @Path("/users")
                public class UserResource {
                }
                """
            ),
            // Existing class with @ApplicationPath - should prevent generation
            java(
                """
                package com.example.api;

                import jakarta.ws.rs.ApplicationPath;

                @ApplicationPath("/existing")
                public class ExistingApp {
                    // Already has ApplicationPath configured
                }
                """
            ),
            // No new config should be generated, POM unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>api-demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            )
        );
    }
}
