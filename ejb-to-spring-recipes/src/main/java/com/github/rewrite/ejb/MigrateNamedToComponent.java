/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for CDI-to-Spring migration - @Named to @Component/@Qualifier conversion
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

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Migrates CDI @Named to Spring @Component and @Qualifier annotations.
 * <p>
 * <b>Class-level @Named:</b>
 * <ul>
 *   <li>{@code @Named} → {@code @Component}</li>
 *   <li>{@code @Named("beanName")} → {@code @Component("beanName")}</li>
 *   <li>{@code @Named(CONSTANT)} → {@code @Component(CONSTANT)} (preserves expression)</li>
 * </ul>
 * <p>
 * <b>Field/Parameter-level @Named (injection point qualifiers):</b>
 * <ul>
 *   <li>{@code @Named("qualifier")} → {@code @Qualifier("qualifier")}</li>
 *   <li>{@code @Named(CONSTANT)} → {@code @Qualifier(CONSTANT)} (preserves expression)</li>
 *   <li>{@code @Named} (without value) → removed (Spring autowires by type)</li>
 * </ul>
 * <p>
 * <b>Stereotype + @Named handling:</b>
 * <ul>
 *   <li>If a class already has @Service/@Repository/@Controller without a value, the @Named value
 *       is transferred to the stereotype (e.g., @Named("x") + @Service → @Service("x"))</li>
 * </ul>
 * <p>
 * <b>Method-level @Named (producer methods):</b>
 * <ul>
 *   <li>Method-level @Named is preserved (CDI producer semantics require manual migration)</li>
 * </ul>
 * <p>
 * Handles both jakarta.inject.Named and javax.inject.Named namespaces.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateNamedToComponent extends Recipe {

    @Option(displayName = "Inject strategy override",
            description = "Override project.yaml inject strategy: keep-jsr330 or migrate-to-spring. " +
                          "If not set, project.yaml (or defaults) are used. Default strategy is keep-jsr330.",
            example = "migrate-to-spring",
            required = false)
    @Nullable
    String strategy;

    private static final String NAMED_JAKARTA = "jakarta.inject.Named";
    private static final String NAMED_JAVAX = "javax.inject.Named";
    private static final String SPRING_COMPONENT = "org.springframework.stereotype.Component";
    private static final String SPRING_SERVICE = "org.springframework.stereotype.Service";
    private static final String SPRING_REPOSITORY = "org.springframework.stereotype.Repository";
    private static final String SPRING_CONTROLLER = "org.springframework.stereotype.Controller";
    private static final String SPRING_QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier";

    private static final Set<String> SPRING_STEREOTYPES = Set.of("Component", "Service", "Repository", "Controller");

    public MigrateNamedToComponent() {
        this.strategy = null;
    }

    public MigrateNamedToComponent(@Nullable String strategy) {
        this.strategy = strategy;
    }

    @Override
    public String getDisplayName() {
        return "Migrate @Named to Spring @Component/@Qualifier";
    }

    @Override
    public String getDescription() {
        return "Converts CDI @Named annotations to Spring equivalents: " +
               "@Named on classes becomes @Component, @Named on injection points becomes @Qualifier. " +
               "Preserves non-literal values (constants). Transfers @Named value to existing stereotypes. " +
               "Handles both jakarta.inject and javax.inject namespaces.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new NamedToSpringVisitor();
    }

    /**
     * Checks if migration should occur based on project.yaml or strategy override.
     * Returns true only if strategy is MIGRATE_TO_SPRING (default is KEEP_JSR330).
     */
    private boolean shouldMigrate(@Nullable Path sourcePath) {
        ProjectConfiguration.InjectStrategy override = ProjectConfiguration.InjectStrategy.fromString(strategy);
        if (strategy != null && override == null) {
            System.err.println("Warning: Unknown inject strategy override '" + strategy +
                    "', using project.yaml/defaults.");
        }
        if (override != null) {
            return override == ProjectConfiguration.InjectStrategy.MIGRATE_TO_SPRING;
        }
        if (sourcePath == null) {
            return ProjectConfiguration.mavenDefaults().getInjectStrategy()
                    == ProjectConfiguration.InjectStrategy.MIGRATE_TO_SPRING;
        }
        ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(sourcePath));
        return config.getInjectStrategy() == ProjectConfiguration.InjectStrategy.MIGRATE_TO_SPRING;
    }

    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = sourcePath.toAbsolutePath().getParent();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("project.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    private class NamedToSpringVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            // Check if migration is enabled by configuration
            if (!shouldMigrate(cu.getSourcePath())) {
                return cu;
            }

            J.CompilationUnit result = super.visitCompilationUnit(cu, ctx);

            // Only remove @Named imports if no @Named annotations remain in the compilation unit
            // (method-level @Named is preserved for manual migration and needs its import)
            if (!hasRemainingNamedAnnotations(result)) {
                maybeRemoveImport(NAMED_JAKARTA);
                maybeRemoveImport(NAMED_JAVAX);
            }

            return result;
        }

        private boolean hasRemainingNamedAnnotations(J.CompilationUnit cu) {
            java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean(false);
            new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                    if (isNamedAnnotation(annotation)) {
                        found.set(true);
                    }
                    return super.visitAnnotation(annotation, ctx);
                }
            }.visit(cu, new InMemoryExecutionContext());
            return found.get();
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Find @Named annotation on class
            NamedAnnotationInfo namedInfo = findNamedAnnotation(cd.getLeadingAnnotations());
            if (namedInfo == null) {
                return cd;
            }

            // Check if class already has a Spring stereotype
            StereotypeInfo stereotypeInfo = findSpringStereotype(cd.getLeadingAnnotations());

            if (stereotypeInfo != null) {
                // Has Spring stereotype - transfer @Named value to it if needed
                return transferNamedValueToStereotype(cd, namedInfo, stereotypeInfo);
            }

            // Replace @Named with @Component
            return replaceNamedWithComponent(cd, namedInfo);
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
            J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);

            // Check for @Named on fields (injection point qualifier)
            NamedAnnotationInfo namedInfo = findNamedAnnotation(vd.getLeadingAnnotations());
            if (namedInfo == null) {
                return vd;
            }

            // @Named on field with value becomes @Qualifier
            // @Named on field without value is removed (Spring autowires by type)
            return replaceNamedOnField(vd, namedInfo);
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            // Check for @Named on parameters (not on the method itself - leave method-level @Named for manual review)
            if (md.getParameters() != null) {
                List<Statement> newParams = new ArrayList<>();
                boolean changed = false;

                for (Statement param : md.getParameters()) {
                    if (param instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) param;
                        NamedAnnotationInfo namedInfo = findNamedAnnotation(vd.getLeadingAnnotations());
                        if (namedInfo != null) {
                            vd = replaceNamedOnField(vd, namedInfo);
                            changed = true;
                        }
                        newParams.add(vd);
                    } else {
                        newParams.add(param);
                    }
                }

                if (changed) {
                    md = md.withParameters(newParams);
                }
            }

            // Note: Method-level @Named is intentionally NOT processed here.
            // CDI producer methods with @Named require manual migration to Spring @Bean.
            // The @Named import will remain if method-level @Named exists.

            return md;
        }

        private static class NamedAnnotationInfo {
            J.Annotation annotation;
            int annotationIndex;
            Expression valueExpression;  // The full expression (can be literal, constant reference, etc.)
            String literalValue;  // null if not a string literal
        }

        private static class StereotypeInfo {
            J.Annotation annotation;
            int annotationIndex;
            String simpleName;  // "Service", "Component", etc.
            boolean hasValue;  // true if @Service("x") has a value
        }

        private NamedAnnotationInfo findNamedAnnotation(List<J.Annotation> annotations) {
            for (int i = 0; i < annotations.size(); i++) {
                J.Annotation ann = annotations.get(i);
                if (isNamedAnnotation(ann)) {
                    NamedAnnotationInfo info = new NamedAnnotationInfo();
                    info.annotation = ann;
                    info.annotationIndex = i;
                    info.valueExpression = extractValueExpression(ann);
                    info.literalValue = extractLiteralValue(info.valueExpression);
                    return info;
                }
            }
            return null;
        }

        private StereotypeInfo findSpringStereotype(List<J.Annotation> annotations) {
            for (int i = 0; i < annotations.size(); i++) {
                J.Annotation ann = annotations.get(i);
                String simpleName = ann.getSimpleName();
                // Only consider Spring stereotype annotations (verify by type, not just name)
                if (SPRING_STEREOTYPES.contains(simpleName) && isSpringStereotype(ann)) {
                    StereotypeInfo info = new StereotypeInfo();
                    info.annotation = ann;
                    info.annotationIndex = i;
                    info.simpleName = simpleName;
                    info.hasValue = hasAnnotationValue(ann);
                    return info;
                }
            }
            return null;
        }

        private boolean isSpringStereotype(J.Annotation ann) {
            // Use type checking to verify this is actually a Spring stereotype
            return TypeUtils.isOfClassType(ann.getType(), SPRING_COMPONENT) ||
                   TypeUtils.isOfClassType(ann.getType(), SPRING_SERVICE) ||
                   TypeUtils.isOfClassType(ann.getType(), SPRING_REPOSITORY) ||
                   TypeUtils.isOfClassType(ann.getType(), SPRING_CONTROLLER);
        }

        private boolean isNamedAnnotation(J.Annotation ann) {
            if ("Named".equals(ann.getSimpleName())) {
                return true;
            }
            return TypeUtils.isOfClassType(ann.getType(), NAMED_JAKARTA) ||
                   TypeUtils.isOfClassType(ann.getType(), NAMED_JAVAX);
        }

        private Expression extractValueExpression(J.Annotation ann) {
            if (ann.getArguments() == null || ann.getArguments().isEmpty()) {
                return null;
            }

            for (Expression arg : ann.getArguments()) {
                if (arg instanceof J.Empty) {
                    continue;
                }
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    String key = assignment.getVariable().toString();
                    if ("value".equals(key)) {
                        return assignment.getAssignment();
                    }
                } else {
                    // Direct value without "value = " prefix
                    return arg;
                }
            }
            return null;
        }

        private String extractLiteralValue(Expression expr) {
            if (expr instanceof J.Literal) {
                J.Literal literal = (J.Literal) expr;
                if (literal.getValue() instanceof String) {
                    return (String) literal.getValue();
                }
            }
            return null;
        }

        private boolean hasAnnotationValue(J.Annotation ann) {
            if (ann.getArguments() == null || ann.getArguments().isEmpty()) {
                return false;
            }
            for (Expression arg : ann.getArguments()) {
                if (!(arg instanceof J.Empty)) {
                    return true;
                }
            }
            return false;
        }

        private J.ClassDeclaration transferNamedValueToStereotype(J.ClassDeclaration cd,
                                                                   NamedAnnotationInfo namedInfo,
                                                                   StereotypeInfo stereotypeInfo) {
            // Note: Import removal is handled at compilation unit level to preserve
            // imports when method-level @Named exists

            List<J.Annotation> newAnnotations = new ArrayList<>(cd.getLeadingAnnotations());
            Space namedPrefix = namedInfo.annotation.getPrefix();

            // If @Named has a value and stereotype doesn't, transfer the value
            if (namedInfo.valueExpression != null && !stereotypeInfo.hasValue) {
                // Ensure import exists for the stereotype (in case original was fully-qualified)
                String stereotypeFqn = getStereotypeFqn(stereotypeInfo.simpleName);
                maybeAddImport(stereotypeFqn);

                // Create new stereotype with the @Named value, using @Named's prefix if @Named came first
                Space newPrefix = namedInfo.annotationIndex < stereotypeInfo.annotationIndex
                    ? namedPrefix
                    : stereotypeInfo.annotation.getPrefix();
                J.Annotation newStereotype = createAnnotationWithExpression(
                    stereotypeInfo.simpleName,
                    stereotypeFqn,
                    namedInfo.valueExpression,
                    newPrefix
                );
                newAnnotations.set(stereotypeInfo.annotationIndex, newStereotype);
            } else if (namedInfo.annotationIndex < stereotypeInfo.annotationIndex) {
                // @Named has no value but comes before stereotype - transfer prefix to stereotype
                J.Annotation updatedStereotype = stereotypeInfo.annotation.withPrefix(namedPrefix);
                newAnnotations.set(stereotypeInfo.annotationIndex, updatedStereotype);
            }

            // Remove @Named
            newAnnotations.remove(namedInfo.annotationIndex);

            return cd.withLeadingAnnotations(newAnnotations);
        }

        private String getStereotypeFqn(String simpleName) {
            return switch (simpleName) {
                case "Service" -> SPRING_SERVICE;
                case "Repository" -> SPRING_REPOSITORY;
                case "Controller" -> SPRING_CONTROLLER;
                default -> SPRING_COMPONENT;
            };
        }

        private J.ClassDeclaration replaceNamedWithComponent(J.ClassDeclaration cd, NamedAnnotationInfo namedInfo) {
            maybeAddImport(SPRING_COMPONENT);
            // Note: Import removal is handled at compilation unit level to preserve
            // imports when method-level @Named exists

            // Create @Component annotation, preserving the original expression
            J.Annotation componentAnn;
            if (namedInfo.valueExpression != null) {
                componentAnn = createAnnotationWithExpression(
                    "Component",
                    SPRING_COMPONENT,
                    namedInfo.valueExpression,
                    namedInfo.annotation.getPrefix()
                );
            } else {
                componentAnn = createSpringAnnotation(
                    "Component",
                    SPRING_COMPONENT,
                    null,
                    namedInfo.annotation.getPrefix()
                );
            }

            // Replace @Named with @Component
            List<J.Annotation> newAnnotations = new ArrayList<>(cd.getLeadingAnnotations());
            newAnnotations.set(namedInfo.annotationIndex, componentAnn);
            return cd.withLeadingAnnotations(newAnnotations);
        }

        private J.VariableDeclarations replaceNamedOnField(J.VariableDeclarations vd, NamedAnnotationInfo namedInfo) {
            // Note: Import removal is handled at compilation unit level to preserve
            // imports when method-level @Named exists

            List<J.Annotation> newAnnotations = new ArrayList<>(vd.getLeadingAnnotations());

            if (namedInfo.valueExpression != null) {
                // @Named("qualifier") or @Named(CONSTANT) -> @Qualifier(...)
                maybeAddImport(SPRING_QUALIFIER);

                J.Annotation qualifierAnn = createAnnotationWithExpression(
                    "Qualifier",
                    SPRING_QUALIFIER,
                    namedInfo.valueExpression,
                    namedInfo.annotation.getPrefix()
                );
                newAnnotations.set(namedInfo.annotationIndex, qualifierAnn);
            } else {
                // @Named without value - just remove it
                newAnnotations.remove(namedInfo.annotationIndex);
            }

            return vd.withLeadingAnnotations(newAnnotations);
        }

        /**
         * Creates an annotation with the given expression as its value.
         * Preserves non-literal expressions (constants, etc.).
         */
        private J.Annotation createAnnotationWithExpression(String simpleName, String fqn,
                                                             Expression valueExpr, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(fqn);

            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                simpleName,
                type,
                null
            );

            // Clone the value expression with empty prefix for use in annotation
            Expression clonedExpr = valueExpr.withPrefix(Space.EMPTY);

            List<JRightPadded<Expression>> argList = new ArrayList<>();
            argList.add(new JRightPadded<>(clonedExpr, Space.EMPTY, Markers.EMPTY));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                argList,
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

        private J.Annotation createSpringAnnotation(String simpleName, String fqn, String value, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(fqn);

            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                simpleName,
                type,
                null
            );

            JContainer<Expression> args = null;
            if (value != null && !value.isEmpty()) {
                J.Literal literal = new J.Literal(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    value,
                    "\"" + escapeJavaString(value) + "\"",
                    Collections.emptyList(),
                    JavaType.Primitive.String
                );

                List<JRightPadded<Expression>> argList = new ArrayList<>();
                argList.add(new JRightPadded<>(literal, Space.EMPTY, Markers.EMPTY));

                args = JContainer.build(
                    Space.EMPTY,
                    argList,
                    Markers.EMPTY
                );
            }

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
    }
}
