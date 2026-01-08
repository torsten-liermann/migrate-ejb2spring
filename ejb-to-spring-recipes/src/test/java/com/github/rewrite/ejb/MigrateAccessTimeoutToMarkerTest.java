package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link MigrateAccessTimeoutToMarker}.
 * <p>
 * GAP-CONC-001: Verifies that @AccessTimeout is replaced with @NeedsReview
 * containing specific guidance for Spring alternatives.
 * <p>
 * Important: @Transactional(timeout=...) is NOT suggested as it has different semantics.
 */
class MigrateAccessTimeoutToMarkerTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAccessTimeoutToMarker())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api"));
    }

    @Test
    @DocumentExample
    void migratesClassLevelAccessTimeout() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.AccessTimeout;
                import jakarta.ejb.Singleton;
                import java.util.concurrent.TimeUnit;

                @Singleton
                @AccessTimeout(value = 5, unit = TimeUnit.SECONDS)
                public class CacheService {

                    public String getValue(String key) {
                        return "value";
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.Singleton;
                import java.util.concurrent.TimeUnit;

                @NeedsReview(reason = "@AccessTimeout removed - no Spring equivalent for lock acquisition timeout", category = NeedsReview.Category.CONCURRENCY, originalCode = "@AccessTimeout(value = 5, unit = TimeUnit.SECONDS)", suggestedAction = "Consider: ReentrantLock.tryLock(timeout, unit) or Semaphore.tryAcquire(timeout, unit). Note: @Transactional(timeout=...) is NOT equivalent - it controls transaction timeout, not lock wait time.")
                @Singleton
                public class CacheService {

                    public String getValue(String key) {
                        return "value";
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesMethodLevelAccessTimeout() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.AccessTimeout;
                import jakarta.ejb.Singleton;
                import java.util.concurrent.TimeUnit;

                @Singleton
                public class ResourceService {

                    @AccessTimeout(value = 30, unit = TimeUnit.SECONDS)
                    public void processResource() {
                        // slow operation
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.Singleton;
                import java.util.concurrent.TimeUnit;

                @Singleton
                public class ResourceService {

                    @NeedsReview( reason = "@AccessTimeout removed - no Spring equivalent for lock acquisition timeout", category = NeedsReview.Category.CONCURRENCY, originalCode = "@AccessTimeout(value = 30, unit = TimeUnit.SECONDS)", suggestedAction = "Consider: ReentrantLock.tryLock(timeout, unit) or Semaphore.tryAcquire(timeout, unit). Note: @Transactional(timeout=...) is NOT equivalent - it controls transaction timeout, not lock wait time.")
                    public void processResource() {
                        // slow operation
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesAccessTimeoutWithValueOnly() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.AccessTimeout;
                import jakarta.ejb.Stateless;

                @Stateless
                @AccessTimeout(5000)
                public class QuickService {

                    public void doWork() {
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.Stateless;

                @NeedsReview(reason = "@AccessTimeout removed - no Spring equivalent for lock acquisition timeout", category = NeedsReview.Category.CONCURRENCY, originalCode = "@AccessTimeout(5000)", suggestedAction = "Consider: ReentrantLock.tryLock(timeout, unit) or Semaphore.tryAcquire(timeout, unit). Note: @Transactional(timeout=...) is NOT equivalent - it controls transaction timeout, not lock wait time.")
                @Stateless
                public class QuickService {

                    public void doWork() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesJavaxAccessTimeout() {
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api")
                .dependsOn(
                    """
                    package javax.ejb;
                    import java.lang.annotation.*;
                    import java.util.concurrent.TimeUnit;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.TYPE, ElementType.METHOD})
                    public @interface AccessTimeout {
                        long value() default 0;
                        TimeUnit unit() default TimeUnit.MILLISECONDS;
                    }
                    """,
                    """
                    package javax.ejb;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target(ElementType.TYPE)
                    public @interface Singleton {}
                    """
                )),
            java(
                """
                package com.example;

                import javax.ejb.AccessTimeout;
                import javax.ejb.Singleton;
                import java.util.concurrent.TimeUnit;

                @Singleton
                @AccessTimeout(value = 10, unit = TimeUnit.MINUTES)
                public class LegacyService {

                    public void process() {
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;

                import javax.ejb.Singleton;
                import java.util.concurrent.TimeUnit;

                @NeedsReview(reason = "@AccessTimeout removed - no Spring equivalent for lock acquisition timeout", category = NeedsReview.Category.CONCURRENCY, originalCode = "@AccessTimeout(value = 10, unit = TimeUnit.MINUTES)", suggestedAction = "Consider: ReentrantLock.tryLock(timeout, unit) or Semaphore.tryAcquire(timeout, unit). Note: @Transactional(timeout=...) is NOT equivalent - it controls transaction timeout, not lock wait time.")
                @Singleton
                public class LegacyService {

                    public void process() {
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeWithoutAccessTimeout() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.Singleton;

                @Singleton
                public class SimpleService {

                    public void doWork() {
                    }
                }
                """
            )
        );
    }

    @Test
    void addsNeedsReviewEvenWhenExistingNeedsReviewPresent() {
        // Tests that @NeedsReview for AccessTimeout is added even when
        // another @NeedsReview already exists (since @NeedsReview is repeatable)
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api")
                .dependsOn(
                    """
                    package com.github.rewrite.ejb.annotations;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Repeatable(NeedsReview.Container.class)
                    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                    public @interface NeedsReview {
                        String reason() default "";
                        Category category();
                        String originalCode() default "";
                        String suggestedAction() default "";
                        enum Category { CONCURRENCY, MANUAL_MIGRATION, OTHER }
                        @interface Container { NeedsReview[] value(); }
                    }
                    """
                )),
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.AccessTimeout;
                import jakarta.ejb.Singleton;
                import java.util.concurrent.TimeUnit;

                @NeedsReview(reason = "Some other migration issue", category = NeedsReview.Category.OTHER)
                @Singleton
                @AccessTimeout(value = 5, unit = TimeUnit.SECONDS)
                public class ServiceWithExistingReview {

                    public void doWork() {
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.Singleton;
                import java.util.concurrent.TimeUnit;

                @NeedsReview(reason = "Some other migration issue", category = NeedsReview.Category.OTHER)
                @NeedsReview(reason = "@AccessTimeout removed - no Spring equivalent for lock acquisition timeout", category = NeedsReview.Category.CONCURRENCY, originalCode = "@AccessTimeout(value = 5, unit = TimeUnit.SECONDS)", suggestedAction = "Consider: ReentrantLock.tryLock(timeout, unit) or Semaphore.tryAcquire(timeout, unit). Note: @Transactional(timeout=...) is NOT equivalent - it controls transaction timeout, not lock wait time.")
                @Singleton
                public class ServiceWithExistingReview {

                    public void doWork() {
                    }
                }
                """
            )
        );
    }
}
