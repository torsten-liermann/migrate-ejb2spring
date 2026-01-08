package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Marks Arquillian test classes for manual migration to Spring Boot Test.
 * <p>
 * Arquillian tests require significant manual migration because:
 * <ul>
 *   <li>ShrinkWrap deployment model has no Spring equivalent</li>
 *   <li>Container deployment differs from Spring Boot testing</li>
 *   <li>CDI injection patterns need Spring test equivalents</li>
 * </ul>
 * <p>
 * Migration targets:
 * <ul>
 *   <li>{@code @ExtendWith(ArquillianExtension.class)} -> {@code @SpringBootTest}</li>
 *   <li>ShrinkWrap -> Standard Maven test dependencies</li>
 *   <li>CDI Event mocks -> ApplicationEventPublisher mocks</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MarkArquillianTestsForMigration extends Recipe {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    // Arquillian types to detect
    private static final String ARQUILLIAN_EXTENSION = "org.jboss.arquillian.junit5.ArquillianExtension";
    private static final String ARQUILLIAN_RUNNER = "org.jboss.arquillian.junit.Arquillian";
    private static final String DEPLOYMENT = "org.jboss.arquillian.container.test.api.Deployment";

    // ShrinkWrap types
    private static final String SHRINKWRAP = "org.jboss.shrinkwrap.api.ShrinkWrap";
    private static final String WEB_ARCHIVE = "org.jboss.shrinkwrap.api.spec.WebArchive";
    private static final String JAVA_ARCHIVE = "org.jboss.shrinkwrap.api.spec.JavaArchive";
    private static final String ENTERPRISE_ARCHIVE = "org.jboss.shrinkwrap.api.spec.EnterpriseArchive";

    // Maven resolver
    private static final String MAVEN_RESOLVER = "org.jboss.shrinkwrap.resolver.api.maven.Maven";

    @Override
    public String getDisplayName() {
        return "Mark Arquillian tests for manual migration to Spring Boot Test";
    }

    @Override
    public String getDescription() {
        return "Adds @NeedsReview annotation to Arquillian test classes, indicating they need manual migration to @SpringBootTest with Testcontainers.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        // Note: No Preconditions.check() with UsesType because it requires type resolution.
        // The visitor itself detects Arquillian patterns via annotation names and return types.
        return new ArquillianTestVisitor();
    }

    private class ArquillianTestVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Check if class already has @NeedsReview with MANUAL_MIGRATION
            boolean hasNeedsReview = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> a.getSimpleName().equals("NeedsReview"));

            if (hasNeedsReview) {
                return cd;
            }

            // Detect Arquillian usage
            String arquillianUsage = detectArquillianUsage(cd);
            if (arquillianUsage == null) {
                return cd;
            }

            // Add @NeedsReview annotation
            maybeAddImport(NEEDS_REVIEW_FQN);
            // Static import for enum values
            doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN + ".Category", "MANUAL_MIGRATION", false));

            Space prefix = cd.getLeadingAnnotations().isEmpty()
                ? cd.getPrefix()
                : cd.getLeadingAnnotations().get(0).getPrefix();

            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                "Arquillian test needs migration to @SpringBootTest",
                "MANUAL_MIGRATION",
                arquillianUsage,
                "Replace Arquillian with @SpringBootTest, remove ShrinkWrap, use Testcontainers for integration tests",
                prefix
            );

            List<J.Annotation> newAnnotations = new ArrayList<>();
            newAnnotations.add(needsReviewAnn);

            for (int i = 0; i < cd.getLeadingAnnotations().size(); i++) {
                J.Annotation ann = cd.getLeadingAnnotations().get(i);
                if (i == 0) {
                    String ws = prefix.getWhitespace();
                    ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n"));
                }
                newAnnotations.add(ann);
            }

            return cd.withLeadingAnnotations(newAnnotations);
        }

        private String detectArquillianUsage(J.ClassDeclaration classDecl) {
            List<String> arquillianFeatures = new ArrayList<>();

            // Check class-level annotations
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                String simpleName = ann.getSimpleName();

                // Check for @ExtendWith(ArquillianExtension.class)
                if ("ExtendWith".equals(simpleName)) {
                    if (hasArquillianExtensionArg(ann)) {
                        arquillianFeatures.add("@ExtendWith(ArquillianExtension.class)");
                    }
                }
                // Check for @RunWith(Arquillian.class)
                if ("RunWith".equals(simpleName)) {
                    if (hasArquillianRunnerArg(ann)) {
                        arquillianFeatures.add("@RunWith(Arquillian.class)");
                    }
                }
            }

            // Check for @Deployment methods
            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                        for (J.Annotation ann : method.getLeadingAnnotations()) {
                            if ("Deployment".equals(ann.getSimpleName())) {
                                arquillianFeatures.add("@Deployment");
                            }
                        }
                    }
                }
            }

            // Check for ShrinkWrap usage in method bodies (simplified detection)
            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                        // Check return type for WebArchive, JavaArchive, etc.
                        if (method.getReturnTypeExpression() != null) {
                            JavaType returnType = method.getReturnTypeExpression().getType();
                            if (returnType instanceof JavaType.FullyQualified) {
                                String fqn = ((JavaType.FullyQualified) returnType).getFullyQualifiedName();
                                if (WEB_ARCHIVE.equals(fqn)) {
                                    if (!arquillianFeatures.contains("ShrinkWrap WebArchive")) {
                                        arquillianFeatures.add("ShrinkWrap WebArchive");
                                    }
                                } else if (JAVA_ARCHIVE.equals(fqn)) {
                                    if (!arquillianFeatures.contains("ShrinkWrap JavaArchive")) {
                                        arquillianFeatures.add("ShrinkWrap JavaArchive");
                                    }
                                } else if (ENTERPRISE_ARCHIVE.equals(fqn)) {
                                    if (!arquillianFeatures.contains("ShrinkWrap EnterpriseArchive")) {
                                        arquillianFeatures.add("ShrinkWrap EnterpriseArchive");
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (arquillianFeatures.isEmpty()) {
                // No Arquillian features detected - return null to skip marking
                return null;
            }

            return String.join(", ", arquillianFeatures);
        }

        private boolean hasArquillianExtensionArg(J.Annotation annotation) {
            if (annotation.getArguments() == null) {
                return false;
            }
            for (Expression arg : annotation.getArguments()) {
                String argStr = arg.toString();
                if (argStr.contains("ArquillianExtension")) {
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

            // Create MANUAL_MIGRATION identifier (works with static import of NeedsReview.Category.MANUAL_MIGRATION)
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
    }
}
