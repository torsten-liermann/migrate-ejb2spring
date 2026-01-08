package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link MigrateEjbInterceptorsToAop}.
 */
class MigrateEjbInterceptorsToAopTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEjbInterceptorsToAop())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context", "aspectjrt", "migration-annotations"));
    }

    @Test
    @DocumentExample
    void transformsInterceptorClassToAspect() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.interceptor.AroundInvoke;
                import jakarta.interceptor.InvocationContext;
                import jakarta.interceptor.Interceptor;

                @Interceptor
                public class AuditInterceptor {

                    @AroundInvoke
                    public Object audit(InvocationContext ctx) throws Exception {
                        System.out.println("Before");
                        Object result = ctx.proceed();
                        System.out.println("After");
                        return result;
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.stereotype.Component;




                @Aspect
                @Component
                @NeedsReview(reason ="AOP pointcut requires manual refinement",
                        category =NeedsReview.Category.MANUAL_MIGRATION,
                        originalCode ="Original EJB @Interceptors binding",
                        suggestedAction ="Add @Around with target pointcut")
                public class AuditInterceptor {

                   \s
                    public Object audit(ProceedingJoinPoint ctx) throws Throwable {
                        System.out.println("Before");
                        Object result = ctx.proceed();
                        System.out.println("After");
                        return result;
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsInterceptorWithoutInterceptorAnnotation() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.interceptor.AroundInvoke;
                import jakarta.interceptor.InvocationContext;

                public class LoggingInterceptor {

                    @AroundInvoke
                    public Object log(InvocationContext context) throws Exception {
                        return context.proceed();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.stereotype.Component;




                @Aspect
                @Component
                @NeedsReview(reason ="AOP pointcut requires manual refinement",
                        category =NeedsReview.Category.MANUAL_MIGRATION,
                        originalCode ="Original EJB @Interceptors binding",
                        suggestedAction ="Add @Around with target pointcut")
                public class LoggingInterceptor {

                   \s
                    public Object log(ProceedingJoinPoint context) throws Throwable {
                        return context.proceed();
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsJavaxInterceptor() {
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.interceptor-api", "spring-context", "aspectjrt", "migration-annotations")),
            java(
                """
                package com.example;

                import javax.interceptor.AroundInvoke;
                import javax.interceptor.InvocationContext;
                import javax.interceptor.Interceptor;

                @Interceptor
                public class LegacyInterceptor {

                    @AroundInvoke
                    public Object intercept(InvocationContext ic) throws Exception {
                        return ic.proceed();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.stereotype.Component;




                @Aspect
                @Component
                @NeedsReview(reason ="AOP pointcut requires manual refinement",
                        category =NeedsReview.Category.MANUAL_MIGRATION,
                        originalCode ="Original EJB @Interceptors binding",
                        suggestedAction ="Add @Around with target pointcut")
                public class LegacyInterceptor {

                   \s
                    public Object intercept(ProceedingJoinPoint ic) throws Throwable {
                        return ic.proceed();
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsGetParametersToGetArgs() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.interceptor.AroundInvoke;
                import jakarta.interceptor.InvocationContext;

                public class ParamInterceptor {

                    @AroundInvoke
                    public Object intercept(InvocationContext ctx) throws Exception {
                        Object[] params = ctx.getParameters();
                        System.out.println("Params: " + params.length);
                        return ctx.proceed();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.stereotype.Component;




                @Aspect
                @Component
                @NeedsReview(reason ="AOP pointcut requires manual refinement",
                        category =NeedsReview.Category.MANUAL_MIGRATION,
                        originalCode ="Original EJB @Interceptors binding",
                        suggestedAction ="Add @Around with target pointcut")
                public class ParamInterceptor {

                   \s
                    public Object intercept(ProceedingJoinPoint ctx) throws Throwable {
                        Object[] params = ctx.getArgs();
                        System.out.println("Params: " + params.length);
                        return ctx.proceed();
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesExistingAspectAnnotation() {
        // When a class already has @Aspect, transform @AroundInvoke methods
        // and add @Component if missing (per Codex review fix)
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.interceptor.AroundInvoke;
                import jakarta.interceptor.InvocationContext;
                import org.aspectj.lang.annotation.Aspect;

                @Aspect
                public class AlreadyAspect {

                    @AroundInvoke
                    public Object intercept(InvocationContext ctx) throws Exception {
                        return ctx.proceed();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.stereotype.Component;

                @Aspect
                @Component
                @NeedsReview(reason ="AOP pointcut requires manual refinement",
                        category =NeedsReview.Category.MANUAL_MIGRATION,
                        originalCode ="Original EJB @Interceptors binding",
                        suggestedAction ="Add @Around with target pointcut")
                public class AlreadyAspect {

                   \s
                    public Object intercept(ProceedingJoinPoint ctx) throws Throwable {
                        return ctx.proceed();
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeForNonInterceptorClass() {
        rewriteRun(
            java(
                """
                package com.example;

                public class RegularService {

                    public void doSomething() {
                        System.out.println("Regular service");
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsMultipleAroundInvokeMethods() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.interceptor.AroundInvoke;
                import jakarta.interceptor.InvocationContext;
                import jakarta.interceptor.Interceptor;

                @Interceptor
                public class MultiMethodInterceptor {

                    @AroundInvoke
                    public Object first(InvocationContext ctx) throws Exception {
                        return ctx.proceed();
                    }

                    public void helper() {
                        // Not an interceptor method
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.stereotype.Component;




                @Aspect
                @Component
                @NeedsReview(reason ="AOP pointcut requires manual refinement",
                        category =NeedsReview.Category.MANUAL_MIGRATION,
                        originalCode ="Original EJB @Interceptors binding",
                        suggestedAction ="Add @Around with target pointcut")
                public class MultiMethodInterceptor {

                   \s
                    public Object first(ProceedingJoinPoint ctx) throws Throwable {
                        return ctx.proceed();
                    }

                    public void helper() {
                        // Not an interceptor method
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesOtherAnnotations() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.interceptor.AroundInvoke;
                import jakarta.interceptor.InvocationContext;
                import jakarta.interceptor.Interceptor;

                @Interceptor
                @SuppressWarnings("unused")
                public class AnnotatedInterceptor {

                    @AroundInvoke
                    @Deprecated
                    public Object intercept(InvocationContext ctx) throws Exception {
                        return ctx.proceed();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.stereotype.Component;




                @Aspect
                @Component
                @NeedsReview(reason ="AOP pointcut requires manual refinement",
                        category =NeedsReview.Category.MANUAL_MIGRATION,
                        originalCode ="Original EJB @Interceptors binding",
                        suggestedAction ="Add @Around with target pointcut")
                @SuppressWarnings("unused")
                public class AnnotatedInterceptor {

                   \s
                    @Deprecated
                    public Object intercept(ProceedingJoinPoint ctx) throws Throwable {
                        return ctx.proceed();
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsInvocationContextWhenUsingUnsupportedApis() {
        // When using getMethod(), setParameters(), or getContextData(),
        // we keep InvocationContext to avoid compile errors (HIGH issue fix)
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.interceptor.AroundInvoke;
                import jakarta.interceptor.InvocationContext;
                import jakarta.interceptor.Interceptor;

                @Interceptor
                public class AdvancedInterceptor {

                    @AroundInvoke
                    public Object intercept(InvocationContext ctx) throws Exception {
                        // Uses getMethod() which has no direct ProceedingJoinPoint equivalent
                        System.out.println("Method: " + ctx.getMethod().getName());
                        return ctx.proceed();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.interceptor.InvocationContext;
                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.stereotype.Component;




                @Aspect
                @Component
                @NeedsReview(reason ="AOP pointcut requires manual refinement",
                        category =NeedsReview.Category.MANUAL_MIGRATION,
                        originalCode ="Original EJB @Interceptors binding",
                        suggestedAction ="Add @Around with target pointcut")
                public class AdvancedInterceptor {

                   \s
                    public Object intercept(InvocationContext ctx) throws Throwable {
                        // Uses getMethod() which has no direct ProceedingJoinPoint equivalent
                        System.out.println("Method: " + ctx.getMethod().getName());
                        return ctx.proceed();
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesExistingComponentAnnotation() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.interceptor.AroundInvoke;
                import jakarta.interceptor.InvocationContext;
                import org.springframework.stereotype.Component;

                @Component
                public class ComponentInterceptor {

                    @AroundInvoke
                    public Object intercept(InvocationContext ctx) throws Exception {
                        return ctx.proceed();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.aspectj.lang.ProceedingJoinPoint;
                import org.aspectj.lang.annotation.Aspect;
                import org.springframework.stereotype.Component;




                @Aspect
                @NeedsReview(reason ="AOP pointcut requires manual refinement",
                        category =NeedsReview.Category.MANUAL_MIGRATION,
                        originalCode ="Original EJB @Interceptors binding",
                        suggestedAction ="Add @Around with target pointcut")
                @Component
                public class ComponentInterceptor {

                   \s
                    public Object intercept(ProceedingJoinPoint ctx) throws Throwable {
                        return ctx.proceed();
                    }
                }
                """
            )
        );
    }

}
