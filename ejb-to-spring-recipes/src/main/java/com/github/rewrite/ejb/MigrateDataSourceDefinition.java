package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.tree.Properties;

import java.util.*;

/**
 * Migrates @DataSourceDefinition to Spring Boot application.properties.
 * <p>
 * This recipe:
 * 1. Extracts connection info from @DataSourceDefinition
 * 2. Appends spring.datasource.* properties to existing application.properties
 * 3. Removes @DataSourceDefinition from the class
 * 4. Removes @NeedsReview from the associated DataSource field ONLY if properties were written
 * <p>
 * IMPORTANT: This recipe MUST run in a separate phase AFTER MigratePersistenceXmlToProperties
 * (which creates application.properties). Running in the same phase will cause the scanner
 * to not find application.properties since all scanners run before any generators.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateDataSourceDefinition extends ScanningRecipe<MigrateDataSourceDefinition.Accumulator> {

    private static final String DATASOURCE_DEFINITION_FQN = "jakarta.annotation.sql.DataSourceDefinition";
    private static final String JAVAX_DATASOURCE_DEFINITION_FQN = "javax.annotation.sql.DataSourceDefinition";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    /**
     * Codex P2.1h Round 30: Convention over Configuration - Default Maven/Gradle source roots.
     * These are the standard source root patterns that work out-of-the-box.
     * Custom layouts can be configured via .migration-source-roots.yaml.
     */
    private static final List<String> DEFAULT_SOURCE_ROOTS = List.of(
        "src/main/java",
        "src/test/java",
        "src/it/java",                    // Maven integration tests
        "src/integrationTest/java",       // Gradle integration tests
        "target/generated-sources",       // Maven generated (any subtree)
        "target/generated-test-sources",  // Maven generated test sources
        "build/generated"                 // Gradle generated (any subtree)
    );

    /** Config file name for custom source root layouts */
    private static final String SOURCE_ROOTS_CONFIG_FILE = ".migration-source-roots.yaml";

    @Override
    public String getDisplayName() {
        return "Migrate @DataSourceDefinition to Spring Boot properties";
    }

    @Override
    public String getDescription() {
        return "Extracts @DataSourceDefinition configuration and appends to application.properties. " +
               "Must run after MigratePersistenceXmlToProperties creates application.properties.";
    }

    /**
     * Codex P2.1h-1: Force 2 cycles for safe @NeedsReview removal.
     * Cycle 1: Writes DS config to application.properties
     * Cycle 2: Scanner detects written config -> Visitor removes @NeedsReview
     * This eliminates the need for separate migration runs.
     */
    @Override
    public int maxCycles() {
        return 2;
    }

    static class DataSourceInfo {
        String name;        // JNDI name
        String className;   // Driver class
        String url;
        String user;
        String password;
        String sourcePath;  // Full path to the Java file containing @DataSourceDefinition
        String moduleRoot;  // Module root (e.g., "module-a/" or "" for root module) - Codex 2.1
        String originalAnnotationCode; // Codex P2.1f-2: For @NeedsReview originalCode when JNDI not extractable
        String stableKey;   // Codex P2.1g-3: Stable key for matching (sourcePath + className)
        // Use TreeSet for deterministic output order (Codex 2.6)
        Set<String> unsupportedAttributes = new TreeSet<>();
        // Track attributes with non-literal values (Codex 2.5)
        Set<String> nonLiteralAttributes = new TreeSet<>();

        // Supported attributes that we can migrate
        private static final Set<String> SUPPORTED_ATTRIBUTES = Set.of(
            "name", "className", "url", "user", "password",
            "maxPoolSize", "minPoolSize", "maxIdleTime", "idleTimeout", "loginTimeout", "initialPoolSize"
        );

        // DS-001: Hikari Pool Properties
        Integer maxPoolSize;
        Integer minPoolSize;
        Integer maxIdleTime;      // in seconds - will be converted to ms for Hikari
        Integer loginTimeout;     // in seconds - will be converted to ms for Hikari
        Integer initialPoolSize;

        boolean hasUnsupportedAttributes() {
            return !unsupportedAttributes.isEmpty();
        }

        /** Returns true if any critical attributes have non-literal values (Codex 2.7: name included) */
        boolean hasNonLiteralMigratableAttributes() {
            // Codex 2.7: name must be literal for JNDI tracking/TODO-removal to work
            // url/user/password must be literal for safe properties writing
            return nonLiteralAttributes.contains("name") ||
                   nonLiteralAttributes.contains("url") ||
                   nonLiteralAttributes.contains("user") ||
                   nonLiteralAttributes.contains("password");
        }

        void trackAttribute(String attrName) {
            if (!SUPPORTED_ATTRIBUTES.contains(attrName)) {
                unsupportedAttributes.add(attrName);
            }
        }

        void trackNonLiteralAttribute(String attrName) {
            nonLiteralAttributes.add(attrName);
        }

        /**
         * Codex P2.1h Round 45: Module root extraction with pattern-length specificity.
         * Selects the best root using (in order):
         * 1. Highest endIdx (matchIdx + patternLength) - deepest match wins
         * 2. Longest patternLength (more segments = more specific) - specificity!
         * 3. Custom root over default (only at equal specificity!)
         * 4. Lexicographically smallest pattern (deterministic final fallback)
         *
         * Key insight: A longer pattern is MORE specific, even if it starts earlier.
         * Example: `module/src/main/java` (4 segments) is more specific than
         * `src/main/java` (3 segments) at the same endIdx.
         */
        void computeModuleRoot(List<String> effectiveSourceRoots) {
            if (sourcePath == null) {
                moduleRoot = "";
                return;
            }
            // Normalize path for consistent matching
            String normalizedPath = sourcePath.replace('\\', '/');
            String[] pathSegments = normalizedPath.split("/");

            // Round 45/51: Pattern LENGTH as specificity metric (not matchIdx)
            // 1. Higher endIdx (deeper match)
            // 2. Longer pattern (more segments = more specific)
            // 3. Lexicographic pattern (deterministic fallback for equal specificity)
            // Note: isCustom tie-breaker removed in Round 51 - it was structurally unreachable
            // because equal endIdx + equal patternLength implies identical patterns, and
            // effectiveRoots is deduplicated (a pattern can only appear once).
            int bestEndIdx = -1;
            int bestPatternLength = -1;
            int bestStartIdx = -1;  // Still needed for moduleRoot calculation
            String bestPattern = null;

            for (String pattern : effectiveSourceRoots) {
                String[] patternSegments = pattern.split("/");
                // Find the LAST (deepest) match for this pattern
                int matchIdx = findLastSegmentMatch(pathSegments, patternSegments);
                if (matchIdx >= 0) {
                    int endIdx = matchIdx + patternSegments.length;
                    int patternLength = patternSegments.length;
                    // Round 51: Simplified tie-breaker (isCustom removed as dead code)
                    boolean isBetter = (endIdx > bestEndIdx)
                        || (endIdx == bestEndIdx && patternLength > bestPatternLength)
                        || (endIdx == bestEndIdx && patternLength == bestPatternLength
                            && (bestPattern == null || pattern.compareTo(bestPattern) < 0));
                    if (isBetter) {
                        bestEndIdx = endIdx;
                        bestPatternLength = patternLength;
                        bestStartIdx = matchIdx;
                        bestPattern = pattern;
                    }
                }
            }

            // Build module root from the best match (segments before bestStartIdx)
            if (bestStartIdx >= 0) {
                if (bestStartIdx == 0) {
                    moduleRoot = "";
                } else {
                    StringBuilder moduleBuilder = new StringBuilder();
                    for (int i = 0; i < bestStartIdx; i++) {
                        if (i > 0) moduleBuilder.append("/");
                        moduleBuilder.append(pathSegments[i]);
                    }
                    moduleRoot = moduleBuilder.toString() + "/";
                }
            } else {
                // Fallback: no known pattern found
                moduleRoot = "";
            }
        }

        /**
         * Codex P2.1h Round 36 Fix 1: Find LAST (deepest) segment match.
         * Returns the start index of the last match, or -1 if not found.
         * This ensures nested paths pick the match closest to Java files.
         */
        private int findLastSegmentMatch(String[] pathSegments, String[] patternSegments) {
            if (patternSegments.length == 0 || pathSegments.length < patternSegments.length) {
                return -1;
            }
            // Start from the END to find the last/deepest match
            outer:
            for (int i = pathSegments.length - patternSegments.length; i >= 0; i--) {
                for (int j = 0; j < patternSegments.length; j++) {
                    if (!pathSegments[i + j].equals(patternSegments[j])) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
    }

    static class Accumulator {
        /** DataSources that can be migrated (have url and only supported attributes) */
        List<DataSourceInfo> dataSources = new ArrayList<>();
        /** JNDI names of successfully migrated @DataSourceDefinition (for removal check) */
        Set<String> migratedJndiNames = new HashSet<>();
        /** JNDI names of @DataSourceDefinition that could NOT be migrated (no url) */
        Set<String> nonMigratableJndiNames = new HashSet<>();
        /** JNDI names that are non-migratable because of Multi-DS (>1) - different reason message */
        Set<String> multiDsJndiNames = new HashSet<>();
        /** Codex P2.1f-1: Per-JNDI count for Multi-DS (correct per-module count, not global) */
        Map<String, Integer> multiDsCountByJndi = new HashMap<>();
        /** JNDI names with unsupported attributes (serverName, portNumber, etc.) - maps to attribute names */
        Map<String, Set<String>> unsupportedAttributesByJndi = new HashMap<>();
        /** JNDI names with non-literal values in url/user/password (Codex 2.5) - maps to attribute names */
        Map<String, Set<String>> nonLiteralAttributesByJndi = new HashMap<>();
        /** Maps source root (e.g., "module-a/") to its application.properties path (Codex 2.1) */
        Map<String, String> sourceRootToPropertiesPath = new LinkedHashMap<>();
        /** Codex 2.6: Counts ALL @DataSourceDefinition per module (regardless of migratability) for Multi-DS detection */
        Map<String, List<String>> allDsJndiPerModule = new LinkedHashMap<>();
        /** Codex P2.1f-2: Count of DS per module INCLUDING those without literal JNDI (for correct Multi-DS detection) */
        Map<String, Integer> totalDsCountPerModule = new LinkedHashMap<>();
        /** Codex P2.1f-2: DataSourceInfos with non-literal name (need @NeedsReview but JNDI not extractable) */
        List<DataSourceInfo> nonLiteralNameDataSources = new ArrayList<>();
        /** Codex P2.1h-2: Module-scoped JNDIs with DS config (moduleRoot -> Set of JNDIs) */
        Map<String, Set<String>> configuredJndisByModule = new LinkedHashMap<>();
        /**
         * Codex P2.1h Round 48: All scanned DataSourceInfo objects (before moduleRoot computation).
         * ModuleRoot is computed in the visitor phase AFTER all files are scanned to ensure
         * custom source roots from YAML config are loaded (fixes order-dependency issue).
         */
        List<DataSourceInfo> allScannedDataSources = new ArrayList<>();
        // Codex P2.1g-6: Removed propertiesWrittenForJndi (was dead code - written but never read)
        // Codex P2.1f-6: Removed unused fields hasApplicationProperties, detectedSourceRoots (0 tech debt)

        /**
         * Codex P2.1h Round 30/31/42: Custom source roots from .migration-source-roots.yaml.
         * If empty, DEFAULT_SOURCE_ROOTS are used (convention over configuration).
         * Multi-module: all found configs are merged (union).
         * Round 42: LinkedHashSet for O(1) lookup + deterministic iteration order.
         */
        Set<String> customSourceRoots = new LinkedHashSet<>();

        /**
         * Codex P2.1h Round 53: Custom roots with module prefix that may cause wrong moduleRoot.
         * E.g., "module/src/main/java" would be problematic because the algorithm extracts
         * moduleRoot from the part BEFORE the matching source root, causing confusion.
         * These roots are tracked for @NeedsReview emission.
         */
        Set<String> problematicCustomRoots = new LinkedHashSet<>();

        // Codex P2.1h Round 60: Removed emittedProblematicRootsWarning (dead code, replaced by per-module approach)

        /** Codex P2.1h Round 59/60: Per-module target class for problematic roots warning.
         *  Key: module prefix (e.g., "moduleA/"), Value: lowest sourcePath in that module. */
        Map<String, String> problematicRootsTargetByModule = new LinkedHashMap<>();

        /**
         * Codex P2.1h Round 34 Fix 2: Returns effective source roots sorted by specificity.
         * Custom roots are merged with defaults, then sorted by:
         * 1. Length descending (most specific first)
         * 2. Lexicographic ascending (deterministic tie-breaker for equal-length)
         */
        List<String> getEffectiveSourceRoots() {
            List<String> merged = new ArrayList<>(DEFAULT_SOURCE_ROOTS);
            // Add custom roots (avoiding duplicates)
            for (String custom : customSourceRoots) {
                if (!merged.contains(custom)) {
                    merged.add(custom);
                }
            }
            // Sort by length descending, then lexicographic (deterministic)
            merged.sort((a, b) -> {
                int lenCompare = Integer.compare(b.length(), a.length());
                return lenCompare != 0 ? lenCompare : a.compareTo(b);
            });
            return merged;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    // Codex P2.1h Round 97: Marker key for cycle-based clearing (fix for per-file getScanner calls)
    private static final String SCANNER_CLEAR_MARKER = "MigrateDataSourceDefinition.scannerCleared";

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        // Codex P2.1h Round 97 FIX: getScanner() is called for each file, not once per cycle.
        // We MUST use ExecutionContext to track if we've already cleared for this cycle.
        // Without this fix, the accumulator is cleared for each file, losing data.
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                // Only clear once per cycle (first file triggers clear)
                if (ctx.getMessage(SCANNER_CLEAR_MARKER) == null) {
                    ctx.putMessage(SCANNER_CLEAR_MARKER, Boolean.TRUE);
                    // Codex P2.1h Round 50/53/60: Clear scanner-phase data to prevent double-accumulation in cycle 2.
                    acc.allScannedDataSources.clear();
                    acc.customSourceRoots.clear();
                    acc.problematicCustomRoots.clear();
                    acc.sourceRootToPropertiesPath.clear();
                    acc.configuredJndisByModule.clear();
                    acc.problematicRootsTargetByModule.clear();
                }
                return doScan(tree, ctx, acc);
            }
        };
    }

    /** Actual scanning logic (called directly from getScanner visitor) */
    private Tree doScan(Tree tree, ExecutionContext ctx, Accumulator acc) {
        if (tree instanceof SourceFile) {
            SourceFile sf = (SourceFile) tree;
            String path = sf.getSourcePath().toString();

            // Codex P2.1h Round 30/31 Fix 3: Load custom source roots from YAML config
            // Multi-module: merge all found configs (duplicates prevented in loadCustomSourceRoots)
            if (path.endsWith(SOURCE_ROOTS_CONFIG_FILE)) {
                loadCustomSourceRoots(sf.printAll(), acc);
            }

            // Detect application.properties - can be PlainText or Properties type
            // Multi-module support (Codex 2.1): Map each module's properties to its source root
            if (path.endsWith("application.properties") && path.contains("src/main/resources")) {
                // Extract module root from properties path (Codex 2.1)
                // e.g., "module-a/src/main/resources/application.properties" -> "module-a/"
                int srcIdx = path.indexOf("src/main/resources");
                if (srcIdx >= 0) {
                    String moduleRoot = srcIdx > 0 ? path.substring(0, srcIdx) : "";
                    acc.sourceRootToPropertiesPath.put(moduleRoot, path);

                    // Codex P2.1h-2: Scan for already-configured JNDIs (module-scoped)
                    // This enables safe @NeedsReview removal for idempotent runs
                    String content = sf.printAll();
                    if (content.contains(DS_BLOCK_BEGIN) && content.contains("# Original JNDI:")) {
                        // Extract JNDIs from existing DS block into module-scoped map
                        for (String line : content.split("\n")) {
                            if (line.startsWith("# Original JNDI:")) {
                                String jndi = line.substring("# Original JNDI:".length()).trim();
                                if (!jndi.isEmpty()) {
                                    acc.configuredJndisByModule
                                        .computeIfAbsent(moduleRoot, k -> new HashSet<>())
                                        .add(jndi);
                                }
                            }
                        }
                    }
                }
            }
        }

        // For Java files, use Java visitor
        if (tree instanceof J.CompilationUnit) {
            return new JavaScanner(acc).visit(tree, ctx);
        }

        return tree;
    }

    /**
     * Codex P2.1h Round 20: Central path normalization for cross-platform support.
     * Converts Windows-style backslashes to forward slashes for consistent stableKey handling.
     * All stableKey operations should use this method to ensure consistent path representation.
     * Static to be accessible from all inner classes (JavaScanner, ClassVisitor).
     */
    private static String normalizeSourcePath(String path) {
        if (path == null) {
            return null;
        }
        return path.replace('\\', '/');
    }

    /**
     * Codex P2.1h Round 59: Extract module prefix from a path.
     * Returns everything before the first standard source root pattern.
     * E.g., "moduleA/src/main/java/Foo.java" -> "moduleA/"
     * E.g., "src/main/java/Foo.java" -> ""
     * Static to be accessible from inner classes.
     */
    private static String extractModulePrefix(String path) {
        if (path == null) {
            return "";
        }
        String normalized = path.replace('\\', '/');
        // Standard source root patterns to look for
        String[] patterns = {"src/main/java", "src/java", "src/test/java", "src/main/resources", "src/"};
        for (String pattern : patterns) {
            int idx = normalized.indexOf(pattern);
            if (idx >= 0) {
                return normalized.substring(0, idx);
            }
        }
        // No pattern found - return empty (single-module project)
        return "";
    }

    /**
     * Codex P2.1h Round 63: Base64 URL-safe encoding for stableKey.
     * Uses Base64 URL-safe encoding to ensure collision-free, reversible keys.
     * Base64 avoids the '+' vs space ambiguity that URL encoding has.
     * E.g., "module/src/main/java" -> "bW9kdWxlL3NyYy9tYWluL2phdmE"
     */
    private static String sanitizeForStableKey(String path) {
        if (path == null) {
            return "";
        }
        // Base64 URL-safe encoding without padding for clean, reversible keys
        return java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(path.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Codex P2.1h Round 62: Build stableKey for problematic custom root.
     * Shared method for consistency between dedup and annotation creation.
     */
    private static String buildProblematicRootStableKey(String problematicRoot) {
        return "_config_problematic_root:" + sanitizeForStableKey(problematicRoot);
    }

    /**
     * Codex P2.1h Round 63: Decode Base64 URL-safe encoded root from stableKey.
     * Reverses the sanitizeForStableKey encoding for removal logic.
     * Returns null if decoding fails (e.g., old format key).
     */
    private static String decodeStableKeyRoot(String encodedRoot) {
        if (encodedRoot == null || encodedRoot.isEmpty()) {
            return null;
        }
        try {
            byte[] decoded = java.util.Base64.getUrlDecoder().decode(encodedRoot);
            return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            // Not valid Base64 - likely an old format key
            return null;
        }
    }

    /**
     * Codex P2.1h Round 30/53: Load custom source roots from YAML configuration.
     * Expected format:
     * <pre>
     * # .migration-source-roots.yaml
     * sourceRoots:
     *   - src/main/java
     *   - src/test/java
     *   - custom/generated/java
     * </pre>
     * Uses simple line parsing to avoid external YAML library dependency.
     * Round 53: Validates for module prefix patterns that would cause incorrect moduleRoot.
     */
    private static void loadCustomSourceRoots(String yamlContent, Accumulator acc) {
        if (yamlContent == null || yamlContent.isEmpty()) {
            return;
        }
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
                        // Codex P2.1h Round 31 Fix 2: Normalize custom roots
                        // - Convert backslashes to forward slashes
                        // - Remove trailing slashes for consistent matching
                        root = root.replace('\\', '/');
                        while (root.endsWith("/")) {
                            root = root.substring(0, root.length() - 1);
                        }
                        // LinkedHashSet handles duplicates automatically
                        if (!root.isEmpty()) {
                            // Codex P2.1h Round 53/54/55: Validate for module prefix patterns
                            String problematicSuffix = detectModulePrefixPattern(root);
                            if (problematicSuffix != null) {
                                // Round 55: Skip problematic roots AND emit warning
                                // The standard root (problematicSuffix) is already in DEFAULT_SOURCE_ROOTS
                                acc.problematicCustomRoots.add(root);
                                // Emit warning to stderr (visible in migration output)
                                System.err.println("[WARNING] MigrateDataSourceDefinition: Custom source root '" + root +
                                    "' contains module prefix before standard pattern '" + problematicSuffix +
                                    "'. This root will be IGNORED to prevent incorrect moduleRoot calculation. " +
                                    "Use just '" + problematicSuffix + "' instead.");
                                // Do NOT add to customSourceRoots - this is a misconfiguration
                            } else {
                                acc.customSourceRoots.add(root);
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
     * Codex P2.1h Round 53: Detect if a custom root has a module prefix pattern.
     * A module prefix pattern is when a custom root contains a standard source root
     * as a suffix (e.g., "module/src/main/java" contains "src/main/java").
     * This causes incorrect moduleRoot extraction.
     * @return the standard root suffix if found, null otherwise
     */
    private static String detectModulePrefixPattern(String customRoot) {
        for (String defaultRoot : DEFAULT_SOURCE_ROOTS) {
            // Check if custom root ends with a default root pattern (after a /)
            if (customRoot.endsWith("/" + defaultRoot)) {
                return defaultRoot;
            }
            // Also check if custom root equals a default root with a prefix
            // e.g., "module/src/main/java" starts with any prefix before "src/main/java"
            int idx = customRoot.indexOf("/" + defaultRoot);
            if (idx > 0) {
                // Has a module prefix before the standard root
                return defaultRoot;
            }
        }
        return null;
    }

    /**
     * DS-001: Simple Constant Evaluation - Resolves static final constants within the same class.
     * This allows @DataSourceDefinition attributes like url = DB_URL to be resolved when DB_URL
     * is defined as a static final String in the same class.
     */
    private static class ConstantResolver {

        /**
         * Extracts all static final String/int/long/boolean fields from a class declaration.
         * Only processes fields with literal initializers (no method calls or complex expressions).
         *
         * @param classDecl The class declaration to scan for constants
         * @return Map of constant name to resolved value
         */
        static Map<String, Object> extractClassConstants(J.ClassDeclaration classDecl) {
            return extractClassConstants(classDecl, Collections.emptyList());
        }

        /**
         * DS-001 Medium Fix: Extracts constants from a class and its implemented interfaces.
         * Interface constants in the same compilation unit are also resolved.
         *
         * @param classDecl The class declaration to scan for constants
         * @param sameFileInterfaces List of interfaces defined in the same compilation unit
         * @return Map of constant name to resolved value
         */
        static Map<String, Object> extractClassConstants(J.ClassDeclaration classDecl,
                                                          List<J.ClassDeclaration> sameFileInterfaces) {
            Map<String, Object> constants = new HashMap<>();

            // First: Extract constants from implemented interfaces (lower priority)
            // Interface constants are resolved first so class constants can override them
            for (J.ClassDeclaration iface : sameFileInterfaces) {
                if (classImplementsInterface(classDecl, iface)) {
                    extractConstantsFromBody(iface, constants);
                }
            }

            // Second: Extract constants from the class itself (higher priority - can override)
            extractConstantsFromBody(classDecl, constants);

            return constants;
        }

        /**
         * Extracts constants from a class or interface body into the provided map.
         * DS-001 Enhancement: Now handles J.Binary expressions for string concatenation
         * and numeric arithmetic (e.g., HOST + "/db", 10 * 2).
         */
        private static void extractConstantsFromBody(J.ClassDeclaration decl, Map<String, Object> constants) {
            if (decl.getBody() == null) {
                return;
            }

            // First pass: extract all simple literals
            for (Statement stmt : decl.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations varDecls = (J.VariableDeclarations) stmt;

                    // For interfaces, fields are implicitly public static final
                    // For classes, check for explicit static final modifiers
                    boolean isInterface = decl.getKind() == J.ClassDeclaration.Kind.Type.Interface;
                    boolean isStatic = isInterface;
                    boolean isFinal = isInterface;

                    if (!isInterface) {
                        for (J.Modifier modifier : varDecls.getModifiers()) {
                            if (modifier.getType() == J.Modifier.Type.Static) {
                                isStatic = true;
                            } else if (modifier.getType() == J.Modifier.Type.Final) {
                                isFinal = true;
                            }
                        }
                    }

                    if (!isStatic || !isFinal) {
                        continue;
                    }

                    // Extract values from each variable in the declaration
                    for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                        String name = var.getSimpleName();
                        Expression initializer = var.getInitializer();

                        if (initializer instanceof J.Literal) {
                            J.Literal literal = (J.Literal) initializer;
                            Object value = literal.getValue();
                            if (value != null) {
                                constants.put(name, value);
                            }
                        }
                    }
                }
            }

            // Second pass: resolve binary expressions using already-known constants
            // This allows resolving expressions like: static final String URL = HOST + "/db";
            // where HOST was defined earlier in the class
            for (Statement stmt : decl.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations varDecls = (J.VariableDeclarations) stmt;

                    // For interfaces, fields are implicitly public static final
                    // For classes, check for explicit static final modifiers
                    boolean isInterface = decl.getKind() == J.ClassDeclaration.Kind.Type.Interface;
                    boolean isStatic = isInterface;
                    boolean isFinal = isInterface;

                    if (!isInterface) {
                        for (J.Modifier modifier : varDecls.getModifiers()) {
                            if (modifier.getType() == J.Modifier.Type.Static) {
                                isStatic = true;
                            } else if (modifier.getType() == J.Modifier.Type.Final) {
                                isFinal = true;
                            }
                        }
                    }

                    if (!isStatic || !isFinal) {
                        continue;
                    }

                    for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                        String name = var.getSimpleName();
                        Expression initializer = var.getInitializer();

                        // Skip if already resolved in first pass
                        if (constants.containsKey(name)) {
                            continue;
                        }

                        // Try to evaluate binary expressions
                        if (initializer instanceof J.Binary) {
                            Object evaluated = evaluateBinaryExpression((J.Binary) initializer, constants);
                            if (evaluated != null) {
                                constants.put(name, evaluated);
                            }
                        }
                    }
                }
            }
        }

        /**
         * DS-001: Evaluates a binary expression recursively.
         * Handles string concatenation and numeric arithmetic operations.
         *
         * @param binary The binary expression to evaluate
         * @param constants Map of known constant names to values
         * @return The evaluated result, or null if not fully resolvable
         */
        static Object evaluateBinaryExpression(J.Binary binary, Map<String, Object> constants) {
            Object left = evaluateExpression(binary.getLeft(), constants);
            Object right = evaluateExpression(binary.getRight(), constants);

            if (left == null || right == null) {
                return null;
            }

            J.Binary.Type operator = binary.getOperator();

            // String concatenation: if either side is a String and operator is Addition
            if (operator == J.Binary.Type.Addition &&
                (left instanceof String || right instanceof String)) {
                return left.toString() + right.toString();
            }

            // Numeric operations: both sides must be Numbers
            if (left instanceof Number && right instanceof Number) {
                double leftVal = ((Number) left).doubleValue();
                double rightVal = ((Number) right).doubleValue();

                switch (operator) {
                    case Addition:
                        return simplifyNumber(leftVal + rightVal, left, right);
                    case Subtraction:
                        return simplifyNumber(leftVal - rightVal, left, right);
                    case Multiplication:
                        return simplifyNumber(leftVal * rightVal, left, right);
                    case Division:
                        if (rightVal == 0) {
                            return null; // Avoid division by zero
                        }
                        return simplifyNumber(leftVal / rightVal, left, right);
                    case Modulo:
                        if (rightVal == 0) {
                            return null; // Avoid modulo by zero
                        }
                        return simplifyNumber(leftVal % rightVal, left, right);
                    default:
                        return null; // Unsupported operator
                }
            }

            return null;
        }

        /**
         * DS-001: Evaluates an expression to a value.
         * Handles literals, identifiers (constant references), and nested binary expressions.
         *
         * @param expr The expression to evaluate
         * @param constants Map of known constant names to values
         * @return The evaluated value, or null if not resolvable
         */
        static Object evaluateExpression(Expression expr, Map<String, Object> constants) {
            if (expr instanceof J.Literal) {
                return ((J.Literal) expr).getValue();
            }

            if (expr instanceof J.Identifier) {
                String name = ((J.Identifier) expr).getSimpleName();
                return constants.get(name);
            }

            if (expr instanceof J.Binary) {
                return evaluateBinaryExpression((J.Binary) expr, constants);
            }

            if (expr instanceof J.Parentheses) {
                // Handle parenthesized expressions: (a + b)
                J.Parentheses<?> parens = (J.Parentheses<?>) expr;
                if (parens.getTree() instanceof Expression) {
                    return evaluateExpression((Expression) parens.getTree(), constants);
                }
            }

            return null;
        }

        /**
         * DS-001: Simplifies a double result to Integer or Long if appropriate.
         * Preserves the original numeric type when possible.
         *
         * @param result The computed double result
         * @param left Original left operand
         * @param right Original right operand
         * @return Integer, Long, or Double based on the operand types and result
         */
        private static Number simplifyNumber(double result, Object left, Object right) {
            // If result is a whole number and both operands were integers, return integer
            if (result == Math.floor(result) && !Double.isInfinite(result)) {
                if ((left instanceof Integer || left instanceof Long) &&
                    (right instanceof Integer || right instanceof Long)) {
                    if (result >= Integer.MIN_VALUE && result <= Integer.MAX_VALUE) {
                        return (int) result;
                    } else {
                        return (long) result;
                    }
                }
            }
            return result;
        }

        /**
         * Checks if a class implements a given interface (by simple name comparison).
         * Only checks direct implementation, not transitive.
         */
        private static boolean classImplementsInterface(J.ClassDeclaration classDecl, J.ClassDeclaration iface) {
            if (classDecl.getImplements() == null) {
                return false;
            }
            String ifaceName = iface.getSimpleName();
            for (TypeTree implemented : classDecl.getImplements()) {
                // Compare simple names - works for same-file interfaces
                if (implemented instanceof J.Identifier) {
                    if (ifaceName.equals(((J.Identifier) implemented).getSimpleName())) {
                        return true;
                    }
                } else if (implemented instanceof J.FieldAccess) {
                    // Qualified name like pkg.InterfaceName
                    J.FieldAccess fa = (J.FieldAccess) implemented;
                    if (ifaceName.equals(fa.getSimpleName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Attempts to resolve an expression against the class constants map.
         * Handles J.Identifier references to constants and J.Binary expressions.
         *
         * DS-001 Enhancement: Now uses evaluateExpression for full support of
         * binary expressions (string concatenation and arithmetic).
         *
         * @param expr The expression to resolve
         * @param constants Map of known constant names to values
         * @return The resolved value as String, or null if not resolvable
         */
        static String resolveValue(Expression expr, Map<String, Object> constants) {
            Object result = evaluateExpression(expr, constants);
            return result != null ? result.toString() : null;
        }

        /**
         * Checks if an expression is a simple identifier that could be a constant reference.
         */
        static boolean isSimpleIdentifier(Expression expr) {
            return expr instanceof J.Identifier;
        }
    }

    private static class JavaScanner extends JavaIsoVisitor<ExecutionContext> {
        private final Accumulator acc;

        JavaScanner(Accumulator acc) {
            this.acc = acc;
        }

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            // Codex P2.1f-6: Removed unused detectedSourceRoots tracking
            return super.visitCompilationUnit(cu, ctx);
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
            J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
            String sourcePath = cu.getSourcePath().toString();
            String classSimpleName = classDecl.getSimpleName();

            // Codex P2.1h Round 59: Track lowest sourcePath per module for deterministic problematicRoots placement
            String modulePrefix = extractModulePrefix(sourcePath);
            String currentTarget = acc.problematicRootsTargetByModule.get(modulePrefix);
            if (currentTarget == null || sourcePath.compareTo(currentTarget) < 0) {
                acc.problematicRootsTargetByModule.put(modulePrefix, sourcePath);
            }

            // Codex P2.1h-3: dsIdx counter for indexed annotation keys (per-class)
            // Handles multiple @DataSourceDefinition on the same class
            int dsIdx = 0;

            // DS-001: Extract class-level constants for simple constant evaluation
            // DS-001 Medium Fix: Also include constants from implemented interfaces in the same file
            List<J.ClassDeclaration> sameFileInterfaces = collectInterfacesFromCompilationUnit(cu);
            Map<String, Object> classConstants = ConstantResolver.extractClassConstants(classDecl, sameFileInterfaces);

            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if (isDataSourceDefinition(ann)) {
                    DataSourceInfo info = extractDataSourceInfo(ann, classConstants);
                    if (info == null) continue;

                    // Codex P2.1f-2: Store originalAnnotationCode for @NeedsReview
                    info.originalAnnotationCode = ann.printTrimmed(getCursor());

                    // Codex 2.6: Store source path for later module root computation
                    info.sourcePath = sourcePath;
                    // Codex P2.1h-3: Indexed stable key for matching (handles multiple DS per class)
                    // Codex P2.1h Round 20: Use central normalization method
                    String normalizedSourcePath = normalizeSourcePath(sourcePath);
                    info.stableKey = normalizedSourcePath + "#" + classSimpleName + "#ds=" + dsIdx++;

                    // Codex P2.1h Round 48: Collect ALL DataSourceInfo for deferred moduleRoot computation.
                    // moduleRoot will be computed in getVisitor() AFTER all files are scanned,
                    // ensuring custom source roots from YAML config are loaded (fixes order-dependency).
                    acc.allScannedDataSources.add(info);
                }
            }

            return cd;
        }

        private boolean isDataSourceDefinition(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), DATASOURCE_DEFINITION_FQN) ||
                   TypeUtils.isOfClassType(ann.getType(), JAVAX_DATASOURCE_DEFINITION_FQN) ||
                   "DataSourceDefinition".equals(ann.getSimpleName());
        }

        /**
         * DS-001 Medium Fix: Collects all interface declarations from a CompilationUnit.
         * Used to resolve constants defined in interfaces that the annotated class implements.
         */
        private List<J.ClassDeclaration> collectInterfacesFromCompilationUnit(J.CompilationUnit cu) {
            List<J.ClassDeclaration> interfaces = new ArrayList<>();
            for (J.ClassDeclaration classDecl : cu.getClasses()) {
                if (classDecl.getKind() == J.ClassDeclaration.Kind.Type.Interface) {
                    interfaces.add(classDecl);
                }
            }
            return interfaces;
        }

        /**
         * DS-001: Updated to accept classConstants for simple constant evaluation.
         * Now resolves references to static final fields defined in the same class.
         */
        private DataSourceInfo extractDataSourceInfo(J.Annotation ann, Map<String, Object> classConstants) {
            DataSourceInfo info = new DataSourceInfo();

            if (ann.getArguments() == null) return null;

            for (Expression arg : ann.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        Expression valueExpr = assignment.getAssignment();

                        // DS-001: Try to resolve value using ConstantResolver first
                        // This handles both direct literals and constant references
                        String value = ConstantResolver.resolveValue(valueExpr, classConstants);

                        // Track all attributes to detect unsupported ones
                        info.trackAttribute(attrName);

                        // Codex 2.5 + DS-001: Track non-literal values for supported attributes
                        // Only mark as non-literal if ConstantResolver couldn't resolve it
                        // AND it's not a direct literal (complex expressions, external references, etc.)
                        if (value == null && valueExpr != null && isNonLiteralExpression(valueExpr)) {
                            info.trackNonLiteralAttribute(attrName);
                        }

                        switch (attrName) {
                            case "name":
                                info.name = value;
                                break;
                            case "className":
                                info.className = value;
                                break;
                            case "url":
                                info.url = value;
                                break;
                            case "user":
                                info.user = value;
                                break;
                            case "password":
                                info.password = value;
                                break;
                            // DS-001: Hikari Pool Properties
                            case "maxPoolSize":
                                info.maxPoolSize = extractIntValue(valueExpr, classConstants);
                                break;
                            case "minPoolSize":
                                info.minPoolSize = extractIntValue(valueExpr, classConstants);
                                break;
                            case "maxIdleTime":
                            case "idleTimeout":  // DS-001: Alias for maxIdleTime
                                info.maxIdleTime = extractIntValue(valueExpr, classConstants);
                                break;
                            case "loginTimeout":
                                info.loginTimeout = extractIntValue(valueExpr, classConstants);
                                break;
                            case "initialPoolSize":
                                info.initialPoolSize = extractIntValue(valueExpr, classConstants);
                                break;
                            // All other attributes (serverName, portNumber, databaseName, etc.)
                            // are tracked as unsupported via info.trackAttribute()
                        }
                    }
                }
            }

            // Return info if we found at least a name (even without url)
            // Caller will decide if it's migratable based on url presence and attributes
            return (info.name != null || info.url != null) ? info : null;
        }

        // DS-001: extractStringValue removed - replaced by ConstantResolver.resolveValue

        /**
         * DS-001: Extract integer value from expression.
         * Handles both direct literals and constant references via ConstantResolver.
         */
        private Integer extractIntValue(Expression expr, Map<String, Object> classConstants) {
            if (expr instanceof J.Literal) {
                Object value = ((J.Literal) expr).getValue();
                if (value instanceof Number) {
                    return ((Number) value).intValue();
                }
            }
            // Try ConstantResolver for constant references
            String resolved = ConstantResolver.resolveValue(expr, classConstants);
            if (resolved != null) {
                try {
                    return Integer.parseInt(resolved);
                } catch (NumberFormatException e) {
                    // Not a valid integer - return null
                }
            }
            return null;
        }

        /** Checks if expression is a non-literal (constant, field reference, method call, etc.) */
        private boolean isNonLiteralExpression(Expression expr) {
            // J.Literal is a literal - anything else is non-literal
            return !(expr instanceof J.Literal);
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // Codex P2.1h Round 97 FIX Part 2: Reset the scanner clear marker so next cycle's scanner
        // will clear the accumulator again. Without this, Cycle 2's scanner would skip clearing
        // because the marker from Cycle 1 persists in ExecutionContext.
        // This is a closure that captures 'acc' but we need ctx to remove the marker.
        // The marker removal happens in the returned TreeVisitor's visit() method.

        // Codex P2.1h Round 49: Clear lists BEFORE processing to prevent double-accumulation in cycle 2.
        // The Accumulator is reused across cycles, so we must clear computed/derived data.
        // Note: allScannedDataSources and sourceRootToPropertiesPath are populated in scanner and stay constant.
        acc.dataSources.clear();
        acc.migratedJndiNames.clear();
        acc.nonMigratableJndiNames.clear();
        acc.multiDsJndiNames.clear();
        acc.multiDsCountByJndi.clear();
        acc.unsupportedAttributesByJndi.clear();
        acc.nonLiteralAttributesByJndi.clear();
        acc.nonLiteralNameDataSources.clear();
        acc.totalDsCountPerModule.clear();
        acc.allDsJndiPerModule.clear();

        // Codex P2.1h Round 48: Deferred moduleRoot computation and categorization.
        // This ensures custom source roots from YAML config are fully loaded before
        // computing moduleRoot for Java files (fixes order-dependency issue).

        // Step 1: Compute moduleRoot for all scanned DataSourceInfo
        for (DataSourceInfo info : acc.allScannedDataSources) {
            info.computeModuleRoot(acc.getEffectiveSourceRoots());
            String moduleRoot = info.moduleRoot != null ? info.moduleRoot : "";

            // Step 2: Module-based tracking (was previously in scanner)
            acc.totalDsCountPerModule.merge(moduleRoot, 1, Integer::sum);
            if (info.name != null) {
                acc.allDsJndiPerModule
                    .computeIfAbsent(moduleRoot, k -> new ArrayList<>())
                    .add(info.name);
            }

            // Step 3: Categorization by migratability (was previously in scanner)
            if (info.name == null && info.nonLiteralAttributes.contains("name")) {
                // Non-literal name - can't track JNDI but must add @NeedsReview
                acc.nonLiteralNameDataSources.add(info);
            } else if (info.url != null
                    && !info.hasUnsupportedAttributes()
                    && !info.hasNonLiteralMigratableAttributes()) {
                // Fully migratable: has url, only supported attributes, AND all values are literals
                acc.dataSources.add(info);
                if (info.name != null) {
                    acc.migratedJndiNames.add(info.name);
                }
            } else if (info.name != null && info.hasNonLiteralMigratableAttributes()) {
                // Codex 2.5/2.7: Has non-literal values in name/url/user/password - can't safely migrate
                acc.nonLiteralAttributesByJndi.put(info.name, new TreeSet<>(info.nonLiteralAttributes));
            } else if (info.name != null && info.hasUnsupportedAttributes()) {
                // Has URL but also has unsupported attributes - can't safely remove
                acc.unsupportedAttributesByJndi.put(info.name, info.unsupportedAttributes);
            } else if (info.name != null) {
                // Non-migratable: has name but no url (uses serverName/portNumber/databaseName)
                acc.nonMigratableJndiNames.add(info.name);
            }
        }

        // Run visitor if:
        // - we have migratable datasources (to write properties and remove annotations), OR
        // - we have non-migratable datasources (to add @NeedsReview), OR
        // - we have datasources with unsupported attributes (to add @NeedsReview), OR
        // - we have datasources with non-literal values (Codex 2.5), OR
        // - we have datasources with non-literal name (Codex P2.1f-2), OR
        // - Codex P2.1h: we have configured JNDIs to potentially remove @NeedsReview from DataSource fields
        boolean hasWorkToDo = !acc.dataSources.isEmpty()
            || !acc.nonMigratableJndiNames.isEmpty()
            || !acc.unsupportedAttributesByJndi.isEmpty()
            || !acc.nonLiteralAttributesByJndi.isEmpty()
            || !acc.nonLiteralNameDataSources.isEmpty()
            || !acc.configuredJndisByModule.isEmpty();  // Codex P2.1h: Enable cycle 2 @NeedsReview removal
        // Codex P2.1h Round 98 FIX: Marker must be cleared even when hasWorkToDo == false
        // Previously returned TreeVisitor.noop() which skipped marker removal, breaking multi-cycle coordination
        if (!hasWorkToDo) {
            return new TreeVisitor<Tree, ExecutionContext>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    // Clear the marker even when there's no work to do
                    if (ctx.getMessage(SCANNER_CLEAR_MARKER) != null) {
                        ctx.putMessage(SCANNER_CLEAR_MARKER, null);
                    }
                    return tree;
                }
            };
        }

        // Codex 2.1: Group migratable datasources by module root
        Map<String, List<DataSourceInfo>> dataSourcesByModule = new LinkedHashMap<>();
        for (DataSourceInfo ds : acc.dataSources) {
            String moduleRoot = ds.moduleRoot != null ? ds.moduleRoot : "";
            dataSourcesByModule.computeIfAbsent(moduleRoot, k -> new ArrayList<>()).add(ds);
        }

        // Codex P2.1f-2: Multi-DS Safety Check based on totalDsCountPerModule (includes DS without literal JNDI)
        // If a module has >1 total datasource definitions, we CANNOT auto-migrate safely
        // because spring.datasource.* properties only support a single primary datasource.
        Set<String> modulesWithMultiDs = new HashSet<>();
        for (Map.Entry<String, Integer> entry : acc.totalDsCountPerModule.entrySet()) {
            if (entry.getValue() > 1) {
                String moduleRoot = entry.getKey();
                modulesWithMultiDs.add(moduleRoot);
                int totalCount = entry.getValue();

                // Track ALL datasources with literal JNDI in this module as multi-DS
                List<String> jndisInModule = acc.allDsJndiPerModule.getOrDefault(moduleRoot, Collections.emptyList());
                for (String jndi : jndisInModule) {
                    acc.multiDsJndiNames.add(jndi);
                    acc.multiDsCountByJndi.put(jndi, totalCount); // Codex P2.1f-1: Per-JNDI count
                    acc.migratedJndiNames.remove(jndi);
                    // Remove from other categories since Multi-DS is the primary issue
                    acc.nonMigratableJndiNames.remove(jndi);
                    acc.unsupportedAttributesByJndi.remove(jndi);
                    acc.nonLiteralAttributesByJndi.remove(jndi);
                }

                // Codex P2.1f-2: Mark DS without literal JNDI in this module as Multi-DS too
                for (DataSourceInfo ds : acc.nonLiteralNameDataSources) {
                    if (moduleRoot.equals(ds.moduleRoot != null ? ds.moduleRoot : "")) {
                        ds.nonLiteralAttributes.add("_multiDs"); // Internal marker for Multi-DS
                    }
                }
            }
        }
        // Remove multi-DS modules from migratable processing
        for (String moduleRoot : modulesWithMultiDs) {
            dataSourcesByModule.remove(moduleRoot);
        }

        // Update acc.dataSources to only contain modules with single DS
        acc.dataSources.clear();
        for (List<DataSourceInfo> moduleDs : dataSourcesByModule.values()) {
            acc.dataSources.addAll(moduleDs);
        }

        // Codex P2.1h-2: Module-scoped safe @NeedsReview removal
        // Uses maxCycles()=2: cycle 1 writes properties, cycle 2 scanner detects -> visitor removes @NeedsReview
        // Pass the module-scoped map to the visitor for correct module-level removal

        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                // Codex P2.1h Round 97 FIX Part 2: Remove scanner marker so next cycle clears accumulator.
                // This must be done in visitor phase (not getVisitor() body) because we need ctx.
                // The marker was set by getScanner() and persists across files within a cycle.
                // By removing it here in visitor phase, the next cycle's scanner will see null and clear.
                if (ctx.getMessage(SCANNER_CLEAR_MARKER) != null) {
                    ctx.putMessage(SCANNER_CLEAR_MARKER, null);
                }

                // Handle Java files - remove/mark @DataSourceDefinition
                if (tree instanceof J.CompilationUnit) {
                    // Codex 2.1: Determine if this Java file's module has application.properties
                    String javaPath = ((J.CompilationUnit) tree).getSourcePath().toString();
                    String moduleRoot = extractModuleRoot(javaPath, "src/main/java");
                    boolean canWriteForThisModule = acc.sourceRootToPropertiesPath.containsKey(moduleRoot)
                        && dataSourcesByModule.containsKey(moduleRoot);
                    // Codex P2.1h Round 3 Fix 6: Safe removal ONLY based on configuredJndisByModule
                    // This ensures @NeedsReview is only removed when DS config has ALREADY been written
                    // (from previous migration runs or Cycle 1). Removal happens in Cycle 2.
                    // We do NOT include to-be-written JNDIs to avoid premature removal.
                    Set<String> safeToRemoveForModule = new HashSet<>();
                    safeToRemoveForModule.addAll(acc.configuredJndisByModule.getOrDefault(moduleRoot, Collections.emptySet()));
                    return new DataSourceDefinitionRemover(acc, canWriteForThisModule, safeToRemoveForModule).visit(tree, ctx);
                }

                // Handle application.properties - Codex 2.1: Match to module's datasources
                if (tree instanceof SourceFile && !acc.dataSources.isEmpty()) {
                    SourceFile sf = (SourceFile) tree;
                    String path = sf.getSourcePath().toString();
                    if (path.endsWith("application.properties") && path.contains("src/main/resources")) {
                        String propsModuleRoot = extractModuleRoot(path, "src/main/resources");
                        List<DataSourceInfo> moduleDataSources = dataSourcesByModule.get(propsModuleRoot);
                        if (moduleDataSources != null && moduleDataSources.size() == 1) {
                            // Codex P2.1g-6: Removed dead code that wrote to propertiesWrittenForJndi
                            return appendDataSourcePropertiesForModule(sf, moduleDataSources, acc);
                        }
                    }
                }

                return tree;
            }
        };
    }

    /** Extracts module root from a path given the marker directory (e.g., "src/main/java" or "src/main/resources") */
    private static String extractModuleRoot(String path, String marker) {
        int idx = path.indexOf(marker);
        if (idx >= 0) {
            return idx > 0 ? path.substring(0, idx) : "";
        }
        return "";
    }

    // Marker for idempotency check (Codex 2.4)
    private static final String MIGRATION_MARKER = "# Migrated from @DataSourceDefinition";
    // Codex P2.1f-4: BEGIN/END markers for deterministic block replacement (no duplicate blocks)
    private static final String DS_BLOCK_BEGIN = "# BEGIN SPRING DATASOURCE CONFIGURATION";
    private static final String DS_BLOCK_END = "# END SPRING DATASOURCE CONFIGURATION";

    /**
     * Codex 2.8: Strict detection if className is a JDBC Driver.
     * Only return true if we are CERTAIN it's a driver (no guessing).
     */
    private boolean isDefinitelyDriverClass(String className) {
        if (className == null) return false;
        // Only accept classes that definitively end with "Driver"
        if (className.endsWith("Driver")) return true;
        // Known driver FQCNs that don't follow the convention (allowlist)
        return KNOWN_DRIVER_CLASSES.contains(className);
    }

    // Allowlist of known JDBC driver classes that don't end in "Driver"
    private static final Set<String> KNOWN_DRIVER_CLASSES = Set.of(
        // Add any known exceptions here if needed
        // Most drivers end in "Driver" so this should be minimal
    );

    /**
     * Codex 2.1: Module-aware version of appendDataSourceProperties.
     * Codex P2.1f-4: Uses BEGIN/END markers for deterministic replace instead of append.
     */
    private SourceFile appendDataSourcePropertiesForModule(SourceFile propertiesFile,
                                                            List<DataSourceInfo> moduleDataSources,
                                                            Accumulator acc) {
        // Get current content
        String content = propertiesFile.printAll();

        // Collect JNDI names from this module's datasources for TODO block removal
        Set<String> moduleJndiNames = new HashSet<>();
        for (DataSourceInfo ds : moduleDataSources) {
            if (ds.name != null) {
                moduleJndiNames.add(ds.name);
            }
        }

        // Codex P2.1g-4: Idempotency check - only skip if EXACTLY the same JNDIs are already configured
        boolean hasExistingDsBlock = content.contains(DS_BLOCK_BEGIN) && content.contains(DS_BLOCK_END);
        if (hasExistingDsBlock) {
            // Verify that properties for all requested JNDIs exist within the block
            boolean allJndisConfigured = true;
            for (DataSourceInfo ds : moduleDataSources) {
                if (ds.name != null && !content.contains("# Original JNDI: " + ds.name)) {
                    allJndisConfigured = false;
                    break;
                }
            }
            if (allJndisConfigured && content.contains("spring.datasource.url=")) {
                return propertiesFile; // Already fully migrated with correct JNDIs
            }
            // Otherwise: replace the existing block (below)
        }

        // Remove redundant TODO blocks only for this module's datasources
        content = removeRedundantTodoBlock(content, moduleJndiNames);

        // Codex P2.1f-4: Build the new datasource block with BEGIN/END markers
        StringBuilder dsBlock = new StringBuilder();
        dsBlock.append(DS_BLOCK_BEGIN).append("\n");
        dsBlock.append("# ===========================================\n");
        dsBlock.append("# Spring DataSource Configuration\n");
        dsBlock.append(MIGRATION_MARKER).append("\n");
        dsBlock.append("# ===========================================\n");

        for (DataSourceInfo ds : moduleDataSources) {
            if (ds.name != null) {
                dsBlock.append("# Original JNDI: ").append(ds.name).append("\n");
            }
            if (ds.url != null) {
                dsBlock.append("spring.datasource.url=").append(ds.url).append("\n");
            }
            if (ds.user != null) {
                dsBlock.append("spring.datasource.username=").append(ds.user).append("\n");
            }
            if (ds.password != null) {
                dsBlock.append("spring.datasource.password=").append(ds.password).append("\n");
            }
            // Codex 2.7: Smart driver-class-name handling
            if (ds.className != null && isDefinitelyDriverClass(ds.className)) {
                dsBlock.append("spring.datasource.driver-class-name=").append(ds.className).append("\n");
            } else if (ds.className != null) {
                dsBlock.append("# NOTE: className '").append(ds.className)
                       .append("' may be a DataSource, not a Driver. Spring Boot auto-detects from URL.\n");
            }

            // DS-001: Hikari Connection Pool Properties
            // These are mapped from @DataSourceDefinition pool attributes to Spring Boot Hikari properties
            if (ds.maxPoolSize != null) {
                dsBlock.append("spring.datasource.hikari.maximum-pool-size=").append(ds.maxPoolSize).append("\n");
            }
            if (ds.minPoolSize != null) {
                dsBlock.append("spring.datasource.hikari.minimum-idle=").append(ds.minPoolSize).append("\n");
            }
            if (ds.maxIdleTime != null) {
                // maxIdleTime is in seconds, Hikari idle-timeout is in milliseconds
                dsBlock.append("spring.datasource.hikari.idle-timeout=").append(ds.maxIdleTime * 1000L).append("\n");
            }
            if (ds.loginTimeout != null) {
                // loginTimeout is in seconds, Hikari connection-timeout is in milliseconds
                dsBlock.append("spring.datasource.hikari.connection-timeout=").append(ds.loginTimeout * 1000L).append("\n");
            }
            if (ds.initialPoolSize != null) {
                // Note: Hikari doesn't have initialPoolSize, but minimum-idle serves a similar purpose
                // We document this as a comment for transparency
                dsBlock.append("# initialPoolSize=").append(ds.initialPoolSize)
                       .append(" (Hikari uses minimum-idle instead)\n");
            }
        }
        dsBlock.append(DS_BLOCK_END).append("\n");

        // Codex P2.1g-4: Remove ALL existing DS blocks first (enforce exactly one block)
        // This handles malformed markers, multiple blocks, and legacy format
        String cleanedContent = removeAllExistingDsBlocks(content);

        StringBuilder newContent;
        // After cleanup, simply append the new block
        newContent = new StringBuilder(cleanedContent);
        ensureEndsWithNewlines(newContent);
        newContent.append(dsBlock);

        // Codex P2.1g-4: Legacy handling removed - removeAllExistingDsBlocks() handles all cases

        // Codex 2.3: Use PropertiesParser to create proper Properties.File AST
        return PropertiesParser.builder().build()
            .parse(newContent.toString())
            .findFirst()
            .map(parsed -> (SourceFile) ((Properties.File) parsed)
                .withId(propertiesFile.getId())
                .withSourcePath(propertiesFile.getSourcePath()))
            .orElse(propertiesFile);
    }

    /** Helper to ensure content ends with double newline for clean separation */
    private void ensureEndsWithNewlines(StringBuilder sb) {
        if (!sb.toString().endsWith("\n\n")) {
            if (!sb.toString().endsWith("\n")) {
                sb.append("\n");
            }
            sb.append("\n");
        }
    }

    /**
     * Codex P2.1h-4: Remove ALL existing DS blocks from content.
     * This ensures exactly one DS block exists after we append the new one.
     * Handles: BEGIN/END blocks, legacy format, malformed markers (BEGIN without END), multiple blocks.
     */
    private String removeAllExistingDsBlocks(String content) {
        StringBuilder result = new StringBuilder(content);

        // First: Remove all BEGIN/END marker blocks (can be multiple - loop until none remain)
        while (true) {
            String current = result.toString();
            int beginIdx = current.indexOf(DS_BLOCK_BEGIN);
            if (beginIdx < 0) break;

            int endIdx = current.indexOf(DS_BLOCK_END, beginIdx);
            if (endIdx < 0) {
                // Codex P2.1h-4: BEGIN without END - remove entire block content
                // Delete all lines from BEGIN until first non-DS line (not spring.datasource.*, not # comment about DS)
                int blockStart = beginIdx;
                // Find line start
                while (blockStart > 0 && current.charAt(blockStart - 1) != '\n') {
                    blockStart--;
                }
                // Find end of block: scan until we hit a non-DS line or EOF
                int blockEnd = beginIdx;
                String[] lines = current.substring(beginIdx).split("\n", -1);
                int linesInBlock = 0;
                for (String line : lines) {
                    linesInBlock++;
                    blockEnd += line.length() + 1; // +1 for newline
                    // Terminate block at non-DS content (max 1 trailing blank line)
                    if (linesInBlock > 1) { // Skip the BEGIN marker line itself
                        String trimmed = line.trim();
                        if (trimmed.isEmpty()) {
                            // Allow one blank line, then stop
                            break;
                        }
                        if (!trimmed.startsWith("#") && !trimmed.startsWith("spring.datasource.")) {
                            // Non-comment, non-DS property -> end of block
                            blockEnd -= line.length() + 1; // Don't include this line
                            break;
                        }
                    }
                }
                if (blockEnd > current.length()) blockEnd = current.length();

                result = new StringBuilder();
                result.append(current.substring(0, blockStart));
                if (blockEnd < current.length()) {
                    result.append(current.substring(blockEnd));
                }
            } else {
                // Normal case: remove BEGIN to END (inclusive of END line)
                int blockStart = beginIdx;
                // Find line start
                while (blockStart > 0 && current.charAt(blockStart - 1) != '\n') {
                    blockStart--;
                }
                int endOfEndMarker = current.indexOf('\n', endIdx);
                if (endOfEndMarker < 0) endOfEndMarker = current.length();
                else endOfEndMarker++; // Include the newline

                result = new StringBuilder();
                result.append(current.substring(0, blockStart));
                if (endOfEndMarker < current.length()) {
                    result.append(current.substring(endOfEndMarker));
                }
            }
        }

        // Second: Remove ALL legacy format blocks (# Migrated from @DataSourceDefinition) - LOOP for multiples
        while (true) {
            String current = result.toString();
            int markerIdx = current.indexOf(MIGRATION_MARKER);
            if (markerIdx < 0) break;

            // Find start of block (look backwards for "# ===")
            int blockStart = current.lastIndexOf("# ===", markerIdx);
            if (blockStart < 0) blockStart = markerIdx;
            // Find line start
            while (blockStart > 0 && current.charAt(blockStart - 1) != '\n') {
                blockStart--;
            }
            // Find end of block (until non-DS line or EOF)
            int blockEnd = markerIdx;
            String[] lines = current.substring(markerIdx).split("\n", -1);
            int linesProcessed = 0;
            for (String line : lines) {
                linesProcessed++;
                blockEnd += line.length() + 1;
                if (linesProcessed > 1) { // Skip the marker line itself
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() && linesProcessed > 4) break; // Allow some blank lines in block
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.startsWith("spring.datasource.")) {
                        blockEnd -= line.length() + 1; // Don't include this line
                        break;
                    }
                }
            }
            if (blockEnd > current.length()) blockEnd = current.length();

            result = new StringBuilder();
            result.append(current.substring(0, blockStart));
            if (blockEnd < current.length()) {
                String remaining = current.substring(blockEnd);
                // Skip leading blank line if present
                if (remaining.startsWith("\n")) {
                    remaining = remaining.substring(1);
                }
                result.append(remaining);
            }
        }

        // Third: Remove ALL orphaned END markers (END without BEGIN) - LOOP for multiples
        while (true) {
            String current = result.toString();
            if (!current.contains(DS_BLOCK_END) || current.contains(DS_BLOCK_BEGIN)) break;

            int endIdx = current.indexOf(DS_BLOCK_END);
            int lineStart = current.lastIndexOf('\n', endIdx);
            if (lineStart < 0) lineStart = 0;
            else lineStart++; // Skip the newline itself
            int lineEnd = current.indexOf('\n', endIdx);
            if (lineEnd < 0) lineEnd = current.length();
            else lineEnd++; // Include the newline

            result = new StringBuilder();
            result.append(current.substring(0, lineStart));
            if (lineEnd < current.length()) {
                result.append(current.substring(lineEnd));
            }
        }

        // Clean up multiple consecutive blank lines
        return result.toString().replaceAll("\n{3,}", "\n\n");
    }

    /**
     * Removes redundant TODO blocks from MigratePersistenceXmlToProperties.
     * When @DataSourceDefinition provides actual values, the generic TODO comments
     * about datasource configuration become obsolete and should be cleaned up.
     *
     * Uses BEGIN/END markers for reliable block detection:
     * - # BEGIN DATASOURCE TODO (jndi=java:app/jdbc/MyDS)
     * - ... block content ...
     * - # END DATASOURCE TODO
     *
     * Also supports legacy format (without markers) for backwards compatibility.
     *
     * Only removes TODO blocks whose JNDI name matches one of the migrated
     * @DataSourceDefinition annotations. This ensures TODO blocks for other
     * datasources (not covered by @DataSourceDefinition) are preserved.
     *
     * @param content The properties file content
     * @param migratedJndiNames Set of JNDI names from migrated @DataSourceDefinition annotations
     * @return The cleaned content with matching TODO blocks removed
     */
    private String removeRedundantTodoBlock(String content, Set<String> migratedJndiNames) {
        StringBuilder result = new StringBuilder();
        String[] lines = content.split("\n", -1);
        boolean inMarkedBlock = false;
        boolean inLegacyBlock = false;
        boolean shouldRemoveCurrentBlock = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];

            // NEW FORMAT: Detect BEGIN DATASOURCE TODO marker with embedded JNDI
            if (line.startsWith("# BEGIN DATASOURCE TODO (jndi=")) {
                inMarkedBlock = true;
                // Extract JNDI from marker: # BEGIN DATASOURCE TODO (jndi=java:app/jdbc/DB)
                int jndiStart = line.indexOf("(jndi=") + 6;
                int jndiEnd = line.lastIndexOf(")");
                if (jndiStart > 6 && jndiEnd > jndiStart) {
                    String jndiInMarker = line.substring(jndiStart, jndiEnd);
                    shouldRemoveCurrentBlock = migratedJndiNames.contains(jndiInMarker);
                }
                if (shouldRemoveCurrentBlock) {
                    continue; // Skip BEGIN marker line
                }
            }

            // NEW FORMAT: Detect END DATASOURCE TODO marker
            if (line.equals("# END DATASOURCE TODO")) {
                if (inMarkedBlock && shouldRemoveCurrentBlock) {
                    inMarkedBlock = false;
                    shouldRemoveCurrentBlock = false;
                    continue; // Skip END marker line
                }
                inMarkedBlock = false;
            }

            // Handle content inside marked block
            if (inMarkedBlock && shouldRemoveCurrentBlock) {
                continue; // Skip all lines inside block we're removing
            }

            // LEGACY FORMAT: Detect start of old-style datasource TODO block (without markers)
            if (!inMarkedBlock && line.contains("# TODO: DATASOURCE CONFIGURATION REQUIRED")) {
                inLegacyBlock = true;
                shouldRemoveCurrentBlock = false;

                // Look ahead to find the JNDI name in this block
                for (int j = i + 1; j < lines.length; j++) {
                    String lookahead = lines[j];
                    if (lookahead.startsWith("# Original JNDI:")) {
                        String jndiInBlock = lookahead.substring("# Original JNDI:".length()).trim();
                        shouldRemoveCurrentBlock = migratedJndiNames.contains(jndiInBlock);
                        break;
                    }
                    // Stop at END marker, empty line, or non-comment line
                    if (lookahead.equals("# END DATASOURCE TODO") ||
                        lookahead.trim().isEmpty() ||
                        (!lookahead.startsWith("#") && !lookahead.trim().isEmpty())) {
                        break;
                    }
                }

                if (shouldRemoveCurrentBlock) {
                    continue; // Skip this TODO header line
                }
            }

            // LEGACY FORMAT: Handle lines inside a legacy TODO block
            if (inLegacyBlock) {
                if (line.startsWith("# Original JNDI:") ||
                    line.startsWith("# Configure one of the following:") ||
                    line.startsWith("# spring.datasource.url=") ||
                    line.startsWith("# spring.datasource.username=") ||
                    line.startsWith("# spring.datasource.password=") ||
                    line.startsWith("# spring.datasource.driver-class-name=")) {
                    if (shouldRemoveCurrentBlock) {
                        continue; // Skip this line from the block we're removing
                    }
                } else {
                    // End of legacy TODO block
                    inLegacyBlock = false;
                    if (shouldRemoveCurrentBlock && line.trim().isEmpty()) {
                        shouldRemoveCurrentBlock = false;
                        continue; // Skip trailing blank line after removed block
                    }
                    shouldRemoveCurrentBlock = false;
                }
            }

            // Keep this line
            result.append(line);
            if (i < lines.length - 1) {
                result.append("\n");
            }
        }

        return result.toString();
    }

    private static class DataSourceDefinitionRemover extends JavaIsoVisitor<ExecutionContext> {
        private final Accumulator acc;
        private final boolean canWriteProperties;
        // Codex P2.1g-2: JNDIs where @NeedsReview can be safely removed (already configured, not optimistic)
        private final Set<String> safeToRemoveNeedsReviewForJndi;

        DataSourceDefinitionRemover(Accumulator acc, boolean canWriteProperties, Set<String> safeToRemoveNeedsReviewForJndi) {
            this.acc = acc;
            this.canWriteProperties = canWriteProperties;
            this.safeToRemoveNeedsReviewForJndi = safeToRemoveNeedsReviewForJndi;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
            J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);

            // DS-001: Extract class-level constants for constant resolution in annotation processing
            // DS-001 Medium Fix: Also include constants from implemented interfaces in the same file
            List<J.ClassDeclaration> sameFileInterfaces = collectInterfacesFromCompilationUnit(cu);
            Map<String, Object> classConstants = ConstantResolver.extractClassConstants(cd, sameFileInterfaces);

            // Check if @NeedsReview already exists for DataSource (idempotency)
            // to avoid adding duplicate annotations in successive cycles
            Set<String> existingNeedsReviewJndis = new HashSet<>();
            // Codex P2.1h Fix 2: Track non-literal name @NeedsReview by stableKey (not boolean)
            // This allows proper dedup when a class has multiple non-literal @DataSourceDefinition
            Set<String> existingNonLiteralNameKeys = new HashSet<>();
            // Codex P2.1h Round 6: Count legacy @NeedsReview (without stableKey) for this class
            int legacyNonLiteralReviewCount = 0;
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if (isNeedsReviewForDataSourceDefinition(ann)) {
                    String jndi = extractJndiFromNeedsReview(ann);
                    if (jndi != null) {
                        existingNeedsReviewJndis.add(jndi);
                    }
                    // Codex P2.1h Fix 2: Extract stableKey from non-literal name @NeedsReview (not just boolean)
                    String nonLiteralKey = extractStableKeyFromNonLiteralNameNeedsReview(ann);
                    if (nonLiteralKey != null) {
                        existingNonLiteralNameKeys.add(nonLiteralKey);
                        // Codex P2.1h Round 6: Count legacy @NeedsReview (without stableKey)
                        if ("_legacy_non_literal_name".equals(nonLiteralKey)) {
                            legacyNonLiteralReviewCount++;
                        }
                    }
                }
            }

            // Codex P2.1h Round 6: Count non-literal @DataSourceDefinition for this class
            // Note: Reusing 'cu' from method scope (DS-001 Medium Fix cleanup)
            // Codex P2.1h Round 20: Use central normalization method
            String normalizedSourcePathForCount = normalizeSourcePath(cu.getSourcePath().toString());
            String classKeyPrefixForCount = normalizedSourcePathForCount + "#" + classDecl.getSimpleName() + "#ds=";
            int nonLiteralDsCountInClass = 0;
            for (DataSourceInfo ds : acc.nonLiteralNameDataSources) {
                if (ds.stableKey != null && ds.stableKey.startsWith(classKeyPrefixForCount)) {
                    nonLiteralDsCountInClass++;
                }
            }

            // Codex P2.1h Round 7: Determine if we're in mismatch mode (keeping all legacy)
            // In mismatch mode, we should NOT create new @NeedsReview to avoid duplicates
            boolean keepAllLegacyNonLiteral = nonLiteralDsCountInClass < legacyNonLiteralReviewCount
                || (legacyNonLiteralReviewCount > 0 && nonLiteralDsCountInClass == 0);

            // Codex P2.1h Round 11: Collect all known non-literal DS keys for this class
            // Used for: (1) exact-match removal of stableKey-@NeedsReview, (2) safe dedup filtering
            Set<String> knownNonLiteralDsKeysForClass = new HashSet<>();
            for (DataSourceInfo ds : acc.nonLiteralNameDataSources) {
                if (ds.stableKey != null && ds.stableKey.startsWith(classKeyPrefixForCount)) {
                    knownNonLiteralDsKeysForClass.add(ds.stableKey);
                }
            }

            // Codex P2.1h Round 11: In match mode, filter out ONLY the known non-literal DS keys
            // This ensures recreation is only for keys where a matching DS definitely exists
            if (!keepAllLegacyNonLiteral) {
                existingNonLiteralNameKeys.removeAll(knownNonLiteralDsKeysForClass);
            }

            // Process @DataSourceDefinition annotations
            List<J.Annotation> remainingAnnotations = new ArrayList<>();
            List<J.Annotation> needsReviewToAdd = new ArrayList<>();
            boolean changed = false;
            // Codex P2.1h Round 67: Moved earlier for use in migration loop
            Set<String> alreadyCreatedKeys = new HashSet<>();

            // Determine prefix for new annotations (use class prefix or first annotation prefix)
            Space needsReviewPrefix = cd.getLeadingAnnotations().isEmpty()
                ? cd.getPrefix()
                : cd.getLeadingAnnotations().get(0).getPrefix();

            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if (isDataSourceDefinition(ann)) {
                    // DS-001: Use classConstants for constant resolution
                    String jndi = extractJndiFromAnnotation(ann, classConstants);
                    if (canWriteProperties && jndi != null && acc.migratedJndiNames.contains(jndi)) {
                        // This annotation was successfully migrated - remove it
                        // ONLY if we can write properties (application.properties exists)
                        changed = true;
                        maybeRemoveImport(DATASOURCE_DEFINITION_FQN);
                        maybeRemoveImport(JAVAX_DATASOURCE_DEFINITION_FQN);
                    } else if (jndi != null && acc.multiDsJndiNames.contains(jndi)) {
                        // Multi-DS case: Cannot auto-migrate because >1 datasource
                        remainingAnnotations.add(ann);
                        if (!existingNeedsReviewJndis.contains(jndi)) {
                            // Codex P2.1f-1: Use correct per-module count, not global set size
                            int totalCount = acc.multiDsCountByJndi.getOrDefault(jndi, acc.multiDsJndiNames.size());
                            J.Annotation needsReview = createNeedsReviewForMultiDataSource(jndi, totalCount, needsReviewPrefix);
                            needsReviewToAdd.add(needsReview);
                        }
                    } else if (jndi != null && acc.nonLiteralAttributesByJndi.containsKey(jndi)) {
                        // Codex 2.5: Has non-literal values in url/user/password - keep annotation and add @NeedsReview
                        remainingAnnotations.add(ann);
                        if (!existingNeedsReviewJndis.contains(jndi)) {
                            Set<String> nonLiteralAttrs = acc.nonLiteralAttributesByJndi.get(jndi);
                            J.Annotation needsReview = createNeedsReviewForNonLiteralAttributes(jndi, nonLiteralAttrs, needsReviewPrefix);
                            needsReviewToAdd.add(needsReview);
                        }
                    } else if (jndi != null && acc.unsupportedAttributesByJndi.containsKey(jndi)) {
                        // Has unsupported attributes - keep annotation and add @NeedsReview
                        remainingAnnotations.add(ann);
                        if (!existingNeedsReviewJndis.contains(jndi)) {
                            Set<String> unsupportedAttrs = acc.unsupportedAttributesByJndi.get(jndi);
                            J.Annotation needsReview = createNeedsReviewForUnsupportedAttributes(jndi, unsupportedAttrs, needsReviewPrefix);
                            needsReviewToAdd.add(needsReview);
                        }
                    } else if (jndi != null && acc.nonMigratableJndiNames.contains(jndi)) {
                        // This annotation could NOT be migrated - keep it and add @NeedsReview
                        remainingAnnotations.add(ann);
                        if (!existingNeedsReviewJndis.contains(jndi)) {
                            J.Annotation needsReview = createNeedsReviewForNonMigratableDataSource(jndi, needsReviewPrefix);
                            needsReviewToAdd.add(needsReview);
                        }
                    } else if (jndi == null) {
                        // Codex P2.1h-3: Handle non-literal name with indexed stable key matching
                        // Uses sourcePath + className + dsIdx for robust matching
                        // Note: 'cu' is already defined at method scope (line 1667)
                        // Codex P2.1h Round 20: Use central normalization method
                        String currentSourcePath = normalizeSourcePath(cu.getSourcePath().toString());
                        String currentClassName = classDecl.getSimpleName();
                        // Note: dsIndex is computed below based on annotation position
                        // Codex P2.1h Fix 3: Use cd (post-visit) not classDecl (pre-visit)
                        int dsIndex = computeAnnotationIndex(cd, ann);
                        String currentKey = currentSourcePath + "#" + currentClassName + "#ds=" + dsIndex;

                        DataSourceInfo matchingDs = null;
                        for (DataSourceInfo ds : acc.nonLiteralNameDataSources) {
                            if (ds.stableKey != null && ds.stableKey.equals(currentKey)) {
                                matchingDs = ds;
                                break;
                            }
                        }
                        if (matchingDs != null) {
                            remainingAnnotations.add(ann);
                            // Codex P2.1h Round 7: Skip new @NeedsReview if keeping legacy (to avoid duplicates)
                            // Only create new stableKey-based @NeedsReview if we're removing legacy (match mode)
                            if (!keepAllLegacyNonLiteral && !existingNonLiteralNameKeys.contains(currentKey)) {
                                // Check if Multi-DS (marked with internal flag)
                                if (matchingDs.nonLiteralAttributes.contains("_multiDs")) {
                                    int totalCount = acc.totalDsCountPerModule.getOrDefault(
                                        matchingDs.moduleRoot != null ? matchingDs.moduleRoot : "", 2);
                                    J.Annotation needsReview = createNeedsReviewForNonLiteralNameMultiDs(
                                        matchingDs.originalAnnotationCode, matchingDs.nonLiteralAttributes, currentKey, totalCount, needsReviewPrefix);
                                    needsReviewToAdd.add(needsReview);
                                } else {
                                    J.Annotation needsReview = createNeedsReviewForNonLiteralName(
                                        matchingDs.originalAnnotationCode, matchingDs.nonLiteralAttributes, currentKey, needsReviewPrefix);
                                    needsReviewToAdd.add(needsReview);
                                }
                            }
                        } else {
                            // Unknown - keep as-is
                            remainingAnnotations.add(ann);
                        }
                    } else {
                        // Known JNDI but not in any tracked category - keep as-is
                        remainingAnnotations.add(ann);
                    }
                } else {
                    // Codex P2.1h Round 6: Count-based legacy removal
                    // Only remove legacy @NeedsReview if the number of non-literal @DataSourceDefinition
                    // in this class is >= the number of legacy @NeedsReview (all can be replaced)
                    String legacyKey = extractStableKeyFromNonLiteralNameNeedsReview(ann);
                    if ("_legacy_non_literal_name".equals(legacyKey)) {
                        if (nonLiteralDsCountInClass >= legacyNonLiteralReviewCount && nonLiteralDsCountInClass > 0) {
                            // DS count >= legacy count: all legacy can be replaced
                            // Skip this legacy @NeedsReview (will be replaced with stableKey version)
                            changed = true;
                        } else {
                            // DS count < legacy count OR no DS: can't determine which to remove
                            // Keep all legacy @NeedsReview to avoid losing review tasks
                            remainingAnnotations.add(ann);
                        }
                    } else if (legacyKey != null && isValidStableKeyFormat(legacyKey) && nonLiteralDsCountInClass == 0) {
                        // Codex P2.1h Round 15: Orphan cleanup FIRST - when no non-literal DS exists
                        // Remove ALL valid stableKeys (both this class and foreign) since they're all orphaned
                        changed = true;
                    } else if (legacyKey != null && legacyKey.startsWith(classKeyPrefixForCount)) {
                        // Codex P2.1h Round 13: StableKey-based @NeedsReview for THIS class (Prefix-Match)
                        // Use deterministic prefix-match instead of string heuristic
                        if (keepAllLegacyNonLiteral) {
                            // Mismatch mode: remove ALL stableKey-based @NeedsReview (cleanup from previous runs)
                            // This prevents duplicates even when knownNonLiteralDsKeysForClass is empty
                            changed = true;
                        } else {
                            // Match mode: remove ALL stableKey-@NeedsReview for this class (not just known keys)
                            // This ensures stale keys from index drift are also cleaned up
                            // Fresh stableKey @NeedsReview will be created in fallback loop below
                            changed = true;
                        }
                    } else if (legacyKey != null && isValidStableKeyFormat(legacyKey)) {
                        // Codex P2.1h Round 14: Foreign stableKey cleanup (wrong prefix from class rename)
                        // At this point: valid stableKey that doesn't start with classKeyPrefix = foreign
                        if (keepAllLegacyNonLiteral) {
                            // Mismatch mode: remove foreign stableKeys (cleanup from previous runs)
                            changed = true;
                        } else {
                            // Match mode: also remove foreign stableKeys to prevent duplicates
                            changed = true;
                        }
                    } else if (legacyKey != null && legacyKey.startsWith("_config_problematic_root:")) {
                        // Codex P2.1h Round 64: Handle new format (Base64 encoded)
                        // Only remove if we can decode AND the root is no longer problematic
                        String encodedRoot = legacyKey.substring("_config_problematic_root:".length());
                        String decodedRoot = decodeStableKeyRoot(encodedRoot);
                        if (decodedRoot == null) {
                            // Cannot decode - keep to avoid data loss
                            remainingAnnotations.add(ann);
                        } else if (acc.problematicCustomRoots.contains(decodedRoot)) {
                            // Root still problematic - keep annotation
                            remainingAnnotations.add(ann);
                        } else {
                            // Root no longer problematic - safe to remove
                            changed = true;
                        }
                    } else if (legacyKey != null && legacyKey.startsWith("_config_problematic_root_")) {
                        // Codex P2.1h Round 65: Migrate old format (hashCode based) to new Base64 format
                        // Try to extract the problematic root from originalCode attribute
                        String originalCode = extractOriginalCodeFromAnnotation(ann);
                        String problematicRoot = null;
                        if (originalCode != null && originalCode.startsWith("customSourceRoots: ")) {
                            problematicRoot = originalCode.substring("customSourceRoots: ".length()).trim();
                        }

                        if (problematicRoot != null && !problematicRoot.isEmpty()) {
                            // Successfully extracted root - check if still problematic
                            if (acc.problematicCustomRoots.contains(problematicRoot)) {
                                // Root still problematic - migrate to new Base64 format
                                String standardSuffix = detectModulePrefixPattern(problematicRoot);
                                if (standardSuffix != null) {
                                    // Codex P2.1h Round 67/68: Track migrated key to prevent duplicates in loop below
                                    // Round 68: Also check if annotation already exists in class (from previous run)
                                    String migratedStableKey = buildProblematicRootStableKey(problematicRoot);
                                    if (!hasExistingAnnotationWithStableKey(cd, migratedStableKey)) {
                                        J.Annotation migratedAnn = createNeedsReviewForProblematicCustomRoot(
                                            problematicRoot, standardSuffix, ann.getPrefix());
                                        needsReviewToAdd.add(migratedAnn);
                                    }
                                    alreadyCreatedKeys.add(migratedStableKey);  // Prevent duplicate in problematicCustomRoots loop
                                    // Remove old annotation (will be replaced by migrated one, or already exists)
                                    changed = true;
                                } else {
                                    // Codex P2.1h Round 66: Cannot determine standard suffix
                                    // Keep old annotation to avoid data loss - requires manual review
                                    System.err.println("[WARNING] MigrateDataSourceDefinition: Cannot determine standard suffix for '" +
                                        problematicRoot + "' - keeping old annotation.");
                                    remainingAnnotations.add(ann);
                                }
                            } else {
                                // Root no longer problematic - safe to remove
                                changed = true;
                            }
                        } else {
                            // Cannot extract root from originalCode - keep with MANUAL_MIGRATION marker
                            // This preserves the review task for manual handling
                            System.err.println("[WARNING] MigrateDataSourceDefinition: Cannot migrate old format key '" +
                                legacyKey + "' - keeping annotation. Manual migration required.");
                            remainingAnnotations.add(ann);
                        }
                    } else {
                        remainingAnnotations.add(ann);
                    }
                }
            }

            // Codex P2.1h Round 60/67: alreadyCreatedKeys was moved earlier (line ~1297)
            // for use in migration loop - it tracks stableKeys for deduplication

            // Codex P2.1h Round 12: In match mode, guarantee @NeedsReview creation for ALL known non-literal DS
            // This handles the case where annotation index changed (matchingDs was null) but we removed stableKey reviews
            if (!keepAllLegacyNonLiteral) {
                for (J.Annotation createdAnn : needsReviewToAdd) {
                    String key = extractStableKeyFromNonLiteralNameNeedsReview(createdAnn);
                    if (key != null && !key.equals("_legacy_non_literal_name")) {
                        alreadyCreatedKeys.add(key);
                    }
                }
                // Create @NeedsReview for any DS that wasn't handled in the annotation loop
                for (DataSourceInfo ds : acc.nonLiteralNameDataSources) {
                    if (ds.stableKey != null && ds.stableKey.startsWith(classKeyPrefixForCount)) {
                        if (!alreadyCreatedKeys.contains(ds.stableKey) && !existingNonLiteralNameKeys.contains(ds.stableKey)) {
                            // This DS wasn't created in the loop - create it now
                            if (ds.nonLiteralAttributes.contains("_multiDs")) {
                                int totalCount = acc.totalDsCountPerModule.getOrDefault(
                                    ds.moduleRoot != null ? ds.moduleRoot : "", 2);
                                J.Annotation needsReview = createNeedsReviewForNonLiteralNameMultiDs(
                                    ds.originalAnnotationCode, ds.nonLiteralAttributes, ds.stableKey, totalCount, needsReviewPrefix);
                                needsReviewToAdd.add(needsReview);
                            } else {
                                J.Annotation needsReview = createNeedsReviewForNonLiteralName(
                                    ds.originalAnnotationCode, ds.nonLiteralAttributes, ds.stableKey, needsReviewPrefix);
                                needsReviewToAdd.add(needsReview);
                            }
                            alreadyCreatedKeys.add(ds.stableKey);
                        }
                    }
                }
            }

            // Codex P2.1h Round 59: Emit @NeedsReview for problematic custom roots on DETERMINISTIC per-module class
            // Uses per-module lowest sourcePath (tracked in scanner) for reproducible diffs
            J.CompilationUnit cuForPath = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
            String currentSourcePath = cuForPath.getSourcePath().toString();
            String currentModulePrefix = extractModulePrefix(currentSourcePath);
            String targetForThisModule = acc.problematicRootsTargetByModule.get(currentModulePrefix);
            boolean isTargetClass = currentSourcePath.equals(targetForThisModule);

            if (!acc.problematicCustomRoots.isEmpty() && isTargetClass) {
                // Round 59/60: Only emit warnings for problematic roots in THIS module
                // Round 60: Deduplicate by stableKey to avoid repeat warnings on subsequent runs
                for (String problematicRoot : acc.problematicCustomRoots) {
                    String rootModulePrefix = extractModulePrefix(problematicRoot);
                    // Only process if this problematic root belongs to the current module
                    if (rootModulePrefix.equals(currentModulePrefix)) {
                        String standardSuffix = detectModulePrefixPattern(problematicRoot);
                        if (standardSuffix != null) {
                            // Codex P2.1h Round 62: Use shared method for consistent stableKey
                            String warningStableKey = buildProblematicRootStableKey(problematicRoot);
                            if (!alreadyCreatedKeys.contains(warningStableKey) &&
                                !hasExistingAnnotationWithStableKey(cd, warningStableKey)) {
                                J.Annotation warning = createNeedsReviewForProblematicCustomRoot(
                                    problematicRoot, standardSuffix, needsReviewPrefix);
                                needsReviewToAdd.add(warning);
                                alreadyCreatedKeys.add(warningStableKey);
                            }
                        }
                    }
                }
            }

            if (changed || !needsReviewToAdd.isEmpty()) {
                // Add @NeedsReview annotations before the class
                List<J.Annotation> finalAnnotations = new ArrayList<>(needsReviewToAdd);
                finalAnnotations.addAll(remainingAnnotations);
                cd = cd.withLeadingAnnotations(finalAnnotations);

                // Add import for @NeedsReview if we added any
                if (!needsReviewToAdd.isEmpty()) {
                    maybeAddImport(NEEDS_REVIEW_FQN);
                }
            }

            return cd;
        }

        private boolean isNeedsReviewForDataSourceDefinition(J.Annotation ann) {
            if (!TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) &&
                !"NeedsReview".equals(ann.getSimpleName())) {
                return false;
            }
            // Check if originalCode mentions @DataSourceDefinition
            if (ann.getArguments() != null) {
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                            if ("originalCode".equals(attrName)) {
                                Expression value = assignment.getAssignment();
                                if (value instanceof J.Literal) {
                                    Object literalValue = ((J.Literal) value).getValue();
                                    return literalValue != null &&
                                           literalValue.toString().contains("@DataSourceDefinition");
                                }
                            }
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Codex P2.1h Round 60: Check if class already has @NeedsReview with given stableKey.
         * Used to deduplicate warnings across runs.
         */
        private boolean hasExistingAnnotationWithStableKey(J.ClassDeclaration cd, String stableKey) {
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) ||
                    "NeedsReview".equals(ann.getSimpleName())) {
                    String existingKey = extractStableKeyFromAnnotation(ann);
                    if (stableKey.equals(existingKey)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Codex P2.1h Round 60: Extract stableKey value from @NeedsReview annotation.
         */
        private String extractStableKeyFromAnnotation(J.Annotation ann) {
            if (ann.getArguments() != null) {
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                            if ("stableKey".equals(attrName)) {
                                Expression value = assignment.getAssignment();
                                if (value instanceof J.Literal) {
                                    Object literalValue = ((J.Literal) value).getValue();
                                    return literalValue != null ? literalValue.toString() : null;
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }

        /**
         * Codex P2.1h Round 15: Validate if a string is a valid stableKey format.
         * StableKey format: sourcePath#className#ds=N (where N is a digit)
         * Uses strict structural validation to avoid false positives.
         */
        private boolean isValidStableKeyFormat(String key) {
            if (key == null) {
                return false;
            }
            // Valid format: sourcePath#className#ds=N
            // sourcePath must end with .java (real source file)
            int lastHashIndex = key.lastIndexOf("#ds=");
            if (lastHashIndex <= 0) {
                return false;
            }
            // Check that there's something after #ds=
            String indexPart = key.substring(lastHashIndex + 4);
            if (indexPart.isEmpty()) {
                return false;
            }
            // Validate that the index part is all digits
            for (char c : indexPart.toCharArray()) {
                if (!Character.isDigit(c)) {
                    return false;
                }
            }
            // Check format: pathPart#classNamePart#ds=N
            String pathAndClass = key.substring(0, lastHashIndex);
            int classNameSeparator = pathAndClass.lastIndexOf('#');
            if (classNameSeparator <= 0) {
                return false;
            }
            // Codex P2.1h Round 15: Strict validation - sourcePath must end with .java
            String sourcePath = pathAndClass.substring(0, classNameSeparator);
            if (!sourcePath.endsWith(".java")) {
                return false;
            }
            // Codex P2.1h Round 17: Additional sourcePath validation
            // - No whitespace (manual text unlikely to have clean paths)
            // - No absolute paths (no leading / or \ or drive letter)
            // - Path segments must be valid Java package names
            if (!isValidSourcePath(sourcePath)) {
                return false;
            }
            // Codex P2.1h Round 16: className must be a valid Java identifier
            // Pattern: starts with letter/underscore/$, followed by letters/digits/underscore/$
            String className = pathAndClass.substring(classNameSeparator + 1);
            return isValidJavaIdentifier(className);
        }

        /**
         * Codex P2.1h Round 17: Validate if a string is a valid Java source path.
         * Valid source paths:
         * - No whitespace (manual text unlikely to have clean paths)
         * - No absolute paths (no leading / or \ or drive letter like C:)
         * - Path segments (directories) must be valid Java package names
         * - Filename must be a valid Java identifier followed by .java
         */
        private boolean isValidSourcePath(String sourcePath) {
            if (sourcePath == null || sourcePath.isEmpty()) {
                return false;
            }
            // Codex P2.1h Round 20: Use central normalization method
            String normalizedPath = normalizeSourcePath(sourcePath);
            // No whitespace allowed
            for (char c : normalizedPath.toCharArray()) {
                if (Character.isWhitespace(c)) {
                    return false;
                }
            }
            // No absolute paths (Unix or Windows)
            if (normalizedPath.startsWith("/")) {
                return false;
            }
            // No Windows drive letters (e.g., C:, D:)
            if (normalizedPath.length() >= 2 && normalizedPath.charAt(1) == ':') {
                return false;
            }
            // Split into segments and validate each
            String[] segments = normalizedPath.split("/");
            if (segments.length == 0) {
                return false;
            }
            // Codex P2.1h Round 24: Find 'java' segment only in valid source root contexts
            // Valid patterns:
            // - src/*/java/...  (Maven: main, test, it, integrationTest, etc.)
            // - build/generated/sources/java/...  (Gradle generated)
            // - target/generated-sources/*/java/...  (Maven generated)
            // NOT valid: docs/java/..., tools/java/..., any arbitrary "java" segment
            // After the 'java' segment: strict Java package validation
            // Before the 'java' segment: allow Maven-style segments (hyphens, dots)
            int javaSourceIdx = -1;
            for (int i = 0; i < segments.length - 1; i++) {
                if ("java".equals(segments[i]) && isValidJavaSourceRoot(segments, i)) {
                    javaSourceIdx = i;
                    break;
                }
            }
            // Codex P2.1h Round 32 Fix 1: Handle paths with or without java segment
            // - Paths with valid java-root: validate package names after java/
            // - Paths without java but matching prefix-root: validate as generated sources
            int packageStartIdx;
            if (javaSourceIdx >= 0) {
                // Standard path with java segment
                packageStartIdx = javaSourceIdx + 1;
            } else {
                // No java segment - check if path matches a prefix root (generated sources)
                packageStartIdx = findPrefixRootPackageStart(segments);
                if (packageStartIdx < 0) {
                    return false;  // No valid source root matched
                }
            }
            // Validate segments based on position relative to package start
            for (int i = 0; i < segments.length - 1; i++) {
                if (segments[i].isEmpty()) {
                    return false;  // No empty segments
                }
                if (i >= packageStartIdx) {
                    // After source root: must be valid Java package names
                    if (!isValidJavaIdentifier(segments[i])) {
                        return false;
                    }
                } else {
                    // Before source root: allow Maven-style segments (hyphens, dots)
                    if (!isValidPathSegment(segments[i])) {
                        return false;
                    }
                }
            }
            // Last segment must be filename.java - extract and validate the name part
            String filename = segments[segments.length - 1];
            if (!filename.endsWith(".java")) {
                return false;  // Already checked earlier, but be defensive
            }
            String fileBaseName = filename.substring(0, filename.length() - 5);
            return isValidJavaIdentifier(fileBaseName);
        }

        /**
         * Codex P2.1h Round 24: Validate if a string is a valid path segment.
         * Path segments before the 'java' source root may contain hyphens
         * (Maven module names like 'my-module', 'generated-sources', 'integrationTest').
         * Allowed characters: letters, digits, hyphen, underscore, dot.
         */
        private boolean isValidPathSegment(String segment) {
            if (segment == null || segment.isEmpty()) {
                return false;
            }
            for (char c : segment.toCharArray()) {
                if (!Character.isLetterOrDigit(c) && c != '-' && c != '_' && c != '.') {
                    return false;
                }
            }
            return true;
        }

        /**
         * Codex P2.1h Round 30: Convention over Configuration.
         * Check if 'java' at given index is a valid source root based on configured roots.
         * Uses DEFAULT_SOURCE_ROOTS or custom roots from .migration-source-roots.yaml.
         *
         * Source roots come in two flavors:
         * 1. Exact roots ending with /java (e.g., src/main/java) - path up to java must match exactly
         * 2. Prefix roots (e.g., target/generated-sources, build/generated) - allow any subtree before java
         */
        private boolean isValidJavaSourceRoot(String[] segments, int javaIdx) {
            if (javaIdx < 2) {
                return false;  // Need at least 2 segments before "java"
            }

            // Build path up to (and including) 'java' segment
            StringBuilder pathBuilder = new StringBuilder();
            for (int i = 0; i <= javaIdx; i++) {
                if (i > 0) {
                    pathBuilder.append("/");
                }
                pathBuilder.append(segments[i]);
            }
            String pathToJava = pathBuilder.toString();  // e.g., "src/main/java" or "module/src/main/java"

            // Also build path up to (but excluding) 'java' for prefix matching
            StringBuilder pathBeforeJavaBuilder = new StringBuilder();
            for (int i = 0; i < javaIdx; i++) {
                if (i > 0) {
                    pathBeforeJavaBuilder.append("/");
                }
                pathBeforeJavaBuilder.append(segments[i]);
            }
            String pathBeforeJava = pathBeforeJavaBuilder.toString();  // e.g., "src/main"

            // Check against effective source roots (defaults or custom from YAML)
            for (String root : acc.getEffectiveSourceRoots()) {
                if (root.endsWith("/java") || root.equals("java")) {
                    // Exact match: path must end with this root
                    // e.g., root="src/main/java" matches "src/main/java" or "module/src/main/java"
                    if (pathToJava.equals(root) || pathToJava.endsWith("/" + root)) {
                        return true;
                    }
                } else {
                    // Codex P2.1h Round 34 Fix 1: Segment-based prefix matching
                    // e.g., root="build/generated" matches "build/generated/sources/annotationProcessor/java"
                    // Also matches "module/build/generated/..." for multi-module projects
                    // Segment-based ensures "target/generated-sourcesX" doesn't match "target/generated-sources"
                    String[] pathSegments = pathBeforeJava.split("/");
                    String[] rootSegments = root.split("/");
                    if (matchesRootSegments(pathSegments, rootSegments) >= 0) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Codex P2.1h Round 32 Fix 1: Find package start index for paths without java segment.
         * For prefix roots like target/generated-sources, returns the index after the root match.
         * Returns -1 if no prefix root matches.
         */
        private int findPrefixRootPackageStart(String[] segments) {
            // Build full path for matching
            StringBuilder pathBuilder = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                if (i > 0) {
                    pathBuilder.append("/");
                }
                pathBuilder.append(segments[i]);
            }
            String fullPath = pathBuilder.toString();

            // Codex P2.1h Round 34 Fix 1: Use segment-based matching
            String[] pathSegments = fullPath.split("/");

            for (String root : acc.getEffectiveSourceRoots()) {
                // Skip roots that end with /java (handled by javaSourceIdx logic)
                if (root.endsWith("/java") || root.equals("java")) {
                    continue;
                }
                // Segment-based matching for prefix roots
                String[] rootSegments = root.split("/");
                int matchIdx = matchesRootSegments(pathSegments, rootSegments);
                if (matchIdx >= 0) {
                    // Return index after the matched root segments
                    return matchIdx + rootSegments.length;
                }
            }
            return -1;
        }

        /**
         * Codex P2.1h Round 35 Fix 2: Segment-based root matching - returns LAST/deepest match.
         * Finds where rootSegments appear as contiguous subsequence in pathSegments.
         * Returns the starting index of the LAST (deepest) match, or -1 if no match.
         * This ensures nested paths like .../target/generated-sources/tmp/target/generated-sources/...
         * pick the match closest to the actual Java files.
         * E.g., ["target", "generated-sourcesX"] does NOT match ["target", "generated-sources"]
         */
        private int matchesRootSegments(String[] pathSegments, String[] rootSegments) {
            if (rootSegments.length == 0 || pathSegments.length < rootSegments.length) {
                return -1;
            }
            // Try to find rootSegments as a contiguous subsequence in pathSegments
            // Start from the END to find the last/deepest match
            outer:
            for (int i = pathSegments.length - rootSegments.length; i >= 0; i--) {
                for (int j = 0; j < rootSegments.length; j++) {
                    if (!pathSegments[i + j].equals(rootSegments[j])) {
                        continue outer;
                    }
                }
                return i;  // Found last match starting at index i
            }
            return -1;  // No match found
        }

        /**
         * Codex P2.1h Round 16: Validate if a string is a valid Java identifier.
         * Java identifiers must start with a letter, underscore, or dollar sign,
         * followed by letters, digits, underscores, or dollar signs.
         */
        private boolean isValidJavaIdentifier(String name) {
            if (name == null || name.isEmpty()) {
                return false;
            }
            // First character must be a Java identifier start character
            char first = name.charAt(0);
            if (!Character.isJavaIdentifierStart(first)) {
                return false;
            }
            // Remaining characters must be valid Java identifier parts
            for (int i = 1; i < name.length(); i++) {
                if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                    return false;
                }
            }
            return true;
        }

        /** Codex P2.1h Round 53/54/55/56: Extract stableKey from @NeedsReview for non-literal 'name' attribute.
         *  Returns the stableKey if found, null otherwise.
         *  Round 56: Remove originalCode gate - parse stableKey without freetext dependencies. */
        private String extractStableKeyFromNonLiteralNameNeedsReview(J.Annotation ann) {
            if (ann.getArguments() == null) {
                return null;
            }
            // Codex P2.1h Round 56: Extract stableKey and suggestedAction without originalCode dependency
            String stableKeyValue = null;  // Round 53+: structured attribute
            boolean isCategoryConfiguration = false;  // Structural: category check only
            String suggestedActionValue = null;  // Legacy: embedded in freetext
            for (Expression arg : ann.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        Expression value = assignment.getAssignment();
                        if ("stableKey".equals(attrName) && value instanceof J.Literal) {
                            stableKeyValue = ((J.Literal) value).getValue() != null
                                ? ((J.Literal) value).getValue().toString() : null;
                        } else if ("category".equals(attrName)) {
                            // Check for CONFIGURATION category (structural check)
                            String categoryStr = value.toString();
                            isCategoryConfiguration = categoryStr.contains("CONFIGURATION");
                        } else if ("suggestedAction".equals(attrName) && value instanceof J.Literal) {
                            Object literalValue = ((J.Literal) value).getValue();
                            suggestedActionValue = literalValue != null ? literalValue.toString() : null;
                        }
                    }
                }
            }
            // Round 55: If stableKey is present and valid, return it (no reason text needed)
            if (stableKeyValue != null && !stableKeyValue.isEmpty() && isValidStableKeyFormat(stableKeyValue)) {
                return normalizeSourcePath(stableKeyValue);
            }
            // Codex P2.1h Round 57: Parse suggestedAction for [stableKey=...] WITH category gate
            // Require category=CONFIGURATION to avoid matching unrelated annotations in other recipes
            if (!isCategoryConfiguration) {
                return null;
            }
            // Now safe to parse suggestedAction since we know it's a CONFIGURATION annotation
            // Codex P2.1h Round 58: Validate extracted stableKey before accepting
            if (suggestedActionValue != null && suggestedActionValue.contains("[stableKey=")) {
                int start = suggestedActionValue.indexOf("[stableKey=") + 11;
                int end = suggestedActionValue.indexOf("]", start);
                if (end > start) {
                    String extractedKey = suggestedActionValue.substring(start, end);
                    // Round 58: Validate format before accepting
                    if (isValidStableKeyFormat(extractedKey)) {
                        return normalizeSourcePath(extractedKey);
                    }
                    // Invalid format - fall through to legacy fallback
                }
            }
            // Fallback for old-style @NeedsReview without stableKey (backward compatibility)
            return "_legacy_non_literal_name";
        }

        /**
         * Codex P2.1h Round 65: Extract originalCode attribute value from @NeedsReview annotation.
         * Used for migrating old format keys where we need to parse the problematic root.
         */
        private String extractOriginalCodeFromAnnotation(J.Annotation ann) {
            if (ann.getArguments() != null) {
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                            if ("originalCode".equals(attrName)) {
                                Expression value = assignment.getAssignment();
                                if (value instanceof J.Literal) {
                                    Object literalValue = ((J.Literal) value).getValue();
                                    return literalValue != null ? literalValue.toString() : null;
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }

        private String extractJndiFromNeedsReview(J.Annotation ann) {
            // Extract JNDI from originalCode: @DataSourceDefinition(name="java:app/jdbc/DB", ...)
            if (ann.getArguments() != null) {
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                            if ("originalCode".equals(attrName)) {
                                Expression value = assignment.getAssignment();
                                if (value instanceof J.Literal) {
                                    Object literalValue = ((J.Literal) value).getValue();
                                    if (literalValue != null) {
                                        String code = literalValue.toString();
                                        // Extract JNDI from pattern: name="java:..."
                                        int start = code.indexOf("name=\"");
                                        if (start >= 0) {
                                            start += 6;
                                            int end = code.indexOf("\"", start);
                                            if (end > start) {
                                                return code.substring(start, end);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }

        /**
         * DS-001: Updated to support constant resolution via classConstants map.
         * Extracts the JNDI name from a @DataSourceDefinition annotation.
         */
        private String extractJndiFromAnnotation(J.Annotation ann, Map<String, Object> classConstants) {
            if (ann.getArguments() == null) return null;
            for (Expression arg : ann.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        if ("name".equals(attrName)) {
                            Expression value = assignment.getAssignment();
                            // DS-001: Use ConstantResolver for both literals and constant references
                            return ConstantResolver.resolveValue(value, classConstants);
                        }
                    }
                }
            }
            return null;
        }

        private J.Annotation createNeedsReviewForNonMigratableDataSource(String jndi, Space prefix) {
            // Create @NeedsReview annotation AST-natively (like MigrateSingletonToService)
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                type,
                null
            );

            String reason = "@DataSourceDefinition could not be auto-migrated (missing 'url' attribute or non-literal values)";
            String originalCode = "@DataSourceDefinition(name=\"" + jndi + "\", ...)";
            String suggestedAction = "Configure spring.datasource.* in application.properties or create a DataSource @Bean with HikariDataSource";

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg("CONFIGURATION"));
            arguments.add(createAssignmentArg("originalCode", originalCode, true));
            arguments.add(createAssignmentArg("suggestedAction", suggestedAction, true));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

        private J.Annotation createNeedsReviewForMultiDataSource(String jndi, int totalCount, Space prefix) {
            // Create @NeedsReview annotation for Multi-DS case (cannot auto-migrate >1 datasource)
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                type,
                null
            );

            String reason = "Multiple @DataSourceDefinitions found (" + totalCount + ") - cannot auto-migrate to spring.datasource.*";
            String originalCode = "@DataSourceDefinition(name=\"" + jndi + "\", ...)";
            String suggestedAction = "Configure multi-datasource setup: Create @Configuration class with multiple DataSource @Beans using @Qualifier, " +
                "use prefixed properties (spring.datasource.primary.*, spring.datasource.secondary.*), and update injection points with @Qualifier";

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg("CONFIGURATION"));
            arguments.add(createAssignmentArg("originalCode", originalCode, true));
            arguments.add(createAssignmentArg("suggestedAction", suggestedAction, true));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

        private J.Annotation createNeedsReviewForUnsupportedAttributes(String jndi, Set<String> unsupportedAttrs, Space prefix) {
            // Create @NeedsReview annotation for DS with unsupported attributes
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                type,
                null
            );

            String attrList = String.join(", ", unsupportedAttrs);
            String reason = "@DataSourceDefinition has unsupported attributes: " + attrList;
            String originalCode = "@DataSourceDefinition(name=\"" + jndi + "\", ...)";
            String suggestedAction = "Manually configure spring.datasource.* or create a DataSource @Bean. " +
                "The attributes [" + attrList + "] need manual mapping to Spring Boot configuration.";

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg("CONFIGURATION"));
            arguments.add(createAssignmentArg("originalCode", originalCode, true));
            arguments.add(createAssignmentArg("suggestedAction", suggestedAction, true));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

        private J.Annotation createNeedsReviewForNonLiteralAttributes(String jndi, Set<String> nonLiteralAttrs, Space prefix) {
            // Codex 2.5: Create @NeedsReview annotation for DS with non-literal values in url/user/password
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                type,
                null
            );

            String attrList = String.join(", ", nonLiteralAttrs);
            String reason = "@DataSourceDefinition has non-literal values in: " + attrList + " (constants, fields, or expressions)";
            String originalCode = "@DataSourceDefinition(name=\"" + jndi + "\", ...)";
            String suggestedAction = "Replace non-literal values with string literals, or manually configure " +
                "spring.datasource.* with the resolved values.";

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg("CONFIGURATION"));
            arguments.add(createAssignmentArg("originalCode", originalCode, true));
            arguments.add(createAssignmentArg("suggestedAction", suggestedAction, true));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

        /** Codex P2.1h Round 53: Create @NeedsReview for DS with non-literal name attribute (with structured stableKey) */
        private J.Annotation createNeedsReviewForNonLiteralName(String originalAnnotationCode, Set<String> nonLiteralAttrs, String stableKey, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                type,
                null
            );

            // Filter out internal markers
            Set<String> displayAttrs = new TreeSet<>();
            for (String attr : nonLiteralAttrs) {
                if (!attr.startsWith("_")) {
                    displayAttrs.add(attr);
                }
            }
            String attrList = String.join(", ", displayAttrs);
            String reason = "@DataSourceDefinition has non-literal 'name' attribute (JNDI not extractable)" +
                (displayAttrs.size() > 1 ? " and other non-literal values: " + attrList : "");
            // Codex P2.1h Round 53: StableKey now as structured attribute, not embedded in freetext
            String suggestedAction = "Replace non-literal 'name' with a string literal, or manually configure " +
                "spring.datasource.* with appropriate values.";

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg("CONFIGURATION"));
            arguments.add(createAssignmentArg("originalCode", originalAnnotationCode, true));
            arguments.add(createAssignmentArg("suggestedAction", suggestedAction, true));
            // Codex P2.1h Round 53: Use structured stableKey attribute for deterministic matching
            arguments.add(createAssignmentArg("stableKey", stableKey, true));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

        /** Codex P2.1h Round 53: Create @NeedsReview for DS with non-literal name in Multi-DS context (with structured stableKey) */
        private J.Annotation createNeedsReviewForNonLiteralNameMultiDs(String originalAnnotationCode, Set<String> nonLiteralAttrs, String stableKey, int totalCount, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                type,
                null
            );

            String reason = "Multiple @DataSourceDefinitions found (" + totalCount + ") AND non-literal 'name' attribute - cannot auto-migrate";
            // Codex P2.1h Round 53: StableKey now as structured attribute, not embedded in freetext
            String suggestedAction = "Configure multi-datasource setup: Create @Configuration class with multiple DataSource @Beans. " +
                "Also resolve non-literal 'name' to determine JNDI configuration.";

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg("CONFIGURATION"));
            arguments.add(createAssignmentArg("originalCode", originalAnnotationCode, true));
            arguments.add(createAssignmentArg("suggestedAction", suggestedAction, true));
            // Codex P2.1h Round 53: Use structured stableKey attribute for deterministic matching
            arguments.add(createAssignmentArg("stableKey", stableKey, true));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

        /** Codex P2.1h Round 56: Create @NeedsReview for problematic custom source root configuration */
        private J.Annotation createNeedsReviewForProblematicCustomRoot(String problematicRoot, String standardSuffix, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                type,
                null
            );

            String reason = "Custom source root '" + problematicRoot + "' contains module prefix before '" + standardSuffix + "' - IGNORED";
            String suggestedAction = "In rewrite.yml, change customSourceRoots from '" + problematicRoot + "' to just '" + standardSuffix + "'. " +
                "The module prefix is already extracted automatically from source file paths.";

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg("CONFIGURATION"));
            arguments.add(createAssignmentArg("originalCode", "customSourceRoots: " + problematicRoot, true));
            arguments.add(createAssignmentArg("suggestedAction", suggestedAction, true));
            // Codex P2.1h Round 62: Use shared method for consistent stableKey
            arguments.add(createAssignmentArg("stableKey", buildProblematicRootStableKey(problematicRoot), true));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

        private JRightPadded<Expression> createAssignmentArg(String key, String value, boolean leadingSpace) {
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                leadingSpace ? Space.format(" ") : Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                key,
                null,
                null
            );

            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                value,
                "\"" + value.replace("\"", "\\\"") + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }

        private JRightPadded<Expression> createCategoryArg(String categoryName) {
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "category",
                null,
                null
            );

            JavaType.ShallowClass categoryType = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN + ".Category");

            J.Identifier needsReviewIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                JavaType.ShallowClass.build(NEEDS_REVIEW_FQN),
                null
            );

            J.FieldAccess categoryAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                needsReviewIdent,
                JLeftPadded.build(new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "Category",
                    categoryType,
                    null
                )),
                categoryType
            );

            J.FieldAccess valueExpr = new J.FieldAccess(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                categoryAccess,
                JLeftPadded.build(new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    categoryName,
                    categoryType,
                    null
                )),
                categoryType
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
            J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);

            // Codex P2.1g-2: Only remove @NeedsReview if JNDI is ALREADY configured in properties
            // This is safe (not optimistic) - requires 2 cycles for fresh migrations
            if (safeToRemoveNeedsReviewForJndi.isEmpty()) {
                return vd;
            }

            // Check if this is a DataSource field
            // Codex P2.1h Fix 4: Exact type check only (no contains() to avoid false positives)
            JavaType type = vd.getType();
            boolean isDataSourceType = TypeUtils.isOfClassType(type, "javax.sql.DataSource");
            // Fallback for unresolved types: check exact simple name or FQN
            if (!isDataSourceType && vd.getTypeExpression() != null) {
                String typeText = vd.getTypeExpression().print(getCursor()).trim();
                isDataSourceType = "DataSource".equals(typeText) || "javax.sql.DataSource".equals(typeText);
            }
            if (!isDataSourceType) {
                return vd;
            }

            // Find and remove @NeedsReview annotation ONLY if its JNDI was migrated
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean removedNeedsReview = false;

            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                // Codex P2.1g-2: Extract JNDI and check if it's ALREADY configured (not optimistic)
                String jndiFromAnnotation = extractJndiFromNeedsReviewOnField(ann);
                if (jndiFromAnnotation != null && safeToRemoveNeedsReviewForJndi.contains(jndiFromAnnotation)) {
                    // This JNDI is ALREADY configured in properties - safe to remove @NeedsReview
                    removedNeedsReview = true;
                } else if (isNeedsReviewForDataSource(ann) && jndiFromAnnotation == null) {
                    // Legacy case: @NeedsReview without extractable JNDI - keep it for safety
                    newAnnotations.add(ann);
                } else {
                    newAnnotations.add(ann);
                }
            }

            if (removedNeedsReview) {
                maybeRemoveImport(NEEDS_REVIEW_FQN);
                vd = vd.withLeadingAnnotations(newAnnotations);
            }

            return vd;
        }

        /** Codex 2.5: Extracts JNDI from @NeedsReview on a DataSource field */
        private String extractJndiFromNeedsReviewOnField(J.Annotation ann) {
            if (!TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) &&
                !"NeedsReview".equals(ann.getSimpleName())) {
                return null;
            }
            // Extract JNDI from originalCode containing jdbc or Database pattern
            if (ann.getArguments() != null) {
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                            if ("originalCode".equals(attrName)) {
                                Expression value = assignment.getAssignment();
                                if (value instanceof J.Literal) {
                                    Object literalValue = ((J.Literal) value).getValue();
                                    if (literalValue != null) {
                                        String code = literalValue.toString();
                                        // Codex P2.1g-1: Look for JNDI pattern: java:... (common JNDI prefixes)
                                        // Must handle both single and double quotes as terminators
                                        int jndiStart = code.indexOf("java:");
                                        if (jndiStart >= 0) {
                                            // Extract until quote (single or double), comma, or paren
                                            int end = code.length();
                                            for (int i = jndiStart; i < code.length(); i++) {
                                                char c = code.charAt(i);
                                                // Codex P2.1g-1: Added '\'' for single-quote termination
                                                if (c == '"' || c == '\'' || c == ',' || c == ')') {
                                                    end = i;
                                                    break;
                                                }
                                            }
                                            return code.substring(jndiStart, end).trim();
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }

        private boolean isDataSourceDefinition(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), DATASOURCE_DEFINITION_FQN) ||
                   TypeUtils.isOfClassType(ann.getType(), JAVAX_DATASOURCE_DEFINITION_FQN) ||
                   "DataSourceDefinition".equals(ann.getSimpleName());
        }

        /**
         * DS-001 Medium Fix: Collects all interface declarations from a CompilationUnit.
         * Used to resolve constants defined in interfaces that the annotated class implements.
         */
        private List<J.ClassDeclaration> collectInterfacesFromCompilationUnit(J.CompilationUnit cu) {
            List<J.ClassDeclaration> interfaces = new ArrayList<>();
            for (J.ClassDeclaration classDecl : cu.getClasses()) {
                if (classDecl.getKind() == J.ClassDeclaration.Kind.Type.Interface) {
                    interfaces.add(classDecl);
                }
            }
            return interfaces;
        }

        /**
         * Codex P2.1h-3: Compute annotation index for stable key matching.
         * Returns the index of the given @DataSourceDefinition among all @DataSourceDefinition
         * annotations on the class (0-based).
         */
        private int computeAnnotationIndex(J.ClassDeclaration classDecl, J.Annotation targetAnn) {
            int idx = 0;
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if (isDataSourceDefinition(ann)) {
                    if (ann == targetAnn) {
                        return idx;
                    }
                    idx++;
                }
            }
            return 0; // Fallback (should not happen)
        }

        private boolean isNeedsReviewForDataSource(J.Annotation ann) {
            if (!TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) &&
                !"NeedsReview".equals(ann.getSimpleName())) {
                return false;
            }

            // Check if originalCode mentions jdbc or DataSource
            if (ann.getArguments() != null) {
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                            if ("originalCode".equals(attrName)) {
                                Expression value = assignment.getAssignment();
                                if (value instanceof J.Literal) {
                                    Object literalValue = ((J.Literal) value).getValue();
                                    if (literalValue != null) {
                                        String code = literalValue.toString();
                                        return code.contains("jdbc") || code.contains("Database");
                                    }
                                }
                            }
                        }
                    }
                }
            }

            return false;
        }
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        // Properties are appended directly to application.properties in the visitor phase
        return Collections.emptyList();
    }
}
