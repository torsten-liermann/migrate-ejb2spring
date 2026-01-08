package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

/**
 * Integration test verifying that GenerateMigrationReport can see @NeedsReview
 * annotations added by marker recipes in the SAME run.
 * <p>
 * This test addresses the "Bekannte Limitation" concern from Codex Review Round 14:
 * Does the report include newly generated @NeedsReview annotations from the same execution?
 */
class CompositeRecipeWithReportTest implements RewriteTest {

    /**
     * Composite recipe that marks Jakarta Batch classes and generates report.
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    static class BatchMarkerWithReportComposite extends Recipe {
        @Override
        public String getDisplayName() {
            return "Test Composite: Batch Marker + Report";
        }

        @Override
        public String getDescription() {
            return "Marks Batch classes and generates report in one run.";
        }

        @Override
        public List<Recipe> getRecipeList() {
            return Arrays.asList(
                new MarkJakartaBatchForMigration(),
                new GenerateMigrationReport()
            );
        }
    }

    /**
     * Composite recipe that marks multiple technologies and generates report.
     */
    @Value
    @EqualsAndHashCode(callSuper = false)
    static class MultiMarkerWithReportComposite extends Recipe {
        @Override
        public String getDisplayName() {
            return "Test Composite: Multi-Marker + Report";
        }

        @Override
        public String getDescription() {
            return "Marks Batch and WebSocket classes, then generates report.";
        }

