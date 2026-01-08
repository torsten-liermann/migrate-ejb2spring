package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates the @NeedsReview annotation class in the target project.
 * <p>
 * This recipe should run before any recipe that adds @NeedsReview annotations.
 * It creates the annotation class file if it doesn't exist.
 * <p>
 * The annotation source is loaded from a template resource file to maintain
 * a single source of truth.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddNeedsReviewAnnotation extends ScanningRecipe<AddNeedsReviewAnnotation.Accumulator> {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final String ANNOTATION_PACKAGE = "com.github.rewrite.ejb.annotations";
    private static final String TEMPLATE_RESOURCE = "/com/github/rewrite/ejb/templates/NeedsReview.java.template";
    // Codex P2.1h Round 63: YAML config file for custom source roots
    private static final String SOURCE_ROOTS_CONFIG_FILE = ".migration-source-roots.yaml";

    // Codex P2.1h Round 74/75: Shared constant for generated root patterns
    // Single source of truth for isGeneratedRoot() and DEFAULT_ALL_SOURCE_ROOT_PATTERNS
    // These patterns identify build output directories that should not contain hand-written code
    // Round 75: Segment-boundary matching means "build/generated" matches ALL Gradle subtrees:
    //   - build/generated/source/kapt (Kotlin annotation processing)
    //   - build/generated/sources/annotationProcessor (Java annotation processing)
    //   - build/generated/source/kaptTest (Kotlin test annotation processing)
    //   - build/generated-sources (Gradle variant)
    // The findPatternWithSegmentBoundary() function checks that the pattern ends at a / boundary
    private static final List<String> GENERATED_ROOT_PATTERNS = Arrays.asList(
        "target/generated-sources",       // Maven generated (any subtree via segment-boundary)
        "target/generated-test-sources",  // Maven generated test sources
        "build/generated"                 // Gradle generated (any subtree via segment-boundary)
    );

    // Codex P2.1h Round 60/63/68/69/70: Default patterns for GENERATION (where to create NeedsReview.java)
    // These are overridden by custom roots from YAML if present
    // Round 68: Added src/main/kotlin for multi-language support
    // Round 69: Added integration test patterns for complete module detection
    // Round 70: Generated roots are INTENTIONALLY excluded (see GENERATED_ROOT_PATTERNS)
    private static final List<String> DEFAULT_MAIN_SOURCE_ROOT_PATTERNS = Arrays.asList(
        "src/main/java",
        "src/main/kotlin",
        "src/java",
        "src/it/java",              // Maven integration tests
        "src/integrationTest/java"  // Gradle integration tests
    );

    // Codex P2.1h Round 67/74: Default detection patterns (synced with MigrateDataSourceDefinition)
    // Custom roots from YAML are added to this for comprehensive detection
    // Round 74: Includes GENERATED_ROOT_PATTERNS for consistent generated root handling
    private static final List<String> DEFAULT_ALL_SOURCE_ROOT_PATTERNS;
    static {
        List<String> all = new ArrayList<>(Arrays.asList(
            "src/main/java",
            "src/test/java",
            "src/java",
            "src/main/kotlin",
            "src/test/kotlin",
            "src/it/java",                    // Maven integration tests
            "src/integrationTest/java"        // Gradle integration tests
        ));
        all.addAll(GENERATED_ROOT_PATTERNS);  // Add generated patterns from shared constant
        DEFAULT_ALL_SOURCE_ROOT_PATTERNS = Collections.unmodifiableList(all);
    }

    @Override
    public String getDisplayName() {
        return "Add @NeedsReview annotation class";
    }

    @Override
    public String getDescription() {
        return "Creates the @NeedsReview annotation class in the target project for marking code that needs manual review after migration.";
    }

    static class Accumulator {
        // Codex P2.1h Round 56: Track ALL annotation files (multi-module support)
        // Key: source path, Value: not used (always patch, AST decides)
        Map<String, Boolean> annotationFiles = new LinkedHashMap<>();
        String sourceRoot = null;
        Set<String> detectedSourceRoots = new LinkedHashSet<>();
        // Codex P2.1h Round 58: Track which source roots have NeedsReview.java
        Set<String> sourceRootsWithAnnotation = new LinkedHashSet<>();
        // Codex P2.1h Round 65: Custom source roots from YAML config - PER MODULE
        // Key: module prefix (empty for root), Value: custom roots for that module
        Map<String, Set<String>> customSourceRootsByModule = new LinkedHashMap<>();
        boolean yamlConfigLoaded = false;
        // Codex P2.1h Round 84: Removed validYamlModules field.
        // YAML validity is now checked per-root during generation (scan-state tolerant).

        // Codex P2.1h Round 91: Deferred YAML parsing for order-independence
        // Key: yamlPath, Value: yamlContent - parsed in generate() after all files scanned
        Map<String, String> pendingYamlContents = new LinkedHashMap<>();
        // All Java/Kotlin source paths - detectedSourceRoots computed in generate()
        Set<String> allSourcePaths = new LinkedHashSet<>();
        // Flag to track if deferred processing has been done
        boolean deferredProcessingDone = false;
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
                if (tree instanceof SourceFile) {
                    SourceFile sf = (SourceFile) tree;
                    String path = sf.getSourcePath().toString();

                    // Codex P2.1h Round 59: Normalize path for Windows compatibility
                    String normalizedPath = path.replace('\\', '/');

                    // Codex P2.1h Round 91: Store YAML content for deferred parsing
                    // This makes the recipe order-independent (YAML can be scanned after Java)
                    if (normalizedPath.endsWith(SOURCE_ROOTS_CONFIG_FILE)) {
                        acc.pendingYamlContents.put(normalizedPath, sf.printAll());
                    }

                    // Codex P2.1h Round 56/58/63: Track ALL annotation files (multi-module support)
                    // Round 63: Always add - let AST visitor decide via hasStableKeyMethod()
                    if (normalizedPath.endsWith("NeedsReview.java") && normalizedPath.contains("annotations")) {
                        // Value is not used - AST visitor checks hasStableKeyMethod()
                        acc.annotationFiles.put(normalizedPath, Boolean.FALSE);
                    }

                    // Codex P2.1h Round 91: Store all Java/Kotlin paths for deferred processing
                    // detectedSourceRoots will be computed in generate() after YAML is parsed
                    if (normalizedPath.endsWith(".java") || normalizedPath.endsWith(".kt")) {
                        acc.allSourcePaths.add(normalizedPath);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // Codex P2.1h Round 84: Removed validYamlModules pre-computation.
        // YAML module validity is now checked per-root during generation (scan-state tolerant).
        // This avoids the problem where parser errors in one module invalidate another module's YAML.

        // Codex P2.1h Round 63: Always try to patch ALL annotation files
        // AST visitor's hasStableKeyMethod() decides if patching is needed
        // This eliminates false positives from string-based detection
        Set<String> filesToPatch = acc.annotationFiles.keySet().stream()
            .sorted()  // Deterministic order (lexicographic by path)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        if (!filesToPatch.isEmpty()) {
            return new TreeVisitor<Tree, ExecutionContext>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    if (tree instanceof SourceFile) {
                        SourceFile sf = (SourceFile) tree;
                        // Codex P2.1h Round 59: Normalize path for Windows compatibility
                        String path = sf.getSourcePath().toString().replace('\\', '/');
                        // Try to patch each annotation file - AST decides if needed
                        if (filesToPatch.contains(path)) {
                            return patchAnnotationFile(sf);
                        }
                    }
                    return tree;
                }
            };
        }
        return TreeVisitor.noop();
    }

    /**
     * Codex P2.1h Round 61/63: AST-based patching - uses JavaIsoVisitor to add stableKey method.
     * This preserves AST metadata and formatting, unlike string manipulation.
     * Round 63: Inserts before enum Category if present (proper method placement).
     */
    private SourceFile patchAnnotationFile(SourceFile sf) {
        if (!(sf instanceof J.CompilationUnit)) {
            return sf;
        }
        J.CompilationUnit cu = (J.CompilationUnit) sf;

        // Use JavaIsoVisitor to find and patch the annotation interface
        JavaIsoVisitor<ExecutionContext> visitor = new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Only patch the NeedsReview annotation interface
                if (!"NeedsReview".equals(cd.getSimpleName())) {
                    return cd;
                }

                // Check if stableKey already exists (AST-based detection)
                if (hasStableKeyMethod(cd)) {
                    return cd;
                }

                // Codex P2.1h Round 65: Insert stableKey method BEFORE enum Category
                // OpenRewrite's JavaTemplate doesn't support beforeStatement(Statement).
                // Solution: Reconstruct the statements list with AST manipulation.
                J.Block body = cd.getBody();
                List<Statement> statements = body.getStatements();

                // Build the stableKey method declaration
                JavaTemplate stableKeyTemplate = JavaTemplate.builder(
                    "/**\n" +
                    " * Codex P2.1h Round 53: Stable key for deterministic matching across cycles.\n" +
                    " * Format: sourcePath#className#ds=N (for DataSource annotations)\n" +
                    " * This enables reliable removal of @NeedsReview in subsequent cycles.\n" +
                    " */\n" +
                    "String stableKey() default \"\";"
                ).contextSensitive().build();

                // Find enum Category index
                int enumCategoryIndex = -1;
                for (int i = 0; i < statements.size(); i++) {
                    Statement stmt = statements.get(i);
                    if (stmt instanceof J.ClassDeclaration) {
                        J.ClassDeclaration nestedClass = (J.ClassDeclaration) stmt;
                        if (nestedClass.getKind() == J.ClassDeclaration.Kind.Type.Enum
                            && "Category".equals(nestedClass.getSimpleName())) {
                            enumCategoryIndex = i;
                            break;
                        }
                    }
                }

                // Codex P2.1h Round 66: Apply template to add method at end, then reorder if needed
                // This avoids the double-insertion bug from Round 65's tempCd approach
                J.ClassDeclaration tempCd = stableKeyTemplate.apply(
                    new Cursor(getCursor(), cd),
                    body.getCoordinates().lastStatement()
                );

                if (enumCategoryIndex >= 0) {
                    // Move the newly added method from end to before enum Category
                    List<Statement> tempStatements = new ArrayList<>(tempCd.getBody().getStatements());
                    // The new method is at the end
                    Statement newMethod = tempStatements.remove(tempStatements.size() - 1);

                    // Insert before enum Category (adjust index since we removed from end)
                    tempStatements.add(enumCategoryIndex, newMethod);

                    cd = tempCd.withBody(tempCd.getBody().withStatements(tempStatements));
                } else {
                    // No enum found - method already at end from template application
                    cd = tempCd;
                }

                return cd;
            }

            private boolean hasStableKeyMethod(J.ClassDeclaration cd) {
                if (cd.getBody() == null) {
                    return false;
                }
                for (Statement stmt : cd.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                        if ("stableKey".equals(md.getSimpleName())) {
                            return true;
                        }
                    }
                }
                return false;
            }
        };

        return (SourceFile) visitor.visit(cu, new InMemoryExecutionContext());
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        // Codex P2.1h Round 91: Perform deferred processing (order-independent)
        // Parse YAML and compute detectedSourceRoots AFTER all files have been scanned
        performDeferredProcessing(acc);

        // Codex P2.1h Round 58: Generate NeedsReview.java for each source root that's missing it
        // This supports multi-module builds where each module needs its own annotation

        // Load annotation source from template resource
        String annotationSource = loadTemplateResource();
        if (annotationSource == null) {
            return Collections.emptyList();
        }

        // Codex P2.1h Round 82/92: Find source roots that need NeedsReview.java
        // For modules with valid YAML custom roots, use the YAML root for generation
        // For other modules, use the detected default root
        // Round 92: Deterministic root selection - group by module, then select by priority
        Set<String> missingSourceRoots = new LinkedHashSet<>();

        // Round 92: Group detected roots by module prefix for deterministic selection
        Map<String, List<String>> rootsByModule = new LinkedHashMap<>();
        for (String detectedRoot : acc.detectedSourceRoots) {
            if (isGeneratedRoot(detectedRoot)) {
                continue; // Skip generated roots
            }
            String modulePrefix = extractModulePrefix(detectedRoot, acc);
            rootsByModule.computeIfAbsent(modulePrefix, k -> new ArrayList<>()).add(detectedRoot);
        }

        // Round 92: For each module, select the best root by priority
        for (Map.Entry<String, List<String>> entry : rootsByModule.entrySet()) {
            String modulePrefix = entry.getKey();
            List<String> moduleRoots = entry.getValue();

            // Select the best root for this module using deterministic priority
            String targetRoot = selectBestRootForModule(modulePrefix, moduleRoots, acc);

            if (targetRoot != null && !acc.sourceRootsWithAnnotation.contains(targetRoot)) {
                missingSourceRoots.add(targetRoot);
            }
        }

        // If no source roots detected and no annotations exist, use default
        if (missingSourceRoots.isEmpty() && acc.annotationFiles.isEmpty()) {
            missingSourceRoots.add("src/main/java");
        }

        if (missingSourceRoots.isEmpty()) {
            return Collections.emptyList();
        }

        // Generate annotation file for each missing source root (sorted for deterministic output)
        List<SourceFile> generated = new ArrayList<>();
        JavaParser parser = JavaParser.fromJavaVersion().build();

        for (String sourceRoot : missingSourceRoots.stream().sorted().toList()) {
            Path annotationPath = Paths.get(sourceRoot,
                ANNOTATION_PACKAGE.replace('.', '/'),
                "NeedsReview.java");

            parser.parse(annotationSource)
                .map(j -> (SourceFile) j.withSourcePath(annotationPath))
                .findFirst()
                .ifPresent(generated::add);

            // Codex P2.1h Round 89: Reset parser to allow parsing same FQN for different modules
            parser.reset();
        }

        return generated;
    }

    private String loadTemplateResource() {
        try (InputStream is = getClass().getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (is == null) {
                return null;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Codex P2.1h Round 92: Deterministic root selection priority.
     * Returns priority score for a source root pattern (lower = higher priority).
     * Main source roots have highest priority, test roots have lowest.
     */
    private static final List<String> ROOT_PRIORITY_ORDER = Arrays.asList(
        "src/main/java",           // 0 - highest priority (main Java code)
        "src/main/kotlin",         // 1 - main Kotlin code
        "src/java",                // 2 - legacy main layout
        "src/it/java",             // 3 - Maven integration tests
        "src/integrationTest/java" // 4 - Gradle integration tests
        // 5+ - YAML custom roots (alphabetically sorted)
        // 100+ - test roots (should not be used for NeedsReview.java)
    );

    /**
     * Codex P2.1h Round 92: Select the best source root for a module.
     * Uses deterministic priority to ensure consistent root selection regardless
     * of scan order. Priority: src/main/java > src/main/kotlin > legacy > integration > custom > test.
     * <p>
     * For modules with YAML custom roots, prefers YAML roots that have detected files.
     * For modules without YAML, uses the highest-priority detected standard root.
     *
     * @param modulePrefix the module prefix (empty for root module)
     * @param moduleRoots all detected roots for this module
     * @param acc the accumulator with YAML config
     * @return the best root for generation, or null if none found
     */
    private String selectBestRootForModule(String modulePrefix, List<String> moduleRoots, Accumulator acc) {
        if (moduleRoots.isEmpty()) {
            return null;
        }

        // Round 92/93: Check for YAML custom roots first
        // Round 93: Respect YAML order (LinkedHashSet) instead of alphabetical sort
        // The YAML order is user-defined and should be honored
        if (acc.yamlConfigLoaded && !acc.customSourceRootsByModule.isEmpty()) {
            Set<String> yamlRoots = acc.customSourceRootsByModule.get(modulePrefix);
            if (yamlRoots != null && !yamlRoots.isEmpty()) {
                // Find YAML root that has detected files, respecting YAML order
                for (String yamlRoot : yamlRoots) {  // LinkedHashSet preserves insertion order
                    if (isGeneratedRoot(yamlRoot)) {
                        continue;
                    }
                    String expectedRoot = modulePrefix.isEmpty() ? yamlRoot : modulePrefix + "/" + yamlRoot;
                    if (acc.detectedSourceRoots.contains(expectedRoot)) {
                        return expectedRoot;
                    }
                }
                // No YAML root has detected files - fall through to standard priority
            }
        }

        // Round 92: Sort module roots by priority for deterministic selection
        List<String> sortedRoots = new ArrayList<>(moduleRoots);
        sortedRoots.sort((a, b) -> {
            int priorityA = getRootPriority(a, modulePrefix);
            int priorityB = getRootPriority(b, modulePrefix);
            if (priorityA != priorityB) {
                return Integer.compare(priorityA, priorityB);
            }
            // Same priority - sort alphabetically for determinism
            return a.compareTo(b);
        });

        // Return the highest-priority non-test root, or the first if all are tests
        for (String root : sortedRoots) {
            // Skip test roots if we have main roots
            if (!isTestRoot(root, modulePrefix) || sortedRoots.size() == 1) {
                return root;
            }
        }
        return sortedRoots.get(0); // Fallback to first (should not happen)
    }

    /**
     * Codex P2.1h Round 92: Get priority score for a source root.
     * Lower score = higher priority.
     */
    private static int getRootPriority(String fullRoot, String modulePrefix) {
        // Extract the pattern part (without module prefix)
        String pattern = fullRoot;
        if (!modulePrefix.isEmpty() && fullRoot.startsWith(modulePrefix + "/")) {
            pattern = fullRoot.substring(modulePrefix.length() + 1);
        }

        // Check against priority order
        int idx = ROOT_PRIORITY_ORDER.indexOf(pattern);
        if (idx >= 0) {
            return idx;
        }

        // Test roots have low priority (should not be used for main annotation)
        if (pattern.contains("/test/")) {
            return 100;
        }

        // Custom/YAML roots have medium priority
        return 50;
    }

    /**
     * Codex P2.1h Round 92: Check if a root is a test root.
     */
    private static boolean isTestRoot(String fullRoot, String modulePrefix) {
        String pattern = fullRoot;
        if (!modulePrefix.isEmpty() && fullRoot.startsWith(modulePrefix + "/")) {
            pattern = fullRoot.substring(modulePrefix.length() + 1);
        }
        return pattern.contains("/test/") || pattern.equals("src/test/java") || pattern.equals("src/test/kotlin");
    }

    /**
     * Codex P2.1h Round 91/92: Perform deferred processing for order-independence.
     * This is called at the start of generate() to ensure all YAML configs are
     * parsed and detectedSourceRoots are computed AFTER all files have been scanned.
     * This makes the recipe order-independent.
     * <p>
     * Note: getVisitor() does not call this method because it only patches existing
     * annotation files based on annotationFiles keys set during scanning.
     */
    private void performDeferredProcessing(Accumulator acc) {
        if (acc.deferredProcessingDone) {
            return; // Already processed
        }
        acc.deferredProcessingDone = true;

        // Step 1: Parse all pending YAML contents (order-independent)
        for (Map.Entry<String, String> entry : acc.pendingYamlContents.entrySet()) {
            loadCustomSourceRoots(entry.getKey(), entry.getValue(), acc);
        }

        // Step 2: Compute detectedSourceRoots from allSourcePaths (now YAML is loaded)
        for (String path : acc.allSourcePaths) {
            for (String pattern : getEffectiveAllSourceRootPatterns(path, acc)) {
                int idx = findPatternWithSegmentBoundary(path, pattern);
                if (idx >= 0) {
                    String detectedRoot = path.substring(0, idx) + pattern;
                    acc.detectedSourceRoots.add(detectedRoot);
                    break; // Use first matching pattern for this file
                }
            }
        }

        // Step 3: Compute sourceRootsWithAnnotation from annotationFiles
        for (String annotationPath : acc.annotationFiles.keySet()) {
            String annotationSourceRoot = extractSourceRoot(annotationPath, acc);
            if (annotationSourceRoot != null) {
                acc.sourceRootsWithAnnotation.add(annotationSourceRoot);
            }
        }
    }

    /**
     * Codex P2.1h Round 65: Load custom source roots from YAML configuration.
     * Expected format:
     * <pre>
     * # .migration-source-roots.yaml
     * sourceRoots:
     *   - src/main/java
     *   - src/test/java
     *   - custom/generated/java
     * </pre>
     * Uses simple line parsing to avoid external YAML library dependency.
     * Round 65: Stores roots per module prefix (extracted from YAML file path)
     * to avoid cross-module interference in multi-module projects.
     */
    private static void loadCustomSourceRoots(String yamlPath, String yamlContent, Accumulator acc) {
        if (yamlContent == null || yamlContent.isEmpty()) {
            return;
        }

        // Codex P2.1h Round 73: Removed arbitrary depth-based validation (Finding #1, #2)
        // Previous depth check (depth <= 3) was arbitrary and could reject legitimate nested
        // modules like "apps/backend/services/order" while ignoring Kotlin-only modules.
        //
        // New behavior: Accept all YAML configs. Orphaned configs (with module prefixes that
        // don't match any Java/Kotlin file's module prefix) simply won't apply - their custom
        // roots won't be used because no file path will produce a matching module prefix.
        // This is deterministic and doesn't rely on filesystem checks or arbitrary limits.
        String modulePrefix = extractModulePrefix(yamlPath);

        acc.yamlConfigLoaded = true;

        // Get or create the set of custom roots for this module
        Set<String> moduleRoots = acc.customSourceRootsByModule.computeIfAbsent(
            modulePrefix, k -> new LinkedHashSet<>());

        boolean inSourceRoots = false;
        for (String line : yamlContent.split("\n")) {
            String trimmed = line.trim();
            // Skip comments and empty lines
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            // Start of sourceRoots list
            if (trimmed.equals("sourceRoots:")) {
                inSourceRoots = true;
                continue;
            }
            // Collect list items under sourceRoots:
            if (inSourceRoots) {
                // List item: starts with "- "
                if (trimmed.startsWith("- ")) {
                    String root = trimmed.substring(2).trim();
                    // Remove quotes if present (both single and double)
                    if ((root.startsWith("\"") && root.endsWith("\""))
                        || (root.startsWith("'") && root.endsWith("'"))) {
                        root = root.substring(1, root.length() - 1);
                    }
                    if (!root.isEmpty()) {
                        // Normalize: convert backslashes to forward slashes, remove trailing slashes
                        root = root.replace('\\', '/');
                        while (root.endsWith("/")) {
                            root = root.substring(0, root.length() - 1);
                        }
                        if (!root.isEmpty()) {
                            // Codex P2.1h Round 64: Check for problematic module-prefix pattern
                            String problematicSuffix = detectModulePrefixPattern(root);
                            if (problematicSuffix != null) {
                                // Skip problematic roots - emit warning for consistency
                                System.err.println("[WARNING] AddNeedsReviewAnnotation: Custom source root '" + root +
                                    "' contains module prefix before standard pattern '" + problematicSuffix +
                                    "'. This root will be IGNORED. Use just '" + problematicSuffix + "' instead.");
                            } else if (isGeneratedRoot(root)) {
                                // Codex P2.1h Round 71: Skip generated roots
                                // NeedsReview.java should not be placed in generated source directories
                                System.err.println("[WARNING] AddNeedsReviewAnnotation: Custom source root '" + root +
                                    "' is a generated source directory (output directory, cleaned on build). " +
                                    "This root will be IGNORED. Use src/main/java or similar instead.");
                            } else {
                                moduleRoots.add(root);
                            }
                        }
                    }
                } else if (!trimmed.startsWith(" ") && !trimmed.startsWith("-")) {
                    // New top-level key, stop reading sourceRoots
                    inSourceRoots = false;
                }
            }
        }
    }

    /**
     * Codex P2.1h Round 71/72/75: Check if a root is a generated source directory.
     * Generated source directories are output directories that should not contain
     * hand-written code like NeedsReview.java annotations.
     * Round 72: Uses segment-boundary matching to avoid false positives with
     * legitimate roots like "src/generated-sources/java".
     * Round 75: Segment-boundary matching means "build/generated" matches ALL subtrees
     * like "build/generated/source/kapt" or "build/generated/sources/annotationProcessor".
     * @param root the source root to check
     * @return true if this is a generated source directory
     */
    private static boolean isGeneratedRoot(String root) {
        // Codex P2.1h Round 72/73/74/75: Use segment-boundary matching for generated directories
        // Segment-boundary means "build/generated" matches "build/generated/source/kapt" etc.
        // because the pattern is followed by "/" which is a valid segment boundary.
        // This avoids false positives for "src/generated-sources/java" (hand-written)
        // Round 74: Uses shared GENERATED_ROOT_PATTERNS constant for consistency
        for (String pattern : GENERATED_ROOT_PATTERNS) {
            // Check with segment boundary - matches pattern at start or middle, followed by / or end
            int idx = findPatternWithSegmentBoundary(root, pattern);
            if (idx >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Codex P2.1h Round 67/69/70/78: Extract module prefix from a file path.
     * <p>
     * Module prefix semantics (Round 69/70 documentation):
     * - For files with source root patterns: returns the path BEFORE the pattern
     * - For YAML config files without patterns: returns the parent directory path
     * - Empty string means repo root (no module prefix)
     * <p>
     * Round 78: Also checks YAML custom roots from accumulator for files in custom roots.
     * This allows correct module prefix extraction for files in YAML-defined source roots.
     * <p>
     * Round 70 guidance: YAML config files MUST be placed in the module root directory
     * (next to pom.xml/build.gradle), NOT in subdirectories like "config/".
     * If YAML is placed in a non-module directory (e.g., "config/"), it will be
     * assigned a module prefix that doesn't match any Java file's module, and
     * the custom roots will not be applied.
     * <p>
     * Examples:
     * - "module-a/src/main/java/..." -> "module-a" (correct - module identified)
     * - "src/main/java/..." -> "" (correct - repo root module)
     * - "module-a/.migration-source-roots.yaml" -> "module-a" (correct - module root)
     * - "services/order/.migration-source-roots.yaml" -> "services/order" (correct - nested module)
     * - "module-a/custom/src/Foo.java" -> "module-a" (Round 78: if custom/src is in YAML)
     * - "config/.migration-source-roots.yaml" -> "config" (WRONG: config is not a module!)
     */
    private static String extractModulePrefix(String path, Accumulator acc) {
        // Codex P2.1h Round 81: Check for standard source root patterns, excluding generated roots
        // Generated roots should not be used for module prefix extraction
        for (String pattern : DEFAULT_ALL_SOURCE_ROOT_PATTERNS) {
            if (GENERATED_ROOT_PATTERNS.contains(pattern)) {
                continue; // Skip generated patterns
            }
            int idx = findPatternWithSegmentBoundary(path, pattern);
            if (idx > 0) {
                // Return the prefix before the pattern (without trailing /)
                String prefix = path.substring(0, idx);
                if (prefix.endsWith("/")) {
                    prefix = prefix.substring(0, prefix.length() - 1);
                }
                return prefix;
            } else if (idx == 0) {
                return ""; // No module prefix
            }
        }
        // Codex P2.1h Round 78: Also check YAML custom roots from accumulator
        // This allows correct module prefix extraction for files in custom roots
        if (acc != null && acc.yamlConfigLoaded && !acc.customSourceRootsByModule.isEmpty()) {
            for (Map.Entry<String, Set<String>> entry : acc.customSourceRootsByModule.entrySet()) {
                for (String customRoot : entry.getValue()) {
                    int idx = findPatternWithSegmentBoundary(path, customRoot);
                    if (idx > 0) {
                        String prefix = path.substring(0, idx);
                        if (prefix.endsWith("/")) {
                            prefix = prefix.substring(0, prefix.length() - 1);
                        }
                        return prefix;
                    } else if (idx == 0) {
                        return ""; // No module prefix (root level)
                    }
                }
            }
        }
        // Codex P2.1h Round 67/80: For config/YAML files without source root pattern,
        // use the parent directory as the module prefix to support nested modules
        // E.g., "services/order/.migration-source-roots.yaml" -> "services/order"
        // Round 80: YAML in non-module directories will have no effect - their custom roots
        // won't match any files during detection (natural validation).
        int lastSlash = path.lastIndexOf('/');
        if (lastSlash > 0) {
            return path.substring(0, lastSlash);
        }
        return ""; // Root level or single file
    }

    /**
     * Codex P2.1h Round 78: Backward-compatible overload without accumulator.
     * Used for YAML file path extraction during loading (before custom roots are available).
     */
    private static String extractModulePrefix(String path) {
        return extractModulePrefix(path, null);
    }

    /**
     * Codex P2.1h Round 65: Detect if a custom root has a module prefix pattern.
     * A module prefix pattern is when a custom root contains a standard source root
     * as a suffix (e.g., "module/src/main/java" contains "src/main/java").
     * This is consistent with MigrateDataSourceDefinition.detectModulePrefixPattern().
     * Round 65: Now checks against ALL source root patterns (main + test + kotlin),
     * not just DEFAULT_MAIN_SOURCE_ROOT_PATTERNS.
     * @return the standard root suffix if found, null otherwise
     */
    private static String detectModulePrefixPattern(String customRoot) {
        // Codex P2.1h Round 65: Check against ALL source root patterns for comprehensive validation
        for (String defaultRoot : DEFAULT_ALL_SOURCE_ROOT_PATTERNS) {
            // Check if custom root ends with a default root pattern (after a /)
            if (customRoot.endsWith("/" + defaultRoot)) {
                return defaultRoot;
            }
            // Also check if custom root equals a default root with a prefix
            // e.g., "module/src/test/java" starts with any prefix before "src/test/java"
            int idx = customRoot.indexOf("/" + defaultRoot);
            if (idx > 0) {
                // Has a module prefix before the standard root
                return defaultRoot;
            }
        }
        return null;
    }

    /**
     * Codex P2.1h Round 65/72/78: Get effective main source root patterns for a specific path.
     * Combines defaults with custom roots from YAML for the path's module.
     * Sorted by specificity (longest first) for correct matching order.
     * Round 65: Now module-aware to avoid cross-module interference.
     * Round 72: Filters out generated roots from custom roots to enforce consistency.
     * Round 78: Uses extractModulePrefix with acc to correctly identify modules with YAML roots.
     */
    private static List<String> getEffectiveMainSourceRootPatterns(String filePath, Accumulator acc) {
        Set<String> combined = new LinkedHashSet<>(DEFAULT_MAIN_SOURCE_ROOT_PATTERNS);
        if (acc.yamlConfigLoaded && !acc.customSourceRootsByModule.isEmpty()) {
            // Get custom roots for this file's module only
            // Round 78: Use overload with acc to recognize YAML custom roots
            String modulePrefix = extractModulePrefix(filePath, acc);
            Set<String> moduleRoots = acc.customSourceRootsByModule.get(modulePrefix);
            if (moduleRoots != null && !moduleRoots.isEmpty()) {
                // Codex P2.1h Round 72: Filter out generated roots from custom roots
                // This enforces consistency with DEFAULT_MAIN_SOURCE_ROOT_PATTERNS exclusion
                for (String root : moduleRoots) {
                    if (!isGeneratedRoot(root)) {
                        combined.add(root);
                    }
                }
            }
        }
        // Sort by length descending (longest/most specific first)
        List<String> result = new ArrayList<>(combined);
        result.sort((a, b) -> {
            int lenCmp = Integer.compare(b.length(), a.length());
            return lenCmp != 0 ? lenCmp : a.compareTo(b);
        });
        return result;
    }

    /**
     * Codex P2.1h Round 65/76/77/78/79/80/82/83/85: Get effective source root patterns for DETECTION for a specific path.
     * Round 85: Detection uses default patterns PLUS module-specific YAML roots.
     * For custom-only projects (no default roots), checks if file is under any YAML root.
     * This supports custom-only module layouts while preventing cross-module contamination.
     * Round 76: Filters out generated root patterns to prevent detection of generated directories.
     * Sorted by specificity (longest first) for correct matching order.
     */
    private static List<String> getEffectiveAllSourceRootPatterns(String filePath, Accumulator acc) {
        // Round 83: Use default non-generated patterns for detection
        Set<String> combined = new LinkedHashSet<>();
        for (String pattern : DEFAULT_ALL_SOURCE_ROOT_PATTERNS) {
            if (!GENERATED_ROOT_PATTERNS.contains(pattern)) {
                combined.add(pattern);
            }
        }

        // Round 88: Add YAML roots for detection
        // First try to determine module from default patterns
        // Then verify file is actually under those YAML roots before using them
        if (acc.yamlConfigLoaded && !acc.customSourceRootsByModule.isEmpty()) {
            String modulePrefix = extractModulePrefixFromDefaults(filePath);
            Set<String> yamlRoots = acc.customSourceRootsByModule.get(modulePrefix);

            // Round 88: Even if yamlRoots exist for modulePrefix, verify file is under them
            // This prevents root-module YAML from incorrectly applying to nested modules
            boolean fileUnderYamlRoot = false;
            if (yamlRoots != null && !yamlRoots.isEmpty()) {
                for (String yamlRoot : yamlRoots) {
                    String fullYamlRoot = modulePrefix.isEmpty() ? yamlRoot : modulePrefix + "/" + yamlRoot;
                    if (findPatternWithSegmentBoundary(filePath, fullYamlRoot) == 0) {
                        fileUnderYamlRoot = true;
                        break;
                    }
                }
            }

            // If file NOT under found yamlRoots OR no yamlRoots for modulePrefix, check all YAML roots
            if (!fileUnderYamlRoot) {
                String matchedRoot = null;
                for (Map.Entry<String, Set<String>> entry : acc.customSourceRootsByModule.entrySet()) {
                    String prefix = entry.getKey();
                    for (String yamlRoot : entry.getValue()) {
                        // Build full YAML root path: modulePrefix + yamlRoot
                        String fullYamlRoot = prefix.isEmpty() ? yamlRoot : prefix + "/" + yamlRoot;
                        // Round 87: Match only at expected position to prevent cross-module contamination
                        // For root module (prefix empty): pattern must start at index 0
                        // For other modules: pattern must be at prefix position
                        int expectedIdx = prefix.isEmpty() ? 0 : 0;  // Full path must start at 0
                        int idx = findPatternWithSegmentBoundary(filePath, fullYamlRoot);
                        if (idx == expectedIdx) {
                            matchedRoot = yamlRoot;
                            break;
                        }
                    }
                    if (matchedRoot != null) {
                        break;
                    }
                }
                // Round 86: Only add the matched root, not all module roots
                if (matchedRoot != null && !isGeneratedRoot(matchedRoot)) {
                    combined.add(matchedRoot);
                }
            } else {
                // Module found from defaults - add all its YAML roots
                for (String root : yamlRoots) {
                    if (!isGeneratedRoot(root)) {
                        combined.add(root);
                    }
                }
            }
        }

        // Sort by length descending (longest/most specific first)
        List<String> result = new ArrayList<>(combined);
        result.sort((a, b) -> {
            int lenCmp = Integer.compare(b.length(), a.length());
            return lenCmp != 0 ? lenCmp : a.compareTo(b);
        });
        return result;
    }

    /**
     * Codex P2.1h Round 83/84: Extract module prefix using ONLY default patterns.
     * This avoids chicken-and-egg problem where we need module prefix to get YAML roots,
     * but YAML roots are needed for module prefix extraction.
     * Round 84: No parent fallback - returns empty string for files without patterns.
     * This prevents YAML in arbitrary directories from creating inconsistent modules.
     */
    private static String extractModulePrefixFromDefaults(String path) {
        for (String pattern : DEFAULT_ALL_SOURCE_ROOT_PATTERNS) {
            if (GENERATED_ROOT_PATTERNS.contains(pattern)) {
                continue; // Skip generated patterns
            }
            int idx = findPatternWithSegmentBoundary(path, pattern);
            if (idx > 0) {
                String prefix = path.substring(0, idx);
                if (prefix.endsWith("/")) {
                    prefix = prefix.substring(0, prefix.length() - 1);
                }
                return prefix;
            } else if (idx == 0) {
                return ""; // No module prefix
            }
        }
        // Round 84: No parent fallback - files without patterns map to root
        // This prevents YAML in arbitrary directories from creating modules
        return "";
    }

    /**
     * Codex P2.1h Round 65: Overload for backward compatibility - uses all modules.
     * Used when we need ALL patterns regardless of module (e.g., determineSourceRoot).
     */
    private static List<String> getEffectiveMainSourceRootPatterns(Accumulator acc) {
        Set<String> combined = new LinkedHashSet<>(DEFAULT_MAIN_SOURCE_ROOT_PATTERNS);
        if (acc.yamlConfigLoaded && !acc.customSourceRootsByModule.isEmpty()) {
            // Collect all custom roots from all modules
            for (Set<String> moduleRoots : acc.customSourceRootsByModule.values()) {
                combined.addAll(moduleRoots);
            }
        }
        // Sort by length descending (longest/most specific first)
        List<String> result = new ArrayList<>(combined);
        result.sort((a, b) -> {
            int lenCmp = Integer.compare(b.length(), a.length());
            return lenCmp != 0 ? lenCmp : a.compareTo(b);
        });
        return result;
    }

    /**
     * Codex P2.1h Round 65: Extract source root from a file path for DETECTION.
     * Uses module-specific effective source root patterns (custom or default).
     * E.g., "module-a/src/test/java/de/example/.../NeedsReview.java" -> "module-a/src/test/java"
     * Path should be normalized (forward slashes) before calling.
     *
     * Round 61: Uses segment-based matching to avoid false positives like src/main/javax.
     * Round 65: Now module-aware to avoid cross-module interference.
     */
    private String extractSourceRoot(String path, Accumulator acc) {
        for (String pattern : getEffectiveAllSourceRootPatterns(path, acc)) {
            int idx = findPatternWithSegmentBoundary(path, pattern);
            if (idx >= 0) {
                return path.substring(0, idx) + pattern;
            }
        }
        return null;
    }

    /**
     * Codex P2.1h Round 61: Find pattern in path with proper segment boundaries.
     * Ensures the pattern starts at a segment boundary (start of string or after /)
     * and ends at a segment boundary (end of string or before /).
     * Avoids false positives like matching "src/main/java" in "src/main/javax".
     */
    private static int findPatternWithSegmentBoundary(String path, String pattern) {
        int idx = 0;
        while ((idx = path.indexOf(pattern, idx)) >= 0) {
            // Check start boundary: must be at start or after /
            boolean startOk = (idx == 0) || (path.charAt(idx - 1) == '/');
            // Check end boundary: must be at end or before /
            int endIdx = idx + pattern.length();
            boolean endOk = (endIdx == path.length()) || (path.charAt(endIdx) == '/');

            if (startOk && endOk) {
                return idx;
            }
            idx++;
        }
        return -1;
    }

    // Codex P2.1h Round 67: Removed unused determineSourceRoot method (dead code)
}
