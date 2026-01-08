package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

/**
 * GAP-JNDI-001: Migrates programmatic JNDI lookups to Spring equivalents.
 * <p>
 * Transformations:
 * <ul>
 *   <li>{@code new InitialContext().lookup("literal")} with known type → field assignment with @NeedsReview</li>
 *   <li>{@code ctx.lookup("literal")} where ctx is Context variable → same transformation</li>
 *   <li>Dynamic lookup names (variables) → @NeedsReview marker with ApplicationContext hint</li>
 * </ul>
 * <p>
 * Known JNDI name mappings:
 * <ul>
 *   <li>{@code java:comp/EJBContext} → Use GAP-CTX-001 (SessionContext migration)</li>
 *   <li>{@code java:jboss/mail/*} → Spring Mail configuration hint</li>
 *   <li>{@code java:comp/env/*} → @Value property hint</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateInitialContextLookup extends Recipe {

    private static final String INITIAL_CONTEXT_JAKARTA = "jakarta.naming.InitialContext";
    private static final String INITIAL_CONTEXT_JAVAX = "javax.naming.InitialContext";
    private static final String CONTEXT_JAKARTA = "jakarta.naming.Context";
    private static final String CONTEXT_JAVAX = "javax.naming.Context";

    @Override
    public String getDisplayName() {
        return "Migrate InitialContext.lookup() to Spring";
    }

    @Override
    public String getDescription() {
        return "Transforms programmatic JNDI lookups (InitialContext.lookup, Context.lookup) to Spring equivalents. " +
               "String literals are mapped to @NeedsReview with configuration hints. " +
               "Dynamic lookups are marked for manual migration with ApplicationContext.getBean() suggestion.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(INITIAL_CONTEXT_JAKARTA, false),
                new UsesType<>(INITIAL_CONTEXT_JAVAX, false),
                new UsesType<>(CONTEXT_JAKARTA, false),
                new UsesType<>(CONTEXT_JAVAX, false)
            ),
            new InitialContextLookupVisitor()
        );
    }

    private class InitialContextLookupVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation methodInvocation, ExecutionContext ctx) {
            J.MethodInvocation mi = super.visitMethodInvocation(methodInvocation, ctx);

            // Check if this is a lookup() call
            if (!"lookup".equals(mi.getSimpleName())) {
                return mi;
            }

            // Check if it's on InitialContext or Context
            if (!isContextLookup(mi)) {
                return mi;
            }

            // Get the lookup argument
            List<Expression> args = mi.getArguments();
            if (args == null || args.isEmpty()) {
                return mi;
            }

            Expression lookupArg = args.get(0);
            String lookupName = extractStringLiteral(lookupArg);

            if (lookupName != null) {
                // String literal lookup - add inline comment with migration guidance
                return addLookupComment(mi, lookupName);
            } else {
                // Dynamic lookup - add comment for manual migration
                return addDynamicLookupComment(mi);
            }
        }

        // Note: We intentionally do NOT remove InitialContext imports because
        // the lookup call remains in the code (only a comment is added).
        // Removing the import would cause compile errors.

        private boolean isContextLookup(J.MethodInvocation mi) {
            Expression select = mi.getSelect();
            if (select == null) {
                return false;
            }

            // Check for new InitialContext().lookup(...)
            if (select instanceof J.NewClass) {
                J.NewClass newClass = (J.NewClass) select;
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(newClass.getType());
                if (type != null) {
                    String fqn = type.getFullyQualifiedName();
                    return INITIAL_CONTEXT_JAKARTA.equals(fqn) || INITIAL_CONTEXT_JAVAX.equals(fqn);
                }
                // Fallback to clazz name check
                if (newClass.getClazz() != null) {
                    String typeName = newClass.getClazz().toString();
                    return typeName.contains("InitialContext");
                }
            }

            // Check for ctx.lookup(...) where ctx is Context variable (type-based only)
            if (select.getType() != null) {
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(select.getType());
                if (type != null) {
                    String fqn = type.getFullyQualifiedName();
                    return INITIAL_CONTEXT_JAKARTA.equals(fqn) ||
                           INITIAL_CONTEXT_JAVAX.equals(fqn) ||
                           CONTEXT_JAKARTA.equals(fqn) ||
                           CONTEXT_JAVAX.equals(fqn);
                }
            }

            // Note: We intentionally do NOT use name-based heuristics (ctx, ic, context)
            // to avoid false positives on non-JNDI lookup() calls.
            return false;
        }

        private String extractStringLiteral(Expression expr) {
            if (expr instanceof J.Literal) {
                J.Literal literal = (J.Literal) expr;
                if (literal.getValue() instanceof String) {
                    return (String) literal.getValue();
                }
            }
            return null;
        }

        private boolean hasNeedsReviewComment(J.MethodInvocation mi) {
            for (Comment comment : mi.getPrefix().getComments()) {
                if (comment instanceof TextComment) {
                    String text = ((TextComment) comment).getText();
                    if (text.contains("@NeedsReview")) {
                        return true;
                    }
                }
            }
            return false;
        }

        private J.MethodInvocation addLookupComment(J.MethodInvocation mi, String lookupName) {
            // Check if comment already exists (idempotency)
            if (hasNeedsReviewComment(mi)) {
                return mi;
            }

            // Determine the migration hint based on JNDI name pattern
            String hint = getMigrationHint(lookupName);

            // Build comment text
            String commentText = String.format(
                " @NeedsReview: JNDI lookup '%s' → %s ",
                lookupName.replace("*/", "* /"),
                hint
            );

            // Prepend comment to the method invocation
            List<Comment> existingComments = mi.getPrefix().getComments();
            List<Comment> newComments = new ArrayList<>(existingComments);
            // Use suffix " " to add space after comment
            newComments.add(0, new TextComment(true, commentText, " ", Markers.EMPTY));

            Space newPrefix = mi.getPrefix().withComments(newComments);

            return mi.withPrefix(newPrefix);
        }

        private J.MethodInvocation addDynamicLookupComment(J.MethodInvocation mi) {
            // Check if comment already exists (idempotency)
            if (hasNeedsReviewComment(mi)) {
                return mi;
            }

            String commentText = " @NeedsReview: Dynamic JNDI lookup → ApplicationContext.getBean() or @Autowired ";

            List<Comment> existingComments = mi.getPrefix().getComments();
            List<Comment> newComments = new ArrayList<>(existingComments);
            // Use suffix " " to add space after comment
            newComments.add(0, new TextComment(true, commentText, " ", Markers.EMPTY));

            Space newPrefix = mi.getPrefix().withComments(newComments);

            return mi.withPrefix(newPrefix);
        }

        private String getMigrationHint(String jndiName) {
            // EJB Context lookups
            if (jndiName.contains("EJBContext") || jndiName.contains("SessionContext")) {
                return "See GAP-CTX-001 (SessionContext migration)";
            }

            // Mail session lookups
            if (jndiName.contains("/mail/") || jndiName.contains("Mail")) {
                return "spring.mail.* properties + JavaMailSender bean";
            }

            // Environment entries
            if (jndiName.startsWith("java:comp/env/")) {
                String envName = jndiName.substring("java:comp/env/".length());
                return "@Value(\"${" + envName.replace("/", ".") + "}\")";
            }

            // DataSource lookups
            if (jndiName.contains("DataSource") || jndiName.contains("/jdbc/")) {
                return "@Autowired DataSource or spring.datasource.* properties";
            }

            // JMS lookups
            if (jndiName.contains("/jms/") || jndiName.contains("ConnectionFactory") || jndiName.contains("Queue") || jndiName.contains("Topic")) {
                return "@Autowired JmsTemplate or spring.jms.* properties";
            }

            // EJB lookups
            if (jndiName.contains("java:global/") || jndiName.contains("java:app/") || jndiName.contains("java:module/")) {
                return "@Autowired + @Qualifier or ApplicationContext.getBean()";
            }

            // Generic fallback
            return "@Autowired or ApplicationContext.getBean()";
        }
    }
}
