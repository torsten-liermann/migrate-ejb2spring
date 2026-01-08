package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

/**
 * Migrates EJB @Asynchronous to Spring @Async.
 * <p>
 * Transformation:
 * - @Asynchronous -> @Async
 * - Supports both javax.ejb.Asynchronous and jakarta.ejb.Asynchronous
 * <p>
 * Note: Methods returning Future&lt;T&gt; or AsyncResult&lt;T&gt; should also be migrated
 * to use CompletableFuture&lt;T&gt; (handled by separate recipe MigrateAsyncResultToCompletableFuture).
 * <p>
 * Note: @EnableAsync must be added to the Spring application configuration
 * (handled by AddEnableJmsAndScheduling recipe).
 * <p>
 * <b>MANUAL REVIEW REQUIRED:</b> Spring @Async has different semantics than EJB @Asynchronous:
 * <ul>
 *   <li>Spring uses proxy-based AOP - self-invocation (calling @Async methods from within the same class) will NOT be async</li>
 *   <li>Thread context (SecurityContext, TransactionContext) may not propagate automatically</li>
 *   <li>Default executor is SimpleAsyncTaskExecutor (unbounded threads) - consider configuring a ThreadPoolTaskExecutor</li>
 *   <li>Exception handling differs - use AsyncUncaughtExceptionHandler for void methods</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAsynchronousToAsync extends Recipe {

    private static final String JAKARTA_ASYNCHRONOUS = "jakarta.ejb.Asynchronous";
    private static final String JAVAX_ASYNCHRONOUS = "javax.ejb.Asynchronous";
    private static final String SPRING_ASYNC = "org.springframework.scheduling.annotation.Async";

    @Override
    public String getDisplayName() {
        return "Migrate @Asynchronous to Spring @Async";
    }

    @Override
    public String getDescription() {
        return "Converts EJB @Asynchronous annotations to Spring @Async for asynchronous method execution.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_ASYNCHRONOUS, false),
                new UsesType<>(JAVAX_ASYNCHRONOUS, false)
            ),
            new AsynchronousVisitor()
        );
    }

    private class AsynchronousVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation ann = super.visitAnnotation(annotation, ctx);

            if (isAsynchronousAnnotation(ann)) {
                // Replace with @Async
                maybeAddImport(SPRING_ASYNC);
                doAfterVisit(new RemoveImport<>(JAKARTA_ASYNCHRONOUS, true));
                doAfterVisit(new RemoveImport<>(JAVAX_ASYNCHRONOUS, true));

                // Create @Async annotation (no arguments needed)
                JavaType.ShallowClass type = JavaType.ShallowClass.build(SPRING_ASYNC);
                J.Identifier ident = new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    org.openrewrite.marker.Markers.EMPTY,
                    java.util.Collections.emptyList(),
                    "Async",
                    type,
                    null
                );

                return ann.withAnnotationType(ident).withArguments(null);
            }

            return ann;
        }

        private boolean isAsynchronousAnnotation(J.Annotation ann) {
            if (ann.getType() != null) {
                if (TypeUtils.isOfClassType(ann.getType(), JAKARTA_ASYNCHRONOUS) ||
                    TypeUtils.isOfClassType(ann.getType(), JAVAX_ASYNCHRONOUS)) {
                    return true;
                }
            }
            // Fallback: check simple name if type info is missing
            String simpleName = ann.getSimpleName();
            return "Asynchronous".equals(simpleName);
        }
    }
}
