package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Migrates Arquillian tests to Spring Boot Test.
 * <p>
 * This recipe performs the following transformations:
 * <ul>
 *   <li>{@code @ExtendWith(ArquillianExtension.class)} → {@code @SpringBootTest}</li>
 *   <li>{@code @RunWith(Arquillian.class)} → {@code @SpringBootTest}</li>
 *   <li>Removes {@code @Deployment} methods (ShrinkWrap is not compatible with Spring Boot)</li>
 *   <li>Adds necessary Spring Boot Test imports</li>
 * </ul>
 * <p>
 * Limitations:
 * <ul>
 *   <li>ShrinkWrap deployment logic must be manually converted to Spring configuration</li>
 *   <li>Container-specific tests may need Testcontainers setup</li>
 *   <li>CDI-specific test features need manual review</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateArquillianTests extends Recipe {

    private static final String SPRING_BOOT_TEST = "org.springframework.boot.test.context.SpringBootTest";
    private static final String ARQUILLIAN_EXTENSION = "org.jboss.arquillian.junit5.ArquillianExtension";
    private static final String ARQUILLIAN_RUNNER = "org.jboss.arquillian.junit.Arquillian";
    private static final String EXTEND_WITH = "org.junit.jupiter.api.extension.ExtendWith";
    private static final String RUN_WITH = "org.junit.runner.RunWith";
    private static final String DEPLOYMENT = "org.jboss.arquillian.container.test.api.Deployment";

    @Override
    public String getDisplayName() {
        return "Migrate Arquillian tests to Spring Boot Test";
    }

    @Override
    public String getDescription() {
        return "Converts Arquillian test infrastructure to Spring Boot Test. " +
               "Replaces @ExtendWith(ArquillianExtension.class) with @SpringBootTest and removes @Deployment methods.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new ArquillianMigrationVisitor();
    }

    private class ArquillianMigrationVisitor extends JavaIsoVisitor<ExecutionContext> {
        private boolean isArquillianTest = false;

        // Pattern to match exactly "ArquillianExtension.class" with word boundary
        // Prevents false positives like "MyArquillianExtension.class"
        private static final Pattern ARQUILLIAN_EXTENSION_PATTERN =
            Pattern.compile("(?<![\\w$])ArquillianExtension\\.class(?![\\w$])");

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // Save previous state to handle nested classes correctly
            boolean prevIsArquillianTest = isArquillianTest;
            try {
                // Check if this is an Arquillian test class
                isArquillianTest = hasArquillianAnnotation(classDecl);

                // Always visit children to handle nested Arquillian test classes
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                if (!isArquillianTest) {
                    return cd;
                }

                // Transform class-level annotations
                List<J.Annotation> newAnnotations = new ArrayList<>();
                boolean addedSpringBootTest = false;
                boolean hasSpringBootTest = false;

                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    String simpleName = ann.getSimpleName();

                    // Check if @SpringBootTest already exists
                    if ("SpringBootTest".equals(simpleName)) {
                        hasSpringBootTest = true;
                        newAnnotations.add(ann);
                        continue;
                    }

                    // Handle @ExtendWith annotation
                    if ("ExtendWith".equals(simpleName) && hasArquillianExtensionArg(ann)) {
                        if (!addedSpringBootTest && !hasSpringBootTest) {
                            maybeAddImport(SPRING_BOOT_TEST);
                            newAnnotations.add(createSpringBootTestAnnotation(ann.getPrefix()));
                            addedSpringBootTest = true;
                        }

                        // Check if @ExtendWith has multiple extensions (array)
                        if (hasMultipleExtensions(ann)) {
                            // Remove ArquillianExtension.class from the array, keep others
                            J.Annotation filteredAnn = removeArquillianFromExtendWith(ann);
                            if (filteredAnn != null) {
                                // There are remaining extensions, keep the annotation
                                if (addedSpringBootTest) {
                                    // Adjust prefix for proper formatting after @SpringBootTest
                                    String originalWhitespace = ann.getPrefix().getWhitespace();
                                    String indentation = originalWhitespace.contains("\n")
                                        ? originalWhitespace.substring(originalWhitespace.lastIndexOf('\n'))
                                        : "\n";
                                    newAnnotations.add(filteredAnn.withPrefix(Space.format(indentation)));
                                } else {
                                    // @SpringBootTest already exists, use original prefix
                                    newAnnotations.add(filteredAnn.withPrefix(ann.getPrefix()));
                                }
                            }
                            // Always remove ArquillianExtension import when processing arrays
                            maybeRemoveImport(ARQUILLIAN_EXTENSION);
                            // Only remove ExtendWith import if no other @ExtendWith remains
                            if (filteredAnn == null) {
                                maybeRemoveImport(EXTEND_WITH);
                            }
                        } else {
                            // Single extension - safe to remove entirely
                            maybeRemoveImport(ARQUILLIAN_EXTENSION);
                            maybeRemoveImport(EXTEND_WITH);
                        }
                        continue;
                    }

                    // Replace @RunWith(Arquillian.class) with @SpringBootTest
                    if ("RunWith".equals(simpleName) && hasArquillianRunnerArg(ann)) {
                        if (!addedSpringBootTest && !hasSpringBootTest) {
                            maybeAddImport(SPRING_BOOT_TEST);
                            newAnnotations.add(createSpringBootTestAnnotation(ann.getPrefix()));
                            addedSpringBootTest = true;
                        }
                        maybeRemoveImport(ARQUILLIAN_RUNNER);
                        maybeRemoveImport(RUN_WITH);
                        continue;
                    }

                    // Keep @NeedsReview and other annotations
                    newAnnotations.add(ann);
                }

                // Remove Arquillian imports (ArquillianExtension/ExtendWith handled in annotation loop)
                maybeRemoveImport("org.jboss.arquillian.junit.Arquillian");
                maybeRemoveImport("org.jboss.arquillian.container.test.api.Deployment");
                maybeRemoveImport("org.jboss.shrinkwrap.api.ShrinkWrap");
                maybeRemoveImport("org.jboss.shrinkwrap.api.spec.WebArchive");
                maybeRemoveImport("org.jboss.shrinkwrap.api.spec.JavaArchive");
                maybeRemoveImport("org.jboss.shrinkwrap.api.spec.EnterpriseArchive");

                return cd.withLeadingAnnotations(newAnnotations);
            } finally {
                // Restore previous state for nested class handling
                isArquillianTest = prevIsArquillianTest;
            }
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            if (!isArquillianTest) {
                return method;
            }

            // Remove @Deployment methods
            for (J.Annotation ann : method.getLeadingAnnotations()) {
                if ("Deployment".equals(ann.getSimpleName())) {
                    // Return null to remove the method
                    return null;
                }
            }

            return super.visitMethodDeclaration(method, ctx);
        }

        private boolean hasArquillianAnnotation(J.ClassDeclaration classDecl) {
            boolean hasSpringBootTest = false;
            boolean hasArquillianExtendWith = false;
            boolean hasArquillianRunWith = false;

            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                String simpleName = ann.getSimpleName();

                if ("SpringBootTest".equals(simpleName)) {
                    hasSpringBootTest = true;
                }
                if ("ExtendWith".equals(simpleName) && hasArquillianExtensionArg(ann)) {
                    hasArquillianExtendWith = true;
                }
                if ("RunWith".equals(simpleName) && hasArquillianRunnerArg(ann)) {
                    hasArquillianRunWith = true;
                }
            }

            // Check if there are @Deployment methods to remove
            boolean hasDeploymentMethods = hasDeploymentMethod(classDecl);

            // Idempotency: if @SpringBootTest exists and no Arquillian annotations to process
            if (hasSpringBootTest && !hasArquillianExtendWith && !hasArquillianRunWith) {
                // Only process if there are still @Deployment methods to remove
                return hasDeploymentMethods;
            }

            return hasArquillianExtendWith || hasArquillianRunWith || hasDeploymentMethods;
        }

        private boolean hasDeploymentMethod(J.ClassDeclaration classDecl) {
            if (classDecl.getBody() == null) {
                return false;
            }
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                    for (J.Annotation ann : method.getLeadingAnnotations()) {
                        if ("Deployment".equals(ann.getSimpleName())) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Checks if a string contains exactly "ArquillianExtension.class" with word boundaries.
         * This prevents false positives like "MyArquillianExtension.class".
         */
        private boolean isArquillianExtension(String str) {
            return ARQUILLIAN_EXTENSION_PATTERN.matcher(str).find();
        }

        private boolean hasArquillianExtensionArg(J.Annotation annotation) {
            if (annotation.getArguments() == null) {
                return false;
            }
            for (Expression arg : annotation.getArguments()) {
                String argStr = arg.toString();
                if (isArquillianExtension(argStr)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasMultipleExtensions(J.Annotation annotation) {
            if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                return false;
            }
            // Use AST-based checking for arrays
            for (Expression arg : annotation.getArguments()) {
                // Direct array: @ExtendWith({A.class, B.class})
                if (arg instanceof J.NewArray) {
                    J.NewArray arr = (J.NewArray) arg;
                    return arr.getInitializer() != null && arr.getInitializer().size() > 1;
                }
                // Assignment form: @ExtendWith(value = {A.class, B.class})
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    Expression assignmentValue = assignment.getAssignment();
                    if (assignmentValue instanceof J.NewArray) {
                        J.NewArray arr = (J.NewArray) assignmentValue;
                        return arr.getInitializer() != null && arr.getInitializer().size() > 1;
                    }
                }
                // Fallback: check for comma (indicates multiple values)
                String argStr = arg.toString();
                if (argStr.contains(",")) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasArquillianRunnerArg(J.Annotation annotation) {
            if (annotation.getArguments() == null) {
                return false;
            }
            for (Expression arg : annotation.getArguments()) {
                String argStr = arg.toString();
                if (argStr.contains("Arquillian")) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Removes ArquillianExtension.class from @ExtendWith annotation arguments.
         * @return Modified annotation, or null if no extensions remain after filtering
         */
        private J.Annotation removeArquillianFromExtendWith(J.Annotation annotation) {
            if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                return null;
            }

            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : annotation.getArguments()) {
                if (arg instanceof J.NewArray) {
                    // Direct array: @ExtendWith({A.class, B.class})
                    J.NewArray arr = (J.NewArray) arg;
                    if (arr.getInitializer() != null) {
                        List<Expression> filtered = filterArquillianFromList(arr.getInitializer());
                        if (filtered.isEmpty()) {
                            return null;  // No extensions left
                        }
                        if (filtered.size() == 1) {
                            // Single element - convert to single arg (no array)
                            newArgs.add(filtered.get(0).withPrefix(Space.EMPTY));
                        } else {
                            // Multiple elements - keep as array with proper spacing
                            newArgs.add(arr.withInitializer(filtered));
                        }
                    } else {
                        // Defensive: null initializer - keep original argument unchanged
                        newArgs.add(arg);
                    }
                } else if (arg instanceof J.Assignment) {
                    // Assignment form: @ExtendWith(value = {A.class, B.class})
                    J.Assignment assignment = (J.Assignment) arg;
                    Expression assignmentValue = assignment.getAssignment();
                    if (assignmentValue instanceof J.NewArray) {
                        J.NewArray arr = (J.NewArray) assignmentValue;
                        if (arr.getInitializer() != null) {
                            List<Expression> filtered = filterArquillianFromList(arr.getInitializer());
                            if (filtered.isEmpty()) {
                                return null;  // No extensions left
                            }
                            if (filtered.size() == 1) {
                                // Single element - convert to single arg (no array, no value=)
                                newArgs.add(filtered.get(0).withPrefix(Space.EMPTY));
                            } else {
                                // Multiple elements - keep assignment with filtered array
                                newArgs.add(assignment.withAssignment(arr.withInitializer(filtered)));
                            }
                        } else {
                            // Defensive: null initializer - keep original assignment unchanged
                            newArgs.add(arg);
                        }
                    } else {
                        // Non-array assignment, keep as-is
                        newArgs.add(arg);
                    }
                } else if (!isArquillianExtension(arg.toString())) {
                    // Keep non-Arquillian arguments
                    newArgs.add(arg);
                }
                // Skip arguments containing ArquillianExtension.class
            }

            if (newArgs.isEmpty()) {
                return null;
            }

            return annotation.withArguments(newArgs);
        }

        /**
         * Filters out ArquillianExtension.class from a list of expressions.
         */
        private List<Expression> filterArquillianFromList(List<Expression> elements) {
            List<Expression> filtered = new ArrayList<>();
            boolean isFirst = true;
            for (Expression elem : elements) {
                String elemStr = elem.toString();
                if (!isArquillianExtension(elemStr)) {
                    if (isFirst) {
                        // First element: only clear whitespace if it's just spaces (not newline indents)
                        // Use withWhitespace("") instead of Space.EMPTY to preserve any comments
                        String ws = elem.getPrefix().getWhitespace();
                        if (ws.contains("\n")) {
                            // Preserve multi-line formatting
                            filtered.add(elem);
                        } else {
                            // Single-line: clear leading whitespace but preserve comments
                            filtered.add(elem.withPrefix(elem.getPrefix().withWhitespace("")));
                        }
                        isFirst = false;
                    } else {
                        filtered.add(elem);
                    }
                }
            }
            return filtered;
        }

        private J.Annotation createSpringBootTestAnnotation(Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(SPRING_BOOT_TEST);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "SpringBootTest",
                type,
                null
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                null  // No arguments
            );
        }
    }
}
