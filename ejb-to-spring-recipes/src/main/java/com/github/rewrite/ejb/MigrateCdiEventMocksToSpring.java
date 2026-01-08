package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

/**
 * Migrates CDI Event mocks in test classes to Spring ApplicationEventPublisher.
 * <p>
 * Handles the pattern:
 * {@code @SuppressWarnings("unchecked") private final X x = (Event<T>) mock(Event.class);}
 * Transforms to:
 * {@code private final X x = mock(ApplicationEventPublisher.class);}
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateCdiEventMocksToSpring extends Recipe {

    private static final String APP_EVENT_PUBLISHER_FQN = "org.springframework.context.ApplicationEventPublisher";
    private static final String CDI_EVENT_JAKARTA_FQN = "jakarta.enterprise.event.Event";
    private static final String CDI_EVENT_JAVAX_FQN = "javax.enterprise.event.Event";

    @Override
    public String getDisplayName() {
        return "Migrate CDI Event mocks to Spring ApplicationEventPublisher";
    }

    @Override
    public String getDescription() {
        return "Converts mock(Event.class) to mock(ApplicationEventPublisher.class) and removes Event casts.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);

                boolean modified = false;
                List<J.VariableDeclarations.NamedVariable> newVars = new ArrayList<>();

                for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                    Expression init = var.getInitializer();
                    if (init != null) {
                        Expression transformed = transformExpression(init);
                        if (transformed != init) {
                            var = var.withInitializer(transformed);
                            modified = true;
                        }
                    }
                    newVars.add(var);
                }

                if (modified) {
                    vd = vd.withVariables(newVars);

                    // Remove @SuppressWarnings("unchecked")
                    List<J.Annotation> newAnns = new ArrayList<>();
                    for (J.Annotation ann : vd.getLeadingAnnotations()) {
                        if (!isSuppressWarningsUnchecked(ann)) {
                            newAnns.add(ann);
                        }
                    }
                    if (newAnns.size() != vd.getLeadingAnnotations().size()) {
                        vd = vd.withLeadingAnnotations(newAnns);
                    }
                }

                return vd;
            }

            private Expression transformExpression(Expression expr) {
                // Handle (Event<T>) mock(Event.class) - remove cast and transform mock
                if (expr instanceof J.TypeCast) {
                    J.TypeCast cast = (J.TypeCast) expr;
                    // getClazz() returns J.ControlParentheses<TypeTree>, need to get the inner tree
                    J.ControlParentheses<TypeTree> clazz = cast.getClazz();
                    if (clazz != null && isEventType(clazz.getTree())) {
                        Expression inner = cast.getExpression();
                        Expression transformed = transformExpression(inner);
                        // Only remove the cast if the inner expression was transformed
                        if (transformed != inner) {
                            return transformed;
                        }
                    }
                }

                // Handle mock(Event.class) -> mock(ApplicationEventPublisher.class)
                if (expr instanceof J.MethodInvocation) {
                    J.MethodInvocation mi = (J.MethodInvocation) expr;
                    if ("mock".equals(mi.getSimpleName()) && mi.getArguments().size() >= 1) {
                        Expression firstArg = mi.getArguments().get(0);
                        if (isEventClassLiteral(firstArg)) {
                            maybeAddImport(APP_EVENT_PUBLISHER_FQN);
                            List<Expression> newArgs = new ArrayList<>(mi.getArguments());
                            newArgs.set(0, createClassLiteral("ApplicationEventPublisher", APP_EVENT_PUBLISHER_FQN));
                            return mi.withArguments(newArgs);
                        }
                    }
                }

                return expr;
            }

            private boolean isEventClassLiteral(Expression expr) {
                if (expr instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) expr;
                    if ("class".equals(fa.getSimpleName())) {
                        Expression target = fa.getTarget();
                        if (target instanceof J.Identifier) {
                            J.Identifier ident = (J.Identifier) target;
                            if (!"Event".equals(ident.getSimpleName())) {
                                return false;
                            }
                            if (ident.getType() == null) {
                                return true;
                            }
                            return TypeUtils.isOfClassType(ident.getType(), CDI_EVENT_JAKARTA_FQN)
                                || TypeUtils.isOfClassType(ident.getType(), CDI_EVENT_JAVAX_FQN);
                        }
                    }
                }
                return false;
            }

            private boolean isEventType(TypeTree tree) {
                if (tree instanceof J.ParameterizedType) {
                    J.ParameterizedType pt = (J.ParameterizedType) tree;
                    if (pt.getClazz() instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) pt.getClazz();
                        if (!"Event".equals(ident.getSimpleName())) {
                            return false;
                        }
                        if (ident.getType() == null) {
                            return true;
                        }
                        return TypeUtils.isOfClassType(ident.getType(), CDI_EVENT_JAKARTA_FQN)
                            || TypeUtils.isOfClassType(ident.getType(), CDI_EVENT_JAVAX_FQN);
                    }
                }
                if (tree instanceof J.Identifier) {
                    J.Identifier ident = (J.Identifier) tree;
                    if (!"Event".equals(ident.getSimpleName())) {
                        return false;
                    }
                    if (ident.getType() == null) {
                        return true;
                    }
                    return TypeUtils.isOfClassType(ident.getType(), CDI_EVENT_JAKARTA_FQN)
                        || TypeUtils.isOfClassType(ident.getType(), CDI_EVENT_JAVAX_FQN);
                }
                return false;
            }

            private boolean isSuppressWarningsUnchecked(J.Annotation ann) {
                if (!"SuppressWarnings".equals(ann.getSimpleName())) {
                    return false;
                }
                if (ann.getArguments() == null || ann.getArguments().isEmpty()) {
                    return false;
                }
                Expression arg = ann.getArguments().get(0);
                if (arg instanceof J.Literal) {
                    return "unchecked".equals(((J.Literal) arg).getValue());
                }
                return false;
            }

            private J.FieldAccess createClassLiteral(String simpleName, String fqn) {
                JavaType.ShallowClass type = JavaType.ShallowClass.build(fqn);
                J.Identifier classIdent = new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    simpleName,
                    type,
                    null
                );

                J.Identifier classKeyword = new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "class",
                    null,
                    null
                );

                return new J.FieldAccess(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    classIdent,
                    JLeftPadded.build(classKeyword),
                    null
                );
            }
        };
    }
}
