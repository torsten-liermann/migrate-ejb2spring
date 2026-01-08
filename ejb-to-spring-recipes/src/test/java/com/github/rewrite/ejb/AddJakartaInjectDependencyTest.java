/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
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

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Tests for AddJakartaInjectDependency recipe.
 * <p>
 * Verifies:
 * <ul>
 *   <li>jakarta.inject-api is added when JSR-330 annotations are detected via import</li>
 *   <li>jakarta.inject-api is added when JSR-330 annotations are used with FQN</li>
 *   <li>Dependency is NOT added when strategy is migrate-to-spring</li>
 *   <li>Idempotent when dependency already exists (direct or in dependencyManagement)</li>
 *   <li>WFQ-006: No duplicate when javax.inject:javax.inject (legacy) exists</li>
 *   <li>WFQ-006: No duplicate when dependency exists with different version</li>
 * </ul>
 * <p>
 * Note: Dependency is only added when strategy is "keep-jsr330" (default).
 */
class AddJakartaInjectDependencyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // Use keep-jsr330 strategy (default behavior)
        spec.recipe(new AddJakartaInjectDependency("keep-jsr330"))
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.inject-api", "javax.inject"));
    }

    @DocumentExample
    @Test
    void addsDependencyWhenJakartaNamedImported() {
        rewriteRun(
            // Class with jakarta.inject.Named import
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named("myService")
                public class MyService {
                }
                """
            ),
            // POM without jakarta.inject-api
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                            <version>3.5.0</version>
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
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.inject</groupId>
                            <artifactId>jakarta.inject-api</artifactId>
                            <version>2.0.1</version>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void addsDependencyWhenJakartaInjectImported() {
        rewriteRun(
            // Stub for CustomerService type resolution
            java(
                """
                package com.example;
                public class CustomerService {}
                """
            ),
            // Class with jakarta.inject.Inject import
            java(
                """
                package com.example;

                import jakarta.inject.Inject;

                public class OrderService {
                    @Inject
                    private CustomerService customerService;
                }
                """
            ),
            // POM without jakarta.inject-api
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
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
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.inject</groupId>
                            <artifactId>jakarta.inject-api</artifactId>
                            <version>2.0.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void addsDependencyWhenJavaxInjectImported() {
        rewriteRun(
            // Stub for CustomerService type resolution
            java(
                """
                package com.example;
                public class CustomerService {}
                """
            ),
            // Class with javax.inject.Inject import (legacy)
            java(
                """
                package com.example;

                import javax.inject.Inject;

                public class LegacyService {
                    @Inject
                    private CustomerService customerService;
                }
                """
            ),
            // POM should get jakarta.inject-api (for Spring 3.x compatibility)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
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
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.inject</groupId>
                            <artifactId>jakarta.inject-api</artifactId>
                            <version>2.0.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void addsDependencyWhenFqnAnnotationUsed() {
        // Test FQN usage like @jakarta.inject.Named without import statement
        rewriteRun(
            // Class with FQN annotation usage (no import)
            java(
                """
                package com.example;

                @jakarta.inject.Named("myService")
                public class MyService {
                }
                """
            ),
            // POM without jakarta.inject-api
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
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
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.inject</groupId>
                            <artifactId>jakarta.inject-api</artifactId>
                            <version>2.0.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void noDependencyWhenStrategyIsMigrateToSpring() {
        // Test that dependency is NOT added when strategy is migrate-to-spring
        rewriteRun(
            spec -> spec.recipe(new AddJakartaInjectDependency("migrate-to-spring")),
            // Class with jakarta.inject.Named import
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named("myService")
                public class MyService {
                }
                """
            ),
            // POM should remain unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void addsDependencyWithDefaultStrategy() {
        // Test that default strategy (no explicit strategy) adds dependency
        // because default is KEEP_JSR330
        rewriteRun(
            spec -> spec.recipe(new AddJakartaInjectDependency()),
            // Class with jakarta.inject.Named import
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named("myService")
                public class MyService {
                }
                """
            ),
            // POM should get jakarta.inject-api
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
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
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.inject</groupId>
                            <artifactId>jakarta.inject-api</artifactId>
                            <version>2.0.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void noChangeWhenNoJsr330AnnotationsUsed() {
        rewriteRun(
            // Class without JSR-330 annotations
            java(
                """
                package com.example;

                public class MyService {
                    private String name;

                    public String getName() {
                        return name;
                    }
                }
                """
            ),
            // POM should remain unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void idempotentWhenDependencyExists() {
        rewriteRun(
            // Class with jakarta.inject.Named import
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named("myService")
                public class MyService {
                }
                """
            ),
            // POM already has jakarta.inject-api - should remain unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.inject</groupId>
                            <artifactId>jakarta.inject-api</artifactId>
                            <version>2.0.1</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void idempotentWhenDependencyInDependencyManagement() {
        // WFQ-006: Should not duplicate when dependency is in dependencyManagement
        rewriteRun(
            // Class with jakarta.inject.Named import
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named("myService")
                public class MyService {
                }
                """
            ),
            // POM has jakarta.inject-api in dependencyManagement - should remain unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencyManagement>
                        <dependencies>
                            <dependency>
                                <groupId>jakarta.inject</groupId>
                                <artifactId>jakarta.inject-api</artifactId>
                                <version>2.0.1</version>
                            </dependency>
                        </dependencies>
                    </dependencyManagement>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.inject</groupId>
                            <artifactId>jakarta.inject-api</artifactId>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void noDuplicateWhenJavaxInjectExists() {
        // WFQ-006: Should not add jakarta.inject-api when legacy javax.inject:javax.inject exists
        // The legacy dependency provides the same JSR-330 API
        rewriteRun(
            // Class with javax.inject import (legacy namespace)
            java(
                """
                package com.example;

                import javax.inject.Named;

                @Named("myService")
                public class MyService {
                }
                """
            ),
            // POM has legacy javax.inject - should NOT add jakarta.inject-api
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>javax.inject</groupId>
                            <artifactId>javax.inject</artifactId>
                            <version>1</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void noDuplicateWhenDifferentVersion() {
        // When dependency exists with different version, should not add duplicate
        rewriteRun(
            // Class with jakarta.inject.Named import
            java(
                """
                package com.example;

                import jakarta.inject.Named;

                @Named("myService")
                public class MyService {
                }
                """
            ),
            // POM already has jakarta.inject-api with older version - should remain unchanged
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.inject</groupId>
                            <artifactId>jakarta.inject-api</artifactId>
                            <version>2.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }
}
