/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JAX-RS server provider configuration (JRS-002)
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
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.tree.MavenResolutionResult;
import org.openrewrite.maven.tree.ResolvedDependency;
import org.openrewrite.maven.tree.Scope;
import org.openrewrite.xml.tree.Xml;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Generates JAX-RS server configuration for Spring Boot.
 * <p>
 * This recipe scans for @Path annotated classes and generates provider-specific
 * configuration (Jersey, RESTEasy, or CXF) with the appropriate dependencies.
 * <p>
 * Configuration via project.yaml:
 * <ul>
 *   <li>jaxrs.strategy: keep-jaxrs (required for this recipe to run)</li>
 *   <li>jaxrs.server.provider: jersey|resteasy|cxf (null = auto-detect)</li>
 *   <li>jaxrs.server.basePath: base path for endpoints (default: /api)</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class GenerateJaxRsServerConfig extends ScanningRecipe<GenerateJaxRsServerConfig.Accumulator> {

    private static final String PATH_JAVAX = "javax.ws.rs.Path";
    private static final String PATH_JAKARTA = "jakarta.ws.rs.Path";
    private static final String SPRING_BOOT_APP_FQN = "org.springframework.boot.autoconfigure.SpringBootApplication";

    // Provider dependencies - versions for Spring Boot 3.4.x
    private static final String JERSEY_GROUP = "org.springframework.boot";
    private static final String JERSEY_ARTIFACT = "spring-boot-starter-jersey";
    private static final String JERSEY_VERSION = "3.4.1";  // Spring Boot 3.4.1
    private static final String RESTEASY_GROUP = "org.jboss.resteasy";
    private static final String RESTEASY_ARTIFACT = "resteasy-servlet-spring-boot-starter";
    private static final String RESTEASY_VERSION = "6.3.0.Final";
    private static final String CXF_GROUP = "org.apache.cxf";
    private static final String CXF_ARTIFACT = "cxf-spring-boot-starter-jaxrs";
    private static final String CXF_VERSION = "4.1.4";

    static class Accumulator {
        Map<Path, ModuleState> modules = new HashMap<>();
    }

    static class ModuleState {
        final Path moduleRoot;
        List<ResourceInfo> resources = new ArrayList<>();
        boolean hasExistingConfig = false;
        Set<String> detectedProviders = new HashSet<>();  // all auto-detected providers
        String mainSourceRoot = null;
        String springBootApplicationPackage = null;
        Set<String> packages = new HashSet<>();
        Set<String> classFqns = new HashSet<>();

        ModuleState(Path moduleRoot) {
            this.moduleRoot = moduleRoot;
        }

        /**
         * Returns the single detected provider if exactly one, otherwise null.
         * Use this when no explicit provider is configured.
         */
        String getSingleDetectedProvider() {
            return detectedProviders.size() == 1 ? detectedProviders.iterator().next() : null;
        }

        /**
         * Returns true if multiple providers were detected, indicating ambiguity.
         */
        boolean hasMultipleProviders() {
            return detectedProviders.size() > 1;
        }
    }

    static class ResourceInfo {
        String className;
        String packageName;
        String fqn;
        String pathValue;  // @Path value

        ResourceInfo(String className, String packageName) {
            this.className = className;
            this.packageName = packageName;
            this.fqn = packageName.isEmpty() ? className : packageName + "." + className;
        }
    }

    @Override
    public String getDisplayName() {
        return "Generate JAX-RS server configuration for Spring Boot";
    }

    @Override
    public String getDescription() {
        return "Generates provider-specific JAX-RS server configuration (Jersey, RESTEasy, or CXF) " +
               "based on @Path annotated resources. Only runs when jaxrs.strategy=keep-jaxrs.";
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
                    return visitJavaFile((J.CompilationUnit) tree, ctx);
                } else if (tree instanceof Xml.Document) {
                    return visitPom((Xml.Document) tree, ctx);
                }
                return tree;
            }

            private Tree visitJavaFile(J.CompilationUnit cu, ExecutionContext ctx) {
                Path cuSourcePath = cu.getSourcePath();
                if (cuSourcePath == null) {
                    return cu;
                }

                Path moduleRoot = extractProjectRoot(cuSourcePath);
                ModuleState module = acc.modules.computeIfAbsent(moduleRoot, ModuleState::new);

                String sourcePath = cuSourcePath.toString();
                String normalizedPath = sourcePath.replace('\\', '/');
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(moduleRoot);

                // Only run for keep-jaxrs strategy
                if (config.getJaxRsStrategy() != ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS) {
                    return cu;
                }

                boolean isTestSource = config.isTestSource(normalizedPath);
                if (isTestSource) {
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
                    boolean isSpringBootApp = classDecl.getLeadingAnnotations().stream()
                        .anyMatch(GenerateJaxRsServerConfig::isSpringBootApplicationAnnotation);
                    if (isSpringBootApp) {
                        module.springBootApplicationPackage = pkg;
                        if (module.mainSourceRoot == null) {
                            module.mainSourceRoot = extractSourceRoot(normalizedPath);
                        }
                    }

                    // Check for @Path annotation
                    if (isPathAnnotated(classDecl)) {
                        ResourceInfo resource = new ResourceInfo(classDecl.getSimpleName(), pkg);
                        resource.pathValue = extractPathValue(classDecl);
                        module.resources.add(resource);

                        if (module.mainSourceRoot == null) {
                            module.mainSourceRoot = extractSourceRoot(normalizedPath);
                        }
                    }

                    // Check for existing config
                    if (isExistingJaxRsConfig(classDecl)) {
                        module.hasExistingConfig = true;
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

                // Detect existing provider from dependencies
                final boolean[] mrrAvailable = {false};
                new MavenIsoVisitor<ExecutionContext>() {
                    @Override
                    public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                        MavenResolutionResult mrr = getResolutionResult();
                        if (mrr != null && mrr.getPom() != null) {
                            mrrAvailable[0] = true;
                            // Check compile/runtime dependencies
                            for (Scope scope : List.of(Scope.Compile, Scope.Runtime)) {
                                List<ResolvedDependency> deps = mrr.getDependencies().get(scope);
                                if (deps != null) {
                                    for (ResolvedDependency resolved : deps) {
                                        detectProvider(resolved.getGroupId(), resolved.getArtifactId(), module);
                                    }
                                }
                            }
                            // Also check requested dependencies
                            for (org.openrewrite.maven.tree.Dependency dep : mrr.getPom().getRequestedDependencies()) {
                                if (isMainScope(dep.getScope())) {
                                    detectProvider(dep.getGroupId(), dep.getArtifactId(), module);
                                }
                            }
                        }
                        return document;
                    }

                    private boolean isMainScope(String scope) {
                        return scope == null || "compile".equalsIgnoreCase(scope) || "runtime".equalsIgnoreCase(scope);
                    }

                    private void detectProvider(String groupId, String artifactId, ModuleState module) {
                        if (JERSEY_GROUP.equals(groupId) && JERSEY_ARTIFACT.equals(artifactId)) {
                            module.detectedProviders.add("jersey");
                        }
                        if (RESTEASY_GROUP.equals(groupId) && RESTEASY_ARTIFACT.equals(artifactId)) {
                            module.detectedProviders.add("resteasy");
                        }
                        if (CXF_GROUP.equals(groupId) && CXF_ARTIFACT.equals(artifactId)) {
                            module.detectedProviders.add("cxf");
                        }
                    }
                }.visit(doc, ctx);

                // Fallback to string check
                if (!mrrAvailable[0]) {
                    String content = doc.printAll();
                    if (content.contains(JERSEY_ARTIFACT)) {
                        module.detectedProviders.add("jersey");
                    }
                    if (content.contains(RESTEASY_ARTIFACT)) {
                        module.detectedProviders.add("resteasy");
                    }
                    if (content.contains(CXF_ARTIFACT)) {
                        module.detectedProviders.add("cxf");
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
                if (module == null || module.resources.isEmpty() || module.hasExistingConfig) {
                    return document;
                }

                // Determine provider
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(moduleRoot);
                String provider = config.getJaxRsServerProvider();

                // If no explicit provider, check for auto-detected
                if (provider == null) {
                    // Check for multi-provider ambiguity
                    if (module.hasMultipleProviders()) {
                        // Multiple providers detected - add @NeedsReview comment, skip dependency
                        return addMultiProviderWarning(document, module.detectedProviders, ctx);
                    }
                    provider = module.getSingleDetectedProvider();
                }
                if (provider == null) {
                    provider = "jersey";  // Default
                }

                // Add appropriate dependency
                Xml.Document doc = document;
                switch (provider.toLowerCase()) {
                    case "jersey":
                        doc = (Xml.Document) new AddDependency(
                            JERSEY_GROUP, JERSEY_ARTIFACT, JERSEY_VERSION,
                            null, null, null, null, null, null, null, null, null
                        ).getVisitor().visit(doc, ctx);
                        break;
                    case "resteasy":
                        doc = (Xml.Document) new AddDependency(
                            RESTEASY_GROUP, RESTEASY_ARTIFACT, RESTEASY_VERSION,
                            null, null, null, null, null, null, null, null, null
                        ).getVisitor().visit(doc, ctx);
                        break;
                    case "cxf":
                        doc = (Xml.Document) new AddDependency(
                            CXF_GROUP, CXF_ARTIFACT, CXF_VERSION,
                            null, null, null, null, null, null, null, null, null
                        ).getVisitor().visit(doc, ctx);
                        break;
                }

                return doc;
            }

            /**
             * Adds a @NeedsReview comment when multiple JAX-RS providers are detected.
             */
            private Xml.Document addMultiProviderWarning(Xml.Document document, Set<String> providers, ExecutionContext ctx) {
                String providerList = String.join(", ", providers);
                final String markerText = "\n    @NeedsReview: Multiple JAX-RS Providers Detected\n" +
                    "    Found dependencies for: " + providerList + "\n" +
                    "    \n" +
                    "    Auto-generation skipped due to ambiguity. Please either:\n" +
                    "      1. Remove conflicting provider dependencies from POM\n" +
                    "      2. Explicitly configure the desired provider in project.yaml:\n" +
                    "         jaxrs:\n" +
                    "           server:\n" +
                    "             provider: jersey|resteasy|cxf\n" +
                    "    ";

                return new org.openrewrite.xml.XmlIsoVisitor<ExecutionContext>() {
                    @Override
                    public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext context) {
                        Xml.Tag t = super.visitTag(tag, context);
                        if ("project".equals(t.getName()) && t.getContent() != null) {
                            // Check if comment already exists
                            boolean hasComment = t.getContent().stream()
                                .anyMatch(c -> c instanceof Xml.Comment &&
                                    ((Xml.Comment) c).getText().contains("@NeedsReview: Multiple JAX-RS Providers"));
                            if (!hasComment) {
                                // Create comment and prepend to content
                                Xml.Comment comment = new Xml.Comment(
                                    Tree.randomId(),
                                    "\n    ",
                                    org.openrewrite.marker.Markers.EMPTY,
                                    markerText
                                );
                                List<org.openrewrite.xml.tree.Content> newContent = new ArrayList<>();
                                newContent.add(comment);
                                newContent.addAll(t.getContent());
                                return t.withContent(newContent);
                            }
                        }
                        return t;
                    }
                }.visitDocument(document, ctx);
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
            if (module.resources.isEmpty() || module.hasExistingConfig) {
                continue;
            }

            // Sort resources for deterministic output
            module.resources.sort(Comparator.comparing(r -> r.fqn));

            // Determine base package
            String basePackage = module.springBootApplicationPackage != null
                ? module.springBootApplicationPackage
                : findCommonPackagePrefix(module.packages);
            if (basePackage == null) {
                basePackage = "";
            }

            // Load configuration
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(module.moduleRoot);
            String provider = config.getJaxRsServerProvider();

            // If no explicit provider, check for auto-detected
            if (provider == null) {
                // Skip generation if multiple providers detected (ambiguity)
                if (module.hasMultipleProviders()) {
                    continue;  // POM already has @NeedsReview comment from visitor
                }
                provider = module.getSingleDetectedProvider();
            }
            if (provider == null) {
                provider = "jersey";
            }
            String basePath = config.getJaxRsServerBasePath();

            // Generate configuration class based on provider
            String configSource = generateConfigSource(provider, basePackage, module.resources, basePath);

            // Determine output path
            String sourceRoot = module.mainSourceRoot != null ? module.mainSourceRoot : "src/main/java";
            String packagePath = basePackage.replace('.', '/');
            String className = getConfigClassName(provider);
            Path configPath = module.moduleRoot.resolve(sourceRoot)
                .resolve(packagePath)
                .resolve(className + ".java");

            // Check for name collision
            String finalClassName = className;
            if (module.classFqns.contains(basePackage.isEmpty() ? className : basePackage + "." + className)) {
                finalClassName = className + "2";
                configPath = module.moduleRoot.resolve(sourceRoot)
                    .resolve(packagePath)
                    .resolve(finalClassName + ".java");
                configSource = configSource.replace("class " + className, "class " + finalClassName);
            }

            List<SourceFile> parsed = javaParser.parse(configSource).toList();
            if (!parsed.isEmpty()) {
                SourceFile sf = parsed.get(0).withSourcePath(
                    module.moduleRoot.relativize(configPath)
                );
                generated.add(sf);
            }
        }

        return generated;
    }

    private String getConfigClassName(String provider) {
        return switch (provider.toLowerCase()) {
            case "jersey" -> "JerseyConfiguration";
            case "resteasy" -> "ResteasyConfiguration";
            case "cxf" -> "CxfJaxrsConfiguration";
            default -> "JaxrsConfiguration";
        };
    }

    private String generateConfigSource(String provider, String basePackage, List<ResourceInfo> resources, String basePath) {
        StringBuilder sb = new StringBuilder();

        // Package
        if (!basePackage.isEmpty()) {
            sb.append("package ").append(basePackage).append(";\n\n");
        }

        // Generate provider-specific configuration
        return switch (provider.toLowerCase()) {
            case "jersey" -> generateJerseyConfig(sb, basePackage, resources, basePath);
            case "resteasy" -> generateResteasyConfig(sb, basePackage, resources, basePath);
            case "cxf" -> generateCxfConfig(sb, basePackage, resources, basePath);
            default -> generateJerseyConfig(sb, basePackage, resources, basePath);
        };
    }

    private String generateJerseyConfig(StringBuilder sb, String basePackage, List<ResourceInfo> resources, String basePath) {
        // Imports
        sb.append("import jakarta.ws.rs.ApplicationPath;\n");
        sb.append("import org.glassfish.jersey.server.ResourceConfig;\n");
        sb.append("import org.springframework.stereotype.Component;\n\n");

        // Class as ResourceConfig subclass with @ApplicationPath for basePath support
        sb.append("@Component\n");
        sb.append("@ApplicationPath(\"").append(basePath).append("\")\n");
        sb.append("public class JerseyConfiguration extends ResourceConfig {\n\n");

        // Constructor registers all resources
        sb.append("    public JerseyConfiguration() {\n");
        for (ResourceInfo resource : resources) {
            sb.append("        register(").append(resource.fqn).append(".class);\n");
        }
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String generateResteasyConfig(StringBuilder sb, String basePackage, List<ResourceInfo> resources, String basePath) {
        // Imports
        sb.append("import jakarta.ws.rs.ApplicationPath;\n");
        sb.append("import jakarta.ws.rs.core.Application;\n");
        sb.append("import java.util.Set;\n\n");

        // Class with @ApplicationPath
        sb.append("@ApplicationPath(\"").append(basePath).append("\")\n");
        sb.append("public class ResteasyConfiguration extends Application {\n\n");

        // getClasses method
        sb.append("    @Override\n");
        sb.append("    public Set<Class<?>> getClasses() {\n");
        sb.append("        return Set.of(\n");
        for (int i = 0; i < resources.size(); i++) {
            sb.append("            ").append(resources.get(i).fqn).append(".class");
            if (i < resources.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("        );\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String generateCxfConfig(StringBuilder sb, String basePackage, List<ResourceInfo> resources, String basePath) {
        // Imports
        sb.append("import org.apache.cxf.Bus;\n");
        sb.append("import org.apache.cxf.endpoint.Server;\n");
        sb.append("import org.apache.cxf.jaxrs.JAXRSServerFactoryBean;\n");
        sb.append("import org.springframework.context.annotation.Bean;\n");
        sb.append("import org.springframework.context.annotation.Configuration;\n");
        sb.append("import java.util.List;\n\n");

        // Class
        sb.append("@Configuration\n");
        sb.append("public class CxfJaxrsConfiguration {\n\n");

        // Server bean
        sb.append("    @Bean\n");
        sb.append("    public Server jaxrsServer(Bus bus");
        for (ResourceInfo resource : resources) {
            sb.append(", ").append(resource.fqn).append(" ").append(toLowerCamelCase(resource.className));
        }
        sb.append(") {\n");
        sb.append("        JAXRSServerFactoryBean factory = new JAXRSServerFactoryBean();\n");
        sb.append("        factory.setBus(bus);\n");
        sb.append("        factory.setAddress(\"").append(basePath).append("\");\n");
        sb.append("        factory.setServiceBeans(List.of(\n");
        for (int i = 0; i < resources.size(); i++) {
            sb.append("            ").append(toLowerCamelCase(resources.get(i).className));
            if (i < resources.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }
        sb.append("        ));\n");
        sb.append("        return factory.create();\n");
        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    private String toLowerCamelCase(String className) {
        if (className == null || className.isEmpty()) {
            return className;
        }
        return Character.toLowerCase(className.charAt(0)) + className.substring(1);
    }

    private static boolean isSpringBootApplicationAnnotation(J.Annotation ann) {
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(ann.getType());
        return type != null && SPRING_BOOT_APP_FQN.equals(type.getFullyQualifiedName());
    }

    private boolean isPathAnnotated(J.ClassDeclaration classDecl) {
        return classDecl.getLeadingAnnotations().stream()
            .anyMatch(ann -> {
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(ann.getType());
                return type != null &&
                    (PATH_JAVAX.equals(type.getFullyQualifiedName()) ||
                     PATH_JAKARTA.equals(type.getFullyQualifiedName()));
            });
    }

    private String extractPathValue(J.ClassDeclaration classDecl) {
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(ann.getType());
            if (type != null &&
                (PATH_JAVAX.equals(type.getFullyQualifiedName()) ||
                 PATH_JAKARTA.equals(type.getFullyQualifiedName()))) {
                if (ann.getArguments() != null && !ann.getArguments().isEmpty()) {
                    return ann.getArguments().get(0).toString().replace("\"", "");
                }
            }
        }
        return null;
    }

    private static final String APPLICATION_PATH_JAVAX = "javax.ws.rs.ApplicationPath";
    private static final String APPLICATION_PATH_JAKARTA = "jakarta.ws.rs.ApplicationPath";
    private static final String APPLICATION_JAVAX = "javax.ws.rs.core.Application";
    private static final String APPLICATION_JAKARTA = "jakarta.ws.rs.core.Application";

    private boolean isExistingJaxRsConfig(J.ClassDeclaration classDecl) {
        String name = classDecl.getSimpleName();
        // Check if this looks like an existing JAX-RS config by name
        if (name.contains("JerseyConfig") || name.contains("ResourceConfig") ||
            name.contains("ResteasyConfig") || name.contains("CxfConfig") ||
            name.contains("JaxrsConfig") || name.contains("JaxRsConfig")) {
            return true;
        }

        // Check for @ApplicationPath annotation (indicates JAX-RS Application)
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(ann.getType());
            if (type != null) {
                String fqn = type.getFullyQualifiedName();
                if (APPLICATION_PATH_JAVAX.equals(fqn) || APPLICATION_PATH_JAKARTA.equals(fqn)) {
                    return true;
                }
            }
        }

        // Check if class extends jakarta.ws.rs.core.Application or javax.ws.rs.core.Application
        if (classDecl.getExtends() != null) {
            JavaType.FullyQualified extendsType = TypeUtils.asFullyQualified(classDecl.getExtends().getType());
            if (extendsType != null) {
                String fqn = extendsType.getFullyQualifiedName();
                if (APPLICATION_JAVAX.equals(fqn) || APPLICATION_JAKARTA.equals(fqn)) {
                    return true;
                }
            }
        }

        // Check for ResourceConfig bean or JAXRSServerFactoryBean in @Configuration classes
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(ann.getType());
            if (type != null && "org.springframework.context.annotation.Configuration".equals(type.getFullyQualifiedName())) {
                String source = classDecl.print(new org.openrewrite.Cursor(null, classDecl));
                if (source.contains("ResourceConfig") || source.contains("JAXRSServerFactoryBean")) {
                    return true;
                }
            }
        }
        return false;
    }

    private Path extractProjectRoot(Path sourcePath) {
        String pathStr = sourcePath.toString().replace('\\', '/');
        // Handle absolute paths with /src/
        int srcIndex = pathStr.indexOf("/src/");
        if (srcIndex > 0) {
            return Paths.get(pathStr.substring(0, srcIndex));
        }
        // Handle relative paths starting with src/ (common in tests and real projects)
        if (pathStr.startsWith("src/")) {
            return Paths.get("").toAbsolutePath().normalize();
        }
        // Handle pom.xml at project root
        if (pathStr.equals("pom.xml") || pathStr.endsWith("/pom.xml")) {
            if (pathStr.equals("pom.xml")) {
                return Paths.get("").toAbsolutePath().normalize();
            }
            int lastSlash = pathStr.lastIndexOf("/pom.xml");
            return Paths.get(pathStr.substring(0, lastSlash));
        }
        // For paths that look like package paths (e.g., com/example/UserResource.java),
        // assume they're relative to an implicit project root. This handles OpenRewrite
        // test framework which provides paths without src/main/java prefix.
        if (pathStr.endsWith(".java") && !pathStr.contains("/src/")) {
            return Paths.get("").toAbsolutePath().normalize();
        }
        return sourcePath.getParent() != null ? sourcePath.getParent() : Paths.get("").toAbsolutePath().normalize();
    }

    private String extractSourceRoot(String sourcePath) {
        int javaIndex = sourcePath.indexOf("/java/");
        if (javaIndex >= 0) {
            return sourcePath.substring(0, javaIndex + 5);  // Include "/java"
        }
        int srcIndex = sourcePath.indexOf("/src/");
        if (srcIndex >= 0) {
            int nextSlash = sourcePath.indexOf('/', srcIndex + 5);
            if (nextSlash > srcIndex) {
                int javaAfter = sourcePath.indexOf("/java", nextSlash);
                if (javaAfter > nextSlash) {
                    return sourcePath.substring(srcIndex + 1, javaAfter + 5);
                }
            }
        }
        return "src/main/java";
    }

    private String findCommonPackagePrefix(Set<String> packages) {
        if (packages.isEmpty()) {
            return null;
        }
        List<String> sorted = new ArrayList<>(packages);
        sorted.sort(Comparator.comparingInt(String::length));
        String shortest = sorted.get(0);
        for (String pkg : sorted) {
            while (!pkg.startsWith(shortest) && !shortest.isEmpty()) {
                int lastDot = shortest.lastIndexOf('.');
                if (lastDot > 0) {
                    shortest = shortest.substring(0, lastDot);
                } else {
                    shortest = "";
                }
            }
        }
        return shortest.isEmpty() ? null : shortest;
    }
}
