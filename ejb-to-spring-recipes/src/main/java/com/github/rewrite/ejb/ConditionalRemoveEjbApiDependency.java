/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: WFQ-004 - Conditional EJB-API removal based on Timer type usage
 *           - Extended to include Timer annotations (@Timeout, @Schedule, @Schedules)
 *           - Added FQN detection (not just imports)
 *           - Per-module tracking for multi-module projects
 *           - Round 2: Use TypeUtils.asFullyQualified for FQN detection (toString as fallback)
 *           - Round 2: Use ProjectConfiguration source roots for module path detection
 *           - Round 3: Unified module path resolution based on pom.xml directory structure
 *           - Round 4: Two-pass scanning (XML first, then Java/Kotlin/Scala) for consistent module resolution
 *           - Round 4: Support for .kt and .scala source files alongside .java
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
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.maven.RemoveDependency;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.*;

/**
 * Conditionally removes jakarta.ejb-api and javax.ejb-api dependencies.
 * <p>
 * This recipe scans the codebase for remaining EJB Timer-related types and annotations.
 * If any Timer types or annotations remain, the EJB-API dependency is kept to ensure compilation.
 * <p>
 * WFQ-004: Timer-Migration vollständig machen oder EJB-API behalten
 * <p>
 * Timer types that block removal:
 * <ul>
 *   <li>jakarta.ejb.Timer / javax.ejb.Timer</li>
 *   <li>jakarta.ejb.TimerService / javax.ejb.TimerService</li>
 *   <li>jakarta.ejb.TimerConfig / javax.ejb.TimerConfig</li>
 *   <li>jakarta.ejb.TimerHandle / javax.ejb.TimerHandle</li>
 *   <li>jakarta.ejb.ScheduleExpression / javax.ejb.ScheduleExpression</li>
 *   <li>jakarta.ejb.TimedObject / javax.ejb.TimedObject</li>
 * </ul>
 * <p>
 * Timer annotations that block removal:
 * <ul>
 *   <li>jakarta.ejb.Timeout / javax.ejb.Timeout</li>
 *   <li>jakarta.ejb.Schedule / javax.ejb.Schedule</li>
 *   <li>jakarta.ejb.Schedules / javax.ejb.Schedules</li>
 * </ul>
 * <p>
 * Note: If Timer types have been migrated to migration-annotations stubs
 * (com.github.migration.timer.*), they don't block removal since the stubs
 * are provided by migration-annotations dependency.
 * <p>
 * Multi-module support: Timer usage is tracked per module path, so a Timer in one
 * module doesn't prevent EJB-API removal in unrelated modules.
 */
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false)
public class ConditionalRemoveEjbApiDependency extends ScanningRecipe<ConditionalRemoveEjbApiDependency.Accumulator> {

    // EJB Timer types that block removal of EJB-API dependency
    private static final Set<String> EJB_TIMER_TYPES = Set.of(
        // jakarta namespace
        "jakarta.ejb.Timer",
        "jakarta.ejb.TimerService",
        "jakarta.ejb.TimerConfig",
        "jakarta.ejb.TimerHandle",
        "jakarta.ejb.ScheduleExpression",
        "jakarta.ejb.TimedObject",
        // javax namespace (legacy)
        "javax.ejb.Timer",
        "javax.ejb.TimerService",
        "javax.ejb.TimerConfig",
        "javax.ejb.TimerHandle",
        "javax.ejb.ScheduleExpression",
        "javax.ejb.TimedObject"
    );

    // EJB Timer annotations that also block removal
    private static final Set<String> EJB_TIMER_ANNOTATIONS = Set.of(
        // jakarta namespace
        "jakarta.ejb.Timeout",
        "jakarta.ejb.Schedule",
        "jakarta.ejb.Schedules",
        // javax namespace (legacy)
        "javax.ejb.Timeout",
        "javax.ejb.Schedule",
        "javax.ejb.Schedules"
    );

    // Simple names of blocking types/annotations for FQN detection
    private static final Set<String> BLOCKING_SIMPLE_NAMES = Set.of(
        "Timer", "TimerService", "TimerConfig", "TimerHandle",
        "ScheduleExpression", "TimedObject",
        "Timeout", "Schedule", "Schedules"
    );

    // Migration stub types that do NOT block removal (provided by migration-annotations)
    private static final Set<String> MIGRATION_STUB_TYPES = Set.of(
        "com.github.migration.timer.Timer",
        "com.github.migration.timer.TimerService",
        "com.github.migration.timer.TimerConfig",
        "com.github.migration.timer.TimerHandle",
        "com.github.migration.timer.ScheduleExpression"
    );

