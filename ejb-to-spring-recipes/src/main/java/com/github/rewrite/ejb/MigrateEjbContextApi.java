package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.JavaVisitor;
import org.openrewrite.java.RemoveImport;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * GAP-CTX-001: Migrates EJB SessionContext/EJBContext API to Spring equivalents.
 * <p>
 * Transformations:
 * <ul>
 *   <li>{@code setRollbackOnly()} → {@code TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()}</li>
 *   <li>{@code getCallerPrincipal()} → {@code SecurityContextHolder.getContext().getAuthentication()}</li>
 * </ul>
 * <p>
 * Unmigrated APIs (field and imports are preserved when these are present):
 * <ul>
 *   <li>{@code isCallerInRole(role)} → requires Spring Security setup</li>
 *   <li>{@code getBusinessObject(Type.class)} → requires ObjectProvider self-proxy</li>
 *   <li>{@code lookup(name)} → requires ApplicationContext</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEjbContextApi extends Recipe {

    @Option(displayName = "Security strategy",
            description = "Override security migration strategy. Valid values: keep-jakarta, spring-security. " +
                          "If not set, reads from project.yaml or defaults to keep-jakarta.",
            example = "spring-security",
            required = false)
    @Nullable
    String strategy;

    public MigrateEjbContextApi() {
        this.strategy = null;
    }

    public MigrateEjbContextApi(@Nullable String strategy) {
        this.strategy = strategy;
    }

    // EJB Context types
    private static final String SESSION_CONTEXT_JAKARTA = "jakarta.ejb.SessionContext";
    private static final String SESSION_CONTEXT_JAVAX = "javax.ejb.SessionContext";
    private static final String EJB_CONTEXT_JAKARTA = "jakarta.ejb.EJBContext";
    private static final String EJB_CONTEXT_JAVAX = "javax.ejb.EJBContext";

    // Spring types
    private static final String TRANSACTION_ASPECT_SUPPORT = "org.springframework.transaction.interceptor.TransactionAspectSupport";
    private static final String SECURITY_CONTEXT_HOLDER = "org.springframework.security.core.context.SecurityContextHolder";

    // Methods that are ALWAYS migrated (independent of strategy)
    private static final Set<String> ALWAYS_MIGRATED_METHODS = Set.of("setRollbackOnly", "getCallerPrincipal");

    // Methods that are migrated ONLY when spring-security strategy is enabled
    private static final Set<String> SECURITY_MIGRATABLE_METHODS = Set.of("isCallerInRole");

    // Spring Security GrantedAuthority type
    private static final String GRANTED_AUTHORITY = "org.springframework.security.core.GrantedAuthority";

    // Type stubs for JavaTemplate parser
    private static final String TRANSACTION_STATUS_STUB = """
        package org.springframework.transaction;
        public interface TransactionStatus {
            void setRollbackOnly();
        }
        """;

    private static final String TRANSACTION_ASPECT_SUPPORT_STUB = """
        package org.springframework.transaction.interceptor;
        import org.springframework.transaction.TransactionStatus;
        public abstract class TransactionAspectSupport {
            public static TransactionStatus currentTransactionStatus() { return null; }
        }
        """;

    private static final String AUTHENTICATION_STUB = """
        package org.springframework.security.core;
        public interface Authentication extends java.security.Principal {}
        """;

    private static final String SECURITY_CONTEXT_STUB = """
        package org.springframework.security.core.context;
        import org.springframework.security.core.Authentication;
        public interface SecurityContext {
            Authentication getAuthentication();
        }
        """;

    private static final String SECURITY_CONTEXT_HOLDER_STUB = """
        package org.springframework.security.core.context;
        public class SecurityContextHolder {
            public static SecurityContext getContext() { return null; }
        }
        """;

    private static final String GRANTED_AUTHORITY_STUB = """
        package org.springframework.security.core;
        public interface GrantedAuthority {
            String getAuthority();
        }
        """;

    private static final String AUTHENTICATION_WITH_AUTHORITIES_STUB = """
        package org.springframework.security.core;
        import java.util.Collection;
        public interface Authentication extends java.security.Principal {
            Collection<? extends GrantedAuthority> getAuthorities();
        }
        """;

    @Override
    public String getDisplayName() {
        return "Migrate EJB SessionContext/EJBContext API to Spring";
    }

    @Override
    public String getDescription() {
        return "Transforms EJB SessionContext/EJBContext API calls to Spring equivalents. " +
               "setRollbackOnly() uses TransactionAspectSupport, getCallerPrincipal() uses SecurityContextHolder. " +
               "Unmigrated methods (isCallerInRole, getBusinessObject, lookup) preserve the context field.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(SESSION_CONTEXT_JAKARTA, false),
                new UsesType<>(SESSION_CONTEXT_JAVAX, false),
                new UsesType<>(EJB_CONTEXT_JAKARTA, false),
                new UsesType<>(EJB_CONTEXT_JAVAX, false)
            ),
            new EjbContextApiVisitor()
        );
    }

    /**
     * Checks if spring-security strategy should be used.
     * Respects strategy override from recipe option, then project.yaml, then defaults.
     */
    private boolean isSpringSecurityStrategy(Path sourcePath) {
        // Check for recipe option override
        ProjectConfiguration.SecurityStrategy override = ProjectConfiguration.SecurityStrategy.fromString(strategy);
        if (strategy != null && override == null) {
            System.err.println("Warning: Unknown security strategy override '" + strategy +
                    "', using project.yaml/defaults.");
        }
        if (override != null) {
            return override == ProjectConfiguration.SecurityStrategy.SPRING_SECURITY;
        }
        if (sourcePath == null) {
            return ProjectConfiguration.mavenDefaults().getSecurityStrategy()
                    == ProjectConfiguration.SecurityStrategy.SPRING_SECURITY;
        }
        ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(sourcePath));
        return config.isSpringSecurityStrategy();
    }

    private static Path extractProjectRoot(Path sourcePath) {
        // Find the project root by looking for common markers
        Path current = sourcePath;
        while (current != null) {
            if (current.resolve("pom.xml").toFile().exists() ||
                current.resolve("build.gradle").toFile().exists() ||
                current.resolve("build.gradle.kts").toFile().exists()) {
                return current;
            }
            current = current.getParent();
        }
        return sourcePath.getParent();
    }

    private class EjbContextApiVisitor extends JavaVisitor<ExecutionContext> {

        // Templates with type stubs for proper type information
        private final JavaTemplate setRollbackOnlyTemplate = JavaTemplate.builder(
                "TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()")
            .imports(TRANSACTION_ASPECT_SUPPORT)
            .javaParser(JavaParser.fromJavaVersion().dependsOn(
                TRANSACTION_STATUS_STUB,
                TRANSACTION_ASPECT_SUPPORT_STUB))
            .build();

        private final JavaTemplate getCallerPrincipalTemplate = JavaTemplate.builder(
                "SecurityContextHolder.getContext().getAuthentication()")
            .imports(SECURITY_CONTEXT_HOLDER)
            .javaParser(JavaParser.fromJavaVersion().dependsOn(
                AUTHENTICATION_STUB,
                SECURITY_CONTEXT_STUB,
                SECURITY_CONTEXT_HOLDER_STUB))
            .build();

        // Track context field names in current class
        private Set<String> contextFieldNames = new HashSet<>();
        // Track if class has unmigrated API calls
        private boolean hasUnmigratedCalls = false;
        // Track if spring-security strategy is enabled (checked once per source file)
        private boolean springSecurityEnabled = false;

        @Override
        public J visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // Find all SessionContext/EJBContext fields
            contextFieldNames.clear();
            hasUnmigratedCalls = false;

            // Check security strategy based on source file path
            Path sourcePath = getCursor().firstEnclosingOrThrow(SourceFile.class).getSourcePath();
            springSecurityEnabled = isSpringSecurityStrategy(sourcePath != null ? Paths.get(".").resolve(sourcePath) : null);

            classDecl.getBody().getStatements().stream()
                .filter(s -> s instanceof J.VariableDeclarations)
                .map(s -> (J.VariableDeclarations) s)
                .filter(this::isContextField)
                .forEach(vd -> vd.getVariables().forEach(v -> contextFieldNames.add(v.getSimpleName())));

            if (contextFieldNames.isEmpty()) {
                return super.visitClassDeclaration(classDecl, ctx);
            }

            // Pre-scan for unmigrated API calls
            hasUnmigratedCalls = classHasUnmigratedContextCalls(classDecl);

            J.ClassDeclaration cd = (J.ClassDeclaration) super.visitClassDeclaration(classDecl, ctx);

            // Only remove fields and imports if ALL context API calls are migrated
            if (!hasUnmigratedCalls) {
                // Remove SessionContext/EJBContext fields
                List<Statement> newStatements = cd.getBody().getStatements().stream()
                    .filter(s -> {
                        if (s instanceof J.VariableDeclarations) {
                            J.VariableDeclarations vd = (J.VariableDeclarations) s;
                            return !isContextField(vd);
                        }
                        return true;
                    })
                    .collect(Collectors.toList());

                cd = cd.withBody(cd.getBody().withStatements(newStatements));

                // Schedule import removal only when field is removed
                doAfterVisit(new RemoveImport<>(SESSION_CONTEXT_JAKARTA, true));
                doAfterVisit(new RemoveImport<>(SESSION_CONTEXT_JAVAX, true));
                doAfterVisit(new RemoveImport<>(EJB_CONTEXT_JAKARTA, true));
                doAfterVisit(new RemoveImport<>(EJB_CONTEXT_JAVAX, true));
                doAfterVisit(new RemoveImport<>("jakarta.annotation.Resource", true));
                doAfterVisit(new RemoveImport<>("javax.annotation.Resource", true));
            }

            return cd;
        }

        @Override
        public J visitMethodInvocation(J.MethodInvocation methodInvocation, ExecutionContext ctx) {
            J.MethodInvocation mi = (J.MethodInvocation) super.visitMethodInvocation(methodInvocation, ctx);

            // Check if this is a call on a SessionContext/EJBContext field
            if (!isContextCall(mi)) {
                return mi;
            }

            String methodName = mi.getSimpleName();

            switch (methodName) {
                case "setRollbackOnly":
                    doAfterVisit(new AddImport<>(TRANSACTION_ASPECT_SUPPORT, null, false));
                    return setRollbackOnlyTemplate.apply(getCursor(), mi.getCoordinates().replace());
                case "getCallerPrincipal":
                    doAfterVisit(new AddImport<>(SECURITY_CONTEXT_HOLDER, null, false));
                    return getCallerPrincipalTemplate.apply(getCursor(), mi.getCoordinates().replace());
                case "isCallerInRole":
                    if (springSecurityEnabled) {
                        // Migrate to Spring Security authorities check
                        // ctx.isCallerInRole("ROLE") -> SecurityContextHolder.getContext().getAuthentication()
                        //     .getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ROLE"))
                        return migrateIsCallerInRole(mi);
                    }
                    // Keep for manual migration when spring-security strategy not enabled
                    return mi;
                case "getBusinessObject":
                case "lookup":
                    // Keep these calls - they require manual migration
                    // Field and imports are preserved (see visitClassDeclaration)
                    return mi;
                default:
                    return mi;
            }
        }

        /**
         * Pre-scan class for unmigrated context API calls.
         * If any are found, the context field must be preserved.
         * <p>
         * When spring-security strategy is enabled, isCallerInRole is not considered unmigrated.
         */
        private boolean classHasUnmigratedContextCalls(J.ClassDeclaration classDecl) {
            AtomicBoolean found = new AtomicBoolean(false);

            new JavaVisitor<AtomicBoolean>() {
                @Override
                public J visitMethodInvocation(J.MethodInvocation mi, AtomicBoolean state) {
                    if (isContextCallOnField(mi) && isUnmigratedMethod(mi.getSimpleName())) {
                        state.set(true);
                    }
                    return super.visitMethodInvocation(mi, state);
                }

                private boolean isUnmigratedMethod(String methodName) {
                    // Methods that are always migrated (setRollbackOnly, getCallerPrincipal)
                    if (ALWAYS_MIGRATED_METHODS.contains(methodName)) {
                        return false;
                    }
                    // Security methods (isCallerInRole) are migrated only when spring-security is enabled
                    if (SECURITY_MIGRATABLE_METHODS.contains(methodName)) {
                        return !springSecurityEnabled;
                    }
                    // Any other method (getBusinessObject, lookup, getTimerService, getEJBHome, etc.)
                    // is NOT migrated, so the field must be preserved
                    return true;
                }

                private boolean isContextCallOnField(J.MethodInvocation mi) {
                    if (mi.getSelect() instanceof J.Identifier) {
                        String name = ((J.Identifier) mi.getSelect()).getSimpleName();
                        return contextFieldNames.contains(name);
                    }
                    if (mi.getSelect() != null && mi.getSelect().getType() != null) {
                        String typeName = mi.getSelect().getType().toString();
                        return typeName.contains("SessionContext") || typeName.contains("EJBContext");
                    }
                    return false;
                }
            }.visit(classDecl, found);

            return found.get();
        }

        private boolean isContextField(J.VariableDeclarations vd) {
            if (vd.getType() == null) return false;
            String typeName = vd.getType().toString();
            return typeName.contains("SessionContext") || typeName.contains("EJBContext");
        }

        private boolean isContextCall(J.MethodInvocation mi) {
            if (mi.getSelect() instanceof J.Identifier) {
                String name = ((J.Identifier) mi.getSelect()).getSimpleName();
                return contextFieldNames.contains(name);
            }
            // Also check type if available
            if (mi.getSelect() != null && mi.getSelect().getType() != null) {
                String typeName = mi.getSelect().getType().toString();
                return typeName.contains("SessionContext") || typeName.contains("EJBContext");
            }
            return false;
        }

        /**
         * Migrates isCallerInRole("ROLE") to Spring Security authorities check.
         * <p>
         * Transforms: ctx.isCallerInRole("ADMIN")
         * To: SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
         *         .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))
         */
        private J migrateIsCallerInRole(J.MethodInvocation mi) {
            // Extract the role argument
            if (mi.getArguments().isEmpty()) {
                return mi; // Invalid call, keep as-is
            }
            Expression roleArg = mi.getArguments().get(0);

            doAfterVisit(new AddImport<>(SECURITY_CONTEXT_HOLDER, null, false));

            // For literal strings, we can inline the ROLE_ prefix
            if (roleArg instanceof J.Literal) {
                J.Literal literal = (J.Literal) roleArg;
                if (literal.getValue() != null) {
                    String roleString = "\"ROLE_" + literal.getValue().toString() + "\"";
                    JavaTemplate literalTemplate = JavaTemplate.builder(
                            "SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()" +
                            ".anyMatch(a -> a.getAuthority().equals(" + roleString + "))")
                        .imports(SECURITY_CONTEXT_HOLDER)
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(
                            GRANTED_AUTHORITY_STUB,
                            AUTHENTICATION_WITH_AUTHORITIES_STUB,
                            SECURITY_CONTEXT_STUB,
                            SECURITY_CONTEXT_HOLDER_STUB))
                        .build();
                    return literalTemplate.apply(getCursor(), mi.getCoordinates().replace());
                }
            }

            // For non-literal expressions, use template substitution with #{any(String)}
            JavaTemplate dynamicTemplate = JavaTemplate.builder(
                    "SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()" +
                    ".anyMatch(a -> a.getAuthority().equals(\"ROLE_\" + #{any(String)}))")
                .imports(SECURITY_CONTEXT_HOLDER)
                .javaParser(JavaParser.fromJavaVersion().dependsOn(
                    GRANTED_AUTHORITY_STUB,
                    AUTHENTICATION_WITH_AUTHORITIES_STUB,
                    SECURITY_CONTEXT_STUB,
                    SECURITY_CONTEXT_HOLDER_STUB))
                .build();
            return dynamicTemplate.apply(getCursor(), mi.getCoordinates().replace(), roleArg);
        }
    }
}
