package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Migrates JAX-RS Client code based on a configurable strategy.
 * <p>
 * JAX-RS Client API (jakarta.ws.rs.client.* / javax.ws.rs.client.*) provides
 * a fluent API for building HTTP client requests. This recipe handles migration
 * to Spring Boot in one of two ways:
 * <p>
 * <b>Strategy Options:</b>
 * <ul>
 *   <li><b>manual</b>: Classes using JAX-RS Client are marked with
 *       {@code @NeedsReview(category = MANUAL_MIGRATION)} and
 *       {@code @Profile("manual-migration")} for safety. Developer must manually
 *       migrate to Spring RestClient or WebClient.</li>
 *   <li><b>keep-jaxrs</b> (default if project.yaml is absent): JAX-RS Client code is kept, but required runtime
 *       dependencies are added to pom.xml. Choose provider: jersey (default),
 *       resteasy, or cxf.</li>
 * </ul>
 * <p>
 * <b>Detected JAX-RS Client APIs:</b>
 * <ul>
 *   <li>jakarta.ws.rs.client.ClientBuilder</li>
 *   <li>jakarta.ws.rs.client.Client</li>
 *   <li>jakarta.ws.rs.client.WebTarget</li>
 *   <li>jakarta.ws.rs.client.Invocation</li>
 *   <li>javax.ws.rs.client.* (legacy namespace)</li>
 *   <li>Fully qualified type references (no import needed)</li>
 * </ul>
 * <p>
 * <b>Provider Dependencies (keep-jaxrs strategy):</b>
 * <ul>
 *   <li><b>jersey</b>: org.glassfish.jersey.core:jersey-client,
 *       org.glassfish.jersey.inject:jersey-hk2, org.glassfish.jersey.media:jersey-media-json-binding</li>
 *   <li><b>resteasy</b>: org.jboss.resteasy:resteasy-client, org.jboss.resteasy:resteasy-json-binding-provider</li>
 *   <li><b>cxf</b>: org.apache.cxf:cxf-rt-rs-client</li>
 * </ul>
 */
@EqualsAndHashCode(callSuper = false)
public class MigrateJaxRsClient extends ScanningRecipe<MigrateJaxRsClient.Accumulator> {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final String PROFILE_FQN = "org.springframework.context.annotation.Profile";
    private static final String PROFILE_NAME = "manual-migration";

    // Import prefixes for detection
    private static final String JAKARTA_CLIENT_IMPORT_PREFIX = "jakarta.ws.rs.client";
    private static final String JAVAX_CLIENT_IMPORT_PREFIX = "javax.ws.rs.client";

    // All JAX-RS Client short names for detection
    private static final Set<String> JAXRS_CLIENT_TYPE_NAMES = Set.of(
        // Core client types
        "ClientBuilder", "Client", "WebTarget", "Invocation", "Entity",
        "AsyncInvoker", "SyncInvoker", "CompletionStageRxInvoker", "RxInvoker",
        // Request/Response filters and interceptors
        "ClientRequestFilter", "ClientResponseFilter",
        "ClientRequestContext", "ClientResponseContext",
        "ReaderInterceptor", "ReaderInterceptorContext",
        "WriterInterceptor", "WriterInterceptorContext",
        // Callback and async support
        "InvocationCallback",
        // Exceptions and providers
        "ResponseProcessingException", "RxInvokerProvider"
    );

    // All JAX-RS Client types for fully qualified detection (both namespaces)
    private static final Set<String> ALL_JAXRS_CLIENT_TYPES;
    static {
        Set<String> types = new HashSet<>();
        for (String name : JAXRS_CLIENT_TYPE_NAMES) {
            types.add(JAKARTA_CLIENT_IMPORT_PREFIX + "." + name);
            types.add(JAVAX_CLIENT_IMPORT_PREFIX + "." + name);
        }
        ALL_JAXRS_CLIENT_TYPES = Set.copyOf(types);
    }

    // Default dependency versions (for keep-jaxrs strategy)
    private static final String DEFAULT_JERSEY_VERSION = "3.1.5";
    private static final String DEFAULT_RESTEASY_VERSION = "6.2.7.Final";
    private static final String DEFAULT_CXF_VERSION = "4.0.4";

    // Valid strategies (including planned migrations)
    private static final Set<String> VALID_STRATEGIES = Set.of(
        "manual",
        "keep-jaxrs",
        "migrate-restclient",
        "migrate-webclient"
    );

    @Option(displayName = "Strategy",
            description = "Migration strategy: 'manual' marks classes for manual migration, " +
                          "'keep-jaxrs' adds provider dependencies to keep JAX-RS Client. " +
                          "Unknown strategies fall back to 'manual' with a warning.",
            example = "manual",
            required = false)
    @Nullable
    private final String strategy;

    @Option(displayName = "Provider",
            description = "JAX-RS provider for keep-jaxrs strategy: jersey (default), resteasy, or cxf.",
            example = "jersey",
            required = false)
    @Nullable
    private final String provider;

    @Option(displayName = "Provider Version",
            description = "Version for provider dependencies. If not set, uses built-in defaults.",
            example = "3.1.5",
            required = false)
    @Nullable
    private final String providerVersion;

    // Valid providers
    private static final Set<String> VALID_PROVIDERS = Set.of("jersey", "resteasy", "cxf");

    // Short names of JAX-RS Client types for wildcard import detection
    private static final Set<String> JAXRS_CLIENT_SHORT_NAMES = JAXRS_CLIENT_TYPE_NAMES;

