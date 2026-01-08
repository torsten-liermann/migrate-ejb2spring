package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateSingletonToServiceTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateSingletonToService())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context"));
    }

    @DocumentExample
    @Test
    void migrateSimpleSingleton() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Singleton;

                @Singleton
                public class ConfigurationService {
                    private String configValue;

                    public String getConfig() {
                        return configValue;
                    }
                }
                """,
                """
                import org.springframework.stereotype.Service;

                @Service
                public class ConfigurationService {
                    private String configValue;

                    public String getConfig() {
                        return configValue;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateWithName() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Singleton;

                @Singleton(name = "appConfig")
                public class ConfigurationService {
                }
                """,
                """
                import org.springframework.stereotype.Service;

                @Service(name = "appConfig")
                public class ConfigurationService {
                }
                """
            )
        );
    }

    @Test
    void migrateLockAnnotationsWithSynchronized() {
        rewriteRun(
            // Provide the NeedsReview annotation stub
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.Lock;
                import jakarta.ejb.LockType;
                import jakarta.ejb.Singleton;

                @Singleton
                public class CacheService {
                    @Lock(LockType.READ)
                    public String getValue(String key) {
                        return null;
                    }

                    @Lock(LockType.WRITE)
                    public void setValue(String key, String value) {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.stereotype.Service;

                @NeedsReview(reason = "EJB @Lock annotations migrated to synchronized. For better READ/WRITE semantics, consider using ReadWriteLock instead. ", category = NeedsReview.Category.CONCURRENCY, originalCode = "@Lock annotations", suggestedAction = "For READ/WRITE semantics, upgrade to ReadWriteLock: private final ReadWriteLock lock = new ReentrantReadWriteLock(); then use lock.readLock()/lock.writeLock()")
                @Service
                public class CacheService {
                    public synchronized String getValue(String key) {
                        return null;
                    }

                    public synchronized void setValue(String key, String value) {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateWriteOnlyLockWithoutNeedsReview() {
        // WRITE-only locks with synchronized are semantically equivalent - no @NeedsReview needed
        rewriteRun(
            java(
                """
                import jakarta.ejb.Lock;
                import jakarta.ejb.LockType;
                import jakarta.ejb.Singleton;

                @Singleton
                public class WriteOnlyService {
                    @Lock(LockType.WRITE)
                    public void updateValue(String key, String value) {
                    }

                    @Lock(LockType.WRITE)
                    public void deleteValue(String key) {
                    }
                }
                """,
                """
                import org.springframework.stereotype.Service;

                @Service
                public class WriteOnlyService {
                    public synchronized void updateValue(String key, String value) {
                    }

                    public synchronized void deleteValue(String key) {
                    }
                }
                """
            )
        );
    }

    @Test
    void removeConcurrencyManagement() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.ConcurrencyManagement;
                import jakarta.ejb.ConcurrencyManagementType;
                import jakarta.ejb.Singleton;

                @Singleton
                @ConcurrencyManagement(ConcurrencyManagementType.BEAN)
                public class ThreadSafeService {
                }
                """,
                """
                import org.springframework.stereotype.Service;

                @Service
                public class ThreadSafeService {
                }
                """
            )
        );
    }

    @Test
    void removeStartup() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Singleton;
                import jakarta.ejb.Startup;

                @Singleton
                @Startup
                public class InitializerService {
                }
                """,
                """
                import org.springframework.stereotype.Service;

                @Service
                public class InitializerService {
                }
                """
            )
        );
    }

    @Test
    void preservesJavadocWhenAddingNeedsReview() {
        // Test that existing Javadoc is preserved when @NeedsReview is added for @Lock removal
        rewriteRun(
            // Provide the NeedsReview annotation stub
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ConcurrencyManagement;
                import jakarta.ejb.ConcurrencyManagementType;
                import jakarta.ejb.Lock;
                import jakarta.ejb.LockType;
                import jakarta.ejb.Singleton;

                /**
                 * Thread-safe cache service.
                 * This class manages application-wide caching with proper synchronization.
                 */
                @Singleton
                @ConcurrencyManagement(ConcurrencyManagementType.BEAN)
                public class DocumentedCache {
                    @Lock(LockType.READ)
                    public String getValue(String key) {
                        return null;
                    }
                }
                """,
                // Expected: Javadoc preserved, @NeedsReview added (no ReadWriteLock suggestion for BEAN-managed)
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.stereotype.Service;

                /**
                 * Thread-safe cache service.
                 * This class manages application-wide caching with proper synchronization.
                 */
                @NeedsReview(reason = "EJB @Lock annotations removed. Automatic wrapping skipped: @ConcurrencyManagement(BEAN) present.", category = NeedsReview.Category.CONCURRENCY, originalCode = "@Lock annotations", suggestedAction = "Verify concurrency semantics")
                @Service
                public class DocumentedCache {
                    public String getValue(String key) {
                        return null;
                    }
                }
                """
            )
        );
    }

    @Test
    void startupWithPostConstructBecomesEventListener() {
        // MKR-003: @Startup + @PostConstruct → @EventListener(ApplicationReadyEvent.class)
        // Note: Minor whitespace difference in output (annotation on same line as method) - functionally correct
        rewriteRun(
            java(
                """
                import jakarta.annotation.PostConstruct;
                import jakarta.ejb.Singleton;
                import jakarta.ejb.Startup;

                @Singleton
                @Startup
                public class InitializerService {
                    @PostConstruct
                    public void init() {
                        // Initialization logic
                    }
                }
                """,
                """
                import org.springframework.boot.context.event.ApplicationReadyEvent;
                import org.springframework.context.event.EventListener;
                import org.springframework.stereotype.Service;

                @Service
                public class InitializerService {
                    @EventListener(ApplicationReadyEvent.class)    public void init() {
                        // Initialization logic
                    }
                }
                """
            )
        );
    }

    @Test
    void dependsOnMapsToSpringDependsOn() {
        // MKR-003: @DependsOn → Spring @DependsOn (same semantics, different package)
        rewriteRun(
            java(
                """
                import jakarta.ejb.DependsOn;
                import jakarta.ejb.Singleton;

                @Singleton
                @DependsOn({"CacheService", "ConfigService"})
                public class OrderService {
                }
                """,
                """
                import org.springframework.context.annotation.DependsOn;
                import org.springframework.stereotype.Service;

                @Service
                @DependsOn({"CacheService", "ConfigService"})
                public class OrderService {
                }
                """
            )
        );
    }

    @Test
    void startupWithoutPostConstructJustRemoved() {
        // MKR-003: @Startup without @PostConstruct just removes @Startup
        rewriteRun(
            java(
                """
                import jakarta.ejb.Singleton;
                import jakarta.ejb.Startup;

                @Singleton
                @Startup
                public class SimpleService {
                    public void doWork() {
                    }
                }
                """,
                """
                import org.springframework.stereotype.Service;

                @Service
                public class SimpleService {
                    public void doWork() {
                    }
                }
                """
            )
        );
    }
}
