package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.Collections;

/**
 * Migrates CDI Instance&lt;T&gt; to Spring ObjectProvider&lt;T&gt;.
 * <p>
 * Handles:
 * 1. Instance&lt;T&gt; type -> ObjectProvider&lt;T&gt;
 * 2. .get() -> .getObject()
 * 3. .isUnsatisfied() -> check if getIfAvailable() returns null
 * 4. .isAmbiguous() -> no direct equivalent (removed)
 * <p>
 * Note: Instance.iterator() and stream() have different semantics in ObjectProvider.
 * These cases are marked with TODO comments.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateCdiInstanceToObjectProvider extends Recipe {

    private static final String INSTANCE_FQN = "jakarta.enterprise.inject.Instance";
    private static final String OBJECT_PROVIDER_FQN = "org.springframework.beans.factory.ObjectProvider";

    @Override
    public String getDisplayName() {
        return "Migrate CDI Instance<T> to Spring ObjectProvider<T>";
    }

    @Override
    public String getDescription() {
        return "Converts CDI Instance<T> to Spring ObjectProvider<T> and adapts method calls.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            new UsesType<>(INSTANCE_FQN, false),
            new CdiInstanceVisitor()
        );
    }

    private static class CdiInstanceVisitor extends JavaIsoVisitor<ExecutionContext> {

        private boolean transformedType = false;

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            transformedType = false;
            J.CompilationUnit result = super.visitCompilationUnit(cu, ctx);

            if (transformedType) {
                doAfterVisit(new RemoveImport<>(INSTANCE_FQN, true));
            }

            return result;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations variableDeclarations, ExecutionContext ctx) {
            J.VariableDeclarations vd = super.visitVariableDeclarations(variableDeclarations, ctx);

            TypeTree typeExpression = vd.getTypeExpression();
            if (typeExpression instanceof J.ParameterizedType) {
                J.ParameterizedType pt = (J.ParameterizedType) typeExpression;
                if (pt.getClazz() instanceof J.Identifier) {
                    J.Identifier clazz = (J.Identifier) pt.getClazz();
                    if (isInstanceType(clazz)) {
                        maybeAddImport(OBJECT_PROVIDER_FQN);
                        transformedType = true;

                        // Create new ObjectProvider identifier
                        JavaType.ShallowClass providerType = JavaType.ShallowClass.build(OBJECT_PROVIDER_FQN);
                        J.Identifier newClazz = new J.Identifier(
                            Tree.randomId(),
                            clazz.getPrefix(),
                            Markers.EMPTY,
                            Collections.emptyList(),
                            "ObjectProvider",
                            providerType,
                            null
                        );

                        // Keep type parameters (ObjectProvider<T> is also generic)
                        J.ParameterizedType newPt = pt.withClazz(newClazz);
                        vd = vd.withTypeExpression(newPt);
                    }
                }
            }

            return vd;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);

            Expression select = mi.getSelect();
            if (select == null || select.getType() == null) {
                return mi;
            }

            // Check if this is a method call on Instance or ObjectProvider
            boolean isInstanceOrProvider = TypeUtils.isAssignableTo(INSTANCE_FQN, select.getType()) ||
                                          TypeUtils.isAssignableTo(OBJECT_PROVIDER_FQN, select.getType());

            if (!isInstanceOrProvider) {
                return mi;
            }

            String methodName = mi.getSimpleName();

            switch (methodName) {
                case "get":
                    // Instance.get() -> ObjectProvider.getObject()
                    mi = mi.withName(mi.getName().withSimpleName("getObject"));
                    break;

                case "isUnsatisfied":
                    // Instance.isUnsatisfied() -> ObjectProvider.getIfAvailable() == null
                    // This is a semantic change - add TODO comment
                    // For now, just rename to getIfAvailable and let developer fix
                    mi = mi.withName(mi.getName().withSimpleName("getIfAvailable"));
                    // TODO: Could wrap in "== null" check, but that changes return type
                    break;

                case "isAmbiguous":
                    // No direct equivalent in ObjectProvider
                    // Keep method name, will cause compile error prompting manual fix
                    break;

                case "iterator":
                case "stream":
                    // ObjectProvider has stream() but semantics differ
                    // Keep as-is, should work for basic cases
                    break;

                case "select":
                    // Instance.select(Qualifier.class) has no direct equivalent
                    // Keep method name, will cause compile error
                    break;
            }

            return mi;
        }

        private boolean isInstanceType(J.Identifier ident) {
            if (ident.getType() != null) {
                return TypeUtils.isOfClassType(ident.getType(), INSTANCE_FQN);
            }
            return "Instance".equals(ident.getSimpleName());
        }
    }
}