    /**
     * No-args constructor for OpenRewrite serialization.
     */
    public MigrateJaxRsClient() {
        this.strategy = null;
        this.provider = null;
        this.providerVersion = null;
    }

    /**
     * All-args constructor.
     *
     * @param strategy Migration strategy: "manual" (default) or "keep-jaxrs"
     * @param provider JAX-RS provider: "jersey" (default), "resteasy", or "cxf"
     */
    public MigrateJaxRsClient(@Nullable String strategy, @Nullable String provider) {
        this.strategy = strategy;
        this.provider = provider;
        this.providerVersion = null;
    }

    /**
     * Full constructor with version control.
     *
     * @param strategy Migration strategy: "manual" (default) or "keep-jaxrs"
     * @param provider JAX-RS provider: "jersey" (default), "resteasy", or "cxf"
     * @param providerVersion Version for provider dependencies. If null or empty, uses built-in defaults.
     */
    public MigrateJaxRsClient(@Nullable String strategy, @Nullable String provider, @Nullable String providerVersion) {
        this.strategy = strategy;
        this.provider = provider;
        this.providerVersion = providerVersion;
    }

    public String getStrategy() {
        return strategy;
    }

    public String getProvider() {
        return provider;
    }

    public String getProviderVersion() {
        return providerVersion;
    }

    @Override
    public String getDisplayName() {
        return "Migrate JAX-RS Client code";
    }

    @Override
    public String getDescription() {
        return "Handles JAX-RS Client code migration based on strategy. " +
               "'manual' marks classes for manual migration with @NeedsReview and @Profile. " +
               "'keep-jaxrs' adds provider dependencies (jersey, resteasy, or cxf) to pom.xml.";
    }

    static class Accumulator {
        // Classes that actually use JAX-RS Client APIs (FQN -> usage description)
        Map<String, String> classesUsingJaxRsClient = new LinkedHashMap<>();
        // Whether project uses Jakarta or Javax namespace
        boolean usesJakarta = false;
        boolean usesJavax = false;
        // Individual artifact tracking for providers
        boolean hasJerseyClient = false;
        boolean hasJerseyHk2 = false;
        boolean hasJerseyJsonBinding = false;
        boolean hasResteasyClient = false;
        boolean hasResteasyJsonBinding = false;
        boolean hasCxfClient = false;
        // Source paths of files with JAX-RS Client usage
        Set<String> filesWithJaxRsClient = new LinkedHashSet<>();
        // Warnings for invalid config
        String strategyWarning = null;
        String providerWarning = null;
        // Track unresolved property placeholders in dependencies
        // Only main dependency placeholders should block provider dep additions
        Map<String, Boolean> mainUnresolvedGavPlaceholdersByPom = new HashMap<>();
        Map<String, Boolean> mainUnresolvedScopePlaceholdersByPom = new HashMap<>();
        Map<String, Boolean> profileUnresolvedGavPlaceholdersByPom = new HashMap<>();
        Map<String, Boolean> profileUnresolvedScopePlaceholdersByPom = new HashMap<>();
        // Pre-grouped details by POM and section for efficient warning emission
        Map<String, List<String>> mainGavDetailsByPom = new HashMap<>();
        Map<String, List<String>> mainScopeDetailsByPom = new HashMap<>();
        Map<String, List<String>> profileGavDetailsByPom = new HashMap<>();
        Map<String, List<String>> profileScopeDetailsByPom = new HashMap<>();
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
                // Scan Java files for JAX-RS Client usage
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    scanJavaFile(cu, acc);
                }