    // Configurable source roots (injected via option or default)
    @org.openrewrite.Option(displayName = "Main source roots",
            description = "Comma-separated list of main source roots (e.g., 'src/main/java,src/main/kotlin'). " +
                          "If not specified, Maven defaults are used.",
            required = false)
    String mainSourceRoots;

    @org.openrewrite.Option(displayName = "Test source roots",
            description = "Comma-separated list of test source roots (e.g., 'src/test/java'). " +
                          "If not specified, Maven defaults are used.",
            required = false)
    String testSourceRoots;

    @Override
    public String getDisplayName() {
        return "Conditionally remove EJB API dependency";
    }

    @Override
    public String getDescription() {
        return "Removes jakarta.ejb-api and javax.ejb-api dependencies only if no EJB Timer types " +
               "or annotations remain in the module. If Timer types (@Timeout, @Schedule, @Schedules) " +
               "or Timer classes are still present, the dependency is kept to ensure compilation. " +
               "Timer types migrated to migration-annotations stubs do not block removal. " +
               "In multi-module projects, removal is tracked per module.";
    }

    // Supported source file extensions (Java, Kotlin, Scala)
    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".java", ".kt", ".scala");

    static class Accumulator {
        // Track timer usage per source file path (sourcePath -> type found)
        // This stores raw source paths, module resolution is done in visitor phase
        // when all pom.xml directories are known
        Map<String, Set<String>> sourceFileTimerTypes = new HashMap<>();
        // Track details for logging (will be populated in visitor phase)
        Map<String, Set<String>> moduleFoundTypes = new HashMap<>();
        Map<String, Set<String>> moduleFoundFiles = new HashMap<>();
        // Effective source roots (parsed from options or defaults)
        List<String> effectiveMainRoots;
        List<String> effectiveTestRoots;
        // Track pom.xml locations discovered during scanning (for module path resolution)
        Set<String> knownPomDirectories = new HashSet<>();
    }

    /**
     * Builds the effective source roots from the recipe options.
     */
    private List<String> parseSourceRoots(String optionValue, List<String> defaults) {
        if (optionValue == null || optionValue.isBlank()) {
            return defaults;
        }
        List<String> roots = new ArrayList<>();
        for (String root : optionValue.split(",")) {
            String trimmed = root.trim();
            if (!trimmed.isEmpty()) {
                roots.add(trimmed);
            }
        }
        return roots.isEmpty() ? defaults : roots;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        Accumulator acc = new Accumulator();
        // Initialize effective source roots from options or defaults
        ProjectConfiguration defaults = ProjectConfiguration.mavenDefaults();
        acc.effectiveMainRoots = parseSourceRoots(mainSourceRoots, defaults.getMainSourceRoots());
        acc.effectiveTestRoots = parseSourceRoots(testSourceRoots, defaults.getTestSourceRoots());
        return acc;
    }

    /**
     * Returns a composite scanner that processes sources in two phases:
     * <ol>
     *   <li>Phase 1: Collect all pom.xml directories (XML documents)</li>
     *   <li>Phase 2: Process Java/Kotlin/Scala sources AFTER pom discovery is complete</li>
     * </ol>
     * This ensures consistent module path resolution between Java scanning and POM removal.
     */
    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        // Use a two-pass approach via multiple visitors
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                // Phase 1: Collect pom.xml locations first (always process XML documents)
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    String docPath = doc.getSourcePath().toString();
                    if (docPath.endsWith("pom.xml")) {
                        String pomDir = getPomModulePath(doc.getSourcePath());
                        acc.knownPomDirectories.add(pomDir);
                    }
                    return tree;
                }

                // Phase 2: Process source files (Java, Kotlin, Scala)
                if (tree instanceof SourceFile) {
                    SourceFile sf = (SourceFile) tree;
                    String pathStr = sf.getSourcePath().toString();

                    // Check if this is a supported source file extension
                    if (isSupportedSourceFile(pathStr)) {
                        // Process Java sources directly with the Java scanner
                        // For Kotlin/Scala, we can only scan if they have Java AST representation
                        // (which happens when OpenRewrite parses them)
                        if (pathStr.endsWith(".java")) {
                            return javaScanner(acc).visit(tree, ctx);
                        } else {
                            // For .kt and .scala files, check if OpenRewrite has parsed them as Java AST
                            // This happens when the Kotlin/Scala plugins are configured
                            if (tree instanceof J.CompilationUnit) {
                                return javaScanner(acc).visit(tree, ctx);
                            }
                        }
                    }
                }
                return tree;
            }
        };
    }

    /**
     * Checks if the file path is a supported source file extension.
     *
     * @param pathStr file path string
     * @return true if .java, .kt, or .scala
     */
    private static boolean isSupportedSourceFile(String pathStr) {
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (pathStr.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Creates a Java visitor for scanning Timer type usage.
     * <p>
     * This scanner stores timer usage per source file path (not per module path),
     * deferring module resolution to the visitor phase when all pom.xml directories are known.
     * This ensures consistent module path resolution regardless of file processing order.
     */
    private JavaIsoVisitor<ExecutionContext> javaScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                String sourcePath = cu.getSourcePath().toString();

                // Check imports for Timer types and annotations
                for (J.Import imp : cu.getImports()) {
                    String importPath = imp.getQualid().toString();

                    // Check for EJB Timer types
                    if (EJB_TIMER_TYPES.contains(importPath)) {
                        markSourceAsBlocking(acc, sourcePath, importPath);
                    }

                    // Check for EJB Timer annotations
                    if (EJB_TIMER_ANNOTATIONS.contains(importPath)) {
                        markSourceAsBlocking(acc, sourcePath, importPath);
                    }

                    // Check for wildcard imports from EJB packages
                    if (!imp.isStatic() && importPath.startsWith("jakarta.ejb.") &&
                        importPath.endsWith(".*")) {
                        // Wildcard import - conservatively assume Timer types might be used
                        markSourceAsBlocking(acc, sourcePath, importPath);
                    }
                    if (!imp.isStatic() && importPath.startsWith("javax.ejb.") &&
                        importPath.endsWith(".*")) {
                        markSourceAsBlocking(acc, sourcePath, importPath);
                    }
                }

                // Continue traversal to find FQN usages
                return super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                // Detect FQN usage like jakarta.ejb.Timer or jakarta.ejb.Timeout
                checkFqnUsage(fieldAccess, acc, getCursor());
                return super.visitFieldAccess(fieldAccess, ctx);
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                // Check annotation type (handles both imported and FQN annotations)
                JavaType.FullyQualified annoType = TypeUtils.asFullyQualified(annotation.getType());
                if (annoType != null) {
                    String fqn = annoType.getFullyQualifiedName();
                    if (EJB_TIMER_ANNOTATIONS.contains(fqn)) {
                        J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                        markSourceAsBlocking(acc, cu.getSourcePath().toString(), fqn);
                    }
                }
                return super.visitAnnotation(annotation, ctx);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                // Check if identifier refers to a blocking type via type attribution
                JavaType type = identifier.getType();
                if (type != null) {
                    JavaType.FullyQualified fqType = TypeUtils.asFullyQualified(type);
                    if (fqType != null) {
                        String fqn = fqType.getFullyQualifiedName();
                        if (EJB_TIMER_TYPES.contains(fqn) || EJB_TIMER_ANNOTATIONS.contains(fqn)) {
                            J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                            markSourceAsBlocking(acc, cu.getSourcePath().toString(), fqn);
                        }
                    }
                }
                return super.visitIdentifier(identifier, ctx);
            }

            private void checkFqnUsage(J.FieldAccess fieldAccess, Accumulator acc, Cursor cursor) {
                J.CompilationUnit cu = cursor.firstEnclosingOrThrow(J.CompilationUnit.class);
                String sourcePath = cu.getSourcePath().toString();

                // Strategy 1: Use type attribution (most reliable)
                JavaType.FullyQualified fqType = TypeUtils.asFullyQualified(fieldAccess.getType());
                if (fqType != null) {
                    String fqn = fqType.getFullyQualifiedName();
                    if (EJB_TIMER_TYPES.contains(fqn) || EJB_TIMER_ANNOTATIONS.contains(fqn)) {
                        markSourceAsBlocking(acc, sourcePath, fqn);
                        return;
                    }
                }

                // Strategy 2: Walk the select/name chain to build FQN manually
                String fqnFromChain = buildFqnFromFieldAccess(fieldAccess);
                if (fqnFromChain != null && (EJB_TIMER_TYPES.contains(fqnFromChain) || EJB_TIMER_ANNOTATIONS.contains(fqnFromChain))) {
                    markSourceAsBlocking(acc, sourcePath, fqnFromChain);
                    return;
                }

                // Strategy 3: Fallback to toString() (least reliable, but catches edge cases)
                String fqnFromString = fieldAccess.toString();
                if (EJB_TIMER_TYPES.contains(fqnFromString) || EJB_TIMER_ANNOTATIONS.contains(fqnFromString)) {
                    markSourceAsBlocking(acc, sourcePath, fqnFromString);
                }
            }

            /**
             * Walks the J.FieldAccess chain to build a fully qualified name.
             * For jakarta.ejb.Timer, this walks: FieldAccess(FieldAccess(Identifier("jakarta"), "ejb"), "Timer")
             */
            private String buildFqnFromFieldAccess(J.FieldAccess fieldAccess) {
                StringBuilder sb = new StringBuilder();
                buildFqnRecursive(fieldAccess, sb);
                return sb.toString();
            }

            private void buildFqnRecursive(org.openrewrite.java.tree.Expression expr, StringBuilder sb) {
                if (expr instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) expr;
                    buildFqnRecursive(fa.getTarget(), sb);
                    if (sb.length() > 0) {
                        sb.append(".");
                    }
                    sb.append(fa.getSimpleName());
                } else if (expr instanceof J.Identifier) {
                    J.Identifier id = (J.Identifier) expr;
                    if (sb.length() > 0) {
                        sb.append(".");
                    }
                    sb.append(id.getSimpleName());
                }
            }
        };
    }

    /**
     * Marks a source file as containing blocking timer types.
     * Module resolution is deferred to the visitor phase.
     */
    private static void markSourceAsBlocking(Accumulator acc, String sourcePath, String type) {
        acc.sourceFileTimerTypes.computeIfAbsent(sourcePath, k -> new HashSet<>()).add(type);
    }

    /**
     * Finds the module path for a source file by matching it against known pom.xml directories.
     * <p>
     * This method ensures consistent module path resolution between Java source scanning
     * and pom.xml processing by using the same pom.xml directory as the module identifier.
     * <p>
     * Algorithm:
     * 1. First, try to find a known pom.xml directory that is a prefix of the source path
     * 2. Select the longest matching prefix (for nested modules like apps/app1, apps/app2)
     * 3. Fall back to source root detection if no pom.xml directories match
     *
     * @param sourcePath the source file path
     * @param knownPomDirs set of known pom.xml directories from the project
     * @param mainRoots list of main source roots (e.g., "src/main/java", "src/main/kotlin")
     * @param testRoots list of test source roots (e.g., "src/test/java")
     * @return module path (matches pom.xml directory) or empty string for root module
     */
    private static String findModulePathByPom(Path sourcePath, Set<String> knownPomDirs,
            List<String> mainRoots, List<String> testRoots) {
        String pathStr = sourcePath.toString().replace('\\', '/');

        // Strategy 1: Find the longest matching pom.xml directory
        // This handles nested modules correctly (e.g., apps/app1 vs apps/app2)
        String bestMatch = "";
        for (String pomDir : knownPomDirs) {
            String normalizedPomDir = pomDir.replace('\\', '/');
            // Check if this pom directory is a prefix of the source path
            if (normalizedPomDir.isEmpty()) {
                // Root pom.xml - matches if source is directly under a source root
                if (startsWithSourceRoot(pathStr, mainRoots, testRoots)) {
                    // Root module is a candidate, but prefer more specific matches
                    if (bestMatch.isEmpty()) {
                        bestMatch = "";
                    }
                }
            } else {
                // Module pom.xml - check if source path starts with this module path
                String prefix = normalizedPomDir + "/";
                if (pathStr.startsWith(prefix)) {
                    // Prefer longer (more specific) matches
                    if (normalizedPomDir.length() > bestMatch.length()) {
                        bestMatch = normalizedPomDir;
                    }
                }
            }
        }

        // If we found a match in known pom directories, use it
        if (!bestMatch.isEmpty() || knownPomDirs.contains("")) {
            return bestMatch;
        }

        // Strategy 2: Fall back to source root detection for projects without pom.xml discovery
        return getModulePathBySourceRoot(sourcePath, mainRoots, testRoots);
    }

    /**
     * Checks if the path starts directly with a source root (for root module detection).
     */
    private static boolean startsWithSourceRoot(String pathStr, List<String> mainRoots, List<String> testRoots) {
        List<String> allRoots = new ArrayList<>();
        if (mainRoots != null) allRoots.addAll(mainRoots);
        if (testRoots != null) allRoots.addAll(testRoots);

        for (String root : allRoots) {
            String normalizedRoot = root.replace('\\', '/');
            if (pathStr.startsWith(normalizedRoot + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extracts the module path from a source file path using source root detection.
     * For a path like "module-a/src/main/java/com/example/Foo.java",
     * this returns "module-a" as the module identifier.
     * For single-module projects, returns an empty string.
     * <p>
     * This is a fallback method used when pom.xml directories are not yet discovered.
     *
     * @param sourcePath the source file path
     * @param mainRoots list of main source roots (e.g., "src/main/java", "src/main/kotlin")
     * @param testRoots list of test source roots (e.g., "src/test/java")
     * @return module path prefix or empty string for single-module projects
     */
    private static String getModulePathBySourceRoot(Path sourcePath, List<String> mainRoots, List<String> testRoots) {
        String pathStr = sourcePath.toString().replace('\\', '/');

        // Try all configured source roots
        List<String> allRoots = new ArrayList<>();
        if (mainRoots != null) allRoots.addAll(mainRoots);
        if (testRoots != null) allRoots.addAll(testRoots);

        for (String root : allRoots) {
            String normalizedRoot = "/" + root.replace('\\', '/');
            int srcIdx = pathStr.indexOf(normalizedRoot);
            if (srcIdx > 0) {
                return pathStr.substring(0, srcIdx);
            }
            // Also try without leading slash for paths that start with the root
            if (pathStr.startsWith(root.replace('\\', '/') + "/")) {
                return "";
            }
        }

        // Fallback: try to find the module by looking for source root patterns
        for (String root : allRoots) {
            String[] parts = root.split("/");
            if (parts.length > 0) {
                String firstPart = "/" + parts[0] + "/";
                int idx = pathStr.indexOf(firstPart);
                if (idx > 0) {
                    return pathStr.substring(0, idx);
                }
            }
        }

        // Single-module project or non-standard structure
        return "";
    }

    /**
     * Resolves source file paths to module paths and populates module tracking maps.
     * This is called once at the start of the visitor phase when all pom.xml directories are known.
     */
    private void resolveModulePaths(Accumulator acc) {
        // Only resolve once
        if (!acc.moduleFoundTypes.isEmpty() || acc.sourceFileTimerTypes.isEmpty()) {
            return;
        }

        // Resolve each source file to its module path using the complete knownPomDirectories
        for (Map.Entry<String, Set<String>> entry : acc.sourceFileTimerTypes.entrySet()) {
            String sourcePath = entry.getKey();
            Set<String> types = entry.getValue();

            // Resolve module path using the complete pom directory set
            String modulePath = findModulePathByPom(
                Path.of(sourcePath),
                acc.knownPomDirectories,
                acc.effectiveMainRoots,
                acc.effectiveTestRoots
            );

            // Populate module tracking maps
            acc.moduleFoundTypes.computeIfAbsent(modulePath, k -> new HashSet<>()).addAll(types);
            acc.moduleFoundFiles.computeIfAbsent(modulePath, k -> new HashSet<>()).add(sourcePath);
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // Resolve source file paths to module paths using complete pom directory set
        resolveModulePaths(acc);

        // Per-module removal visitor
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof Xml.Document)) {
                    return tree;
                }

                Xml.Document doc = (Xml.Document) tree;
                String docPath = doc.getSourcePath().toString();
                if (!docPath.endsWith("pom.xml")) {
                    return tree;
                }

                // Determine which module this pom.xml belongs to
                String modulePath = getPomModulePath(doc.getSourcePath());

                // Check if this module has timer types (now using resolved module paths)
                Set<String> types = acc.moduleFoundTypes.get(modulePath);
                if (types != null && !types.isEmpty()) {
                    // Timer types remain in this module - log warning and keep dependency
                    Set<String> files = acc.moduleFoundFiles.getOrDefault(modulePath, Set.of());
                    System.err.println("WFQ-004: EJB Timer types/annotations found in module '" +
                        (modulePath.isEmpty() ? "(root)" : modulePath) + "', keeping EJB-API dependency. " +
                        "Types: " + types + ", Files: " + files);
                    return tree;
                }

                // No Timer types in this module - safe to remove EJB-API dependency
                tree = new RemoveDependency("jakarta.ejb", "jakarta.ejb-api", null)
                    .getVisitor().visit(tree, ctx);

                tree = new RemoveDependency("javax.ejb", "javax.ejb-api", null)
                    .getVisitor().visit(tree, ctx);

                return tree;
            }
        };
    }

    /**
     * Extracts the module path from a pom.xml file path.
     * For a path like "module-a/pom.xml", this returns "module-a".
     * For root "pom.xml", returns empty string.
     */
    private static String getPomModulePath(Path pomPath) {
        String pathStr = pomPath.toString();
        // Remove pom.xml from the end
        if (pathStr.endsWith("pom.xml")) {
            String dir = pathStr.substring(0, pathStr.length() - "pom.xml".length());
            // Remove trailing separator
            if (dir.endsWith("/") || dir.endsWith("\\")) {
                dir = dir.substring(0, dir.length() - 1);
            }
            return dir;
        }
        return "";
    }
}
