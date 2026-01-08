package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.maven.tree.Scope;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Migrates Jakarta WebSocket (JSR-356) to Spring Boot by generating ServerEndpointExporter config.
 * <p>
 * This recipe enables "Keep Mode" for WebSocket endpoints - the @ServerEndpoint classes remain unchanged,
 * but Spring Boot is configured to recognize and expose them via ServerEndpointExporter.
 * <p>
 * MKR-004 requirements:
 * <ul>
 *   <li>When @ServerEndpoint is detected, adds spring-boot-starter-websocket dependency</li>
 *   <li>Generates a @Configuration class with ServerEndpointExporter bean if not present</li>
 *   <li>Endpoint classes remain unchanged (no marker annotations)</li>
 * </ul>
 * <p>
 * Note: This approach uses the native JSR-356 container. Spring dependency injection in endpoints
 * requires a custom ServerEndpointConfig.Configurator or manual configuration.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateWebSocketToSpringConfig extends ScanningRecipe<MigrateWebSocketToSpringConfig.Accumulator> {

    private static final String SERVER_ENDPOINT_JAKARTA = "jakarta.websocket.server.ServerEndpoint";
    private static final String SERVER_ENDPOINT_JAVAX = "javax.websocket.server.ServerEndpoint";
    private static final String SERVER_ENDPOINT_EXPORTER = "org.springframework.web.socket.server.standard.ServerEndpointExporter";
    private static final String SPRING_BOOT_STARTER_WEBSOCKET = "spring-boot-starter-websocket";
    private static final String SPRING_BOOT_GROUP_ID = "org.springframework.boot";
    private static final String DEFAULT_SPRING_BOOT_VERSION = "3.5.0";

    @Override
    public String getDisplayName() {
        return "Migrate Jakarta WebSocket to Spring Boot ServerEndpointExporter";
    }

    @Override
    public String getDescription() {
        return "Generates Spring Boot configuration for Jakarta WebSocket (JSR-356) endpoints. " +
               "Adds spring-boot-starter-websocket dependency and creates ServerEndpointExporter bean. " +
               "Endpoint classes remain unchanged - they continue to use @ServerEndpoint annotations.";
    }

    static class Accumulator {
        Map<Path, ModuleState> modules = new HashMap<>();
    }

    static class ModuleState {
        final Path moduleRoot;
        List<ServerEndpointInfo> endpoints = new ArrayList<>();
        boolean hasExistingExporterConfig = false;
        boolean hasWebSocketDependency = false;
        String mainSourceRoot = null;
        String springBootApplicationPackage = null;
        Set<String> packages = new HashSet<>();
        Set<String> classFqns = new HashSet<>();

        ModuleState(Path moduleRoot) {
            this.moduleRoot = moduleRoot;
        }
    }

    static class ServerEndpointInfo {
        String className;
        String packageName;
        String fqn;
        String path;

        ServerEndpointInfo(String className, String packageName, String path) {
            this.className = className;
            this.packageName = packageName;
            this.fqn = packageName.isEmpty() ? className : packageName + "." + className;
            this.path = path;
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
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof J.CompilationUnit) {
                    return visitJavaSource((J.CompilationUnit) tree, ctx);
                } else if (tree instanceof Xml.Document) {
                    return visitPom((Xml.Document) tree, ctx);
                }
                return tree;
            }

            private Tree visitJavaSource(J.CompilationUnit cu, ExecutionContext ctx) {
                Path cuSourcePath = cu.getSourcePath();
                if (cuSourcePath == null) {
                    return cu;
                }

                Path moduleRoot = extractProjectRoot(cuSourcePath);
                ModuleState module = acc.modules.computeIfAbsent(moduleRoot, ModuleState::new);

                String sourcePath = cuSourcePath.toString();
                String normalizedPath = sourcePath.replace('\\', '/');
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(moduleRoot);

                // Skip test sources
                if (config.isTestSource(normalizedPath)) {
                    return cu;
                }

                String pkg = cu.getPackageDeclaration() != null
                    ? cu.getPackageDeclaration().getPackageName()
                    : "";
                module.packages.add(pkg);

                for (J.ClassDeclaration classDecl : cu.getClasses()) {
                    String fqn = pkg.isEmpty()
                        ? classDecl.getSimpleName()
                        : pkg + "." + classDecl.getSimpleName();
                    module.classFqns.add(fqn);

                    // Check for @SpringBootApplication
                    boolean isBootApp = classDecl.getLeadingAnnotations().stream()
                        .anyMatch(a -> "SpringBootApplication".equals(a.getSimpleName()));
                    if (isBootApp && module.springBootApplicationPackage == null) {
                        module.springBootApplicationPackage = pkg;
                    }

                    // Check for @ServerEndpoint
                    for (J.Annotation annotation : classDecl.getLeadingAnnotations()) {
                        if (isServerEndpointAnnotation(annotation)) {
                            String path = extractEndpointPath(annotation);
                            ServerEndpointInfo info = new ServerEndpointInfo(classDecl.getSimpleName(), pkg, path);
                            module.endpoints.add(info);

                            if (module.mainSourceRoot == null) {
                                module.mainSourceRoot = extractMainSourceRoot(normalizedPath, config);
                            }
                        }
                    }

                    // Check for existing ServerEndpointExporter configuration
                    if (isExistingExporterConfiguration(classDecl)) {
                        module.hasExistingExporterConfig = true;
                    }
                }

                return cu;
            }

            private Tree visitPom(Xml.Document doc, ExecutionContext ctx) {
                String sourcePath = doc.getSourcePath().toString().replace('\\', '/');
                if (!sourcePath.endsWith("pom.xml")) {
                    return doc;
                }

                Path moduleRoot = extractProjectRoot(doc.getSourcePath());
                ModuleState module = acc.modules.computeIfAbsent(moduleRoot, ModuleState::new);

                // Check for existing websocket dependency using MavenResolutionResult
                final boolean[] mrrAvailable = {false};
                new MavenIsoVisitor<ExecutionContext>() {
                    @Override
                    public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                        MavenResolutionResult mrr = getResolutionResult();
                        if (mrr != null && mrr.getPom() != null) {
                            mrrAvailable[0] = true;
                            // Check resolved dependencies
                            for (Scope scope : List.of(Scope.Compile, Scope.Runtime)) {
                                List<ResolvedDependency> deps = mrr.getDependencies().get(scope);
                                if (deps != null) {
                                    for (ResolvedDependency resolved : deps) {
                                        if (SPRING_BOOT_GROUP_ID.equals(resolved.getGroupId()) &&
                                            SPRING_BOOT_STARTER_WEBSOCKET.equals(resolved.getArtifactId())) {
                                            module.hasWebSocketDependency = true;
                                        }
                                    }
                                }
                            }
                        }
                        return document;
                    }
                }.visit(doc, ctx);

                // Fallback: XML structure-based check (only <dependencies>, not <dependencyManagement>)
                if (!mrrAvailable[0]) {
                    if (containsDependencyInDirectSection(doc, SPRING_BOOT_GROUP_ID, SPRING_BOOT_STARTER_WEBSOCKET)) {
                        module.hasWebSocketDependency = true;
                    }
                }

                return doc;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof Xml.Document) {
                    return visitPomFile((Xml.Document) tree, ctx);
                }
                return tree;
            }

            private Tree visitPomFile(Xml.Document document, ExecutionContext ctx) {
                String sourcePath = document.getSourcePath().toString().replace('\\', '/');
                if (!sourcePath.endsWith("pom.xml")) {
                    return document;
                }

                Path moduleRoot = extractProjectRoot(document.getSourcePath());
                ModuleState module = acc.modules.get(moduleRoot);
                if (module == null || module.endpoints.isEmpty()) {
                    return document;
                }

                // Skip if dependency already exists
                if (module.hasWebSocketDependency) {
                    return document;
                }

                // Add spring-boot-starter-websocket dependency
                Xml.Document doc = document;
                doc = (Xml.Document) new AddDependency(
                    SPRING_BOOT_GROUP_ID,
                    SPRING_BOOT_STARTER_WEBSOCKET,
                    DEFAULT_SPRING_BOOT_VERSION,
                    null, null, null, null, null, null, null, null, null
                ).getVisitor().visit(doc, ctx);

                return doc;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (acc.modules.isEmpty()) {
            return Collections.emptyList();
        }

        JavaParser javaParser = JavaParser.fromJavaVersion().build();
        List<SourceFile> generated = new ArrayList<>();

        for (ModuleState module : acc.modules.values()) {
            // Skip if no endpoints or config already exists
            if (module.endpoints.isEmpty() || module.hasExistingExporterConfig) {
                continue;
            }

            // Sort endpoints for deterministic ordering
            module.endpoints.sort(Comparator.comparing(e -> e.fqn));

            // Determine base package
            String basePackage = module.springBootApplicationPackage != null
                ? module.springBootApplicationPackage
                : findCommonPackagePrefix(module.packages);
            if (basePackage == null) {
                basePackage = "";
            }

            // Generate configuration class
            String className = deriveConfigurationClassName(basePackage, module.classFqns);
            String source = generateConfigurationSource(basePackage, className, module.endpoints);

            String mainSourceRoot = module.mainSourceRoot != null ? module.mainSourceRoot : "src/main/java";
            String relativePath = basePackage.isEmpty()
                ? className + ".java"
                : basePackage.replace('.', '/') + "/" + className + ".java";
            Path filePath = Paths.get(mainSourceRoot + "/" + relativePath);

            List<SourceFile> parsed = javaParser.parse(source).toList();
            if (!parsed.isEmpty()) {
                generated.add(parsed.get(0).withSourcePath(filePath));
            }
        }

        return generated;
    }

    private static boolean isServerEndpointAnnotation(J.Annotation annotation) {
        return TypeUtils.isOfClassType(annotation.getType(), SERVER_ENDPOINT_JAKARTA) ||
               TypeUtils.isOfClassType(annotation.getType(), SERVER_ENDPOINT_JAVAX) ||
               "ServerEndpoint".equals(annotation.getSimpleName());
    }

    private static String extractEndpointPath(J.Annotation annotation) {
        if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
            return "";
        }

        for (org.openrewrite.java.tree.Expression arg : annotation.getArguments()) {
            // Direct string value
            if (arg instanceof J.Literal) {
                Object value = ((J.Literal) arg).getValue();
                return value != null ? value.toString() : "";
            }
            // Named value attribute
            if (arg instanceof J.Assignment) {
                J.Assignment assign = (J.Assignment) arg;
                String attrName = assign.getVariable() instanceof J.Identifier
                    ? ((J.Identifier) assign.getVariable()).getSimpleName()
                    : "";
                if ("value".equals(attrName) && assign.getAssignment() instanceof J.Literal) {
                    Object value = ((J.Literal) assign.getAssignment()).getValue();
                    return value != null ? value.toString() : "";
                }
            }
        }
        return "";
    }

    private static boolean isExistingExporterConfiguration(J.ClassDeclaration classDecl) {
        // Check if class is a @Configuration (or meta-annotated like @SpringBootApplication) with ServerEndpointExporter bean
        // @SpringBootApplication and @SpringBootConfiguration are meta-annotated with @Configuration
        boolean hasConfigAnnotation = classDecl.getLeadingAnnotations().stream()
            .anyMatch(a -> {
                String simpleName = a.getSimpleName();
                return "Configuration".equals(simpleName) ||
                       "SpringBootApplication".equals(simpleName) ||
                       "SpringBootConfiguration".equals(simpleName);
            });
        if (!hasConfigAnnotation) {
            return false;
        }

        // Look for ServerEndpointExporter bean method
        if (classDecl.getBody() != null) {
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                    // Check return type
                    if (method.getReturnTypeExpression() != null) {
                        String returnType = method.getReturnTypeExpression().toString();
                        if (returnType.contains("ServerEndpointExporter")) {
                            return true;
                        }
                    }
                    // Check method body for new ServerEndpointExporter()
                    if (method.getBody() != null) {
                        // Use toString() to get textual representation
                        String bodyStr = method.getBody().toString();
                        if (bodyStr.contains("ServerEndpointExporter")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static String findCommonPackagePrefix(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "";
        }

        Iterator<String> iterator = packages.iterator();
        String[] prefixParts = splitPackage(iterator.next());
        int prefixLength = prefixParts.length;

        while (iterator.hasNext() && prefixLength > 0) {
            String[] parts = splitPackage(iterator.next());
            prefixLength = Math.min(prefixLength, parts.length);
            for (int i = 0; i < prefixLength; i++) {
                if (!prefixParts[i].equals(parts[i])) {
                    prefixLength = i;
                    break;
                }
            }
        }

        if (prefixLength == 0) {
            return "";
        }

        return String.join(".", Arrays.copyOf(prefixParts, prefixLength));
    }

    private static String[] splitPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) {
            return new String[0];
        }
        return pkg.split("\\.");
    }

    private static String deriveConfigurationClassName(String basePackage, Set<String> existingFqns) {
        String baseName = "WebSocketConfiguration";
        String candidate = baseName;
        int counter = 2;
        String prefix = basePackage.isEmpty() ? "" : basePackage + ".";
        while (existingFqns.contains(prefix + candidate)) {
            candidate = baseName + counter;
            counter++;
        }
        return candidate;
    }

    /**
     * Generates the Spring @Configuration class source code with ServerEndpointExporter.
     */
    private static String generateConfigurationSource(String basePackage, String className,
                                                       List<ServerEndpointInfo> endpoints) {
        StringBuilder source = new StringBuilder();

        // Package declaration
        if (!basePackage.isEmpty()) {
            source.append("package ").append(basePackage).append(";\n\n");
        }

        // Imports
        source.append("import org.springframework.context.annotation.Bean;\n");
        source.append("import org.springframework.context.annotation.Configuration;\n");
        source.append("import org.springframework.web.socket.server.standard.ServerEndpointExporter;\n");
        source.append("\n");

        // Javadoc with endpoint info
        source.append("/**\n");
        source.append(" * WebSocket configuration for JSR-356 endpoints.\n");
        source.append(" * <p>\n");
        source.append(" * This configuration enables Spring Boot to recognize @ServerEndpoint classes:\n");
        for (ServerEndpointInfo info : endpoints) {
            source.append(" * <li>").append(info.fqn);
            if (info.path != null && !info.path.isEmpty()) {
                source.append(" (").append(info.path).append(")");
            }
            source.append("</li>\n");
        }
        source.append(" * <p>\n");
        source.append(" * Note: For Spring dependency injection in endpoints, a custom\n");
        source.append(" * ServerEndpointConfig.Configurator may be required.\n");
        source.append(" */\n");

        // Class declaration
        source.append("@Configuration\n");
        source.append("public class ").append(className).append(" {\n\n");

        // ServerEndpointExporter bean
        source.append("    /**\n");
        source.append("     * Registers @ServerEndpoint annotated classes with the WebSocket container.\n");
        source.append("     * Required for embedded containers (Tomcat, Jetty, Undertow).\n");
        source.append("     * <p>\n");
        source.append("     * Note: Not needed for WAR deployments to external containers.\n");
        source.append("     */\n");
        source.append("    @Bean\n");
        source.append("    public ServerEndpointExporter serverEndpointExporter() {\n");
        source.append("        return new ServerEndpointExporter();\n");
        source.append("    }\n");

        source.append("}\n");
        return source.toString();
    }

    private static String extractMainSourceRoot(String sourcePath, ProjectConfiguration config) {
        if (sourcePath == null) {
            return "src/main/java";
        }
        String normalizedPath = sourcePath.replace('\\', '/');
        for (String root : config.getMainSourceRoots()) {
            String normalizedRoot = root.replace('\\', '/');
            String marker = "/" + normalizedRoot + "/";
            int markerIndex = normalizedPath.indexOf(marker);
            if (markerIndex >= 0) {
                return normalizedPath.substring(0, markerIndex + marker.length() - 1);
            }
            if (normalizedPath.startsWith(normalizedRoot + "/")) {
                return normalizedRoot;
            }
        }
        return "src/main/java";
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

    /**
     * Checks if a dependency exists in the direct <dependencies> section of a POM.
     * Does NOT match dependencies in <dependencyManagement> or comments.
     */
    private static boolean containsDependencyInDirectSection(Xml.Document doc, String groupId, String artifactId) {
        if (doc.getRoot() == null) {
            return false;
        }

        // Find direct <dependencies> element (not inside <dependencyManagement>)
        for (Xml.Tag child : doc.getRoot().getChildren()) {
            if ("dependencies".equals(child.getName())) {
                // Check each <dependency> element
                for (Xml.Tag dependency : child.getChildren()) {
                    if ("dependency".equals(dependency.getName())) {
                        String depGroupId = getChildValue(dependency, "groupId");
                        String depArtifactId = getChildValue(dependency, "artifactId");
                        if (groupId.equals(depGroupId) && artifactId.equals(depArtifactId)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Gets the text value of a child element.
     */
    private static String getChildValue(Xml.Tag parent, String childName) {
        for (Xml.Tag child : parent.getChildren()) {
            if (childName.equals(child.getName())) {
                return child.getValue().orElse("");
            }
        }
        return "";
    }
}
