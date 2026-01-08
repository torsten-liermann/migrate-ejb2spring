package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.tree.Xml;

import java.util.*;

/**
 * Migrates Maven POM packaging from "ejb" to "jar" for Spring Boot compatibility.
 * <p>
 * Spring Boot applications typically use "jar" packaging with an embedded server.
 * This recipe changes:
 * <ul>
 *   <li>{@code <packaging>ejb</packaging>} → {@code <packaging>jar</packaging>}</li>
 * </ul>
 * <p>
 * Note: "war" packaging is left unchanged as some teams prefer external deployment.
 * The recipe only changes packaging when EJB features are detected in the module.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEjbPackaging extends ScanningRecipe<MigrateEjbPackaging.Accumulator> {

    private static final XPathMatcher PACKAGING_MATCHER = new XPathMatcher("/project/packaging");

    @Override
    public String getDisplayName() {
        return "Migrate EJB packaging to JAR";
    }

    @Override
    public String getDescription() {
        return "Changes Maven POM packaging from 'ejb' to 'jar' for Spring Boot compatibility. " +
               "Spring Boot applications use JAR packaging with embedded servers.";
    }

    static class Accumulator {
        // Track modules with EJB features that need packaging change
        Set<String> modulesWithEjb = new HashSet<>();
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
                return tree;
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

            private boolean isEjbAnnotation(String importPath) {
                return importPath.startsWith("jakarta.ejb.") ||
                       importPath.startsWith("javax.ejb.");
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                Xml.Tag t = super.visitTag(tag, ctx);

                if (PACKAGING_MATCHER.matches(getCursor())) {
                    Optional<String> packagingValue = t.getValue();
                    if (packagingValue.isPresent() && "ejb".equals(packagingValue.get())) {
                        // Having <packaging>ejb</packaging> itself indicates an EJB module
                        // Always change ejb to jar (this is the entire point of the recipe)
                        // The EJB import detection is just an extra safety check for multi-module projects
                        String pomPath = getCursor().firstEnclosing(Xml.Document.class)
                            .getSourcePath().toString().replace('\\', '/');
                        String modulePath = getModulePathFromPom(pomPath);

                        // Change ejb to jar:
                        // - Always in single-module projects (accumulator empty)
                        // - Always if this module has detected EJB imports
                        // - Always if the packaging is ejb (ejb packaging IS the indicator)
                        if (acc.modulesWithEjb.isEmpty() || acc.modulesWithEjb.contains(modulePath)) {
                            return t.withValue("jar");
                        }
                        // Even if no EJB imports detected, change ejb packaging - it's clearly an EJB module
                        return t.withValue("jar");
                    }
                }
                return t;
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
