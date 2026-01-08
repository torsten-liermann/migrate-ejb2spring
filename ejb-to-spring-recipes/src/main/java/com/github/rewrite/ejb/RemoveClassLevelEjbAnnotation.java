package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes class-level @EJB annotations used for JNDI binding metadata.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveClassLevelEjbAnnotation extends Recipe {

    private static final String JAVAX_EJB = "javax.ejb.EJB";
    private static final String JAKARTA_EJB = "jakarta.ejb.EJB";
    private static final AnnotationMatcher JAVAX_EJB_MATCHER =
        new AnnotationMatcher("@" + JAVAX_EJB);
    private static final AnnotationMatcher JAKARTA_EJB_MATCHER =
        new AnnotationMatcher("@" + JAKARTA_EJB);

    @Override
    public String getDisplayName() {
        return "Remove class-level @EJB annotations";
    }

    @Override
    public String getDescription() {
        return "Removes class-level @EJB annotations (typically used for JNDI binding metadata).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                List<J.Annotation> annotations = cd.getLeadingAnnotations();
                if (annotations.isEmpty()) {
                    return cd;
                }

                List<J.Annotation> updated = new ArrayList<>();
                boolean changed = false;
                for (J.Annotation ann : annotations) {
                    if (isEjbAnnotation(ann)) {
                        changed = true;
                        continue;
                    }
                    updated.add(ann);
                }

                if (!changed) {
                    return cd;
                }

                maybeRemoveImport(JAVAX_EJB);
                maybeRemoveImport(JAKARTA_EJB);
                J.ClassDeclaration updatedDecl = cd.withLeadingAnnotations(updated);
                if (updated.isEmpty()) {
                    updatedDecl = updatedDecl.withPrefix(Space.format("\n"));
                }
                return updatedDecl;
            }

            private boolean isEjbAnnotation(J.Annotation ann) {
                if (JAVAX_EJB_MATCHER.matches(ann) || JAKARTA_EJB_MATCHER.matches(ann)) {
                    return true;
                }
                if (TypeUtils.isOfClassType(ann.getType(), JAVAX_EJB) ||
                    TypeUtils.isOfClassType(ann.getType(), JAKARTA_EJB)) {
                    return true;
                }
                return ann.getType() == null && "EJB".equals(ann.getSimpleName());
            }
        };
    }
}
