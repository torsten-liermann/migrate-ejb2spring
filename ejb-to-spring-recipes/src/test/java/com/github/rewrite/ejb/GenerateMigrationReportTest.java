package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

class GenerateMigrationReportTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new GenerateMigrationReport())
            // Skip type validation since NeedsReview annotation is from test source
            .typeValidationOptions(TypeValidation.none())
            // Only 1 cycle needed - generate() creates the file once
            .cycles(1)
            .expectedCyclesThatMakeChanges(1)
            .parser(org.openrewrite.java.JavaParser.fromJavaVersion()
                .classpath("spring-beans", "spring-context", "jakarta.annotation-api"));
    }

    @Test
    @DocumentExample
    void detectsFieldLevelNeedsReview() {
        rewriteRun(
            // Input: Class with @NeedsReview on a field
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.beans.factory.annotation.Autowired;
                import javax.sql.DataSource;

                public class DatabaseSetup {
                    @NeedsReview(
                        reason = "JNDI lookup needs Spring configuration",
                        category = NeedsReview.Category.CONFIGURATION,
                        originalCode = "@Resource(lookup='java:app/jdbc/DB')",
                        suggestedAction = "Create @Bean definition"
                    )
                    @Autowired
                    DataSource dataSource;
                }
                """
            ),
            // Expected: MIGRATION-REVIEW.md is generated
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        // Verify report contains expected content
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            .contains("Configuration")
                            .contains("DatabaseSetup")
                            .contains("dataSource")
                            .contains("JNDI lookup needs Spring configuration");
                        return actual;
                    })
            )
        );
    }

    @Test
    void detectsMethodLevelNeedsReview() {
        rewriteRun(
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class ServiceClass {
                    @NeedsReview(
                        reason = "Async method needs configuration",
                        category = NeedsReview.Category.ASYNC,
                        originalCode = "@Asynchronous",
                        suggestedAction = "Enable @EnableAsync"
                    )
                    public void doAsyncWork() {
                    }
                }
                """
            ),
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Async")
                            .contains("ServiceClass")
                            .contains("doAsyncWork()");
                        return actual;
                    })
            )
        );
    }

    @Test
    void noReportWhenNoAnnotations() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0), // No changes expected
            java(
                """
                package com.example;

                public class SimpleClass {
                    private String name;

                    public String getName() {
                        return name;
                    }
                }
                """
            )
            // No text() assertion - no MIGRATION-REVIEW.md should be generated
        );
    }

    @Test
    void detectsEjbScheduleMarkerOnMethod() {
        rewriteRun(
            java(
                """
                package com.example;

                import com.github.migration.annotations.EjbSchedule;

                public class ScheduledTaskBean {
                    @EjbSchedule(hour = "0", minute = "30", info = "Daily cleanup", year = "2024")
                    public void dailyCleanup() {
                        // Cleanup logic
                    }
                }
                """
            ),
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            .contains("EJB Marker Annotations")
                            .contains("@EjbSchedule")
                            .contains("ScheduledTaskBean")
                            .contains("dailyCleanup()");
                        return actual;
                    })
            )
        );
    }

    @Test
    void detectsEjbStatefulMarkerOnClass() {
        rewriteRun(
            java(
                """
                package com.example;

                import com.github.migration.annotations.EjbStateful;

                @EjbStateful(name = "ShoppingCartBean", passivationCapable = true)
                public class ShoppingCartBean {
                    private String cartId;

                    public void addItem(String item) {
                        // Add item logic
                    }
                }
                """
            ),
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            .contains("EJB Marker Annotations")
                            .contains("@EjbStateful")
                            .contains("ShoppingCartBean")
                            .contains("passivationCapable");
                        return actual;
                    })
            )
        );
    }

    @Test
    void detectsEjbTimeoutMarkerOnMethod() {
        rewriteRun(
            java(
                """
                package com.example;

                import com.github.migration.annotations.EjbTimeout;

                public class TimerBean {
                    @EjbTimeout
                    public void handleTimeout() {
                        // Timeout handling
                    }
                }
                """
            ),
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            .contains("EJB Marker Annotations")
                            .contains("@EjbTimeout")
                            .contains("TimerBean")
                            .contains("handleTimeout()");
                        return actual;
                    })
            )
        );
    }

    @Test
    void detectsMultipleEjbMarkersAndNeedsReview() {
        rewriteRun(
            java(
                """
                package com.example;

                import com.github.migration.annotations.EjbStateful;
                import com.github.migration.annotations.EjbSchedule;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbStateful(name = "ComplexBean")
                public class ComplexBean {

                    @NeedsReview(
                        reason = "Complex timer logic needs manual review",
                        category = NeedsReview.Category.SCHEDULING,
                        originalCode = "@Schedule + TimerService",
                        suggestedAction = "Consider Quartz for complex scheduling"
                    )
                    @EjbSchedule(hour = "*/2", minute = "0")
                    public void complexScheduledTask() {
                        // Complex scheduling logic
                    }
                }
                """
            ),
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            // NeedsReview section
                            .contains("Items Marked with @NeedsReview")
                            .contains("Scheduling")
                            .contains("Complex timer logic needs manual review")
                            // EJB Markers section
                            .contains("EJB Marker Annotations")
                            .contains("@EjbStateful")
                            .contains("@EjbSchedule")
                            .contains("ComplexBean")
                            .contains("complexScheduledTask()");
                        return actual;
                    })
            )
        );
    }

    @Test
    void detectsEjbQuartzTimerServiceMarkerOnField() {
        rewriteRun(
            java(
                """
                package com.example;

                import com.github.migration.annotations.EjbQuartzTimerService;

                public class TimerServiceBean {
                    @EjbQuartzTimerService
                    private Object timerService;

                    public void createTimer() {
                        // Timer creation logic
                    }
                }
                """
            ),
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            .contains("EJB Marker Annotations")
                            .contains("@EjbQuartzTimerService")
                            .contains("TimerServiceBean")
                            .contains("timerService");
                        return actual;
                    })
            )
        );
    }

    @Test
    void detectsAllVariablesInMultiVariableDeclaration() {
        rewriteRun(
            java(
                """
                package com.example;

                import com.github.migration.annotations.EjbQuartzTimerService;

                public class MultiFieldBean {
                    @EjbQuartzTimerService
                    private Object fieldA, fieldB, fieldC;

                    public void doWork() {
                    }
                }
                """
            ),
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            .contains("EJB Marker Annotations")
                            .contains("@EjbQuartzTimerService")
                            .contains("MultiFieldBean")
                            // All three fields should be reported
                            .contains("fieldA")
                            .contains("fieldB")
                            .contains("fieldC");
                        return actual;
                    })
            )
        );
    }

    @Test
    void detectsFqnEjbMarkerWithoutImport() {
        rewriteRun(
            java(
                """
                package com.example;

                public class FqnBean {
                    @com.github.migration.annotations.EjbSchedule
                    public void run() {}
                }
                """
            ),
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            .contains("EJB Marker Annotations")
                            .contains("@EjbSchedule")
                            .contains("FqnBean")
                            .contains("run()");
                        return actual;
                    })
            )
        );
    }

    @Test
    void detectsFqnNeedsReviewWithoutImport() {
        rewriteRun(
            java(
                """
                package com.example;

                public class FqnReviewBean {
                    @com.github.rewrite.ejb.annotations.NeedsReview(
                        reason = "FQN annotation test",
                        category = com.github.rewrite.ejb.annotations.NeedsReview.Category.OTHER
                    )
                    private String testField;
                }
                """
            ),
            text(
                null,
                spec -> spec.path("MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            .contains("Items Marked with @NeedsReview")
                            .contains("FqnReviewBean")
                            .contains("testField")
                            .contains("FQN annotation test");
                        return actual;
                    })
            )
        );
    }

    @Test
    void doesNotDetectNonMigrationAnnotationWithSameName() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0), // No changes expected
            java(
                """
                package com.example;

                // Import from a different package, not com.github.migration.annotations
                import com.other.EjbSchedule;

                public class NonMigrationBean {
                    @EjbSchedule
                    public void scheduledMethod() {
                    }
                }
                """
            ),
            java(
                """
                package com.other;

                public @interface EjbSchedule {
                }
                """
            )
            // No report should be generated since the import is not from the migration package
        );
    }

    @Test
    void generatesPerModuleReports() {
        // WFQ-007: Test that reports are generated per module in multi-module projects
        rewriteRun(
            // Module A: has NeedsReview annotation
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class ServiceA {
                    @NeedsReview(
                        reason = "Module A needs review",
                        category = NeedsReview.Category.CONFIGURATION
                    )
                    private String configA;
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/ServiceA.java")
            ),
            // Module B: has EjbSchedule marker
            java(
                """
                package com.example;

                import com.github.migration.annotations.EjbSchedule;

                public class ServiceB {
                    @EjbSchedule(hour = "0")
                    public void scheduledTask() {
                    }
                }
                """,
                spec -> spec.path("module-b/src/main/java/com/example/ServiceB.java")
            ),
            // Expected: Module A report
            text(
                null,
                spec -> spec.path("module-a/MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            .contains("Module:** `module-a`")
                            .contains("ServiceA")
                            .contains("configA")
                            .contains("Module A needs review")
                            // Should NOT contain Module B content
                            .doesNotContain("ServiceB")
                            .doesNotContain("scheduledTask");
                        return actual;
                    })
            ),
            // Expected: Module B report
            text(
                null,
                spec -> spec.path("module-b/MIGRATION-REVIEW.md")
                    .after(actual -> {
                        org.assertj.core.api.Assertions.assertThat(actual)
                            .contains("Migration Review Report")
                            .contains("Module:** `module-b`")
                            .contains("ServiceB")
                            .contains("scheduledTask")
                            .contains("@EjbSchedule")
                            // Should NOT contain Module A content
                            .doesNotContain("ServiceA")
                            .doesNotContain("configA");
                        return actual;
                    })
            )
        );
    }
}
