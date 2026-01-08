package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

/**
 * Migrates EJBException to RuntimeException.
 * <p>
 * EJBException is the standard exception for EJB errors. In Spring Boot,
 * RuntimeException is the natural replacement.
 * <p>
 * This recipe handles:
 * <ul>
 *   <li>Type change: jakarta.ejb.EJBException → java.lang.RuntimeException</li>
 *   <li>Type change: javax.ejb.EJBException → java.lang.RuntimeException</li>
 *   <li>Method rewrite: getCausedByException() → getCause()</li>
 * </ul>
 * <p>
 * Note: getCausedByException() returns Exception, while getCause() returns Throwable.
 * If the original code casts the result to Exception, the cast will still work.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEjbException extends Recipe {

    private static final String JAVAX_EJB_EXCEPTION = "javax.ejb.EJBException";
    private static final String JAKARTA_EJB_EXCEPTION = "jakarta.ejb.EJBException";
    private static final String RUNTIME_EXCEPTION = "java.lang.RuntimeException";

    @Override
    public String getDisplayName() {
        return "Migrate EJBException to RuntimeException";
    }

    @Override
    public String getDescription() {
        return "Converts EJBException to RuntimeException and rewrites getCausedByException() calls to getCause(). " +
               "Supports both javax.ejb and jakarta.ejb namespaces.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAVAX_EJB_EXCEPTION, false),
                new UsesType<>(JAKARTA_EJB_EXCEPTION, false)
            ),
            new EjbExceptionMigrationVisitor()
        );
    }

    private static class EjbExceptionMigrationVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);

            // Apply ChangeType for both namespaces
            c = (J.CompilationUnit) new ChangeType(JAVAX_EJB_EXCEPTION, RUNTIME_EXCEPTION, true)
                    .getVisitor().visit(c, ctx);
            c = (J.CompilationUnit) new ChangeType(JAKARTA_EJB_EXCEPTION, RUNTIME_EXCEPTION, true)
                    .getVisitor().visit(c, ctx);

            return c;
        }

        @Override
        public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable variable, ExecutionContext ctx) {
            // Check BEFORE super if we need a cast
            // getCausedByException() returns Exception, getCause() returns Throwable
            // If assigned to Exception (or subtype), we need a cast
            boolean needsCast = false;
            if (variable.getInitializer() instanceof J.MethodInvocation) {
                J.MethodInvocation init = (J.MethodInvocation) variable.getInitializer();
                if (isGetCausedByExceptionCall(init)) {
                    JavaType varType = variable.getType();
                    needsCast = varType != null && isExceptionType(varType);
                }
            }

            // Let super handle the transformation (including ChangeType)
            J.VariableDeclarations.NamedVariable v = super.visitVariable(variable, ctx);

            // If we determined a cast is needed, wrap the initializer
            if (needsCast && v.getInitializer() instanceof J.MethodInvocation) {
                J.MethodInvocation transformed = (J.MethodInvocation) v.getInitializer();
                J.TypeCast cast = createExceptionCast(transformed);
                return v.withInitializer(cast);
            }

            return v;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);

            // Rewrite getCausedByException() to getCause()
            if (isGetCausedByExceptionCall(m)) {
                // Replace with getCause() call
                return m.withName(m.getName().withSimpleName("getCause"));
            }

            return m;
        }

        /**
         * Check if the type is Exception or a subtype of Exception (but not Throwable).
         */
        private boolean isExceptionType(JavaType type) {
            return TypeUtils.isAssignableTo("java.lang.Exception", type) &&
                   !TypeUtils.isOfType(type, JavaType.buildType("java.lang.Throwable"));
        }

        /**
         * Create a type cast to Exception wrapping the given expression.
         * Preserves the original expression's prefix on the cast, and adds
         * a single space before the wrapped expression.
         */
        private J.TypeCast createExceptionCast(Expression expr) {
            J.Identifier exceptionIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                null,
                "Exception",
                JavaType.buildType("java.lang.Exception"),
                null
            );

            J.ControlParentheses<TypeTree> clazz = new J.ControlParentheses<>(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                JRightPadded.build(exceptionIdent)
            );

            // Preserve original expression's prefix (space after '=') on the cast
            // Add single space before the wrapped expression (space after ')')
            return new J.TypeCast(
                Tree.randomId(),
                expr.getPrefix(),
                Markers.EMPTY,
                clazz,
                expr.withPrefix(Space.SINGLE_SPACE)
            );
        }

        private boolean isGetCausedByExceptionCall(J.MethodInvocation method) {
            // Check if method name is getCausedByException
            if (!"getCausedByException".equals(method.getSimpleName())) {
                return false;
            }

            // Check if no arguments (getCausedByException() takes no args)
            if (method.getArguments() != null && !method.getArguments().isEmpty()) {
                // Filter out empty arguments marker
                boolean hasRealArgs = method.getArguments().stream()
                    .anyMatch(arg -> !(arg instanceof J.Empty));
                if (hasRealArgs) {
                    return false;
                }
            }

            // Check if called on EJBException type (or variable of that type)
            JavaType selectType = method.getSelect() != null ? method.getSelect().getType() : null;
            if (selectType != null) {
                return TypeUtils.isAssignableTo(JAVAX_EJB_EXCEPTION, selectType) ||
                       TypeUtils.isAssignableTo(JAKARTA_EJB_EXCEPTION, selectType);
            }

            // If we can't determine the type, check by method type
            JavaType.Method methodType = method.getMethodType();
            if (methodType != null && methodType.getDeclaringType() != null) {
                String declaringType = methodType.getDeclaringType().getFullyQualifiedName();
                return JAVAX_EJB_EXCEPTION.equals(declaringType) ||
                       JAKARTA_EJB_EXCEPTION.equals(declaringType);
            }

            return false;
        }
    }
}
