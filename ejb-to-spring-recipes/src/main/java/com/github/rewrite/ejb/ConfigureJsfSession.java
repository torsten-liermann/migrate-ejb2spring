/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - ViewScoped session configuration
 * Modified: Codex P2.3 - Added multi-module support, annotation-based detection
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
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.tree.Properties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Generates session configuration for JSF @ViewScoped beans in application.properties.
 * <p>
 * When @ViewScoped beans are detected (from jakarta.faces.view, javax.faces.view, or org.omnifaces.cdi),
 * this recipe generates the required session tracking configuration for proper ViewScoped behavior.
 * <p>
 * <b>Detection logic (annotation-based, not import-only):</b>
 * <ul>
 *   <li>jakarta.faces.view.ViewScoped - JSF 4.0 (Jakarta) -> Generate session config</li>
 *   <li>javax.faces.view.ViewScoped - JSF 2.x (Legacy) -> Generate session config</li>
 *   <li>org.omnifaces.cdi.ViewScoped - OmniFaces -> Generate session config</li>
 *   <li>org.springframework.web.context.annotation.ViewScoped - Spring (NOT JSF!) -> NO action</li>
 * </ul>
 * <p>
 * <b>Multi-module support:</b> Tracks JSF ViewScoped usage per source root.
 * Generates one application.properties per module that has JSF ViewScoped usage but no existing config.
 * <p>
 * <b>Generated configuration (only if not already present):</b>
 * <pre>
 * server.servlet.session.tracking-modes=cookie
 * server.servlet.session.cookie.same-site=lax
 * joinfaces.jsf.client-window-mode=url
 * </pre>
 * <p>
 * <b>Important:</b> This recipe does NOT overwrite existing properties. User-defined values take precedence.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ConfigureJsfSession extends ScanningRecipe<ConfigureJsfSession.Accumulator> {

    private static final String MIGRATION_MARKER = "# ===== JSF ViewScoped Session Configuration =====";

    // JSF ViewScoped FQCNs that require session configuration
    private static final Set<String> JSF_VIEW_SCOPED_FQCNS = new HashSet<>(Arrays.asList(
        "jakarta.faces.view.ViewScoped",
        "javax.faces.view.ViewScoped",
        "org.omnifaces.cdi.ViewScoped"
    ));

    // Spring ViewScoped FQCN - should NOT trigger session config generation
    private static final String SPRING_VIEW_SCOPED_FQCN = "org.springframework.web.context.annotation.ViewScoped";

    // Properties to generate
    private static final String PROP_TRACKING_MODES = "server.servlet.session.tracking-modes";
    private static final String PROP_SAME_SITE = "server.servlet.session.cookie.same-site";
    private static final String PROP_CLIENT_WINDOW_MODE = "joinfaces.jsf.client-window-mode";

    @Override
    public String getDisplayName() {
        return "Configure JSF session for ViewScoped beans";
    }

    @Override
    public String getDescription() {
        return "Generates session tracking configuration in application.properties when JSF @ViewScoped beans are detected. " +
               "Required for proper ViewScoped behavior with JoinFaces. Does not overwrite existing property values. " +
               "Supports multi-module projects by generating configuration per module.";
    }

    /**
     * Tracks per-module state for multi-module support.
     */
    static class ModuleState {
        final Path moduleRoot;
        boolean hasJsfViewScoped = false;
        boolean hasApplicationProperties = false;
        Set<String> existingPropertyKeys = new HashSet<>();
        boolean alreadyHasMigrationMarker = false;
        Path applicationPropertiesPath = null;

        ModuleState(Path moduleRoot, Path applicationPropertiesPath) {
            this.moduleRoot = moduleRoot;
            this.applicationPropertiesPath = applicationPropertiesPath;
        }
    }

    /**
     * Accumulator that tracks state per source root for multi-module support.
     */
    static class Accumulator {
        final Map<Path, ModuleState> modules = new ConcurrentHashMap<>();
        final Map<Path, ProjectConfiguration> configCache = new ConcurrentHashMap<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    String path = sourceFile.getSourcePath().toString().replace('\\', '/');
                    Path sourcePath = sourceFile.getSourcePath();

                    // Skip test sources
                    if (isTestSource(path)) {
                        return tree;
                    }

                    Path moduleRoot = extractModuleRoot(sourcePath);
                    Path configRoot = resolveConfigRoot(moduleRoot);
                    ProjectConfiguration config = acc.configCache.computeIfAbsent(
                        configRoot, ProjectConfigurationLoader::load);
                    ModuleState moduleState = acc.modules.computeIfAbsent(
                        moduleRoot,
                        root -> new ModuleState(moduleRoot,
                            resolveApplicationPropertiesPath(config, sourcePath, moduleRoot)));

                    // Handle application.properties
                    if (sourcePath.equals(moduleState.applicationPropertiesPath)) {
                        moduleState.hasApplicationProperties = true;

                        if (sourceFile instanceof Properties.File) {
                            scanExistingProperties((Properties.File) sourceFile, moduleState);
                        } else {
                            String content = sourceFile.printAll();
                            extractPropertyKeys(content, moduleState);
                        }
                        return tree;
                    }

                    // Check Java files for JSF ViewScoped annotations
                    if (tree instanceof J.CompilationUnit) {
                        J.CompilationUnit cu = (J.CompilationUnit) tree;

                        // Check class annotations (annotation-based detection)
                        for (J.ClassDeclaration classDecl : cu.getClasses()) {
                            if (hasJsfViewScopedAnnotation(classDecl)) {
                                moduleState.hasJsfViewScoped = true;
                                break;
                            }
                        }

                        // Also check imports for compatibility (catches edge cases)
                        // But skip if already detected via annotation
                        if (!moduleState.hasJsfViewScoped) {
                            for (J.Import imp : cu.getImports()) {
                                String importPath = imp.getQualid().toString();
                                if (JSF_VIEW_SCOPED_FQCNS.contains(importPath)) {
                                    // Only set if the import is actually used on a class
                                    // This prevents false positives from unused imports
                                    for (J.ClassDeclaration classDecl : cu.getClasses()) {
                                        if (hasViewScopedAnnotationSimpleName(classDecl)) {
                                            moduleState.hasJsfViewScoped = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return tree;
            }
        };
    }

    /**
     * Checks if a class has @ViewScoped annotation using type attribution (FQCN check).
     * This handles both imported and fully-qualified annotations.
     */
    private boolean hasJsfViewScopedAnnotation(J.ClassDeclaration classDecl) {
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            JavaType type = ann.getType();
            if (type instanceof JavaType.FullyQualified) {
                String fqcn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
                if (JSF_VIEW_SCOPED_FQCNS.contains(fqcn)) {
                    return true;
                }
            }
            // Fallback: check annotation simple name and field access for FQCN usage
            if (ann.getAnnotationType() instanceof J.FieldAccess) {
                String fqcn = ((J.FieldAccess) ann.getAnnotationType()).toString();
                if (JSF_VIEW_SCOPED_FQCNS.contains(fqcn)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Checks if a class has @ViewScoped annotation by simple name only.
     * Used as fallback when import is present.
     * <p>
     * Important: This only returns true if:
     * 1. The annotation uses simple name "ViewScoped" (not FQCN)
     * 2. The annotation is NOT a FQCN for Spring ViewScoped
     */
    private boolean hasViewScopedAnnotationSimpleName(J.ClassDeclaration classDecl) {
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            // Skip FQCN annotations - they should be handled by hasJsfViewScopedAnnotation()
            // This prevents false positives when a class has @org.springframework.web.context.annotation.ViewScoped
            // but also has an unused jakarta.faces.view.ViewScoped import
            if (ann.getAnnotationType() instanceof J.FieldAccess) {
                // FQCN annotation like @org.springframework.web.context.annotation.ViewScoped
                // or @jakarta.faces.view.ViewScoped - skip here, handled by primary detection
                continue;
            }
            // Check simple name for imports-based detection
            if ("ViewScoped".equals(ann.getSimpleName())) {
                return true;
            }
        }
        return false;
    }

    private void scanExistingProperties(Properties.File propertiesFile, ModuleState moduleState) {
        for (Properties.Content content : propertiesFile.getContent()) {
            if (content instanceof Properties.Entry) {
                Properties.Entry entry = (Properties.Entry) content;
                moduleState.existingPropertyKeys.add(entry.getKey());
            } else if (content instanceof Properties.Comment) {
                Properties.Comment comment = (Properties.Comment) content;
                if (comment.getMessage().contains(MIGRATION_MARKER.substring(2))) {
                    moduleState.alreadyHasMigrationMarker = true;
                }
            }
        }
    }

    private void extractPropertyKeys(String content, ModuleState moduleState) {
        if (content.contains(MIGRATION_MARKER)) {
            moduleState.alreadyHasMigrationMarker = true;
        }
        for (String line : content.split("\n")) {
            line = line.trim();
            if (!line.isEmpty() && !line.startsWith("#") && line.contains("=")) {
                String key = line.substring(0, line.indexOf('=')).trim();
                moduleState.existingPropertyKeys.add(key);
            }
        }
    }

    private boolean isTestSource(String path) {
        return path.contains("/src/test/") ||
               path.contains("/src/it/") ||
               path.contains("/test/") && !path.contains("/main/") ||
               path.contains("/test-") ||
               path.startsWith("test/");
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // Collect modules that need updating (have application.properties)
        Map<Path, List<String>> modulesToUpdate = new HashMap<>();

        for (ModuleState moduleState : acc.modules.values()) {

            // Skip if no JSF ViewScoped or already migrated
            if (!moduleState.hasJsfViewScoped || moduleState.alreadyHasMigrationMarker) {
                continue;
            }

            // Skip if no application.properties exists (generate() handles this)
            if (!moduleState.hasApplicationProperties || moduleState.applicationPropertiesPath == null) {
                continue;
            }

            // Determine which properties need to be added
            List<String> propertiesToAdd = determinePropertiesToAdd(moduleState);
            if (!propertiesToAdd.isEmpty()) {
                modulesToUpdate.put(moduleState.applicationPropertiesPath, propertiesToAdd);
            }
        }

        if (modulesToUpdate.isEmpty()) {
            return TreeVisitor.noop();
        }

        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    List<String> propertiesToAdd = modulesToUpdate.get(sourceFile.getSourcePath());

                    if (propertiesToAdd != null) {
                        return updateApplicationProperties(sourceFile, propertiesToAdd);
                    }
                }
                return tree;
            }
        };
    }

    private List<String> determinePropertiesToAdd(ModuleState moduleState) {
        List<String> propertiesToAdd = new ArrayList<>();
        if (!moduleState.existingPropertyKeys.contains(PROP_TRACKING_MODES)) {
            propertiesToAdd.add(PROP_TRACKING_MODES + "=cookie");
        }
        if (!moduleState.existingPropertyKeys.contains(PROP_SAME_SITE)) {
            propertiesToAdd.add(PROP_SAME_SITE + "=lax");
        }
        if (!moduleState.existingPropertyKeys.contains(PROP_CLIENT_WINDOW_MODE)) {
            propertiesToAdd.add(PROP_CLIENT_WINDOW_MODE + "=url");
        }
        return propertiesToAdd;
    }

    private SourceFile updateApplicationProperties(SourceFile sourceFile, List<String> propertiesToAdd) {
        StringBuilder newContent = new StringBuilder();
        newContent.append("\n\n").append(MIGRATION_MARKER).append("\n");
        newContent.append("# Generated because @ViewScoped beans detected\n");

        for (int i = 0; i < propertiesToAdd.size(); i++) {
            newContent.append(propertiesToAdd.get(i));
            if (i < propertiesToAdd.size() - 1) {
                newContent.append("\n");
            }
        }

        String existingContent = sourceFile.printAll();
        if (existingContent.endsWith("\n")) {
            existingContent = existingContent.substring(0, existingContent.length() - 1);
        }
        String updatedContent = existingContent + newContent.toString();

        return PropertiesParser.builder().build()
            .parse(updatedContent)
            .findFirst()
            .map(parsed -> (SourceFile) ((Properties.File) parsed)
                .withId(sourceFile.getId())
                .withSourcePath(sourceFile.getSourcePath()))
            .orElse(sourceFile);
    }

    @Override
    public List<SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        List<SourceFile> generatedFiles = new ArrayList<>();

        for (ModuleState moduleState : acc.modules.values()) {

            // Skip if no JSF ViewScoped detected
            if (!moduleState.hasJsfViewScoped) {
                continue;
            }

            // Skip if application.properties already exists (getVisitor handles this)
            if (moduleState.hasApplicationProperties) {
                continue;
            }

            // Skip if already migrated
            if (moduleState.alreadyHasMigrationMarker) {
                continue;
            }

            // Determine which properties need to be added
            List<String> propertiesToAdd = determinePropertiesToAdd(moduleState);
            if (propertiesToAdd.isEmpty()) {
                continue;
            }

            // Generate new application.properties
            StringBuilder content = new StringBuilder();
            content.append("# Spring Boot Application Properties\n");
            content.append("# Generated for JSF ViewScoped session configuration\n\n");
            content.append(MIGRATION_MARKER).append("\n");
            content.append("# Generated because @ViewScoped beans detected\n");

            for (String prop : propertiesToAdd) {
                content.append(prop).append("\n");
            }

            PropertiesParser.builder().build()
                .parse(content.toString())
                .findFirst()
                .ifPresent(parsed -> generatedFiles.add(
                    (SourceFile) parsed.withSourcePath(moduleState.applicationPropertiesPath)));
        }

        return generatedFiles;
    }

    private static Path resolveApplicationPropertiesPath(ProjectConfiguration config,
                                                         Path sourcePath,
                                                         Path moduleRoot) {
        String resourceRoot = resolveResourceRoot(config);
        if (sourcePath != null && sourcePath.isAbsolute()) {
            return moduleRoot.resolve(resourceRoot).resolve("application.properties");
        }
        String modulePrefix = extractModulePrefix(sourcePath);
        String prefix = modulePrefix.isEmpty() ? "" : modulePrefix + "/";
        return Paths.get(prefix + resourceRoot, "application.properties");
    }

    private static String resolveResourceRoot(ProjectConfiguration config) {
        List<String> resourceRoots = config.getResourceRoots();
        if (resourceRoots == null || resourceRoots.isEmpty()) {
            return "src/main/resources";
        }
        return resourceRoots.get(0);
    }

    private static String extractModulePrefix(Path sourcePath) {
        if (sourcePath == null) {
            return "";
        }
        String normalized = sourcePath.toString().replace('\\', '/');
        int idx = normalized.indexOf("/src/");
        if (idx > 0) {
            return normalized.substring(0, idx);
        }
        if (normalized.startsWith("src/")) {
            return "";
        }
        return "";
    }

    private static Path extractModuleRoot(Path sourcePath) {
        if (sourcePath == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = sourcePath.toAbsolutePath().getParent();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("build.gradle.kts")) ||
                Files.exists(current.resolve("settings.gradle")) ||
                Files.exists(current.resolve("settings.gradle.kts"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    private static Path resolveConfigRoot(Path moduleRoot) {
        if (moduleRoot == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = moduleRoot;
        while (current != null) {
            if (Files.exists(current.resolve("project.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return moduleRoot;
    }
}
