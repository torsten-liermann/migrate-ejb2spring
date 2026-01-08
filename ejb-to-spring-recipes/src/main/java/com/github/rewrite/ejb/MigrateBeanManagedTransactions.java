package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.*;

/**
 * GAP-TX-001: Marks Bean-Managed Transactions (BMT) for manual migration review.
 * <p>
 * Detects classes with UserTransaction fields and adds @NeedsReview annotation
 * with migration guidance. Does NOT transform field types or method calls.
 * <p>
 * Design rationale: Full transformation from begin/commit/rollback to TransactionTemplate.execute()
 * requires understanding control flow, exception handling, and return values - beyond safe automation.
 * Manual migration ensures correct semantics.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateBeanManagedTransactions extends Recipe {

    private static final String JAKARTA_USER_TX = "jakarta.transaction.UserTransaction";
    private static final String JAVAX_USER_TX = "javax.transaction.UserTransaction";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Mark Bean-Managed Transactions for Migration";
    }

    @Override
    public String getDescription() {
        return "Marks EJB Bean-Managed Transactions (UserTransaction) for manual migration to Spring's " +
               "TransactionTemplate. Adds @NeedsReview with migration guidance. Does not auto-transform " +
               "begin/commit/rollback calls to preserve compile safety.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_USER_TX, false),
                new UsesType<>(JAVAX_USER_TX, false)
            ),
            new BmtMigrationVisitor()
        );
    }

    private static class BmtMigrationVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // Check if this class has any UserTransaction fields
            boolean hasUserTransactionField = false;
            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        if (isUserTransactionType(vd)) {
                            hasUserTransactionField = true;
                            break;
                        }
                    }
                }
            }

            if (!hasUserTransactionField) {
                return classDecl;
            }

            // Visit children first
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Add @NeedsReview when UserTransaction fields exist
            if (!hasNeedsReviewAnnotation(cd)) {
                cd = addNeedsReviewAnnotation(cd, ctx);
            }

            return cd;
        }

        private boolean isUserTransactionType(J.VariableDeclarations vd) {
            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }

            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                if ("UserTransaction".equals(ident.getSimpleName())) {
                    return true;
                }
            }

            JavaType type = typeExpr.getType();
            return type != null && (
                TypeUtils.isOfClassType(type, JAKARTA_USER_TX) ||
                TypeUtils.isOfClassType(type, JAVAX_USER_TX)
            );
        }

        private boolean hasNeedsReviewAnnotation(J.ClassDeclaration cd) {
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) ||
                    "NeedsReview".equals(ann.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }

        private J.ClassDeclaration addNeedsReviewAnnotation(J.ClassDeclaration cd, ExecutionContext ctx) {
            addImportIfNeeded(NEEDS_REVIEW_FQN);

            JavaTemplate template = JavaTemplate.builder(
                "@NeedsReview(reason = \"Bean-Managed Transactions (UserTransaction) require manual migration to TransactionTemplate\", " +
                "category = NeedsReview.Category.MANUAL_MIGRATION, " +
                "originalCode = \"UserTransaction.begin/commit/rollback/setRollbackOnly\", " +
                "suggestedAction = \"Replace with transactionTemplate.execute(status -> { ... }); " +
                "use status.setRollbackOnly() for rollback; return value from lambda for results\")"
            ).javaParser(JavaParser.fromJavaVersion()
                .dependsOn(
                    "package com.github.rewrite.ejb.annotations;\n" +
                    "import java.lang.annotation.*;\n" +
                    "@Retention(RetentionPolicy.RUNTIME)\n" +
                    "@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})\n" +
                    "public @interface NeedsReview {\n" +
                    "    String reason() default \"\";\n" +
                    "    Category category();\n" +
                    "    String originalCode() default \"\";\n" +
                    "    String suggestedAction() default \"\";\n" +
                    "    enum Category { MANUAL_MIGRATION }\n" +
                    "}"
                ))
                .imports(NEEDS_REVIEW_FQN)
                .build();

            return template.apply(
                new Cursor(getCursor(), cd),
                cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
            );
        }

        private void addImportIfNeeded(String fqn) {
            doAfterVisit(new org.openrewrite.java.AddImport<>(fqn, null, false));
        }
    }
}
