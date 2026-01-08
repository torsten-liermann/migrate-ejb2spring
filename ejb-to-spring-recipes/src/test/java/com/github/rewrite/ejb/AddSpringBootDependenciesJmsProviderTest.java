package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

class AddSpringBootDependenciesJmsProviderTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddSpringBootDependencies(null))
            .parser(JavaParser.fromJavaVersion().classpath("jakarta.jms-api"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void usesActiveMqStarterWhenProviderActivemq(@TempDir Path tempDir) throws IOException {
        ProjectConfigurationLoader.clearCache();
        Path projectDir = tempDir.resolve("activemq-project");
        Files.createDirectories(projectDir.resolve("src/main/java/com/example"));
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        Files.writeString(projectDir.resolve("project.yaml"), """
            migration:
              jms:
                provider: activemq
            """);

        rewriteRun(
            pomXml(
                """
                <project xmlns=\"http://maven.apache.org/POM/4.0.0\"
                         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                spec -> spec.path(projectDir.resolve("pom.xml").toString())
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("spring-boot-starter-activemq")
                            .doesNotContain("spring-boot-starter-artemis");
                        return actual;
                    })
            ),
            java(
                """
                package com.example;

                import jakarta.jms.Queue;

                public class JmsService {
                    private Queue queue;
                }
                """,
                spec -> spec.path(projectDir.resolve("src/main/java/com/example/JmsService.java").toString())
            )
        );
    }

    @Test
    void usesArtemisStarterWhenProviderEmbedded(@TempDir Path tempDir) throws IOException {
        ProjectConfigurationLoader.clearCache();
        Path projectDir = tempDir.resolve("embedded-project");
        Files.createDirectories(projectDir.resolve("src/main/java/com/example"));
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        Files.writeString(projectDir.resolve("project.yaml"), """
            migration:
              jms:
                provider: embedded
            """);

        rewriteRun(
            pomXml(
                """
                <project xmlns=\"http://maven.apache.org/POM/4.0.0\"
                         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"
                         xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>demo</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                spec -> spec.path(projectDir.resolve("pom.xml").toString())
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("spring-boot-starter-artemis");
                        return actual;
                    })
            ),
            java(
                """
                package com.example;

                import jakarta.jms.Queue;

                public class JmsService {
                    private Queue queue;
                }
                """,
                spec -> spec.path(projectDir.resolve("src/main/java/com/example/JmsService.java").toString())
            )
        );
    }
}
