package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.test.SourceSpecs.text;

class MigrateJndiStringToValueTest implements RewriteTest {

    private static final String NEEDS_REVIEW_STUB = """
        package com.github.rewrite.ejb.annotations;
        import java.lang.annotation.*;
        @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
        @Retention(RetentionPolicy.SOURCE)
        public @interface NeedsReview {
            String reason() default "";
            String originalCode() default "";
            String action() default "";
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateJndiStringToValue())
            .parser(JavaParser.fromJavaVersion()
                .classpath("spring-beans")
                .dependsOn(NEEDS_REVIEW_STUB));
    }

    @Test
    void noChangesWithoutApplicationProperties() {
        // Without application.properties, the recipe should not make changes
        rewriteRun(
            java(
                """
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class Service {
                    @NeedsReview(
                        reason = "JNDI needs config",
                        originalCode = "@Resource(lookup='java:app/config/Url')"
                    )
                    @Autowired
                    private String url;
                }
                """,
                spec -> spec.path("src/main/java/com/example/Service.java")
            )
        );
    }

    @Test
    void noChangesWithoutNeedsReview() {
        // Without @NeedsReview, regular @Autowired String should not be changed
        rewriteRun(
            java(
                """
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;

                public class Service {
                    @Autowired
                    private String someString;
                }
                """,
                spec -> spec.path("src/main/java/com/example/Service.java")
            ),
            properties(
                """
                spring.application.name=test
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void noChangesWithoutJndiInOriginalCode() {
        // @NeedsReview without JNDI lookup in originalCode should not be changed
        rewriteRun(
            java(
                """
                package com.example;

                import org.springframework.beans.factory.annotation.Autowired;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class Service {
                    @NeedsReview(
                        reason = "Other reason",
                        originalCode = "@Inject"
                    )
                    @Autowired
                    private String config;
                }
                """,
                spec -> spec.path("src/main/java/com/example/Service.java")
            ),
            properties(
                """
                spring.application.name=test
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    // Note: Full positive tests that verify:
    // - @Autowired -> @Value transformation
    // - Property stub appending to application.properties
    // - @NeedsReview removal
    // are verified via the integration tests in migration-test module.
    //
    // The OpenRewrite unit test framework has limitations with ScanningRecipes
    // that modify text/properties files, making it impractical to test
    // the full flow here. The no-op tests above verify the recipe parses
    // correctly and doesn't break on edge cases.
}
