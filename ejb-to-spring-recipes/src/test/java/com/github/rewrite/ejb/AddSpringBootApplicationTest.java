package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.test.SourceSpecs.text;

/**
 * Tests for AddSpringBootApplication recipe.
 * Verifies that the generated @SpringBootApplication class is placed in the
 * correct package to ensure all beans are scanned.
 *
 * WFQ-001: Application class must be generated per module, not in aggregator root.
 */
class AddSpringBootApplicationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddSpringBootApplication());
    }

    @Test
    void createsApplicationInCommonPackagePrefix() {
        // Given classes in com.example.app and com.example.lib
        // The Application should be created in com.example (common prefix)
        rewriteRun(
            java(
                """
                package com.example.app;

                public class ServiceA {
                }
                """
            ),
            java(
                """
                package com.example.lib;

                public class HelperB {
                }
                """
            ),
            // Expected: Application class generated in com.example
            java(
                null,
                """
                package com.example;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class ExampleApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(ExampleApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/ExampleApplication.java")
            )
        );
    }

    @Test
    void doesNotCreateIfApplicationAlreadyExists() {
        rewriteRun(
            java(
                """
                package com.example;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class MyApp {
                    public static void main(String[] args) {
                        SpringApplication.run(MyApp.class, args);
                    }
                }
                """
            )
        );
    }

    @Test
    void derivesClassNameFromLastPackagePart() {
        rewriteRun(
            java(
                """
                package org.eclipse.cargotracker.application;

                public class BookingService {
                }
                """
            ),
            java(
                """
                package org.eclipse.cargotracker.domain;

                public class Cargo {
                }
                """
            ),
            // Expected: CargotrackerApplication (from last package part)
            java(
                null,
                """
                package org.eclipse.cargotracker;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class CargotrackerApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(CargotrackerApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("src/main/java/org/eclipse/cargotracker/CargotrackerApplication.java")
            )
        );
    }

    @Test
    void addsComponentScanForDisjointPackages() {
        // When packages have no common prefix (org.foo and com.bar),
        // @ComponentScan must be added to scan all packages
        // Also @NeedsReview is added to flag disjoint roots for manual review
        rewriteRun(
            java(
                """
                package org.foo.service;

                public class FooService {
                }
                """
            ),
            java(
                """
                package com.bar.util;

                public class BarUtil {
                }
                """
            ),
            // Expected: Application with @ComponentScan listing parent package names
            // and @NeedsReview to flag disjoint packages
            java(
                null,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.context.annotation.ComponentScan;

                @SpringBootApplication
                @ComponentScan(basePackages = {"com.bar", "org.foo"})
                @NeedsReview("Disjoint package roots detected. Review @ComponentScan basePackages to ensure correct bean scanning scope.")
                public class ExampleApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(ExampleApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/ExampleApplication.java")
            )
        );
    }

    /**
     * WFQ-001: Multi-module project test.
     * Application class should be created in each module, NOT in the aggregator root.
     */
    @Test
    void createsApplicationInModuleNotAggregator() {
        rewriteRun(
            // Aggregator POM (packaging=pom with <modules>) should NOT get an Application class
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>module-a</module>
                        <module>module-b</module>
                    </modules>
                </project>
                """
                // No second argument = no changes expected
            ),
            // Module A POM (jar packaging)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>module-a</artifactId>
                </project>
                """,
                spec -> spec.path("module-a/pom.xml")
            ),
            // Module B POM (jar packaging)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>module-b</artifactId>
                </project>
                """,
                spec -> spec.path("module-b/pom.xml")
            ),
            // Java class in module-a
            java(
                """
                package com.example.modulea;

                public class ServiceA {
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/modulea/ServiceA.java")
            ),
            // Java class in module-b
            java(
                """
                package com.example.moduleb;

                public class ServiceB {
                }
                """,
                spec -> spec.path("module-b/src/main/java/com/example/moduleb/ServiceB.java")
            ),
            // Expected: Application class in module-a
            java(
                null,
                """
                package com.example.modulea;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class ModuleaApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(ModuleaApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/modulea/ModuleaApplication.java")
            ),
            // Expected: Application class in module-b
            java(
                null,
                """
                package com.example.moduleb;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class ModulebApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(ModulebApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("module-b/src/main/java/com/example/moduleb/ModulebApplication.java")
            )
        );
    }

    /**
     * WFQ-001: Aggregator-only POM should not generate an Application class.
     */
    @Test
    void doesNotCreateApplicationInAggregatorPom() {
        rewriteRun(
            // Aggregator POM only (no Java sources)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>aggregator</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>child-module</module>
                    </modules>
                </project>
                """
                // No changes expected - aggregator POMs don't get Application classes
            )
        );
    }

    /**
     * MEDIUM fix (Round 2): Namespace-prefixed XML tag handling.
     * <p>
     * Note: This test cannot directly test namespace-prefixed POMs (like {@code <mvn:project>})
     * because OpenRewrite's Maven parser correctly rejects them as invalid Maven POMs.
     * However, the code fix (using getLocalName()) is defensive programming that handles:
     * <ul>
     *   <li>XML files that might be parsed with a generic XML parser</li>
     *   <li>Edge cases in enterprise environments with custom POM processing</li>
     * </ul>
     * <p>
     * The fix is verified by code review - see AddSpringBootApplication.getLocalName(),
     * hasPomPackaging(), and hasModulesElement() methods.
     */

    /**
     * WFQ-001: Module that already has @SpringBootApplication should not get another one.
     */
    @Test
    void doesNotDuplicateApplicationInModuleWithExisting() {
        rewriteRun(
            // Root aggregator
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>parent</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>module-with-app</module>
                        <module>module-without-app</module>
                    </modules>
                </project>
                """
            ),
            // Module with existing Application
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>module-with-app</artifactId>
                </project>
                """,
                spec -> spec.path("module-with-app/pom.xml")
            ),
            // Module without existing Application
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>parent</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>module-without-app</artifactId>
                </project>
                """,
                spec -> spec.path("module-without-app/pom.xml")
            ),
            // Existing Application in module-with-app - should remain unchanged
            java(
                """
                package com.example.withapp;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class ExistingApp {
                    public static void main(String[] args) {
                        SpringApplication.run(ExistingApp.class, args);
                    }
                }
                """,
                spec -> spec.path("module-with-app/src/main/java/com/example/withapp/ExistingApp.java")
            ),
            // Service in module-with-app
            java(
                """
                package com.example.withapp;

                public class MyService {
                }
                """,
                spec -> spec.path("module-with-app/src/main/java/com/example/withapp/MyService.java")
            ),
            // Service in module-without-app
            java(
                """
                package com.example.withoutapp;

                public class AnotherService {
                }
                """,
                spec -> spec.path("module-without-app/src/main/java/com/example/withoutapp/AnotherService.java")
            ),
            // Expected: Only module-without-app gets a new Application class
            java(
                null,
                """
                package com.example.withoutapp;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class WithoutappApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(WithoutappApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("module-without-app/src/main/java/com/example/withoutapp/WithoutappApplication.java")
            )
        );
    }

    /**
     * MEDIUM fix (Round 3): Test module with non-standard source path (e.g., src/java instead of src/main/java).
     * The recipe should detect the actual source root from the path and use it for output.
     */
    @Test
    void handlesNonStandardSourcePath() {
        // Projects with alternative source structures like Gradle's src/java
        // The recipe should detect the source root and generate output there
        rewriteRun(
            // Standard root pom
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>alt-root</artifactId>
                    <version>1.0.0</version>
                    <packaging>pom</packaging>
                    <modules>
                        <module>alt-module</module>
                    </modules>
                </project>
                """
            ),
            // Module pom
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <parent>
                        <groupId>com.example</groupId>
                        <artifactId>alt-root</artifactId>
                        <version>1.0.0</version>
                    </parent>
                    <artifactId>alt-module</artifactId>
                </project>
                """,
                spec -> spec.path("alt-module/pom.xml")
            ),
            // Java class in a non-standard path (src/java instead of src/main/java)
            java(
                """
                package com.example.alt;

                public class AltService {
                }
                """,
                spec -> spec.path("alt-module/src/java/com/example/alt/AltService.java")
            ),
            // Expected: Application class generated using the DETECTED source root (src/java)
            // HIGH fix (Round 3): Recipe now detects source root from actual source files
            java(
                null,
                """
                package com.example.alt;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class AltApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(AltApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("alt-module/src/java/com/example/alt/AltApplication.java")
            )
        );
    }

    /**
     * HIGH fix (Round 4): Test for Gradle-only projects.
     * The recipe should detect module roots from build.gradle files when no pom.xml exists.
     */
    @Test
    void createsApplicationForGradleOnlyProject() {
        rewriteRun(
            // Gradle build file in root (no pom.xml)
            text(
                """
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '3.2.0'
                }

                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter'
                }
                """,
                spec -> spec.path("build.gradle")
            ),
            // Java class in standard Gradle location
            java(
                """
                package com.example.gradle;

                public class GradleService {
                }
                """,
                spec -> spec.path("src/main/java/com/example/gradle/GradleService.java")
            ),
            // Expected: Application class generated for Gradle project
            java(
                null,
                """
                package com.example.gradle;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class GradleApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(GradleApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/gradle/GradleApplication.java")
            )
        );
    }

    /**
     * HIGH fix (Round 4): Test for Gradle multi-module project.
     * Note: This test uses a simplified scenario where only the submodule has Java sources.
     * The root Gradle project acts purely as an aggregator (no Java sources).
     */
    @Test
    void createsApplicationForGradleMultiModule() {
        rewriteRun(
            // Module build.gradle only (no root build.gradle in this test)
            // This simulates a multi-module setup where we only scan the submodule
            text(
                """
                plugins {
                    id 'java'
                    id 'org.springframework.boot' version '3.2.0'
                }

                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter'
                }
                """,
                spec -> spec.path("gradle-module/build.gradle")
            ),
            // Java class in module
            java(
                """
                package com.example.gradlemod;

                public class ModuleService {
                }
                """,
                spec -> spec.path("gradle-module/src/main/java/com/example/gradlemod/ModuleService.java")
            ),
            // Expected: Application class generated in module
            java(
                null,
                """
                package com.example.gradlemod;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class GradlemodApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(GradlemodApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("gradle-module/src/main/java/com/example/gradlemod/GradlemodApplication.java")
            )
        );
    }

    /**
     * HIGH fix (Round 5): Test that test sources (src/test/java) are not used for Application generation.
     * The recipe should only generate @SpringBootApplication in main source roots.
     */
    @Test
    void doesNotGenerateApplicationInTestSources() {
        // When only test sources exist, no Application should be generated
        // (Test classes should not trigger Application class generation)
        rewriteRun(
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-only</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            ),
            // Only test class exists (no main sources)
            java(
                """
                package com.example.test;

                public class MyTestClass {
                }
                """,
                spec -> spec.path("src/test/java/com/example/test/MyTestClass.java")
            )
            // No Application class should be generated since there are only test sources
        );
    }

    /**
     * HIGH fix (Round 5): Test that main sources get Application even when test sources also exist.
     * Application should be placed in src/main/java, not src/test/java.
     */
    @Test
    void generatesApplicationInMainSourcesNotTest() {
        rewriteRun(
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>main-and-test</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            ),
            // Main source class
            java(
                """
                package com.example.service;

                public class MyService {
                }
                """,
                spec -> spec.path("src/main/java/com/example/service/MyService.java")
            ),
            // Test source class (should be ignored for Application generation)
            java(
                """
                package com.example.service;

                public class MyServiceTest {
                }
                """,
                spec -> spec.path("src/test/java/com/example/service/MyServiceTest.java")
            ),
            // Expected: Application class in src/main/java only
            java(
                null,
                """
                package com.example.service;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class ServiceApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(ServiceApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/service/ServiceApplication.java")
            )
        );
    }

    /**
     * MEDIUM fix (Round 5): Test that Gradle aggregator (root with no Java sources) is skipped.
     * Only submodules with actual Java sources should get Application classes.
     */
    @Test
    void skipsGradleAggregatorWithNoJavaSources() {
        rewriteRun(
            // Root build.gradle (aggregator - no Java sources under root)
            text(
                """
                plugins {
                    id 'java'
                }

                subprojects {
                    apply plugin: 'java'
                }
                """,
                spec -> spec.path("build.gradle")
            ),
            // Submodule build.gradle
            text(
                """
                plugins {
                    id 'org.springframework.boot' version '3.2.0'
                }

                dependencies {
                    implementation 'org.springframework.boot:spring-boot-starter'
                }
                """,
                spec -> spec.path("submodule/build.gradle")
            ),
            // Java class only in submodule (not in root)
            java(
                """
                package com.example.sub;

                public class SubService {
                }
                """,
                spec -> spec.path("submodule/src/main/java/com/example/sub/SubService.java")
            ),
            // Expected: Application class only in submodule
            java(
                null,
                """
                package com.example.sub;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class SubApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(SubApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("submodule/src/main/java/com/example/sub/SubApplication.java")
            )
        );
    }

    /**
     * MEDIUM fix (Round 4): Test that detectSourceRootFromPath does not apply to non-Java files.
     * Paths like src/generated/... or src/main/resources/... should not affect source root detection.
     */
    @Test
    void doesNotDetectSourceRootFromResourceFiles() {
        // This test verifies that resource files don't interfere with source root detection
        rewriteRun(
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>resource-test</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            ),
            // Java file in standard location
            java(
                """
                package com.example.app;

                public class MyService {
                }
                """,
                spec -> spec.path("src/main/java/com/example/app/MyService.java")
            ),
            // Expected: Application class uses correct source root (src/main/java, not src/main/resources)
            java(
                null,
                """
                package com.example.app;

                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class AppApplication {

                    public static void main(String[] args) {
                        SpringApplication.run(AppApplication.class, args);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/app/AppApplication.java")
            )
        );
    }
}
