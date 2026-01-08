package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Migrates @Stateless EJB annotations to Spring @Service.
 * <p>
 * Handles both javax.ejb and jakarta.ejb namespaces:
 * - @Stateless -> @Service
 * - Removes @LocalBean (not needed in Spring)
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateStatelessToService extends Recipe {

    private static final String JAVAX_STATELESS = "javax.ejb.Stateless";
    private static final String JAKARTA_STATELESS = "jakarta.ejb.Stateless";
    private static final String JAVAX_LOCALBEAN = "javax.ejb.LocalBean";
    private static final String JAKARTA_LOCALBEAN = "jakarta.ejb.LocalBean";
    private static final String SPRING_SERVICE = "org.springframework.stereotype.Service";

    @Override
    public String getDisplayName() {
        return "Migrate @Stateless to @Service";
    }

    @Override
    public String getDescription() {
        return "Converts EJB @Stateless annotations to Spring @Service annotations. " +
               "Supports both javax.ejb and jakarta.ejb namespaces.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAVAX_STATELESS, false),
                new UsesType<>(JAKARTA_STATELESS, false)
            ),
            new StatelessToServiceVisitor()
        );
    }

    private static class StatelessToServiceVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);

            // Check for @Stateless (javax or jakarta)
            if (TypeUtils.isOfClassType(a.getType(), JAVAX_STATELESS)) {
                maybeAddImport(SPRING_SERVICE);
                maybeRemoveImport(JAVAX_STATELESS);
                doAfterVisit(new ChangeType(JAVAX_STATELESS, SPRING_SERVICE, true).getVisitor());
                return a;
            }
            if (TypeUtils.isOfClassType(a.getType(), JAKARTA_STATELESS)) {
                maybeAddImport(SPRING_SERVICE);
                maybeRemoveImport(JAKARTA_STATELESS);
                doAfterVisit(new ChangeType(JAKARTA_STATELESS, SPRING_SERVICE, true).getVisitor());
                return a;
            }

            // Check for @LocalBean and remove it (javax or jakarta)
            if (TypeUtils.isOfClassType(a.getType(), JAVAX_LOCALBEAN)) {
                maybeRemoveImport(JAVAX_LOCALBEAN);
                //noinspection DataFlowIssue
                return null; // Remove the annotation
            }
            if (TypeUtils.isOfClassType(a.getType(), JAKARTA_LOCALBEAN)) {
                maybeRemoveImport(JAKARTA_LOCALBEAN);
                //noinspection DataFlowIssue
                return null; // Remove the annotation
            }

            return a;
        }
    }
}
