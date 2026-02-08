package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.maven.AddManagedDependency;
import org.openrewrite.maven.AddProperty;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Adds Spring Boot dependencies to Maven POM files based on detected EJB features.
 * <p>
 * This recipe performs a feature-based analysis:
 * <ul>
 *   <li>Scans Java files for EJB/Jakarta EE feature usage</li>
 *   <li>Adds Spring Boot BOM to parent pom.xml dependencyManagement</li>
 *   <li>Adds spring-boot.version property</li>
 *   <li>Adds appropriate Spring Boot starters based on detected features</li>
 * </ul>
 * <p>
 * Feature detection and starter mapping:
 * <table border="1">
 *   <tr><th>Feature</th><th>Detection</th><th>Dependency</th></tr>
 *   <tr><td>Basic</td><td>@Service, @Autowired</td><td>spring-boot-starter</td></tr>
 *   <tr><td>Scheduling</td><td>@Scheduled, @Schedule</td><td>spring-boot-starter (included)</td></tr>
 *   <tr><td>JMS</td><td>@JmsListener, @MessageDriven</td><td>spring-boot-starter-artemis (default; provider-aware)</td></tr>
 *   <tr><td>JPA</td><td>@Entity, @PersistenceContext</td><td>spring-boot-starter-data-jpa</td></tr>
 *   <tr><td>JAX-RS/Web</td><td>@Path, @GET, @RestController</td><td>spring-boot-starter-web</td></tr>
 *   <tr><td>Scopes</td><td>@RequestScoped, @SessionScoped</td><td>spring-web (NOT starter-web)</td></tr>
 * </table>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddSpringBootDependencies extends ScanningRecipe<AddSpringBootDependencies.Accumulator> {

    private static final String DEFAULT_SPRING_BOOT_VERSION = "3.5.0";
    // Spring Framework version corresponding to Spring Boot 3.5.x
    private static final String DEFAULT_SPRING_FRAMEWORK_VERSION = "6.2.1";

    @Option(displayName = "Spring Boot Version",
            description = "The Spring Boot version to use for BOM and starters. " +
                          "Defaults to " + DEFAULT_SPRING_BOOT_VERSION + " if not specified.",
            example = "3.5.0",
            required = false)
    @Nullable
    String springBootVersion;

    public AddSpringBootDependencies() {
        this.springBootVersion = null;
    }

    public AddSpringBootDependencies(@Nullable String springBootVersion) {
        this.springBootVersion = springBootVersion;
    }

    private String getSpringBootVersion() {
        return springBootVersion != null ? springBootVersion : DEFAULT_SPRING_BOOT_VERSION;
    }

    private String getSpringFrameworkVersion() {
        // When using Spring Boot 3.5.x, Spring Framework 6.2.x is the matching version
        // The BOM will manage version alignment, but AddDependency requires a non-null version
        return DEFAULT_SPRING_FRAMEWORK_VERSION;
    }

    // Feature detection patterns - detect EJB/Jakarta EE annotations to determine needed starters

    // Basic EJB annotations → spring-boot-starter
    private static final Set<String> EJB_BASIC_ANNOTATIONS = Set.of(
        "jakarta.ejb.Singleton",
        "jakarta.ejb.Stateless",
        "jakarta.ejb.Stateful",
        "javax.ejb.Singleton",
        "javax.ejb.Stateless",
        "javax.ejb.Stateful"
    );

    // Scheduling annotations → spring-boot-starter (includes scheduling support)
    private static final Set<String> SCHEDULING_ANNOTATIONS = Set.of(
        "jakarta.ejb.Schedule",
        "jakarta.ejb.Timeout",
        "jakarta.ejb.TimerService",
        "javax.ejb.Schedule",
        "javax.ejb.Timeout",
        "javax.ejb.TimerService"
    );

    // JMS annotations → spring-boot-starter-artemis
    private static final Set<String> JMS_ANNOTATIONS = Set.of(
        "jakarta.ejb.MessageDriven",
        "jakarta.jms.JMSContext",
        "jakarta.jms.Queue",
        "jakarta.jms.Topic",
        "jakarta.jms.MessageListener",
        "javax.ejb.MessageDriven",
        "javax.jms.JMSContext",
        "javax.jms.Queue",
        "javax.jms.Topic"
    );

    // JPA annotations → spring-boot-starter-data-jpa
    private static final Set<String> JPA_ANNOTATIONS = Set.of(
        "jakarta.persistence.Entity",
        "jakarta.persistence.PersistenceContext",
        "jakarta.persistence.PersistenceUnit",
        "jakarta.persistence.EntityManager",
        "javax.persistence.Entity",
        "javax.persistence.PersistenceContext",
        "javax.persistence.PersistenceUnit"
    );

    // JAX-RS/Web annotations → spring-boot-starter-web
    // Also includes @Remote as it triggers REST controller generation
    private static final Set<String> WEB_ANNOTATIONS = Set.of(
        "jakarta.ws.rs.Path",
        "jakarta.ws.rs.GET",
        "jakarta.ws.rs.POST",
        "jakarta.ws.rs.PUT",
        "jakarta.ws.rs.DELETE",
        "jakarta.ejb.Remote",
        "javax.ws.rs.Path",
        "javax.ws.rs.GET",
        "javax.ws.rs.POST",
        "javax.ejb.Remote"
    );

    // CDI Scope annotations → spring-web (NOT spring-boot-starter-web)
    // These are detected both before migration (jakarta/javax) and after migration (Spring)
    // to ensure spring-web dependency is added regardless of recipe ordering
    private static final Set<String> SCOPE_ANNOTATIONS = Set.of(
        // CDI scope annotations (before migration)
        "jakarta.enterprise.context.RequestScoped",
        "jakarta.enterprise.context.SessionScoped",
        "javax.enterprise.context.RequestScoped",
        "javax.enterprise.context.SessionScoped",
        // Spring scope annotations (after ChangeType migration)
        "org.springframework.web.context.annotation.RequestScope",
        "org.springframework.web.context.annotation.SessionScope"
    );

    @Override
    public String getDisplayName() {
        return "Migrate Maven POM to Spring Boot";
    }

    @Override
    public String getDescription() {
        return "Adds Spring Boot dependencies to Maven POMs based on detected EJB/Jakarta EE features. " +
               "Adds spring-boot-dependencies BOM to parent pom.xml and appropriate starters to modules.";
    }

    static class Accumulator {
        boolean needsSpringBoot = false;
        boolean hasScheduling = false;
        boolean hasJms = false;
        boolean hasJpa = false;
        boolean hasWeb = false;
        boolean hasScopeAnnotations = false;  // Tracks RequestScoped/SessionScoped usage
        boolean hasSpringBootBom = false;

        // Track which modules need which starters (path -> features)
        Map<String, Set<String>> moduleFeatures = new HashMap<>();
        Map<String, ProjectConfiguration.JmsProvider> moduleJmsProviders = new HashMap<>();
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
                // Scan Java files for feature usage
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    String modulePath = getModulePath(cu.getSourcePath().toString());
                    ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(cu.getSourcePath()));
                    acc.moduleJmsProviders.putIfAbsent(modulePath, config.getJmsProvider());

                    Set<String> features = acc.moduleFeatures.computeIfAbsent(modulePath, k -> new HashSet<>());

                    for (J.Import imp : cu.getImports()) {
                        String importPath = imp.getQualid().toString();

                        // Check for basic EJB annotations (@Singleton, @Stateless, @Stateful)
                        if (EJB_BASIC_ANNOTATIONS.contains(importPath)) {
                            acc.needsSpringBoot = true;
                            features.add("basic");
                        }

                        // Check scheduling
                        if (SCHEDULING_ANNOTATIONS.contains(importPath)) {
                            acc.hasScheduling = true;
                            acc.needsSpringBoot = true;
                            features.add("scheduling");
                        }

                        // Check JMS
                        if (JMS_ANNOTATIONS.contains(importPath)) {
                            acc.hasJms = true;
                            acc.needsSpringBoot = true;
                            features.add("jms");
                        }

                        // Check JPA
                        if (JPA_ANNOTATIONS.contains(importPath)) {
                            acc.hasJpa = true;
                            acc.needsSpringBoot = true;
                            features.add("jpa");
                        }

                        // Check Web/JAX-RS
                        if (WEB_ANNOTATIONS.contains(importPath)) {
                            acc.hasWeb = true;
                            acc.needsSpringBoot = true;
                            features.add("web");
                        }

                        // Check CDI/Spring Scope annotations (RequestScoped, SessionScoped)
                        // These require spring-web (not spring-boot-starter-web)
                        if (SCOPE_ANNOTATIONS.contains(importPath)) {
                            acc.hasScopeAnnotations = true;
                            acc.needsSpringBoot = true;  // Ensures BOM is added
                            features.add("scopes");
                        }
                    }
                }

                // Check if Spring Boot BOM is already present
                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    String sourcePath = doc.getSourcePath().toString();
                    if (sourcePath.endsWith("pom.xml")) {
                        new MavenIsoVisitor<ExecutionContext>() {
                            @Override
                            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                                MavenResolutionResult mrr = getResolutionResult();
                                if (mrr != null) {
                                    for (ResolvedDependency dep : mrr.getDependencies().values().stream()
                                            .flatMap(List::stream).toList()) {
                                        String groupId = dep.getGroupId();
                                        String artifactId = dep.getArtifactId();

                                        if ("org.springframework.boot".equals(groupId) &&
                                            "spring-boot-dependencies".equals(artifactId)) {
                                            acc.hasSpringBootBom = true;
                                        }
                                    }
                                }
                                return document;
                            }
                        }.visit(doc, ctx);
                    }
                }

                return tree;
            }

            private String getModulePath(String sourcePath) {
                // Extract module path from source file path
                // e.g., "module-a/src/main/java/com/example/Foo.java" -> "module-a"
                int srcIndex = sourcePath.indexOf("/src/");
                if (srcIndex > 0) {
                    return sourcePath.substring(0, srcIndex);
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
        // Only apply if EJB features are detected
        if (!acc.needsSpringBoot) {
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

                // Determine if this is likely a parent POM (contains modules or is root pom.xml)
                boolean isParentPom = isParentPom(doc);
                String modulePath = getModulePath(sourcePath);
                Set<String> features = acc.moduleFeatures.getOrDefault(modulePath, Collections.emptySet());

                // 1. Add Spring Boot BOM to dependency management (root POM only)
                if (!acc.hasSpringBootBom) {
                    // AddManagedDependency: groupId, artifactId, version, scope, type,
                    // classifier, versionPattern, releasesOnly, onlyIfUsing, addToRootPom
                    // addToRootPom=true ensures BOM is only in root pom.xml, child POMs inherit it
                    // This prevents version drift from multiple BOM declarations
                    tree = new AddManagedDependency(
                        "org.springframework.boot",
                        "spring-boot-dependencies",
                        getSpringBootVersion(),
                        "import",
                        "pom",
                        null,  // classifier
                        null,  // versionPattern
                        null,  // releasesOnly
                        null,  // onlyIfUsing
                        true   // addToRootPom - only add to root pom.xml
                    ).getVisitor().visit(tree, ctx);
                }

                // 2. Add spring-boot-starter (base dependency)
                // AddDependency: groupId, artifactId, version, versionPattern, scope,
                // releasesOnly, onlyIfUsing, type, classifier, optional, familyPattern, acceptTransitive
                tree = new AddDependency(
                    "org.springframework.boot",
                    "spring-boot-starter",
                    getSpringBootVersion(),
                    null,  // versionPattern
                    null,  // scope
                    null,  // releasesOnly
                    null,  // onlyIfUsing
                    null,  // type
                    null,  // classifier
                    null,  // optional
                    null,  // familyPattern
                    null   // acceptTransitive
                ).getVisitor().visit(tree, ctx);

                // 3. Add feature-specific starters
                if (features.contains("jms") || acc.hasJms) {
                    ProjectConfiguration.JmsProvider provider =
                        acc.moduleJmsProviders.getOrDefault(modulePath, ProjectConfiguration.JmsProvider.NONE);
                    String jmsStarter = resolveJmsStarter(provider);
                    tree = new AddDependency(
                        "org.springframework.boot",
                        jmsStarter,
                        getSpringBootVersion(),
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(tree, ctx);
                }

                if (features.contains("jpa") || acc.hasJpa) {
                    tree = new AddDependency(
                        "org.springframework.boot",
                        "spring-boot-starter-data-jpa",
                        getSpringBootVersion(),
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(tree, ctx);
                }

                if (features.contains("web") || acc.hasWeb) {
                    tree = new AddDependency(
                        "org.springframework.boot",
                        "spring-boot-starter-web",
                        getSpringBootVersion(),
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(tree, ctx);
                }

                // Add spring-web for scope annotations (NOT spring-boot-starter-web)
                // RequestScope/SessionScope from org.springframework.web.context.annotation
                // require spring-web, not the full web starter
                if (features.contains("scopes") || acc.hasScopeAnnotations) {
                    // AddDependency requires a non-null version parameter
                    // The BOM will manage the actual version alignment
                    tree = new AddDependency(
                        "org.springframework",
                        "spring-web",
                        getSpringFrameworkVersion(),
                        null, null, null, null, null, null, null, null, null
                    ).getVisitor().visit(tree, ctx);
                }

                return tree;
            }

            private boolean isParentPom(Xml.Document doc) {
                // Check if the POM contains <modules> or <packaging>pom</packaging>
                // or if it's the root pom.xml
                String content = doc.printAll();
                return content.contains("<modules>") ||
                       content.contains("<packaging>pom</packaging>") ||
                       doc.getSourcePath().toString().equals("pom.xml");
            }

            private String getModulePath(String sourcePath) {
                // e.g., "module-a/pom.xml" -> "module-a"
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

    private static String resolveJmsStarter(ProjectConfiguration.JmsProvider provider) {
        if (provider == null) {
            return "spring-boot-starter-artemis";
        }
        switch (provider) {
            case ACTIVEMQ:
                return "spring-boot-starter-activemq";
            case ARTEMIS:
            case EMBEDDED:
            case NONE:
            default:
                return "spring-boot-starter-artemis";
        }
    }

    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = sourcePath.toAbsolutePath().getParent();
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
        return Paths.get(System.getProperty("user.dir"));
    }
}
