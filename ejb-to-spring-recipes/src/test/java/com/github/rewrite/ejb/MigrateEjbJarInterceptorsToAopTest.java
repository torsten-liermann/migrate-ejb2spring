package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.xml.Assertions.xml;

/**
 * Tests for {@link MigrateEjbJarInterceptorsToAop}.
 */
class MigrateEjbJarInterceptorsToAopTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEjbJarInterceptorsToAop())
            .parser(JavaParser.fromJavaVersion())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    @DocumentExample
    void generatesAopConfigForGlobalBinding() {
        rewriteRun(
            // Java source file to establish package
            java(
                """
                package com.example.ejb;

                public class MyService {
                    public void doSomething() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/ejb/MyService.java")
            ),
            // ejb-jar.xml with global interceptor binding
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ejb-jar xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0">
                    <interceptors>
                        <interceptor>
                            <interceptor-class>com.example.jboss.interceptor.performancelog.GenericPerformanceInterceptor</interceptor-class>
                            <around-invoke>
                                <method-name>intercept</method-name>
                            </around-invoke>
                        </interceptor>
                    </interceptors>
                    <assembly-descriptor>
                        <interceptor-binding>
                            <ejb-name>*</ejb-name>
                            <interceptor-class>com.example.jboss.interceptor.performancelog.GenericPerformanceInterceptor</interceptor-class>
                        </interceptor-binding>
                    </assembly-descriptor>
                </ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/ejb-jar.xml")
            ),
            // Expected generated AOP config
            java(
                null,
                """
                package com.example.ejb.config;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Around;
                import org.aspectj.lang.annotation.Aspect;
                import org.aspectj.lang.annotation.Pointcut;
                import org.springframework.stereotype.Component;

                @NeedsReview(
                    reason = "ejb-jar.xml interceptor-bindings require manual wiring",
                    category = NeedsReview.Category.MANUAL_MIGRATION,
                    originalCode = "Interceptor wiring not generated; manual binding required; Global binding (ejb-name=*) requires package-specific pointcut refinement",
                    suggestedAction = "1. Wire interceptor beans via constructor injection. 2. Adjust pointcuts to match actual bean classes. 3. Call interceptor's @AroundInvoke method in advice."
                )
                @Aspect
                @Component
                public class EjbJarAopConfig {

                    // Source: src/main/resources/META-INF/ejb-jar.xml
                    // ejb-name: *
                    // interceptor-class: com.example.jboss.interceptor.performancelog.GenericPerformanceInterceptor
                    @Pointcut("within(__YOUR_EJB_PACKAGE__..*) && execution(* *(..))")
                    // PLACEHOLDER: Replace '__YOUR_EJB_PACKAGE__' with your actual EJB package (e.g., 'com.mycompany.ejb')
                    public void globalInterceptorPointcut() {}

                    // TODO: Wire interceptor bean(s) via constructor injection
                    // private final GenericPerformanceInterceptor genericPerformanceInterceptor;

                    @Around("globalInterceptorPointcut()")
                    public Object applyGlobalInterceptor(ProceedingJoinPoint pjp) throws Throwable {
                        // SKELETON: Interceptors are NOT invoked - this is a placeholder only!
                        // TODO: Wire interceptor beans and invoke their @AroundInvoke method
                        // The method name varies per interceptor class (find the method annotated with @AroundInvoke)
                        // Example: return genericPerformanceInterceptor.<aroundInvokeMethod>(pjp);
                        return pjp.proceed(); // PLACEHOLDER: Replace with actual interceptor chain
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/ejb/config/EjbJarAopConfig.java")
            )
        );
    }

    @Test
    void generatesAopConfigForSpecificEjbBinding() {
        rewriteRun(
            // Java source file to establish package
            java(
                """
                package com.example.service;

                public class UserService {
                    public void createUser() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/service/UserService.java")
            ),
            // ejb-jar.xml with specific EJB binding
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ejb-jar xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0">
                    <assembly-descriptor>
                        <interceptor-binding>
                            <ejb-name>UserService</ejb-name>
                            <interceptor-class>com.example.interceptor.AuditInterceptor</interceptor-class>
                        </interceptor-binding>
                    </assembly-descriptor>
                </ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/ejb-jar.xml")
            ),
            // Expected generated AOP config
            java(
                null,
                """
                package com.example.service.config;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Around;
                import org.aspectj.lang.annotation.Aspect;
                import org.aspectj.lang.annotation.Pointcut;
                import org.springframework.stereotype.Component;

                @NeedsReview(
                    reason = "ejb-jar.xml interceptor-bindings require manual wiring",
                    category = NeedsReview.Category.MANUAL_MIGRATION,
                    originalCode = "Interceptor wiring not generated; manual binding required; ejb-name may not match class name; verify pointcut targets",
                    suggestedAction = "1. Wire interceptor beans via constructor injection. 2. Adjust pointcuts to match actual bean classes. 3. Call interceptor's @AroundInvoke method in advice."
                )
                @Aspect
                @Component
                public class EjbJarAopConfig {

                    // Source: src/main/resources/META-INF/ejb-jar.xml
                    // ejb-name: UserService
                    // interceptor-class: com.example.interceptor.AuditInterceptor
                    @Pointcut("within(__VERIFY_PACKAGE__..UserService) && execution(* *(..))")
                    // PLACEHOLDER: This pointcut is likely INCORRECT. ejb-name 'UserService' may not match the actual class name. Replace '__VERIFY_PACKAGE__' and verify the class.
                    public void ejbInterceptorPointcut1() {}

                    // TODO: Wire interceptor bean(s) via constructor injection
                    // private final AuditInterceptor auditInterceptor;

                    @Around("ejbInterceptorPointcut1()")
                    public Object applyEjbInterceptor1(ProceedingJoinPoint pjp) throws Throwable {
                        // SKELETON: Interceptors are NOT invoked - this is a placeholder only!
                        // TODO: Wire interceptor beans and invoke their @AroundInvoke method
                        // The method name varies per interceptor class (find the method annotated with @AroundInvoke)
                        // Example: return auditInterceptor.<aroundInvokeMethod>(pjp);
                        return pjp.proceed(); // PLACEHOLDER: Replace with actual interceptor chain
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/service/config/EjbJarAopConfig.java")
            )
        );
    }

    @Test
    void generatesAopConfigForMultipleInterceptors() {
        rewriteRun(
            // Java source file to establish package
            java(
                """
                package com.example.app;

                public class OrderService {
                    public void processOrder() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/app/OrderService.java")
            ),
            // ejb-jar.xml with multiple interceptors
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ejb-jar xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0">
                    <assembly-descriptor>
                        <interceptor-binding>
                            <ejb-name>*</ejb-name>
                            <interceptor-class>com.example.interceptor.LoggingInterceptor</interceptor-class>
                            <interceptor-class>com.example.interceptor.SecurityInterceptor</interceptor-class>
                        </interceptor-binding>
                    </assembly-descriptor>
                </ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/ejb-jar.xml")
            ),
            // Expected generated AOP config
            java(
                null,
                """
                package com.example.app.config;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Around;
                import org.aspectj.lang.annotation.Aspect;
                import org.aspectj.lang.annotation.Pointcut;
                import org.springframework.stereotype.Component;

                @NeedsReview(
                    reason = "ejb-jar.xml interceptor-bindings require manual wiring",
                    category = NeedsReview.Category.MANUAL_MIGRATION,
                    originalCode = "Interceptor wiring not generated; manual binding required; Global binding (ejb-name=*) requires package-specific pointcut refinement",
                    suggestedAction = "1. Wire interceptor beans via constructor injection. 2. Adjust pointcuts to match actual bean classes. 3. Call interceptor's @AroundInvoke method in advice. 4. Configure @Order on @Aspect classes to preserve interceptor execution sequence."
                )
                @Aspect
                @Component
                public class EjbJarAopConfig {

                    // Source: src/main/resources/META-INF/ejb-jar.xml
                    // ejb-name: *
                    // interceptor-class: com.example.interceptor.LoggingInterceptor
                    // interceptor-class: com.example.interceptor.SecurityInterceptor
                    @Pointcut("within(__YOUR_EJB_PACKAGE__..*) && execution(* *(..))")
                    // PLACEHOLDER: Replace '__YOUR_EJB_PACKAGE__' with your actual EJB package (e.g., 'com.mycompany.ejb')
                    public void globalInterceptorPointcut() {}

                    // TODO: Wire interceptor bean(s) via constructor injection
                    // private final LoggingInterceptor loggingInterceptor;
                    // private final SecurityInterceptor securityInterceptor;

                    @Around("globalInterceptorPointcut()")
                    public Object applyGlobalInterceptor(ProceedingJoinPoint pjp) throws Throwable {
                        // SKELETON: Interceptors are NOT invoked - this is a placeholder only!
                        // TODO: Wire interceptor beans and invoke their @AroundInvoke method
                        // The method name varies per interceptor class (find the method annotated with @AroundInvoke)
                        // Example: return loggingInterceptor.<aroundInvokeMethod>(pjp);
                        // Example: return securityInterceptor.<aroundInvokeMethod>(pjp);
                        return pjp.proceed(); // PLACEHOLDER: Replace with actual interceptor chain
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/app/config/EjbJarAopConfig.java")
            )
        );
    }

    @Test
    void marksComplexBindingWithMethodLevel() {
        rewriteRun(
            // Java source file to establish package
            java(
                """
                package com.example.complex;

                public class PaymentService {
                    public void processPayment() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/complex/PaymentService.java")
            ),
            // ejb-jar.xml with method-level binding (complex)
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ejb-jar xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0">
                    <assembly-descriptor>
                        <interceptor-binding>
                            <ejb-name>PaymentService</ejb-name>
                            <interceptor-class>com.example.interceptor.TransactionInterceptor</interceptor-class>
                            <method>
                                <method-name>processPayment</method-name>
                            </method>
                        </interceptor-binding>
                    </assembly-descriptor>
                </ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/ejb-jar.xml")
            ),
            // Expected generated AOP config with @NeedsReview for complex binding
            java(
                null,
                """
                package com.example.complex.config;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Around;
                import org.aspectj.lang.annotation.Aspect;
                import org.aspectj.lang.annotation.Pointcut;
                import org.springframework.stereotype.Component;

                @NeedsReview(
                    reason = "ejb-jar.xml interceptor-bindings require manual wiring",
                    category = NeedsReview.Category.MANUAL_MIGRATION,
                    originalCode = "Interceptor wiring not generated; manual binding required; ejb-name may not match class name; verify pointcut targets; ejb-name=PaymentService, method-binding",
                    suggestedAction = "1. Wire interceptor beans via constructor injection. 2. Adjust pointcuts to match actual bean classes. 3. Call interceptor's @AroundInvoke method in advice."
                )
                @Aspect
                @Component
                public class EjbJarAopConfig {

                    // Source: src/main/resources/META-INF/ejb-jar.xml
                    // ejb-name: PaymentService
                    // interceptor-class: com.example.interceptor.TransactionInterceptor
                    // WARNING: Complex binding detected - requires manual review
                    // - Contains method-level binding
                    @Pointcut("within(__VERIFY_PACKAGE__..PaymentService) && execution(* *(..))")
                    // PLACEHOLDER: This pointcut is likely INCORRECT. ejb-name 'PaymentService' may not match the actual class name. Replace '__VERIFY_PACKAGE__' and verify the class.
                    public void ejbInterceptorPointcut1() {}

                    // TODO: Wire interceptor bean(s) via constructor injection
                    // private final TransactionInterceptor transactionInterceptor;

                    @Around("ejbInterceptorPointcut1()")
                    public Object applyEjbInterceptor1(ProceedingJoinPoint pjp) throws Throwable {
                        // SKELETON: Interceptors are NOT invoked - this is a placeholder only!
                        // TODO: Wire interceptor beans and invoke their @AroundInvoke method
                        // The method name varies per interceptor class (find the method annotated with @AroundInvoke)
                        // Example: return transactionInterceptor.<aroundInvokeMethod>(pjp);
                        return pjp.proceed(); // PLACEHOLDER: Replace with actual interceptor chain
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/complex/config/EjbJarAopConfig.java")
            )
        );
    }

    @Test
    void marksComplexBindingWithExcludeClassInterceptors() {
        rewriteRun(
            // Java source file to establish package
            java(
                """
                package com.example.excluded;

                public class SpecialService {
                    public void specialMethod() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/excluded/SpecialService.java")
            ),
            // ejb-jar.xml with exclude-class-interceptors
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ejb-jar xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0">
                    <assembly-descriptor>
                        <interceptor-binding>
                            <ejb-name>SpecialService</ejb-name>
                            <exclude-class-interceptors>true</exclude-class-interceptors>
                            <interceptor-class>com.example.interceptor.CustomInterceptor</interceptor-class>
                        </interceptor-binding>
                    </assembly-descriptor>
                </ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/ejb-jar.xml")
            ),
            // Expected generated AOP config with @NeedsReview for complex binding
            java(
                null,
                """
                package com.example.excluded.config;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Around;
                import org.aspectj.lang.annotation.Aspect;
                import org.aspectj.lang.annotation.Pointcut;
                import org.springframework.stereotype.Component;

                @NeedsReview(
                    reason = "ejb-jar.xml interceptor-bindings require manual wiring",
                    category = NeedsReview.Category.MANUAL_MIGRATION,
                    originalCode = "Interceptor wiring not generated; manual binding required; ejb-name may not match class name; verify pointcut targets; ejb-name=SpecialService, exclude-class-interceptors",
                    suggestedAction = "1. Wire interceptor beans via constructor injection. 2. Adjust pointcuts to match actual bean classes. 3. Call interceptor's @AroundInvoke method in advice."
                )
                @Aspect
                @Component
                public class EjbJarAopConfig {

                    // Source: src/main/resources/META-INF/ejb-jar.xml
                    // ejb-name: SpecialService
                    // interceptor-class: com.example.interceptor.CustomInterceptor
                    // WARNING: Complex binding detected - requires manual review
                    // - Contains exclude-class-interceptors=true
                    @Pointcut("within(__VERIFY_PACKAGE__..SpecialService) && execution(* *(..))")
                    // PLACEHOLDER: This pointcut is likely INCORRECT. ejb-name 'SpecialService' may not match the actual class name. Replace '__VERIFY_PACKAGE__' and verify the class.
                    public void ejbInterceptorPointcut1() {}

                    // TODO: Wire interceptor bean(s) via constructor injection
                    // private final CustomInterceptor customInterceptor;

                    @Around("ejbInterceptorPointcut1()")
                    public Object applyEjbInterceptor1(ProceedingJoinPoint pjp) throws Throwable {
                        // SKELETON: Interceptors are NOT invoked - this is a placeholder only!
                        // TODO: Wire interceptor beans and invoke their @AroundInvoke method
                        // The method name varies per interceptor class (find the method annotated with @AroundInvoke)
                        // Example: return customInterceptor.<aroundInvokeMethod>(pjp);
                        return pjp.proceed(); // PLACEHOLDER: Replace with actual interceptor chain
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/excluded/config/EjbJarAopConfig.java")
            )
        );
    }

    @Test
    void handlesInterceptorOrder() {
        rewriteRun(
            // Java source file to establish package
            java(
                """
                package com.example.ordered;

                public class OrderedService {
                    public void orderedMethod() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/ordered/OrderedService.java")
            ),
            // ejb-jar.xml with interceptor-order
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ejb-jar xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0">
                    <assembly-descriptor>
                        <interceptor-binding>
                            <ejb-name>*</ejb-name>
                            <interceptor-order>
                                <interceptor-class>com.example.interceptor.FirstInterceptor</interceptor-class>
                                <interceptor-class>com.example.interceptor.SecondInterceptor</interceptor-class>
                            </interceptor-order>
                        </interceptor-binding>
                    </assembly-descriptor>
                </ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/ejb-jar.xml")
            ),
            // Expected generated AOP config
            java(
                null,
                """
                package com.example.ordered.config;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Around;
                import org.aspectj.lang.annotation.Aspect;
                import org.aspectj.lang.annotation.Pointcut;
                import org.springframework.stereotype.Component;

                @NeedsReview(
                    reason = "ejb-jar.xml interceptor-bindings require manual wiring",
                    category = NeedsReview.Category.MANUAL_MIGRATION,
                    originalCode = "Interceptor wiring not generated; manual binding required; Global binding (ejb-name=*) requires package-specific pointcut refinement",
                    suggestedAction = "1. Wire interceptor beans via constructor injection. 2. Adjust pointcuts to match actual bean classes. 3. Call interceptor's @AroundInvoke method in advice. 4. Configure @Order on @Aspect classes to preserve interceptor execution sequence."
                )
                @Aspect
                @Component
                public class EjbJarAopConfig {

                    // Source: src/main/resources/META-INF/ejb-jar.xml
                    // ejb-name: *
                    // interceptor-class: com.example.interceptor.FirstInterceptor
                    // interceptor-class: com.example.interceptor.SecondInterceptor
                    @Pointcut("within(__YOUR_EJB_PACKAGE__..*) && execution(* *(..))")
                    // PLACEHOLDER: Replace '__YOUR_EJB_PACKAGE__' with your actual EJB package (e.g., 'com.mycompany.ejb')
                    public void globalInterceptorPointcut() {}

                    // TODO: Wire interceptor bean(s) via constructor injection
                    // private final FirstInterceptor firstInterceptor;
                    // private final SecondInterceptor secondInterceptor;

                    @Around("globalInterceptorPointcut()")
                    public Object applyGlobalInterceptor(ProceedingJoinPoint pjp) throws Throwable {
                        // SKELETON: Interceptors are NOT invoked - this is a placeholder only!
                        // TODO: Wire interceptor beans and invoke their @AroundInvoke method
                        // The method name varies per interceptor class (find the method annotated with @AroundInvoke)
                        // Example: return firstInterceptor.<aroundInvokeMethod>(pjp);
                        // Example: return secondInterceptor.<aroundInvokeMethod>(pjp);
                        return pjp.proceed(); // PLACEHOLDER: Replace with actual interceptor chain
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/ordered/config/EjbJarAopConfig.java")
            )
        );
    }

    @Test
    void noChangeWithoutEjbJarXml() {
        rewriteRun(
            // Java source file only, no ejb-jar.xml
            java(
                """
                package com.example;

                public class RegularService {
                    public void doSomething() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/RegularService.java")
            )
        );
    }

    @Test
    void noChangeWithEmptyAssemblyDescriptor() {
        rewriteRun(
            // Java source file
            java(
                """
                package com.example;

                public class EmptyService {
                    public void doSomething() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/EmptyService.java")
            ),
            // ejb-jar.xml with empty assembly-descriptor
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ejb-jar xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0">
                    <assembly-descriptor>
                    </assembly-descriptor>
                </ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/ejb-jar.xml")
            )
        );
    }

    @Test
    void noChangeWithoutInterceptorBindings() {
        rewriteRun(
            // Java source file
            java(
                """
                package com.example;

                public class NoBindingsService {
                    public void doSomething() {}
                }
                """,
                spec -> spec.path("src/main/java/com/example/NoBindingsService.java")
            ),
            // ejb-jar.xml with interceptors but no bindings
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <ejb-jar xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0">
                    <interceptors>
                        <interceptor>
                            <interceptor-class>com.example.interceptor.UnusedInterceptor</interceptor-class>
                        </interceptor>
                    </interceptors>
                </ejb-jar>
                """,
                spec -> spec.path("src/main/resources/META-INF/ejb-jar.xml")
            )
        );
    }
}
