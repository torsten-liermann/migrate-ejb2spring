package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class ConvertJBossTransactionTimeoutTest implements RewriteTest {

    private static final String JBOSS_TIMEOUT_STUB = """
        package org.jboss.ejb3.annotation;

        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;
        import java.util.concurrent.TimeUnit;

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.METHOD})
        public @interface TransactionTimeout {
            int value() default 0;
            TimeUnit unit() default TimeUnit.SECONDS;
        }
        """;
    private static final String TRANSACTIONAL_STUB = """
        package org.springframework.transaction.annotation;

        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.METHOD})
        public @interface Transactional {
            int timeout() default -1;
            boolean readOnly() default false;
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ConvertJBossTransactionTimeout())
            .parser(JavaParser.fromJavaVersion());
    }

    @Test
    void convertsMinutesToSeconds() {
        rewriteRun(
            java(JBOSS_TIMEOUT_STUB),
            java(TRANSACTIONAL_STUB),
            java(
                """
                import java.util.concurrent.TimeUnit;
                import org.jboss.ejb3.annotation.TransactionTimeout;

                public class Example {
                    @TransactionTimeout(value = 30, unit = TimeUnit.MINUTES)
                    public void runJob() {
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                public class Example {
                    @Transactional(timeout = 1800)
                    public void runJob() {
                    }
                }
                """
            )
        );
    }

    @Test
    void convertsArithmeticExpressionWithDefaultUnit() {
        rewriteRun(
            java(JBOSS_TIMEOUT_STUB),
            java(TRANSACTIONAL_STUB),
            java(
                """
                import org.jboss.ejb3.annotation.TransactionTimeout;

                public class Example {
                    @TransactionTimeout(10 * 60 * 2)
                    public void runJob() {
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                public class Example {
                    @Transactional(timeout = 1200)
                    public void runJob() {
                    }
                }
                """
            )
        );
    }

    @Test
    void addsTimeoutToExistingTransactional() {
        rewriteRun(
            java(JBOSS_TIMEOUT_STUB),
            java(TRANSACTIONAL_STUB),
            java(
                """
                import java.util.concurrent.TimeUnit;
                import org.jboss.ejb3.annotation.TransactionTimeout;
                import org.springframework.transaction.annotation.Transactional;

                public class Example {
                    @Transactional(readOnly = true)
                    @TransactionTimeout(value = 10, unit = TimeUnit.MINUTES)
                    public void runJob() {
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                public class Example {
                    @Transactional(readOnly = true, timeout = 600)
                    public void runJob() {
                    }
                }
                """
            )
        );
    }
}
