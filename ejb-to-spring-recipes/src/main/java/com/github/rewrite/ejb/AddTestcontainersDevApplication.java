package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Generates a dev-time Spring Boot wrapper (test source set) that starts the app with Testcontainers.
 * <p>
 * The wrapper is generated in src/test/java and uses @ServiceConnection to auto-wire the container.
 * This enables "start the app and DB comes along" via the test runtime classpath.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddTestcontainersDevApplication extends ScanningRecipe<AddTestcontainersDevApplication.Accumulator> {

    @Option(displayName = "Application Class Name",
            description = "Name of the main Spring Boot application class (without .java). " +
                          "If not provided, the recipe tries to detect @SpringBootApplication or " +
                          "derives the name from the base package.",
            example = "CargoTrackerApplication",
            required = false)
    String applicationClassName;

    @Option(displayName = "Dev Wrapper Class Name",
            description = "Name of the dev wrapper main class (without .java). " +
                          "If not provided, defaults to 'Dev' + ApplicationClassName.",
            example = "DevCargoTrackerApplication",
            required = false)
    String devApplicationClassName;

    @Option(displayName = "Database Type",
            description = "Database type for Testcontainers: postgresql, mysql, mariadb. " +
                          "If not provided, the recipe tries to infer it from @DataSourceDefinition " +
                          "or Maven dependencies.",
            example = "postgresql",
            required = false)
    String databaseType;

    @Option(displayName = "Container Image",
            description = "Container image to use for Testcontainers. " +
                          "If not provided, a sensible default is chosen per database type.",
            example = "postgres:16-alpine",
            required = false)
    String containerImage;

    private static final String DEV_CONTAINERS_CONFIG = "DevContainersConfig";

    public AddTestcontainersDevApplication() {
        this.applicationClassName = null;
        this.devApplicationClassName = null;
        this.databaseType = null;
        this.containerImage = null;
    }

    public AddTestcontainersDevApplication(String applicationClassName,
                                           String devApplicationClassName,
                                           String databaseType,
                                           String containerImage) {
        this.applicationClassName = applicationClassName;
        this.devApplicationClassName = devApplicationClassName;
        this.databaseType = databaseType;
        this.containerImage = containerImage;
    }

    @Override
    public String getDisplayName() {
        return "Add Testcontainers dev wrapper application";
    }

    @Override
    public String getDescription() {
        return "Generates a dev-time Spring Boot wrapper (test source set) that starts the " +
               "application via Testcontainers using @ServiceConnection.";
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
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    String sourcePath = normalizePath(cu.getSourcePath());

                    // Load project configuration
                    ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                            extractProjectRoot(cu.getSourcePath()));

                    boolean isTestSource = isTestSource(sourcePath, config);
                    boolean isMainSource = isMainSource(sourcePath, config);
                    String modulePrefix = extractModulePrefix(sourcePath, config);

                    if (cu.getPackageDeclaration() != null) {
                        String pkg = cu.getPackageDeclaration().getPackageName();
                        if (isMainSource) {
                            acc.mainPackagesByModule
                                .computeIfAbsent(modulePrefix, key -> new HashSet<>())
                                .add(pkg);
                        }
                        acc.allPackagesByModule
                            .computeIfAbsent(modulePrefix, key -> new HashSet<>())
                            .add(pkg);
                    }

                    for (J.ClassDeclaration classDecl : cu.getClasses()) {
                        String simpleName = classDecl.getSimpleName();
                        if (isTestSource) {
                            acc.testClassNamesByModule
                                .computeIfAbsent(modulePrefix, key -> new HashSet<>())
                                .add(simpleName);
                            // Track the detected test source root for this module
                            if (!acc.testSourceRootByModule.containsKey(modulePrefix)) {
                                acc.testSourceRootByModule.put(modulePrefix,
                                        extractTestSourceRoot(sourcePath, config));
                            }
                        }

                        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                            if ("SpringBootApplication".equals(ann.getSimpleName())) {
                                acc.springBootApplicationClassName = simpleName;
                                acc.springBootApplicationModulePrefix = modulePrefix;
                                if (cu.getPackageDeclaration() != null) {
                                    acc.springBootApplicationPackage = cu.getPackageDeclaration().getPackageName();
                                }
                            }
                        }
                    }

                    for (J.Annotation ann : cu.getClasses().stream()
                            .flatMap(c -> c.getLeadingAnnotations().stream())
                            .toList()) {
                        if ("DataSourceDefinition".equals(ann.getSimpleName())) {
                            String detected = detectDbFromAnnotation(ann);
                            acc.recordDatabaseType(detected);
                        }
                    }
                }

                if (tree instanceof Xml.Document) {
                    Xml.Document doc = (Xml.Document) tree;
                    String sourcePath = normalizePath(doc.getSourcePath());
                    if (sourcePath.endsWith("pom.xml")) {
                        new MavenIsoVisitor<ExecutionContext>() {
                            @Override
                            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                                MavenResolutionResult mrr = getResolutionResult();
                                if (mrr != null) {
                                    for (ResolvedDependency dep : mrr.getDependencies().values().stream()
                                            .flatMap(List::stream).toList()) {
                                        String groupId = dep.getGroupId();
                                        if ("org.postgresql".equals(groupId)) {
                                            acc.recordDatabaseType("postgresql");
                                        } else if ("mysql".equals(groupId)) {
                                            acc.recordDatabaseType("mysql");
                                        } else if ("org.mariadb.jdbc".equals(groupId)) {
                                            acc.recordDatabaseType("mariadb");
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
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        String resolvedDbType = resolveDatabaseType(acc);
        ContainerSpec spec = ContainerSpec.forType(resolvedDbType);
        if (spec == null) {
            return Collections.emptyList();
        }

        String modulePrefix = resolveModulePrefix(acc);
        String basePackage = resolveBasePackage(acc, modulePrefix);
        if (basePackage == null) {
            return Collections.emptyList();
        }

        String appClassName = resolveApplicationClassName(acc, basePackage);
        if (appClassName == null) {
            return Collections.emptyList();
        }

        String devClassName = resolveDevClassName(appClassName);

        List<SourceFile> generated = new ArrayList<>();
        JavaParser parser = JavaParser.fromJavaVersion().build();

        // Use detected test source root or default to src/test/java
        String testSourceRoot = acc.testSourceRootByModule.getOrDefault(modulePrefix, "src/test/java");

        Set<String> testClassNames = acc.testClassNamesByModule.getOrDefault(modulePrefix, Collections.emptySet());
        if (!testClassNames.contains(DEV_CONTAINERS_CONFIG)) {
            String source = generateDevContainersConfigSource(basePackage, spec, resolveContainerImage(spec));
            Path filePath = Paths.get(modulePrefix + testSourceRoot + "/" + basePackage.replace('.', '/') + "/" +
                                      DEV_CONTAINERS_CONFIG + ".java");
            List<SourceFile> parsed = parser.parse(source).toList();
            if (!parsed.isEmpty()) {
                generated.add(parsed.get(0).withSourcePath(filePath));
            }
        }

        if (!testClassNames.contains(devClassName)) {
            String source = generateDevWrapperSource(basePackage, devClassName, appClassName);
            Path filePath = Paths.get(modulePrefix + testSourceRoot + "/" + basePackage.replace('.', '/') + "/" +
                                      devClassName + ".java");
            List<SourceFile> parsed = parser.parse(source).toList();
            if (!parsed.isEmpty()) {
                generated.add(parsed.get(0).withSourcePath(filePath));
            }
        }

        return generated;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        String resolvedDbType = resolveDatabaseType(acc);
        ContainerSpec spec = ContainerSpec.forType(resolvedDbType);
        if (spec == null) {
            return TreeVisitor.noop();
        }

        return new MavenIsoVisitor<ExecutionContext>() {
            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                if (!normalizePath(document.getSourcePath()).endsWith("pom.xml")) {
                    return document;
                }

                Xml.Document doc = document;
                doc = (Xml.Document) new AddDependency(
                    "org.springframework.boot",
                    "spring-boot-testcontainers",
                    "3.5.9",
                    "",
                    "test",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
                ).getVisitor().visit(doc, ctx);

                doc = (Xml.Document) new AddDependency(
                    "org.springframework.boot",
                    "spring-boot-test",
                    "3.5.9",
                    "",
                    "test",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
                ).getVisitor().visit(doc, ctx);

                doc = (Xml.Document) new AddDependency(
                    "org.testcontainers",
                    spec.artifactId,
                    "1.21.4",
                    "",
                    "test",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
                ).getVisitor().visit(doc, ctx);

                return doc;
            }
        };
    }

    private String resolveBasePackage(Accumulator acc, String modulePrefix) {
        if (acc.springBootApplicationPackage != null) {
            return acc.springBootApplicationPackage;
        }
        Set<String> packages = acc.mainPackagesByModule.get(modulePrefix);
        if (packages == null || packages.isEmpty()) {
            packages = acc.allPackagesByModule.get(modulePrefix);
        }
        if (packages.isEmpty()) {
            return null;
        }
        return packages.stream()
            .min(Comparator.comparingInt(String::length))
            .orElse(null);
    }

    private String resolveApplicationClassName(Accumulator acc, String basePackage) {
        if (applicationClassName != null && !applicationClassName.trim().isEmpty()) {
            return applicationClassName.trim();
        }
        if (acc.springBootApplicationClassName != null) {
            return acc.springBootApplicationClassName;
        }
        return deriveClassName(basePackage);
    }

    private String resolveDevClassName(String appClassName) {
        if (devApplicationClassName != null && !devApplicationClassName.trim().isEmpty()) {
            return devApplicationClassName.trim();
        }
        return "Dev" + appClassName;
    }

    private String resolveDatabaseType(Accumulator acc) {
        if (databaseType != null && !databaseType.trim().isEmpty()) {
            return normalizeDbType(databaseType);
        }
        if (acc.detectedDatabaseType != null && !"conflict".equals(acc.detectedDatabaseType)) {
            return acc.detectedDatabaseType;
        }
        return null;
    }

    private String resolveModulePrefix(Accumulator acc) {
        if (acc.springBootApplicationModulePrefix != null) {
            return acc.springBootApplicationModulePrefix;
        }
        if (!acc.mainPackagesByModule.isEmpty()) {
            return acc.mainPackagesByModule.keySet().stream().sorted().findFirst().orElse("");
        }
        if (!acc.allPackagesByModule.isEmpty()) {
            return acc.allPackagesByModule.keySet().stream().sorted().findFirst().orElse("");
        }
        return "";
    }

    private String resolveContainerImage(ContainerSpec spec) {
        if (containerImage != null && !containerImage.trim().isEmpty()) {
            return containerImage.trim();
        }
        return spec.defaultImage;
    }

    private String deriveClassName(String basePackage) {
        String[] parts = basePackage.split("\\.");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            return capitalize(lastPart) + "Application";
        }
        return "Application";
    }

    private String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private String generateDevContainersConfigSource(String basePackage, ContainerSpec spec, String image) {
        return String.format("""
            package %s;

            import org.springframework.boot.test.context.TestConfiguration;
            import org.springframework.context.annotation.Bean;
            import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
            import %s;

            @TestConfiguration(proxyBeanMethods = false)
            public class %s {

                @Bean
                @ServiceConnection
                %s<?> %s() {
                    return new %s<>("%s");
                }
            }
            """, basePackage, spec.containerImport, DEV_CONTAINERS_CONFIG,
            spec.containerClass, spec.beanMethodName, spec.containerClass, image);
    }

    private String generateDevWrapperSource(String basePackage, String devClassName, String appClassName) {
        return String.format("""
            package %s;

            import org.springframework.boot.SpringApplication;

            public class %s {

                public static void main(String[] args) {
                    SpringApplication.from(%s::main)
                        .with(%s.class)
                        .run(args);
                }
            }
            """, basePackage, devClassName, appClassName, DEV_CONTAINERS_CONFIG);
    }

    private static boolean isTestSource(String sourcePath, ProjectConfiguration config) {
        return config.isTestSource(sourcePath);
    }

    private static boolean isMainSource(String sourcePath, ProjectConfiguration config) {
        return config.isMainSource(sourcePath);
    }

    private static String extractModulePrefix(String sourcePath, ProjectConfiguration config) {
        if (sourcePath == null) {
            return "";
        }
        // Try to find any source root in the path
        for (String root : config.getTestSourceRoots()) {
            int idx = sourcePath.indexOf("/" + root + "/");
            if (idx >= 0) {
                return sourcePath.substring(0, idx + 1);
            }
            if (sourcePath.startsWith(root + "/")) {
                return "";
            }
        }
        for (String root : config.getMainSourceRoots()) {
            int idx = sourcePath.indexOf("/" + root + "/");
            if (idx >= 0) {
                return sourcePath.substring(0, idx + 1);
            }
            if (sourcePath.startsWith(root + "/")) {
                return "";
            }
        }
        // Fallback to old behavior
        int idx = sourcePath.indexOf("/src/");
        if (idx <= 0) {
            return "";
        }
        return sourcePath.substring(0, idx + 1);
    }

    private static String extractTestSourceRoot(String sourcePath, ProjectConfiguration config) {
        if (sourcePath == null) {
            return "src/test/java";
        }
        for (String root : config.getTestSourceRoots()) {
            if (sourcePath.contains("/" + root + "/") || sourcePath.startsWith(root + "/")) {
                return root;
            }
        }
        return "src/test/java";
    }

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

    private static String normalizePath(Path path) {
        if (path == null) {
            return "";
        }
        return path.toString().replace('\\', '/');
    }

    private String detectDbFromAnnotation(J.Annotation ann) {
        if (ann.getArguments() == null) {
            return null;
        }
        for (Expression arg : ann.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) arg;
                Expression value = assignment.getAssignment();
                if (value instanceof J.Literal) {
                    J.Literal literal = (J.Literal) value;
                    if (literal.getValue() instanceof String) {
                        String detected = detectDbFromString((String) literal.getValue());
                        if (detected != null) {
                            return detected;
                        }
                    }
                }
            }
        }
        return null;
    }

    private String detectDbFromString(String value) {
        if (value == null) {
            return null;
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        if (lowered.contains("postgresql") || lowered.contains("pgxads") || lowered.contains("pgxa")) {
            return "postgresql";
        }
        if (lowered.contains("mariadb")) {
            return "mariadb";
        }
        if (lowered.contains("mysql")) {
            return "mysql";
        }
        return null;
    }

    private static String normalizeDbType(String value) {
        String lowered = value.trim().toLowerCase(Locale.ROOT);
        if ("postgres".equals(lowered)) {
            return "postgresql";
        }
        return lowered;
    }

    static class Accumulator {
        final Map<String, Set<String>> mainPackagesByModule = new HashMap<>();
        final Map<String, Set<String>> allPackagesByModule = new HashMap<>();
        final Map<String, Set<String>> testClassNamesByModule = new HashMap<>();
        final Map<String, String> testSourceRootByModule = new HashMap<>();
        String springBootApplicationClassName;
        String springBootApplicationPackage;
        String springBootApplicationModulePrefix;
        String detectedDatabaseType;

        void recordDatabaseType(String detected) {
            if (detected == null) {
                return;
            }
            if (detectedDatabaseType == null) {
                detectedDatabaseType = detected;
            } else if (!detectedDatabaseType.equals(detected)) {
                detectedDatabaseType = "conflict";
            }
        }
    }

    static class ContainerSpec {
        final String type;
        final String containerClass;
        final String containerImport;
        final String artifactId;
        final String defaultImage;
        final String beanMethodName;

        ContainerSpec(String type,
                      String containerClass,
                      String containerImport,
                      String artifactId,
                      String defaultImage,
                      String beanMethodName) {
            this.type = type;
            this.containerClass = containerClass;
            this.containerImport = containerImport;
            this.artifactId = artifactId;
            this.defaultImage = defaultImage;
            this.beanMethodName = beanMethodName;
        }

        static ContainerSpec forType(String type) {
            if (type == null) {
                return null;
            }
            switch (type) {
                case "postgresql":
                    return new ContainerSpec(
                        "postgresql",
                        "PostgreSQLContainer",
                        "org.testcontainers.containers.PostgreSQLContainer",
                        "postgresql",
                        "postgres:16-alpine",
                        "postgres");
                case "mysql":
                    return new ContainerSpec(
                        "mysql",
                        "MySQLContainer",
                        "org.testcontainers.containers.MySQLContainer",
                        "mysql",
                        "mysql:8.4",
                        "mysql");
                case "mariadb":
                    return new ContainerSpec(
                        "mariadb",
                        "MariaDBContainer",
                        "org.testcontainers.containers.MariaDBContainer",
                        "mariadb",
                        "mariadb:11.4",
                        "mariadb");
                default:
                    return null;
            }
        }
    }
}
