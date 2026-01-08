package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link RemoveInterceptorsWithAopMarker}.
 * <p>
 * JUS-005: Remove @Interceptors annotations and add @NeedsReview marker for AOP migration.
 */
class RemoveInterceptorsWithAopMarkerTest implements RewriteTest {

    // Stub for NeedsReview annotation
    private static final String NEEDS_REVIEW_STUB = """
        package com.github.rewrite.ejb.annotations;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
        public @interface NeedsReview {
            String reason();
            Category category() default Category.OTHER;
            String originalCode() default "";
            String suggestedAction() default "";

            enum Category {
                REMOTE_ACCESS, TIMER, CONCURRENCY, BATCH, OTHER
            }
        }
        """;

    // Stub for Jakarta Interceptors
    private static final String JAKARTA_INTERCEPTORS_STUB = """
        package jakarta.interceptor;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.METHOD})
        public @interface Interceptors {
            Class<?>[] value();
        }
        """;

    // Stub for javax Interceptors
    private static final String JAVAX_INTERCEPTORS_STUB = """
        package javax.interceptor;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.METHOD})
        public @interface Interceptors {
            Class<?>[] value();
        }
        """;

    // Stub interceptor classes
    private static final String INTERCEPTOR_A_STUB = """
        package com.example.interceptors;

        public class EntryPointProtokollInterceptor { }
        """;

    private static final String INTERCEPTOR_B_STUB = """
        package com.example.interceptors;

        public class LaufzeitProtokollInterceptor { }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveInterceptorsWithAopMarker());
    }

    @DocumentExample
    @Test
    void removeClassLevelInterceptors() {
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(JAKARTA_INTERCEPTORS_STUB),
            java(INTERCEPTOR_A_STUB),
            java(INTERCEPTOR_B_STUB),
            java(
                """
                package com.example;

                import jakarta.interceptor.Interceptors;
                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.example.interceptors.LaufzeitProtokollInterceptor;

