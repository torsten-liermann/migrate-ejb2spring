package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

/**
 * Migrates EJB AsyncResult to CompletableFuture.
 * <p>
 * Transformation:
 * - new AsyncResult&lt;&gt;(value) -> CompletableFuture.completedFuture(value)
 * - AsyncResult&lt;T&gt; type references -> CompletableFuture&lt;T&gt; (variable declarations, return types, etc.)
 * - Supports both javax.ejb.AsyncResult and jakarta.ejb.AsyncResult
 * <p>
 * This recipe should be used together with MigrateAsynchronousToAsync
 * for complete async method migration.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAsyncResultToCompletableFuture extends Recipe {

    private static final String JAKARTA_ASYNC_RESULT = "jakarta.ejb.AsyncResult";
    private static final String JAVAX_ASYNC_RESULT = "javax.ejb.AsyncResult";
    private static final String COMPLETABLE_FUTURE = "java.util.concurrent.CompletableFuture";

    @Override
    public String getDisplayName() {
        return "Migrate AsyncResult to CompletableFuture";
    }

    @Override
    public String getDescription() {
        return "Converts EJB AsyncResult to CompletableFuture.completedFuture() for Spring async methods.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_ASYNC_RESULT, false),
                new UsesType<>(JAVAX_ASYNC_RESULT, false)
            ),
            new AsyncResultVisitor()
        );
    }

    /**
     * Returns additional recipes to change AsyncResult type references to CompletableFuture.
     * This ensures that variable declarations, return types, and other type usages are also migrated.
     */
    @Override
    public java.util.List<Recipe> getRecipeList() {
        return java.util.Arrays.asList(
            new ChangeType(JAVAX_ASYNC_RESULT, COMPLETABLE_FUTURE, true),
            new ChangeType(JAKARTA_ASYNC_RESULT, COMPLETABLE_FUTURE, true)
        );
    }

    private class AsyncResultVisitor extends JavaVisitor<ExecutionContext> {

        private final JavaTemplate completedFutureTemplate = JavaTemplate.builder(
                "CompletableFuture.completedFuture(#{any()})")
            .imports(COMPLETABLE_FUTURE)
            .build();

        @Override
        public J visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
            J.NewClass nc = (J.NewClass) super.visitNewClass(newClass, ctx);

            if (isAsyncResultConstructor(nc)) {
                maybeAddImport(COMPLETABLE_FUTURE);
                doAfterVisit(new RemoveImport<>(JAKARTA_ASYNC_RESULT, true));
                doAfterVisit(new RemoveImport<>(JAVAX_ASYNC_RESULT, true));

                // Get the constructor argument
                Expression valueArg = null;
                if (nc.getArguments() != null && !nc.getArguments().isEmpty()) {
                    valueArg = nc.getArguments().get(0);
                    if (valueArg instanceof J.Empty) {
                        valueArg = null;
                    }
                }

                if (valueArg != null) {
                    return completedFutureTemplate.apply(
                        getCursor(),
                        nc.getCoordinates().replace(),
                        valueArg
                    );
                } else {
                    // Handle case with no argument (null)
                    JavaTemplate nullTemplate = JavaTemplate.builder(
                            "CompletableFuture.completedFuture(null)")
                        .imports(COMPLETABLE_FUTURE)
                        .build();
                    return nullTemplate.apply(
                        getCursor(),
                        nc.getCoordinates().replace()
                    );
                }
            }

            return nc;
        }

        private boolean isAsyncResultConstructor(J.NewClass newClass) {
            if (newClass.getClazz() != null) {
                JavaType type = newClass.getClazz().getType();
                if (type != null) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                    if (fq != null) {
                        String fqn = fq.getFullyQualifiedName();
                        if (JAKARTA_ASYNC_RESULT.equals(fqn) || JAVAX_ASYNC_RESULT.equals(fqn)) {
                            return true;
                        }
                    }
                }
                // Fallback: check class name
                if (newClass.getClazz() instanceof J.ParameterizedType) {
                    J.ParameterizedType pt = (J.ParameterizedType) newClass.getClazz();
                    if (pt.getClazz() instanceof J.Identifier) {
                        String name = ((J.Identifier) pt.getClazz()).getSimpleName();
                        if ("AsyncResult".equals(name)) {
                            return true;
                        }
                    }
                } else if (newClass.getClazz() instanceof J.Identifier) {
                    String name = ((J.Identifier) newClass.getClazz()).getSimpleName();
                    if ("AsyncResult".equals(name)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
