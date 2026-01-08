package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.J;

/**
 * Removes @Local annotations from EJB interfaces and classes.
 * <p>
 * Handles both javax.ejb and jakarta.ejb namespaces.
 * In Spring, local business interfaces don't need special annotations.
 * The interface remains as a regular Java interface, and Spring handles
 * injection through @Autowired.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveLocalAnnotation extends Recipe {

    private static final String JAVAX_LOCAL = "javax.ejb.Local";
    private static final String JAKARTA_LOCAL = "jakarta.ejb.Local";

    @Override
    public String getDisplayName() {
        return "Remove @Local annotations";
    }

    @Override
    public String getDescription() {
        return "Removes EJB @Local annotations from interfaces and classes. " +
               "Supports both javax.ejb and jakarta.ejb namespaces. " +
               "Spring doesn't require these annotations for local bean access.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new RemoveLocalVisitor();
    }

    private static class RemoveLocalVisitor extends JavaIsoVisitor<ExecutionContext> {

        private static final AnnotationMatcher JAVAX_LOCAL_MATCHER =
            new AnnotationMatcher("@" + JAVAX_LOCAL);
        private static final AnnotationMatcher JAKARTA_LOCAL_MATCHER =
            new AnnotationMatcher("@" + JAKARTA_LOCAL);

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);

            if (JAVAX_LOCAL_MATCHER.matches(a)) {
                maybeRemoveImport(JAVAX_LOCAL);
                //noinspection DataFlowIssue
                return null; // Remove the annotation
            }
            if (JAKARTA_LOCAL_MATCHER.matches(a)) {
                maybeRemoveImport(JAKARTA_LOCAL);
                //noinspection DataFlowIssue
                return null; // Remove the annotation
            }

            return a;
        }
    }
}
