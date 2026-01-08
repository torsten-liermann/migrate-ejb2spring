package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for MigrateInjectToAutowired recipe.
 * <p>
 * Note: Migration only occurs when strategy is "migrate-to-spring" (default is "keep-jsr330").
 * Tests use strategy override to test migration behavior.
 */
class MigrateInjectToAutowiredTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // Use migrate-to-spring strategy to enable migration (default is keep-jsr330)
        spec.recipe(new MigrateInjectToAutowired("migrate-to-spring"))
            .parser(JavaParser.fromJavaVersion()
                .classpath("javax.inject", "spring-beans"));
    }

    @DocumentExample
    @Test
    void migrateInjectOnField() {
        rewriteRun(
            java(
                """
                import javax.inject.Inject;

                public class OrderService {
                    @Inject
                    private CustomerService customerService;
                }
                """,
                """
                import org.springframework.beans.factory.annotation.Autowired;

                public class OrderService {
                    @Autowired
                    private CustomerService customerService;
                }
                """
            ),
            java(
                """
                public class CustomerService {}
                """
            )
        );
    }

    @Test
    void migrateInjectOnConstructor() {
        rewriteRun(
            java(
                """
                import javax.inject.Inject;

                public class OrderService {
                    private final CustomerService customerService;

                    @Inject
                    public OrderService(CustomerService customerService) {
                        this.customerService = customerService;
                    }
                }
                """,
                """
                import org.springframework.beans.factory.annotation.Autowired;

                public class OrderService {
                    private final CustomerService customerService;

                    @Autowired
                    public OrderService(CustomerService customerService) {
                        this.customerService = customerService;
                    }
                }
                """
            ),
            java(
                """
                public class CustomerService {}
                """
            )
        );
    }

    @Test
    void migrateInjectOnMethod() {
        rewriteRun(
            java(
                """
                import javax.inject.Inject;

                public class OrderService {
                    private CustomerService customerService;

                    @Inject
                    public void setCustomerService(CustomerService customerService) {
                        this.customerService = customerService;
                    }
                }
                """,
                """
                import org.springframework.beans.factory.annotation.Autowired;

                public class OrderService {
                    private CustomerService customerService;

                    @Autowired
                    public void setCustomerService(CustomerService customerService) {
                        this.customerService = customerService;
                    }
                }
                """
            ),
            java(
                """
                public class CustomerService {}
                """
            )
        );
    }

    @Test
    void noMigrationWhenStrategyIsKeepJsr330() {
        // Test that no migration happens when strategy is keep-jsr330
        rewriteRun(
            spec -> spec.recipe(new MigrateInjectToAutowired("keep-jsr330")),
            java(
                """
                import javax.inject.Inject;

                public class OrderService {
                    @Inject
                    private CustomerService customerService;
                }
                """
                // No expected change - @Inject should be preserved
            ),
            java(
                """
                public class CustomerService {}
                """
            )
        );
    }

    @Test
    void noMigrationWithDefaultStrategy() {
        // Test that default strategy (no explicit strategy) doesn't migrate
        // because default is KEEP_JSR330
        rewriteRun(
            spec -> spec.recipe(new MigrateInjectToAutowired()),
            java(
                """
                import javax.inject.Inject;

                public class AnotherService {
                    @Inject
                    private CustomerService customerService;
                }
                """
                // No expected change - default is keep-jsr330
            ),
            java(
                """
                public class CustomerService {}
                """
            )
        );
    }
}
