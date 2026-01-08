package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link RemoveJBossSecurityDomain}.
 * <p>
 * JUS-001: RemoveJBossSecurityDomain (154 occurrences in JUS codebase)
 */
class RemoveJBossSecurityDomainTest implements RewriteTest {

    // Stub for JBoss SecurityDomain annotation (not on classpath)
    private static final String SECURITY_DOMAIN_STUB = """
        package org.jboss.ejb3.annotation;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface SecurityDomain {
            String value();
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

    // Stub for EJB Local annotation
    private static final String LOCAL_STUB = """
        package javax.ejb;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.METHOD})
        public @interface Local {
            Class<?>[] value() default {};
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveJBossSecurityDomain());
    }

    @DocumentExample
    @Test
    void removeSecurityDomainAnnotation() {
        rewriteRun(
            java(SECURITY_DOMAIN_STUB),
            java(
                """
                import org.jboss.ejb3.annotation.SecurityDomain;

                @SecurityDomain("java:/jaas/JusRealm")
                public class FooService {
                }
                """,
                """
                public class FooService {
                }
                """
            )
        );
    }

    @Test
    void removeSecurityDomainWithStateless() {
        rewriteRun(
            java(SECURITY_DOMAIN_STUB),
            java(STATELESS_STUB),
            java(
                """
                import org.jboss.ejb3.annotation.SecurityDomain;
                import javax.ejb.Stateless;

                @SecurityDomain("java:/jaas/JusRealm")
                @Stateless
                public class FooService {
                }
                """,
                """
                import javax.ejb.Stateless;


                @Stateless
                public class FooService {
                }
                """
            )
        );
    }

    @Test
    void removeSecurityDomainWithMultipleAnnotations() {
        rewriteRun(
            java(SECURITY_DOMAIN_STUB),
            java(STATELESS_STUB),
            java(LOCAL_STUB),
            java(
                """
                import org.jboss.ejb3.annotation.SecurityDomain;
                import javax.ejb.Stateless;
                import javax.ejb.Local;

                @SecurityDomain("java:/jaas/JusRealm")
                @Stateless
                @Local(CustomerService.class)
                public class CustomerServiceImpl implements CustomerService {
                    public void notify() {}
                }
                """,
                """
                import javax.ejb.Stateless;
                import javax.ejb.Local;


                @Stateless
                @Local(CustomerService.class)
                public class CustomerServiceImpl implements CustomerService {
                    public void notify() {}
                }
                """
            ),
            java(
                """
                public interface CustomerService {
                    void notify();
                }
                """
            )
        );
    }

    @Test
    void noChangeWithoutSecurityDomain() {
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

    @Test
    void removeImportWhenAnnotationRemoved() {
        rewriteRun(
            java(SECURITY_DOMAIN_STUB),
            java(STATELESS_STUB),
            java(
                """
                import org.jboss.ejb3.annotation.SecurityDomain;
                import javax.ejb.Stateless;

                @SecurityDomain("java:/jaas/AppRealm")
                @Stateless
                public class BarService {
                    public void process() {}
                }
                """,
                """
                import javax.ejb.Stateless;


                @Stateless
                public class BarService {
                    public void process() {}
                }
                """
            )
        );
    }
}
