package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * Tests for MigrateEjbPackaging recipe.
 * <p>
 * Verifies:
 * <ul>
 *   <li>packaging=ejb is changed to packaging=jar</li>
 *   <li>packaging=war is left unchanged</li>
 *   <li>packaging=jar is left unchanged (idempotent)</li>
 *   <li>Only modules with EJB features are affected</li>
 * </ul>
 */
class MigrateEjbPackagingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEjbPackaging());
    }

    @DocumentExample
    @Test
    void changesEjbPackagingToJar() {
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
            // POM with ejb packaging
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
                    <packaging>ejb</packaging>
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
                </project>
                """
            )
        );
    }

    @Test
    void warPackagingLeftUnchanged() {
        rewriteRun(
            // Java source with EJB annotation
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
            // POM with war packaging - should not be changed
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-war</artifactId>
                    <version>1.0.0</version>
                    <packaging>war</packaging>
                </project>
                """
            )
        );
    }

    @Test
    void jarPackagingIdempotent() {
        rewriteRun(
            // Java source with EJB annotation
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
            // POM already has jar packaging - should not be changed
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>my-jar</artifactId>
                    <version>1.0.0</version>
                    <packaging>jar</packaging>
                </project>
                """
            )
        );
    }

    @Test
    void changesEjbPackagingWithSingletonAnnotation() {
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
                    <packaging>ejb</packaging>
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
                </project>
                """
            )
        );
    }

    @Test
    void changesEjbPackagingWithJavaxNamespace() {
        rewriteRun(
            java(
                """
                package com.example;

                import javax.ejb.Stateless;

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
                    <packaging>ejb</packaging>
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
                </project>
                """
            )
        );
    }
}
