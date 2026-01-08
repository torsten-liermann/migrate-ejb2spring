package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.java.Assertions.mavenProject;
import static org.openrewrite.java.Assertions.srcMainJava;
import static org.openrewrite.java.Assertions.srcTestJava;
import static org.openrewrite.maven.Assertions.pomXml;

class AddTestcontainersDevApplicationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddTestcontainersDevApplication(
                "DemoApplication",
                "DevDemoApplication",
                "postgresql",
                "postgres:16-alpine"))
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion());
    }

    @Test
    void generatesDevWrapperAndConfig() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            mavenProject("demo",
                srcMainJava(
                    java(
                        """
                        package com.example;

                        public class Demo {
                        }
                        """,
                        spec -> spec.path("src/main/java/com/example/Demo.java")
                    )
                ),
                srcTestJava(
                    java(
                        null,
                        spec -> spec.path("com/example/DevContainersConfig.java")
                            .after(actual -> {
                                org.assertj.core.api.Assertions.assertThat(actual)
                                    .contains("class DevContainersConfig")
                                    .contains("@TestConfiguration(proxyBeanMethods = false)")
                                    .contains("PostgreSQLContainer")
                                    .contains("postgres:16-alpine");
                                return actual;
                            })
                    ),
                    java(
                        null,
                        spec -> spec.path("com/example/DevDemoApplication.java")
                            .after(actual -> {
                                org.assertj.core.api.Assertions.assertThat(actual)
                                    .contains("class DevDemoApplication")
                                    .contains("SpringApplication.from(DemoApplication::main)")
                                    .contains("DevContainersConfig.class");
                                return actual;
                            })
                    )
                ),
                pomXml(
                    """
                    <project xmlns="http://maven.apache.org/POM/4.0.0"
                             xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                             xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                        <modelVersion>4.0.0</modelVersion>
                        <groupId>com.example</groupId>
                        <artifactId>demo</artifactId>
                        <version>1.0.0</version>

                        <dependencyManagement>
                            <dependencies>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-dependencies</artifactId>
                                    <version>3.5.9</version>
                                    <type>pom</type>
                                    <scope>import</scope>
                                </dependency>
                            </dependencies>
                        </dependencyManagement>

                        <dependencies>
                            <dependency>
                                <groupId>org.postgresql</groupId>
                                <artifactId>postgresql</artifactId>
                                <scope>runtime</scope>
                            </dependency>
                        </dependencies>
                    </project>
                    """,
                    spec -> spec.after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("<artifactId>spring-boot-testcontainers</artifactId>")
                            .contains("<artifactId>spring-boot-test</artifactId>")
                            .contains("<groupId>org.testcontainers</groupId>")
                            .contains("<artifactId>postgresql</artifactId>")
                            .contains("<scope>test</scope>");
                        return actual;
                    })
                )
            )
        );
    }
}
