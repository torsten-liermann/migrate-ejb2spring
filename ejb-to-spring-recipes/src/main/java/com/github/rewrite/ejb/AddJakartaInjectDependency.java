/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSR-330 support - adds jakarta.inject-api dependency for keep-jsr330 strategy
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
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Adds the jakarta.inject-api dependency when JSR-330 annotations are used
 * and the inject strategy is keep-jsr330 (default).
 * <p>
 * This recipe:
 * <ul>
 *   <li>Detects usage of {@code @Named} and {@code @Inject} annotations</li>
 *   <li>Adds {@code jakarta.inject:jakarta.inject-api} dependency if keep-jsr330 strategy is configured</li>
 *   <li>Supports both jakarta.inject and javax.inject namespaces (adds jakarta.inject-api for Spring 3.x compatibility)</li>
 * </ul>
 * <p>
 * The jakarta.inject-api artifact provides Spring-compatible JSR-330 annotations:
 * <ul>
 *   <li>{@code @Named} - equivalent to {@code @Component}</li>
 *   <li>{@code @Inject} - equivalent to {@code @Autowired}</li>
 *   <li>{@code @Qualifier} - qualifier annotation support</li>
 *   <li>{@code @Singleton} - singleton scope</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddJakartaInjectDependency extends ScanningRecipe<AddJakartaInjectDependency.Accumulator> {

    private static final String JAKARTA_INJECT_GROUP_ID = "jakarta.inject";
    private static final String JAKARTA_INJECT_ARTIFACT_ID = "jakarta.inject-api";
    private static final String DEFAULT_VERSION = "2.0.1";

    // Legacy javax.inject coordinates - semantically equivalent to jakarta.inject-api
    private static final String JAVAX_INJECT_GROUP_ID = "javax.inject";
    private static final String JAVAX_INJECT_ARTIFACT_ID = "javax.inject";

    // JSR-330 annotations that require the jakarta.inject-api dependency
    private static final Set<String> JSR330_ANNOTATIONS = Set.of(
        "jakarta.inject.Named",
        "jakarta.inject.Inject",
        "jakarta.inject.Qualifier",
        "jakarta.inject.Singleton",
        "jakarta.inject.Provider",
        "javax.inject.Named",
        "javax.inject.Inject",
        "javax.inject.Qualifier",
        "javax.inject.Singleton",
        "javax.inject.Provider"
    );

    @Option(displayName = "Inject strategy override",
            description = "Override project.yaml inject strategy: keep-jsr330 or migrate-to-spring. " +
                          "If not set, project.yaml (or defaults) are used. Default strategy is keep-jsr330.",
            example = "keep-jsr330",
            required = false)
    @Nullable
    String strategy;

    public AddJakartaInjectDependency() {
        this.strategy = null;
    }

    public AddJakartaInjectDependency(@Nullable String strategy) {
        this.strategy = strategy;
    }

    @Override
    public String getDisplayName() {
        return "Add jakarta.inject-api dependency for JSR-330 support";
    }

    @Override
    public String getDescription() {
        return "Adds the jakarta.inject:jakarta.inject-api Maven dependency when JSR-330 annotations " +
               "(@Named, @Inject) are detected and the inject strategy is keep-jsr330 (default). " +
               "This allows Spring to process JSR-330 annotations natively without migration.";
    }

    static class Accumulator {
        boolean needsJakartaInject = false;
        boolean shouldKeepJsr330 = false;
        Set<String> modulesNeedingDependency = new HashSet<>();
        // WFQ-006: Track modules where JSR-330 dependency already exists
        Set<String> modulesWithExistingJsr330 = new HashSet<>();
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
                // Scan Java files for JSR-330 annotation usage
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    Path sourcePath = cu.getSourcePath();
                    String modulePath = getModulePath(sourcePath != null ? sourcePath.toString() : "");

                    // Check if strategy is keep-jsr330
                    if (!acc.shouldKeepJsr330) {
                        acc.shouldKeepJsr330 = shouldKeepJsr330(sourcePath);
                    }

                    // Only proceed if we should keep JSR-330
                    if (!acc.shouldKeepJsr330) {
                        return tree;
                    }

                    // Check imports for JSR-330 annotations
                    for (J.Import imp : cu.getImports()) {
                        String importPath = imp.getQualid().toString();
                        if (JSR330_ANNOTATIONS.contains(importPath)) {
                            acc.needsJakartaInject = true;
                            acc.modulesNeedingDependency.add(modulePath);
                            return tree;
                        }
                    }

                    // Also check annotation types directly (for FQN usages like @jakarta.inject.Named)
                    new JavaIsoVisitor<ExecutionContext>() {
                        @Override
                        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                            for (String jsr330Ann : JSR330_ANNOTATIONS) {
                                if (TypeUtils.isOfClassType(annotation.getType(), jsr330Ann)) {
                                    acc.needsJakartaInject = true;
                                    acc.modulesNeedingDependency.add(modulePath);
                                    break;
                                }
                            }
                            return super.visitAnnotation(annotation, ctx);
                        }
                    }.visit(cu, ctx);
                }

                // WFQ-006: Scan POM files for existing JSR-330 dependencies (declared, not transitive)
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    String sourcePath = doc.getSourcePath().toString();
                    if (sourcePath.endsWith("pom.xml")) {
                        String modulePath = getModulePathFromPom(sourcePath);
                        new MavenIsoVisitor<ExecutionContext>() {
                            @Override
                            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                                MavenResolutionResult mrr = getResolutionResult();
                                if (mrr != null && mrr.getPom() != null) {
                                    // Check declared dependencies only (effective POM = direct + inherited from parent)
                                    // Note: dependencyManagement-only does NOT count as existing - it only provides versioning
                                    for (org.openrewrite.maven.tree.Dependency dep : mrr.getPom().getRequestedDependencies()) {
                                        if (isJsr330Dependency(dep.getGroupId(), dep.getArtifactId())) {
                                            acc.modulesWithExistingJsr330.add(modulePath);
                                            return document;
                                        }
                                    }
                                }
                                return document;
                            }

                            private boolean isJsr330Dependency(String groupId, String artifactId) {
                                return (JAKARTA_INJECT_GROUP_ID.equals(groupId) && JAKARTA_INJECT_ARTIFACT_ID.equals(artifactId))
                                    || (JAVAX_INJECT_GROUP_ID.equals(groupId) && JAVAX_INJECT_ARTIFACT_ID.equals(artifactId));
                            }
                        }.visit(doc, ctx);
                    }
                }

                return tree;
            }

            private String getModulePath(String sourcePath) {
                int srcIndex = sourcePath.indexOf("/src/");
                if (srcIndex > 0) {
                    return sourcePath.substring(0, srcIndex);
                }
                return "";
            }

            private String getModulePathFromPom(String sourcePath) {
                if (sourcePath.equals("pom.xml")) {
                    return "";
                }
                int pomIndex = sourcePath.indexOf("/pom.xml");
                if (pomIndex > 0) {
                    return sourcePath.substring(0, pomIndex);
                }
                return "";
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        return Collections.emptyList();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (!acc.needsJakartaInject || !acc.shouldKeepJsr330) {
            return TreeVisitor.noop();
        }

        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof Xml.Document)) {
                    return tree;
                }

                Xml.Document doc = (Xml.Document) tree;
                String sourcePath = doc.getSourcePath().toString();
                if (!sourcePath.endsWith("pom.xml")) {
                    return tree;
                }

                String modulePath = getModulePath(sourcePath);

                boolean isRootPom = modulePath.isEmpty();
                boolean moduleNeedsDependency = acc.modulesNeedingDependency.contains(modulePath);

                // Add to root pom or specific module pom
                if (!isRootPom && !moduleNeedsDependency) {
                    return tree;
                }

                // WFQ-006: Skip if JSR-330 dependency already exists (jakarta.inject-api or javax.inject)
                if (acc.modulesWithExistingJsr330.contains(modulePath)) {
                    return tree;
                }

                // Add jakarta.inject-api dependency
                tree = new AddDependency(
                    JAKARTA_INJECT_GROUP_ID,
                    JAKARTA_INJECT_ARTIFACT_ID,
                    DEFAULT_VERSION,
                    null,        // versionPattern
                    null,        // scope (compile is default)
                    null,        // releasesOnly
                    null,        // onlyIfUsing
                    null,        // type
                    null,        // classifier
                    null,        // optional
                    null,        // familyPattern
                    null         // acceptTransitive
                ).getVisitor().visit(tree, ctx);

                return tree;
            }

            private String getModulePath(String sourcePath) {
                if (sourcePath.equals("pom.xml")) {
                    return "";
                }
                int pomIndex = sourcePath.indexOf("/pom.xml");
                if (pomIndex > 0) {
                    return sourcePath.substring(0, pomIndex);
                }
                return "";
            }
        };
    }

    /**
     * Checks if JSR-330 annotations should be kept based on project.yaml or strategy override.
     * Returns true if strategy is KEEP_JSR330 (default).
     */
    private boolean shouldKeepJsr330(@Nullable Path sourcePath) {
        ProjectConfiguration.InjectStrategy override = ProjectConfiguration.InjectStrategy.fromString(strategy);
        if (strategy != null && override == null) {
            System.err.println("Warning: Unknown inject strategy override '" + strategy +
                    "', using project.yaml/defaults.");
        }
        if (override != null) {
            return override == ProjectConfiguration.InjectStrategy.KEEP_JSR330;
        }
        if (sourcePath == null) {
            return ProjectConfiguration.mavenDefaults().getInjectStrategy()
                    == ProjectConfiguration.InjectStrategy.KEEP_JSR330;
        }
        ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(sourcePath));
        return config.getInjectStrategy() == ProjectConfiguration.InjectStrategy.KEEP_JSR330;
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
}