                // Scan Maven POM for existing provider dependencies
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    String sourcePath = doc.getSourcePath().toString();
                    if (sourcePath.endsWith("pom.xml")) {
                        scanMavenDependencies(doc, acc, ctx);
                    }
                }

                return tree;
            }
        };
    }

    private void scanJavaFile(J.CompilationUnit cu, Accumulator acc) {
        String sourcePath = cu.getSourcePath().toString();

        // Track imports and detect wildcard usage
        boolean hasWildcardImport = false;
        Set<String> importedApis = new LinkedHashSet<>();
        String namespace = null;

        for (J.Import imp : cu.getImports()) {
            String importPath = imp.getQualid().toString();

            if (importPath.startsWith(JAKARTA_CLIENT_IMPORT_PREFIX)) {
                acc.usesJakarta = true;
                namespace = "jakarta";
                String api = extractApiName(importPath);
                if ("*".equals(api)) {
                    hasWildcardImport = true;
                } else {
                    importedApis.add(api);
                }
            } else if (importPath.startsWith(JAVAX_CLIENT_IMPORT_PREFIX)) {
                acc.usesJavax = true;
                namespace = "javax";
                String api = extractApiName(importPath);
                if ("*".equals(api)) {
                    hasWildcardImport = true;
                } else {
                    importedApis.add(api);
                }
            }
        }

        // When wildcard import is present, use known JAX-RS Client type names
        // for per-class scanning instead of marking all classes blindly
        Set<String> effectiveImportedApis = new LinkedHashSet<>(importedApis);
        if (hasWildcardImport) {
            effectiveImportedApis.addAll(JAXRS_CLIENT_SHORT_NAMES);
        }

        // Per-class detection: Scan each class for actual JAX-RS Client usage
        for (J.ClassDeclaration classDecl : cu.getClasses()) {
            if (classDecl.getType() == null) continue;

            String classFqn = classDecl.getType().getFullyQualifiedName();
            Set<String> classUsedApis = new LinkedHashSet<>();

            // Check if class uses imported APIs (via field types, method parameters, return types)
            boolean usesJaxRsClient = scanClassForJaxRsClientUsage(classDecl, effectiveImportedApis, classUsedApis);

            // Also detect fully qualified usage
            boolean usesFullyQualified = scanClassForFullyQualifiedUsage(classDecl, classUsedApis);

            // Only mark if actual usage detected
            if (usesJaxRsClient || usesFullyQualified) {
                acc.filesWithJaxRsClient.add(sourcePath);

                String usageDesc;
                if (!classUsedApis.isEmpty()) {
                    usageDesc = "Uses: " + String.join(", ", classUsedApis);
                } else if (hasWildcardImport) {
                    usageDesc = "Uses: " + namespace + ".ws.rs.client.*";
                } else {
                    usageDesc = "Uses: JAX-RS Client API";
                }

                acc.classesUsingJaxRsClient.put(classFqn, usageDesc);
            }
        }
    }

    /**
     * Scans a class for JAX-RS Client usage via imports.
     * Returns true if usage detected, and populates classUsedApis with API names.
     */
    private boolean scanClassForJaxRsClientUsage(J.ClassDeclaration classDecl, Set<String> importedApis, Set<String> classUsedApis) {
        if (importedApis.isEmpty()) {
            return false;
        }

        // Simple heuristic: if class has JAX-RS Client imports in its file and has fields/methods,
        // assume it uses them. More precise would be AST traversal for identifiers.
        boolean[] found = {false};

        new JavaIsoVisitor<Set<String>>() {
            @Override
            public J.Identifier visitIdentifier(J.Identifier identifier, Set<String> apis) {
                String name = identifier.getSimpleName();
                if (importedApis.contains(name)) {
                    apis.add(name);
                    found[0] = true;
                }
                return identifier;
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, Set<String> apis) {
                // Check for fully qualified access like jakarta.ws.rs.client.ClientBuilder
                String fqn = fieldAccess.toString();
                for (String type : ALL_JAXRS_CLIENT_TYPES) {
                    if (fqn.contains(type) || fqn.startsWith(type.substring(type.lastIndexOf('.') + 1))) {
                        apis.add(type.substring(type.lastIndexOf('.') + 1));
                        found[0] = true;
                    }
                }
                return super.visitFieldAccess(fieldAccess, apis);
            }
        }.visit(classDecl, classUsedApis);

        return found[0];
    }

    /**
     * Scans a class for fully qualified JAX-RS Client usage (no import needed).
     * Detects usage like jakarta.ws.rs.client.ClientBuilder without import.
     */
    private boolean scanClassForFullyQualifiedUsage(J.ClassDeclaration classDecl, Set<String> classUsedApis) {
        boolean[] found = {false};

        new JavaIsoVisitor<Set<String>>() {
            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, Set<String> apis) {
                JavaType type = fieldAccess.getType();
                if (type instanceof JavaType.Class) {
                    String fqn = ((JavaType.Class) type).getFullyQualifiedName();
                    if (ALL_JAXRS_CLIENT_TYPES.contains(fqn)) {
                        apis.add(fqn.substring(fqn.lastIndexOf('.') + 1));
                        found[0] = true;
                    }
                }
                // Also check the string representation for unresolved types
                String accessStr = fieldAccess.toString();
                for (String jaxrsType : ALL_JAXRS_CLIENT_TYPES) {
                    if (accessStr.equals(jaxrsType) || accessStr.endsWith("." + jaxrsType.substring(jaxrsType.lastIndexOf('.') + 1))) {
                        apis.add(jaxrsType.substring(jaxrsType.lastIndexOf('.') + 1));
                        found[0] = true;
                    }
                }
                return super.visitFieldAccess(fieldAccess, apis);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Set<String> apis) {
                // Check for static method calls like ClientBuilder.newClient()
                Expression select = method.getSelect();
                if (select != null) {
                    JavaType selectType = select.getType();
                    if (selectType instanceof JavaType.Class) {
                        String fqn = ((JavaType.Class) selectType).getFullyQualifiedName();
                        if (ALL_JAXRS_CLIENT_TYPES.contains(fqn)) {
                            apis.add(fqn.substring(fqn.lastIndexOf('.') + 1));
                            found[0] = true;
                        }
                    }
                }
                return super.visitMethodInvocation(method, apis);
            }
        }.visit(classDecl, classUsedApis);

        return found[0];
    }

    private String extractApiName(String importPath) {
        int lastDot = importPath.lastIndexOf('.');
        if (lastDot > 0) {
            return importPath.substring(lastDot + 1);
        }
        return importPath;
    }

    /**
     * Scans Maven POM for existing DIRECT provider dependencies.
     * Uses MavenResolutionResult with isDirect() for property resolution.
     * Also scans profile dependencies via XML since MavenResolutionResult only shows active profiles.
     */
    private void scanMavenDependencies(Xml.Document doc, Accumulator acc, ExecutionContext ctx) {
        boolean foundAnyDirectDep = false;

        // Try MavenResolutionResult first for resolved coordinates (handles property interpolation)
        Optional<MavenResolutionResult> mavenResultOpt = doc.getMarkers().findFirst(MavenResolutionResult.class);
        if (mavenResultOpt.isPresent()) {
            MavenResolutionResult mavenResult = mavenResultOpt.get();
            Map<org.openrewrite.maven.tree.Scope, List<ResolvedDependency>> dependencies = mavenResult.getDependencies();

            if (dependencies != null) {
                // Scan Compile scope
                List<ResolvedDependency> compileDeps = dependencies.getOrDefault(
                    org.openrewrite.maven.tree.Scope.Compile, Collections.emptyList());
                for (ResolvedDependency dep : compileDeps) {
                    if (dep.isDirect()) {
                        checkAndTrackDependency(dep.getGroupId(), dep.getArtifactId(), acc);
                        foundAnyDirectDep = true;
                    }
                }

                // Scan Runtime scope
                List<ResolvedDependency> runtimeDeps = dependencies.getOrDefault(
                    org.openrewrite.maven.tree.Scope.Runtime, Collections.emptyList());
                for (ResolvedDependency dep : runtimeDeps) {
                    if (dep.isDirect()) {
                        checkAndTrackDependency(dep.getGroupId(), dep.getArtifactId(), acc);
                        foundAnyDirectDep = true;
                    }
                }
            }
        }

        // Fallback to XML scanning if MavenResolutionResult unavailable or empty
        // Also scan profile dependencies (inactive profiles not in MavenResolutionResult)
        scanMavenDependenciesViaXml(doc, acc, !foundAnyDirectDep);
    }

    /**
     * Scans Maven POM via XML for dependencies.
     * Used as fallback when MavenResolutionResult is unavailable, and always for profile dependencies.
     * Only scans dependencies under project/dependencies or profile/dependencies,
     * NOT under dependencyManagement or plugin sections.
     * Always scans main deps for placeholders, even when MavenResolutionResult exists.
     *
     * @param doc the Maven POM document
     * @param acc accumulator for tracking dependencies
     * @param trackMainProviderDeps if true, also track main provider dependencies (fallback mode)
     */
    // Package-private for testing
    void scanMavenDependenciesViaXml(Xml.Document doc, Accumulator acc, boolean trackMainProviderDeps) {
        String sourcePath = doc.getSourcePath().toString();

        new MavenIsoVisitor<Accumulator>() {
            private boolean inProfileDependencies = false;
            private boolean inMainDependencies = false;

            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Accumulator a) {
                // Track when we're inside a profile's dependencies section
                if ("profile".equals(tag.getName())) {
                    // Visit children to find dependencies inside profile
                    return super.visitTag(tag, a);
                }

                if ("dependencies".equals(tag.getName())) {
                    // Check parent to determine what kind of dependencies section this is
                    Xml.Tag parent = getCursor().getParentOrThrow().getValue() instanceof Xml.Tag
                        ? (Xml.Tag) getCursor().getParentOrThrow().getValue() : null;

                    if (parent == null) {
                        return super.visitTag(tag, a);
                    }

                    String parentName = parent.getName();

                    // Only process dependencies under profile or project
                    // Skip dependencyManagement, plugin, build, etc.
                    if ("profile".equals(parentName)) {
                        inProfileDependencies = true;
                        Xml.Tag result = super.visitTag(tag, a);
                        inProfileDependencies = false;
                        return result;
                    } else if ("project".equals(parentName)) {
                        // ALWAYS scan main dependencies for placeholders
                        // (even when MavenResolutionResult exists), but only track provider deps in fallback mode
                        inMainDependencies = true;
                        Xml.Tag result = super.visitTag(tag, a);
                        inMainDependencies = false;
                        return result;
                    }
                    // Skip dependencies under dependencyManagement, build/plugins, etc.
                }

                if ("dependency".equals(tag.getName())) {
                    // Only process if we're in valid dependency sections
                    if (inProfileDependencies || inMainDependencies) {
                        String groupId = getChildValue(tag, "groupId");
                        String artifactId = getChildValue(tag, "artifactId");
                        String scope = getChildValue(tag, "scope");

                        String safeGroupId = groupId != null ? groupId : "<unknown>";
                        String safeArtifactId = artifactId != null ? artifactId : "<unknown>";
                        String section = inProfileDependencies ? "profile" : "main";

                        // Track GAV placeholders separately for main vs profile (pre-grouped by POM)
                        // Include sourcePath in detail for traceability when extracted from context
                        String gavDetail = sourcePath + ": " + safeGroupId + ":" + safeArtifactId;
                        if (groupId != null && groupId.contains("${")) {
                            if (inMainDependencies) {
                                a.mainUnresolvedGavPlaceholdersByPom.put(sourcePath, true);
                                a.mainGavDetailsByPom.computeIfAbsent(sourcePath, k -> new ArrayList<>()).add(gavDetail);
                            } else {
                                a.profileUnresolvedGavPlaceholdersByPom.put(sourcePath, true);
                                a.profileGavDetailsByPom.computeIfAbsent(sourcePath, k -> new ArrayList<>()).add(gavDetail);
                            }
                            return super.visitTag(tag, a); // Skip this dependency
                        }
                        if (artifactId != null && artifactId.contains("${")) {
                            if (inMainDependencies) {
                                a.mainUnresolvedGavPlaceholdersByPom.put(sourcePath, true);
                                a.mainGavDetailsByPom.computeIfAbsent(sourcePath, k -> new ArrayList<>()).add(gavDetail);
                            } else {
                                a.profileUnresolvedGavPlaceholdersByPom.put(sourcePath, true);
                                a.profileGavDetailsByPom.computeIfAbsent(sourcePath, k -> new ArrayList<>()).add(gavDetail);
                            }
                            return super.visitTag(tag, a); // Skip this dependency
                        }

                        // Track scope placeholders separately (pre-grouped by POM)
                        // Include sourcePath in detail for traceability when extracted from context
                        if (scope != null && scope.contains("${")) {
                            String scopeDetail = sourcePath + ": " + safeGroupId + ":" + safeArtifactId + " (scope=" + scope + ")";
                            if (inMainDependencies) {
                                a.mainUnresolvedScopePlaceholdersByPom.put(sourcePath, true);
                                a.mainScopeDetailsByPom.computeIfAbsent(sourcePath, k -> new ArrayList<>()).add(scopeDetail);
                            } else {
                                a.profileUnresolvedScopePlaceholdersByPom.put(sourcePath, true);
                                a.profileScopeDetailsByPom.computeIfAbsent(sourcePath, k -> new ArrayList<>()).add(scopeDetail);
                            }
                        }

                        // Only track provider deps when:
                        // - In profile dependencies (always track to avoid duplicates)
                        // - In main dependencies AND in fallback mode (trackMainProviderDeps=true)
                        // Only track compile/runtime scope (or default=compile)
                        if (groupId != null && artifactId != null && isRuntimeScope(scope)) {
                            if (inProfileDependencies || trackMainProviderDeps) {
                                checkAndTrackDependency(groupId, artifactId, a);
                            }
                        }
                    }
                }

                return super.visitTag(tag, a);
            }
        }.visit(doc, acc);
    }

    /**
     * Tracks provider dependency artifacts.
     */
    private void checkAndTrackDependency(String groupId, String artifactId, Accumulator acc) {
        // Track individual Jersey artifacts
        if ("org.glassfish.jersey.core".equals(groupId) && "jersey-client".equals(artifactId)) {
            acc.hasJerseyClient = true;
        }
        if ("org.glassfish.jersey.inject".equals(groupId) && "jersey-hk2".equals(artifactId)) {
            acc.hasJerseyHk2 = true;
        }
        if ("org.glassfish.jersey.media".equals(groupId) && "jersey-media-json-binding".equals(artifactId)) {
            acc.hasJerseyJsonBinding = true;
        }

        // Track individual RESTEasy artifacts
        if ("org.jboss.resteasy".equals(groupId) && "resteasy-client".equals(artifactId)) {
            acc.hasResteasyClient = true;
        }
        if ("org.jboss.resteasy".equals(groupId) && "resteasy-json-binding-provider".equals(artifactId)) {
            acc.hasResteasyJsonBinding = true;
        }

        // Track CXF client
        if ("org.apache.cxf".equals(groupId) && "cxf-rt-rs-client".equals(artifactId)) {
            acc.hasCxfClient = true;
        }
    }

    /**
     * Checks if scope is compile/runtime (or empty = compile by default).
     * test/provided/system scoped deps should not count as "existing" for runtime needs.
     * Property placeholders treated as non-runtime (skip dependency tracking).
     * This ensures we don't falsely assume deps exist when they might be test/provided.
     */
    private boolean isRuntimeScope(String scope) {
        if (scope == null || scope.isEmpty()) {
            return true; // default scope = compile
        }
        String trimmed = scope.trim();
        // Property placeholders are unknown - treat as non-runtime
        // If placeholder resolves to test/provided, we shouldn't count it as existing runtime dep
        // Better to add a potential duplicate than miss a required runtime dep
        if (trimmed.contains("${")) {
            return false;
        }
        return "compile".equals(trimmed) || "runtime".equals(trimmed);
    }

    private String getChildValue(Xml.Tag tag, String childName) {
        return tag.getChildren().stream()
            .filter(c -> c instanceof Xml.Tag && childName.equals(((Xml.Tag) c).getName()))
            .map(c -> ((Xml.Tag) c).getValue().orElse(null))
            .findFirst()
            .orElse(null);
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        // No file generation needed
        return Collections.emptyList();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // No JAX-RS Client usage detected - nothing to do
        if (acc.classesUsingJaxRsClient.isEmpty()) {
            return TreeVisitor.noop();
        }

        return new TreeVisitor<Tree, ExecutionContext>() {
            private boolean configWarningEmitted = false;
            // Track POMs that have had warnings emitted
            private final Set<String> pomsWithWarningsEmitted = new HashSet<>();

            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree == null) {
                    return null;
                }

                // Handle Java files - mark classes for manual strategy
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    String sourcePath = cu.getSourcePath().toString().replace('\\', '/');
                    ProjectConfiguration.JaxRsClientStrategy effectiveStrategy =
                        resolveClientStrategy(cu.getSourcePath(), acc);
                    emitConfigWarningsIfNeeded(acc, ctx);

                    if ((effectiveStrategy == ProjectConfiguration.JaxRsClientStrategy.MANUAL ||
                        effectiveStrategy == ProjectConfiguration.JaxRsClientStrategy.MIGRATE_RESTCLIENT ||
                        effectiveStrategy == ProjectConfiguration.JaxRsClientStrategy.MIGRATE_WEBCLIENT) &&
                        acc.filesWithJaxRsClient.stream()
                            .map(p -> p.replace('\\', '/'))
                            .anyMatch(p -> p.equals(sourcePath))) {
                        return new JaxRsClientManualMigrationVisitor(acc, effectiveStrategy).visit(cu, ctx);
                    }
                }

                // Handle Maven POM - add dependencies for keep-jaxrs strategy
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    String sourcePath = doc.getSourcePath().toString();

                    if (sourcePath.endsWith("pom.xml")) {
                        ProjectConfiguration.JaxRsClientStrategy effectiveStrategy =
                            resolveClientStrategy(doc.getSourcePath(), acc);
                        emitConfigWarningsIfNeeded(acc, ctx);
                        if (effectiveStrategy != ProjectConfiguration.JaxRsClientStrategy.KEEP_JAXRS) {
                            return doc;
                        }

                        String effectiveProvider = resolveProvider(doc.getSourcePath(), acc);
                        String effectiveVersion = resolveProviderVersion(doc.getSourcePath(), effectiveProvider);

                        // Emit per-POM warnings
                        if (!pomsWithWarningsEmitted.contains(sourcePath)) {
                            emitPerPomPlaceholderWarnings(sourcePath, acc, ctx);
                            pomsWithWarningsEmitted.add(sourcePath);
                        }

                        // Only block when MAIN dependencies have placeholders
                        // Profile-only placeholders should not prevent adding deps to main section
                        // Scope placeholders MUST block again to prevent duplicates
                        boolean thisPomsMainHasGavPlaceholder = acc.mainUnresolvedGavPlaceholdersByPom.getOrDefault(sourcePath, false);
                        boolean thisPomsMainHasScopePlaceholder = acc.mainUnresolvedScopePlaceholdersByPom.getOrDefault(sourcePath, false);
                        if (thisPomsMainHasGavPlaceholder || thisPomsMainHasScopePlaceholder) {
                            // Don't add provider deps - can't reliably identify existing deps in main section of THIS POM
                            return doc;
                        }
                        return addProviderDependencies(doc, acc, effectiveProvider, effectiveVersion, ctx);
                    }
                }

                return tree;
            }

            private void emitConfigWarningsIfNeeded(Accumulator acc, ExecutionContext ctx) {
                if (configWarningEmitted) {
                    return;
                }
                if (acc.strategyWarning != null) {
                    ctx.getOnError().accept(new IllegalArgumentException(acc.strategyWarning));
                }
                if (acc.providerWarning != null) {
                    ctx.getOnError().accept(new IllegalArgumentException(acc.providerWarning));
                }
                if (acc.strategyWarning != null || acc.providerWarning != null) {
                    configWarningEmitted = true;
                }
            }

            /**
             * Emit warnings per-POM for placeholder issues.
             * This provides clearer context for multi-module projects.
             * Uses pre-grouped details for efficiency (no repeated filtering).
             */
            private void emitPerPomPlaceholderWarnings(String sourcePath, Accumulator acc, ExecutionContext ctx) {
                boolean hasMainGavPlaceholder = acc.mainUnresolvedGavPlaceholdersByPom.getOrDefault(sourcePath, false);
                boolean hasProfileGavPlaceholder = acc.profileUnresolvedGavPlaceholdersByPom.getOrDefault(sourcePath, false);
                boolean hasMainScopePlaceholder = acc.mainUnresolvedScopePlaceholdersByPom.getOrDefault(sourcePath, false);
                boolean hasProfileScopePlaceholder = acc.profileUnresolvedScopePlaceholdersByPom.getOrDefault(sourcePath, false);

                // Emit GAV placeholder warning for this POM (using pre-grouped details)
                if (hasMainGavPlaceholder || hasProfileGavPlaceholder) {
                    List<String> mainGavDetails = acc.mainGavDetailsByPom.getOrDefault(sourcePath, Collections.emptyList());
                    List<String> profileGavDetails = acc.profileGavDetailsByPom.getOrDefault(sourcePath, Collections.emptyList());
                    // Only add section prefixes to details when both sections present (header is [main+profile])
                    boolean bothSections = hasMainGavPlaceholder && hasProfileGavPlaceholder;
                    String gavDetails = formatDetails(mainGavDetails, profileGavDetails, bothSections);

                    String section;
                    String action;
                    if (bothSections) {
                        section = "[main+profile]";
                        action = " Cannot reliably detect existing provider dependencies - skipping automatic dependency changes.";
                    } else if (hasMainGavPlaceholder) {
                        section = "[main]";
                        action = " Cannot reliably detect existing provider dependencies - skipping automatic dependency changes.";
                    } else {
                        section = "[profile]";
                        action = " Profile-only placeholders detected (informational - not blocking main dependency changes).";
                    }
                    ctx.getOnError().accept(new IllegalArgumentException(
                        "MigrateJaxRsClient " + section + " [" + sourcePath + "]: Dependency with unresolved property placeholders in groupId/artifactId detected." + gavDetails +
                        action + " Please resolve the placeholders or add provider dependencies manually."));
                }

                // Emit scope placeholder warning for this POM (using pre-grouped details)
                if (hasMainScopePlaceholder || hasProfileScopePlaceholder) {
                    List<String> mainScopeDetails = acc.mainScopeDetailsByPom.getOrDefault(sourcePath, Collections.emptyList());
                    List<String> profileScopeDetails = acc.profileScopeDetailsByPom.getOrDefault(sourcePath, Collections.emptyList());
                    // Only add section prefixes to details when both sections present (header is [main+profile])
                    boolean bothSections = hasMainScopePlaceholder && hasProfileScopePlaceholder;
                    String scopeDetails = formatDetails(mainScopeDetails, profileScopeDetails, bothSections);

                    String section;
                    String action;
                    if (bothSections) {
                        section = "[main+profile]";
                        action = " Cannot reliably determine scope - skipping automatic dependency changes.";
                    } else if (hasMainScopePlaceholder) {
                        section = "[main]";
                        action = " Cannot reliably determine scope - skipping automatic dependency changes.";
                    } else {
                        section = "[profile]";
                        action = " Profile-only placeholders detected (informational - not blocking main dependency changes).";
                    }
                    ctx.getOnError().accept(new IllegalArgumentException(
                        "MigrateJaxRsClient " + section + " [" + sourcePath + "]: Dependency with unresolved property placeholder in scope detected." + scopeDetails +
                        action + " Please resolve the scope placeholder or add provider dependencies manually."));
                }
            }

            /**
             * Format details from main and profile lists, capping at 5 total entries.
             * Only adds section prefixes when both sections are present (to distinguish sources).
             */
            private String formatDetails(List<String> mainDetails, List<String> profileDetails, boolean includePrefixes) {
                List<String> combined = new ArrayList<>();
                if (includePrefixes) {
                    mainDetails.forEach(d -> combined.add("[main] " + d));
                    profileDetails.forEach(d -> combined.add("[profile] " + d));
                } else {
                    combined.addAll(mainDetails);
                    combined.addAll(profileDetails);
                }
                if (combined.isEmpty()) {
                    return "";
                } else if (combined.size() <= 5) {
                    return " Affected: " + String.join(", ", combined);
                } else {
                    return " Affected: " + String.join(", ", combined.subList(0, 5)) +
                        " (and " + (combined.size() - 5) + " more)";
                }
            }
        };
    }

    private ProjectConfiguration.JaxRsClientStrategy resolveClientStrategy(@Nullable Path sourcePath, Accumulator acc) {
        ProjectConfiguration.JaxRsClientStrategy override =
            ProjectConfiguration.JaxRsClientStrategy.fromString(strategy);
        if (strategy != null && override == null && acc.strategyWarning == null) {
            acc.strategyWarning = "MigrateJaxRsClient: Unknown strategy '" + strategy + "', using project.yaml/defaults. " +
                "Valid strategies: " + VALID_STRATEGIES;
        }

        ProjectConfiguration.JaxRsClientStrategy resolved = override;
        if (resolved == null) {
            resolved = loadConfig(sourcePath).getJaxRsClientStrategy();
        }
        if (resolved == null) {
            resolved = ProjectConfiguration.JaxRsClientStrategy.KEEP_JAXRS;
        }

        return resolved;
    }

    private String resolveProvider(@Nullable Path sourcePath, Accumulator acc) {
        String effectiveProvider = provider;
        if (effectiveProvider == null || effectiveProvider.isBlank()) {
            effectiveProvider = loadConfig(sourcePath).getJaxRsClientProvider();
        }
        if (effectiveProvider == null || effectiveProvider.isBlank()) {
            effectiveProvider = "jersey";
        }
        effectiveProvider = effectiveProvider.toLowerCase();
        if (!VALID_PROVIDERS.contains(effectiveProvider)) {
            if (acc.providerWarning == null) {
                acc.providerWarning = "MigrateJaxRsClient: Unknown provider '" + effectiveProvider +
                    "', falling back to 'jersey'. Valid providers: " + VALID_PROVIDERS;
            }
            effectiveProvider = "jersey";
        }
        return effectiveProvider;
    }

    private String resolveProviderVersion(@Nullable Path sourcePath, String effectiveProvider) {
        String version = providerVersion;
        if (version == null || version.isBlank()) {
            version = loadConfig(sourcePath).getJaxRsClientProviderVersion();
        }
        if (version != null && !version.isBlank()) {
            return version;
        }
        return switch (effectiveProvider) {
            case "resteasy" -> DEFAULT_RESTEASY_VERSION;
            case "cxf" -> DEFAULT_CXF_VERSION;
            default -> DEFAULT_JERSEY_VERSION;
        };
    }

    private ProjectConfiguration loadConfig(@Nullable Path sourcePath) {
        if (sourcePath == null) {
            return ProjectConfiguration.mavenDefaults();
        }
        return ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(sourcePath));
    }

    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return null;
        }
        Path current = sourcePath.toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("build.gradle.kts")) ||
                Files.exists(current.resolve("settings.gradle")) ||
                Files.exists(current.resolve("settings.gradle.kts")) ||
                Files.exists(current.resolve("project.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return sourcePath.toAbsolutePath().getParent();
    }

    /**
     * Visitor that marks classes using JAX-RS Client with @NeedsReview and @Profile.
     * Only marks classes that actually use JAX-RS Client, not all classes in file.
     */
    private class JaxRsClientManualMigrationVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final Accumulator acc;
        private final String reason;
        private final String suggestedAction;

        JaxRsClientManualMigrationVisitor(Accumulator acc, ProjectConfiguration.JaxRsClientStrategy strategy) {
            this.acc = acc;
            if (strategy == ProjectConfiguration.JaxRsClientStrategy.MIGRATE_RESTCLIENT) {
                this.reason = "JAX-RS Client should be migrated to Spring RestClient";
                this.suggestedAction = "Replace ClientBuilder/Client with RestClient.builder()";
            } else if (strategy == ProjectConfiguration.JaxRsClientStrategy.MIGRATE_WEBCLIENT) {
                this.reason = "JAX-RS Client should be migrated to Spring WebClient";
                this.suggestedAction = "Replace ClientBuilder/Client with WebClient.builder()";
            } else {
                this.reason = "JAX-RS Client requires migration to Spring RestClient/WebClient";
                this.suggestedAction = "Replace ClientBuilder/Client with RestClient.builder() or WebClient.builder()";
            }
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Only mark classes that were detected as using JAX-RS Client
            if (cd.getType() == null) {
                return cd;
            }
            String classFqn = cd.getType().getFullyQualifiedName();
            String jaxRsUsage = acc.classesUsingJaxRsClient.get(classFqn);
            if (jaxRsUsage == null) {
                return cd;
            }

            // Check if class already has @NeedsReview
            boolean hasNeedsReview = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> "NeedsReview".equals(a.getSimpleName()));

            // Check if class already has @Profile
            boolean hasProfile = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> "Profile".equals(a.getSimpleName()));

            if (hasNeedsReview && hasProfile) {
                return cd;
            }

            List<J.Annotation> updatedAnnotations = new ArrayList<>(cd.getLeadingAnnotations());
            Space prefix = updatedAnnotations.isEmpty()
                ? cd.getPrefix()
                : updatedAnnotations.get(0).getPrefix();
            String indent = extractIndent(prefix);

            // Add @NeedsReview if not present
            if (!hasNeedsReview) {
                maybeAddImport(NEEDS_REVIEW_FQN);
                doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN + ".Category", "MANUAL_MIGRATION", false));

                J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                    reason,
                    "MANUAL_MIGRATION",
                    jaxRsUsage,
                    suggestedAction,
                    prefix
                );
                updatedAnnotations.add(0, needsReviewAnn);

                // Update prefix of the following annotation
                if (updatedAnnotations.size() > 1) {
                    J.Annotation second = updatedAnnotations.get(1);
                    updatedAnnotations.set(1, second.withPrefix(Space.format("\n" + indent)));
                }
            }

            // Add @Profile if not present
            if (!hasProfile) {
                doAfterVisit(new AddImport<>(PROFILE_FQN, null, false));

                // Find insertion point (after @NeedsReview if it exists)
                int insertIndex = 0;
                for (int i = 0; i < updatedAnnotations.size(); i++) {
                    if ("NeedsReview".equals(updatedAnnotations.get(i).getSimpleName())) {
                        insertIndex = i + 1;
                        break;
                    }
                }

                Space profilePrefix = insertIndex < updatedAnnotations.size()
                    ? updatedAnnotations.get(insertIndex).getPrefix()
                    : Space.format("\n" + indent);

                J.Annotation profileAnn = createProfileAnnotation(profilePrefix);
                updatedAnnotations.add(insertIndex, profileAnn);

                // Update prefix of the following annotation
                if (insertIndex + 1 < updatedAnnotations.size()) {
                    J.Annotation next = updatedAnnotations.get(insertIndex + 1);
                    updatedAnnotations.set(insertIndex + 1, next.withPrefix(Space.format("\n" + indent)));
                }
            }

            J.ClassDeclaration updatedClass = cd.withLeadingAnnotations(updatedAnnotations);

            // Handle case where class had no annotations before
            if (cd.getLeadingAnnotations().isEmpty()) {
                updatedClass = updatedClass.withPrefix(prefix);
                updatedClass = updateFirstModifierPrefix(updatedClass, indent);
            }

            return updatedClass;
        }

        private J.Annotation createNeedsReviewAnnotation(String reason, String category,
                                                          String originalCode, String suggestedAction,
                                                          Space prefix) {
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

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg(category));
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

        private J.Annotation createProfileAnnotation(Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(PROFILE_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Profile",
                type,
                null
            );

            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                PROFILE_NAME,
                "\"" + PROFILE_NAME + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(new JRightPadded<>(valueExpr, Space.EMPTY, Markers.EMPTY));

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

            String escapedValue = escapeJavaString(value);
            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                value,
                "\"" + escapedValue + "\"",
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

            J.Identifier valueExpr = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                categoryName,
                categoryType,
                null
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

        private String escapeJavaString(String value) {
            if (value == null) return "";
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }

        private String extractIndent(Space prefix) {
            String ws = prefix.getWhitespace();
            int lastNewline = ws.lastIndexOf('\n');
            if (lastNewline >= 0) {
                return ws.substring(lastNewline + 1);
            }
            return "";
        }

        private J.ClassDeclaration updateFirstModifierPrefix(J.ClassDeclaration classDecl, String indent) {
            List<J.Modifier> modifiers = classDecl.getModifiers();
            if (modifiers.isEmpty()) {
                return classDecl;
            }
            List<J.Modifier> updated = new ArrayList<>(modifiers);
            J.Modifier first = updated.get(0).withPrefix(Space.format("\n" + indent));
            updated.set(0, first);
            return classDecl.withModifiers(updated);
        }
    }

    /**
     * Adds JAX-RS provider dependencies to pom.xml for keep-jaxrs strategy.
     * Adds only missing individual artifacts, not skipping if groupId partially exists.
     */
    private Tree addProviderDependencies(Xml.Document doc, Accumulator acc, String provider,
                                          @Nullable String version, ExecutionContext ctx) {
        Tree result = doc;

        switch (provider) {
            case "resteasy":
                // Add only missing RESTEasy artifacts
                if (!acc.hasResteasyClient) {
                    result = new AddDependency(
                        "org.jboss.resteasy",
                        "resteasy-client",
                        version,
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(result, ctx);
                }
                if (!acc.hasResteasyJsonBinding) {
                    result = new AddDependency(
                        "org.jboss.resteasy",
                        "resteasy-json-binding-provider",
                        version,
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(result, ctx);
                }
                break;

            case "cxf":
                if (!acc.hasCxfClient) {
                    result = new AddDependency(
                        "org.apache.cxf",
                        "cxf-rt-rs-client",
                        version,
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(result, ctx);
                }
                break;

            case "jersey":
            default:
                // Add only missing Jersey artifacts
                if (!acc.hasJerseyClient) {
                    result = new AddDependency(
                        "org.glassfish.jersey.core",
                        "jersey-client",
                        version,
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(result, ctx);
                }
                if (!acc.hasJerseyHk2) {
                    result = new AddDependency(
                        "org.glassfish.jersey.inject",
                        "jersey-hk2",
                        version,
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(result, ctx);
                }
                if (!acc.hasJerseyJsonBinding) {
                    result = new AddDependency(
                        "org.glassfish.jersey.media",
                        "jersey-media-json-binding",
                        version,
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(result, ctx);
                }
                break;
        }

        return result;
    }
}
