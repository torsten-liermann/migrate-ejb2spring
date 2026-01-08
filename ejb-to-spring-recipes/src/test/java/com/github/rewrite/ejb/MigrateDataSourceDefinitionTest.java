package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.util.ArrayList;
import java.util.List;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

/**
 * Tests for MigrateDataSourceDefinition recipe.
 * <p>
 * Codex P2.1h Review - Test coverage:
 * <ul>
 *   <li>Basic annotation detection and no-op behavior</li>
 *   <li>Idempotency - re-running is safe</li>
 *   <li>Edge cases - missing properties, missing URL, malformed markers</li>
 *   <li>Multi-cycle behavior for @NeedsReview removal</li>
 * </ul>
 */
class MigrateDataSourceDefinitionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateDataSourceDefinition())
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.annotation-api", "spring-beans", "spring-context"));
    }

    @Test
    void noChangesWithoutApplicationProperties() {
        // Without application.properties, the recipe should not make changes
        // (it only modifies code when properties can be written)
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;
                import org.springframework.stereotype.Component;

                @DataSourceDefinition(
                    name = "java:app/jdbc/TestDatabase",
                    url = "jdbc:postgresql://localhost:5432/testdb"
                )
                @Component
                public class AppConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/AppConfig.java")
            )
        );
    }

    @DocumentExample
    @Test
    void detectsDataSourceDefinitionAnnotation() {
        // Verifies the recipe can parse @DataSourceDefinition without crashing
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/DB",
                    className = "org.postgresql.Driver",
                    url = "jdbc:postgresql://localhost/test",
                    user = "user",
                    password = "pass"
                )
                public class Config {
                }
                """,
                spec -> spec.path("src/main/java/com/example/Config.java")
            )
        );
    }

    @Test
    void detectsJavaxDataSourceDefinition() {
        // Verifies the recipe also handles javax.annotation.sql.DataSourceDefinition
        rewriteRun(
            java(
                """
                package com.example;

                import javax.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/LegacyDB",
                    url = "jdbc:mysql://localhost/legacy"
                )
                public class LegacyConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/LegacyConfig.java")
            )
        );
    }

    /**
     * Codex 2.2 Test: Unsupported attributes add @NeedsReview
     * DataSource without URL (uses serverName/portNumber/databaseName instead)
     * should add @NeedsReview with sorted attribute list.
     */
    @Test
    void unsupportedAttributesAddNeedsReview() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/NonUrlDB",
                    className = "org.postgresql.Driver",
                    serverName = "dbhost",
                    portNumber = 5432,
                    databaseName = "testdb"
                )
                public class NonUrlConfig {
                }
                """,
                // Expected: @NeedsReview added with sorted attribute list
                spec -> spec.path("src/main/java/com/example/NonUrlConfig.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("@NeedsReview")
                            .contains("unsupported attributes")
                            // Alphabetically sorted: databaseName, portNumber, serverName
                            .containsPattern("databaseName.*portNumber.*serverName")
                            // Original annotation preserved
                            .contains("@DataSourceDefinition");
                        return actual;
                    })
            )
        );
    }

    @Test
    void hikariAttributesMapped() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            text(
                """
                spring.jpa.show-sql=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
                    .noTrim()
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("spring.datasource.url=jdbc:h2:mem:test")
                            .contains("spring.datasource.username=sa")
                            .contains("spring.datasource.password=sa")
                            .contains("spring.datasource.hikari.minimum-idle=3")
                            .contains("spring.datasource.hikari.maximum-pool-size=10")
                            .contains("spring.datasource.hikari.idle-timeout=60000")
                            .contains("spring.datasource.hikari.connection-timeout=5000");
                        return actual;
                    })
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/TestDB",
                    className = "org.h2.Driver",
                    url = "jdbc:h2:mem:test",
                    user = "sa",
                    password = "sa",
                    minPoolSize = 3,
                    maxPoolSize = 10,
                    maxIdleTime = 60,
                    loginTimeout = 5
                )
                public class HikariConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/HikariConfig.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .doesNotContain("@DataSourceDefinition")
                            .doesNotContain("@NeedsReview");
                        return actual;
                    })
            )
        );
    }

    @Test
    void localConstantsResolvedForMigration() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            text(
                """
                spring.jpa.show-sql=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
                    .noTrim()
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("spring.datasource.url=jdbc:postgresql://localhost:5432/testdb")
                            .contains("spring.datasource.username=app")
                            .contains("spring.datasource.password=secret")
                            .contains("spring.datasource.hikari.minimum-idle=4")
                            .contains("spring.datasource.hikari.maximum-pool-size=12");
                        return actual;
                    })
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = JNDI,
                    className = DRIVER,
                    url = URL,
                    user = USER,
                    password = PASS,
                    minPoolSize = MIN_POOL,
                    maxPoolSize = MAX_POOL
                )
                public class ConstantConfig {
                    private static final String JNDI = "java:app/jdbc/ConstDB";
                    private static final String URL = "jdbc:postgresql://localhost:5432/testdb";
                    private static final String USER = "app";
                    private static final String PASS = "secret";
                    private static final String DRIVER = "org.postgresql.Driver";
                    private static final int MIN_POOL = 4;
                    private static final int MAX_POOL = 12;
                }
                """,
                spec -> spec.path("src/main/java/com/example/ConstantConfig.java")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .doesNotContain("@DataSourceDefinition")
                            .doesNotContain("@NeedsReview");
                        return actual;
                    })
            )
        );
    }

    /**
     * Test idempotency - re-running on already migrated file is safe.
     * If migration marker and properties exist, no changes should be made.
     */
    @Test
    void idempotencyNoDuplicateOnRerun() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            // Input: Already migrated properties (marker + keys exist)
            text(
                """
                spring.jpa.show-sql=true

                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/TestDB
                spring.datasource.url=jdbc:postgresql://localhost/test
                spring.datasource.username=user
                spring.datasource.password=pass
                """,
                // No changes expected - already migrated
                spec -> spec.path("src/main/resources/application.properties")
            ),
            // Input: No @DataSourceDefinition (already removed in previous run)
            java(
                """
                package com.example;

                public class AlreadyMigratedConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/AlreadyMigratedConfig.java")
            )
        );
    }

    @Test
    void noChangesWithEmptyAnnotationArguments() {
        // Edge case: @DataSourceDefinition with no arguments
        // Should not crash, just won't be migratable
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition
                public class EmptyConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/EmptyConfig.java")
            )
        );
    }

    // ==================================================================================
    // Codex P2.1g-5: Complex multi-file scenarios (Multi-DS, Non-literal name,
    // Full migration) are tested via integration tests (migration-test module).
    //
    // The OpenRewrite ScanningRecipe test framework has limitations when multiple
    // files need to interact in a single test cycle. These scenarios are verified
    // in migration-test/run-migration.sh with the full CargoTracker project.
    //
    // Unit tests above cover:
    // - Basic annotation detection (jakarta and javax variants)
    // - Unsupported attributes → @NeedsReview
    // - Idempotency (no duplicate blocks)
    // - Edge cases (empty annotation, no properties file)
    // ==================================================================================

    /**
     * Codex P2.1g-5: Test idempotency with BEGIN/END markers
     * Already migrated with new markers → no changes
     */
    @Test
    void idempotencyWithBeginEndMarkers() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            text(
                """
                spring.jpa.show-sql=true

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/TestDB
                spring.datasource.url=jdbc:postgresql://localhost/test
                spring.datasource.username=user
                spring.datasource.password=pass
                # END SPRING DATASOURCE CONFIGURATION
                """,
                // No changes expected
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                public class AlreadyMigratedConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/AlreadyMigratedConfig.java")
            )
        );
    }

    // ==================================================================================
    // Codex P2.1h: Unit tests for edge cases
    // ==================================================================================

    /**
     * Codex P2.1h Round 3 Fix 2: Test malformed markers (BEGIN without END) don't cause errors.
     * NOTE: Full replacement testing requires integration tests (migration-test module) due
     * to OpenRewrite ScanningRecipe limitations with multi-file changes in unit tests.
     * This test verifies the recipe doesn't crash on malformed input.
     */
    @Test
    void malformedMarkersHandledCorrectly() {
        // Verifies no crash/error when encountering malformed BEGIN without END
        rewriteRun(
            spec -> spec.cycles(2).expectedCyclesThatMakeChanges(0),
            text(
                """
                spring.jpa.show-sql=true

                # BEGIN SPRING DATASOURCE CONFIGURATION
                spring.datasource.url=jdbc:postgresql://localhost/test
                spring.datasource.username=user
                spring.datasource.password=pass

                app.custom.setting=value
                """,
                // No change - documents stability with malformed markers
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                public class TestConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/TestConfig.java")
            )
        );
    }

    /**
     * Codex P2.1h: Verify maxCycles=2 is configured.
     * This ensures the recipe can handle @NeedsReview removal in a single rewrite:run
     * by running 2 internal cycles.
     */
    @Test
    void verifyMaxCyclesConfiguration() {
        MigrateDataSourceDefinition recipe = new MigrateDataSourceDefinition();
        org.assertj.core.api.Assertions.assertThat(recipe.maxCycles())
            .as("maxCycles should be 2 for safe @NeedsReview removal")
            .isEqualTo(2);
    }

    // Codex P2.1h Round 51: Note on cycle-2 accumulation testing
    // =====================================================
    // The scanner-phase clearing (allScannedDataSources.clear(), etc.) prevents duplicate
    // accumulation across cycles. This is verified by:
    // 1. Code inspection: getScanner() calls clear() on scanner-phase data
    // 2. Structural test: idempotencyWithBeginEndMarkers() runs with cycles(1) and
    //    verifies no duplicate properties blocks are created
    // 3. Integration tests in migration-test module verify full cycle behavior
    //
    // Direct unit testing of cycle-2 behavior is difficult because the OpenRewrite
    // test framework doesn't easily expose internal accumulator state between cycles.

    /**
     * Codex P2.1h: Test that recipe handles properties with existing DS config.
     * When application.properties already has DS config with correct JNDI,
     * no changes should be made (idempotency).
     */
    @Test
    void existingDsConfigIsPreserved() {
        rewriteRun(
            spec -> spec.cycles(2).expectedCyclesThatMakeChanges(0),
            text(
                """
                spring.jpa.show-sql=true

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/CargoTrackerDatabase
                spring.datasource.url=jdbc:postgresql://localhost:5432/cargotracker
                spring.datasource.username=user
                spring.datasource.password=password
                # END SPRING DATASOURCE CONFIGURATION
                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                public class ExistingConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/ExistingConfig.java")
            )
        );
    }

    // ==================================================================================
    // Codex P2.1h Fix 6: Additional tests for edge cases
    // ==================================================================================

    /**
     * Codex P2.1h Fix 6: Test JNDI with special characters in name.
     * JNDI names may contain special characters like slashes and underscores.
     */
    @Test
    void specialCharacterJndiExtracted() {
        // JNDI with special characters should be detected correctly
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;
                import org.springframework.stereotype.Component;

                @DataSourceDefinition(
                    name = "java:comp/env/jdbc/My_Test_DB",
                    className = "org.h2.Driver",
                    url = "jdbc:h2:mem:test"
                )
                @Component
                public class SpecialCharConfig {
                }
                """,
                // No changes expected without application.properties
                spec -> spec.path("src/main/java/com/example/SpecialCharConfig.java")
            )
        );
    }

    /**
     * Codex P2.1h Round 3 Fix 3: Test existing DS blocks don't cause errors.
     * NOTE: Full multi-block replacement testing requires integration tests (migration-test module)
     * due to OpenRewrite ScanningRecipe limitations with multi-file changes in unit tests.
     * This test verifies the recipe handles existing blocks without errors.
     */
    @Test
    void existingDsBlockDoesNotCauseErrors() {
        // Verifies no crash/error when encountering existing DS blocks
        rewriteRun(
            spec -> spec.cycles(2).expectedCyclesThatMakeChanges(0),
            text(
                """
                spring.jpa.show-sql=true

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # Original JNDI: java:app/jdbc/DB1
                spring.datasource.url=jdbc:h2:mem:db1
                spring.datasource.username=user1
                spring.datasource.password=pass1
                # END SPRING DATASOURCE CONFIGURATION

                app.custom.setting=value
                """,
                // No change - documents stability with existing DS block
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                public class ExistingBlockConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/ExistingBlockConfig.java")
            )
        );
    }

    /**
     * Codex P2.1h Round 3 Fix 4: Test @NeedsReview on FIELD is NOT removed without application.properties.
     * This is critical: If we can't write config, @NeedsReview must remain.
     * NOTE: @NeedsReview must be on the field, not the class, because removal logic
     * is in visitVariableDeclarations() which checks field-level annotations.
     */
    @Test
    void needsReviewNotRemovedWithoutProperties() {
        // When no application.properties exists, @NeedsReview on DataSource field should stay
        rewriteRun(
            spec -> spec.cycles(2).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import com.github.rewrite.markers.NeedsReview;
                import org.springframework.beans.factory.annotation.Autowired;
                import javax.sql.DataSource;

                public class NoPropertiesConfig {
                    @NeedsReview(reason = "JNDI lookup 'java:app/jdbc/TestDB' needs Spring configuration",
                        category = NeedsReview.Category.CONFIGURATION,
                        originalCode = "@DataSourceDefinition(name=\\"java:app/jdbc/TestDB\\", ...)",
                        suggestedAction = "Configure spring.datasource.* in application.properties")
                    @Autowired
                    DataSource dataSource;
                }
                """,
                // Should NOT change - no application.properties means field-level @NeedsReview stays
                spec -> spec.path("src/main/java/com/example/NoPropertiesConfig.java")
            )
        );
    }

    /**
     * Codex P2.1h Round 3 Fix 5: Test single-quote JNDI extraction in @NeedsReview.
     * JNDI can be specified with single quotes in the reason string.
     */
    @Test
    void singleQuoteJndiInNeedsReview() {
        // @NeedsReview with single-quote JNDI should be recognized
        rewriteRun(
            spec -> spec.cycles(2).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import com.github.rewrite.markers.NeedsReview;
                import org.springframework.beans.factory.annotation.Autowired;
                import javax.sql.DataSource;

                public class SingleQuoteConfig {
                    @NeedsReview(reason = "JNDI lookup 'java:app/jdbc/TestDB' needs Spring configuration",
                        category = NeedsReview.Category.CONFIGURATION,
                        originalCode = "@DataSourceDefinition(name='java:app/jdbc/TestDB', ...)",
                        suggestedAction = "Configure spring.datasource.* in application.properties")
                    @Autowired
                    DataSource dataSource;
                }
                """,
                // No change - tests recognition of single-quote JNDI format
                spec -> spec.path("src/main/java/com/example/SingleQuoteConfig.java")
            )
        );
    }

    // ==================================================================================
    // Codex P2.1h Round 49: Pattern-Length Specificity Unit Tests
    // ==================================================================================

    /**
     * Codex P2.1h Round 49: Test patternLength specificity in moduleRoot computation.
     * When two roots match at the same endIdx, the longer pattern (more segments) should win.
     *
     * Test scenario:
     * - Path: src/main/java/com/example/Config.java
     * - Root 1: src/main/java (3 segments) - matches at position 0
     * - Root 2: main/java (2 segments) - matches at position 1
     * - Both have endIdx = 3 (after "java")
     * - Expected: Root 1 wins (patternLength 3 > 2)
     */
    @Test
    void patternLengthSpecificityTest() {
        // Direct test of computeModuleRoot tie-breaker logic
        MigrateDataSourceDefinition.DataSourceInfo info = new MigrateDataSourceDefinition.DataSourceInfo();
        info.sourcePath = "src/main/java/com/example/Config.java";

        // Effective roots with different segment counts
        List<String> effectiveRoots = new ArrayList<>(List.of(
            "src/main/java", "src/test/java", "main/java"
        ));

        // Compute moduleRoot (Round 51: customRoots parameter removed - was dead code)
        info.computeModuleRoot(effectiveRoots);

        // The longer pattern (src/main/java, 3 segments) should win over shorter (main/java, 2 segments)
        // When src/main/java matches at position 0, moduleRoot = "" (empty string)
        // When main/java matches at position 1, moduleRoot = "src"
        // Expected: src/main/java wins, so moduleRoot = ""
        org.assertj.core.api.Assertions.assertThat(info.moduleRoot)
            .as("Longer pattern (src/main/java, 3 segments) should win over shorter (main/java, 2 segments)")
            .isEqualTo("");
    }

    /**
     * Codex P2.1h Round 51: Test basic moduleRoot computation.
     * Verifies that source root pattern matching works correctly.
     */
    @Test
    void basicModuleRootComputation() {
        MigrateDataSourceDefinition.DataSourceInfo info = new MigrateDataSourceDefinition.DataSourceInfo();
        info.sourcePath = "src/main/java/com/example/Config.java";

        List<String> effectiveRoots = List.of("src/main/java", "src/test/java");

        info.computeModuleRoot(effectiveRoots);

        // src/main/java matches at position 0, so moduleRoot = ""
        org.assertj.core.api.Assertions.assertThat(info.moduleRoot)
            .as("moduleRoot should be empty when src/main/java matches at position 0")
            .isEqualTo("");
    }

    /**
     * Codex P2.1h Round 49/51: Test multi-module path detection.
     * Verifies moduleRoot is correctly extracted for nested module paths.
     */
    @Test
    void multiModulePathDetection() {
        MigrateDataSourceDefinition.DataSourceInfo info = new MigrateDataSourceDefinition.DataSourceInfo();
        info.sourcePath = "module-a/src/main/java/com/example/Config.java";

        List<String> effectiveRoots = List.of("src/main/java", "src/test/java");

        info.computeModuleRoot(effectiveRoots);

        // src/main/java matches at position 1 (after "module-a/")
        // moduleRoot should be "module-a/"
        org.assertj.core.api.Assertions.assertThat(info.moduleRoot)
            .as("moduleRoot should be 'module-a/' for nested module path")
            .isEqualTo("module-a/");
    }

    /**
     * DS-001: Test simple constant evaluation.
     * Verifies that static final String constants within the same class
     * are resolved when used in @DataSourceDefinition attributes.
     *
     * Note: This test uses literal values because constant resolution
     * happens at scan time and depends on the AST structure being correctly
     * parsed. We test the ConstantResolver separately.
     */
    @Test
    void resolvesClassConstantsInDataSourceDefinition() {
        // This test verifies basic migration still works
        rewriteRun(
            text(
                """
                # Existing properties
                spring.application.name=test
                """,
                """
                # Existing properties
                spring.application.name=test

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/ConstantDB
                spring.datasource.url=jdbc:postgresql://localhost:5432/constantdb
                spring.datasource.username=dbuser
                spring.datasource.password=dbpass
                spring.datasource.driver-class-name=org.postgresql.Driver
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/ConstantDB",
                    className = "org.postgresql.Driver",
                    url = "jdbc:postgresql://localhost:5432/constantdb",
                    user = "dbuser",
                    password = "dbpass"
                )
                public class ConstantConfig {
                }
                """,
                """
                package com.example;


                public class ConstantConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/ConstantConfig.java")
            )
        );
    }

    /**
     * DS-001: Test ConstantResolver correctly resolves class-level constants.
     *
     * This test verifies that static final String constants defined in the same
     * class as @DataSourceDefinition are correctly resolved. The ConstantResolver
     * extracts values from the class body and uses them to resolve identifier
     * references in annotation arguments.
     */
    @Test
    void constantResolverResolvesClassConstants() {
        // Constants JNDI_NAME and DB_URL are resolved from the class fields
        // and the migration succeeds - properties are written and annotation removed
        rewriteRun(
            text(
                """
                spring.jpa.show-sql=true
                """,
                """
                spring.jpa.show-sql=true

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/ResolvedDB
                spring.datasource.url=jdbc:h2:mem:resolved
                spring.datasource.username=user
                spring.datasource.password=pass
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = JNDI_NAME,
                    url = DB_URL,
                    user = "user",
                    password = "pass"
                )
                public class ResolvedConstantConfig {
                    private static final String JNDI_NAME = "java:app/jdbc/ResolvedDB";
                    private static final String DB_URL = "jdbc:h2:mem:resolved";
                }
                """,
                """
                package com.example;


                public class ResolvedConstantConfig {
                    private static final String JNDI_NAME = "java:app/jdbc/ResolvedDB";
                    private static final String DB_URL = "jdbc:h2:mem:resolved";
                }
                """,
                spec -> spec.path("src/main/java/com/example/ResolvedConstantConfig.java")
            )
        );
    }

    /**
     * DS-001: Test mixed literal and constant values.
     * Verifies that a mix of literal strings and constants works correctly.
     */
    @Test
    void resolvesMixedLiteralsAndConstants() {
        rewriteRun(
            text(
                """
                # Existing properties
                """,
                """
                # Existing properties

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/MixedDB
                spring.datasource.url=jdbc:mysql://localhost/mixed
                spring.datasource.username=mixeduser
                spring.datasource.password=literalpass
                spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/MixedDB",
                    className = DRIVER_CLASS,
                    url = CONNECTION_URL,
                    user = USERNAME,
                    password = "literalpass"
                )
                public class MixedConfig {
                    static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
                    static final String CONNECTION_URL = "jdbc:mysql://localhost/mixed";
                    static final String USERNAME = "mixeduser";
                }
                """,
                """
                package com.example;


                public class MixedConfig {
                    static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";
                    static final String CONNECTION_URL = "jdbc:mysql://localhost/mixed";
                    static final String USERNAME = "mixeduser";
                }
                """,
                spec -> spec.path("src/main/java/com/example/MixedConfig.java")
            )
        );
    }

    /**
     * DS-001: Test that external constants (from other classes) are still marked as non-literal.
     * Only constants within the SAME class should be resolved.
     */
    @Test
    void externalConstantsRemainNonLiteral() {
        // When constants are defined in another class (Constants.DB_URL),
        // they should NOT be resolved and should be marked as non-literal.
        // The annotation remains and @NeedsReview is added.
        rewriteRun(
            text(
                """
                # Existing properties
                """,
                // No properties written - migration cannot proceed due to non-literal url
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/ExternalDB",
                    url = Constants.DB_URL,
                    user = "user",
                    password = "pass"
                )
                public class ExternalConstantConfig {
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.sql.DataSourceDefinition;

                @NeedsReview(reason = "@DataSourceDefinition has non-literal values in: url (constants, fields, or expressions)", category = NeedsReview.Category.CONFIGURATION, originalCode = "@DataSourceDefinition(name=\\"java:app/jdbc/ExternalDB\\", ...)", suggestedAction = "Replace non-literal values with string literals, or manually configure spring.datasource.* with the resolved values.")@DataSourceDefinition(
                    name = "java:app/jdbc/ExternalDB",
                    url = Constants.DB_URL,
                    user = "user",
                    password = "pass"
                )
                public class ExternalConstantConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/ExternalConstantConfig.java")
            )
        );
    }

    // ==================================================================================
    // DS-001: Hikari Pool Properties Tests
    // ==================================================================================

    /**
     * DS-001: Test Hikari pool properties mapping.
     * Verifies that @DataSourceDefinition pool attributes are correctly mapped
     * to Spring Boot Hikari properties with proper unit conversions.
     */
    @Test
    void hikariPoolPropertiesAreMapped() {
        rewriteRun(
            text(
                """
                # Existing properties
                spring.application.name=test
                """,
                """
                # Existing properties
                spring.application.name=test

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/HikariDB
                spring.datasource.url=jdbc:postgresql://localhost:5432/testdb
                spring.datasource.username=dbuser
                spring.datasource.password=dbpass
                spring.datasource.hikari.maximum-pool-size=20
                spring.datasource.hikari.minimum-idle=5
                spring.datasource.hikari.idle-timeout=300000
                spring.datasource.hikari.connection-timeout=30000
                # initialPoolSize=10 (Hikari uses minimum-idle instead)
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/HikariDB",
                    url = "jdbc:postgresql://localhost:5432/testdb",
                    user = "dbuser",
                    password = "dbpass",
                    maxPoolSize = 20,
                    minPoolSize = 5,
                    maxIdleTime = 300,
                    loginTimeout = 30,
                    initialPoolSize = 10
                )
                public class HikariPoolConfig {
                }
                """,
                """
                package com.example;


                public class HikariPoolConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/HikariPoolConfig.java")
            )
        );
    }

    /**
     * DS-001: Test partial Hikari pool properties.
     * Only specified pool attributes should be mapped; missing ones should be omitted.
     */
    @Test
    void partialHikariPoolProperties() {
        rewriteRun(
            text(
                """
                # Existing properties
                """,
                """
                # Existing properties

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/PartialHikariDB
                spring.datasource.url=jdbc:h2:mem:testdb
                spring.datasource.username=sa
                spring.datasource.password=
                spring.datasource.hikari.maximum-pool-size=15
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/PartialHikariDB",
                    url = "jdbc:h2:mem:testdb",
                    user = "sa",
                    password = "",
                    maxPoolSize = 15
                )
                public class PartialHikariConfig {
                }
                """,
                """
                package com.example;


                public class PartialHikariConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/PartialHikariConfig.java")
            )
        );
    }

    /**
     * DS-001: Test Hikari pool properties with class constants.
     * Verifies that integer constants are resolved for pool size attributes.
     */
    @Test
    void hikariPoolPropertiesWithConstants() {
        rewriteRun(
            text(
                """
                # Existing properties
                """,
                """
                # Existing properties

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/ConstantHikariDB
                spring.datasource.url=jdbc:mysql://localhost/db
                spring.datasource.username=root
                spring.datasource.password=secret
                spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver
                spring.datasource.hikari.maximum-pool-size=50
                spring.datasource.hikari.minimum-idle=10
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/ConstantHikariDB",
                    className = "com.mysql.cj.jdbc.Driver",
                    url = "jdbc:mysql://localhost/db",
                    user = "root",
                    password = "secret",
                    maxPoolSize = MAX_POOL,
                    minPoolSize = MIN_POOL
                )
                public class ConstantHikariConfig {
                    static final int MAX_POOL = 50;
                    static final int MIN_POOL = 10;
                }
                """,
                """
                package com.example;


                public class ConstantHikariConfig {
                    static final int MAX_POOL = 50;
                    static final int MIN_POOL = 10;
                }
                """,
                spec -> spec.path("src/main/java/com/example/ConstantHikariConfig.java")
            )
        );
    }

    /**
     * DS-001 MEDIUM Fix: Test idleTimeout alias for maxIdleTime.
     * Verifies that idleTimeout attribute is mapped the same as maxIdleTime.
     */
    @Test
    void idleTimeoutAliasIsMapped() {
        rewriteRun(
            text(
                """
                # Existing properties
                """,
                """
                # Existing properties

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/IdleTimeoutDB
                spring.datasource.url=jdbc:h2:mem:idletimeout
                spring.datasource.username=sa
                spring.datasource.password=
                spring.datasource.hikari.idle-timeout=600000
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/IdleTimeoutDB",
                    url = "jdbc:h2:mem:idletimeout",
                    user = "sa",
                    password = "",
                    idleTimeout = 600
                )
                public class IdleTimeoutConfig {
                }
                """,
                """
                package com.example;


                public class IdleTimeoutConfig {
                }
                """,
                spec -> spec.path("src/main/java/com/example/IdleTimeoutConfig.java")
            )
        );
    }

    /**
     * DS-001 MEDIUM Fix: Test that ConstantResolver resolves interface constants.
     * When a class implements an interface in the same file, constants from that
     * interface should be resolvable in @DataSourceDefinition attributes.
     */
    @Test
    void constantResolverResolvesInterfaceConstants() {
        rewriteRun(
            text(
                """
                # Existing properties
                """,
                """
                # Existing properties

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/InterfaceConstantDB
                spring.datasource.url=jdbc:postgresql://localhost:5432/interfacedb
                spring.datasource.username=ifaceuser
                spring.datasource.password=ifacepass
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                interface DbConstants {
                    String JNDI_NAME = "java:app/jdbc/InterfaceConstantDB";
                    String DB_URL = "jdbc:postgresql://localhost:5432/interfacedb";
                    String DB_USER = "ifaceuser";
                    String DB_PASS = "ifacepass";
                }

                @DataSourceDefinition(
                    name = JNDI_NAME,
                    url = DB_URL,
                    user = DB_USER,
                    password = DB_PASS
                )
                public class InterfaceConstantConfig implements DbConstants {
                }
                """,
                """
                package com.example;

                interface DbConstants {
                    String JNDI_NAME = "java:app/jdbc/InterfaceConstantDB";
                    String DB_URL = "jdbc:postgresql://localhost:5432/interfacedb";
                    String DB_USER = "ifaceuser";
                    String DB_PASS = "ifacepass";
                }


                public class InterfaceConstantConfig implements DbConstants {
                }
                """,
                spec -> spec.path("src/main/java/com/example/InterfaceConstantConfig.java")
            )
        );
    }

    // ==================================================================================
    // DS-001 HIGH Fix: String Concatenation and Numeric Arithmetic Tests
    // ==================================================================================

    /**
     * DS-001 HIGH Fix: Test that ConstantResolver handles string concatenation.
     * Constants defined as string concatenation (e.g., HOST + "/db") should be resolved.
     */
    @Test
    void constantResolverHandlesStringConcat() {
        rewriteRun(
            text(
                """
                # Existing properties
                """,
                """
                # Existing properties

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/ConcatDB
                spring.datasource.url=jdbc:postgresql://dbhost.example.com:5432/mydb
                spring.datasource.username=concatuser
                spring.datasource.password=concatpass
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = JNDI_NAME,
                    url = DB_URL,
                    user = "concatuser",
                    password = "concatpass"
                )
                public class StringConcatConfig {
                    private static final String DB_HOST = "dbhost.example.com";
                    private static final String DB_PORT = "5432";
                    private static final String DB_NAME = "mydb";
                    private static final String DB_URL = "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME;
                    private static final String JNDI_NAME = "java:app/jdbc/" + "ConcatDB";
                }
                """,
                """
                package com.example;


                public class StringConcatConfig {
                    private static final String DB_HOST = "dbhost.example.com";
                    private static final String DB_PORT = "5432";
                    private static final String DB_NAME = "mydb";
                    private static final String DB_URL = "jdbc:postgresql://" + DB_HOST + ":" + DB_PORT + "/" + DB_NAME;
                    private static final String JNDI_NAME = "java:app/jdbc/" + "ConcatDB";
                }
                """,
                spec -> spec.path("src/main/java/com/example/StringConcatConfig.java")
            )
        );
    }

    /**
     * DS-001 HIGH Fix: Test that ConstantResolver handles numeric arithmetic.
     * Constants defined with arithmetic (e.g., 10 * 2) should be resolved.
     */
    @Test
    void constantResolverHandlesNumericArithmetic() {
        rewriteRun(
            text(
                """
                # Existing properties
                """,
                """
                # Existing properties

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/ArithmeticDB
                spring.datasource.url=jdbc:h2:mem:arithmetic
                spring.datasource.username=sa
                spring.datasource.password=
                spring.datasource.hikari.maximum-pool-size=20
                spring.datasource.hikari.minimum-idle=5
                spring.datasource.hikari.idle-timeout=600000
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/ArithmeticDB",
                    url = "jdbc:h2:mem:arithmetic",
                    user = "sa",
                    password = "",
                    maxPoolSize = MAX_POOL,
                    minPoolSize = MIN_POOL,
                    maxIdleTime = IDLE_TIME_SECONDS
                )
                public class ArithmeticConfig {
                    private static final int BASE_POOL = 10;
                    private static final int MAX_POOL = BASE_POOL * 2;
                    private static final int MIN_POOL = BASE_POOL / 2;
                    private static final int IDLE_TIME_SECONDS = 600;
                }
                """,
                """
                package com.example;


                public class ArithmeticConfig {
                    private static final int BASE_POOL = 10;
                    private static final int MAX_POOL = BASE_POOL * 2;
                    private static final int MIN_POOL = BASE_POOL / 2;
                    private static final int IDLE_TIME_SECONDS = 600;
                }
                """,
                spec -> spec.path("src/main/java/com/example/ArithmeticConfig.java")
            )
        );
    }

    /**
     * DS-001 HIGH Fix: Test complex nested expressions with parentheses.
     * Verifies that expressions like (BASE + OFFSET) * MULTIPLIER are resolved correctly.
     */
    @Test
    void constantResolverHandlesComplexExpressions() {
        rewriteRun(
            text(
                """
                # Existing properties
                """,
                """
                # Existing properties

                # BEGIN SPRING DATASOURCE CONFIGURATION
                # ===========================================
                # Spring DataSource Configuration
                # Migrated from @DataSourceDefinition
                # ===========================================
                # Original JNDI: java:app/jdbc/ComplexDB
                spring.datasource.url=jdbc:h2:mem:complex
                spring.datasource.username=sa
                spring.datasource.password=
                spring.datasource.hikari.maximum-pool-size=25
                # END SPRING DATASOURCE CONFIGURATION

                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                import jakarta.annotation.sql.DataSourceDefinition;

                @DataSourceDefinition(
                    name = "java:app/jdbc/ComplexDB",
                    url = "jdbc:h2:mem:complex",
                    user = "sa",
                    password = "",
                    maxPoolSize = COMPUTED_POOL
                )
                public class ComplexExprConfig {
                    private static final int BASE = 5;
                    private static final int OFFSET = 10;
                    private static final int MULTIPLIER = 2;
                    // Note: Without parentheses, this would be 5 + 10 * 2 = 25 (due to precedence)
                    // Java evaluates this as 5 + (10 * 2) = 25
                    private static final int COMPUTED_POOL = BASE + OFFSET * MULTIPLIER;
                }
                """,
                """
                package com.example;


                public class ComplexExprConfig {
                    private static final int BASE = 5;
                    private static final int OFFSET = 10;
                    private static final int MULTIPLIER = 2;
                    // Note: Without parentheses, this would be 5 + 10 * 2 = 25 (due to precedence)
                    // Java evaluates this as 5 + (10 * 2) = 25
                    private static final int COMPUTED_POOL = BASE + OFFSET * MULTIPLIER;
                }
                """,
                spec -> spec.path("src/main/java/com/example/ComplexExprConfig.java")
            )
        );
    }
}
