package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import org.openrewrite.text.PlainText;

/**
 * Creates a Spring Boot Application class if it doesn't exist.
 * <p>
 * This recipe detects the base package from existing source files and
 * generates a standard Spring Boot main class with @SpringBootApplication.
 * <p>
 * NOTE: This recipe ONLY generates @SpringBootApplication. Additional annotations like
 * @EnableJms, @EnableScheduling, @EnableAsync are added conditionally by the separate
 * AddEnableJmsAndScheduling recipe (which runs after this one and checks for actual usage).
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddSpringBootApplication extends ScanningRecipe<AddSpringBootApplication.Accumulator> {

    @Option(displayName = "Application Class Name",
            description = "Name of the application class to generate (without .java extension)",
            example = "CargoTrackerApplication",
            required = false)
    String applicationClassName;

    @Override
    public String getDisplayName() {
        return "Add Spring Boot Application class";
    }

    @Override
    public String getDescription() {
        return "Creates a Spring Boot Application class with @SpringBootApplication annotation " +
               "if one doesn't already exist. Detects the base package from existing sources.";
    }

    public AddSpringBootApplication() {
        this.applicationClassName = null;
    }

    public AddSpringBootApplication(String applicationClassName) {
        this.applicationClassName = applicationClassName;
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
                // Scan pom.xml files to identify aggregator POMs
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    String sourcePath = doc.getSourcePath().toString().replace('\\', '/');
                    if (sourcePath.endsWith("pom.xml")) {
                        String moduleRoot = extractModuleRootFromPom(sourcePath);

                        // Track if we saw any POMs with module prefixes (like "ejb-demo/pom.xml")
                        if (!moduleRoot.isEmpty()) {
                            acc.sawSubmodulePoms = true;
                        }

                        // Detect if this is a submodule run (POM has parent with relativePath to parent dir)
                        // This happens when running with -pl on a submodule
                        if (moduleRoot.isEmpty() && hasParentWithRelativePath(doc)) {
                            acc.isSubmoduleRun = true;
                        }

                        // HIGH fix: Use proper XML tag parsing instead of string matching
                        boolean hasPomPackaging = hasPomPackaging(doc);
                        boolean hasModules = hasModulesElement(doc);

                        if (hasPomPackaging && hasModules) {
                            acc.aggregatorRoots.add(moduleRoot);
                        } else if (!hasPomPackaging) {
                            // This is a module with actual code (jar/war packaging)
                            acc.moduleRoots.add(moduleRoot);
                        }
                    }
                    return tree;
                }

                // HIGH fix (Round 4): Scan build.gradle and build.gradle.kts files for Gradle-only projects
                // MEDIUM fix (Round 5): Only track Gradle roots here; add to moduleRoots later based on Java sources
                if (tree instanceof PlainText) {
                    PlainText plainText = (PlainText) tree;
                    String sourcePath = plainText.getSourcePath().toString().replace('\\', '/');
                    if (sourcePath.endsWith("build.gradle") || sourcePath.endsWith("build.gradle.kts")) {
                        String moduleRoot = extractModuleRootFromGradle(sourcePath);
                        // Track Gradle root, but don't add to moduleRoots yet
                        // moduleRoots will be populated when we find Java sources under this root
                        acc.gradleRoots.add(moduleRoot);
                    }
                    return tree;
                }

                // Scan Java files
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;

                    // Determine which module this Java file belongs to
                    String sourcePath = cu.getSourcePath().toString().replace('\\', '/');
                    // HIGH fix (Round 3): Use moduleRoots from POM scanning to find module root
                    // This avoids legacyModuleRoot issues with non-standard source paths
                    // MEDIUM fix (Round 5): Also consider Gradle roots (promotes to moduleRoot if Java source found)
                    String moduleRoot = findModuleRootFromPomOrGradleIndex(sourcePath, acc);

                    // Check if this is a Spring Boot Application class
                    for (J.ClassDeclaration classDecl : cu.getClasses()) {
                        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                            if ("SpringBootApplication".equals(ann.getSimpleName())) {
                                acc.modulesWithApplication.add(moduleRoot);
                                return tree;
                            }
                        }
                    }

                    // HIGH fix (Round 5): Skip test sources - they should not trigger Application generation
                    if (isTestSource(sourcePath)) {
                        return tree;
                    }

                    // Collect package names per module (main sources only)
                    if (cu.getPackageDeclaration() != null) {
                        String pkg = cu.getPackageDeclaration().getPackageName();
                        acc.packagesByModule.computeIfAbsent(moduleRoot, k -> new HashSet<>()).add(pkg);

                        // Also collect globally for backward compatibility
                        acc.packages.add(pkg);

                        // Track source path per module
                        if (!acc.sourcePathByModule.containsKey(moduleRoot) && cu.getSourcePath() != null) {
                            acc.sourcePathByModule.put(moduleRoot, cu.getSourcePath());

                            // Detect main source root from the source path
                            // MEDIUM fix: Load config for the specific module, not just root
                            Path configRoot = extractProjectRootForModule(cu.getSourcePath(), moduleRoot);
                            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(configRoot);
                            if (config.isMainSource(sourcePath)) {
                                String mainSourceRoot = extractMainSourceRoot(sourcePath, config);
                                acc.mainSourceRootByModule.put(moduleRoot, mainSourceRoot);
                            } else {
                                // HIGH fix (Round 3): For non-standard paths not recognized by config,
                                // detect the actual source root from the path (e.g., src/java)
                                // MEDIUM fix (Round 4): Pass config to allow checking against configured roots
                                String detectedSourceRoot = detectSourceRootFromPath(sourcePath, moduleRoot, config);
                                if (detectedSourceRoot != null) {
                                    acc.mainSourceRootByModule.put(moduleRoot, detectedSourceRoot);
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
     * HIGH fix: Checks if the POM has packaging=pom using proper XML parsing.
     * Returns true only if the packaging element explicitly contains "pom".
     * MEDIUM fix: Handles namespace-prefixed root tag names (e.g., mvn:project).
     */
    private boolean hasPomPackaging(Xml.Document doc) {
        Xml.Tag root = doc.getRoot();
        if (root == null || !"project".equals(getLocalName(root.getName()))) {
            return false;
        }
        if (root.getContent() == null) {
            return false;
        }
        for (Content content : root.getContent()) {
            if (content instanceof Xml.Tag) {
                Xml.Tag child = (Xml.Tag) content;
                if ("packaging".equals(getLocalName(child.getName()))) {
                    String value = child.getValue().orElse("").trim();
                    return "pom".equals(value);
                }
            }
        }
        // Default packaging is "jar" when not specified
        return false;
    }

    /**
     * Checks if the POM has a parent element with relativePath pointing to a parent directory.
     * This indicates the POM is a submodule when seen without a module prefix.
     */
    private boolean hasParentWithRelativePath(Xml.Document doc) {
        Xml.Tag root = doc.getRoot();
        if (root == null || !"project".equals(getLocalName(root.getName()))) {
            return false;
        }
        if (root.getContent() == null) {
            return false;
        }
        for (Content content : root.getContent()) {
            if (content instanceof Xml.Tag) {
                Xml.Tag child = (Xml.Tag) content;
                if ("parent".equals(getLocalName(child.getName()))) {
                    // Check for relativePath child element
                    if (child.getContent() != null) {
                        for (Content parentContent : child.getContent()) {
                            if (parentContent instanceof Xml.Tag) {
                                Xml.Tag parentChild = (Xml.Tag) parentContent;
                                if ("relativePath".equals(getLocalName(parentChild.getName()))) {
                                    String value = parentChild.getValue().orElse("").trim();
                                    // If relativePath starts with ".." it points to parent directory
                                    return value.startsWith("..");
                                }
                            }
                        }
                    }
                    // Parent exists but no explicit relativePath - Maven defaults to ../pom.xml
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * HIGH fix: Checks if the POM has a modules element with at least one module child.
     * Uses proper XML parsing instead of string matching.
     * MEDIUM fix: Handles namespace-prefixed tag names.
     */
    private boolean hasModulesElement(Xml.Document doc) {
        Xml.Tag root = doc.getRoot();
        if (root == null || !"project".equals(getLocalName(root.getName()))) {
            return false;
        }
        if (root.getContent() == null) {
            return false;
        }
        for (Content content : root.getContent()) {
            if (content instanceof Xml.Tag) {
                Xml.Tag child = (Xml.Tag) content;
                if ("modules".equals(getLocalName(child.getName()))) {
                    // Check for at least one module child element
                    if (child.getContent() != null) {
                        for (Content moduleContent : child.getContent()) {
                            if (moduleContent instanceof Xml.Tag) {
                                Xml.Tag moduleTag = (Xml.Tag) moduleContent;
                                if ("module".equals(getLocalName(moduleTag.getName()))) {
                                    return true;
                                }
                            }
                        }
                    }
                    // modules element exists but is empty
                    return false;
                }
            }
        }
        return false;
    }

    /**
     * MEDIUM fix (Round 5): Finds the module root from POM or Gradle index.
     * For Gradle roots, promotes to moduleRoots only when Java sources are found.
     * <p>
     * This ensures Gradle aggregator projects (root build.gradle without Java sources)
     * don't get treated as code modules.
     *
     * @param sourcePath the normalized source path (forward slashes)
     * @param acc the accumulator containing moduleRoots and gradleRoots
     * @return the module root (empty string for root project)
     */
    private String findModuleRootFromPomOrGradleIndex(String sourcePath, Accumulator acc) {
        // First, check if this Java file belongs to any known module from POM scanning
        for (String moduleRoot : acc.moduleRoots) {
            if (!moduleRoot.isEmpty() && sourcePath.startsWith(moduleRoot + "/")) {
                return moduleRoot;
            }
        }

        // Check if root module is in the index and this file could belong to it
        if (acc.moduleRoots.contains("")) {
            // Root project has sources - check if there's no submodule prefix
            boolean hasSubmodulePrefix = acc.moduleRoots.stream()
                    .filter(m -> !m.isEmpty())
                    .anyMatch(m -> sourcePath.startsWith(m + "/"));
            if (!hasSubmodulePrefix) {
                return "";
            }
        }

        // MEDIUM fix (Round 5): Check Gradle roots and promote to moduleRoots if Java sources found
        for (String gradleRoot : acc.gradleRoots) {
            if (!gradleRoot.isEmpty() && sourcePath.startsWith(gradleRoot + "/")) {
                // Found Java source under this Gradle root - promote to moduleRoots
                if (!acc.moduleRoots.contains(gradleRoot) && !acc.aggregatorRoots.contains(gradleRoot)) {
                    acc.moduleRoots.add(gradleRoot);
                }
                return gradleRoot;
            }
        }

        // Check if root Gradle project has Java sources
        if (acc.gradleRoots.contains("")) {
            boolean hasSubmodulePrefix = acc.gradleRoots.stream()
                    .filter(m -> !m.isEmpty())
                    .anyMatch(m -> sourcePath.startsWith(m + "/"));
            if (!hasSubmodulePrefix) {
                // Root Gradle project has Java sources - promote to moduleRoots
                if (!acc.moduleRoots.contains("") && !acc.aggregatorRoots.contains("")) {
                    acc.moduleRoots.add("");
                }
                return "";
            }
        }

        // Fallback: use legacy /src/ detection if no POM/Gradle index is available
        // (e.g., when no build files are in the source set)
        return extractModuleRoot(sourcePath);
    }

    /**
     * HIGH fix (Round 3): Finds the module root from the POM-indexed moduleRoots set.
     * This is more reliable than /src/ detection for non-standard source paths.
     * <p>
     * Falls back to legacy /src/ detection only if no POM-indexed modules match.
     *
     * @param sourcePath the normalized source path (forward slashes)
     * @param moduleRoots the set of module roots collected from POM scanning
     * @return the module root (empty string for root project)
     * @deprecated Use {@link #findModuleRootFromPomOrGradleIndex(String, Accumulator)} instead
     */
    @Deprecated
    private String findModuleRootFromPomIndex(String sourcePath, Set<String> moduleRoots) {
        // First, check if this Java file belongs to any known module from POM scanning
        for (String moduleRoot : moduleRoots) {
            if (!moduleRoot.isEmpty() && sourcePath.startsWith(moduleRoot + "/")) {
                return moduleRoot;
            }
        }

        // Check if root module is in the index and this file could belong to it
        if (moduleRoots.contains("")) {
            // Root project has sources - check if there's no submodule prefix
            boolean hasSubmodulePrefix = moduleRoots.stream()
                    .filter(m -> !m.isEmpty())
                    .anyMatch(m -> sourcePath.startsWith(m + "/"));
            if (!hasSubmodulePrefix) {
                return "";
            }
        }

        // Fallback: use legacy /src/ detection if no POM index is available
        // (e.g., when no pom.xml files are in the source set)
        return extractModuleRoot(sourcePath);
    }

    /**
     * HIGH fix (Round 3): Detects the source root from a source path by analyzing
     * path structure. Handles non-standard paths like src/java.
     * <p>
     * MEDIUM fix (Round 4): Only applies to .java files and excludes non-source paths
     * like src/generated or src/main/resources.
     *
     * @param sourcePath the normalized source path (forward slashes)
     * @param moduleRoot the module root (empty for root project)
     * @param config     the project configuration (may be null)
     * @return the detected source root (e.g., "src/java") or null if not detected
     */
    private String detectSourceRootFromPath(String sourcePath, String moduleRoot, ProjectConfiguration config) {
        // MEDIUM fix (Round 4): Only apply to Java source files
        if (!sourcePath.endsWith(".java")) {
            return null;
        }

        String pathInModule = moduleRoot.isEmpty() ? sourcePath : sourcePath.substring(moduleRoot.length() + 1);

        // MEDIUM fix (Round 4): Check against configured source roots first
        if (config != null) {
            for (String configuredRoot : config.getMainSourceRoots()) {
                if (pathInModule.startsWith(configuredRoot + "/")) {
                    return configuredRoot;
                }
            }
        }

        // Look for common source root patterns
        // Pattern: src/<something>/package/path/File.java
        if (pathInModule.startsWith("src/")) {
            int firstSlash = pathInModule.indexOf('/');
            int secondSlash = pathInModule.indexOf('/', firstSlash + 1);
            if (secondSlash > 0) {
                String possibleSourceRoot = pathInModule.substring(0, secondSlash);

                // MEDIUM fix (Round 4): Exclude known non-source directories
                String secondSegment = pathInModule.substring(firstSlash + 1, secondSlash);
                if (isExcludedSourceDirectory(secondSegment)) {
                    return null;
                }

                // Validate: the remaining path should look like a package (lowercase segments)
                String remainder = pathInModule.substring(secondSlash + 1);
                if (looksLikePackagePath(remainder)) {
                    return possibleSourceRoot;
                }
            }
        }
        return null;
    }

    /**
     * HIGH fix (Round 5): Checks if the source path is a test source (src/test/).
     * Test sources should not be used for Application class generation.
     *
     * @param sourcePath the normalized source path (forward slashes)
     * @return true if this is a test source path
     */
    private boolean isTestSource(String sourcePath) {
        // Check for common test source patterns
        // Standard Maven: src/test/java, src/test/groovy, etc.
        // Module path: module-name/src/test/java
        return sourcePath.contains("/src/test/") || sourcePath.startsWith("src/test/");
    }

    /**
     * MEDIUM fix (Round 4): Checks if the directory name is a known non-source directory.
     * This excludes paths like src/generated, src/main/resources, src/test/*, etc.
     * <p>
     * HIGH fix (Round 5): Also excludes "test" to prevent detecting src/test/java as a main source root.
     */
    private boolean isExcludedSourceDirectory(String dirName) {
        return "generated".equals(dirName) ||
               "resources".equals(dirName) ||
               "test-resources".equals(dirName) ||
               "webapp".equals(dirName) ||
               "assets".equals(dirName) ||
               "test".equals(dirName);  // HIGH fix (Round 5): Exclude test sources
    }

    /**
     * Checks if a path looks like a Java package path (lowercase directory segments).
     */
    private boolean looksLikePackagePath(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        String[] segments = path.split("/");
        // Need at least one segment (package) plus filename
        if (segments.length < 2) {
            return false;
        }
        // Check first segment (should be lowercase like com, org, de, etc.)
        String firstSegment = segments[0];
        return firstSegment.equals(firstSegment.toLowerCase()) && !firstSegment.isEmpty();
    }

    /**
     * MEDIUM fix: Extracts the project root for a specific module.
     * For aggregator projects, this returns the module directory, not the root.
     *
     * @param sourcePath the source file path
     * @param moduleRoot the module root (empty for root project)
     * @return the path to use for loading ProjectConfiguration
     */
    private Path extractProjectRootForModule(Path sourcePath, String moduleRoot) {
        Path projectRoot = extractProjectRoot(sourcePath);
        if (moduleRoot.isEmpty()) {
            return projectRoot;
        }
        // For modules, try to find module-specific project.yaml first
        Path moduleSpecificRoot = projectRoot.resolve(moduleRoot);
        if (Files.exists(moduleSpecificRoot.resolve("project.yaml"))) {
            return moduleSpecificRoot;
        }
        // Otherwise fall back to root-level project.yaml
        return projectRoot;
    }

    /**
     * Extracts the module root from a pom.xml path.
     * e.g., "module-a/pom.xml" -> "module-a"
     * e.g., "pom.xml" -> ""
     */
    private String extractModuleRootFromPom(String pomPath) {
        if (pomPath.equals("pom.xml")) {
            return "";
        }
        int pomIndex = pomPath.lastIndexOf("/pom.xml");
        if (pomIndex > 0) {
            return pomPath.substring(0, pomIndex);
        }
        return "";
    }

    /**
     * HIGH fix (Round 4): Extracts the module root from a build.gradle or build.gradle.kts path.
     * e.g., "module-a/build.gradle" -> "module-a"
     * e.g., "build.gradle" -> ""
     */
    private String extractModuleRootFromGradle(String gradlePath) {
        if (gradlePath.equals("build.gradle") || gradlePath.equals("build.gradle.kts")) {
            return "";
        }
        int gradleIndex = gradlePath.lastIndexOf("/build.gradle");
        if (gradleIndex > 0) {
            return gradlePath.substring(0, gradleIndex);
        }
        return "";
    }

    /**
     * Extracts the module root from a Java source path.
     * e.g., "module-a/src/main/java/com/example/Foo.java" -> "module-a"
     * e.g., "src/main/java/com/example/Foo.java" -> ""
     */
    private String extractModuleRoot(String sourcePath) {
        int srcIndex = sourcePath.indexOf("/src/");
        if (srcIndex > 0) {
            return sourcePath.substring(0, srcIndex);
        }
        // No module prefix, this is the root project
        return "";
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        List<SourceFile> generatedFiles = new ArrayList<>();

        // Determine which modules need an Application class
        Set<String> modulesToProcess = new HashSet<>();

        // If we have module information from pom.xml scanning, use it
        if (!acc.moduleRoots.isEmpty()) {
            modulesToProcess.addAll(acc.moduleRoots);
        } else if (!acc.packagesByModule.isEmpty()) {
            // Fall back to modules detected from Java source paths
            modulesToProcess.addAll(acc.packagesByModule.keySet());
        } else if (!acc.packages.isEmpty()) {
            // Backward compatibility: single-module project
            modulesToProcess.add("");
        }

        // Remove aggregator roots - they should NOT have Application classes
        modulesToProcess.removeAll(acc.aggregatorRoots);

        // Process each module
        for (String moduleRoot : modulesToProcess) {
            // Skip if this module already has an Application class
            if (acc.modulesWithApplication.contains(moduleRoot)) {
                continue;
            }

            // Skip root-level generation if we're running on a submodule with -pl
            // This prevents generating files at project root when running on a submodule
            // Detection: POM at "pom.xml" (no module prefix) has a parent with relativePath to parent dir
            if (moduleRoot.isEmpty() && acc.isSubmoduleRun) {
                // This is a submodule run - don't generate at root level
                continue;
            }

            // Get packages for this module
            Set<String> modulePackages = acc.packagesByModule.getOrDefault(moduleRoot, acc.packages);
            if (modulePackages.isEmpty()) {
                continue;
            }

            // Find the base package (common prefix) and check if @ComponentScan is needed
            String basePackage = findLongestCommonPackagePrefix(modulePackages);
            Set<String> scanPackages = findPackageRoots(modulePackages);
            // @ComponentScan is needed if packages don't share a common prefix that covers all of them
            boolean needsComponentScan = !allPackagesUnderBase(modulePackages, basePackage);
            // Check if packages are disjoint (different top-level segments like com vs org)
            boolean disjointRoots = hasDisjointPackageRoots(modulePackages);

            // Determine class name
            String className = applicationClassName != null ? applicationClassName : deriveClassName(basePackage);

            // Create the source file (with or without @ComponentScan)
            // Add @NeedsReview when packages have disjoint roots to flag for manual review
            String source;
            if (needsComponentScan) {
                source = generateApplicationSourceWithComponentScan(basePackage, className, scanPackages, disjointRoots);
            } else {
                source = generateApplicationSource(basePackage, className);
            }

            // Determine the file path using detected or default main source root
            String mainSourceRoot = acc.mainSourceRootByModule.getOrDefault(moduleRoot, "src/main/java");
            String pathPrefix = moduleRoot.isEmpty() ? "" : moduleRoot + "/";
            Path filePath = Paths.get(pathPrefix + mainSourceRoot + "/" +
                                      basePackage.replace('.', '/') + "/" +
                                      className + ".java");

            // Parse the source to create a proper J.CompilationUnit
            // Note: We don't need Spring Boot on classpath for parsing this simple class
            JavaParser javaParser = JavaParser.fromJavaVersion().build();

            List<SourceFile> parsed = javaParser.parse(source).toList();
            if (!parsed.isEmpty()) {
                SourceFile sf = parsed.get(0);
                generatedFiles.add(sf.withSourcePath(filePath));
            }
        }

        return generatedFiles;
    }

    /**
     * Computes the minimal set of package roots that cover all given packages.
     * Groups packages by their top-level segment and finds the longest common prefix
     * within each group.
     * <p>
     * Example: [com.example.app, com.example.lib, org.foo.bar] → [com.example, org.foo]
     * Example: [com.company.projectA.api, com.company.projectB.web] → [com.company]
     * Example: [com.bar.util, org.foo.service] → [com.bar, org.foo]
     * <p>
     * For single-package groups, uses the parent package (one segment up) to ensure sibling
     * packages are also scanned, avoiding under-scanning when beans live in unobserved siblings.
     * For multi-package groups, computes the common prefix to minimize scanning scope.
     *
     * @param packages the set of all packages found in the project
     * @return the minimal set of package roots that cover all packages
     */
    private Set<String> findPackageRoots(Set<String> packages) {
        if (packages.isEmpty()) {
            return Collections.emptySet();
        }

        // Group packages by first segment (com, org, de, etc.)
        Map<String, Set<String>> groupsByFirstSegment = new HashMap<>();
        for (String pkg : packages) {
            String[] parts = pkg.split("\\.");
            String firstSegment = parts.length > 0 ? parts[0] : pkg;
            groupsByFirstSegment.computeIfAbsent(firstSegment, k -> new HashSet<>()).add(pkg);
        }

        // For each group, find the longest common prefix
        Set<String> roots = new TreeSet<>();
        for (Set<String> group : groupsByFirstSegment.values()) {
            if (group.size() == 1) {
                // Single package: use parent package (one segment up) to catch sibling packages
                String pkg = group.iterator().next();
                roots.add(getParentPackage(pkg));
            } else {
                // Multiple packages: find common prefix
                String commonPrefix = findLongestCommonPackagePrefix(group);
                if (!commonPrefix.isEmpty() && !commonPrefix.equals("com.example")) {
                    roots.add(commonPrefix);
                } else {
                    // No meaningful common prefix: use parent package for each
                    for (String pkg : group) {
                        roots.add(getParentPackage(pkg));
                    }
                }
            }
        }
        return roots;
    }

    /**
     * Returns the parent package (one segment up) of the given package.
     * For packages with only one or two segments, returns the package as-is.
     * <p>
     * Example: com.example.app.service → com.example.app
     * Example: com.example → com.example
     * Example: com → com
     */
    private String getParentPackage(String pkg) {
        int lastDot = pkg.lastIndexOf('.');
        if (lastDot <= 0) {
            return pkg; // No parent, return as-is
        }
        String parent = pkg.substring(0, lastDot);
        // Ensure we have at least 2 segments (e.g., com.example, not just com)
        if (!parent.contains(".")) {
            return pkg; // Would result in single segment, keep full package
        }
        return parent;
    }

    /**
     * Checks if packages are disjoint (have different top-level segments like com vs org).
     * This indicates the project may need manual review of component scanning.
     */
    private boolean hasDisjointPackageRoots(Set<String> packages) {
        Set<String> firstSegments = new HashSet<>();
        for (String pkg : packages) {
            String[] parts = pkg.split("\\.");
            if (parts.length > 0) {
                firstSegments.add(parts[0]);
            }
        }
        return firstSegments.size() > 1;
    }

    /**
     * Checks if all packages are under the given base package.
     */
    private boolean allPackagesUnderBase(Set<String> packages, String basePackage) {
        for (String pkg : packages) {
            if (!pkg.startsWith(basePackage + ".") && !pkg.equals(basePackage)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds the longest common package prefix that covers all given packages.
     * This ensures @SpringBootApplication will scan all beans.
     * <p>
     * Example: [com.example.app, com.example.lib] -> com.example
     * Example: [org.foo, com.bar] -> "" (no common prefix, will default to com.example)
     */
    private String findLongestCommonPackagePrefix(Set<String> packages) {
        if (packages.isEmpty()) {
            return "com.example";
        }

        List<String[]> splitPackages = packages.stream()
            .map(p -> p.split("\\."))
            .toList();

        int minLength = splitPackages.stream()
            .mapToInt(arr -> arr.length)
            .min()
            .orElse(0);

        StringBuilder commonPrefix = new StringBuilder();
        for (int i = 0; i < minLength; i++) {
            final int index = i;
            String first = splitPackages.get(0)[i];
            boolean allMatch = splitPackages.stream()
                .allMatch(arr -> arr[index].equals(first));
            if (allMatch) {
                if (!commonPrefix.isEmpty()) {
                    commonPrefix.append(".");
                }
                commonPrefix.append(first);
            } else {
                break;
            }
        }

        String result = commonPrefix.toString();
        return result.isEmpty() ? "com.example" : result;
    }

    private String deriveClassName(String basePackage) {
        // Convert package name to class name
        // org.eclipse.cargotracker -> CargoTrackerApplication
        String[] parts = basePackage.split("\\.");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            return capitalize(lastPart) + "Application";
        }
        return "Application";
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private String generateApplicationSource(String basePackage, String className) {
        // NOTE: Only @SpringBootApplication is generated here.
        // @EnableJms, @EnableScheduling, @EnableAsync are added conditionally by
        // AddEnableJmsAndScheduling recipe (only if JMS/Scheduling/Async are actually used).
        return String.format("""
            package %s;

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class %s {

                public static void main(String[] args) {
                    SpringApplication.run(%s.class, args);
                }
            }
            """, basePackage, className, className);
    }

    /**
     * Generates application source with explicit @ComponentScan for disjoint packages.
     * This is needed when packages don't share a common prefix.
     *
     * @param disjointRoots if true, adds @NeedsReview to flag that packages have different
     *                      top-level segments (e.g., com vs org) and may need manual review
     */
    private String generateApplicationSourceWithComponentScan(String basePackage, String className,
                                                               Set<String> scanPackages, boolean disjointRoots) {
        String packagesArray = scanPackages.stream()
            .map(p -> "\"" + p + "\"")
            .reduce((a, b) -> a + ", " + b)
            .orElse("");

        if (disjointRoots) {
            // Add @NeedsReview when packages have disjoint roots
            return String.format("""
                package %s;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.boot.SpringApplication;
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.context.annotation.ComponentScan;

                @SpringBootApplication
                @ComponentScan(basePackages = {%s})
                @NeedsReview("Disjoint package roots detected. Review @ComponentScan basePackages to ensure correct bean scanning scope.")
                public class %s {

                    public static void main(String[] args) {
                        SpringApplication.run(%s.class, args);
                    }
                }
                """, basePackage, packagesArray, className, className);
        }

        return String.format("""
            package %s;

            import org.springframework.boot.SpringApplication;
            import org.springframework.boot.autoconfigure.SpringBootApplication;
            import org.springframework.context.annotation.ComponentScan;

            @SpringBootApplication
            @ComponentScan(basePackages = {%s})
            public class %s {

                public static void main(String[] args) {
                    SpringApplication.run(%s.class, args);
                }
            }
            """, basePackage, packagesArray, className, className);
    }

    static class Accumulator {
        // Legacy field for backward compatibility with single-module projects
        boolean hasSpringBootApplication = false;
        Set<String> packages = new HashSet<>();
        Path sourcePath = null;
        String mainSourceRoot = null;

        // Multi-module support: track modules and their packages separately
        // Module root = "" for root project, "module-name" for submodules
        Set<String> aggregatorRoots = new HashSet<>();  // POMs with packaging=pom AND <modules>
        Set<String> moduleRoots = new HashSet<>();       // POMs with jar/war packaging (actual code modules)
        Set<String> gradleRoots = new HashSet<>();       // HIGH fix (Round 4): Gradle build file locations
        Set<String> modulesWithApplication = new HashSet<>();  // Modules that already have @SpringBootApplication
        Map<String, Set<String>> packagesByModule = new HashMap<>();
        Map<String, Path> sourcePathByModule = new HashMap<>();
        Map<String, String> mainSourceRootByModule = new HashMap<>();
        // Track if we saw POMs with module prefixes (like "ejb-demo/pom.xml")
        // If this is false but moduleRoots contains "", we might be running on a submodule with -pl
        boolean sawSubmodulePoms = false;
        // Track if we detected we're running on a submodule (POM has parent with relativePath to parent dir)
        boolean isSubmoduleRun = false;
    }

    /**
     * Extracts the main source root from the given source path.
     */
    private static String extractMainSourceRoot(String sourcePath, ProjectConfiguration config) {
        if (sourcePath == null) {
            return "src/main/java";
        }
        for (String root : config.getMainSourceRoots()) {
            if (sourcePath.contains("/" + root + "/") || sourcePath.startsWith(root + "/")) {
                return root;
            }
        }
        return "src/main/java";
    }

    /**
     * Extracts the project root directory from the given source path.
     */
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

    /**
     * MEDIUM fix (Round 2): Extracts the local name from a potentially namespace-prefixed tag name.
     * E.g., "mvn:project" -> "project", "project" -> "project"
     *
     * @param name the tag name (may contain namespace prefix)
     * @return the local name without namespace prefix
     */
    private static String getLocalName(String name) {
        if (name == null) {
            return null;
        }
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}