        @Override
        public List<Recipe> getRecipeList() {
            return Arrays.asList(
                new MarkJakartaBatchForMigration(),
                new MarkWebSocketForMigration(),
                new GenerateMigrationReport()
            );
        }
    }

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new BatchMarkerWithReportComposite())
            .typeValidationOptions(TypeValidation.none())
            // Two cycles required: Cycle 1 adds @NeedsReview annotations to the AST.
            // The @NeedsReview annotations are only visible from Cycle 2 onwards,
            // allowing ScanningRecipe to collect them and generate MIGRATION-REVIEW.md.
            .cycles(2)
            .expectedCyclesThatMakeChanges(2)
            .parser(org.openrewrite.java.JavaParser.fromJavaVersion()
                .classpath("jakarta.batch-api", "spring-beans", "spring-context"));
    }

    /**
     * Tests that @NeedsReview annotations added by MarkJakartaBatchForMigration
     * are visible to GenerateMigrationReport in the same composite run.
     * <p>
     * This verifies OpenRewrite's execution model: recipes in a composite
     * see the modifications made by earlier recipes.
     */
    @Test
    void reportIncludesNeedsReviewAddedInSameRun() {
        rewriteRun(
            // Input: Jakarta Batch ItemReader WITHOUT @NeedsReview
            java(
                """
                package com.example.batch;

                import jakarta.batch.api.chunk.ItemReader;
                import java.io.Serializable;

                public class MyItemReader implements ItemReader {
                    @Override
                    public void open(Serializable checkpoint) throws Exception {
                    }

                    @Override
                    public void close() throws Exception {
                    }

                    @Override
                    public Object readItem() throws Exception {
                        return null;
                    }

                    @Override
                    public Serializable checkpointInfo() throws Exception {
                        return null;
                    }
                }
                """,
                // Verify the annotation is added (allow any whitespace formatting)
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .as("Should add @NeedsReview annotation")
                        .contains("@NeedsReview")
                        .contains("Jakarta Batch class needs migration to Spring Batch")
                        .contains("implements ItemReader")
                        .contains("MANUAL_MIGRATION");
                    return actual;
                })
            ),
            // Verify MIGRATION-REVIEW.md is generated with the new @NeedsReview item
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("Report should be generated")
                            .isNotNull()
                            .as("Report should contain header")
                            .contains("Migration Review Report")
                            .as("Report should contain Manual Migration category")
                            .contains("Manual Migration")
                            .as("Report should contain the marked class")
                            .contains("MyItemReader")
                            .as("Report should contain the reason")
                            .contains("Jakarta Batch class needs migration to Spring Batch")
                            .as("Report should contain the original code reference")
                            .contains("implements ItemReader");
                        return actual;
                    })
            )
        );
    }

    /**
     * Tests that multiple marker recipes can run together with the report generator,
     * and all marked classes appear in the generated report.
     */
    @Test
    void reportIncludesMultipleMarkersFromSameRun() {
        rewriteRun(
            spec -> spec.recipe(new MultiMarkerWithReportComposite())
                .typeValidationOptions(TypeValidation.none())
                .cycles(2)
                .expectedCyclesThatMakeChanges(2)
                .parser(org.openrewrite.java.JavaParser.fromJavaVersion()
                    .classpath("jakarta.batch-api", "jakarta.websocket-api", "spring-beans", "spring-context")),

            // Batch class - verify annotation is added
            java(
                """
                package com.example.batch;

                import jakarta.batch.api.Batchlet;

                public class MyBatchlet implements Batchlet {
                    @Override
                    public String process() throws Exception {
                        return "COMPLETED";
                    }

                    @Override
                    public void stop() throws Exception {
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION");
                    return actual;
                })
            ),

            // WebSocket class - verify annotation is added
            java(
                """
                package com.example.websocket;

                import jakarta.websocket.server.ServerEndpoint;
                import jakarta.websocket.OnMessage;
                import jakarta.websocket.Session;

                @ServerEndpoint("/chat")
                public class ChatEndpoint {
                    @OnMessage
                    public void onMessage(String message, Session session) {
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION");
                    return actual;
                })
            ),

            // Report should include BOTH classes
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .as("Report should contain Manual Migration section")
                            .contains("Manual Migration")
                            .as("Report should contain Batch class")
                            .contains("MyBatchlet")
                            .as("Report should contain WebSocket class")
                            .contains("ChatEndpoint")
                            .as("Report should show count of 2")
                            .contains("| **Total** | **2** |");
                        return actual;
                    })
            )
        );
    }

    /**
     * Smoke test that loads the composite recipe from META-INF/rewrite/ejb-to-spring.yml.
     * This verifies that recipe packaging and classloading work correctly.
     * If this test fails, there's likely a classpath or recipe registration issue.
     */
    @Test
    void yamlCompositeRecipeCanBeLoaded() {
        // Load recipe by name from YAML definition
        // Use package filter to reduce scan surface and improve test performance
        Environment env = Environment.builder()
            .scanRuntimeClasspath("com.github.rewrite.ejb")
            .build();

        // activateRecipes() returns the composite Recipe directly
        Recipe recipe = env.activateRecipes("com.github.rewrite.ejb.MigrateEjbToSpring");

        assertThat(recipe)
            .as("Recipe 'com.github.rewrite.ejb.MigrateEjbToSpring' should be loadable from YAML")
            .isNotNull();
        assertThat(recipe.getName())
            .isEqualTo("com.github.rewrite.ejb.MigrateEjbToSpring");
        assertThat(recipe.getRecipeList())
            .as("Composite should contain sub-recipes")
            .isNotEmpty();

        // Verify marker recipes and report generator are included
        List<String> recipeNames = recipe.getRecipeList().stream()
            .map(Recipe::getName)
            .toList();

        assertThat(recipeNames)
            .as("Should include marker recipes")
            .contains("com.github.rewrite.ejb.MarkJakartaBatchForMigration")
            .contains("com.github.rewrite.ejb.MarkJsfForMigration")
            // MKR-004: MarkWebSocketForMigration replaced with MigrateWebSocketToSpringConfig
            .contains("com.github.rewrite.ejb.MigrateWebSocketToSpringConfig")
            .contains("com.github.rewrite.ejb.MarkArquillianTestsForMigration")
            .as("Should include report generator")
            .contains("com.github.rewrite.ejb.GenerateMigrationReport");
    }
}
