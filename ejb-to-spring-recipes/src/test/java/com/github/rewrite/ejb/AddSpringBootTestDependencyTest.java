package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Tests for AddSpringBootTestDependency recipe.
 * <p>
 * Verifies:
 * <ul>
 *   <li>spring-boot-starter-test is added when @SpringBootTest is detected</li>
 *   <li>Dependency is added with test scope</li>
 *   <li>Idempotent when dependency already exists</li>
 *   <li>Not added when @SpringBootTest is not used</li>
 * </ul>
 */
class AddSpringBootTestDependencyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddSpringBootTestDependency(null))
            // Disable type validation - @SpringBootTest is not on test classpath
            // Recipe only checks import/annotation names, doesn't need type info
            .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void addsTestDependencyWhenSpringBootTestUsed() {
        rewriteRun(
            // Test class with @SpringBootTest
            java(
                """
                package com.example;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class MyApplicationTest {
                }
                """,
                spec -> spec.path("src/test/java/com/example/MyApplicationTest.java")
            ),
            // POM without spring-boot-starter-test
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
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter</artifactId>
                            <version>3.5.0</version>
                        </dependency>
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <version>3.5.0</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void noChangeWhenSpringBootTestNotUsed() {
        rewriteRun(
            // Test class WITHOUT @SpringBootTest
            java(
                """
                package com.example;

                import org.junit.jupiter.api.Test;

                public class MyTest {
                    @Test
                    void testSomething() {
                    }
                }
                """,
                spec -> spec.path("src/test/java/com/example/MyTest.java")
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
    void idempotentWhenTestDependencyExists() {
        rewriteRun(
            // Test class with @SpringBootTest
            java(
                """
                package com.example;

                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class MyApplicationTest {
                }
                """,
                spec -> spec.path("src/test/java/com/example/MyApplicationTest.java")
            ),
            // POM already has spring-boot-starter-test - should remain unchanged
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
                        <dependency>
                            <groupId>org.springframework.boot</groupId>
                            <artifactId>spring-boot-starter-test</artifactId>
                            <version>3.5.0</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                </project>
                """
            )
        );
    }

    @Test
    void noChangeForMainSourceSpringBootTestUsage() {
        // Edge case: If somehow @SpringBootTest is in main source (which is unusual),
        // we should not detect it since we only scan test sources
        rewriteRun(
            // File in main (not test) with SpringBootTest import
            java(
                """
                package com.example;

                import org.springframework.boot.test.context.SpringBootTest;

                // This is a weird case - SpringBootTest in main source
                public class MainClass {
                }
                """,
                spec -> spec.path("src/main/java/com/example/MainClass.java")
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
                </project>
                """
            )
        );
    }
}
