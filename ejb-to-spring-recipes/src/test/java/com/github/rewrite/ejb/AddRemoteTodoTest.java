package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link AddRemoteTodo}.
 * <p>
 * JUS-002: AddRemoteTodo with simple-name fallback for unresolved types.
 */
class AddRemoteTodoTest implements RewriteTest {

    // Stub for EJB Remote annotation (javax namespace)
    private static final String REMOTE_JAVAX_STUB = """
        package javax.ejb;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Remote {
            Class<?>[] value() default {};
        }
        """;

    // Stub for EJB Remote annotation (jakarta namespace)
    private static final String REMOTE_JAKARTA_STUB = """
        package jakarta.ejb;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Remote {
            Class<?>[] value() default {};
        }
        """;

    // Stub for EJB Stateless annotation
    private static final String STATELESS_STUB = """
        package javax.ejb;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Stateless {
            String name() default "";
        }
        """;

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

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddRemoteTodo());
    }

    @DocumentExample
    @Test
    void removeRemoteAndAddNeedsReview() {
        rewriteRun(
            java(REMOTE_JAVAX_STUB),
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import javax.ejb.Remote;

                @Remote
                public class FooService {
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "Remote EJB requires manual migration to REST API, gRPC, or Spring Remoting",  category = NeedsReview.Category.REMOTE_ACCESS,  originalCode = "@Remote",  suggestedAction = "Convert to REST controller or use Spring Cloud for service communication")
                public class FooService {
                }
                """
            )
        );
    }

    @Test
    void removeRemoteWithStateless() {
        rewriteRun(
            java(REMOTE_JAVAX_STUB),
            java(STATELESS_STUB),
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import javax.ejb.Remote;
                import javax.ejb.Stateless;

                @Remote
                @Stateless
                public class FooService {
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                import javax.ejb.Stateless;



                @NeedsReview(reason = "Remote EJB requires manual migration to REST API, gRPC, or Spring Remoting", category = NeedsReview.Category.REMOTE_ACCESS, originalCode = "@Remote", suggestedAction = "Convert to REST controller or use Spring Cloud for service communication")
                @Stateless
                public class FooService {
                }
                """
            )
        );
    }

    @Test
    void noChangeWithoutRemote() {
        rewriteRun(
            java(STATELESS_STUB),
            java(
                """
                import javax.ejb.Stateless;

                @Stateless
                public class FooService {
                }
                """
            )
        );
    }

    /**
     * JUS-002 Test: Unresolved @Remote annotation handled via simple-name fallback.
     * When EJB API is provided-only (not on parser classpath), the type is unresolved.
     * This test does NOT include the Remote stub, so annotation.getType() returns null
     * and the simple-name fallback path is exercised.
     */
    @Test
    void unresolvedRemoteAnnotationHandled() {
        rewriteRun(
            spec -> spec.typeValidationOptions(TypeValidation.none()),
            // Note: NO REMOTE_JAVAX_STUB - @Remote type is unresolved!
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import javax.ejb.Remote;

                @Remote
                public class UnresolvedRemoteService {
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "Remote EJB requires manual migration to REST API, gRPC, or Spring Remoting",  category = NeedsReview.Category.REMOTE_ACCESS,  originalCode = "@Remote",  suggestedAction = "Convert to REST controller or use Spring Cloud for service communication")
                public class UnresolvedRemoteService {
                }
                """
            )
        );
    }

    /**
     * JUS-002 Test: Fully qualified @Remote in source code handled.
     * When @javax.ejb.Remote is written as FQN directly in source.
     * This test does NOT include the Remote stub, so annotation.getType() returns null
     * and the J.FieldAccess fallback path is exercised.
     */
    @Test
    void fullyQualifiedRemoteInSourceHandled() {
        rewriteRun(
            spec -> spec.typeValidationOptions(TypeValidation.none()),
            // Note: NO REMOTE_JAVAX_STUB - @javax.ejb.Remote type is unresolved!
            java(NEEDS_REVIEW_STUB),
            java(
                """
                @javax.ejb.Remote
                public class FqnRemoteService {
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "Remote EJB requires manual migration to REST API, gRPC, or Spring Remoting", category = NeedsReview.Category.REMOTE_ACCESS, originalCode = "@Remote", suggestedAction = "Convert to REST controller or use Spring Cloud for service communication")
                public class FqnRemoteService {
                }
                """
            )
        );
    }

    @Test
    void jakartaNamespaceRemoteHandled() {
        rewriteRun(
            java(REMOTE_JAKARTA_STUB),
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import jakarta.ejb.Remote;

                @Remote
                public class JakartaRemoteService {
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "Remote EJB requires manual migration to REST API, gRPC, or Spring Remoting",  category = NeedsReview.Category.REMOTE_ACCESS,  originalCode = "@Remote",  suggestedAction = "Convert to REST controller or use Spring Cloud for service communication")
                public class JakartaRemoteService {
                }
                """
            )
        );
    }
}
