package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.maven.AddPlugin;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.RemovePlugin;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.tree.Xml;

import java.util.*;

/**
 * Migrates Maven build plugins from EJB to Spring Boot.
 * <p>
 * This recipe performs the following transformations:
 * <ul>
 *   <li>Removes {@code maven-ejb-plugin} - no longer needed for Spring Boot</li>
 *   <li>Adds {@code spring-boot-maven-plugin} - enables executable JAR creation</li>
 * </ul>
 * <p>
 * The spring-boot-maven-plugin is essential for:
 * <ul>
 *   <li>Building executable JARs with {@code mvn package}</li>
 *   <li>Running with {@code mvn spring-boot:run}</li>
 *   <li>Creating native images (GraalVM)</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateBuildPlugins extends ScanningRecipe<MigrateBuildPlugins.Accumulator> {

    private static final String DEFAULT_SPRING_BOOT_VERSION = "3.5.0";

    @Option(displayName = "Spring Boot Version",
            description = "The Spring Boot version to use for the maven plugin. " +
                          "Defaults to " + DEFAULT_SPRING_BOOT_VERSION + " if not specified.",
            example = "3.5.0",
            required = false)
    @Nullable
    String springBootVersion;

    public MigrateBuildPlugins() {
        this.springBootVersion = null;
    }

    public MigrateBuildPlugins(@Nullable String springBootVersion) {
        this.springBootVersion = springBootVersion;
    }

    private String getSpringBootVersion() {
        return springBootVersion != null ? springBootVersion : DEFAULT_SPRING_BOOT_VERSION;
    }

    @Override
    public String getDisplayName() {
        return "Migrate build plugins to Spring Boot";
    }

    @Override
    public String getDescription() {
        return "Removes maven-ejb-plugin and adds spring-boot-maven-plugin for Spring Boot compatibility. " +
               "Enables executable JAR packaging and 'mvn spring-boot:run'.";
    }

    static class Accumulator {
        // Track modules with EJB features
        Set<String> modulesWithEjb = new HashSet<>();
        // Track modules that already have spring-boot-maven-plugin in <build><plugins>
        Set<String> modulesWithSpringBootPlugin = new HashSet<>();
        // Track modules that have maven-ejb-plugin
        Set<String> modulesWithEjbPlugin = new HashSet<>();
        // Track modules with ejb packaging (indicates EJB module even without Java imports)
        Set<String> modulesWithEjbPackaging = new HashSet<>();
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
                // Scan Java files for EJB annotations
                if (tree instanceof org.openrewrite.java.tree.J.CompilationUnit) {
                    org.openrewrite.java.tree.J.CompilationUnit cu =
                        (org.openrewrite.java.tree.J.CompilationUnit) tree;
                    String modulePath = getModulePath(cu.getSourcePath().toString());

                    for (org.openrewrite.java.tree.J.Import imp : cu.getImports()) {
                        String importPath = imp.getQualid().toString();
                        if (isEjbAnnotation(importPath)) {
                            acc.modulesWithEjb.add(modulePath);
                            break;
                        }
                    }
                }

                // Scan POM files for existing plugins, packaging, and Spring Boot version
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    String sourcePath = doc.getSourcePath().toString().replace('\\', '/');
                    if (sourcePath.endsWith("pom.xml")) {
                        String modulePath = getModulePathFromPom(sourcePath);

                        // Use XML-aware scanning to detect plugins
                        scanPomForPluginsAndPackaging(doc, modulePath, acc);
                    }
                }

                return tree;
            }

            private void scanPomForPluginsAndPackaging(Xml.Document doc, String modulePath, Accumulator acc) {
                // XPath matchers for precise detection
                XPathMatcher buildPluginMatcher = new XPathMatcher("/project/build/plugins/plugin");
                XPathMatcher packagingMatcher = new XPathMatcher("/project/packaging");
                XPathMatcher bootDependencyBomMatcher = new XPathMatcher("/project/dependencyManagement/dependencies/dependency");
                XPathMatcher parentArtifactMatcher = new XPathMatcher("/project/parent/artifactId");
                XPathMatcher parentVersionMatcher = new XPathMatcher("/project/parent/version");

                new org.openrewrite.xml.XmlVisitor<Accumulator>() {
                    boolean inBuildPlugin = false;
                    String currentArtifactId = null;
                    String currentGroupId = null;
                    String currentVersion = null;
                    boolean inBomDependency = false;
                    String bomGroupId = null;
                    String bomArtifactId = null;
                    String bomVersion = null;
                    String parentArtifactId = null;
                    String parentVersion = null;

                    @Override
                    public Xml visitTag(Xml.Tag tag, Accumulator a) {
                        // Check for packaging=ejb
                        if (packagingMatcher.matches(getCursor())) {
                            String packaging = tag.getValue().orElse("");
                            if ("ejb".equals(packaging)) {
                                a.modulesWithEjbPackaging.add(modulePath);
                            }
                        }

                        // Check for build plugins (not pluginManagement)
                        if (buildPluginMatcher.matches(getCursor())) {
                            // Extract artifactId from this plugin tag
                            String artifactId = extractChildValue(tag, "artifactId");
                            if ("spring-boot-maven-plugin".equals(artifactId)) {
                                a.modulesWithSpringBootPlugin.add(modulePath);
                            }
                            if ("maven-ejb-plugin".equals(artifactId)) {
                                a.modulesWithEjbPlugin.add(modulePath);
                            }
                        }

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
                if (pomPath.equals("pom.xml")) {
                    return "";
                }
                int pomIndex = pomPath.indexOf("/pom.xml");
                if (pomIndex > 0) {
                    return pomPath.substring(0, pomIndex);
                }
                return "";
            }

            private boolean isEjbAnnotation(String importPath) {
                return importPath.startsWith("jakarta.ejb.") ||
                       importPath.startsWith("javax.ejb.");
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof Xml.Document)) {
                    return tree;
                }

                Xml.Document doc = (Xml.Document) tree;
                String sourcePath = doc.getSourcePath().toString().replace('\\', '/');
                if (!sourcePath.endsWith("pom.xml")) {
                    return tree;
                }

                String modulePath = getModulePathFromPom(sourcePath);
                Tree result = tree;

                // Process modules that have EJB features, maven-ejb-plugin, or ejb packaging
                boolean shouldProcess = acc.modulesWithEjb.contains(modulePath) ||
                                        acc.modulesWithEjbPlugin.contains(modulePath) ||
                                        acc.modulesWithEjbPackaging.contains(modulePath) ||
                                        (acc.modulesWithEjb.isEmpty() &&
                                         acc.modulesWithEjbPlugin.isEmpty() &&
                                         acc.modulesWithEjbPackaging.isEmpty());

                if (!shouldProcess) {
                    return tree;
                }

                // 1. Remove maven-ejb-plugin if present
                if (acc.modulesWithEjbPlugin.contains(modulePath) || acc.modulesWithEjbPlugin.isEmpty()) {
                    result = new RemovePlugin(
                        "org.apache.maven.plugins",
                        "maven-ejb-plugin"
                    ).getVisitor().visit(result, ctx);
                }

                // 2. Add spring-boot-maven-plugin if not already present in <build><plugins>
                if (!acc.modulesWithSpringBootPlugin.contains(modulePath)) {
                    // Use detected version from BOM/parent, or explicit option, or default
                    String versionToUse = getEffectiveVersion(modulePath, acc);

                    result = new AddPlugin(
                        "org.springframework.boot",
                        "spring-boot-maven-plugin",
                        versionToUse,
                        null,  // configuration
                        null,  // dependencies
                        null,  // executions
                        null   // filePattern
                    ).getVisitor().visit(result, ctx);
                }

                return result;
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
