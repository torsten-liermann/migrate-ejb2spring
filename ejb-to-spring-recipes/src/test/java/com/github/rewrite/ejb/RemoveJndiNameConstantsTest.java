package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link RemoveJndiNameConstants}.
 * <p>
 * JUS-003: Remove unused JNDI_NAME/JNDI_LOCAL_NAME constants from interfaces.
 */
class RemoveJndiNameConstantsTest implements RewriteTest {

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
        spec.recipe(new RemoveJndiNameConstants());
    }

    @DocumentExample
    @Test
    void removeUnusedSingleVarConstant() {
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                package com.example;

                public interface IFooHome {
                    String JNDI_NAME = "java:global/FooHome";
                }
                """,
                """
                package com.example;

                public interface IFooHome {
                }
                """
            )
        );
    }

    @Test
    void removeUnusedJndiLocalName() {
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                package com.example;

                public interface IBarLocal {
                    String JNDI_LOCAL_NAME = "java:module/BarLocal";
                }
                """,
                """
                package com.example;

                public interface IBarLocal {
                }
                """
            )
        );
    }

    @Test
    void keepOtherConstants() {
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                package com.example;

                public interface IFooService {
                    String SERVICE_NAME = "FooService";
                    int DEFAULT_TIMEOUT = 30;
                }
                """
            )
        );
    }

    @Test
    void markUsedSingleVarConstantWithNeedsReview() {
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            // Interface with JNDI_NAME - should get @NeedsReview because of usage
            java(
                """
                package com.example;

                public interface IFooHome {
                    String JNDI_NAME = "java:global/FooHome";
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;

                public interface IFooHome {
                    @NeedsReview(reason = "JNDI constant has 1 usage(s) - manual migration required", category = NeedsReview.Category.OTHER)
                    String JNDI_NAME = "java:global/FooHome";
                }
                """
            ),
            // Usage of the constant - remains unchanged
            java(
                """
                package com.example;

                public class FooClient {
                    public void lookup() {
                        String name = IFooHome.JNDI_NAME;
                    }
                }
                """
            )
        );
    }

    @Test
    void removeUnusedFromMultiVarDeclaration() {
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                package com.example;

                public interface IFoo {
                    String OTHER_CONST = "other", JNDI_NAME = "java:global/Foo";
                }
                """,
                """
                package com.example;

                public interface IFoo {
                    String OTHER_CONST = "other";
                }
                """
            )
        );
    }

    @Test
    void ignoreClassConstants() {
        // Only interfaces should be processed, not classes
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                package com.example;

                public class FooConstants {
                    public static final String JNDI_NAME = "java:global/Foo";
                }
                """
            )
        );
    }

    @Test
    void removeUnusedConstantEvenWithTypeValidationDisabled() {
        // TypeValidation.none() doesn't make types unresolved - it just skips validation.
        // The constant has no usages, so it gets removed.
        rewriteRun(
            spec -> spec.typeValidationOptions(TypeValidation.none()),
            java(NEEDS_REVIEW_STUB),
            java(
                """
                package com.example;

                public interface IUnusedConstant {
                    String JNDI_NAME = "java:global/Unused";
                }
                """,
                """
                package com.example;

                public interface IUnusedConstant {
                }
                """
            )
        );
    }

    @Test
    void removeMultipleUnusedConstants() {
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                package com.example;

                public interface IFooHome {
                    String JNDI_NAME = "java:global/FooHome";
                    String JNDI_LOCAL_NAME = "java:module/FooLocal";
                    String JNDI_GLOBAL_NAME = "java:global/FooGlobal";
                }
                """,
                """
                package com.example;

                public interface IFooHome {
                }
                """
            )
        );
    }

    @Test
    void keepNonJndiConstantsWhenRemovingJndi() {
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                package com.example;

                public interface IFooService {
                    String JNDI_NAME = "java:global/Foo";
                    String SERVICE_ID = "foo-service";
                    int TIMEOUT = 30;
                }
                """,
                """
                package com.example;

                public interface IFooService {
                    String SERVICE_ID = "foo-service";
                    int TIMEOUT = 30;
                }
                """
            )
        );
    }

    @Test
    void idempotentNeedsReviewAnnotation() {
        // If @NeedsReview already exists, don't add another one
        rewriteRun(
            spec -> spec.typeValidationOptions(TypeValidation.none()),
            java(NEEDS_REVIEW_STUB),
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;

                public interface IAlreadyMarked {
                    @NeedsReview(reason = "Already marked", category = NeedsReview.Category.OTHER)
                    String JNDI_NAME = "java:global/AlreadyMarked";
                }
                """
            )
        );
    }

    @Test
    void conservativeWhenUnresolvedUsageExists() {
        // When there's a usage of JNDI_NAME that cannot be resolved to a specific interface,
        // we must be conservative and NOT remove any JNDI_NAME constant.
        // Instead, mark it with @NeedsReview.
        rewriteRun(
            spec -> spec.typeValidationOptions(TypeValidation.none()),
            java(NEEDS_REVIEW_STUB),
            // Interface with JNDI_NAME - would normally be removed (no direct usages)
            // but because there's an unresolved usage of JNDI_NAME somewhere, be conservative
            java(
                """
                package com.example;

                public interface IFooHome {
                    String JNDI_NAME = "java:global/FooHome";
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;

                public interface IFooHome {
                    @NeedsReview(reason = "JNDI constant has unresolved usages - manual verification required", category = NeedsReview.Category.OTHER)
                    String JNDI_NAME = "java:global/FooHome";
                }
                """
            ),
            // This class references an interface not in our source set (unresolved)
            // The JNDI_NAME reference can't be resolved to IFooHome or any other known interface
            java(
                """
                package com.example;

                public class ClientWithUnresolvedReference {
                    // IUnknownService is not defined - type is unresolved
                    // This should trigger conservative behavior
                    public void lookup() {
                        String name = IUnknownService.JNDI_NAME;
                    }
                }
                """
            )
        );
    }

    @Test
    void splitMultiVarWhenUnresolvedUsageExists() {
        // Multi-var declaration with JNDI constant + unresolved usage should:
        // 1. Split the declaration
        // 2. Add @NeedsReview to the JNDI constant
        rewriteRun(
            spec -> spec.typeValidationOptions(TypeValidation.none()),
            java(NEEDS_REVIEW_STUB),
            // Interface with multi-var declaration including JNDI_NAME
            java(
                """
                package com.example;

                public interface IFoo {
                    String OTHER_CONST = "other", JNDI_NAME = "java:global/Foo";
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;

                public interface IFoo {
                    String OTHER_CONST = "other";
                    @NeedsReview(reason = "JNDI constant has unresolved usages - manual verification required", category = NeedsReview.Category.OTHER)
                    String JNDI_NAME = "java:global/Foo";
                }
                """
            ),
            // Unresolved reference to JNDI_NAME
            java(
                """
                package com.example;

                public class Client {
                    public void lookup() {
                        String name = IUnknown.JNDI_NAME;
                    }
                }
                """
            )
        );
    }
}
