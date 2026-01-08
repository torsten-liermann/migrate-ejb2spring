package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes references to LoggerProducer class after it has been deleted.
 * <p>
 * This handles the cleanup in test classes (like Arquillian Deployments.java)
 * that reference the deleted LoggerProducer class.
 * <p>
 * Transformations:
 * - Removes import statements for LoggerProducer
 * - Removes method calls like .addClass(LoggerProducer.class)
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveLoggerProducerReferences extends Recipe {

    private static final String LOGGER_PRODUCER_SIMPLE = "LoggerProducer";

    @Override
    public String getDisplayName() {
        return "Remove LoggerProducer references";
    }

    @Override
    public String getDescription() {
        return "Removes references to LoggerProducer class which was deleted during migration. " +
               "Cleans up test classes that reference the deleted class.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new LoggerProducerReferenceVisitor();
    }

    private static class LoggerProducerReferenceVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);

            // Remove LoggerProducer imports
            List<J.Import> newImports = new ArrayList<>();
            boolean changed = false;
            for (J.Import imp : c.getImports()) {
                String importName = imp.getQualid().toString();
                if (importName.endsWith(".LoggerProducer") ||
                    importName.endsWith(".logging.LoggerProducer")) {
                    changed = true;
                    // Skip this import
                } else {
                    newImports.add(imp);
                }
            }

            if (changed) {
                c = c.withImports(newImports);
            }

            return c;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation m = super.visitMethodInvocation(method, ctx);

            // Check for .addClass(LoggerProducer.class) pattern
            if ("addClass".equals(m.getSimpleName()) && m.getArguments().size() == 1) {
                Expression arg = m.getArguments().get(0);
                if (isLoggerProducerClassRef(arg)) {
                    // Return the select (the object before .addClass) to remove this call
                    // e.g., war.addPackage(...).addClass(LoggerProducer.class) -> war.addPackage(...)
                    if (m.getSelect() != null) {
                        return (J.MethodInvocation) m.getSelect();
                    }
                }
            }

            return m;
        }

        private boolean isLoggerProducerClassRef(Expression expr) {
            if (expr instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) expr;
                if ("class".equals(fa.getSimpleName())) {
                    Expression target = fa.getTarget();
                    if (target instanceof J.Identifier) {
                        return LOGGER_PRODUCER_SIMPLE.equals(((J.Identifier) target).getSimpleName());
                    }
                }
            }
            return false;
        }
    }
}
