package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateEjbToAutowiredTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEjbToAutowired(null))
            .parser(JavaParser.fromJavaVersion()
                .classpath("javax.ejb-api", "jakarta.inject-api", "spring-beans"));
    }

    @DocumentExample
    @Test
    void migrateToInjectWithDefaultStrategy() {
        // Default strategy is keep-jsr330, so @EJB becomes @Inject
        rewriteRun(
            java(
                """
                import javax.ejb.EJB;

                public class OrderService {
                    @EJB
                    private CustomerService customerService;

                    public void process() {
                        customerService.notify();
                    }
                }
                """,
                """
                import jakarta.inject.Inject;

                public class OrderService {
                    @Inject
                    private CustomerService customerService;

                    public void process() {
                        customerService.notify();
                    }
                }
                """
            ),
            java(
                """
                public class CustomerService {
                    public void notify() {}
                }
                """
            )
        );
    }

    @Test
    void migrateToAutowiredWithMigrateToSpringStrategy() {
        // With migrate-to-spring strategy, @EJB becomes @Autowired
        rewriteRun(
            spec -> spec.recipe(new MigrateEjbToAutowired("migrate-to-spring")),
            java(
                """
                import javax.ejb.EJB;

                public class OrderService {
                    @EJB
                    private CustomerService customerService;

                    public void process() {
                        customerService.notify();
                    }
                }
                """,
                """
                import org.springframework.beans.factory.annotation.Autowired;

                public class OrderService {
                    @Autowired
                    private CustomerService customerService;

                    public void process() {
                        customerService.notify();
                    }
                }
                """
            ),
            java(
                """
                public class CustomerService {
                    public void notify() {}
                }
                """
            )
        );
    }

    @Test
    void migrateEjbWithBeanNameToAutowiredAndQualifier() {
        // beanName attribute is converted to @Qualifier for Spring
        rewriteRun(
            spec -> spec.recipe(new MigrateEjbToAutowired("migrate-to-spring")),
            java(
                """
                import javax.ejb.EJB;

                public class OrderService {
                    @EJB(beanName = "primaryCustomer")
                    private CustomerService customerService;
                }
                """,
                """
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.beans.factory.annotation.Qualifier;

                public class OrderService {
                    @Autowired
                    @Qualifier("primaryCustomer")
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
    void migrateEjbWithBeanNameToInjectAndNamed() {
        // beanName attribute is converted to @Named for JSR-330 (default strategy)
        rewriteRun(
            java(
                """
                import javax.ejb.EJB;

                public class OrderService {
                    @EJB(beanName = "primaryCustomer")
                    private CustomerService customerService;
                }
                """,
                """
                import jakarta.inject.Inject;
                import jakarta.inject.Named;

                public class OrderService {
                    @Inject
                    @Named("primaryCustomer")
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
    void migrateEjbWithLookupAddsNeedsReview() {
        // lookup attribute triggers @NeedsReview since JNDI lookups need manual migration
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.ejb-api", "jakarta.inject-api", "spring-beans")
                .dependsOn("""
                    package com.github.rewrite.ejb.annotations;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                    public @interface NeedsReview {
                        Category category();
                        String reason() default "";
                        String originalCode() default "";
                        String suggestedAction() default "";
                        enum Category { MANUAL_MIGRATION }
                    }
                    """)),
            java(
                """
                import javax.ejb.EJB;

                public class OrderService {
                    @EJB(lookup = "java:global/app/CustomerService")
                    private CustomerService customerService;
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.inject.Inject;

                public class OrderService {
                    @Inject
                    @NeedsReview(reason= "JNDI/reference metadata not supported in Spring",category= NeedsReview.Category.MANUAL_MIGRATION,originalCode= "@EJB(lookup = \\"java:global/app/CustomerService\\")",suggestedAction= "Configure Spring bean manually or use @Value/@Qualifier")
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
    void migrateSetterInjection() {
        // @EJB on setter method is also migrated
        rewriteRun(
            java(
                """
                import javax.ejb.EJB;

                public class OrderService {
                    private CustomerService customerService;

                    @EJB
                    public void setCustomerService(CustomerService customerService) {
                        this.customerService = customerService;
                    }
                }
                """,
                """
                import jakarta.inject.Inject;

                public class OrderService {
                    private CustomerService customerService;

                    @Inject
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
    void migrateSetterWithBeanNameToAutowiredAndQualifier() {
        // @EJB(beanName=...) on setter method becomes @Autowired + @Qualifier
        rewriteRun(
            spec -> spec.recipe(new MigrateEjbToAutowired("migrate-to-spring")),
            java(
                """
                import javax.ejb.EJB;

                public class OrderService {
                    private CustomerService customerService;

                    @EJB(beanName = "primaryCustomer")
                    public void setCustomerService(CustomerService customerService) {
                        this.customerService = customerService;
                    }
                }
                """,
                """
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.beans.factory.annotation.Qualifier;

                public class OrderService {
                    private CustomerService customerService;

                    @Autowired
                    @Qualifier("primaryCustomer")
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
}
