package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MarkEjbTimerServiceForReviewTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkEjbTimerServiceForReview())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context"));
    }

    @Test
    void addsClassLevelNeedsReviewForTimerService() {
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    Category category();
                    String reason() default "";
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { CONFIGURATION, MANUAL_MIGRATION }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class TimerBean {
                    @Resource
                    TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;
                import org.springframework.context.annotation.Profile;

                @NeedsReview(reason = "Programmatic EJB timers require refactoring to Spring TaskScheduler", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "@Timeout / TimerService", suggestedAction = "Replace TimerService with TaskScheduler and schedule via schedule()/scheduleAtFixedRate()")
                @Profile("manual-migration")
                public class TimerBean {
                    @Resource
                    TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void addsClassLevelManualNeedsReviewWhenClassAlreadyMarked() {
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    Category category();
                    String reason() default "";
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { CONFIGURATION, MANUAL_MIGRATION }
                }
                """
            ),
            java(
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "Existing issue", category = NeedsReview.Category.CONFIGURATION, originalCode = "x", suggestedAction = "y")
                @Component
                public class TimerBean {
                    @Resource
                    TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;
                import org.springframework.context.annotation.Profile;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "Existing issue", category = NeedsReview.Category.CONFIGURATION, originalCode = "x", suggestedAction = "y")
                @Profile("manual-migration")
                @NeedsReview(reason = "Programmatic EJB timers require refactoring to Spring TaskScheduler", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "@Timeout / TimerService", suggestedAction = "Replace TimerService with TaskScheduler and schedule via schedule()/scheduleAtFixedRate()")
                @Component
                public class TimerBean {
                    @Resource
                    TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }
}
