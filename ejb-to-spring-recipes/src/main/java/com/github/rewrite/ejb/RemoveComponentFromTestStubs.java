package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Einfache Recipe: Entfernt @Component/@Service/@Repository von Test-Stubs
 * und fuegt @NeedsStubReview Annotation hinzu.
 * <p>
 * Dies ist der einfache Ansatz - keine TestConfiguration Generierung,
 * keine @Import Manipulation. Der Entwickler entscheidet selbst ueber
 * weitere Schritte.
 * <p>
 * Fuer den komplexeren Ansatz mit TestConfiguration Generierung siehe:
 * {@link MigrateTestStubsToTestConfiguration}
 * <p>
 * Transformation:
 * <pre>
 * BEFORE:
 * &#64;Component
 * public class ApplicationEventsStub implements ApplicationEvents { }
 *
 * AFTER:
 * &#64;NeedsStubReview("@Component entfernt - manuelle Pruefung erforderlich")
 * public class ApplicationEventsStub implements ApplicationEvents { }
 * </pre>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveComponentFromTestStubs extends Recipe {

    private static final String COMPONENT_FQN = "org.springframework.stereotype.Component";
    private static final String SERVICE_FQN = "org.springframework.stereotype.Service";
    private static final String REPOSITORY_FQN = "org.springframework.stereotype.Repository";

    private static final Set<String> SPRING_STEREOTYPES = Set.of(COMPONENT_FQN, SERVICE_FQN, REPOSITORY_FQN);
    private static final Set<String> SPRING_STEREOTYPE_SIMPLE_NAMES = Set.of("Component", "Service", "Repository");
    private static final List<String> STUB_NAME_PATTERNS = List.of("Stub", "Mock", "Fake");

    private static final String NEEDS_STUB_REVIEW_FQN = "com.github.migration.NeedsStubReview";
    private static final String NEEDS_STUB_REVIEW_STUB =
        "package com.github.migration;\n" +
        "import java.lang.annotation.*;\n" +
        "@Target(ElementType.TYPE)\n" +
        "@Retention(RetentionPolicy.SOURCE)\n" +
        "@Documented\n" +
        "public @interface NeedsStubReview { String value() default \"\"; }\n";

    @Override
    public String getDisplayName() {
        return "Remove @Component from test stubs";
    }

    @Override
    public String getDescription() {
        return "Removes @Component/@Service/@Repository from test stub classes and adds " +
               "@NeedsStubReview annotation for manual review. Simple approach without " +
               "TestConfiguration generation.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Only process top-level classes
                if (getCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) {
                    return cd;
                }

                Cursor cursor = getCursor();
                J.CompilationUnit cu = cursor.firstEnclosingOrThrow(J.CompilationUnit.class);
                String sourcePath = cu.getSourcePath() != null ? cu.getSourcePath().toString().replace('\\', '/') : "";

                // Only process test sources
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                        extractProjectRoot(cu.getSourcePath()));
                if (!config.isTestSource(sourcePath)) {
                    return cd;
                }

                String className = cd.getSimpleName();

                // Check if class name matches stub pattern
                boolean isStubByName = STUB_NAME_PATTERNS.stream().anyMatch(className::endsWith);
                if (!isStubByName) {
                    return cd;
                }

                // Check if has Spring stereotype annotation
                String stereotypeAnnotation = getSpringStereotypeAnnotation(cd);
                if (stereotypeAnnotation == null) {
                    return cd;
                }

                // Remove @Component and add @NeedsStubReview
                return removeStereotypeAndAddReview(cd, stereotypeAnnotation);
            }

            private String getSpringStereotypeAnnotation(J.ClassDeclaration classDecl) {
                for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                    String simpleName = ann.getSimpleName();
                    if (SPRING_STEREOTYPE_SIMPLE_NAMES.contains(simpleName)) {
                        return simpleName;
                    }
                    if (ann.getType() != null) {
                        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ann.getType());
                        if (fq != null && SPRING_STEREOTYPES.contains(fq.getFullyQualifiedName())) {
                            return fq.getClassName();
                        }
                    }
                }
                return null;
            }

            private J.ClassDeclaration removeStereotypeAndAddReview(J.ClassDeclaration cd, String removedAnnotation) {
                // Check if already has @NeedsStubReview
                boolean hasReviewAnnotation = cd.getLeadingAnnotations().stream()
                        .anyMatch(ann -> "NeedsStubReview".equals(ann.getSimpleName()));

                // IMPORTANT: Apply template FIRST (before modifying annotations list)
                // because getCursor() points to original tree and template.apply()
                // would overwrite any prior withLeadingAnnotations() changes
                if (!hasReviewAnnotation) {
                    maybeAddImport(NEEDS_STUB_REVIEW_FQN);

                    String reviewMessage = "@" + removedAnnotation + " entfernt - manuelle Pruefung erforderlich";
                    JavaTemplate reviewTemplate = JavaTemplate
                            .builder("@NeedsStubReview(\"" + reviewMessage + "\")")
                            .javaParser(JavaParser.fromJavaVersion().dependsOn(NEEDS_STUB_REVIEW_STUB))
                            .imports(NEEDS_STUB_REVIEW_FQN)
                            .build();

                    cd = reviewTemplate.apply(
                            getCursor(),
                            cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
                    );
                }

                // NOW remove Spring stereotype annotations from the (possibly modified) list
                List<J.Annotation> newAnnotations = new ArrayList<>();
                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    if (isSpringStereotype(ann)) {
                        String fqnToRemove = getAnnotationFqn(ann);
                        if (fqnToRemove != null) {
                            maybeRemoveImport(fqnToRemove);
                        }
                    } else {
                        newAnnotations.add(ann);
                    }
                }

                return cd.withLeadingAnnotations(newAnnotations);
            }

            private boolean isSpringStereotype(J.Annotation ann) {
                if (SPRING_STEREOTYPE_SIMPLE_NAMES.contains(ann.getSimpleName())) {
                    return true;
                }
                if (ann.getType() != null) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ann.getType());
                    return fq != null && SPRING_STEREOTYPES.contains(fq.getFullyQualifiedName());
                }
                return false;
            }

            private String getAnnotationFqn(J.Annotation ann) {
                if (ann.getType() != null) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ann.getType());
                    if (fq != null) {
                        return fq.getFullyQualifiedName();
                    }
                }
                // Fallback to simple name mapping
                String simpleName = ann.getSimpleName();
                switch (simpleName) {
                    case "Component": return COMPONENT_FQN;
                    case "Service": return SERVICE_FQN;
                    case "Repository": return REPOSITORY_FQN;
                    default: return null;
                }
            }

            private Path extractProjectRoot(Path sourcePath) {
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
        };
    }
}