                @Interceptors({EntryPointProtokollInterceptor.class, LaufzeitProtokollInterceptor.class})
                public class MyFassade { }
                """,
                """
                package com.example;

                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.example.interceptors.LaufzeitProtokollInterceptor;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "EJB Interceptors removed: EntryPointProtokollInterceptor, LaufzeitProtokollInterceptor - migrate to Spring AOP. Configure @Order on generated @Aspect classes to preserve execution sequence.", category = NeedsReview.Category.OTHER)
                public class MyFassade { }
                """
            )
        );
    }

    @Test
    void removeMethodLevelInterceptors() {
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(JAKARTA_INTERCEPTORS_STUB),
            java(INTERCEPTOR_A_STUB),
            java(
                """
                package com.example;

                import jakarta.interceptor.Interceptors;
                import com.example.interceptors.EntryPointProtokollInterceptor;

                public class MyService {
                    @Interceptors(EntryPointProtokollInterceptor.class)
                    public void doSomething() { }
                }
                """,
                """
                package com.example;

                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class MyService {
                    @NeedsReview(reason = "EJB Interceptors removed: EntryPointProtokollInterceptor - migrate to Spring AOP", category = NeedsReview.Category.OTHER)
                    public void doSomething() { }
                }
                """
            )
        );
    }

    @Test
    void handleSingleInterceptor() {
        // Single interceptor without array syntax
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(JAKARTA_INTERCEPTORS_STUB),
            java(INTERCEPTOR_A_STUB),
            java(
                """
                package com.example;

                import jakarta.interceptor.Interceptors;
                import com.example.interceptors.EntryPointProtokollInterceptor;

                @Interceptors(EntryPointProtokollInterceptor.class)
                public class MyFassade { }
                """,
                """
                package com.example;

                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "EJB Interceptors removed: EntryPointProtokollInterceptor - migrate to Spring AOP", category = NeedsReview.Category.OTHER)
                public class MyFassade { }
                """
            )
        );
    }

    @Test
    void handleJavaxNamespace() {
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(JAVAX_INTERCEPTORS_STUB),
            java(INTERCEPTOR_A_STUB),
            java(
                """
                package com.example;

                import javax.interceptor.Interceptors;
                import com.example.interceptors.EntryPointProtokollInterceptor;

                @Interceptors(EntryPointProtokollInterceptor.class)
                public class MyFassade { }
                """,
                """
                package com.example;

                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "EJB Interceptors removed: EntryPointProtokollInterceptor - migrate to Spring AOP", category = NeedsReview.Category.OTHER)
                public class MyFassade { }
                """
            )
        );
    }

    @Test
    void noOpWhenNoInterceptors() {
        // Class without @Interceptors should remain unchanged
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(JAKARTA_INTERCEPTORS_STUB),
            java(
                """
                package com.example;

                public class MyFassade { }
                """
            )
        );
    }

    @Test
    void preserveOtherAnnotations() {
        // Other annotations on the class should remain
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(JAKARTA_INTERCEPTORS_STUB),
            java(INTERCEPTOR_A_STUB),
            java(
                """
                package com.example;

                import jakarta.interceptor.Interceptors;
                import com.example.interceptors.EntryPointProtokollInterceptor;

                @Deprecated
                @Interceptors(EntryPointProtokollInterceptor.class)
                public class MyFassade { }
                """,
                """
                package com.example;

                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "EJB Interceptors removed: EntryPointProtokollInterceptor - migrate to Spring AOP", category = NeedsReview.Category.OTHER)
                @Deprecated
                public class MyFassade { }
                """
            )
        );
    }

    @Test
    void idempotentNeedsReview() {
        // If @NeedsReview already exists, don't add another one
        // Note: In this case, @Interceptors is still removed but no new @NeedsReview is added
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(JAKARTA_INTERCEPTORS_STUB),
            java(INTERCEPTOR_A_STUB),
            java(
                """
                package com.example;

                import jakarta.interceptor.Interceptors;
                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "Already marked", category = NeedsReview.Category.OTHER)
                @Interceptors(EntryPointProtokollInterceptor.class)
                public class MyFassade { }
                """,
                """
                package com.example;

                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "Already marked", category = NeedsReview.Category.OTHER)
                public class MyFassade { }
                """
            )
        );
    }

    @Test
    void handleUnresolvedInterceptorType() {
        // When interceptor type cannot be resolved, use simple name with marker
        rewriteRun(
            spec -> spec.typeValidationOptions(TypeValidation.none()),
            java(NEEDS_REVIEW_STUB),
            java(JAKARTA_INTERCEPTORS_STUB),
            java(
                """
                package com.example;

                import jakarta.interceptor.Interceptors;

                @Interceptors(UnknownInterceptor.class)
                public class MyFassade { }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "EJB Interceptors removed: UnknownInterceptor (unresolved) - migrate to Spring AOP", category = NeedsReview.Category.OTHER)
                public class MyFassade { }
                """
            )
        );
    }

    @Test
    void handleBothClassAndMethodInterceptors() {
        // Both class-level and method-level @Interceptors
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(JAKARTA_INTERCEPTORS_STUB),
            java(INTERCEPTOR_A_STUB),
            java(INTERCEPTOR_B_STUB),
            java(
                """
                package com.example;

                import jakarta.interceptor.Interceptors;
                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.example.interceptors.LaufzeitProtokollInterceptor;

                @Interceptors(EntryPointProtokollInterceptor.class)
                public class MyService {
                    @Interceptors(LaufzeitProtokollInterceptor.class)
                    public void doSomething() { }
                }
                """,
                """
                package com.example;

                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.example.interceptors.LaufzeitProtokollInterceptor;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "EJB Interceptors removed: EntryPointProtokollInterceptor - migrate to Spring AOP", category = NeedsReview.Category.OTHER)
                public class MyService {
                    @NeedsReview(reason = "EJB Interceptors removed: LaufzeitProtokollInterceptor - migrate to Spring AOP", category = NeedsReview.Category.OTHER)
                    public void doSomething() { }
                }
                """
            )
        );
    }

    @Test
    void handleOverloadedMethodsWithOnlyOneAnnotated() {
        // HIGH fix validation: Only the annotated overload should get @NeedsReview
        // The non-annotated overload with the same method name must remain untouched
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(JAKARTA_INTERCEPTORS_STUB),
            java(INTERCEPTOR_A_STUB),
            java(
                """
                package com.example;

                import jakarta.interceptor.Interceptors;
                import com.example.interceptors.EntryPointProtokollInterceptor;

                public class MyService {
                    public void process() { }

                    @Interceptors(EntryPointProtokollInterceptor.class)
                    public void process(String input) { }

                    public void process(String input, int count) { }
                }
                """,
                """
                package com.example;

                import com.example.interceptors.EntryPointProtokollInterceptor;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class MyService {
                    public void process() { }

                    @NeedsReview(reason = "EJB Interceptors removed: EntryPointProtokollInterceptor - migrate to Spring AOP", category = NeedsReview.Category.OTHER)
                    public void process(String input) { }

                    public void process(String input, int count) { }
                }
                """
            )
        );
    }

    @Test
    void handleEmptyInterceptorList() {
        // LOW fix validation: Empty @Interceptors({}) should still get @NeedsReview with generic message
        rewriteRun(
            spec -> spec.typeValidationOptions(TypeValidation.none()),
            java(NEEDS_REVIEW_STUB),
            java(JAKARTA_INTERCEPTORS_STUB),
            java(
                """
                package com.example;

                import jakarta.interceptor.Interceptors;

                @Interceptors({})
                public class MyFassade { }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "EJB Interceptors removed (unresolved) - migrate to Spring AOP. Configure @Order on generated @Aspect classes to preserve execution sequence.", category = NeedsReview.Category.OTHER)
                public class MyFassade { }
                """
            )
        );
    }
}
