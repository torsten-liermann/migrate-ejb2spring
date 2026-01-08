package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.tree.Xml;

import java.util.*;

/**
 * Adds spring-boot-starter-test dependency when @SpringBootTest is used.
 * <p>
 * This recipe detects usage of {@code @SpringBootTest} in test sources and adds
 * the required {@code spring-boot-starter-test} dependency with test scope if not already present.
 * <p>
 * The dependency provides:
 * <ul>
 *   <li>JUnit 5</li>
 *   <li>Spring Test support</li>
 *   <li>AssertJ, Hamcrest, Mockito</li>
 *   <li>JsonPath, JSONAssert</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddSpringBootTestDependency extends ScanningRecipe<AddSpringBootTestDependency.Accumulator> {

    private static final String DEFAULT_SPRING_BOOT_VERSION = "3.5.0";
    private static final String SPRING_BOOT_TEST_ANNOTATION = "org.springframework.boot.test.context.SpringBootTest";

    @Option(displayName = "Spring Boot Version",
            description = "The Spring Boot version to use. Defaults to " + DEFAULT_SPRING_BOOT_VERSION,
            example = "3.5.0",
            required = false)
    @Nullable
    String springBootVersion;

    public AddSpringBootTestDependency() {
        this.springBootVersion = null;
    }

    public AddSpringBootTestDependency(@Nullable String springBootVersion) {
        this.springBootVersion = springBootVersion;
    }

    private String getSpringBootVersion() {
        return springBootVersion != null ? springBootVersion : DEFAULT_SPRING_BOOT_VERSION;
    }

    @Override
    public String getDisplayName() {
        return "Add spring-boot-starter-test dependency";
    }

    @Override
    public String getDescription() {
        return "Adds spring-boot-starter-test dependency with test scope when @SpringBootTest is detected in test sources. " +
               "This is required for Spring Boot test support including @SpringBootTest annotation.";
    }

    static class Accumulator {
        // Track if @SpringBootTest is used anywhere
        boolean hasSpringBootTest = false;
        // Track modules with @SpringBootTest usage
        Set<String> modulesWithSpringBootTest = new HashSet<>();
        // Track modules that already have spring-boot-starter-test
        Set<String> modulesWithTestDependency = new HashSet<>();
        // Track detected Spring Boot version from BOM or parent
        Map<String, String> detectedSpringBootVersions = new HashMap<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                // Scan Java files for @SpringBootTest usage
                if (tree instanceof org.openrewrite.java.tree.J.CompilationUnit) {
                    org.openrewrite.java.tree.J.CompilationUnit cu =
                        (org.openrewrite.java.tree.J.CompilationUnit) tree;
                    // Normalize Windows paths
                    String sourcePath = cu.getSourcePath().toString().replace('\\', '/');

                    // Only check test sources
                    if (isTestSource(sourcePath)) {
                        String modulePath = getModulePath(sourcePath);

                        for (org.openrewrite.java.tree.J.Import imp : cu.getImports()) {
                            String importPath = imp.getQualid().toString();
                            if (SPRING_BOOT_TEST_ANNOTATION.equals(importPath)) {
                                acc.hasSpringBootTest = true;
                                acc.modulesWithSpringBootTest.add(modulePath);
                                break;
                            }
                        }

                        // Also check for direct annotation usage without import
                        for (org.openrewrite.java.tree.J.ClassDeclaration clazz : cu.getClasses()) {
                            for (org.openrewrite.java.tree.J.Annotation ann : clazz.getLeadingAnnotations()) {
                                if ("SpringBootTest".equals(ann.getSimpleName())) {
                                    acc.hasSpringBootTest = true;
                                    acc.modulesWithSpringBootTest.add(modulePath);
                                    break;
                                }
                            }
                        }
                    }
                }

                // Scan POM files for existing test dependency and Spring Boot version
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    // Normalize Windows paths
                    String sourcePath = doc.getSourcePath().toString().replace('\\', '/');
                    if (sourcePath.endsWith("pom.xml")) {
                        String modulePath = getModulePathFromPom(sourcePath);
                        String content = doc.printAll();

                        if (content.contains("spring-boot-starter-test")) {
                            acc.modulesWithTestDependency.add(modulePath);
                        }

                        // Detect Spring Boot version from BOM or parent
                        detectSpringBootVersion(doc, modulePath, acc);
                    }
                }

                return tree;
            }

            private void detectSpringBootVersion(Xml.Document doc, String modulePath, Accumulator acc) {
                XPathMatcher bootDependencyBomMatcher = new XPathMatcher("/project/dependencyManagement/dependencies/dependency");
                XPathMatcher parentArtifactMatcher = new XPathMatcher("/project/parent/artifactId");
                XPathMatcher parentVersionMatcher = new XPathMatcher("/project/parent/version");

                new org.openrewrite.xml.XmlVisitor<Accumulator>() {
                    String parentArtifactId = null;
                    String parentVersion = null;

                    @Override
                    public Xml visitTag(Xml.Tag tag, Accumulator a) {
                        // Check for spring-boot-dependencies BOM
                        if (bootDependencyBomMatcher.matches(getCursor())) {
                            String groupId = extractChildValue(tag, "groupId");
                            String artifactId = extractChildValue(tag, "artifactId");
                            String version = extractChildValue(tag, "version");
                            if ("org.springframework.boot".equals(groupId) &&
                                "spring-boot-dependencies".equals(artifactId) &&
                                version != null && !version.isEmpty()) {
                                a.detectedSpringBootVersions.put(modulePath, version);
                            }
                        }

                        // Check for spring-boot-starter-parent
                        if (parentArtifactMatcher.matches(getCursor())) {
                            parentArtifactId = tag.getValue().orElse("");
                        }
                        if (parentVersionMatcher.matches(getCursor())) {
                            parentVersion = tag.getValue().orElse("");
                        }

                        return super.visitTag(tag, a);
                    }

                    @Override
                    public Xml visitDocument(Xml.Document document, Accumulator a) {
                        Xml result = super.visitDocument(document, a);
                        // After visiting, check if parent is spring-boot-starter-parent
                        if ("spring-boot-starter-parent".equals(parentArtifactId) &&
                            parentVersion != null && !parentVersion.isEmpty()) {
                            a.detectedSpringBootVersions.put(modulePath, parentVersion);
                        }
                        return result;
                    }

                    private String extractChildValue(Xml.Tag parent, String childName) {
                        for (org.openrewrite.xml.tree.Content content : parent.getContent()) {
                            if (content instanceof Xml.Tag) {
                                Xml.Tag child = (Xml.Tag) content;
                                if (childName.equals(child.getName())) {
                                    return child.getValue().orElse("");
                                }
                            }
                        }
                        return null;
                    }
                }.visit(doc, acc);
            }

            private boolean isTestSource(String sourcePath) {
                // Normalize Windows paths and handle both absolute and relative paths
                sourcePath = sourcePath.replace('\\', '/');
                return sourcePath.contains("/src/test/") || sourcePath.startsWith("src/test/");
            }

            private String getModulePath(String sourcePath) {
                // Normalize Windows paths
                sourcePath = sourcePath.replace('\\', '/');
                int srcIndex = sourcePath.indexOf("/src/");
                if (srcIndex > 0) {
                    return sourcePath.substring(0, srcIndex);
                }
                // For paths starting with src/ (no leading slash), module path is empty
                if (sourcePath.startsWith("src/")) {
                    return "";
                }
                return "";
            }

            private String getModulePathFromPom(String pomPath) {
                pomPath = pomPath.replace('\\', '/');
                if (pomPath.equals("pom.xml")) {
                    return "";
                }
                int pomIndex = pomPath.indexOf("/pom.xml");
                if (pomIndex > 0) {
                    return pomPath.substring(0, pomIndex);
                }
                return "";
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // Only apply if @SpringBootTest is detected
        if (!acc.hasSpringBootTest) {
            return TreeVisitor.noop();
        }

        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof Xml.Document)) {
                    return tree;
                }

                Xml.Document doc = (Xml.Document) tree;
                // Normalize Windows paths
                String sourcePath = doc.getSourcePath().toString().replace('\\', '/');
                if (!sourcePath.endsWith("pom.xml")) {
                    return tree;
                }

                String modulePath = getModulePathFromPom(sourcePath);

                // Only add dependency to modules that use @SpringBootTest and don't already have it
                if (!acc.modulesWithSpringBootTest.contains(modulePath)) {
                    return tree;
                }

                if (acc.modulesWithTestDependency.contains(modulePath)) {
                    return tree;
                }

                // Use detected version from BOM/parent, or explicit option, or default
                String versionToUse = getEffectiveVersion(modulePath, acc);

                // Add spring-boot-starter-test with test scope
                // AddDependency parameters: groupId, artifactId, version, versionPattern, scope,
                // releasesOnly, onlyIfUsing, type, classifier, optional, familyPattern, acceptTransitive
                return new AddDependency(
                    "org.springframework.boot",
                    "spring-boot-starter-test",
                    versionToUse,
                    null,   // versionPattern
                    "test", // scope
                    null,   // releasesOnly
                    null,   // onlyIfUsing
                    null,   // type
                    null,   // classifier
                    null,   // optional
                    null,   // familyPattern
                    null    // acceptTransitive
                ).getVisitor().visit(tree, ctx);
            }

            private String getEffectiveVersion(String modulePath, Accumulator acc) {
                // 1. User-provided option takes precedence
                if (springBootVersion != null) {
                    return springBootVersion;
                }
                // 2. If BOM or parent detected, return null to let Maven resolve from dependencyManagement
                String detected = acc.detectedSpringBootVersions.get(modulePath);
                if (detected != null && !detected.isEmpty()) {
                    return null; // Let Maven's dependencyManagement resolve the version
                }
                // 3. Check parent modules (empty string is root)
                if (!modulePath.isEmpty()) {
                    detected = acc.detectedSpringBootVersions.get("");
                    if (detected != null && !detected.isEmpty()) {
                        return null; // Let Maven's dependencyManagement resolve the version
                    }
                }
                // 4. Fall back to default only when no BOM/parent exists
                return DEFAULT_SPRING_BOOT_VERSION;
            }

            private String getModulePathFromPom(String pomPath) {
                pomPath = pomPath.replace('\\', '/');
                if (pomPath.equals("pom.xml")) {
                    return "";
                }
                int pomIndex = pomPath.indexOf("/pom.xml");
                if (pomIndex > 0) {
                    return pomPath.substring(0, pomIndex);
                }
                return "";
            }
        };
    }
}
