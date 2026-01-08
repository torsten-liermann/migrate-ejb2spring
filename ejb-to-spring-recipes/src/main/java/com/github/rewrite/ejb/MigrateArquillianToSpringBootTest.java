/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Implemented Arquillian to Spring Boot Test migration with JUnit 4 to 5 transformation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
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
 * Migrates Arquillian tests to Spring Boot Test with comprehensive transformations.
 * <p>
 * This recipe performs the following transformations:
 * <ul>
 *   <li>{@code @RunWith(Arquillian.class)} -> {@code @SpringBootTest}</li>
 *   <li>{@code @ExtendWith(ArquillianExtension.class)} -> {@code @SpringBootTest}</li>
 *   <li>{@code @Deployment} methods -> removed with {@code @NeedsReview} on class</li>
 *   <li>{@code @ArquillianResource} -> {@code @Autowired} with {@code @NeedsReview}</li>
 *   <li>JUnit 4 {@code @Test} -> JUnit 5 {@code @Test}</li>
 *   <li>JUnit 4 {@code @Before} -> JUnit 5 {@code @BeforeEach}</li>
 *   <li>JUnit 4 {@code @After} -> JUnit 5 {@code @AfterEach}</li>
 *   <li>JUnit 4 {@code @BeforeClass} -> JUnit 5 {@code @BeforeAll}</li>
 *   <li>JUnit 4 {@code @AfterClass} -> JUnit 5 {@code @AfterAll}</li>
 *   <li>JUnit 4 {@code @Ignore} -> JUnit 5 {@code @Disabled}</li>
 * </ul>
 * <p>
 * Limitations requiring manual review:
 * <ul>
 *   <li>ShrinkWrap deployment logic must be manually converted to Spring configuration</li>
 *   <li>Container-specific tests may need Testcontainers setup</li>
 *   <li>@ArquillianResource injected types need verification</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateArquillianToSpringBootTest extends Recipe {

    // Spring Boot Test
    private static final String SPRING_BOOT_TEST = "org.springframework.boot.test.context.SpringBootTest";
    private static final String AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";

    // Arquillian types
    private static final String ARQUILLIAN_EXTENSION = "org.jboss.arquillian.junit5.ArquillianExtension";
    private static final String ARQUILLIAN_RUNNER = "org.jboss.arquillian.junit.Arquillian";
    private static final String EXTEND_WITH = "org.junit.jupiter.api.extension.ExtendWith";
    private static final String RUN_WITH = "org.junit.runner.RunWith";
    private static final String DEPLOYMENT = "org.jboss.arquillian.container.test.api.Deployment";
    private static final String ARQUILLIAN_RESOURCE = "org.jboss.arquillian.test.api.ArquillianResource";

    // JUnit 4 types
    private static final String JUNIT4_TEST = "org.junit.Test";
    private static final String JUNIT4_BEFORE = "org.junit.Before";
    private static final String JUNIT4_AFTER = "org.junit.After";
    private static final String JUNIT4_BEFORE_CLASS = "org.junit.BeforeClass";
    private static final String JUNIT4_AFTER_CLASS = "org.junit.AfterClass";
    private static final String JUNIT4_IGNORE = "org.junit.Ignore";

    // JUnit 5 types
    private static final String JUNIT5_TEST = "org.junit.jupiter.api.Test";
    private static final String JUNIT5_BEFORE_EACH = "org.junit.jupiter.api.BeforeEach";
    private static final String JUNIT5_AFTER_EACH = "org.junit.jupiter.api.AfterEach";
    private static final String JUNIT5_BEFORE_ALL = "org.junit.jupiter.api.BeforeAll";
    private static final String JUNIT5_AFTER_ALL = "org.junit.jupiter.api.AfterAll";
    private static final String JUNIT5_DISABLED = "org.junit.jupiter.api.Disabled";

    // NeedsReview annotation
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Migrate Arquillian tests to Spring Boot Test";
    }

    @Override
    public String getDescription() {
        return "Converts Arquillian test infrastructure to Spring Boot Test. " +
               "Replaces @RunWith(Arquillian.class) or @ExtendWith(ArquillianExtension.class) with @SpringBootTest, " +
               "removes @Deployment methods, converts @ArquillianResource to @Autowired, " +
               "and migrates JUnit 4 annotations to JUnit 5. " +
               "Adds @NeedsReview annotations for manual verification.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new ArquillianMigrationVisitor();
    }

    private class ArquillianMigrationVisitor extends JavaIsoVisitor<ExecutionContext> {
        private boolean isArquillianTest = false;
        private boolean hasDeploymentMethod = false;
        private boolean hasArquillianResource = false;
        private final List<String> arquillianFeatures = new ArrayList<>();
        // Track imports to add/remove at class level
        private final Set<String> importsToAdd = new LinkedHashSet<>();
        private final Set<String> importsToRemove = new LinkedHashSet<>();

        // Pattern to match exactly "ArquillianExtension.class" with word boundary
        private static final Pattern ARQUILLIAN_EXTENSION_PATTERN =
            Pattern.compile("(?<![\\w$])ArquillianExtension\\.class(?![\\w$])");

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            // Reset state for each compilation unit
            isArquillianTest = false;
            hasDeploymentMethod = false;
            hasArquillianResource = false;
            arquillianFeatures.clear();
            importsToAdd.clear();
            importsToRemove.clear();

            J.CompilationUnit result = super.visitCompilationUnit(cu, ctx);

            // Apply collected import changes after all classes are visited
            // Process imports in the result directly
            if (!importsToRemove.isEmpty() || !importsToAdd.isEmpty()) {
                String firstImportPrefix = result.getImports().isEmpty()
                    ? "\n\n"
                    : result.getImports().get(0).getPrefix().getWhitespace();

                // Filter out imports to remove and collect remaining ones
                List<J.Import> newImports = new ArrayList<>();
                for (J.Import imp : result.getImports()) {
                    String typeName = imp.getTypeName();
                    if (!importsToRemove.contains(typeName)) {
                        newImports.add(imp);
                    }
                }

                // Add new imports
                for (String importToAdd : importsToAdd) {
                    // Check if import already exists
                    boolean exists = newImports.stream()
                        .anyMatch(i -> i.getTypeName().equals(importToAdd));
                    if (!exists) {
                        J.Import newImport = createImport(importToAdd);
                        newImports.add(newImport);
                    }
                }

                // Sort imports
                newImports.sort(Comparator.comparing(J.Import::getTypeName));

                if (!newImports.isEmpty()) {
                    newImports.set(0, newImports.get(0).withPrefix(Space.format(firstImportPrefix)));
                }

                result = result.withImports(newImports);
            }

            return result;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // Save previous state to handle nested classes correctly
            boolean prevIsArquillianTest = isArquillianTest;
            boolean prevHasDeploymentMethod = hasDeploymentMethod;
            boolean prevHasArquillianResource = hasArquillianResource;
            List<String> prevArquillianFeatures = new ArrayList<>(arquillianFeatures);

            try {
                // Reset for this class
                hasDeploymentMethod = false;
                hasArquillianResource = false;
                arquillianFeatures.clear();

                // Check if this is an Arquillian test class
                isArquillianTest = hasArquillianAnnotation(classDecl);

                // Pre-scan for features that need @NeedsReview
                if (isArquillianTest) {
                    scanForArquillianFeatures(classDecl);
                }

                // Always visit children to handle nested Arquillian test classes
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                if (!isArquillianTest) {
                    return cd;
                }

                // Transform class-level annotations
                List<J.Annotation> newAnnotations = new ArrayList<>();
                boolean addedSpringBootTest = false;
                boolean hasSpringBootTest = false;
                boolean hasNeedsReview = false;

                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    String simpleName = ann.getSimpleName();

                    // Check if @SpringBootTest already exists
                    if ("SpringBootTest".equals(simpleName)) {
                        hasSpringBootTest = true;
                        newAnnotations.add(ann);
                        continue;
                    }

                    // Check if @NeedsReview already exists
                    if ("NeedsReview".equals(simpleName)) {
                        hasNeedsReview = true;
                        newAnnotations.add(ann);
                        continue;
                    }

                    // Handle @ExtendWith annotation
                    if ("ExtendWith".equals(simpleName) && hasArquillianExtensionArg(ann)) {
                        if (!addedSpringBootTest && !hasSpringBootTest) {
                            importsToAdd.add(SPRING_BOOT_TEST);
                            newAnnotations.add(createSpringBootTestAnnotation(ann.getPrefix()));
                            addedSpringBootTest = true;
                        }

                        // Check if @ExtendWith has multiple extensions (array)
                        if (hasMultipleExtensions(ann)) {
                            J.Annotation filteredAnn = removeArquillianFromExtendWith(ann);
                            if (filteredAnn != null) {
                                String originalWhitespace = ann.getPrefix().getWhitespace();
                                String indentation = originalWhitespace.contains("\n")
                                    ? originalWhitespace.substring(originalWhitespace.lastIndexOf('\n'))
                                    : "\n";
                                newAnnotations.add(filteredAnn.withPrefix(Space.format(indentation)));
                            }
                            importsToRemove.add(ARQUILLIAN_EXTENSION);
                            if (filteredAnn == null) {
                                importsToRemove.add(EXTEND_WITH);
                            }
                        } else {
                            importsToRemove.add(ARQUILLIAN_EXTENSION);
                            importsToRemove.add(EXTEND_WITH);
                        }
                        continue;
                    }

                    // Replace @RunWith(Arquillian.class) with @SpringBootTest
                    if ("RunWith".equals(simpleName) && hasArquillianRunnerArg(ann)) {
                        if (!addedSpringBootTest && !hasSpringBootTest) {
                            importsToAdd.add(SPRING_BOOT_TEST);
                            newAnnotations.add(createSpringBootTestAnnotation(ann.getPrefix()));
                            addedSpringBootTest = true;
                        }
                        importsToRemove.add(ARQUILLIAN_RUNNER);
                        importsToRemove.add(RUN_WITH);
                        continue;
                    }

                    // Keep other annotations
                    newAnnotations.add(ann);
                }

                // Add @NeedsReview if we have features that need manual review
                if (!hasNeedsReview && (hasDeploymentMethod || hasArquillianResource)) {
                    importsToAdd.add(NEEDS_REVIEW_FQN);
                    doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN + ".Category", "MANUAL_MIGRATION", false));

                    Space prefix = newAnnotations.isEmpty()
                        ? cd.getPrefix()
                        : newAnnotations.get(0).getPrefix();

                    String reason = buildNeedsReviewReason();
                    String originalCode = String.join(", ", arquillianFeatures);
                    String suggestedAction = buildSuggestedAction();

                    J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                        reason, "MANUAL_MIGRATION", originalCode, suggestedAction, prefix
                    );

                    List<J.Annotation> finalAnnotations = new ArrayList<>();
                    finalAnnotations.add(needsReviewAnn);

                    for (int i = 0; i < newAnnotations.size(); i++) {
                        J.Annotation ann = newAnnotations.get(i);
                        if (i == 0) {
                            String ws = prefix.getWhitespace();
                            ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n"));
                        }
                        finalAnnotations.add(ann);
                    }

                    newAnnotations = finalAnnotations;
                }

                // Collect Arquillian-related imports for removal
                importsToRemove.add("org.jboss.arquillian.junit.Arquillian");
                importsToRemove.add("org.jboss.arquillian.container.test.api.Deployment");
                importsToRemove.add("org.jboss.arquillian.test.api.ArquillianResource");
                importsToRemove.add("org.jboss.shrinkwrap.api.ShrinkWrap");
                importsToRemove.add("org.jboss.shrinkwrap.api.spec.WebArchive");
                importsToRemove.add("org.jboss.shrinkwrap.api.spec.JavaArchive");
                importsToRemove.add("org.jboss.shrinkwrap.api.spec.EnterpriseArchive");

                return cd.withLeadingAnnotations(newAnnotations);
            } finally {
                // Restore previous state for nested class handling
                isArquillianTest = prevIsArquillianTest;
                hasDeploymentMethod = prevHasDeploymentMethod;
                hasArquillianResource = prevHasArquillianResource;
                arquillianFeatures.clear();
                arquillianFeatures.addAll(prevArquillianFeatures);
            }
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            if (!isArquillianTest) {
                return method;
            }

            J.MethodDeclaration md = method;

            // Remove @Deployment methods
            for (J.Annotation ann : method.getLeadingAnnotations()) {
                if ("Deployment".equals(ann.getSimpleName())) {
                    hasDeploymentMethod = true;
                    arquillianFeatures.add("@Deployment method");
                    return null; // Remove the method
                }
            }

            // Transform method annotations (JUnit 4 -> 5)
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean annotationsChanged = false;

            for (J.Annotation ann : md.getLeadingAnnotations()) {
                String simpleName = ann.getSimpleName();
                J.Annotation transformedAnn = transformJUnitAnnotation(ann, simpleName);
                if (transformedAnn != ann) {
                    annotationsChanged = true;
                    if (transformedAnn != null) {
                        newAnnotations.add(transformedAnn);
                    }
                } else {
                    newAnnotations.add(ann);
                }
            }

            if (annotationsChanged) {
                md = md.withLeadingAnnotations(newAnnotations);
            }

            return md;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable, ExecutionContext ctx) {
            if (!isArquillianTest) {
                return multiVariable;
            }

            J.VariableDeclarations vd = multiVariable;

            // Check for @ArquillianResource annotation
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean hasArquillianResourceAnn = false;

            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                if ("ArquillianResource".equals(ann.getSimpleName())) {
                    hasArquillianResourceAnn = true;
                    hasArquillianResource = true;
                    arquillianFeatures.add("@ArquillianResource");

                    // Replace with @Autowired
                    importsToAdd.add(AUTOWIRED);
                    J.Annotation autowiredAnn = createSimpleAnnotation("Autowired", AUTOWIRED, ann.getPrefix());
                    newAnnotations.add(autowiredAnn);
                } else {
                    newAnnotations.add(ann);
                }
            }

            if (hasArquillianResourceAnn) {
                vd = vd.withLeadingAnnotations(newAnnotations);
            }

            return vd;
        }

        private void scanForArquillianFeatures(J.ClassDeclaration classDecl) {
            if (classDecl.getBody() == null) {
                return;
            }

            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                    for (J.Annotation ann : method.getLeadingAnnotations()) {
                        if ("Deployment".equals(ann.getSimpleName())) {
                            hasDeploymentMethod = true;
                        }
                    }
                }
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations varDecls = (J.VariableDeclarations) stmt;
                    for (J.Annotation ann : varDecls.getLeadingAnnotations()) {
                        if ("ArquillianResource".equals(ann.getSimpleName())) {
                            hasArquillianResource = true;
                        }
                    }
                }
            }
        }

        private J.Annotation transformJUnitAnnotation(J.Annotation annotation, String simpleName) {
            switch (simpleName) {
                case "Test":
                    // Only transform JUnit 4 @Test
                    if (isJUnit4TestAnnotation(annotation)) {
                        importsToAdd.add(JUNIT5_TEST);
                        importsToRemove.add(JUNIT4_TEST);
                        return createSimpleAnnotation("Test", JUNIT5_TEST, annotation.getPrefix());
                    }
                    break;
                case "Before":
                    importsToAdd.add(JUNIT5_BEFORE_EACH);
                    importsToRemove.add(JUNIT4_BEFORE);
                    return createSimpleAnnotation("BeforeEach", JUNIT5_BEFORE_EACH, annotation.getPrefix());
                case "After":
                    importsToAdd.add(JUNIT5_AFTER_EACH);
                    importsToRemove.add(JUNIT4_AFTER);
                    return createSimpleAnnotation("AfterEach", JUNIT5_AFTER_EACH, annotation.getPrefix());
                case "BeforeClass":
                    importsToAdd.add(JUNIT5_BEFORE_ALL);
                    importsToRemove.add(JUNIT4_BEFORE_CLASS);
                    return createSimpleAnnotation("BeforeAll", JUNIT5_BEFORE_ALL, annotation.getPrefix());
                case "AfterClass":
                    importsToAdd.add(JUNIT5_AFTER_ALL);
                    importsToRemove.add(JUNIT4_AFTER_CLASS);
                    return createSimpleAnnotation("AfterAll", JUNIT5_AFTER_ALL, annotation.getPrefix());
                case "Ignore":
                    importsToAdd.add(JUNIT5_DISABLED);
                    importsToRemove.add(JUNIT4_IGNORE);
                    // Transfer the reason if present
                    if (annotation.getArguments() != null && !annotation.getArguments().isEmpty()) {
                        return createAnnotationWithArgs("Disabled", JUNIT5_DISABLED,
                            annotation.getPrefix(), annotation.getArguments());
                    }
                    return createSimpleAnnotation("Disabled", JUNIT5_DISABLED, annotation.getPrefix());
            }
            return annotation;
        }

        private boolean isJUnit4TestAnnotation(J.Annotation annotation) {
            // Check by FQN if available
            if (annotation.getType() instanceof JavaType.FullyQualified) {
                String fqn = ((JavaType.FullyQualified) annotation.getType()).getFullyQualifiedName();
                if (JUNIT4_TEST.equals(fqn)) {
                    return true;
                }
                if (JUNIT5_TEST.equals(fqn)) {
                    return false;
                }
            }
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            if (cu != null) {
                for (J.Import imp : cu.getImports()) {
                    if (JUNIT4_TEST.equals(imp.getTypeName())) {
                        return true;
                    }
                }
            }
            // If no type info, assume JUnit 4 for @RunWith(Arquillian.class) tests
            // JUnit 5 tests would use @ExtendWith instead of @RunWith
            return true;
        }

        private boolean hasArquillianAnnotation(J.ClassDeclaration classDecl) {
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                String simpleName = ann.getSimpleName();
                if ("ExtendWith".equals(simpleName) && hasArquillianExtensionArg(ann)) {
                    return true;
                }
                if ("RunWith".equals(simpleName) && hasArquillianRunnerArg(ann)) {
                    return true;
                }
            }
            // Also check for @Deployment methods (class without @RunWith but with @Deployment)
            return hasDeploymentMethod(classDecl);
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
            for (Expression arg : annotation.getArguments()) {
                if (arg instanceof J.NewArray) {
                    J.NewArray arr = (J.NewArray) arg;
                    return arr.getInitializer() != null && arr.getInitializer().size() > 1;
                }
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    Expression assignmentValue = assignment.getAssignment();
                    if (assignmentValue instanceof J.NewArray) {
                        J.NewArray arr = (J.NewArray) assignmentValue;
                        return arr.getInitializer() != null && arr.getInitializer().size() > 1;
                    }
                }
                if (arg.toString().contains(",")) {
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

        private J.Annotation removeArquillianFromExtendWith(J.Annotation annotation) {
            if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                return null;
            }

            List<Expression> newArgs = new ArrayList<>();
            for (Expression arg : annotation.getArguments()) {
                if (arg instanceof J.NewArray) {
                    J.NewArray arr = (J.NewArray) arg;
                    if (arr.getInitializer() != null) {
                        List<Expression> filtered = filterArquillianFromList(arr.getInitializer());
                        if (filtered.isEmpty()) {
                            return null;
                        }
                        if (filtered.size() == 1) {
                            newArgs.add(filtered.get(0).withPrefix(Space.EMPTY));
                        } else {
                            newArgs.add(arr.withInitializer(filtered));
                        }
                    } else {
                        newArgs.add(arg);
                    }
                } else if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    Expression assignmentValue = assignment.getAssignment();
                    if (assignmentValue instanceof J.NewArray) {
                        J.NewArray arr = (J.NewArray) assignmentValue;
                        if (arr.getInitializer() != null) {
                            List<Expression> filtered = filterArquillianFromList(arr.getInitializer());
                            if (filtered.isEmpty()) {
                                return null;
                            }
                            if (filtered.size() == 1) {
                                newArgs.add(filtered.get(0).withPrefix(Space.EMPTY));
                            } else {
                                newArgs.add(assignment.withAssignment(arr.withInitializer(filtered)));
                            }
                        } else {
                            newArgs.add(arg);
                        }
                    } else {
                        newArgs.add(arg);
                    }
                } else if (!isArquillianExtension(arg.toString())) {
                    newArgs.add(arg);
                }
            }

            if (newArgs.isEmpty()) {
                return null;
            }

            return annotation.withArguments(newArgs);
        }

        private List<Expression> filterArquillianFromList(List<Expression> elements) {
            List<Expression> filtered = new ArrayList<>();
            boolean isFirst = true;
            for (Expression elem : elements) {
                String elemStr = elem.toString();
                if (!isArquillianExtension(elemStr)) {
                    if (isFirst) {
                        String ws = elem.getPrefix().getWhitespace();
                        if (ws.contains("\n")) {
                            filtered.add(elem);
                        } else {
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

        private String buildNeedsReviewReason() {
            StringBuilder sb = new StringBuilder("Arquillian test migrated to Spring Boot Test");
            if (hasDeploymentMethod) {
                sb.append(" - @Deployment method removed");
            }
            if (hasArquillianResource) {
                sb.append(" - @ArquillianResource replaced with @Autowired");
            }
            return sb.toString();
        }

        private String buildSuggestedAction() {
            List<String> actions = new ArrayList<>();
            if (hasDeploymentMethod) {
                actions.add("Convert ShrinkWrap deployment to Spring configuration");
            }
            if (hasArquillianResource) {
                actions.add("Verify @Autowired injection types are correct");
            }
            actions.add("Consider using Testcontainers for integration tests");
            return String.join(". ", actions);
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
                null
            );
        }

        private J.Annotation createSimpleAnnotation(String name, String fqn, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(fqn);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                name,
                type,
                null
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                null
            );
        }

        private J.Annotation createAnnotationWithArgs(String name, String fqn, Space prefix, List<Expression> args) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(fqn);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                name,
                type,
                null
            );

            List<JRightPadded<Expression>> paddedArgs = new ArrayList<>();
            for (Expression arg : args) {
                paddedArgs.add(new JRightPadded<>(arg, Space.EMPTY, Markers.EMPTY));
            }

            JContainer<Expression> argsContainer = JContainer.build(
                Space.EMPTY,
                paddedArgs,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                argsContainer
            );
        }

        private J.Annotation createNeedsReviewAnnotation(String reason, String category,
                                                          String originalCode, String suggestedAction,
                                                          Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                type,
                null
            );

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg(category));
            arguments.add(createAssignmentArg("originalCode", originalCode, true));
            arguments.add(createAssignmentArg("suggestedAction", suggestedAction, true));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

        private String escapeJavaString(String value) {
            if (value == null) return "";
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }

        private JRightPadded<Expression> createAssignmentArg(String key, String value, boolean leadingSpace) {
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                leadingSpace ? Space.format(" ") : Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                key,
                null,
                null
            );

            String escapedValue = escapeJavaString(value);
            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                value,
                "\"" + escapedValue + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }

        private JRightPadded<Expression> createCategoryArg(String categoryName) {
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "category",
                null,
                null
            );

            JavaType.ShallowClass categoryType = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN + ".Category");

            J.Identifier valueExpr = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                categoryName,
                categoryType,
                null
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }

        private J.Import createImport(String fullyQualifiedName) {
            int lastDot = fullyQualifiedName.lastIndexOf('.');
            String packageName = lastDot > 0 ? fullyQualifiedName.substring(0, lastDot) : "";
            String className = lastDot > 0 ? fullyQualifiedName.substring(lastDot + 1) : fullyQualifiedName;

            // Build the field access chain for the import
            J.Identifier packageIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                packageName,
                null,
                null
            );

            J.FieldAccess fieldAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                packageIdent,
                JLeftPadded.build(new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    className,
                    JavaType.ShallowClass.build(fullyQualifiedName),
                    null
                )),
                JavaType.ShallowClass.build(fullyQualifiedName)
            );

            return new J.Import(
                Tree.randomId(),
                Space.format("\n"),
                Markers.EMPTY,
                JLeftPadded.build(false),
                fieldAccess,
                null
            );
        }
    }
}
