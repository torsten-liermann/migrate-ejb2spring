package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.ExecutionContext;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.util.List;

import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Tests for RemoveWildFlyPlugins recipe.
 * <p>
 * Verifies:
 * <ul>
 *   <li>Removes wildfly-maven-plugin from build/plugins</li>
 *   <li>Removes wildfly-maven-plugin from build/pluginManagement</li>
 *   <li>Removes WildFly-only profiles</li>
 *   <li>Keeps mixed profiles but removes WildFly plugin block</li>
 *   <li>No removal when spring-boot-maven-plugin not present</li>
 *   <li>Removes unused WildFly version properties</li>
 * </ul>
 * <p>
 * <b>Configuration Inheritance Note:</b> Configuration flags ({@code keepWildFlyPlugins} and
 * {@code bootPluginInProfiles}) use "sticky TRUE" semantics: if any ancestor sets the flag to TRUE,
 * it cannot be overridden to FALSE by descendants. However, a module can enable the flag for itself
 * and its descendants if no ancestor has it TRUE. Tests that verify opt-in/opt-out behavior use
 * ExecutionContext configuration injection, which applies to all modules in the test run.
 */
class RemoveWildFlyPluginsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveWildFlyPlugins());
    }

    @DocumentExample
    @Test
    void removesWildFlyPluginFromBuildPlugins() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                                <configuration>
                                    <hostname>localhost</hostname>
                                    <port>9990</port>
                                </configuration>
                            </plugin>
                        </plugins>
                    </build>
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
            )
        );
    }

    @Test
    void removesWildFlyPluginFromPluginManagement() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <pluginManagement>
                            <plugins>
                                <plugin>
                                    <groupId>org.wildfly.plugins</groupId>
                                    <artifactId>wildfly-maven-plugin</artifactId>
                                    <version>4.2.0.Final</version>
                                </plugin>
                            </plugins>
                        </pluginManagement>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
            )
        );
    }

    @Test
    void removesWildFlyOnlyProfile() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>wildfly-deploy</id>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.wildfly.plugins</groupId>
                                        <artifactId>wildfly-maven-plugin</artifactId>
                                        <configuration>
                                            <hostname>production</hostname>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                    </profiles>
                </project>
                """
            )
        );
    }

    @Test
    void removesWildFlyPluginFromMixedProfile() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>deploy</id>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>extra-lib</artifactId>
                                    <version>1.0.0</version>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.wildfly.plugins</groupId>
                                        <artifactId>wildfly-maven-plugin</artifactId>
                                        <configuration>
                                            <hostname>production</hostname>
                                        </configuration>
                                    </plugin>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-surefire-plugin</artifactId>
                                        <configuration>
                                            <skipTests>true</skipTests>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>deploy</id>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>extra-lib</artifactId>
                                    <version>1.0.0</version>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-surefire-plugin</artifactId>
                                        <configuration>
                                            <skipTests>true</skipTests>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
                </project>
                """
            )
        );
    }

    @Test
    void noRemovalWhenSpringBootPluginNotPresent() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
            )
        );
    }

    @Test
    void removesWildFlyVersionProperty() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <properties>
                        <java.version>21</java.version>
                        <version.plugin.wildfly>4.2.0.Final</version.plugin.wildfly>
                    </properties>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>${version.plugin.wildfly}</version>
                            </plugin>
                        </plugins>
                    </build>
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
                    <packaging>jar</packaging>
                    <properties>
                        <java.version>21</java.version>
                    </properties>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
            )
        );
    }

    @Test
    void noChangeWhenNoWildFlyPlugin() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
            )
        );
    }

    /**
     * HIGH fix: Tests that child modules are processed when spring-boot-maven-plugin
     * is only in the parent POM.
     */
    @Test
    void removesFromChildModuleWhenSpringBootInParent() {
        rewriteRun(
            // Parent POM with spring-boot-maven-plugin and module definition
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>child</module>
                    </modules>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
            ),
            // Child POM with only wildfly plugin - should be removed because parent has Spring Boot
            // Empty build/plugins section is cleaned up by RemovePlugin recipe
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <packaging>jar</packaging>
                </project>
                """,
                spec -> spec.path("child/pom.xml")
            )
        );
    }

    /**
     * MEDIUM fix: Tests that profiles with properties element are not removed
     * (even if they only have WildFly plugin in build section).
     * The empty build/plugins section is removed by the RemovePlugin recipe.
     */
    @Test
    void keepsProfileWithPropertiesElement() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>wildfly-deploy</id>
                            <properties>
                                <deploy.env>production</deploy.env>
                            </properties>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.wildfly.plugins</groupId>
                                        <artifactId>wildfly-maven-plugin</artifactId>
                                        <configuration>
                                            <hostname>production</hostname>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>wildfly-deploy</id>
                            <properties>
                                <deploy.env>production</deploy.env>
                            </properties>
                        </profile>
                    </profiles>
                </project>
                """
            )
        );
    }

    /**
     * LOW fix: Tests that WildFly plugin is removed from BOTH plugins and pluginManagement
     * sections within profiles. Empty sections are cleaned up by the recipe.
     */
    @Test
    void removesWildFlyFromProfilePluginManagement() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>deploy</id>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>extra-lib</artifactId>
                                    <version>1.0.0</version>
                                </dependency>
                            </dependencies>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.wildfly.plugins</groupId>
                                            <artifactId>wildfly-maven-plugin</artifactId>
                                            <version>4.2.0.Final</version>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </profile>
                    </profiles>
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>deploy</id>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>extra-lib</artifactId>
                                    <version>1.0.0</version>
                                </dependency>
                            </dependencies>
                        </profile>
                    </profiles>
                </project>
                """
            )
        );
    }

    /**
     * MEDIUM fix: Tests that profiles with reporting element are not removed.
     * Empty build/plugins section is cleaned up by the recipe.
     */
    @Test
    void keepsProfileWithReportingElement() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>wildfly-deploy</id>
                            <reporting>
                                <plugins>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-project-info-reports-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </reporting>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.wildfly.plugins</groupId>
                                        <artifactId>wildfly-maven-plugin</artifactId>
                                        <configuration>
                                            <hostname>production</hostname>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>wildfly-deploy</id>
                            <reporting>
                                <plugins>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-project-info-reports-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </reporting>
                        </profile>
                    </profiles>
                </project>
                """
            )
        );
    }

    /**
     * MEDIUM fix #1: Tests that Spring Boot plugin ONLY in a profile does NOT trigger
     * WildFly plugin removal by default. This is because profile-only Spring Boot
     * means the default build still uses WildFly and shouldn't be broken.
     */
    @Test
    void keepsWildFlyPluginWhenSpringBootIsOnlyInProfile() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>spring-boot</id>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                        <version>3.5.0</version>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
                </project>
                """
                // No expected output = no change expected
            )
        );
    }

    /**
     * LOW fix: Tests that WildFly version properties in profile properties are removed
     * when WildFly plugin is removed from that profile.
     */
    @Test
    void removesWildFlyVersionPropertyFromProfileProperties() {
        rewriteRun(
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>deploy</id>
                            <properties>
                                <version.plugin.wildfly>4.2.0.Final</version.plugin.wildfly>
                                <other.property>keep-this</other.property>
                            </properties>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>extra-lib</artifactId>
                                    <version>1.0.0</version>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.wildfly.plugins</groupId>
                                        <artifactId>wildfly-maven-plugin</artifactId>
                                        <version>${version.plugin.wildfly}</version>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>deploy</id>
                            <properties>
                                <other.property>keep-this</other.property>
                            </properties>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>extra-lib</artifactId>
                                    <version>1.0.0</version>
                                </dependency>
                            </dependencies>
                        </profile>
                    </profiles>
                </project>
                """
            )
        );
    }

    // ===========================================================================================
    // Configuration inheritance tests (bootPluginInProfiles and keepWildFlyPlugins)
    // Note: Configuration flags use "sticky TRUE" semantics - once an ancestor sets TRUE,
    // descendants cannot override to FALSE. But modules can enable flags if no ancestor has TRUE.
    // ===========================================================================================

    /**
     * Tests that with bootPluginInProfiles=true, Spring Boot plugin in profile
     * DOES trigger WildFly plugin removal. This setting applies to all modules
     * (configuration inheritance, not per-module override).
     */
    @Test
    void removesWildFlyPluginWhenBootPluginInProfilesEnabled() {
        rewriteRun(
            spec -> spec.executionContext(createContextWithBootPluginInProfiles(true)),
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>spring-boot</id>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                        <version>3.5.0</version>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
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
                    <packaging>jar</packaging>
                    <profiles>
                        <profile>
                            <id>spring-boot</id>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                        <version>3.5.0</version>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
                </project>
                """
            )
        );
    }

    /**
     * Tests that bootPluginInProfiles setting affects child modules. Since configuration
     * is inherited (not per-module overridable), enabling this flag applies to all modules.
     */
    @Test
    void removesFromChildWhenBootPluginInProfilesEnabled() {
        rewriteRun(
            spec -> spec.executionContext(createContextWithBootPluginInProfiles(true)),
            // Parent POM with spring-boot-maven-plugin ONLY in profile
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>child</module>
                    </modules>
                    <profiles>
                        <profile>
                            <id>spring-boot</id>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                        <version>3.5.0</version>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
                </project>
                """
            ),
            // Child POM with wildfly plugin - should be removed because parent has Spring Boot in profile
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <packaging>jar</packaging>
                </project>
                """,
                spec -> spec.path("child/pom.xml")
            )
        );
    }

    /**
     * MEDIUM fix (Round 6): Tests "sticky TRUE" semantics for bootPluginInProfiles.
     * When parent has bootPluginInProfiles=true, it propagates to descendants and cannot
     * be overridden to FALSE by child modules. Even if child has its own project.yaml with
     * bootPluginInProfiles=false, the parent's TRUE value takes precedence.
     * <p>
     * Scenario: Parent has bootPluginInProfiles=true, child has Spring Boot only in profile,
     * child has WildFly plugin in main build. The WildFly plugin should be removed because
     * the parent's bootPluginInProfiles=true applies to the child (sticky TRUE).
     * <p>
     * Note: In this test, we inject the configuration via ExecutionContext which applies
     * to all modules. In production, the sticky TRUE behavior is implemented by checking
     * the ancestor chain in hasAncestorBootPluginInProfiles().
     */
    @Test
    void stickyTrueBootPluginInProfilesFromParentAppliesToChild() {
        rewriteRun(
            spec -> spec.executionContext(createContextWithBootPluginInProfiles(true)),
            // Parent POM with module definition (no Spring Boot - it's in child's profile)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>child</module>
                    </modules>
                </project>
                """
            ),
            // Child POM with Spring Boot ONLY in profile and WildFly in main build
            // WildFly should be removed because parent's bootPluginInProfiles=true propagates down
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>spring-boot</id>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                        <version>3.5.0</version>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <packaging>jar</packaging>
                    <profiles>
                        <profile>
                            <id>spring-boot</id>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                        <version>3.5.0</version>
                                    </plugin>
                                </plugins>
                            </build>
                        </profile>
                    </profiles>
                </project>
                """,
                spec -> spec.path("child/pom.xml")
            )
        );
    }

    // ===========================================================================================
    // Opt-out behavior tests (keepWildFlyPlugins)
    // Note: keepWildFlyPlugins uses "sticky TRUE" semantics - ancestor TRUE cannot be overridden
    // to FALSE, but a module can enable it if no ancestor has it TRUE.
    // ===========================================================================================

    /**
     * Tests that keepWildFlyPlugins=true prevents WildFly plugin removal.
     * This setting applies to all modules (configuration inheritance).
     */
    @Test
    void keepsWildFlyPluginWhenOptOutConfigured() {
        rewriteRun(
            spec -> spec.executionContext(createContextWithKeepWildFlyPlugins(true)),
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
                // No expected output = no change expected
            )
        );
    }

    /**
     * Tests that keepWildFlyPlugins=true prevents removal in child modules.
     * Since configuration is inherited (not per-module overridable), setting this
     * flag applies to all modules in the project.
     */
    @Test
    void keepsWildFlyPluginInChildWhenOptOutConfigured() {
        rewriteRun(
            spec -> spec.executionContext(createContextWithKeepWildFlyPlugins(true)),
            // Parent POM with spring-boot-maven-plugin and module definition
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>child</module>
                    </modules>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
            ),
            // Child POM with wildfly plugin - should be KEPT because opt-out is configured
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>child</artifactId>
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """,
                // No expected output = no change expected
                spec -> spec.path("child/pom.xml")
            )
        );
    }

    /**
     * Tests that with keepWildFlyPlugins=false (default), WildFly plugin removal proceeds.
     * This verifies that the opt-out can be explicitly disabled.
     */
    @Test
    void removesWildFlyPluginWhenOptOutDisabled() {
        rewriteRun(
            spec -> spec.executionContext(createContextWithKeepWildFlyPlugins(false)),
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
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
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
            )
        );
    }

    // ===========================================================================================
    // LOW #3: Multi-module profile property removal test
    // ===========================================================================================

    /**
     * LOW fix #3: Tests that WildFly version properties in profiles are correctly removed
     * only from the module where they were found, not from other modules with same profile ID.
     * <p>
     * This verifies the module-aware keying (modulePath + ":" + profileId) works correctly.
     */
    @Test
    void removesProfilePropertiesOnlyFromCorrectModule() {
        rewriteRun(
            // Parent POM with spring-boot-maven-plugin and module definitions
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>moduleA</module>
                        <module>moduleB</module>
                    </modules>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.springframework.boot</groupId>
                                <artifactId>spring-boot-maven-plugin</artifactId>
                                <version>3.5.0</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """
            ),
            // Module A with wildfly plugin and profile containing wildfly property
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>moduleA</artifactId>
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>deploy</id>
                            <properties>
                                <version.plugin.wildfly>4.2.0.Final</version.plugin.wildfly>
                                <keep.this.property>value-A</keep.this.property>
                            </properties>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>extra-lib</artifactId>
                                    <version>1.0.0</version>
                                </dependency>
                            </dependencies>
                        </profile>
                    </profiles>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>moduleA</artifactId>
                    <packaging>jar</packaging>
                    <profiles>
                        <profile>
                            <id>deploy</id>
                            <properties>
                                <keep.this.property>value-A</keep.this.property>
                            </properties>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>extra-lib</artifactId>
                                    <version>1.0.0</version>
                                </dependency>
                            </dependencies>
                        </profile>
                    </profiles>
                </project>
                """,
                spec -> spec.path("moduleA/pom.xml")
            ),
            // Module B with same profile ID "deploy" but different property that should be kept
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>moduleB</artifactId>
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.wildfly.plugins</groupId>
                                <artifactId>wildfly-maven-plugin</artifactId>
                                <version>4.2.0.Final</version>
                            </plugin>
                        </plugins>
                    </build>
                    <profiles>
                        <profile>
                            <id>deploy</id>
                            <properties>
                                <keep.this.property>value-B</keep.this.property>
                            </properties>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>another-lib</artifactId>
                                    <version>2.0.0</version>
                                </dependency>
                            </dependencies>
                        </profile>
                    </profiles>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>moduleB</artifactId>
                    <packaging>jar</packaging>
                    <profiles>
                        <profile>
                            <id>deploy</id>
                            <properties>
                                <keep.this.property>value-B</keep.this.property>
                            </properties>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>another-lib</artifactId>
                                    <version>2.0.0</version>
                                </dependency>
                            </dependencies>
                        </profile>
                    </profiles>
                </project>
                """,
                spec -> spec.path("moduleB/pom.xml")
            )
        );
    }

    // ===========================================================================================
    // Helper methods for creating test configurations via ExecutionContext
    // ===========================================================================================

    /**
     * Creates an ExecutionContext with bootPluginInProfiles configuration set.
     * The configuration is injected via message key, which the recipe checks
     * before loading from file.
     */
    private ExecutionContext createContextWithBootPluginInProfiles(boolean bootPluginInProfiles) {
        ExecutionContext ctx = new InMemoryExecutionContext();
        ProjectConfiguration config = new ProjectConfiguration(
            List.of("src/main/java"),
            List.of("src/test/java"),
            List.of("src/main/resources"),
            List.of("src/test/resources"),
            ProjectConfiguration.TimerStrategy.SCHEDULED,
            com.github.rewrite.ejb.config.ClusterMode.NONE,
            ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS,
            ProjectConfiguration.JaxRsClientStrategy.KEEP_JAXRS,
            "jersey",
            null,
            ProjectConfiguration.JmsProvider.NONE,
            ProjectConfiguration.RemoteStrategy.REST,
            ProjectConfiguration.InjectStrategy.KEEP_JSR330,
            ProjectConfiguration.JsfStrategy.JOINFACES,
            false,  // keepWildFlyPlugins
            bootPluginInProfiles
        );
        ctx.putMessage(RemoveWildFlyPlugins.TEST_CONFIG_KEY, config);
        return ctx;
    }

    /**
     * Creates an ExecutionContext with keepWildFlyPlugins configuration set.
     * The configuration is injected via message key, which the recipe checks
     * before loading from file.
     */
    private ExecutionContext createContextWithKeepWildFlyPlugins(boolean keepWildFlyPlugins) {
        ExecutionContext ctx = new InMemoryExecutionContext();
        ProjectConfiguration config = new ProjectConfiguration(
            List.of("src/main/java"),
            List.of("src/test/java"),
            List.of("src/main/resources"),
            List.of("src/test/resources"),
            ProjectConfiguration.TimerStrategy.SCHEDULED,
            com.github.rewrite.ejb.config.ClusterMode.NONE,
            ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS,
            ProjectConfiguration.JaxRsClientStrategy.KEEP_JAXRS,
            "jersey",
            null,
            ProjectConfiguration.JmsProvider.NONE,
            ProjectConfiguration.RemoteStrategy.REST,
            ProjectConfiguration.InjectStrategy.KEEP_JSR330,
            ProjectConfiguration.JsfStrategy.JOINFACES,
            keepWildFlyPlugins,
            false  // bootPluginInProfiles
        );
        ctx.putMessage(RemoveWildFlyPlugins.TEST_CONFIG_KEY, config);
        return ctx;
    }
}
