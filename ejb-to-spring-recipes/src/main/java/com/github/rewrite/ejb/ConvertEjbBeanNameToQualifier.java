package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts @EJB(beanName=...) to @Autowired + @Qualifier and updates beanInterface type if present.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ConvertEjbBeanNameToQualifier extends Recipe {

    private static final String JAVAX_EJB = "javax.ejb.EJB";
    private static final String JAKARTA_EJB = "jakarta.ejb.EJB";
    private static final String SPRING_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final String SPRING_QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier";

    private static final AnnotationMatcher JAVAX_EJB_MATCHER =
        new AnnotationMatcher("@" + JAVAX_EJB);
    private static final AnnotationMatcher JAKARTA_EJB_MATCHER =
        new AnnotationMatcher("@" + JAKARTA_EJB);

    @Override
    public String getDisplayName() {
        return "Convert @EJB(beanName=...) to @Qualifier";
    }

    @Override
    public String getDescription() {
        return "Replaces @EJB(beanName=...) with @Autowired and @Qualifier, and updates the field type " +
               "when beanInterface is specified.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new BeanNameToQualifierVisitor();
    }

    private class BeanNameToQualifierVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext ctx) {
            J.VariableDeclarations decls = super.visitVariableDeclarations(vd, ctx);
            if (!isFieldDeclaration()) {
                return decls;
            }

            J.Annotation ejbAnnotation = findEjbAnnotation(decls.getLeadingAnnotations());
            if (ejbAnnotation == null) {
                return decls;
            }

            Expression beanNameExpr = extractBeanName(ejbAnnotation);
            if (beanNameExpr == null) {
                return decls;
            }

            BeanInterfaceInfo beanInterface = extractBeanInterface(ejbAnnotation);
            if (beanInterface != null) {
                TypeTree currentType = decls.getTypeExpression();
                Space typePrefix = currentType != null ? currentType.getPrefix() : Space.EMPTY;
                TypeTree updatedType = beanInterface.typeExpr != null
                    ? (TypeTree) ((J) beanInterface.typeExpr).withPrefix(typePrefix)
                    : currentType;
                if (updatedType != null) {
                    decls = decls.withTypeExpression(updatedType);
                }
                if (beanInterface.type != null) {
                    decls = updateVariableTypes(decls, beanInterface.type);
                }
            }

            boolean hasAutowired = hasAnnotation(decls.getLeadingAnnotations(), SPRING_AUTOWIRED, "Autowired");
            boolean hasQualifier = hasQualifier(decls.getLeadingAnnotations(), beanNameExpr);

            List<J.Annotation> newAnnotations = new ArrayList<>();
            for (J.Annotation ann : decls.getLeadingAnnotations()) {
                if (ann == ejbAnnotation) {
                    Space basePrefix = ann.getPrefix();
                    String indent = extractIndent(basePrefix);
                    if (indent.isEmpty()) {
                        indent = extractIndent(decls.getPrefix());
                    }
                    if (!hasAutowired) {
                        newAnnotations.add(createAutowiredAnnotation(basePrefix));
                    }
                    Space qualifierPrefix = Space.format("\n" + indent);
                    if (!hasQualifier) {
                        newAnnotations.add(createQualifierAnnotation(beanNameExpr, qualifierPrefix));
                    }
                } else {
                    newAnnotations.add(ann);
                }
            }

            decls = decls.withLeadingAnnotations(newAnnotations);

            if (!hasAutowired) {
                maybeAddImport(SPRING_AUTOWIRED);
            }
            if (!hasQualifier) {
                maybeAddImport(SPRING_QUALIFIER);
            }
            maybeRemoveImport(JAVAX_EJB);
            maybeRemoveImport(JAKARTA_EJB);

            return decls;
        }

        private J.Annotation findEjbAnnotation(List<J.Annotation> annotations) {
            for (J.Annotation ann : annotations) {
                if (JAVAX_EJB_MATCHER.matches(ann) || JAKARTA_EJB_MATCHER.matches(ann)) {
                    return ann;
                }
                if (TypeUtils.isOfClassType(ann.getType(), JAVAX_EJB) ||
                    TypeUtils.isOfClassType(ann.getType(), JAKARTA_EJB)) {
                    return ann;
                }
                if (ann.getType() == null && "EJB".equals(ann.getSimpleName())) {
                    return ann;
                }
            }
            return null;
        }

        private @Nullable Expression extractBeanName(J.Annotation annotation) {
            List<Expression> args = annotation.getArguments();
            if (args == null) {
                return null;
            }
            for (Expression arg : args) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assign = (J.Assignment) arg;
                    if (assign.getVariable() instanceof J.Identifier) {
                        if ("beanName".equals(((J.Identifier) assign.getVariable()).getSimpleName())) {
                            return assign.getAssignment().withPrefix(Space.EMPTY);
                        }
                    }
                }
            }
            return null;
        }

        private @Nullable BeanInterfaceInfo extractBeanInterface(J.Annotation annotation) {
            List<Expression> args = annotation.getArguments();
            if (args == null) {
                return null;
            }
            for (Expression arg : args) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assign = (J.Assignment) arg;
                    if (assign.getVariable() instanceof J.Identifier) {
                        if ("beanInterface".equals(((J.Identifier) assign.getVariable()).getSimpleName())) {
                            return resolveClassLiteral(assign.getAssignment());
                        }
                    }
                }
            }
            return null;
        }

        private @Nullable BeanInterfaceInfo resolveClassLiteral(Expression expr) {
            if (!(expr instanceof J.FieldAccess)) {
                return null;
            }
            J.FieldAccess fa = (J.FieldAccess) expr;
            if (!"class".equals(fa.getSimpleName())) {
                return null;
            }
            Expression target = fa.getTarget();
            if (!(target instanceof TypeTree)) {
                return null;
            }
            JavaType type = target.getType();
            JavaType.ShallowClass shallow = null;
            if (type instanceof JavaType.FullyQualified) {
                shallow = JavaType.ShallowClass.build(((JavaType.FullyQualified) type).getFullyQualifiedName());
            } else if (target instanceof J.Identifier || target instanceof J.FieldAccess) {
                shallow = JavaType.ShallowClass.build(target.toString());
            }
            return new BeanInterfaceInfo((TypeTree) target, shallow);
        }

        private boolean hasAnnotation(List<J.Annotation> annotations, String fqn, String simpleName) {
            for (J.Annotation ann : annotations) {
                if (simpleName.equals(ann.getSimpleName()) || TypeUtils.isOfClassType(ann.getType(), fqn)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasQualifier(List<J.Annotation> annotations, Expression beanName) {
            for (J.Annotation ann : annotations) {
                if (!"Qualifier".equals(ann.getSimpleName()) &&
                    !TypeUtils.isOfClassType(ann.getType(), SPRING_QUALIFIER)) {
                    continue;
                }
                List<Expression> args = ann.getArguments();
                if (args == null || args.isEmpty()) {
                    return false;
                }
                Expression first = args.get(0);
                return first.printTrimmed().equals(beanName.printTrimmed());
            }
            return false;
        }

        private J.Annotation createAutowiredAnnotation(Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(SPRING_AUTOWIRED);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Autowired",
                type,
                null
            );
            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                null
            );
        }

        private J.Annotation createQualifierAnnotation(Expression valueExpr, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(SPRING_QUALIFIER);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Qualifier",
                type,
                null
            );
            List<JRightPadded<Expression>> args = new ArrayList<>();
            args.add(new JRightPadded<>(valueExpr.withPrefix(Space.EMPTY), Space.EMPTY, Markers.EMPTY));
            JContainer<Expression> container = JContainer.build(Space.EMPTY, args, Markers.EMPTY);
            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                container
            );
        }

        private String extractIndent(Space prefix) {
            String ws = prefix.getWhitespace();
            int lastNewline = ws.lastIndexOf('\n');
            if (lastNewline >= 0) {
                return ws.substring(lastNewline + 1);
            }
            return "";
        }

        private boolean isFieldDeclaration() {
            Cursor parent = getCursor().getParentTreeCursor();
            if (parent == null || !(parent.getValue() instanceof J.Block)) {
                return false;
            }
            Cursor grandParent = parent.getParentTreeCursor();
            return grandParent != null && grandParent.getValue() instanceof J.ClassDeclaration;
        }

        private J.VariableDeclarations updateVariableTypes(J.VariableDeclarations vd, JavaType.ShallowClass type) {
            List<J.VariableDeclarations.NamedVariable> updated = new ArrayList<>();
            for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                JavaType.Variable variableType = var.getVariableType();
                if (variableType != null) {
                    var = var.withVariableType(variableType.withType(type));
                } else {
                    var = var.withVariableType(new JavaType.Variable(null, 0, var.getSimpleName(), null, type, null));
                }
                updated.add(var);
            }
            return vd.withVariables(updated);
        }
    }

    private static class BeanInterfaceInfo {
        final TypeTree typeExpr;
        final JavaType.ShallowClass type;

        BeanInterfaceInfo(TypeTree typeExpr, JavaType.ShallowClass type) {
            this.typeExpr = typeExpr;
            this.type = type;
        }
    }
}
