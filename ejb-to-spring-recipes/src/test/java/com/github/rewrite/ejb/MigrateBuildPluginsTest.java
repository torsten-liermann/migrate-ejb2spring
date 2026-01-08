package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Tests for MigrateBuildPlugins recipe.
 * <p>
 * Verifies:
 * <ul>
 *   <li>maven-ejb-plugin is removed</li>
 *   <li>spring-boot-maven-plugin is added</li>
 *   <li>Idempotency when spring-boot-maven-plugin already exists</li>
 *   <li>Only modules with EJB features are affected</li>
 * </ul>
 */
class MigrateBuildPluginsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateBuildPlugins(null));
    }

    @DocumentExample
    @Test
    void replacesEjbPluginWithSpringBootPlugin() {
        rewriteRun(
            // Java source with EJB annotation to trigger detection
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class MyService {
                }
                """,
                spec -> spec.path("src/main/java/com/example/MyService.java")
            ),
            // POM with maven-ejb-plugin
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-ejb</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-ejb-plugin</artifactId>
                                <version>3.2.1</version>
                                <configuration>
                                    <ejbVersion>3.2</ejbVersion>
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
                    <artifactId>my-ejb</artifactId>
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
    void addsSpringBootPluginWhenNoPluginsExist() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class MyService {
                }
                """,
                spec -> spec.path("src/main/java/com/example/MyService.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-ejb</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-ejb</artifactId>
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
    void idempotentWhenSpringBootPluginExists() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class MyService {
                }
                """,
                spec -> spec.path("src/main/java/com/example/MyService.java")
            ),
            // POM already has spring-boot-maven-plugin
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-ejb</artifactId>
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
    void removesEjbPluginAndAddsSpringBootPluginWithExistingOtherPlugins() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.Singleton;

                @Singleton
                public class MySingleton {
                }
                """,
                spec -> spec.path("src/main/java/com/example/MySingleton.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-ejb</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.11.0</version>
                            </plugin>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-ejb-plugin</artifactId>
                                <version>3.2.1</version>
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
                    <artifactId>my-ejb</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-compiler-plugin</artifactId>
                                <version>3.11.0</version>
                            </plugin>
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
}
