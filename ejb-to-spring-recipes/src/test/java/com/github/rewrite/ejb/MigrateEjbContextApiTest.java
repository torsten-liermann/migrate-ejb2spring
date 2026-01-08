package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link MigrateEjbContextApi}.
 */
class MigrateEjbContextApiTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEjbContextApi(null))
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.ejb-api", "jakarta.annotation-api", "spring-tx", "spring-security-core"));
    }

    @Test
    @DocumentExample
    void transformsSetRollbackOnly() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.ejb.SessionContext;
                import jakarta.ejb.Stateless;

                @Stateless
                public class OrderService {

                    @Resource
                    private SessionContext ctx;

                    public void cancelOrder(long orderId) {
                        try {
                            // some operation
                        } catch (Exception e) {
                            ctx.setRollbackOnly();
                        }
                    }
                }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;
                import org.springframework.transaction.interceptor.TransactionAspectSupport;

                @Stateless
                public class OrderService {

                    public void cancelOrder(long orderId) {
                        try {
                            // some operation
                        } catch (Exception e) {
                            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsGetCallerPrincipal() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.ejb.SessionContext;
                import jakarta.ejb.Stateless;
                import java.security.Principal;

                @Stateless
                public class AuditService {

                    @Resource
                    private SessionContext ctx;

                    public String getCurrentUser() {
                        Principal principal = ctx.getCallerPrincipal();
                        return principal.getName();
                    }
                }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;
                import org.springframework.security.core.context.SecurityContextHolder;

                import java.security.Principal;

                @Stateless
                public class AuditService {

                    public String getCurrentUser() {
                        Principal principal = SecurityContextHolder.getContext().getAuthentication();
                        return principal.getName();
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsIsCallerInRoleForManualMigration() {
        // isCallerInRole requires manual migration to Spring Security
        // Field and imports are preserved when unmigrated APIs are present
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.ejb.SessionContext;
                import jakarta.ejb.Stateless;

                @Stateless
                public class SecureService {

                    @Resource
                    private SessionContext ctx;

                    public void adminAction() {
                        if (ctx.isCallerInRole("ADMIN")) {
                            // do admin stuff
                        }
                    }
                }
                """
                // No change - field and imports preserved for unmigrated API
            )
        );
    }

    @Test
    void migratesIsCallerInRoleWithSpringSecurityStrategy() {
        // With spring-security strategy enabled, isCallerInRole is migrated
        rewriteRun(
            spec -> spec.recipe(new MigrateEjbContextApi("spring-security")),
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.ejb.SessionContext;
                import jakarta.ejb.Stateless;

                @Stateless
                public class SecureService {

                    @Resource
                    private SessionContext ctx;

                    public boolean isAdmin() {
                        return ctx.isCallerInRole("ADMIN");
                    }
                }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;
                import org.springframework.security.core.context.SecurityContextHolder;

                @Stateless
                public class SecureService {

                    public boolean isAdmin() {
                        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesBothGetCallerPrincipalAndIsCallerInRoleWithStrategy() {
        // With spring-security strategy, both methods are migrated and field is removed
        rewriteRun(
            spec -> spec.recipe(new MigrateEjbContextApi("spring-security")),
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.ejb.SessionContext;
                import jakarta.ejb.Stateless;
                import java.security.Principal;

                @Stateless
                public class AuthService {

                    @Resource
                    private SessionContext ctx;

                    public String getCurrentUser() {
                        Principal p = ctx.getCallerPrincipal();
                        return p.getName();
                    }

                    public boolean hasRole(String role) {
                        return ctx.isCallerInRole(role);
                    }
                }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;
                import org.springframework.security.core.context.SecurityContextHolder;

                import java.security.Principal;

                @Stateless
                public class AuthService {

                    public String getCurrentUser() {
                        Principal p = SecurityContextHolder.getContext().getAuthentication();
                        return p.getName();
                    }

                    public boolean hasRole(String role) {
                        return SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
                    }
                }
                """
            )
        );
    }

    @Test
    void removesContextFieldEvenWithNoMethodCalls() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.ejb.SessionContext;
                import jakarta.ejb.Stateless;

                @Stateless
                public class SimpleService {

                    @Resource
                    private SessionContext ctx;

                    public void doSomething() {
                        System.out.println("Hello");
                    }
                }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class SimpleService {

                    public void doSomething() {
                        System.out.println("Hello");
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesFieldForUnsupportedContextMethods() {
        // Field must be preserved when unsupported methods like getTimerService are used
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.ejb.SessionContext;
                import jakarta.ejb.Stateless;
                import jakarta.ejb.TimerService;

                @Stateless
                public class TimerUserService {

                    @Resource
                    private SessionContext ctx;

                    public TimerService getTimers() {
                        return ctx.getTimerService();
                    }
                }
                """
                // No change - field and imports preserved for unsupported getTimerService()
            )
        );
    }

    @Test
    void noChangeWithoutSessionContext() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class RegularService {

                    public void doSomething() {
                        System.out.println("Regular");
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsJavaxSessionContext() {
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.ejb-api", "spring-tx", "spring-security-core")),
            java(
                """
                package com.example;

                import javax.ejb.SessionContext;
                import javax.ejb.Stateless;

                @Stateless
                public class LegacyService {

                    private SessionContext ctx;

                    public void rollback() {
                        ctx.setRollbackOnly();
                    }
                }
                """,
                """
                package com.example;

                import org.springframework.transaction.interceptor.TransactionAspectSupport;

                import javax.ejb.Stateless;

                @Stateless
                public class LegacyService {

                    public void rollback() {
                        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                    }
                }
                """
            )
        );
    }
}
