package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.*;

/**
 * Removes CDI Logger-Producer classes AND cleans up all references to them.
 * <p>
 * This is a GENERIC solution that:
 * <ol>
 *   <li>SCAN: Finds classes that ONLY produce Logger via @Produces + InjectionPoint</li>
 *   <li>SCAN: Collects their FQN and SimpleName for reference cleanup</li>
 *   <li>EDIT: Deletes the producer classes (returns null)</li>
 *   <li>EDIT: Removes imports for deleted classes</li>
 *   <li>EDIT: Removes .addClass(DeletedClass.class) method calls</li>
 * </ol>
 * <p>
 * This works with ANY logger producer class name (LoggerProducer, TraceProducer, MyLoggerFactory, etc.)
 * because it identifies producer classes by their CODE PATTERN, not by name.
 * <p>
 * Replaces the combination of RemoveLoggerProducerClasses + RemoveLoggerProducerReferences.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveLoggerProducerCompletely extends ScanningRecipe<RemoveLoggerProducerCompletely.Accumulator> {

    private static final String PRODUCES_FQN = "jakarta.enterprise.inject.Produces";
    private static final String INJECTION_POINT_FQN = "jakarta.enterprise.inject.spi.InjectionPoint";

    @Override
    public String getDisplayName() {
        return "Remove Logger Producer Classes and References";
    }

    @Override
    public String getDescription() {
        return "Removes CDI Logger-Producer classes (identified by @Produces Logger with InjectionPoint) " +
               "and cleans up all references to them. Works with any class name, not just 'LoggerProducer'.";
    }

    /**
     * Accumulator to collect information about logger producer classes during scan phase.
     */
    static class Accumulator {
        /**
         * Fully qualified names of logger producer classes (e.g., "org.example.logging.LoggerProducer")
         */
        Set<String> loggerProducerFqns = new HashSet<>();

        /**
         * Simple names of logger producer classes (e.g., "LoggerProducer")
         */
        Set<String> loggerProducerSimpleNames = new HashSet<>();

        /**
         * Source paths of logger producer classes (for deletion)
         */
        Set<String> loggerProducerSourcePaths = new HashSet<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return Preconditions.check(
            Preconditions.and(
                new UsesType<>(PRODUCES_FQN, false),
                new UsesType<>(INJECTION_POINT_FQN, false)
            ),
            new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                    String sourcePath = cu.getSourcePath().toString();

                    for (J.ClassDeclaration classDecl : cu.getClasses()) {
                        if (isLoggerOnlyProducerClass(classDecl)) {
                            // Collect the class info
                            acc.loggerProducerSimpleNames.add(classDecl.getSimpleName());
                            acc.loggerProducerSourcePaths.add(sourcePath);

                            // Determine FQN from package + class name
                            String fqn = determineFqn(cu, classDecl);
                            if (fqn != null) {
                                acc.loggerProducerFqns.add(fqn);
                            }
                        }
                    }

                    return cu;
                }

                private String determineFqn(J.CompilationUnit cu, J.ClassDeclaration classDecl) {
                    if (cu.getPackageDeclaration() != null) {
                        String pkg = cu.getPackageDeclaration().getExpression().toString();
                        return pkg + "." + classDecl.getSimpleName();
                    }
                    return classDecl.getSimpleName();
                }
            }
        );
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // If no logger producers found, nothing to do
        if (acc.loggerProducerSimpleNames.isEmpty()) {
            return TreeVisitor.noop();
        }

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                String sourcePath = cu.getSourcePath().toString();

                // Delete the logger producer class files
                if (acc.loggerProducerSourcePaths.contains(sourcePath)) {
                    // Verify it's still a logger producer (in case of multi-pass)
                    for (J.ClassDeclaration classDecl : cu.getClasses()) {
                        if (isLoggerOnlyProducerClass(classDecl)) {
                            //noinspection DataFlowIssue
                            return null; // Delete file
                        }
                    }
                }

                // Remove imports for deleted classes
                J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);
                c = removeLoggerProducerImports(c, acc);

                return c;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);

                // Check for .addClass(DeletedProducer.class) pattern in method chains
                if ("addClass".equals(m.getSimpleName()) && m.getArguments().size() == 1) {
                    Expression arg = m.getArguments().get(0);
                    if (isDeletedProducerClassRef(arg, acc)) {
                        // Method chain case: war.addPackage(...).addClass(LoggerProducer.class)
                        // -> war.addPackage(...)
                        if (m.getSelect() instanceof J.MethodInvocation) {
                            // Preserve whitespace prefix when removing from chain
                            return ((J.MethodInvocation) m.getSelect()).withPrefix(m.getPrefix());
                        }
                        // For non-chain case (archive.addClass), handled by visitBlock
                    }
                }

                return m;
            }

            @Override
            public J.Block visitBlock(J.Block block, ExecutionContext ctx) {
                J.Block b = super.visitBlock(block, ctx);

                // Remove standalone statements like: archive.addClass(LoggerProducer.class);
                List<Statement> newStatements = new ArrayList<>();
                boolean modified = false;

                for (Statement stmt : b.getStatements()) {
                    if (isAddClassStatementToRemove(stmt, acc)) {
                        modified = true;
                        // Skip this statement (remove it)
                    } else {
                        newStatements.add(stmt);
                    }
                }

                if (modified) {
                    return b.withStatements(newStatements);
                }
                return b;
            }

            /**
             * Checks if a statement is a standalone addClass(DeletedProducer.class) call
             * that should be removed (not part of a method chain).
             */
            private boolean isAddClassStatementToRemove(Statement stmt, Accumulator acc) {
                if (!(stmt instanceof J.MethodInvocation)) {
                    return false;
                }
                J.MethodInvocation mi = (J.MethodInvocation) stmt;

                if (!"addClass".equals(mi.getSimpleName()) || mi.getArguments().size() != 1) {
                    return false;
                }

                // Only remove if not part of a chain (select is Identifier, not MethodInvocation)
                if (mi.getSelect() instanceof J.MethodInvocation) {
                    return false; // Chain case handled by visitMethodInvocation
                }

                Expression arg = mi.getArguments().get(0);
                return isDeletedProducerClassRef(arg, acc);
            }

            private J.CompilationUnit removeLoggerProducerImports(J.CompilationUnit cu, Accumulator acc) {
                List<J.Import> newImports = new ArrayList<>();
                boolean changed = false;

                for (J.Import imp : cu.getImports()) {
                    String importName = imp.getQualid().toString();
                    boolean shouldRemove = false;

                    // Check against collected FQNs
                    for (String fqn : acc.loggerProducerFqns) {
                        if (importName.equals(fqn)) {
                            shouldRemove = true;
                            break;
                        }
                    }

                    // Also check by simple name pattern (for wildcard or partial matches)
                    if (!shouldRemove) {
                        for (String simpleName : acc.loggerProducerSimpleNames) {
                            if (importName.endsWith("." + simpleName)) {
                                shouldRemove = true;
                                break;
                            }
                        }
                    }

                    if (shouldRemove) {
                        changed = true;
                    } else {
                        newImports.add(imp);
                    }
                }

                if (changed) {
                    return cu.withImports(newImports);
                }
                return cu;
            }

            private boolean isDeletedProducerClassRef(Expression expr, Accumulator acc) {
                if (expr instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) expr;
                    if ("class".equals(fa.getSimpleName())) {
                        Expression target = fa.getTarget();
                        if (target instanceof J.Identifier) {
                            String name = ((J.Identifier) target).getSimpleName();
                            return acc.loggerProducerSimpleNames.contains(name);
                        }
                    }
                }
                return false;
            }
        };
    }

    // ============= Helper methods (same logic as RemoveLoggerProducerClasses) =============

    /**
     * Checks if a class ONLY exists to produce Logger instances via InjectionPoint.
     * <p>
     * Private methods are ALLOWED (they're internal implementation details, e.g., helper methods).
     * Only public/protected/package-private non-producer methods prevent deletion.
     */
    private static boolean isLoggerOnlyProducerClass(J.ClassDeclaration classDecl) {
        int loggerProducerMethods = 0;
        int nonPrivateOtherMethods = 0;
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
                    // Only count non-private methods as "other methods"
                    // Private methods are internal helpers and safe to delete with the class
                    boolean isPrivate = method.hasModifier(J.Modifier.Type.Private);
                    if (!isPrivate) {
                        nonPrivateOtherMethods++;
                    }
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

        return loggerProducerMethods > 0 && nonPrivateOtherMethods == 0 && instanceFields == 0;
    }

    /**
     * Checks if a method is a Logger producer with InjectionPoint parameter.
     */
    private static boolean isLoggerProducerMethod(J.MethodDeclaration method) {
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
        return method.getParameters().stream()
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
    }
}
