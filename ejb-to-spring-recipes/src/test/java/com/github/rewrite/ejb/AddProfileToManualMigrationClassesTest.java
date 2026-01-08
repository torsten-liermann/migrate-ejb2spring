package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class AddProfileToManualMigrationClassesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddProfileToManualMigrationClasses())
            .parser(JavaParser.fromJavaVersion()
                .classpath("spring-context"));
    }

    @Test
    void addsProfileWhenManualReviewOnField() {
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
                import org.springframework.stereotype.Component;

                @Component
                public class FieldManualReview {
                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "x")
                    private String value;
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.context.annotation.Profile;
                import org.springframework.stereotype.Component;

                @Profile("manual-migration")
                @Component
                public class FieldManualReview {
                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "x")
                    private String value;
                }
                """
            )
        );
    }

    @Test
    void addsProfileWhenManualReviewOnMethod() {
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

                public class MethodManualReview {
                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "x")
                    public void run() {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.context.annotation.Profile;

                @Profile("manual-migration")
                public class MethodManualReview {
                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "x")
                    public void run() {
                    }
                }
                """
            )
        );
    }
}
