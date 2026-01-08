package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class ClassifyRemainingEjbUsageTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ClassifyRemainingEjbUsage())
            .typeValidationOptions(TypeValidation.none())
            .parser(org.openrewrite.java.JavaParser.fromJavaVersion()
                .classpath("jakarta.ejb-api", "javax.ejb-api"));
    }

    @Test
    @DocumentExample
    void addsNeedsReviewForTimerServiceField() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.TimerService;

                public class MyService {
                    private TimerService timerService;
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("Remaining EJB reference: TimerService")
                        .contains("MANUAL_MIGRATION")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return actual;
                })
            )
        );
    }

    @Test
    void addsNeedsReviewForSessionContextField() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.SessionContext;

                public class MyService {
                    private SessionContext ctx;
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("Remaining EJB reference: SessionContext")
                        .contains("MANUAL_MIGRATION")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return actual;
                })
            )
        );
    }

    @Test
    void doesNotAddNeedsReviewForMigratedAnnotations() {
        // @Stateless, @MessageDriven, etc. are handled by other recipes
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class MyService {
                }
                """
            )
        );
    }

    @Test
    void doesNotAddNeedsReviewForMarkerMappedAnnotations() {
        // @Schedule, @Lock, etc. are mapped to Ejb* marker annotations
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import jakarta.ejb.Schedule;

                public class MyService {
                    @Schedule(hour = "0")
                    public void doWork() {}
                }
                """
            )
        );
    }

    @Test
    void doesNotAddDuplicateNeedsReview() {
        // Already has @NeedsReview - should not add another
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.TimerService;

                public class MyService {
                    @NeedsReview(reason = "Existing review")
                    private TimerService timerService;
                }
                """
            )
        );
    }

    @Test
    void addsNeedsReviewForTimerReturnType() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.Timer;

                public class MyService {
                    public Timer getTimer() {
                        return null;
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("Remaining EJB reference: Timer")
                        .contains("MANUAL_MIGRATION")
                        .contains("return type Timer")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return actual;
                })
            )
        );
    }

    @Test
    void doesNotAnnotateLocalVariablesUsesCommentInstead() {
        // Issue 1 Fix: Local variables get comment, not annotation
        // @NeedsReview is only valid for TYPE/METHOD/FIELD/PARAMETER, not LOCAL_VARIABLE
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.Timer;

                public class MyService {
                    public void doWork() {
                        Timer timer = null;
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .doesNotContain("@NeedsReview")
                        .contains("TODO: NeedsReview")
                        .contains("Remaining EJB reference: Timer");
                    return actual;
                })
            )
        );
    }

    @Test
    void addsNeedsReviewForParameterWithEjbType() {
        // Issue 1 Fix: Method parameters are valid targets for @NeedsReview
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.Timer;

                public class MyService {
                    public void handleTimer(Timer timer) {
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("Remaining EJB reference: Timer");
                    return actual;
                })
            )
        );
    }

    @Test
    void doesNotAnnotateInitializerBlockLocals() {
        // Issue 2 Fix: Initializer block locals get comment, not annotation
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.Timer;

                public class MyService {
                    static {
                        Timer timer = null;
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .doesNotContain("@NeedsReview")
                        .contains("TODO: NeedsReview");
                    return actual;
                })
            )
        );
    }

    @Test
    void addsNeedsReviewForEjbAccessExceptionThrowsClause() {
        // WFQ-011: EJBAccessException should be marked when allowedTypes=[]
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.EJBAccessException;

                public class SecuredService {
                    public void doSecuredWork() throws EJBAccessException {
                        // security check
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("Remaining EJB reference: EJBAccessException")
                        .contains("MANUAL_MIGRATION")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return actual;
                })
            )
        );
    }

    @Test
    void addsNeedsReviewForEjbHomeField() {
        // WFQ-012: EJB 2.x APIs (EJBHome) should be marked with @NeedsReview
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.EJBHome;

                public class LegacyClient {
                    private EJBHome customerHome;
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("Remaining EJB reference: EJBHome")
                        .contains("MANUAL_MIGRATION")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return actual;
                })
            )
        );
    }

    @Test
    void addsNeedsReviewForEjbObjectReturnType() {
        // WFQ-012: EJB 2.x APIs (EJBObject) should be marked with @NeedsReview
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.EJBObject;

                public class LegacyService {
                    public EJBObject getRemoteBean() {
                        return null;
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("Remaining EJB reference: EJBObject")
                        .contains("MANUAL_MIGRATION")
                        .contains("return type EJBObject");
                    return actual;
                })
            )
        );
    }

    @Test
    void addsNeedsReviewForEjbLocalHomeInterface() {
        // WFQ-012: EJB 2.x APIs (EJBLocalHome) in implements clause should be marked
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.EJBLocalHome;

                public interface CustomerLocalHome extends EJBLocalHome {
                    Object create();
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("Remaining EJB reference: EJBLocalHome")
                        .contains("MANUAL_MIGRATION")
                        .contains("implements EJBLocalHome");
                    return actual;
                })
            )
        );
    }

    @Test
    void addsNeedsReviewForEjbLocalObjectParameter() {
        // WFQ-012: EJB 2.x APIs (EJBLocalObject) in parameters should be marked
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.EJBLocalObject;

                public class LegacyProcessor {
                    public void process(EJBLocalObject entity) {
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@NeedsReview")
                        .contains("Remaining EJB reference: EJBLocalObject")
                        .contains("MANUAL_MIGRATION");
                    return actual;
                })
            )
        );
    }
}
