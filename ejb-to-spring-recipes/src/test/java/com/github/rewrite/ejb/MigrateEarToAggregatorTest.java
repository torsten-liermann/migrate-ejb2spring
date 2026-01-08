package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Tests for MigrateEarToAggregator recipe.
 * Verifies that EAR packaging is converted to POM aggregator packaging
 * and maven-ear-plugin is removed.
 */
class MigrateEarToAggregatorTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEarToAggregator());
    }

    @Test
    void convertsEarToPom() {
        rewriteRun(
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>myapp-ear</artifactId>
                    <version>1.0.0</version>
                    <packaging>ear</packaging>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>myapp-ear</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                </project>
                """
            )
        );
    }

    @Test
    void removesEarPlugin() {
        rewriteRun(
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>myapp-ear</artifactId>
                    <version>1.0.0</version>
                    <packaging>ear</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-ear-plugin</artifactId>
                                <version>3.3.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.11.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>myapp-ear</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.11.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
            )
        );
    }

    @Test
    void preservesModulesSection() {
        rewriteRun(
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>myapp-ear</artifactId>
                    <version>1.0.0</version>
                    <packaging>ear</packaging>
                    <modules>
                        <module>myapp-ejb</module>
                        <module>myapp-web</module>
                    </modules>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>myapp-ear</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>myapp-ejb</module>
                        <module>myapp-web</module>
                    </modules>
                </project>
                """
            )
        );
    }

    @Test
    void doesNotChangeJarPackaging() {
        rewriteRun(
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>myapp</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                </project>
                """
                // No second argument = no changes expected
            )
        );
    }

    @Test
    void doesNotChangeWarPackaging() {
        rewriteRun(
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>myapp</artifactId>
                    <version>1.0.0</version>
                    <packaging>war</packaging>
                </project>
                """
                // No second argument = no changes expected
            )
        );
    }
}
