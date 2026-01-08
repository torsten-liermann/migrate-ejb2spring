/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - Move JSF resources to META-INF
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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Moves JSF resources from webapp to META-INF/resources for Spring Boot compatibility.
 * <p>
 * Spring Boot serves JSF resources from {@code classpath:/META-INF/resources/} instead of
 * {@code src/main/webapp/}. This recipe moves files according to the JoinFaces file structure:
 * <p>
 * <table border="1">
 *   <tr><th>Source</th><th>Target</th></tr>
 *   <tr><td>src/main/webapp/WEB-INF/faces-config.xml</td><td>src/main/resources/META-INF/faces-config.xml</td></tr>
 *   <tr><td>src/main/webapp/*.xhtml</td><td>src/main/resources/META-INF/resources/*.xhtml</td></tr>
 *   <tr><td>src/main/webapp/pages/*.xhtml</td><td>src/main/resources/META-INF/resources/pages/*.xhtml</td></tr>
 *   <tr><td>src/main/webapp/resources/**</td><td>src/main/resources/META-INF/resources/**</td></tr>
 *   <tr><td>src/main/webapp/WEB-INF/templates/**</td><td>src/main/resources/META-INF/resources/templates/**</td></tr>
 * </table>
 * <p>
 * Note: This recipe does NOT modify file contents. Use {@link RewriteJsfResourceReferences} to
 * update path references in XHTML files after moving.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MoveJsfResourcesToMetaInf extends ScanningRecipe<MoveJsfResourcesToMetaInf.Accumulator> {

    // Source path prefixes (webapp)
    private static final String WEBAPP_PREFIX = "src/main/webapp/";
    private static final String WEBAPP_WEB_INF = "src/main/webapp/WEB-INF/";

    // Target path prefixes (META-INF)
    private static final String META_INF_PREFIX = "src/main/resources/META-INF/";
    private static final String META_INF_RESOURCES = "src/main/resources/META-INF/resources/";

    // Files to exclude from moving (stay in webapp for traditional WAR deployment)
    private static final Set<String> EXCLUDE_FILES = new HashSet<>(Arrays.asList(
        "web.xml"  // web.xml is handled separately by CleanupJsfWebXml
    ));

    @Override
    public String getDisplayName() {
        return "Move JSF resources to META-INF for JoinFaces";
    }

    @Override
    public String getDescription() {
        return "Moves JSF resources (faces-config.xml, *.xhtml, resources/) from src/main/webapp " +
               "to src/main/resources/META-INF/ for Spring Boot JoinFaces compatibility. " +
               "faces-config.xml goes to META-INF/, all other resources go to META-INF/resources/.";
    }

    static class Accumulator {
        // Track files to move: source path -> target path
        Map<String, String> filesToMove = new LinkedHashMap<>();
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
                    String sourcePath = normalizePath(sourceFile.getSourcePath());

                    // Check if file is in webapp and should be moved
                    String targetPath = calculateTargetPath(sourcePath);
                    if (targetPath != null) {
                        acc.filesToMove.put(sourcePath, targetPath);
                    }
                }
                return tree;
            }
        };
    }

    /**
     * Calculates the target path for a file in webapp.
     *
     * @param sourcePath the normalized source path
     * @return the target path, or null if the file should not be moved
     */
    private String calculateTargetPath(String sourcePath) {
        // Must be in webapp directory
        if (!sourcePath.startsWith(WEBAPP_PREFIX)) {
            return null;
        }

        // Get relative path within webapp
        String relativePath = sourcePath.substring(WEBAPP_PREFIX.length());

        // Check exclusions
        String fileName = getFileName(relativePath);
        if (EXCLUDE_FILES.contains(fileName)) {
            return null;
        }

        // Rule 1: faces-config.xml in WEB-INF -> META-INF/faces-config.xml
        if (relativePath.equals("WEB-INF/faces-config.xml")) {
            return META_INF_PREFIX + "faces-config.xml";
        }

        // Rule 2: WEB-INF/templates/** -> META-INF/resources/templates/**
        if (relativePath.startsWith("WEB-INF/templates/")) {
            String templatePath = relativePath.substring("WEB-INF/".length());
            return META_INF_RESOURCES + templatePath;
        }

        // Rule 3: WEB-INF/includes/** -> META-INF/resources/includes/**
        if (relativePath.startsWith("WEB-INF/includes/")) {
            String includePath = relativePath.substring("WEB-INF/".length());
            return META_INF_RESOURCES + includePath;
        }

        // Rule 4: Other WEB-INF files (except web.xml) - move to META-INF/resources/WEB-INF/**
        // This handles custom WEB-INF structures
        if (relativePath.startsWith("WEB-INF/") && !relativePath.equals("WEB-INF/web.xml")) {
            // Move other WEB-INF content to resources, preserving relative structure
            return META_INF_RESOURCES + relativePath;
        }

        // Rule 5: resources/** -> META-INF/resources/**
        // (JSF resource library convention: /resources/library/resource.css)
        // Strip the leading "resources/" to preserve library resolution
        // e.g., webapp/resources/css/style.css -> META-INF/resources/css/style.css
        // This allows <h:outputStylesheet library="css" name="style.css"/> to work
        if (relativePath.startsWith("resources/")) {
            return META_INF_RESOURCES + relativePath.substring("resources/".length());
        }

        // Rule 6: *.xhtml files (views) -> META-INF/resources/*.xhtml
        if (relativePath.endsWith(".xhtml")) {
            return META_INF_RESOURCES + relativePath;
        }

        // Rule 7: Static resources (CSS, JS, images) -> META-INF/resources/**
        if (isStaticResource(relativePath)) {
            return META_INF_RESOURCES + relativePath;
        }

        // Don't move other files (JSP, etc. - not JSF)
        return null;
    }

    /**
     * Checks if the file is a static resource that should be moved.
     */
    private boolean isStaticResource(String relativePath) {
        String lowerPath = relativePath.toLowerCase();
        return lowerPath.endsWith(".css") ||
               lowerPath.endsWith(".js") ||
               lowerPath.endsWith(".png") ||
               lowerPath.endsWith(".jpg") ||
               lowerPath.endsWith(".jpeg") ||
               lowerPath.endsWith(".gif") ||
               lowerPath.endsWith(".ico") ||
               lowerPath.endsWith(".svg") ||
               lowerPath.endsWith(".woff") ||
               lowerPath.endsWith(".woff2") ||
               lowerPath.endsWith(".ttf") ||
               lowerPath.endsWith(".eot") ||
               // Common directories
               relativePath.startsWith("css/") ||
               relativePath.startsWith("js/") ||
               relativePath.startsWith("images/") ||
               relativePath.startsWith("img/") ||
               relativePath.startsWith("fonts/") ||
               relativePath.startsWith("assets/");
    }

    private String getFileName(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    private String normalizePath(Path path) {
        return path.toString().replace('\\', '/');
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (acc.filesToMove.isEmpty()) {
            return TreeVisitor.noop();
        }

        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    String sourcePath = normalizePath(sourceFile.getSourcePath());

                    String targetPath = acc.filesToMove.get(sourcePath);
                    if (targetPath != null) {
                        // Move file by changing its source path
                        return sourceFile.withSourcePath(Paths.get(targetPath));
                    }
                }
                return tree;
            }
        };
    }
}
