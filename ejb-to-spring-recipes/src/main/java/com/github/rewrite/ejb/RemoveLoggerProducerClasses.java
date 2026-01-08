package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

/**
 * Removes classes that ONLY produce Logger via CDI InjectionPoint.
 * <p>
 * This is a GENERIC solution that replaces project-specific file deletion rules.
 * Instead of relying on file names like "LoggerProducer.java", this recipe:
 * <ol>
 *   <li>Finds classes with @Produces methods that return Logger</li>
 *   <li>Checks if the method has an InjectionPoint parameter</li>
 *   <li>If the class ONLY contains such producer methods (no other business logic), deletes it</li>
 *   <li>If the class has other members, it is kept for manual review</li>
 * </ol>
 * <p>
 * Logger injection in Spring should use:
 * - {@code private static final Logger LOGGER = LoggerFactory.getLogger(MyClass.class);}
 * - Or Lombok's {@code @Slf4j} annotation
 * <p>
 * Use {@link MigrateLoggerInjectionToFactory} to convert injected loggers to factory pattern.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveLoggerProducerClasses extends Recipe {

    private static final String PRODUCES_FQN = "jakarta.enterprise.inject.Produces";
    private static final String INJECTION_POINT_FQN = "jakarta.enterprise.inject.spi.InjectionPoint";

    @Override
    public String getDisplayName() {
        return "Remove Logger Producer Classes";
    }

    @Override
    public String getDescription() {
        return "Removes classes that only exist to produce Logger instances via CDI InjectionPoint. " +
               "These classes have no Spring equivalent - use LoggerFactory.getLogger() or Lombok @Slf4j instead.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.and(
                new UsesType<>(PRODUCES_FQN, false),
                new UsesType<>(INJECTION_POINT_FQN, false)
            ),
            new LoggerProducerRemover()
        );
    }

    private static class LoggerProducerRemover extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            // Find the primary class
            for (J.ClassDeclaration classDecl : cu.getClasses()) {
                if (isLoggerOnlyProducerClass(classDecl)) {
                    // This class only produces loggers - delete entire file
                    //noinspection DataFlowIssue
                    return null;
                }
            }

            return super.visitCompilationUnit(cu, ctx);
        }

        /**
         * Checks if a class ONLY exists to produce Logger instances via InjectionPoint.
         * A class is considered a "Logger-only producer" if:
         * 1. It has at least one method with @Produces that returns Logger AND has InjectionPoint param
         * 2. All non-constructor methods are such producer methods
         * 3. The class has no fields (other than maybe constants)
         */
        private boolean isLoggerOnlyProducerClass(J.ClassDeclaration classDecl) {
            int loggerProducerMethods = 0;
            int otherMethods = 0;
            int instanceFields = 0;

            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration method = (J.MethodDeclaration) stmt;

                    // Skip constructors
                    if (method.isConstructor()) {
                        continue;
                    }

                    if (isLoggerProducerMethod(method)) {
                        loggerProducerMethods++;
                    } else {
                        otherMethods++;
                    }
                } else if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vars = (J.VariableDeclarations) stmt;
                    // Skip static final fields (constants)
                    boolean isStatic = vars.hasModifier(J.Modifier.Type.Static);
                    boolean isFinal = vars.hasModifier(J.Modifier.Type.Final);
                    if (!(isStatic && isFinal)) {
                        instanceFields++;
                    }
                }
            }

            // Class is a logger-only producer if:
            // - Has at least one logger producer method
            // - Has no other methods
            // - Has no instance fields
            return loggerProducerMethods > 0 && otherMethods == 0 && instanceFields == 0;
        }

        /**
         * Checks if a method is a Logger producer with InjectionPoint parameter.
         */
        private boolean isLoggerProducerMethod(J.MethodDeclaration method) {
            // Check for @Produces annotation
            boolean hasProduces = method.getLeadingAnnotations().stream()
                .anyMatch(a -> TypeUtils.isOfClassType(a.getType(), PRODUCES_FQN) ||
                              "Produces".equals(a.getSimpleName()));

            if (!hasProduces) {
                return false;
            }

            // Check return type is Logger
            TypeTree returnType = method.getReturnTypeExpression();
            boolean returnsLogger = false;
            if (returnType instanceof J.Identifier) {
                String typeName = ((J.Identifier) returnType).getSimpleName();
                returnsLogger = "Logger".equals(typeName);
            } else if (returnType != null && returnType.getType() != null) {
                String fqn = returnType.getType().toString();
                returnsLogger = fqn.contains("Logger");
            }

            if (!returnsLogger) {
                return false;
            }

            // Check for InjectionPoint parameter
            boolean hasInjectionPoint = method.getParameters().stream()
                .filter(p -> p instanceof J.VariableDeclarations)
                .map(p -> (J.VariableDeclarations) p)
                .anyMatch(vd -> {
                    TypeTree typeExpr = vd.getTypeExpression();
                    if (typeExpr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) typeExpr;
                        return TypeUtils.isOfClassType(ident.getType(), INJECTION_POINT_FQN) ||
                               "InjectionPoint".equals(ident.getSimpleName());
                    }
                    return false;
                });

            return hasInjectionPoint;
        }
    }
}
