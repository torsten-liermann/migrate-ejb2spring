package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

/**
 * Tests for AddNeedsReviewAnnotation recipe.
 * <p>
 * Codex P2.1h Round 89/90: Test coverage for source root detection logic:
 * <ul>
 *   <li>Standard source root detection</li>
 *   <li>NeedsReview.java generation verification</li>
 *   <li>Kotlin (.kt) file detection</li>
 *   <li>Multi-module support</li>
 * </ul>
 * <p>
 * NOTE: YAML-based custom source root tests are deferred to integration tests
 * (migration-test module) due to OpenRewrite's non-deterministic file scanning
 * order in unit tests. The Round 88 fileUnderYamlRoot logic is verified there.
 */
class AddNeedsReviewAnnotationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddNeedsReviewAnnotation())
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion().classpath("spring-context"));
    }

    /**
     * Codex P2.1h Round 89: Test standard source root detection without YAML.
     * <p>
     * Verifies that standard src/main/java roots are detected correctly
     * when no YAML configuration is present.
     */
    @Test
    void standardSourceRootWithoutYaml() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            // Standard source file without YAML config
            java(
                """
                package com.example;
                public class Standard {}
                """,
                spec -> spec.path("src/main/java/com/example/Standard.java")
            ),
            // NeedsReview.java should be generated in src/main/java
            java(
                null,
                spec -> spec.path("src/main/java/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("NeedsReview.java should be generated in src/main/java")
                            .contains("@interface NeedsReview")
                            .contains("package com.github.rewrite.ejb.annotations");
                        return actual;
                    })
            )
        );
    }

    /**
     * Codex P2.1h Round 89: Test multi-module project with standard source roots.
     * <p>
     * Verifies that NeedsReview.java is generated in module's src/main/java.
     */
    @Test
    void multiModuleWithStandardSourceRoots() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            // File in module-a using standard path
            java(
                """
                package com.example;
                public class ModuleA {}
                """,
                spec -> spec.path("module-a/src/main/java/com/example/ModuleA.java")
            ),
            // NeedsReview.java should be generated in module-a/src/main/java
            java(
                null,
                spec -> spec.path("module-a/src/main/java/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("NeedsReview.java should be generated in module-a/src/main/java")
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            )
        );
    }

    /**
     * Codex P2.1h Round 89: Test multiple modules each get their own NeedsReview.java.
     * <p>
     * Verifies that each module with detected sources gets its own annotation file.
     */
    @Test
    void multipleModulesEachGetAnnotation() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            // File in module-a
            java(
                """
                package com.example;
                public class ModuleA {}
                """,
                spec -> spec.path("module-a/src/main/java/com/example/ModuleA.java")
            ),
            // File in module-b
            java(
                """
                package com.example;
                public class ModuleB {}
                """,
                spec -> spec.path("module-b/src/main/java/com/example/ModuleB.java")
            ),
            // NeedsReview.java in module-a
            java(
                null,
                spec -> spec.path("module-a/src/main/java/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            ),
            // NeedsReview.java in module-b
            java(
                null,
                spec -> spec.path("module-b/src/main/java/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            )
        );
    }

    /**
     * Codex P2.1h Round 90: Test Kotlin (.kt) source file detection.
     * <p>
     * Verifies that .kt files under src/main/kotlin are detected correctly.
     * Uses text() instead of java() to create a real .kt file path.
     */
    @Test
    void kotlinSourceFileDetected() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            // Kotlin source file (.kt extension)
            text(
                """
                package com.example
                class KotlinClass
                """,
                spec -> spec.path("src/main/kotlin/com/example/KotlinClass.kt")
            ),
            // NeedsReview.java should be generated in src/main/kotlin
            java(
                null,
                spec -> spec.path("src/main/kotlin/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("NeedsReview.java should be generated in src/main/kotlin for .kt files")
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            )
        );
    }

    /**
     * Codex P2.1h Round 91: Test YAML custom source roots with order-independence.
     * <p>
     * This test verifies that:
     * 1. Root-level YAML config applies to root module files
     * 2. Root-level YAML does NOT apply to nested modules (module-a)
     * 3. Scan order (YAML before/after Java) doesn't matter (order-independence)
     * <p>
     * Round 91 made the recipe order-independent by deferring YAML parsing
     * and source root detection to generate() phase, after all files are scanned.
     */
    @Test
    void yamlCustomSourceRootWithNestedModule() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            // Root-level YAML config (defines custom-src for root module)
            text(
                """
                sourceRoots:
                  - custom-src
                """,
                spec -> spec.path(".migration-source-roots.yaml")
            ),
            // Root module file in custom-src (should use custom-src from YAML)
            java(
                """
                package com.example;
                public class RootClass {}
                """,
                spec -> spec.path("custom-src/com/example/RootClass.java")
            ),
            // Nested module file in standard src/main/java (should NOT use root YAML)
            java(
                """
                package com.example;
                public class ModuleA {}
                """,
                spec -> spec.path("module-a/src/main/java/com/example/ModuleA.java")
            ),
            // NeedsReview.java should be generated in custom-src (root module)
            java(
                null,
                spec -> spec.path("custom-src/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("NeedsReview.java should be generated in custom-src for root module")
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            ),
            // NeedsReview.java should be generated in module-a/src/main/java (nested module default)
            java(
                null,
                spec -> spec.path("module-a/src/main/java/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("NeedsReview.java should be generated in module-a/src/main/java (not affected by root YAML)")
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            )
        );
    }

    /**
     * Codex P2.1h Round 91: Test YAML order-independence by placing YAML AFTER Java files.
     * <p>
     * This test intentionally places YAML config AFTER Java files in the spec
     * to verify that the recipe correctly processes YAML regardless of scan order.
     */
    @Test
    void yamlOrderIndependence() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            // Java file FIRST (before YAML)
            java(
                """
                package com.example;
                public class First {}
                """,
                spec -> spec.path("custom-src/com/example/First.java")
            ),
            // YAML config AFTER Java file
            text(
                """
                sourceRoots:
                  - custom-src
                """,
                spec -> spec.path(".migration-source-roots.yaml")
            ),
            // NeedsReview.java should be generated in custom-src (from YAML)
            java(
                null,
                spec -> spec.path("custom-src/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("NeedsReview.java should be generated in custom-src even when YAML scanned after Java")
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            )
        );
    }

    /**
     * Codex P2.1h Round 92: Test custom-only nested module (no standard roots).
     * <p>
     * This test verifies that:
     * 1. Root-level YAML config with custom-src applies to root module
     * 2. Nested module with ONLY custom-src (no src/main/java) gets its own NeedsReview.java
     * 3. The nested module's YAML config is correctly applied to its custom root
     * <p>
     * This is the edge-case from Round 88/89 that was originally not tested.
     */
    @Test
    void customOnlyNestedModule() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            // Root-level YAML config (defines custom-src for root module)
            text(
                """
                sourceRoots:
                  - custom-src
                """,
                spec -> spec.path(".migration-source-roots.yaml")
            ),
            // Root module file in custom-src
            java(
                """
                package com.example;
                public class RootClass {}
                """,
                spec -> spec.path("custom-src/com/example/RootClass.java")
            ),
            // Nested module YAML config (defines custom-src for nested module)
            text(
                """
                sourceRoots:
                  - custom-src
                """,
                spec -> spec.path("module-a/.migration-source-roots.yaml")
            ),
            // Nested module file in custom-src (NO src/main/java exists)
            java(
                """
                package com.example;
                public class ModuleA {}
                """,
                spec -> spec.path("module-a/custom-src/com/example/ModuleA.java")
            ),
            // NeedsReview.java should be generated in root custom-src
            java(
                null,
                spec -> spec.path("custom-src/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("NeedsReview.java should be generated in root custom-src")
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            ),
            // NeedsReview.java should be generated in module-a/custom-src (not root!)
            java(
                null,
                spec -> spec.path("module-a/custom-src/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("NeedsReview.java should be generated in module-a/custom-src (custom-only module)")
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            )
        );
    }

    /**
     * Codex P2.1h Round 92: Test deterministic root selection with main and test roots.
     * <p>
     * This test verifies that:
     * 1. When a module has both src/main/java and src/test/java
     * 2. NeedsReview.java is ALWAYS generated in src/main/java (higher priority)
     * 3. The selection is deterministic regardless of scan order
     */
    @Test
    void deterministicMainOverTestRoot() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            // Test file (might be scanned first)
            java(
                """
                package com.example;
                public class TestClass {}
                """,
                spec -> spec.path("src/test/java/com/example/TestClass.java")
            ),
            // Main file (should be selected for NeedsReview.java)
            java(
                """
                package com.example;
                public class MainClass {}
                """,
                spec -> spec.path("src/main/java/com/example/MainClass.java")
            ),
            // NeedsReview.java should ALWAYS be in src/main/java (not src/test/java)
            java(
                null,
                spec -> spec.path("src/main/java/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("NeedsReview.java should be in src/main/java (deterministic priority)")
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            )
        );
    }

    /**
     * Codex P2.1h Round 93: Test YAML multi-root ordering is respected.
     * <p>
     * This test verifies that:
     * 1. When YAML has multiple roots (src/main/java and custom-src)
     * 2. The first root in YAML order with detected files is used
     * 3. YAML order is preserved, not alphabetically sorted
     * <p>
     * YAML order: src/main/java comes before custom-src
     * If alphabetically sorted: custom-src would come first (wrong!)
     */
    @Test
    void yamlMultiRootOrderRespected() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            // YAML with src/main/java FIRST, then custom-src (order matters)
            text(
                """
                sourceRoots:
                  - src/main/java
                  - custom-src
                """,
                spec -> spec.path(".migration-source-roots.yaml")
            ),
            // File in src/main/java (first in YAML)
            java(
                """
                package com.example;
                public class MainClass {}
                """,
                spec -> spec.path("src/main/java/com/example/MainClass.java")
            ),
            // File in custom-src (second in YAML, but alphabetically first)
            java(
                """
                package com.example;
                public class CustomClass {}
                """,
                spec -> spec.path("custom-src/com/example/CustomClass.java")
            ),
            // NeedsReview.java should be in src/main/java (YAML order, not alphabetical)
            java(
                null,
                spec -> spec.path("src/main/java/com/github/rewrite/ejb/annotations/NeedsReview.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("NeedsReview.java should be in src/main/java (YAML order respected)")
                            .contains("@interface NeedsReview");
                        return actual;
                    })
            )
        );
    }
}
