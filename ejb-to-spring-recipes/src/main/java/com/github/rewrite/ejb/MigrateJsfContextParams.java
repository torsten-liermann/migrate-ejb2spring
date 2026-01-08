/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - web.xml context-param to application.properties
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
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Migrates JSF context-params from web.xml to JoinFaces application.properties.
 * <p>
 * This recipe:
 * <ol>
 *   <li>Scans web.xml for JSF-related context-params (jakarta.faces.*, javax.faces.*, primefaces.*, org.apache.myfaces.*)</li>
 *   <li>Maps them to their JoinFaces application.properties equivalents</li>
 *   <li>Creates or updates application.properties with the mapped properties</li>
 *   <li>Unknown context-params are added as comments with TODO markers</li>
 * </ol>
 * <p>
 * <b>Standard mappings:</b>
 * <table border="1">
 *   <tr><th>web.xml context-param</th><th>application.properties</th></tr>
 *   <tr><td>jakarta.faces.PROJECT_STAGE</td><td>joinfaces.jsf.project-stage</td></tr>
 *   <tr><td>jakarta.faces.FACELETS_SKIP_COMMENTS</td><td>joinfaces.jsf.facelets-skip-comments</td></tr>
 *   <tr><td>jakarta.faces.STATE_SAVING_METHOD</td><td>joinfaces.jsf.state-saving-method</td></tr>
 *   <tr><td>jakarta.faces.INTERPRET_EMPTY_STRING_SUBMITTED_VALUES_AS_NULL</td><td>joinfaces.jsf.interpret-empty-string-submitted-values-as-null</td></tr>
 *   <tr><td>jakarta.faces.DATETIMECONVERTER_DEFAULT_TIMEZONE_IS_SYSTEM_TIMEZONE</td><td>joinfaces.jsf.datetimeconverter-default-timezone-is-system-timezone</td></tr>
 *   <tr><td>jakarta.faces.FACELETS_REFRESH_PERIOD</td><td>joinfaces.jsf.facelets-refresh-period</td></tr>
 *   <tr><td>jakarta.faces.PARTIAL_STATE_SAVING</td><td>joinfaces.jsf.partial-state-saving</td></tr>
 *   <tr><td>jakarta.faces.SERIALIZE_SERVER_STATE</td><td>joinfaces.jsf.serialize-server-state</td></tr>
 *   <tr><td>jakarta.faces.VALIDATE_EMPTY_FIELDS</td><td>joinfaces.jsf.validate-empty-fields</td></tr>
 *   <tr><td>primefaces.THEME</td><td>joinfaces.primefaces.theme</td></tr>
 *   <tr><td>primefaces.FONT_AWESOME</td><td>joinfaces.primefaces.font-awesome</td></tr>
 *   <tr><td>primefaces.SUBMIT</td><td>joinfaces.primefaces.submit</td></tr>
 *   <tr><td>org.apache.myfaces.*</td><td>joinfaces.myfaces.*</td></tr>
 * </table>
 * <p>
 * Note: This recipe should be run BEFORE CleanupJsfWebXml, as CleanupJsfWebXml removes
 * the context-params from web.xml after they have been extracted here.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateJsfContextParams extends ScanningRecipe<MigrateJsfContextParams.Accumulator> {

    private static final String DEFAULT_RESOURCE_ROOT = "src/main/resources";
    private static final String MIGRATION_MARKER = "# ===== JoinFaces JSF Configuration (migrated from web.xml) =====";

    // JSF context-param prefixes
    private static final Set<String> JSF_CONTEXT_PARAM_PREFIXES = new HashSet<>(Arrays.asList(
        "jakarta.faces.",
        "javax.faces.",
        "primefaces.",
        "org.apache.myfaces."
    ));

    // Direct mappings: web.xml param-name -> application.properties key
    private static final Map<String, String> DIRECT_MAPPINGS = new LinkedHashMap<>();
    static {
        // Jakarta Faces (JSF 3.0+)
        DIRECT_MAPPINGS.put("jakarta.faces.PROJECT_STAGE", "joinfaces.jsf.project-stage");
        DIRECT_MAPPINGS.put("jakarta.faces.FACELETS_SKIP_COMMENTS", "joinfaces.jsf.facelets-skip-comments");
        DIRECT_MAPPINGS.put("jakarta.faces.STATE_SAVING_METHOD", "joinfaces.jsf.state-saving-method");
        DIRECT_MAPPINGS.put("jakarta.faces.INTERPRET_EMPTY_STRING_SUBMITTED_VALUES_AS_NULL", "joinfaces.jsf.interpret-empty-string-submitted-values-as-null");
        DIRECT_MAPPINGS.put("jakarta.faces.DATETIMECONVERTER_DEFAULT_TIMEZONE_IS_SYSTEM_TIMEZONE", "joinfaces.jsf.datetimeconverter-default-timezone-is-system-timezone");
        DIRECT_MAPPINGS.put("jakarta.faces.FACELETS_REFRESH_PERIOD", "joinfaces.jsf.facelets-refresh-period");
        DIRECT_MAPPINGS.put("jakarta.faces.PARTIAL_STATE_SAVING", "joinfaces.jsf.partial-state-saving");
        DIRECT_MAPPINGS.put("jakarta.faces.SERIALIZE_SERVER_STATE", "joinfaces.jsf.serialize-server-state");
        DIRECT_MAPPINGS.put("jakarta.faces.VALIDATE_EMPTY_FIELDS", "joinfaces.jsf.validate-empty-fields");
        DIRECT_MAPPINGS.put("jakarta.faces.FACELETS_LIBRARIES", "joinfaces.jsf.facelets-libraries");
        DIRECT_MAPPINGS.put("jakarta.faces.FACELETS_DECORATORS", "joinfaces.jsf.facelets-decorators");
        DIRECT_MAPPINGS.put("jakarta.faces.FACELETS_RESOURCE_RESOLVER", "joinfaces.jsf.facelets-resource-resolver");
        DIRECT_MAPPINGS.put("jakarta.faces.LIFECYCLE_ID", "joinfaces.jsf.lifecycle-id");
        DIRECT_MAPPINGS.put("jakarta.faces.CONFIG_FILES", "joinfaces.jsf.config-files");
        DIRECT_MAPPINGS.put("jakarta.faces.DISABLE_FACELET_JSF_VIEWHANDLER", "joinfaces.jsf.disable-facelet-jsf-viewhandler");
        DIRECT_MAPPINGS.put("jakarta.faces.WEBAPP_CONTRACTS_DIRECTORY", "joinfaces.jsf.webapp-contracts-directory");
        DIRECT_MAPPINGS.put("jakarta.faces.WEBAPP_RESOURCES_DIRECTORY", "joinfaces.jsf.webapp-resources-directory");

        // Javax Faces (legacy JSF 2.x)
        DIRECT_MAPPINGS.put("javax.faces.PROJECT_STAGE", "joinfaces.jsf.project-stage");
        DIRECT_MAPPINGS.put("javax.faces.FACELETS_SKIP_COMMENTS", "joinfaces.jsf.facelets-skip-comments");
        DIRECT_MAPPINGS.put("javax.faces.STATE_SAVING_METHOD", "joinfaces.jsf.state-saving-method");
        DIRECT_MAPPINGS.put("javax.faces.INTERPRET_EMPTY_STRING_SUBMITTED_VALUES_AS_NULL", "joinfaces.jsf.interpret-empty-string-submitted-values-as-null");
        DIRECT_MAPPINGS.put("javax.faces.DATETIMECONVERTER_DEFAULT_TIMEZONE_IS_SYSTEM_TIMEZONE", "joinfaces.jsf.datetimeconverter-default-timezone-is-system-timezone");
        DIRECT_MAPPINGS.put("javax.faces.FACELETS_REFRESH_PERIOD", "joinfaces.jsf.facelets-refresh-period");
        DIRECT_MAPPINGS.put("javax.faces.PARTIAL_STATE_SAVING", "joinfaces.jsf.partial-state-saving");
        DIRECT_MAPPINGS.put("javax.faces.SERIALIZE_SERVER_STATE", "joinfaces.jsf.serialize-server-state");
        DIRECT_MAPPINGS.put("javax.faces.VALIDATE_EMPTY_FIELDS", "joinfaces.jsf.validate-empty-fields");
        DIRECT_MAPPINGS.put("javax.faces.FACELETS_LIBRARIES", "joinfaces.jsf.facelets-libraries");
        DIRECT_MAPPINGS.put("javax.faces.FACELETS_DECORATORS", "joinfaces.jsf.facelets-decorators");
        DIRECT_MAPPINGS.put("javax.faces.FACELETS_RESOURCE_RESOLVER", "joinfaces.jsf.facelets-resource-resolver");
        DIRECT_MAPPINGS.put("javax.faces.LIFECYCLE_ID", "joinfaces.jsf.lifecycle-id");
        DIRECT_MAPPINGS.put("javax.faces.CONFIG_FILES", "joinfaces.jsf.config-files");
        DIRECT_MAPPINGS.put("javax.faces.DISABLE_FACELET_JSF_VIEWHANDLER", "joinfaces.jsf.disable-facelet-jsf-viewhandler");
        DIRECT_MAPPINGS.put("javax.faces.WEBAPP_CONTRACTS_DIRECTORY", "joinfaces.jsf.webapp-contracts-directory");
        DIRECT_MAPPINGS.put("javax.faces.WEBAPP_RESOURCES_DIRECTORY", "joinfaces.jsf.webapp-resources-directory");

        // PrimeFaces
        DIRECT_MAPPINGS.put("primefaces.THEME", "joinfaces.primefaces.theme");
        DIRECT_MAPPINGS.put("primefaces.FONT_AWESOME", "joinfaces.primefaces.font-awesome");
        DIRECT_MAPPINGS.put("primefaces.SUBMIT", "joinfaces.primefaces.submit");
        DIRECT_MAPPINGS.put("primefaces.MOVE_SCRIPTS_TO_BOTTOM", "joinfaces.primefaces.move-scripts-to-bottom");
        DIRECT_MAPPINGS.put("primefaces.TRANSFORM_METADATA", "joinfaces.primefaces.transform-metadata");
        DIRECT_MAPPINGS.put("primefaces.LEGACY_WIDGET_NAMESPACE", "joinfaces.primefaces.legacy-widget-namespace");
        DIRECT_MAPPINGS.put("primefaces.INTERPOLATE_CLIENT_SIDE_VALIDATION_MESSAGES", "joinfaces.primefaces.interpolate-client-side-validation-messages");
        DIRECT_MAPPINGS.put("primefaces.EARLY_POST_PARAM_EVALUATION", "joinfaces.primefaces.early-post-param-evaluation");
        DIRECT_MAPPINGS.put("primefaces.DIR", "joinfaces.primefaces.dir");
        DIRECT_MAPPINGS.put("primefaces.RESET_VALUES", "joinfaces.primefaces.reset-values");
        DIRECT_MAPPINGS.put("primefaces.SECRET", "joinfaces.primefaces.secret");
        DIRECT_MAPPINGS.put("primefaces.CLIENT_SIDE_VALIDATION", "joinfaces.primefaces.client-side-validation");
        DIRECT_MAPPINGS.put("primefaces.UPLOADER", "joinfaces.primefaces.uploader");
        DIRECT_MAPPINGS.put("primefaces.CSP", "joinfaces.primefaces.csp");
        DIRECT_MAPPINGS.put("primefaces.CSP_POLICY", "joinfaces.primefaces.csp-policy");
    }

    @Override
    public String getDisplayName() {
        return "Migrate JSF context-params to JoinFaces application.properties";
    }

    @Override
    public String getDescription() {
        return "Extracts JSF context-params from web.xml and generates JoinFaces application.properties entries. " +
               "Supports jakarta.faces.*, javax.faces.*, primefaces.*, and org.apache.myfaces.* parameters.";
    }

    static class Accumulator {
        Map<Path, ProjectConfiguration> configCache = new HashMap<>();
        Map<Path, ModuleData> modules = new LinkedHashMap<>();
    }

    static class ModuleData {
        final Path moduleRoot;
        final Path applicationPropertiesPath;
        boolean hasApplicationProperties = false;
        boolean applicationPropertiesAlreadyMigrated = false;
        // Extracted JSF context-params: param-name -> param-value (per module)
        Map<String, String> jsfContextParams = new LinkedHashMap<>();
        // Generated properties
        List<String> springProperties = new ArrayList<>();
        // TODO comments for unknown params (commented out)
        List<String> todoComments = new ArrayList<>();

        ModuleData(Path moduleRoot, Path applicationPropertiesPath) {
            this.moduleRoot = moduleRoot;
            this.applicationPropertiesPath = applicationPropertiesPath;
        }
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
                    String path = sourceFile.getSourcePath().toString();
                    Path moduleRoot = extractModuleRoot(sourceFile.getSourcePath());
                    Path configRoot = resolveConfigRoot(moduleRoot);
                    ProjectConfiguration config = acc.configCache.computeIfAbsent(
                        configRoot, ProjectConfigurationLoader::load);
                    ModuleData module = acc.modules.computeIfAbsent(
                        moduleRoot,
                        root -> new ModuleData(moduleRoot,
                            resolveApplicationPropertiesPath(config, sourceFile.getSourcePath(), moduleRoot)));

                    // Check for application.properties (configured resource root)
                    if (sourceFile.getSourcePath().equals(module.applicationPropertiesPath)) {
                        String content = sourceFile.printAll();
                        module.hasApplicationProperties = true;
                        // Only skip if migration marker is present - partial JoinFaces configs are merged
                        if (content.contains(MIGRATION_MARKER)) {
                            module.applicationPropertiesAlreadyMigrated = true;
                        }
                    }

                    // Parse all web.xml files (supports multi-module projects)
                    // Params from all web.xml files are merged (first occurrence wins for duplicates)
                    if (path.endsWith("web.xml") && tree instanceof Xml.Document) {
                        Xml.Document doc = (Xml.Document) tree;
                        if (isWebAppDocument(doc)) {
                            extractJsfContextParams(doc, module);
                        }
                    }
                }
                return tree;
            }
        };
    }

    private boolean isWebAppDocument(Xml.Document doc) {
        Xml.Tag root = doc.getRoot();
        return root != null && "web-app".equals(root.getName());
    }

    private void extractJsfContextParams(Xml.Document doc, ModuleData module) {
        new XmlVisitor<ModuleData>() {
            @Override
            public Xml visitTag(Xml.Tag tag, ModuleData accumulator) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, accumulator);

                if ("context-param".equals(t.getName())) {
                    String paramName = getChildText(t, "param-name");
                    String paramValue = getChildText(t, "param-value");

                    if (paramName != null && paramValue != null && isJsfContextParam(paramName)) {
                        accumulator.jsfContextParams.put(paramName, paramValue);
                    }
                }

                return t;
            }

            @Nullable
            private String getChildText(Xml.Tag parent, String childName) {
                if (parent.getContent() == null) {
                    return null;
                }
                for (Content content : parent.getContent()) {
                    if (content instanceof Xml.Tag) {
                        Xml.Tag child = (Xml.Tag) content;
                        if (childName.equals(child.getName())) {
                            return getTextContent(child);
                        }
                    }
                }
                return null;
            }

            @Nullable
            private String getTextContent(Xml.Tag tag) {
                if (tag.getContent() == null) {
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                for (Content content : tag.getContent()) {
                    if (content instanceof Xml.CharData) {
                        sb.append(((Xml.CharData) content).getText());
                    }
                }
                String result = sb.toString().trim();
                return result.isEmpty() ? null : result;
            }
        }.visit(doc, module);
        // Note: conversion to Spring properties is done in getVisitor() after all web.xml files are scanned
    }

    private boolean isJsfContextParam(String paramName) {
        for (String prefix : JSF_CONTEXT_PARAM_PREFIXES) {
            if (paramName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void convertToSpringProperties(ModuleData module) {
        for (Map.Entry<String, String> entry : module.jsfContextParams.entrySet()) {
            String paramName = entry.getKey();
            String paramValue = entry.getValue();

            // Check for direct mapping
            if (DIRECT_MAPPINGS.containsKey(paramName)) {
                String springKey = DIRECT_MAPPINGS.get(paramName);
                module.springProperties.add(springKey + "=" + paramValue);
            }
            // MyFaces params - convert dynamically
            else if (paramName.startsWith("org.apache.myfaces.")) {
                String suffix = paramName.substring("org.apache.myfaces.".length());
                String springKey = "joinfaces.myfaces." + toKebabCase(suffix);
                module.springProperties.add(springKey + "=" + paramValue);
            }
            // Unknown jakarta.faces.* param - commented out to avoid misconfiguration
            else if (paramName.startsWith("jakarta.faces.")) {
                String suffix = paramName.substring("jakarta.faces.".length());
                String springKey = "joinfaces.jsf." + toKebabCase(suffix);
                module.todoComments.add("# TODO: Unknown JSF param - verify JoinFaces property name");
                module.todoComments.add("# Original: " + paramName + "=" + paramValue);
                module.todoComments.add("# " + springKey + "=" + paramValue);
            }
            // Unknown javax.faces.* param - commented out to avoid misconfiguration
            else if (paramName.startsWith("javax.faces.")) {
                String suffix = paramName.substring("javax.faces.".length());
                String springKey = "joinfaces.jsf." + toKebabCase(suffix);
                module.todoComments.add("# TODO: Unknown JSF param - verify JoinFaces property name");
                module.todoComments.add("# Original: " + paramName + "=" + paramValue);
                module.todoComments.add("# " + springKey + "=" + paramValue);
            }
            // Unknown primefaces.* param - commented out to avoid misconfiguration
            else if (paramName.startsWith("primefaces.")) {
                String suffix = paramName.substring("primefaces.".length());
                String springKey = "joinfaces.primefaces." + toKebabCase(suffix);
                module.todoComments.add("# TODO: Unknown PrimeFaces param - verify JoinFaces property name");
                module.todoComments.add("# Original: " + paramName + "=" + paramValue);
                module.todoComments.add("# " + springKey + "=" + paramValue);
            }
        }
    }

    /**
     * Converts SCREAMING_SNAKE_CASE or SCREAMING_UPPER_CASE to kebab-case.
     * Examples: PROJECT_STAGE -> project-stage, FONT_AWESOME -> font-awesome
     */
    private String toKebabCase(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        return input.toLowerCase().replace('_', '-');
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        boolean hasWork = false;
        for (ModuleData module : acc.modules.values()) {
            if (!module.jsfContextParams.isEmpty() &&
                module.springProperties.isEmpty() &&
                module.todoComments.isEmpty()) {
                convertToSpringProperties(module);
            }
            if (!module.jsfContextParams.isEmpty() &&
                !module.applicationPropertiesAlreadyMigrated &&
                (!module.springProperties.isEmpty() || !module.todoComments.isEmpty())) {
                hasWork = true;
            }
        }

        // Skip if:
        // - no JSF params found in any web.xml
        // - already migrated (marker present)
        // - nothing to add
        if (!hasWork) {
            return TreeVisitor.noop();
        }

        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    Path moduleRoot = extractModuleRoot(sourceFile.getSourcePath());
                    ModuleData module = acc.modules.get(moduleRoot);
                    if (module == null ||
                        !module.hasApplicationProperties ||
                        module.applicationPropertiesAlreadyMigrated ||
                        module.jsfContextParams.isEmpty() ||
                        (module.springProperties.isEmpty() && module.todoComments.isEmpty())) {
                        return tree;
                    }

                    if (sourceFile.getSourcePath().equals(module.applicationPropertiesPath)) {
                        return updateApplicationProperties(sourceFile, module);
                    }
                }
                return tree;
            }
        };
    }

    private SourceFile updateApplicationProperties(SourceFile sourceFile, ModuleData module) {
        // Build the new content to append
        StringBuilder newContent = new StringBuilder();
        newContent.append("\n\n").append(MIGRATION_MARKER).append("\n");

        // Add TODO comments
        for (String comment : module.todoComments) {
            newContent.append(comment).append("\n");
        }

        // Add blank line before properties if we have TODO comments
        if (!module.springProperties.isEmpty() && !module.todoComments.isEmpty()) {
            newContent.append("\n");
        }

        // Add Spring properties
        for (int i = 0; i < module.springProperties.size(); i++) {
            newContent.append(module.springProperties.get(i));
            if (i < module.springProperties.size() - 1) {
                newContent.append("\n");
            }
        }

        // Get existing content and append new content
        String existingContent = sourceFile.printAll();
        // Remove trailing newline from existing content if present
        if (existingContent.endsWith("\n")) {
            existingContent = existingContent.substring(0, existingContent.length() - 1);
        }
        String updatedContent = existingContent + newContent.toString();

        // Use PropertiesParser to create a proper Properties.File AST
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
        List<SourceFile> generated = new ArrayList<>();
        for (ModuleData module : acc.modules.values()) {
            if (!module.jsfContextParams.isEmpty() &&
                module.springProperties.isEmpty() &&
                module.todoComments.isEmpty()) {
                convertToSpringProperties(module);
            }

            if (!module.hasApplicationProperties &&
                !module.applicationPropertiesAlreadyMigrated &&
                !module.jsfContextParams.isEmpty() &&
                (!module.springProperties.isEmpty() || !module.todoComments.isEmpty())) {

                StringBuilder content = new StringBuilder();
                content.append("# Spring Boot Application Properties\n");
                content.append("# Generated from web.xml JSF context-params migration\n\n");
                content.append(MIGRATION_MARKER).append("\n");

                // Add TODO comments
                for (String comment : module.todoComments) {
                    content.append(comment).append("\n");
                }

                // Add blank line before properties
                if (!module.springProperties.isEmpty() && !module.todoComments.isEmpty()) {
                    content.append("\n");
                }

                // Add Spring properties
                for (String prop : module.springProperties) {
                    content.append(prop).append("\n");
                }

                generated.addAll(PropertiesParser.builder().build()
                    .parse(content.toString())
                    .map(parsed -> (SourceFile) parsed.withSourcePath(module.applicationPropertiesPath))
                    .collect(Collectors.toList()));
            }
        }
        return generated;
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
            return DEFAULT_RESOURCE_ROOT;
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

    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = sourcePath.toAbsolutePath().getParent();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    private static Path extractModuleRoot(Path sourcePath) {
        Path moduleRoot = extractProjectRoot(sourcePath);
        if (moduleRoot != null) {
            return moduleRoot;
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
