package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.J;

/**
 * Removes JBoss-specific @SecurityDomain annotation from EJB beans.
 * <p>
 * This annotation is used in JBoss/WildFly to associate a bean with a JAAS security domain.
 * In Spring Boot, security is configured via Spring Security and doesn't use this annotation.
 * <p>
 * Example transformation:
 * <pre>
 * Before:
 * {@code @SecurityDomain("java:/jaas/JusRealm")}
 * {@code @Stateless}
 * public class FooService { }
 *
 * After:
 * {@code @Stateless}
 * public class FooService { }
 * </pre>
 * <p>
 * Note: Spring Security configuration must be set up separately.
 * This recipe only removes the JBoss-specific annotation.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveJBossSecurityDomain extends Recipe {

    private static final String JBOSS_SECURITY_DOMAIN = "org.jboss.ejb3.annotation.SecurityDomain";

    @Override
    public String getDisplayName() {
        return "Remove @SecurityDomain annotations";
    }

    @Override
    public String getDescription() {
        return "Removes JBoss @SecurityDomain annotations from EJB beans. " +
               "Spring Boot uses Spring Security for authentication/authorization instead.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new RemoveSecurityDomainVisitor();
    }

    private static class RemoveSecurityDomainVisitor extends JavaIsoVisitor<ExecutionContext> {

        private static final AnnotationMatcher SECURITY_DOMAIN_MATCHER =
            new AnnotationMatcher("@" + JBOSS_SECURITY_DOMAIN);
        private static final String SECURITY_DOMAIN_SIMPLE_NAME = "SecurityDomain";

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);

            if (SECURITY_DOMAIN_MATCHER.matches(a)) {
                maybeRemoveImport(JBOSS_SECURITY_DOMAIN);
                //noinspection DataFlowIssue
                return null; // Remove the annotation
            }

            // Fallback: match by simple name when type is unresolved (e.g., JBoss provided-only)
            if (isUnresolvedSecurityDomain(a)) {
                maybeRemoveImport(JBOSS_SECURITY_DOMAIN);
                //noinspection DataFlowIssue
                return null; // Remove the annotation
            }

            return a;
        }

        /**
         * Checks if the annotation is an unresolved @SecurityDomain.
         * This handles the case when JBoss annotations are provided-only and not on the parser classpath.
         */
        private boolean isUnresolvedSecurityDomain(J.Annotation annotation) {
            // Check if type is unresolved (null type)
            if (annotation.getType() != null) {
                return false;
            }

            // Check simple name
            if (annotation.getAnnotationType() instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) annotation.getAnnotationType();
                return SECURITY_DOMAIN_SIMPLE_NAME.equals(ident.getSimpleName());
            }
            // Handle fully qualified name in source (org.jboss.ejb3.annotation.SecurityDomain)
            if (annotation.getAnnotationType() instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) annotation.getAnnotationType();
                return SECURITY_DOMAIN_SIMPLE_NAME.equals(fa.getSimpleName()) &&
                       fa.toString().contains("jboss");
            }

            return false;
        }
    }
}
