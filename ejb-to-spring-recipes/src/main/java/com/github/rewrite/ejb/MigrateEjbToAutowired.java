package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import java.util.Comparator;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Migrates @EJB injection annotations to Spring @Autowired or JSR-330 @Inject.
 * <p>
 * Handles both javax.ejb and jakarta.ejb namespaces.
 * <p>
 * Migration behavior is controlled by project.yaml:
 * <pre>
 * migration:
 *   inject:
 *     strategy: keep-jsr330 | migrate-to-spring
 * </pre>
 * <ul>
 *   <li>{@code keep-jsr330} (default): @EJB → @Inject (jakarta.inject)</li>
 *   <li>{@code migrate-to-spring}: @EJB → @Autowired</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEjbToAutowired extends Recipe {

    private static final String JAVAX_EJB = "javax.ejb.EJB";
    private static final String JAKARTA_EJB = "jakarta.ejb.EJB";
    private static final String SPRING_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final String SPRING_QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier";
    private static final String JAKARTA_INJECT = "jakarta.inject.Inject";
    private static final String JAKARTA_NAMED = "jakarta.inject.Named";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    private static final String NEEDS_REVIEW_STUB =
        "package com.github.rewrite.ejb.annotations;\n" +
        "import java.lang.annotation.*;\n" +
        "@Retention(RetentionPolicy.RUNTIME)\n" +
        "@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})\n" +
        "public @interface NeedsReview {\n" +
        "    Category category();\n" +
        "    String reason() default \"\";\n" +
        "    String originalCode() default \"\";\n" +
        "    String suggestedAction() default \"\";\n" +
        "    enum Category { MANUAL_MIGRATION }\n" +
        "}\n";

    @Option(displayName = "Inject strategy override",
            description = "Override project.yaml inject strategy: keep-jsr330 or migrate-to-spring. " +
                          "If not set, project.yaml (or defaults) are used. Default strategy is keep-jsr330.",
            example = "migrate-to-spring",
            required = false)
    @Nullable
    String strategy;

    public MigrateEjbToAutowired() {
        this.strategy = null;
    }

    public MigrateEjbToAutowired(@Nullable String strategy) {
        this.strategy = strategy;
    }

    @Override
    public String getDisplayName() {
        return "Migrate @EJB to @Autowired or @Inject";
    }

    @Override
    public String getDescription() {
        return "Converts EJB @EJB injection annotations to Spring @Autowired or JSR-330 @Inject. " +
               "Supports both javax.ejb and jakarta.ejb namespaces. " +
               "When inject strategy is 'keep-jsr330' (default), converts to @Inject; " +
               "when 'migrate-to-spring', converts to @Autowired.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAVAX_EJB, false),
                new UsesType<>(JAKARTA_EJB, false)
            ),
            new EjbToInjectionVisitor()
        );
    }

    /**
     * Checks if Spring @Autowired should be used (migrate-to-spring strategy).
     * If false, JSR-330 @Inject is used instead.
     */
    private boolean shouldUseAutowired(@Nullable Path sourcePath) {
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

    private class EjbToInjectionVisitor extends JavaIsoVisitor<ExecutionContext> {

        private boolean useAutowired = false;


        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            useAutowired = shouldUseAutowired(cu.getSourcePath());
            return super.visitCompilationUnit(cu, ctx);
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            J.Annotation ejbAnnotation = findEjbAnnotation(cd.getLeadingAnnotations());
            if (ejbAnnotation == null) {
                return cd;
            }

            // Class-level @EJB is rare and needs manual review
            // Replace with @NeedsReview preserving all attribute info
            List<J.Annotation> newAnnotations = new ArrayList<>();
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if (ann == ejbAnnotation) {
                    Space prefix = ann.getPrefix();
                    String indent = extractIndent(prefix);
                    if (indent.isEmpty()) {
                        indent = extractIndent(cd.getPrefix());
                    }

                    // Build original code from all attributes
                    String originalCode = buildOriginalCodeString(ejbAnnotation);
                    Space reviewPrefix = prefix;
                    newAnnotations.add(createNeedsReviewForClassLevel(originalCode, reviewPrefix));
                    maybeAddImport(NEEDS_REVIEW_FQN);

                    // Remove EJB import
                    if (TypeUtils.isOfClassType(ejbAnnotation.getType(), JAVAX_EJB)) {
                        maybeRemoveImport(JAVAX_EJB);
                    } else if (TypeUtils.isOfClassType(ejbAnnotation.getType(), JAKARTA_EJB)) {
                        maybeRemoveImport(JAKARTA_EJB);
                    }
                } else {
                    newAnnotations.add(ann);
                }
            }

            return cd.withLeadingAnnotations(newAnnotations);
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            J.Annotation ejbAnnotation = findEjbAnnotation(md.getLeadingAnnotations());
            if (ejbAnnotation == null) {
                return md;
            }

            // Extract attributes
            Expression beanNameExpr = extractAttribute(ejbAnnotation, "beanName");
            Expression lookupExpr = extractAttribute(ejbAnnotation, "lookup");
            Expression mappedNameExpr = extractAttribute(ejbAnnotation, "mappedName");
            Expression nameExpr = extractAttribute(ejbAnnotation, "name");

            // Build new annotation list
            List<J.Annotation> newAnnotations = new ArrayList<>();
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (ann == ejbAnnotation) {
                    Space prefix = ann.getPrefix();
                    String indent = extractIndent(prefix);
                    if (indent.isEmpty()) {
                        indent = extractIndent(md.getPrefix());
                    }

                    // Add @Inject or @Autowired
                    String targetAnnotation = useAutowired ? SPRING_AUTOWIRED : JAKARTA_INJECT;
                    newAnnotations.add(createSimpleAnnotation(targetAnnotation, prefix));
                    maybeAddImport(targetAnnotation);

                    // Handle beanName -> @Qualifier (Spring) or @Named (JSR-330)
                    if (beanNameExpr != null) {
                        String qualifierAnnotation = useAutowired ? SPRING_QUALIFIER : JAKARTA_NAMED;
                        Space qualifierPrefix = Space.format("\n" + indent);
                        newAnnotations.add(createAnnotationWithValue(qualifierAnnotation, beanNameExpr, qualifierPrefix));
                        maybeAddImport(qualifierAnnotation);
                    }

                    // Handle lookup/mappedName/name -> @NeedsReview marker
                    if (lookupExpr != null || mappedNameExpr != null || nameExpr != null) {
                        String originalCode = buildOriginalCodeString(ejbAnnotation);
                        Space reviewPrefix = Space.format("\n" + indent);
                        newAnnotations.add(createNeedsReviewForJndiAttribute(originalCode, reviewPrefix));
                        maybeAddImport(NEEDS_REVIEW_FQN);
                    }

                    // Remove EJB import
                    if (TypeUtils.isOfClassType(ejbAnnotation.getType(), JAVAX_EJB)) {
                        maybeRemoveImport(JAVAX_EJB);
                    } else if (TypeUtils.isOfClassType(ejbAnnotation.getType(), JAKARTA_EJB)) {
                        maybeRemoveImport(JAKARTA_EJB);
                    }
                } else {
                    newAnnotations.add(ann);
                }
            }

            return md.withLeadingAnnotations(newAnnotations);
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext ctx) {
            J.VariableDeclarations decls = super.visitVariableDeclarations(vd, ctx);

            J.Annotation ejbAnnotation = findEjbAnnotation(decls.getLeadingAnnotations());
            if (ejbAnnotation == null) {
                return decls;
            }

            // Extract attributes
            Expression beanNameExpr = extractAttribute(ejbAnnotation, "beanName");
            Expression lookupExpr = extractAttribute(ejbAnnotation, "lookup");
            Expression mappedNameExpr = extractAttribute(ejbAnnotation, "mappedName");
            Expression nameExpr = extractAttribute(ejbAnnotation, "name");

            // Build new annotation list
            List<J.Annotation> newAnnotations = new ArrayList<>();
            for (J.Annotation ann : decls.getLeadingAnnotations()) {
                if (ann == ejbAnnotation) {
                    Space prefix = ann.getPrefix();
                    String indent = extractIndent(prefix);
                    if (indent.isEmpty()) {
                        indent = extractIndent(decls.getPrefix());
                    }

                    // Add @Inject or @Autowired
                    String targetAnnotation = useAutowired ? SPRING_AUTOWIRED : JAKARTA_INJECT;
                    newAnnotations.add(createSimpleAnnotation(targetAnnotation, prefix));
                    maybeAddImport(targetAnnotation);

                    // Handle beanName -> @Qualifier (Spring) or @Named (JSR-330)
                    // But warn if multi-variable declaration
                    if (beanNameExpr != null) {
                        if (decls.getVariables().size() > 1) {
                            // Multi-variable declaration with beanName - add NeedsReview instead
                            Space reviewPrefix = Space.format("\n" + indent);
                            newAnnotations.add(createNeedsReviewForMultiVarAnnotation(beanNameExpr, reviewPrefix));
                            maybeAddImport(NEEDS_REVIEW_FQN);
                        } else {
                            String qualifierAnnotation = useAutowired ? SPRING_QUALIFIER : JAKARTA_NAMED;
                            Space qualifierPrefix = Space.format("\n" + indent);
                            newAnnotations.add(createAnnotationWithValue(qualifierAnnotation, beanNameExpr, qualifierPrefix));
                            maybeAddImport(qualifierAnnotation);
                        }
                    }

                    // Handle lookup/mappedName/name -> @NeedsReview marker
                    if (lookupExpr != null || mappedNameExpr != null || nameExpr != null) {
                        String originalCode = buildOriginalCodeString(ejbAnnotation);
                        Space reviewPrefix = Space.format("\n" + indent);
                        newAnnotations.add(createNeedsReviewForJndiAttribute(originalCode, reviewPrefix));
                        maybeAddImport(NEEDS_REVIEW_FQN);
                    }

                    // Remove EJB import
                    if (TypeUtils.isOfClassType(ejbAnnotation.getType(), JAVAX_EJB)) {
                        maybeRemoveImport(JAVAX_EJB);
                    } else if (TypeUtils.isOfClassType(ejbAnnotation.getType(), JAKARTA_EJB)) {
                        maybeRemoveImport(JAKARTA_EJB);
                    }
                } else {
                    newAnnotations.add(ann);
                }
            }

            return decls.withLeadingAnnotations(newAnnotations);
        }

        private J.Annotation findEjbAnnotation(List<J.Annotation> annotations) {
            for (J.Annotation ann : annotations) {
                if (TypeUtils.isOfClassType(ann.getType(), JAVAX_EJB) ||
                    TypeUtils.isOfClassType(ann.getType(), JAKARTA_EJB)) {
                    return ann;
                }
                // Fallback for missing type info
                if (ann.getType() == null && "EJB".equals(ann.getSimpleName())) {
                    return ann;
                }
            }
            return null;
        }

        private Expression extractAttribute(J.Annotation annotation, String name) {
            List<Expression> args = annotation.getArguments();
            if (args == null) {
                return null;
            }
            for (Expression arg : args) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assign = (J.Assignment) arg;
                    if (assign.getVariable() instanceof J.Identifier) {
                        if (name.equals(((J.Identifier) assign.getVariable()).getSimpleName())) {
                            return assign.getAssignment().withPrefix(Space.EMPTY);
                        }
                    }
                }
            }
            return null;
        }

        private J.Annotation createSimpleAnnotation(String fqn, Space prefix) {
            String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
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
            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                null
            );
        }

        private J.Annotation createAnnotationWithValue(String fqn, Expression value, Space prefix) {
            String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
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
            List<JRightPadded<Expression>> args = new ArrayList<>();
            args.add(new JRightPadded<>(value.withPrefix(Space.EMPTY), Space.EMPTY, Markers.EMPTY));
            JContainer<Expression> container = JContainer.build(Space.EMPTY, args, Markers.EMPTY);
            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                container
            );
        }

        private String extractIndent(Space prefix) {
            String ws = prefix.getWhitespace();
            int lastNewline = ws.lastIndexOf('\n');
            if (lastNewline >= 0) {
                return ws.substring(lastNewline + 1);
            }
            return "";
        }

        private String buildOriginalCodeString(J.Annotation annotation) {
            StringBuilder sb = new StringBuilder("@EJB");
            List<Expression> args = annotation.getArguments();
            if (args != null && !args.isEmpty()) {
                sb.append("(");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(args.get(i).printTrimmed(getCursor()));
                }
                sb.append(")");
            }
            return sb.toString();
        }

        private J.Annotation createNeedsReviewForJndiAttribute(String originalCode, Space prefix) {
            String reason = "JNDI/reference metadata not supported in Spring";

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

            List<JRightPadded<Expression>> args = new ArrayList<>();
            args.add(createAssignmentArg("reason", reason));
            args.add(createCategoryArg());
            args.add(createAssignmentArg("originalCode", originalCode));
            args.add(createAssignmentArg("suggestedAction", "Configure Spring bean manually or use @Value/@Qualifier"));

            JContainer<Expression> container = JContainer.build(Space.EMPTY, args, Markers.EMPTY);
            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                container
            );
        }

        private J.Annotation createNeedsReviewForClassLevel(String originalCode, Space prefix) {
            String reason = "Class-level @EJB requires manual migration";

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

            List<JRightPadded<Expression>> args = new ArrayList<>();
            args.add(createAssignmentArg("reason", reason));
            args.add(createCategoryArg());
            args.add(createAssignmentArg("originalCode", originalCode));
            args.add(createAssignmentArg("suggestedAction", "Convert to field injection or use Spring configuration"));

            JContainer<Expression> container = JContainer.build(Space.EMPTY, args, Markers.EMPTY);
            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                container
            );
        }

        private J.Annotation createNeedsReviewForMultiVarAnnotation(Expression beanNameExpr, Space prefix) {
            String beanName = beanNameExpr.printTrimmed(getCursor());
            String reason = "Multi-variable declaration with beanName: " + beanName;
            String originalCode = "@EJB(beanName=" + beanName + ")";

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

            List<JRightPadded<Expression>> args = new ArrayList<>();
            args.add(createAssignmentArg("reason", reason));
            args.add(createCategoryArg());
            args.add(createAssignmentArg("originalCode", originalCode));
            args.add(createAssignmentArg("suggestedAction", "Split declaration or add @Qualifier manually"));

            JContainer<Expression> container = JContainer.build(Space.EMPTY, args, Markers.EMPTY);
            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                container
            );
        }

        private JRightPadded<Expression> createAssignmentArg(String name, String value) {
            J.Identifier varIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                name,
                null,
                null
            );
            J.Literal literal = new J.Literal(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                value,
                "\"" + value.replace("\"", "\\\"") + "\"",
                null,
                JavaType.Primitive.String
            );
            J.Assignment assign = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                varIdent,
                new JLeftPadded<>(Space.EMPTY, literal, Markers.EMPTY),
                null
            );
            return new JRightPadded<>(assign, Space.EMPTY, Markers.EMPTY);
        }

        private JRightPadded<Expression> createCategoryArg() {
            J.Identifier varIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "category",
                null,
                null
            );
            // Create NeedsReview.Category.MANUAL_MIGRATION as field access
            J.Identifier needsReviewIdent = new J.Identifier(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                null,
                null
            );
            J.FieldAccess categoryAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                needsReviewIdent,
                new JLeftPadded<>(Space.EMPTY, new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "Category",
                    null,
                    null
                ), Markers.EMPTY),
                null
            );
            J.FieldAccess manualMigration = new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                categoryAccess,
                new JLeftPadded<>(Space.EMPTY, new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "MANUAL_MIGRATION",
                    null,
                    null
                ), Markers.EMPTY),
                null
            );
            J.Assignment assign = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                varIdent,
                new JLeftPadded<>(Space.EMPTY, manualMigration, Markers.EMPTY),
                null
            );
            return new JRightPadded<>(assign, Space.EMPTY, Markers.EMPTY);
        }
    }
}
