package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Migrates EJB @TransactionAttribute to Spring @Transactional.
 * <p>
 * Note: TransactionAttributeType values map to Propagation values with same names.
 * - REQUIRED -> @Transactional (or Propagation.REQUIRED)
 * - REQUIRES_NEW -> Propagation.REQUIRES_NEW
 * - etc.
 * <p>
 * Also handles:
 * - @TransactionManagement removed (Spring manages transactions differently)
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateTransactionAttribute extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate @TransactionAttribute to @Transactional";
    }

    @Override
    public String getDescription() {
        return "Converts EJB @TransactionAttribute annotations to Spring @Transactional.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>("javax.ejb.TransactionAttribute", false),
                new UsesType<>("javax.ejb.TransactionManagement", false)
            ),
            new TransactionAttributeVisitor()
        );
    }

    private static class TransactionAttributeVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);

            if (TypeUtils.isOfClassType(a.getType(), "javax.ejb.TransactionAttribute")) {
                maybeAddImport("org.springframework.transaction.annotation.Transactional");
                maybeRemoveImport("javax.ejb.TransactionAttribute");
                maybeRemoveImport("javax.ejb.TransactionAttributeType");

                // Use ChangeType for the annotation and TransactionAttributeType -> Propagation
                doAfterVisit(new ChangeType("javax.ejb.TransactionAttribute",
                    "org.springframework.transaction.annotation.Transactional", true).getVisitor());
                doAfterVisit(new ChangeType("javax.ejb.TransactionAttributeType",
                    "org.springframework.transaction.annotation.Propagation", true).getVisitor());

                return a;
            }

            // Remove @TransactionManagement
            if (TypeUtils.isOfClassType(a.getType(), "javax.ejb.TransactionManagement")) {
                maybeRemoveImport("javax.ejb.TransactionManagement");
                maybeRemoveImport("javax.ejb.TransactionManagementType");
                //noinspection DataFlowIssue
                return null;
            }

            return a;
        }
    }
}
