package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.maven.AddManagedDependency;
import org.openrewrite.maven.AddPlugin;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.RemoveDependency;
import org.openrewrite.maven.RemoveManagedDependency;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.xml.tree.Xml;
import org.openrewrite.xml.XPathMatcher;

import java.util.*;

/**
 * Adds JoinFaces dependencies to enable JSF on Spring Boot.
 * <p>
 * This recipe enables the "JSF-on-Spring-Boot" strategy via JoinFaces,
 * allowing existing JSF applications to run on Spring Boot without
 * rewriting the UI layer to Thymeleaf.
 * <p>
 * <b>Version Compatibility (Finding 2):</b>
 * <table border="1">
 *   <tr><th>JoinFaces</th><th>Spring Boot</th><th>Jakarta EE</th><th>Java</th></tr>
 *   <tr><td>5.3.x</td><td>3.3.x - 3.5.x</td><td>EE 10</td><td>17+</td></tr>
 * </table>
 * Tested with: JoinFaces 5.3.0 + Spring Boot 3.5.x + Jakarta EE 10 + Java 21.
 * <p>
 * <b>CDI/JSF Scope Support (Finding 4):</b>
 * JoinFaces integrates CDI via the Spring CDI bridge. Supported scopes:
 * <ul>
 *   <li>{@code @ViewScoped} - requires joinfaces cdi-spring-boot-starter (auto-included)</li>
 *   <li>{@code @SessionScoped}, {@code @RequestScoped} - work via Spring scopes</li>
 *   <li>{@code @ConversationScoped} - requires explicit CDI configuration</li>
 * </ul>
 * <p>
 * <b>Priority Rule (Finding 1):</b>
 * If MyFaces is detected, it takes precedence over Mojarra.
 * PrimeFaces works with both implementations.
 * <p>
 * <b>PrimeFaces Version Compatibility (P0.3):</b>
 * <table border="1">
 *   <tr><th>PrimeFaces</th><th>Jakarta Faces</th><th>JoinFaces 5.x</th></tr>
 *   <tr><td>&lt; 12.0</td><td>JSF 2.x (javax)</td><td>Incompatible</td></tr>
 *   <tr><td>12.x - 13.x</td><td>Faces 3.0/4.0</td><td>Check required</td></tr>
 *   <tr><td>14.x+</td><td>Faces 4.0+</td><td>Compatible</td></tr>
 * </table>
 * When PrimeFaces &lt; 12.0 is detected, a @NeedsReview marker is added to indicate
 * that an upgrade to PrimeFaces 14.x is required for JoinFaces 5.x compatibility.
 * <p>
 * Conceptual Decision (2026-01-16):
 * <ul>
 *   <li>JSF is kept as UI technology (developers have JSF know-how)</li>
 *   <li>No UI rewrite needed (XHTML/Facelets remain unchanged)</li>
 *   <li>PrimeFaces components continue to work</li>
 *   <li>Only backend migration: EJB → Spring</li>
 * </ul>
 * <p>
 * Detection:
 * <ul>
 *   <li>JSF usage: imports starting with jakarta.faces.* or javax.faces.*</li>
 *   <li>PrimeFaces: dependency org.primefaces:primefaces</li>
 *   <li>MyFaces vs Mojarra: dependency detection</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddJoinFacesDependencies extends ScanningRecipe<AddJoinFacesDependencies.Accumulator> {

    private static final String JOINFACES_VERSION = "5.3.0";

    // JSF imports to detect
    private static final String JAKARTA_FACES_IMPORT = "jakarta.faces";
    private static final String JAVAX_FACES_IMPORT = "javax.faces";

    // Dependencies to detect
    private static final String PRIMEFACES_GROUP = "org.primefaces";
    private static final String PRIMEFACES_ARTIFACT = "primefaces";
    private static final String MYFACES_GROUP = "org.apache.myfaces.core";

    // PrimeFaces version compatibility threshold (P0.3)
    // Versions < 12.0 are incompatible with JoinFaces 5.x (they use javax.faces, not jakarta.faces)
    private static final int PRIMEFACES_MIN_COMPATIBLE_MAJOR_VERSION = 12;

    @Override
    public String getDisplayName() {
        return "Add JoinFaces dependencies for JSF on Spring Boot";
    }

    @Override
    public String getDescription() {
        return "Adds JoinFaces dependencies to enable JSF applications on Spring Boot. " +
               "Detects PrimeFaces and MyFaces usage and adds appropriate starters. " +
               "This allows keeping JSF as the UI technology without rewriting to Thymeleaf.";
    }

    static class Accumulator {
        boolean hasJsfImport = false;
        boolean hasPrimeFaces = false;
        boolean hasMyFaces = false;
        boolean hasJoinFaces = false;
        // Finding 1: Track conflict case for @NeedsReview warning
        boolean hasConflict = false;
        // P0.3: PrimeFaces version tracking
        String primeFacesVersion = null;
        boolean primeFacesVersionIncompatible = false;
        // P0.3 Codex-Fix 2: Track unresolved PrimeFaces version (e.g., ${...} or unparseable)
        boolean primeFacesVersionUnresolved = false;
        // P0.3 Codex-Fix 4: Track PrimeFaces 12.x-13.x versions that require review
        boolean primeFacesVersionRequiresReview = false;
        // P0.3: Track Java files that need @NeedsReview for PrimeFaces incompatibility
        Set<String> jsfClassPaths = new LinkedHashSet<>();
        // P0.3: Track pom.xml paths for property resolution
        Map<String, Map<String, String>> pomProperties = new LinkedHashMap<>();
        // P2.1: Track BOM positions in dependencyManagement for order validation
        // -1 means not found, otherwise the index position (0-based)
        int springBootBomPosition = -1;
        int joinfacesBomPosition = -1;
        // P2.1: Track if Spring Boot is used via parent (no explicit BOM needed)
        boolean hasSpringBootParent = false;
        // P2.1 Codex-Fix 1: Track existing JoinFaces BOM version for preservation during reorder
        String existingJoinfacesBomVersion = null;
        // P2.1 Codex-Fix 1: Track if existing JoinFaces BOM version is unresolved (property placeholder)
        boolean joinfacesBomVersionUnresolved = false;
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
                // Check Java files for JSF imports
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    boolean hasJsfInFile = false;
                    for (J.Import imp : cu.getImports()) {
                        String importPath = imp.getQualid().toString();
                        if (importPath.startsWith(JAKARTA_FACES_IMPORT) ||
                            importPath.startsWith(JAVAX_FACES_IMPORT)) {
                            acc.hasJsfImport = true;
                            hasJsfInFile = true;
                        }
                    }
                    // P0.3: Track JSF class paths for potential @NeedsReview markers
                    if (hasJsfInFile && cu.getSourcePath() != null) {
                        acc.jsfClassPaths.add(cu.getSourcePath().toString());
                    }
                }

                // Check Maven POM for dependencies
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    String sourcePath = doc.getSourcePath().toString();
                    if (sourcePath.endsWith("pom.xml")) {
                        // P0.3: Extract properties from POM for version resolution
                        extractPomProperties(doc, sourcePath, acc);

                        new MavenIsoVisitor<ExecutionContext>() {
                            @Override
                            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                                MavenResolutionResult mrr = getResolutionResult();
                                if (mrr != null) {
                                    for (ResolvedDependency dep : mrr.getDependencies().values().stream()
                                            .flatMap(List::stream).toList()) {
                                        String groupId = dep.getGroupId();
                                        String artifactId = dep.getArtifactId();

                                        // Check for PrimeFaces
                                        if (PRIMEFACES_GROUP.equals(groupId) &&
                                            PRIMEFACES_ARTIFACT.equals(artifactId)) {
                                            acc.hasPrimeFaces = true;
                                            // P0.3: Extract and check PrimeFaces version
                                            String version = dep.getVersion();
                                            if (version != null && !version.isEmpty()) {
                                                acc.primeFacesVersion = version;
                                                // P0.3 Codex-Fix 2: Check for unresolved before checking incompatible
                                                acc.primeFacesVersionUnresolved = isPrimeFacesVersionUnresolved(version);
                                                if (!acc.primeFacesVersionUnresolved) {
                                                    acc.primeFacesVersionIncompatible = isPrimeFacesVersionIncompatible(version);
                                                    // P0.3 Codex-Fix 4: Check if 12.x-13.x requires review
                                                    if (!acc.primeFacesVersionIncompatible) {
                                                        acc.primeFacesVersionRequiresReview = isPrimeFacesVersionRequiresReview(version);
                                                    }
                                                }
                                            } else {
                                                // No version found - treat as unresolved
                                                acc.primeFacesVersionUnresolved = true;
                                            }
                                        }

                                        // Check for MyFaces
                                        if (groupId != null && groupId.startsWith(MYFACES_GROUP)) {
                                            acc.hasMyFaces = true;
                                        }

                                        // Check if JoinFaces already present
                                        if ("org.joinfaces".equals(groupId)) {
                                            acc.hasJoinFaces = true;
                                        }
                                    }
                                }
                                return document;
                            }
                        }.visit(doc, ctx);

                        // P0.3: Fallback - scan XML directly for PrimeFaces version if not found via MavenResolutionResult
                        if (acc.hasPrimeFaces && acc.primeFacesVersion == null) {
                            extractPrimeFacesVersionFromXml(doc, sourcePath, acc);
                        }

                        // P2.1: Extract BOM positions and check for Spring Boot Parent
                        extractBomPositions(doc, acc);
                        checkSpringBootParent(doc, acc);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        // No file generation needed
        return Collections.emptyList();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // P2.1 Codex-Fix 2 & 3: Check if BOM order correction is needed
        // Gate on BOM presence (joinfacesBomPosition >= 0), not on hasJoinFaces (dependency usage)
        // Also respect Spring Boot Parent - if parent is used, BOM order is irrelevant
        boolean needsBomOrderCorrection = !isBomOrderCorrect(acc);

        // Only apply if:
        // 1. JSF is detected AND JoinFaces not already present, OR
        // 2. JoinFaces BOM is present but order is incorrect (P2.1)
        if (!acc.hasJsfImport) {
            return TreeVisitor.noop();
        }
        if (acc.hasJoinFaces && !needsBomOrderCorrection) {
            return TreeVisitor.noop();
        }

        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                // P0.3: Handle Java files - add @NeedsReview for incompatible, unresolved, or review-required PrimeFaces version
                if (tree instanceof J.CompilationUnit && (acc.primeFacesVersionIncompatible || acc.primeFacesVersionUnresolved || acc.primeFacesVersionRequiresReview)) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    String sourcePath = cu.getSourcePath() != null ? cu.getSourcePath().toString() : "";
                    if (acc.jsfClassPaths.contains(sourcePath)) {
                        if (acc.primeFacesVersionUnresolved) {
                            return addPrimeFacesUnresolvedVersionMarker(cu, acc.primeFacesVersion, ctx);
                        }
                        if (acc.primeFacesVersionIncompatible) {
                            return addPrimeFacesIncompatibilityMarker(cu, acc.primeFacesVersion, ctx);
                        }
                        // P0.3 Codex-Fix 4: Add review marker for 12.x-13.x versions
                        if (acc.primeFacesVersionRequiresReview) {
                            return addPrimeFacesReviewRequiredMarker(cu, acc.primeFacesVersion, ctx);
                        }
                    }
                    return tree;
                }

                if (!(tree instanceof Xml.Document)) {
                    return tree;
                }

                Xml.Document doc = (Xml.Document) tree;
                String sourcePath = doc.getSourcePath().toString();
                if (!sourcePath.endsWith("pom.xml")) {
                    return tree;
                }

                // P0.3 Codex-Fix 1 & 4: If PrimeFaces is incompatible, version is unresolved, or requires review,
                // skip ALL dependency changes. Only add @NeedsReview marker to JSF classes.
                // This prevents breaking the build before PrimeFaces compatibility is verified.
                if (acc.primeFacesVersionIncompatible || acc.primeFacesVersionUnresolved || acc.primeFacesVersionRequiresReview) {
                    return tree; // No POM changes when PrimeFaces needs manual attention
                }

                // P2.1: If JoinFaces BOM is already present but order is incorrect, add marker
                // P2.1 Codex-Fix 2: Gate on BOM presence, not on hasJoinFaces
                // P2.1 Codex-Fix 5: Use marker approach instead of auto-reorder
                // (RemoveManagedDependency + AddManagedDependency does not work reliably)
                if (acc.joinfacesBomPosition >= 0 && needsBomOrderCorrection) {
                    // Add XML comment marker to indicate manual correction needed
                    tree = addBomOrderMarker((Xml.Document) tree, acc);
                    return tree; // Marker added, no other changes needed
                }

                // P0.3: Add primefaces-spring-boot-starter only for compatible versions
                boolean addPrimeFacesStarter = acc.hasPrimeFaces;

                // 1. Add JoinFaces BOM to dependency management
                // AddManagedDependency params: groupId, artifactId, version, scope, type,
                //   classifier, versionPattern, releasesOnly, onlyIfUsing, addToRootPom, because
                tree = new AddManagedDependency(
                    "org.joinfaces",
                    "joinfaces-dependencies",
                    JOINFACES_VERSION,
                    "import",
                    "pom",
                    null, null, null, null, true, null
                ).getVisitor().visit(tree, ctx);

                // 2. Add JSF Spring Boot Starter
                // Finding 1: Clear priority rule - MyFaces takes precedence if detected
                // PrimeFaces works with both Mojarra and MyFaces, so no conflict there.
                // AddDependency params: groupId, artifactId, version, versionPattern, scope,
                //   releasesOnly, onlyIfUsing, type, classifier, optional, familyPattern, acceptTransitive
                if (acc.hasMyFaces) {
                    // MyFaces detected: Use myfaces-spring-boot-starter (replaces Mojarra)
                    // NOTE: myfaces-spring-boot-starter includes JSF implementation,
                    // jsf-spring-boot-starter is NOT needed when using MyFaces
                    tree = new AddDependency(
                        "org.joinfaces",
                        "myfaces-spring-boot-starter",
                        null, // version managed by BOM
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(tree, ctx);
                } else {
                    // Mojarra (default): Use jsf-spring-boot-starter
                    tree = new AddDependency(
                        "org.joinfaces",
                        "jsf-spring-boot-starter",
                        null, null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(tree, ctx);
                }

                // 3. Add PrimeFaces starter if detected AND compatible (works with both Mojarra and MyFaces)
                if (addPrimeFacesStarter) {
                    tree = new AddDependency(
                        "org.joinfaces",
                        "primefaces-spring-boot-starter",
                        null, null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(tree, ctx);

                    // Remove standalone PrimeFaces dependency (now managed by starter)
                    tree = new RemoveDependency(
                        PRIMEFACES_GROUP,
                        PRIMEFACES_ARTIFACT,
                        null
                    ).getVisitor().visit(tree, ctx);
                }

                // 4. Add JoinFaces Maven Plugin for classpath scanning
                // Finding 5: Only added when JSF is detected (which is already the precondition).
                // The plugin is recommended for production to speed up startup.
                // AddPlugin params: groupId, artifactId, version, configuration, dependencies, executions, filePattern
                tree = new AddPlugin(
                    "org.joinfaces",
                    "joinfaces-maven-plugin",
                    JOINFACES_VERSION,
                    null,
                    null,
                    "<executions><execution><goals><goal>classpath-scan</goal></goals></execution></executions>",
                    null
                ).getVisitor().visit(tree, ctx);

                // 5. Remove conflicting old JSF dependencies
                for (String[] dep : getOldJsfDependencies()) {
                    tree = new RemoveDependency(dep[0], dep[1], null)
                        .getVisitor().visit(tree, ctx);
                }

                return tree;
            }
        };
    }

    /**
     * Returns list of old JSF dependencies that should be removed
     * (they're now provided by JoinFaces starters).
     */
    private String[][] getOldJsfDependencies() {
        return new String[][] {
            // Jakarta Faces
            {"jakarta.faces", "jakarta.faces-api"},
            {"org.glassfish", "jakarta.faces"},
            // Javax Faces
            {"javax.faces", "javax.faces-api"},
            {"com.sun.faces", "jsf-api"},
            {"com.sun.faces", "jsf-impl"},
            {"org.glassfish", "javax.faces"},
            // JBoss
            {"org.jboss.spec.javax.faces", "jboss-jsf-api_2.3_spec"},
            // MyFaces (if using starter)
            {"org.apache.myfaces.core", "myfaces-api"},
            {"org.apache.myfaces.core", "myfaces-impl"},
        };
    }

    /**
     * P0.3: Extract properties from POM for version resolution.
     * Supports versions defined via ${property.name} syntax.
     */
    private static void extractPomProperties(Xml.Document doc, String sourcePath, Accumulator acc) {
        Map<String, String> properties = new LinkedHashMap<>();
        XPathMatcher propertiesMatcher = new XPathMatcher("/project/properties/*");

        new org.openrewrite.xml.XmlIsoVisitor<Map<String, String>>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Map<String, String> props) {
                Xml.Tag t = super.visitTag(tag, props);
                if (propertiesMatcher.matches(getCursor())) {
                    String value = t.getValue().orElse("");
                    props.put(t.getName(), value);
                }
                return t;
            }
        }.visit(doc, properties);

        acc.pomProperties.put(sourcePath, properties);
    }

    /**
     * P0.3: Extract PrimeFaces version directly from XML when MavenResolutionResult doesn't provide it.
     * This handles cases where the version is defined via a property.
     */
    private static void extractPrimeFacesVersionFromXml(Xml.Document doc, String sourcePath, Accumulator acc) {
        XPathMatcher dependencyMatcher = new XPathMatcher("/project/dependencies/dependency");
        XPathMatcher depMgmtMatcher = new XPathMatcher("/project/dependencyManagement/dependencies/dependency");

        new org.openrewrite.xml.XmlIsoVisitor<Accumulator>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Accumulator a) {
                Xml.Tag t = super.visitTag(tag, a);
                if (dependencyMatcher.matches(getCursor()) || depMgmtMatcher.matches(getCursor())) {
                    String groupId = null;
                    String artifactId = null;
                    String version = null;

                    for (org.openrewrite.xml.tree.Content content : t.getChildren()) {
                        if (content instanceof Xml.Tag) {
                            Xml.Tag child = (Xml.Tag) content;
                            String name = child.getName();
                            String value = child.getValue().orElse("");
                            if ("groupId".equals(name)) {
                                groupId = value;
                            } else if ("artifactId".equals(name)) {
                                artifactId = value;
                            } else if ("version".equals(name)) {
                                version = value;
                            }
                        }
                    }

                    if (PRIMEFACES_GROUP.equals(groupId) && PRIMEFACES_ARTIFACT.equals(artifactId) && version != null) {
                        // Resolve property reference if needed
                        String resolvedVersion = resolvePropertyValue(version, sourcePath, a);
                        if (resolvedVersion != null && !resolvedVersion.isEmpty()) {
                            a.primeFacesVersion = resolvedVersion;
                            // P0.3 Codex-Fix 2: Check for unresolved before checking incompatible
                            a.primeFacesVersionUnresolved = isPrimeFacesVersionUnresolved(resolvedVersion);
                            if (!a.primeFacesVersionUnresolved) {
                                a.primeFacesVersionIncompatible = isPrimeFacesVersionIncompatible(resolvedVersion);
                                // P0.3 Codex-Fix 4: Check if 12.x-13.x requires review
                                if (!a.primeFacesVersionIncompatible) {
                                    a.primeFacesVersionRequiresReview = isPrimeFacesVersionRequiresReview(resolvedVersion);
                                }
                            }
                        } else {
                            // No version resolved - treat as unresolved
                            a.primeFacesVersionUnresolved = true;
                        }
                    }
                }
                return t;
            }
        }.visit(doc, acc);
    }

    /**
     * P0.3: Resolve a property reference like ${primefaces.version} to its actual value.
     */
    private static String resolvePropertyValue(String value, String sourcePath, Accumulator acc) {
        if (value == null || !value.startsWith("${") || !value.endsWith("}")) {
            return value;
        }
        String propertyName = value.substring(2, value.length() - 1);
        Map<String, String> properties = acc.pomProperties.get(sourcePath);
        if (properties != null) {
            String resolved = properties.get(propertyName);
            if (resolved != null) {
                return resolved;
            }
        }
        // Property not found - might be defined in parent POM or settings, return as-is
        return value;
    }

    /**
     * P0.3: Check if PrimeFaces version is incompatible with JoinFaces 5.x.
     * Versions < 12.0 are incompatible (they use javax.faces instead of jakarta.faces).
     * Note: Unresolved/unparseable versions are handled by isPrimeFacesVersionUnresolved().
     */
    static boolean isPrimeFacesVersionIncompatible(String version) {
        if (version == null || version.isEmpty()) {
            return false; // Unknown version - handled by unresolved check
        }
        // Handle property placeholders that couldn't be resolved
        if (version.startsWith("${")) {
            return false; // Unresolved - handled by unresolved check
        }
        try {
            // Extract major version from strings like "11.0.0", "14.0.0", "12.0.10", etc.
            String majorPart = version.split("\\.")[0];
            // Handle versions with qualifiers like "11.0.0-SNAPSHOT"
            majorPart = majorPart.split("-")[0];
            int majorVersion = Integer.parseInt(majorPart);
            return majorVersion < PRIMEFACES_MIN_COMPATIBLE_MAJOR_VERSION;
        } catch (NumberFormatException e) {
            return false; // Unparseable - handled by unresolved check
        }
    }

    /**
     * P0.3 Codex-Fix 4: Check if PrimeFaces version requires manual review.
     * Versions 12.x-13.x work with Jakarta Faces 3.0/4.0 but need verification
     * to ensure compatibility with JoinFaces 5.x (which requires Faces 4.0).
     */
    static boolean isPrimeFacesVersionRequiresReview(String version) {
        if (version == null || version.isEmpty()) {
            return false; // Handled by unresolved check
        }
        if (version.startsWith("${")) {
            return false; // Handled by unresolved check
        }
        try {
            String majorPart = version.split("\\.")[0];
            majorPart = majorPart.split("-")[0];
            int majorVersion = Integer.parseInt(majorPart);
            // PrimeFaces 12.x-13.x requires review (transitional versions)
            return majorVersion >= 12 && majorVersion <= 13;
        } catch (NumberFormatException e) {
            return false; // Handled by unresolved check
        }
    }

    /**
     * P0.3 Codex-Fix 2: Check if PrimeFaces version is unresolved or unparseable.
     * Unresolved versions require manual review before JoinFaces migration can proceed.
     */
    static boolean isPrimeFacesVersionUnresolved(String version) {
        if (version == null || version.isEmpty()) {
            return true; // Unknown version - needs review
        }
        // Handle property placeholders that couldn't be resolved
        if (version.startsWith("${")) {
            return true; // Unresolved property - needs review
        }
        try {
            // Try to parse the major version
            String majorPart = version.split("\\.")[0];
            majorPart = majorPart.split("-")[0];
            Integer.parseInt(majorPart);
            return false; // Parseable - not unresolved
        } catch (NumberFormatException e) {
            return true; // Unparseable - needs review
        }
    }

    /**
     * P0.3: Add @NeedsReview annotation to a JSF class when PrimeFaces version is incompatible.
     */
    private Tree addPrimeFacesIncompatibilityMarker(J.CompilationUnit cu, String primeFacesVersion, ExecutionContext ctx) {
        // Check if class already has @NeedsReview annotation
        if (cu.getClasses().isEmpty()) {
            return cu;
        }
        J.ClassDeclaration classDecl = cu.getClasses().get(0);
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            if (ann.getSimpleName().equals("NeedsReview")) {
                // Already has @NeedsReview - check if it's for PrimeFaces
                String annString = ann.toString();
                if (annString.contains("PrimeFaces")) {
                    return cu; // Already marked for PrimeFaces
                }
            }
        }

        // Add @NeedsReview annotation
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Only annotate top-level class
                if (getCursor().getParentTreeCursor().getValue() instanceof J.CompilationUnit) {
                    // Add import for NeedsReview
                    maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                    maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview.Category");

                    String reason = String.format(
                        "PrimeFaces %s is incompatible with JoinFaces 5.x (requires PrimeFaces 12.x or higher)",
                        primeFacesVersion != null ? primeFacesVersion : "< 12.0"
                    );
                    String suggestedAction = "Upgrade PrimeFaces to version 14.x or higher for Jakarta Faces 4.0 compatibility. " +
                        "See: https://primefaces.github.io/primefaces/14_0_0/#/gettingstarted/whatsnew";

                    JavaTemplate template = JavaTemplate.builder(
                        "@NeedsReview(\n" +
                        "    reason = \"" + reason + "\",\n" +
                        "    category = Category.CONFIGURATION,\n" +
                        "    originalCode = \"org.primefaces:primefaces:" + (primeFacesVersion != null ? primeFacesVersion : "<12.0") + "\",\n" +
                        "    suggestedAction = \"" + suggestedAction + "\"\n" +
                        ")"
                    ).javaParser(org.openrewrite.java.JavaParser.fromJavaVersion()
                        .classpath("ejb-to-spring-recipes"))
                    .imports("com.github.rewrite.ejb.annotations.NeedsReview",
                             "com.github.rewrite.ejb.annotations.NeedsReview.Category")
                    .build();

                    cd = template.apply(getCursor(), cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
                }
                return cd;
            }
        }.visit(cu, ctx);
    }

    /**
     * P0.3 Codex-Fix 4: Add @NeedsReview annotation to a JSF class when PrimeFaces version requires review (12.x-13.x).
     */
    private Tree addPrimeFacesReviewRequiredMarker(J.CompilationUnit cu, String primeFacesVersion, ExecutionContext ctx) {
        // Check if class already has @NeedsReview annotation
        if (cu.getClasses().isEmpty()) {
            return cu;
        }
        J.ClassDeclaration classDecl = cu.getClasses().get(0);
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            if (ann.getSimpleName().equals("NeedsReview")) {
                String annString = ann.toString();
                if (annString.contains("PrimeFaces")) {
                    return cu; // Already marked for PrimeFaces
                }
            }
        }

        // Add @NeedsReview annotation
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Only annotate top-level class
                if (getCursor().getParentTreeCursor().getValue() instanceof J.CompilationUnit) {
                    maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                    maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview.Category");

                    String reason = String.format(
                        "PrimeFaces %s requires manual verification for JoinFaces 5.x compatibility (Faces 4.0)",
                        primeFacesVersion != null ? primeFacesVersion : "12.x-13.x"
                    );
                    String suggestedAction = "1. Verify PrimeFaces " + (primeFacesVersion != null ? primeFacesVersion : "12.x-13.x") +
                        " is compatible with Jakarta Faces 4.0. " +
                        "2. Consider upgrading to PrimeFaces 14.x for guaranteed Faces 4.0 support. " +
                        "3. Test JSF pages thoroughly after JoinFaces migration.";

                    JavaTemplate template = JavaTemplate.builder(
                        "@NeedsReview(\n" +
                        "    reason = \"" + reason + "\",\n" +
                        "    category = Category.CONFIGURATION,\n" +
                        "    originalCode = \"org.primefaces:primefaces:" + (primeFacesVersion != null ? primeFacesVersion : "12.x-13.x") + "\",\n" +
                        "    suggestedAction = \"" + suggestedAction + "\"\n" +
                        ")"
                    ).javaParser(org.openrewrite.java.JavaParser.fromJavaVersion()
                        .classpath("ejb-to-spring-recipes"))
                    .imports("com.github.rewrite.ejb.annotations.NeedsReview",
                             "com.github.rewrite.ejb.annotations.NeedsReview.Category")
                    .build();

                    cd = template.apply(getCursor(), cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
                }
                return cd;
            }
        }.visit(cu, ctx);
    }

    /**
     * P0.3 Codex-Fix 2: Add @NeedsReview annotation to a JSF class when PrimeFaces version cannot be resolved.
     */
    private Tree addPrimeFacesUnresolvedVersionMarker(J.CompilationUnit cu, String primeFacesVersion, ExecutionContext ctx) {
        // Check if class already has @NeedsReview annotation
        if (cu.getClasses().isEmpty()) {
            return cu;
        }
        J.ClassDeclaration classDecl = cu.getClasses().get(0);
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            if (ann.getSimpleName().equals("NeedsReview")) {
                String annString = ann.toString();
                if (annString.contains("PrimeFaces")) {
                    return cu; // Already marked for PrimeFaces
                }
            }
        }

        // Add @NeedsReview annotation
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Only annotate top-level class
                if (getCursor().getParentTreeCursor().getValue() instanceof J.CompilationUnit) {
                    maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                    maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview.Category");

                    String versionInfo = primeFacesVersion != null ? primeFacesVersion : "unknown";
                    String reason = String.format(
                        "PrimeFaces version '%s' could not be resolved. Manual verification required before JoinFaces migration.",
                        versionInfo
                    );
                    String suggestedAction = "1. Resolve the PrimeFaces version in pom.xml (check parent POMs, BOMs, or profiles). " +
                        "2. Verify PrimeFaces >= 12.0 for Jakarta Faces 4.0 compatibility. " +
                        "3. If PrimeFaces < 12.0, upgrade to 14.x before running this recipe again.";

                    JavaTemplate template = JavaTemplate.builder(
                        "@NeedsReview(\n" +
                        "    reason = \"" + reason + "\",\n" +
                        "    category = Category.CONFIGURATION,\n" +
                        "    originalCode = \"org.primefaces:primefaces:" + versionInfo + "\",\n" +
                        "    suggestedAction = \"" + suggestedAction + "\"\n" +
                        ")"
                    ).javaParser(org.openrewrite.java.JavaParser.fromJavaVersion()
                        .classpath("ejb-to-spring-recipes"))
                    .imports("com.github.rewrite.ejb.annotations.NeedsReview",
                             "com.github.rewrite.ejb.annotations.NeedsReview.Category")
                    .build();

                    cd = template.apply(getCursor(), cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
                }
                return cd;
            }
        }.visit(cu, ctx);
    }

    /**
     * P2.1: Extract BOM positions from dependencyManagement section.
     * Tracks the position (0-based index) of Spring Boot BOM and JoinFaces BOM.
     * Also captures the existing JoinFaces BOM version for preservation during reorder.
     * <p>
     * The order matters: JoinFaces BOM must come AFTER Spring Boot BOM to correctly
     * override versions. If the order is wrong, the recipe should correct it.
     */
    private static void extractBomPositions(Xml.Document doc, Accumulator acc) {
        XPathMatcher depMgmtMatcher = new XPathMatcher("/project/dependencyManagement/dependencies/dependency");

        final int[] position = {0}; // Counter for dependency position

        new org.openrewrite.xml.XmlIsoVisitor<Accumulator>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Accumulator a) {
                Xml.Tag t = super.visitTag(tag, a);
                if (depMgmtMatcher.matches(getCursor())) {
                    String groupId = null;
                    String artifactId = null;
                    String version = null;
                    String type = null;
                    String scope = null;

                    for (org.openrewrite.xml.tree.Content content : t.getChildren()) {
                        if (content instanceof Xml.Tag) {
                            Xml.Tag child = (Xml.Tag) content;
                            String name = child.getName();
                            String value = child.getValue().orElse("");
                            if ("groupId".equals(name)) {
                                groupId = value;
                            } else if ("artifactId".equals(name)) {
                                artifactId = value;
                            } else if ("version".equals(name)) {
                                version = value;
                            } else if ("type".equals(name)) {
                                type = value;
                            } else if ("scope".equals(name)) {
                                scope = value;
                            }
                        }
                    }

                    // Check if this is a BOM import (type=pom, scope=import)
                    boolean isBom = "pom".equals(type) && "import".equals(scope);

                    if (isBom) {
                        // Check for Spring Boot BOM
                        if ("org.springframework.boot".equals(groupId) &&
                            "spring-boot-dependencies".equals(artifactId)) {
                            a.springBootBomPosition = position[0];
                        }
                        // Check for JoinFaces BOM
                        if ("org.joinfaces".equals(groupId) &&
                            "joinfaces-dependencies".equals(artifactId)) {
                            a.joinfacesBomPosition = position[0];
                            // P2.1 Codex-Fix 1: Capture existing version for preservation
                            a.existingJoinfacesBomVersion = version;
                            // Check if version is unresolved (property placeholder)
                            if (version != null && version.startsWith("${")) {
                                a.joinfacesBomVersionUnresolved = true;
                            }
                        }
                    }
                    position[0]++;
                }
                return t;
            }
        }.visit(doc, acc);
    }

    /**
     * P2.1: Check if Spring Boot is used via parent POM.
     * If spring-boot-starter-parent is the parent, no explicit Spring Boot BOM is needed.
     */
    private static void checkSpringBootParent(Xml.Document doc, Accumulator acc) {
        XPathMatcher parentMatcher = new XPathMatcher("/project/parent");

        new org.openrewrite.xml.XmlIsoVisitor<Accumulator>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Accumulator a) {
                Xml.Tag t = super.visitTag(tag, a);
                if (parentMatcher.matches(getCursor())) {
                    String groupId = null;
                    String artifactId = null;

                    for (org.openrewrite.xml.tree.Content content : t.getChildren()) {
                        if (content instanceof Xml.Tag) {
                            Xml.Tag child = (Xml.Tag) content;
                            String name = child.getName();
                            String value = child.getValue().orElse("");
                            if ("groupId".equals(name)) {
                                groupId = value;
                            } else if ("artifactId".equals(name)) {
                                artifactId = value;
                            }
                        }
                    }

                    // Check for Spring Boot Parent
                    if ("org.springframework.boot".equals(groupId) &&
                        "spring-boot-starter-parent".equals(artifactId)) {
                        a.hasSpringBootParent = true;
                    }
                }
                return t;
            }
        }.visit(doc, acc);
    }

    /**
     * P2.1: Check if JoinFaces BOM order is correct.
     * Returns true if the order is correct (Spring Boot before JoinFaces) or if Spring Boot Parent is used.
     */
    static boolean isBomOrderCorrect(Accumulator acc) {
        // If Spring Boot Parent is used, no explicit BOM is needed - order is always OK
        if (acc.hasSpringBootParent) {
            return true;
        }
        // If JoinFaces BOM is not present, order is OK (nothing to check)
        if (acc.joinfacesBomPosition < 0) {
            return true;
        }
        // If Spring Boot BOM is not present, order is OK (JoinFaces can be standalone)
        if (acc.springBootBomPosition < 0) {
            return true;
        }
        // Both BOMs present: JoinFaces must come AFTER Spring Boot
        return acc.joinfacesBomPosition > acc.springBootBomPosition;
    }

    /**
     * P2.1 Codex-Fix 5: Add XML comment marker to POM indicating BOM order needs manual correction.
     * This approach is used because RemoveManagedDependency + AddManagedDependency does not work reliably.
     * <p>
     * The marker is added before the dependencyManagement section to make it visible.
     */
    private static Xml.Document addBomOrderMarker(Xml.Document doc, Accumulator acc) {
        XPathMatcher depMgmtMatcher = new XPathMatcher("/project/dependencyManagement");

        return (Xml.Document) new org.openrewrite.xml.XmlIsoVisitor<Accumulator>() {
            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Accumulator a) {
                Xml.Tag t = super.visitTag(tag, a);
                if (depMgmtMatcher.matches(getCursor())) {
                    // Check if marker already exists (avoid duplicates)
                    String existingPrefix = t.getPrefix();
                    if (existingPrefix != null && existingPrefix.contains("@NeedsReview: BOM Order")) {
                        return t; // Already marked
                    }

                    // Build marker comment
                    String versionInfo = a.existingJoinfacesBomVersion != null
                        ? a.existingJoinfacesBomVersion
                        : "unknown";
                    String marker = String.format(
                        "\n    <!-- @NeedsReview: BOM Order\n" +
                        "         Problem: JoinFaces BOM (version %s) must come AFTER Spring Boot BOM in dependencyManagement.\n" +
                        "         Current order: JoinFaces at position %d, Spring Boot at position %d.\n" +
                        "         Fix: Move the joinfaces-dependencies BOM import below spring-boot-dependencies.\n" +
                        "         See: https://docs.joinfaces.org/current/reference/#_spring_boot_bom_version_management -->\n    ",
                        versionInfo, a.joinfacesBomPosition, a.springBootBomPosition
                    );

                    // Add marker comment before the tag
                    return t.withPrefix(marker);
                }
                return t;
            }
        }.visit(doc, acc);
    }
}
