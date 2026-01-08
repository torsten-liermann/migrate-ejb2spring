package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Migrates Jakarta EJB @TransactionAttribute to Spring @Transactional.
 * <p>
 * Handles:
 * - @TransactionAttribute -> @Transactional
 * - @TransactionAttribute(REQUIRED) -> @Transactional (REQUIRED is default)
 * - @TransactionAttribute(REQUIRES_NEW) -> @Transactional(propagation = Propagation.REQUIRES_NEW)
 * - @TransactionAttribute(TransactionAttributeType.X) -> @Transactional(propagation = Propagation.X)
 * - @TransactionManagement -> removed (Spring manages transactions differently)
 * <p>
 * Works with both jakarta.ejb and javax.ejb namespaces.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateTransactionAttributeJakarta extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate @TransactionAttribute to @Transactional (Jakarta EE)";
    }

    @Override
    public String getDescription() {
        return "Converts Jakarta EJB @TransactionAttribute annotations to Spring @Transactional with proper propagation mapping.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>("jakarta.ejb.TransactionAttribute", false),
                new UsesType<>("jakarta.ejb.TransactionManagement", false),
                new UsesType<>("javax.ejb.TransactionAttribute", false),
                new UsesType<>("javax.ejb.TransactionManagement", false)
            ),
            new TransactionAttributeVisitor()
        );
    }

    private static class TransactionAttributeVisitor extends JavaIsoVisitor<ExecutionContext> {

        private static final String JAKARTA_TX_ATTR = "jakarta.ejb.TransactionAttribute";
        private static final String JAKARTA_TX_ATTR_TYPE = "jakarta.ejb.TransactionAttributeType";
        private static final String JAKARTA_TX_MGMT = "jakarta.ejb.TransactionManagement";
        private static final String JAVAX_TX_ATTR = "javax.ejb.TransactionAttribute";
        private static final String JAVAX_TX_ATTR_TYPE = "javax.ejb.TransactionAttributeType";
        private static final String JAVAX_TX_MGMT = "javax.ejb.TransactionManagement";
        private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
        private static final String SPRING_PROPAGATION = "org.springframework.transaction.annotation.Propagation";

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);

            // Handle @TransactionAttribute (both jakarta and javax)
            if (isTransactionAttribute(a)) {
                return transformTransactionAttribute(a, ctx);
            }

            // Remove @TransactionManagement (both jakarta and javax)
            if (isTransactionManagement(a)) {
                maybeRemoveImport(JAKARTA_TX_MGMT);
                maybeRemoveImport(JAVAX_TX_MGMT);
                maybeRemoveImport("jakarta.ejb.TransactionManagementType");
                maybeRemoveImport("javax.ejb.TransactionManagementType");
                //noinspection DataFlowIssue
                return null;
            }

            return a;
        }

        private boolean isTransactionAttribute(J.Annotation a) {
            if ("TransactionAttribute".equals(a.getSimpleName())) {
                return true;
            }
            return TypeUtils.isOfClassType(a.getType(), JAKARTA_TX_ATTR) ||
                   TypeUtils.isOfClassType(a.getType(), JAVAX_TX_ATTR);
        }

        private boolean isTransactionManagement(J.Annotation a) {
            if ("TransactionManagement".equals(a.getSimpleName())) {
                return true;
            }
            return TypeUtils.isOfClassType(a.getType(), JAKARTA_TX_MGMT) ||
                   TypeUtils.isOfClassType(a.getType(), JAVAX_TX_MGMT);
        }

        private J.Annotation transformTransactionAttribute(J.Annotation annotation, ExecutionContext ctx) {
            maybeAddImport(SPRING_TRANSACTIONAL);
            maybeRemoveImport(JAKARTA_TX_ATTR);
            maybeRemoveImport(JAKARTA_TX_ATTR_TYPE);
            maybeRemoveImport(JAVAX_TX_ATTR);
            maybeRemoveImport(JAVAX_TX_ATTR_TYPE);

            // Check if there are arguments
            List<Expression> args = annotation.getArguments();

            // Create new @Transactional identifier
            JavaType.ShallowClass transactionalType = JavaType.ShallowClass.build(SPRING_TRANSACTIONAL);
            J.Identifier transactionalIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Transactional",
                transactionalType,
                null
            );

            if (args == null || args.isEmpty() ||
                (args.size() == 1 && args.get(0) instanceof J.Empty)) {
                // No arguments: @TransactionAttribute -> @Transactional
                return annotation.withAnnotationType(transactionalIdent).withArguments(null);
            }

            // Has arguments - need to map TransactionAttributeType to Propagation
            String propagationValue = extractPropagationValue(args);

            if (propagationValue == null || "REQUIRED".equals(propagationValue)) {
                // REQUIRED is the default, just use @Transactional without arguments
                return annotation.withAnnotationType(transactionalIdent).withArguments(null);
            }

            // Other propagation values need explicit mapping
            maybeAddImport(SPRING_PROPAGATION);

            // Create propagation = Propagation.X argument
            JavaType.ShallowClass propagationType = JavaType.ShallowClass.build(SPRING_PROPAGATION);

            J.Identifier propagationClass = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Propagation",
                propagationType,
                null
            );

            J.Identifier propagationValueIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                propagationValue,
                null,
                null
            );

            J.FieldAccess propagationFieldAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                propagationClass,
                JLeftPadded.build(propagationValueIdent),
                propagationType
            );

            J.Identifier propagationKey = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "propagation",
                null,
                null
            );

            J.Assignment propagationAssignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                propagationKey,
                JLeftPadded.<Expression>build(propagationFieldAccess).withBefore(Space.format(" ")),
                null
            );

            List<Expression> newArgs = new ArrayList<>();
            newArgs.add(propagationAssignment);

            return annotation
                .withAnnotationType(transactionalIdent)
                .withArguments(newArgs);
        }

        private String extractPropagationValue(List<Expression> args) {
            if (args == null || args.isEmpty()) {
                return null;
            }

            Expression arg = args.get(0);

            // Handle @TransactionAttribute(value = ...) or @TransactionAttribute(...)
            if (arg instanceof J.Assignment) {
                arg = ((J.Assignment) arg).getAssignment();
            }

            // Handle TransactionAttributeType.REQUIRED or just REQUIRED
            if (arg instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) arg;
                return fa.getSimpleName();
            }

            if (arg instanceof J.Identifier) {
                return ((J.Identifier) arg).getSimpleName();
            }

            return null;
        }
    }
}
