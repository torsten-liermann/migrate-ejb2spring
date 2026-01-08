package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateStatefulBeanTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateStatefulBean())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.ejb-api", "javax.ejb-api", "spring-context", "jakarta.annotation-api")
                .dependsOn(
                    """
                    package com.github.rewrite.ejb.annotations;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                    public @interface NeedsReview {
                        String reason() default "";
                        Category category();
                        String originalCode() default "";
                        String suggestedAction() default "";
                        enum Category { STATEFUL_BEAN, MANUAL_MIGRATION }
                    }
                    """
                ));
    }

    @Test
    @DocumentExample
    void migratesBasicStatefulBean() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Stateful;

                @Stateful
                public class ShoppingCart {
                    private int itemCount = 0;

                    public void addItem() {
                        itemCount++;
                    }

                    public int getItemCount() {
                        return itemCount;
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.context.annotation.Scope;
                import org.springframework.stereotype.Service;

                @NeedsReview(reason = "Stateful EJB migrated to prototype scope. Consider: For web sessions use @SessionScope; inject via ObjectProvider<T> for lazy/fresh instances.", category = NeedsReview.Category.STATEFUL_BEAN, originalCode = "@Stateful", suggestedAction = "Review scope: @SessionScope for web session affinity, @Scope(\\"prototype\\") + ObjectProvider<T> for per-call instances. For cleanup, call ObjectProvider.destroy(bean) or implement explicit disposal.")
                @Scope("prototype")
                @Service
                public class ShoppingCart {
                    private int itemCount = 0;

                    public void addItem() {
                        itemCount++;
                    }

                    public int getItemCount() {
                        return itemCount;
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesStatefulWithRemoveMethod() {
        // @Remove is removed; @NeedsReview explains manual cleanup needed
        rewriteRun(
            java(
                """
                import jakarta.ejb.Remove;
                import jakarta.ejb.Stateful;

                @Stateful
                public class BookingSession {
                    private String bookingId;

                    public void setBookingId(String id) {
                        this.bookingId = id;
                    }

                    @Remove
                    public void checkout() {
                        // Cleanup and complete booking
                    }
                }
                """,
                // @Remove removed - method becomes regular cleanup method requiring manual invocation
                "import com.github.rewrite.ejb.annotations.NeedsReview;\n" +
                "import org.springframework.context.annotation.Scope;\n" +
                "import org.springframework.stereotype.Service;\n" +
                "\n" +
                "@NeedsReview(reason = \"Stateful EJB migrated to prototype scope. @Remove methods retained but require manual cleanup: Spring does NOT auto-call @PreDestroy for prototype-scoped beans. Use ObjectProvider<T>.destroy(bean) or implement explicit disposal. Consider: For web sessions use @SessionScope; inject via ObjectProvider<T> for lazy/fresh instances.\", category = NeedsReview.Category.STATEFUL_BEAN, originalCode = \"@Stateful\", suggestedAction = \"Review scope: @SessionScope for web session affinity, @Scope(\\\"prototype\\\") + ObjectProvider<T> for per-call instances. For cleanup, call ObjectProvider.destroy(bean) or implement explicit disposal.\")\n" +
                "@Scope(\"prototype\")\n" +
                "@Service\n" +
                "public class BookingSession {\n" +
                "    private String bookingId;\n" +
                "\n" +
                "    public void setBookingId(String id) {\n" +
                "        this.bookingId = id;\n" +
                "    }\n" +
                "    \n" +  // Trailing whitespace from removed @Remove
                "    public void checkout() {\n" +
                "        // Cleanup and complete booking\n" +
                "    }\n" +
                "}\n"
            )
        );
    }

    @Test
    void migratesStatefulWithLifecycleCallbacks() {
        // Note: When @PostActivate/@PrePassivate are removed, blank lines may have trailing whitespace
        // due to how OpenRewrite handles whitespace. The expected output matches actual recipe behavior.
        rewriteRun(
            java(
                """
                import jakarta.ejb.PostActivate;
                import jakarta.ejb.PrePassivate;
                import jakarta.ejb.Stateful;
                import jakarta.ejb.StatefulTimeout;
                import java.util.concurrent.TimeUnit;

                @Stateful
                @StatefulTimeout(value = 30, unit = TimeUnit.MINUTES)
                public class UserSession {
                    private String userId;

                    @PostActivate
                    public void afterActivation() {
                        // Restore state
                    }

                    @PrePassivate
                    public void beforePassivation() {
                        // Save state
                    }
                }
                """,
                // Expected output with trailing whitespace on blank lines (4 spaces)
                "import com.github.rewrite.ejb.annotations.NeedsReview;\n" +
                "import org.springframework.context.annotation.Scope;\n" +
                "import org.springframework.stereotype.Service;\n" +
                "\n" +
                "import java.util.concurrent.TimeUnit;\n" +
                "\n" +
                "@NeedsReview( reason = \"Stateful EJB migrated to prototype scope. @PostActivate/@PrePassivate removed (passivation not supported in Spring). @StatefulTimeout removed (no Spring equivalent; consider application-level timeout). Consider: For web sessions use @SessionScope; inject via ObjectProvider<T> for lazy/fresh instances.\", category = NeedsReview.Category.STATEFUL_BEAN, originalCode = \"@Stateful\", suggestedAction = \"Review scope: @SessionScope for web session affinity, @Scope(\\\"prototype\\\") + ObjectProvider<T> for per-call instances. For cleanup, call ObjectProvider.destroy(bean) or implement explicit disposal.\")\n" +
                "@Scope(\"prototype\")\n" +
                "@Service\n" +
                "public class UserSession {\n" +
                "    private String userId;\n" +
                "    \n" +  // Blank line with trailing whitespace from removed @PostActivate
                "    public void afterActivation() {\n" +
                "        // Restore state\n" +
                "    }\n" +
                "    \n" +  // Blank line with trailing whitespace from removed @PrePassivate
                "    public void beforePassivation() {\n" +
                "        // Save state\n" +
                "    }\n" +
                "}\n"
            )
        );
    }

    @Test
    void migratesJavaxStatefulBean() {
        // @Remove is removed; method becomes regular cleanup method
        rewriteRun(
            java(
                """
                import javax.ejb.Remove;
                import javax.ejb.Stateful;

                @Stateful
                public class LegacyCart {
                    private int count;

                    @Remove
                    public void clear() {
                        count = 0;
                    }
                }
                """,
                "import com.github.rewrite.ejb.annotations.NeedsReview;\n" +
                "import org.springframework.context.annotation.Scope;\n" +
                "import org.springframework.stereotype.Service;\n" +
                "\n" +
                "@NeedsReview(reason = \"Stateful EJB migrated to prototype scope. @Remove methods retained but require manual cleanup: Spring does NOT auto-call @PreDestroy for prototype-scoped beans. Use ObjectProvider<T>.destroy(bean) or implement explicit disposal. Consider: For web sessions use @SessionScope; inject via ObjectProvider<T> for lazy/fresh instances.\", category = NeedsReview.Category.STATEFUL_BEAN, originalCode = \"@Stateful\", suggestedAction = \"Review scope: @SessionScope for web session affinity, @Scope(\\\"prototype\\\") + ObjectProvider<T> for per-call instances. For cleanup, call ObjectProvider.destroy(bean) or implement explicit disposal.\")\n" +
                "@Scope(\"prototype\")\n" +
                "@Service\n" +
                "public class LegacyCart {\n" +
                "    private int count;\n" +
                "    \n" +  // Trailing whitespace from removed @Remove
                "    public void clear() {\n" +
                "        count = 0;\n" +
                "    }\n" +
                "}\n"
            )
        );
    }

    @Test
    void doesNotModifyNonStatefulClass() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Stateless;

                @Stateless
                public class OrderService {
                    public void placeOrder() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesStatefulWithMultipleRemoveMethods() {
        // Multiple @Remove methods are removed - methods become regular cleanup methods
        rewriteRun(
            java(
                """
                import jakarta.ejb.Remove;
                import jakarta.ejb.Stateful;

                @Stateful
                public class MultiRemoveBean {
                    @Remove
                    public void close() {
                    }

                    @Remove(retainIfException = true)
                    public void closeWithRetain() {
                    }
                }
                """,
                "import com.github.rewrite.ejb.annotations.NeedsReview;\n" +
                "import org.springframework.context.annotation.Scope;\n" +
                "import org.springframework.stereotype.Service;\n" +
                "\n" +
                "@NeedsReview(reason = \"Stateful EJB migrated to prototype scope. @Remove methods retained but require manual cleanup: Spring does NOT auto-call @PreDestroy for prototype-scoped beans. Use ObjectProvider<T>.destroy(bean) or implement explicit disposal. Consider: For web sessions use @SessionScope; inject via ObjectProvider<T> for lazy/fresh instances.\", category = NeedsReview.Category.STATEFUL_BEAN, originalCode = \"@Stateful\", suggestedAction = \"Review scope: @SessionScope for web session affinity, @Scope(\\\"prototype\\\") + ObjectProvider<T> for per-call instances. For cleanup, call ObjectProvider.destroy(bean) or implement explicit disposal.\")\n" +
                "@Scope(\"prototype\")\n" +
                "@Service\n" +
                "public class MultiRemoveBean {\n" +
                "    \n" +  // Trailing whitespace from removed @Remove
                "    public void close() {\n" +
                "    }\n" +
                "    \n" +  // Trailing whitespace from removed @Remove
                "    public void closeWithRetain() {\n" +
                "    }\n" +
                "}\n"
            )
        );
    }

    @Test
    void migratesStatefulWithAllLifecycleAnnotations() {
        // All lifecycle annotations removed; methods become regular methods
        rewriteRun(
            java(
                """
                import jakarta.ejb.PostActivate;
                import jakarta.ejb.PrePassivate;
                import jakarta.ejb.Remove;
                import jakarta.ejb.Stateful;
                import jakarta.ejb.StatefulTimeout;
                import java.util.concurrent.TimeUnit;

                @Stateful
                @StatefulTimeout(value = 1, unit = TimeUnit.HOURS)
                public class FullLifecycleBean {
                    @PostActivate
                    public void activate() {
                    }

                    @PrePassivate
                    public void passivate() {
                    }

                    @Remove
                    public void cleanup() {
                    }
                }
                """,
                // All lifecycle annotations removed (note: space after @NeedsReview( due to AST construction)
                "import com.github.rewrite.ejb.annotations.NeedsReview;\n" +
                "import org.springframework.context.annotation.Scope;\n" +
                "import org.springframework.stereotype.Service;\n" +
                "\n" +
                "import java.util.concurrent.TimeUnit;\n" +
                "\n" +
                "@NeedsReview( reason = \"Stateful EJB migrated to prototype scope. @PostActivate/@PrePassivate removed (passivation not supported in Spring). @StatefulTimeout removed (no Spring equivalent; consider application-level timeout). @Remove methods retained but require manual cleanup: Spring does NOT auto-call @PreDestroy for prototype-scoped beans. Use ObjectProvider<T>.destroy(bean) or implement explicit disposal. Consider: For web sessions use @SessionScope; inject via ObjectProvider<T> for lazy/fresh instances.\", category = NeedsReview.Category.STATEFUL_BEAN, originalCode = \"@Stateful\", suggestedAction = \"Review scope: @SessionScope for web session affinity, @Scope(\\\"prototype\\\") + ObjectProvider<T> for per-call instances. For cleanup, call ObjectProvider.destroy(bean) or implement explicit disposal.\")\n" +
                "@Scope(\"prototype\")\n" +
                "@Service\n" +
                "public class FullLifecycleBean {\n" +
                "    \n" +  // Trailing whitespace from removed @PostActivate
                "    public void activate() {\n" +
                "    }\n" +
                "    \n" +  // Trailing whitespace from removed @PrePassivate
                "    public void passivate() {\n" +
                "    }\n" +
                "    \n" +  // Trailing whitespace from removed @Remove
                "    public void cleanup() {\n" +
                "    }\n" +
                "}\n"
            )
        );
    }

}
