/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JAX-WS to CXF Spring Boot migration
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
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.text.PlainText;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;
import org.openrewrite.xml.XmlIsoVisitor;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;
import java.util.Comparator;

/**
 * Generates CXF Spring Boot configuration for JAX-WS endpoints.
 * <p>
 * This recipe scans for @WebService and @WebServiceProvider annotated classes
 * and generates a @Configuration class with CXF endpoint beans.
 * It also adds the cxf-spring-boot-starter-jaxws dependency.
 * <p>
 * Configuration via project.yaml:
 * <ul>
 *   <li>jaxws.provider: cxf|manual (default: cxf)</li>
 *   <li>jaxws.basePath: servlet mapping base path (default: /services)</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class GenerateCxfJaxwsConfig extends ScanningRecipe<GenerateCxfJaxwsConfig.Accumulator> {

    private static final String WEB_SERVICE_JAVAX = "javax.jws.WebService";
    private static final String WEB_SERVICE_JAKARTA = "jakarta.jws.WebService";
    private static final String WEB_SERVICE_PROVIDER_JAVAX = "javax.xml.ws.WebServiceProvider";
    private static final String WEB_SERVICE_PROVIDER_JAKARTA = "jakarta.xml.ws.WebServiceProvider";
    private static final String SPRING_BOOT_APP_FQN = "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String SPRING_WS_CORE = "org.springframework.ws.core";
    private static final String CXF_GROUP_ID = "org.apache.cxf";
    private static final String CXF_ARTIFACT_ID = "cxf-spring-boot-starter-jaxws";
    private static final Pattern URL_SAFE_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    static class Accumulator {
        Map<Path, ModuleState> modules = new HashMap<>();
    }

    static class ModuleState {
        final Path moduleRoot;
        List<WebServiceInfo> webServices = new ArrayList<>();
        boolean hasExistingCxfConfig = false;
        boolean hasExistingCxfDependency = false;
        boolean hasSpringWsDependency = false;
        boolean hasApplicationProperties = false;
        Path applicationPropertiesPath = null;
        boolean applicationPropertiesAppendable = false;  // true only if PlainText or Properties.File
        boolean cxfPathAlreadyConfigured = false;
        String cxfPathConfigSource = null;  // Where cxf.path was detected: "properties", "yaml", "profile"
        boolean cxfPathConfigured = false;  // Set to true when visitor updates the file
        String mainSourceRoot = null;
        String springBootApplicationPackage = null;
        Set<String> packages = new HashSet<>();
        Set<String> classFqns = new HashSet<>();

        ModuleState(Path moduleRoot) {
            this.moduleRoot = moduleRoot;
        }
    }

    static class WebServiceInfo {
        String className;
        String packageName;
        String fqn;
        String nameAttribute;  // @WebService(name=...)
        String serviceName;    // @WebService(serviceName=...)
        String endpointPath;   // Derived endpoint path
        boolean needsReview = false;
        String reviewReason = null;

        WebServiceInfo(String className, String packageName) {
            this.className = className;
            this.packageName = packageName;
            this.fqn = packageName.isEmpty() ? className : packageName + "." + className;
        }
    }

    @Override
    public String getDisplayName() {
        return "Generate CXF JAX-WS Configuration";
    }

    @Override
    public String getDescription() {
        return "Generates CXF Spring Boot configuration for JAX-WS endpoints, " +
               "including @Configuration class with endpoint beans and CXF dependency.";
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
                } else if (tree instanceof SourceFile) {
                    return visitSourceFile((SourceFile) tree, ctx);
                }
                return tree;
            }

            private Tree visitSourceFile(SourceFile sourceFile, ExecutionContext ctx) {
                String sourcePath = sourceFile.getSourcePath().toString().replace('\\', '/');
                String fileName = sourceFile.getSourcePath().getFileName().toString();

                // Scan application.properties, application-*.properties, application.yml/yaml
                boolean isMainProperties = "application.properties".equals(fileName);
                boolean isProfileProperties = fileName.startsWith("application-") && fileName.endsWith(".properties");
                boolean isYaml = "application.yml".equals(fileName) || "application.yaml".equals(fileName);

                if (!isMainProperties && !isProfileProperties && !isYaml) {
                    return sourceFile;
                }

                Path moduleRoot = extractProjectRoot(sourceFile.getSourcePath());
                ModuleState module = acc.modules.computeIfAbsent(moduleRoot, ModuleState::new);

                // Track main application.properties for visitor updates
                if (isMainProperties) {
                    module.hasApplicationProperties = true;
                    module.applicationPropertiesPath = sourceFile.getSourcePath();
                    // Track if file type supports append (Properties.File or PlainText)
                    module.applicationPropertiesAppendable =
                        (sourceFile instanceof PlainText) || (sourceFile instanceof Properties.File);
                }

                // Check if cxf.path is already configured in any config file
                String content = sourceFile instanceof PlainText
                    ? ((PlainText) sourceFile).getText()
                    : (sourceFile instanceof Properties.File
                        ? ((Properties.File) sourceFile).printAll()
                        : "");

                // Pattern for .properties files: "cxf.path" followed by "=" or ":"
                // Both delimiters are valid in Java .properties files
                if ((isMainProperties || isProfileProperties) &&
                    content.matches("(?s).*(?m)^\\s*cxf\\.path\\s*[:=].*")) {
                    module.cxfPathAlreadyConfigured = true;
                    module.cxfPathConfigSource = isMainProperties ? "properties" : "profile";
                }
                // Pattern for YAML files: supports both flat style (cxf.path:) and nested style (cxf:\n  path:)
                if (isYaml && hasCxfPathInYaml(content)) {
                    module.cxfPathAlreadyConfigured = true;
                    module.cxfPathConfigSource = "yaml";
                }

                return sourceFile;
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

                // Check if jaxws.provider is set to manual
                String jaxwsProvider = config.getJaxwsProvider();
                if ("manual".equalsIgnoreCase(jaxwsProvider)) {
                    return cu;  // Skip if manual mode
                }

                boolean isTestSource = config.isTestSource(normalizedPath);
                if (isTestSource) {
                    return cu;  // Skip test sources
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
                        .anyMatch(GenerateCxfJaxwsConfig::isSpringBootApplicationAnnotation);
                    if (isBootApp && module.springBootApplicationPackage == null) {
                        module.springBootApplicationPackage = pkg;
                    }

                    // Check for @WebService or @WebServiceProvider
                    for (J.Annotation annotation : classDecl.getLeadingAnnotations()) {
                        if (isWebServiceAnnotation(annotation)) {
                            WebServiceInfo wsInfo = new WebServiceInfo(classDecl.getSimpleName(), pkg);
                            extractWebServiceAttributes(annotation, wsInfo);
                            deriveEndpointPath(wsInfo);
                            module.webServices.add(wsInfo);

                            if (module.mainSourceRoot == null) {
                                module.mainSourceRoot = extractMainSourceRoot(normalizedPath, config);
                            }
                        }
                    }

                    // Check for existing CXF configuration
                    if (isExistingCxfConfiguration(classDecl)) {
                        module.hasExistingCxfConfig = true;
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

                // JAXWS-002: Use MavenResolutionResult for effective POM dependency check
                // This properly checks parent chain, not just the current POM content
                final boolean[] mrrAvailable = {false};
                new MavenIsoVisitor<ExecutionContext>() {
                    @Override
                    public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                        MavenResolutionResult mrr = getResolutionResult();
                        if (mrr != null && mrr.getPom() != null) {
                            mrrAvailable[0] = true;
                            // Check requested dependencies (direct + inherited from parent)
                            // Only consider compile/runtime scopes, not test/provided
                            for (org.openrewrite.maven.tree.Dependency dep : mrr.getPom().getRequestedDependencies()) {
                                if (isMainScope(dep.getScope())) {
                                    checkDependency(dep.getGroupId(), dep.getArtifactId());
                                }
                            }
                            // Also check resolved dependencies for transitive CXF
                            // Only check Compile and Runtime scopes
                            for (Scope scope : List.of(Scope.Compile, Scope.Runtime)) {
                                List<ResolvedDependency> deps = mrr.getDependencies().get(scope);
                                if (deps != null) {
                                    for (ResolvedDependency resolved : deps) {
                                        checkDependency(resolved.getGroupId(), resolved.getArtifactId());
                                    }
                                }
                            }
                        }
                        return document;
                    }

                    private boolean isMainScope(String scope) {
                        // null scope defaults to compile; compile and runtime are main scopes
                        return scope == null || "compile".equalsIgnoreCase(scope) || "runtime".equalsIgnoreCase(scope);
                    }

                    private void checkDependency(String groupId, String artifactId) {
                        // CXF JAX-WS starter
                        if (CXF_GROUP_ID.equals(groupId) && CXF_ARTIFACT_ID.equals(artifactId)) {
                            module.hasExistingCxfDependency = true;
                        }
                        // Spring-WS (conflict detection)
                        if ("org.springframework.ws".equals(groupId) && "spring-ws-core".equals(artifactId)) {
                            module.hasSpringWsDependency = true;
                        }
                        if ("org.springframework.boot".equals(groupId) && "spring-boot-starter-web-services".equals(artifactId)) {
                            module.hasSpringWsDependency = true;
                        }
                    }
                }.visit(doc, ctx);

                // Fallback: if MavenResolutionResult unavailable, use string-based check
                if (!mrrAvailable[0]) {
                    String content = doc.printAll();
                    if (content.contains(CXF_ARTIFACT_ID)) {
                        module.hasExistingCxfDependency = true;
                    }
                    if (content.contains("spring-ws-core") || content.contains("spring-boot-starter-web-services")) {
                        module.hasSpringWsDependency = true;
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
                } else if (tree instanceof SourceFile) {
                    return visitPropertiesFile((SourceFile) tree, ctx);
                }
                return tree;
            }

            private Tree visitPropertiesFile(SourceFile sourceFile, ExecutionContext ctx) {
                String sourcePath = sourceFile.getSourcePath().toString().replace('\\', '/');
                if (!sourcePath.endsWith("application.properties")) {
                    return sourceFile;
                }

                Path moduleRoot = extractProjectRoot(sourceFile.getSourcePath());
                ModuleState module = acc.modules.get(moduleRoot);
                if (module == null || module.webServices.isEmpty() || module.hasSpringWsDependency) {
                    return sourceFile;
                }

                // Check if we need to add cxf.path
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(moduleRoot);
                String basePath = normalizeBasePath(config.getJaxwsBasePath());
                if ("/services".equals(basePath) || module.cxfPathAlreadyConfigured) {
                    return sourceFile;  // Default path or already configured
                }

                // Skip if this is a newly generated file (generate() already added cxf.path)
                if (!module.hasApplicationProperties) {
                    return sourceFile;
                }

                module.cxfPathConfigured = true;  // Track that we updated the file

                // Handle Properties.File with AST to preserve type for downstream recipes
                if (sourceFile instanceof Properties.File) {
                    Properties.File propsFile = (Properties.File) sourceFile;
                    List<Properties.Content> newContent = new ArrayList<>(propsFile.getContent());

                    // Add comment entry
                    Properties.Comment comment = new Properties.Comment(
                        Tree.randomId(),
                        "\n",
                        org.openrewrite.marker.Markers.EMPTY,
                        Properties.Comment.Delimiter.HASH_TAG,
                        " CXF JAX-WS base path (auto-generated)"
                    );
                    newContent.add(comment);

                    // Add cxf.path entry
                    Properties.Entry entry = new Properties.Entry(
                        Tree.randomId(),
                        "\n",
                        org.openrewrite.marker.Markers.EMPTY,
                        "cxf.path",
                        "",  // beforeEquals
                        Properties.Entry.Delimiter.EQUALS,
                        new Properties.Value(
                            Tree.randomId(),
                            "",
                            org.openrewrite.marker.Markers.EMPTY,
                            basePath
                        )
                    );
                    newContent.add(entry);

                    return propsFile.withContent(newContent);
                }

                // Handle PlainText with text append (preserves original formatting)
                if (sourceFile instanceof PlainText) {
                    PlainText plainText = (PlainText) sourceFile;
                    String existingContent = plainText.getText();
                    String newProperty = "\n# CXF JAX-WS base path (auto-generated)\ncxf.path=" + basePath + "\n";
                    String updatedContent = existingContent.endsWith("\n")
                        ? existingContent + newProperty
                        : existingContent + "\n" + newProperty;

                    return plainText.withText(updatedContent);
                }

                return sourceFile;
            }

            private Tree visitPomFile(Xml.Document document, ExecutionContext ctx) {
                String sourcePath = document.getSourcePath().toString().replace('\\', '/');
                if (!sourcePath.endsWith("pom.xml")) {
                    return document;
                }

                Path moduleRoot = extractProjectRoot(document.getSourcePath());
                ModuleState module = acc.modules.get(moduleRoot);
                if (module == null || module.webServices.isEmpty() || module.hasExistingCxfConfig) {
                    return document;
                }

                // Skip if CXF dependency already exists
                if (module.hasExistingCxfDependency) {
                    return document;
                }

                // If Spring-WS conflict, add a comment marker inside <project> element
                if (module.hasSpringWsDependency) {
                    String content = document.printAll();
                    if (!content.contains("@NeedsReview: JAX-WS Migration Conflict")) {
                        // Build the marker comment content
                        StringBuilder markerBuilder = new StringBuilder();
                        markerBuilder.append("\n    @NeedsReview: JAX-WS Migration Conflict\n");
                        markerBuilder.append("    Spring-WS dependency detected - CXF configuration was NOT generated.\n");
                        markerBuilder.append("    \n");
                        markerBuilder.append("    Found @WebService classes:\n");
                        for (WebServiceInfo ws : module.webServices) {
                            markerBuilder.append("      - ").append(ws.fqn).append("\n");
                        }
                        markerBuilder.append("    \n");
                        markerBuilder.append("    Resolution options:\n");
                        markerBuilder.append("      1. Remove Spring-WS dependency and re-run migration for CXF support\n");
                        markerBuilder.append("      2. Configure JAX-WS endpoints manually using Spring-WS\n");
                        markerBuilder.append("      3. Keep both frameworks with careful servlet mapping separation\n");
                        markerBuilder.append("    ");
                        final String markerText = markerBuilder.toString();

                        // Add comment as first child inside <project> using XmlIsoVisitor
                        return new XmlIsoVisitor<ExecutionContext>() {
                            @Override
                            public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext context) {
                                Xml.Tag t = super.visitTag(tag, context);
                                if ("project".equals(t.getName()) && t.getContent() != null) {
                                    // Check if comment already exists
                                    boolean hasComment = t.getContent().stream()
                                        .anyMatch(c -> c instanceof Xml.Comment &&
                                            ((Xml.Comment) c).getText().contains("@NeedsReview: JAX-WS Migration Conflict"));
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
                        }.visit(document, ctx);
                    }
                    return document;
                }

                // Add CXF dependency
                Xml.Document doc = document;
                doc = (Xml.Document) new AddDependency(
                    CXF_GROUP_ID,
                    CXF_ARTIFACT_ID,
                    "4.1.4",  // CXF version for Spring Boot 3.x
                    null,
                    null,
                    null, null, null, null, null, null, null
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
            // Skip if no web services, existing config, existing dependency, or Spring-WS conflict
            // Spring-WS conflict marker is handled by the visitor (adding comment to POM)
            if (module.webServices.isEmpty() || module.hasExistingCxfConfig ||
                module.hasExistingCxfDependency || module.hasSpringWsDependency) {
                continue;
            }

            // Sort web services by FQN for deterministic ordering
            module.webServices.sort(Comparator.comparing(ws -> ws.fqn));

            // Resolve path collisions (now deterministic due to sorting)
            resolvePathCollisions(module.webServices);

            // Determine base package
            String basePackage = module.springBootApplicationPackage != null
                ? module.springBootApplicationPackage
                : findCommonPackagePrefix(module.packages);
            if (basePackage == null) {
                basePackage = "";
            }

            // Load project configuration for basePath and normalize it
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(module.moduleRoot);
            String basePath = normalizeBasePath(config.getJaxwsBasePath());
            boolean needsBasePathConfig = !"/services".equals(basePath);

            // Determine needsManualReview BEFORE generating config
            // Check if properties generation will be skipped due to resource root issues
            // or if append will fail due to unsupported file type
            boolean needsManualReview = false;
            boolean skipPropertiesGeneration = false;
            Path resourcePath = null;

            // Check if existing application.properties can't be appended (unsupported type)
            if (needsBasePathConfig && module.hasApplicationProperties && !module.applicationPropertiesAppendable
                && !module.cxfPathAlreadyConfigured) {
                needsManualReview = true;
            }

            if (needsBasePathConfig && !module.hasApplicationProperties && !module.cxfPathAlreadyConfigured) {
                // Determine resource root: use module-relative path for generated file
                List<String> resourceRoots = config.getResourceRoots();

                if (resourceRoots != null && !resourceRoots.isEmpty()) {
                    String configuredRoot = resourceRoots.get(0);
                    Path rootPath = Paths.get(configuredRoot);

                    if (rootPath.isAbsolute()) {
                        if (rootPath.startsWith(module.moduleRoot)) {
                            resourcePath = rootPath;
                        } else {
                            // Absolute path outside module - can't safely generate file
                            skipPropertiesGeneration = true;
                            needsManualReview = true;
                        }
                    } else {
                        resourcePath = module.moduleRoot.resolve(rootPath);
                    }
                } else {
                    resourcePath = module.moduleRoot.resolve("src/main/resources");
                }
            }

            // Generate the configuration source with correct needsManualReview flag and config source
            String className = deriveConfigurationClassName(basePackage, module.classFqns);
            String configSource = module.cxfPathAlreadyConfigured ? module.cxfPathConfigSource : null;
            String source = generateConfigurationSource(basePackage, className, module.webServices, basePath,
                needsManualReview, configSource);

            String mainSourceRoot = module.mainSourceRoot != null ? module.mainSourceRoot : "src/main/java";
            String relativePath = basePackage.isEmpty()
                ? className + ".java"
                : basePackage.replace('.', '/') + "/" + className + ".java";
            Path filePath = Paths.get(mainSourceRoot + "/" + relativePath);

            List<SourceFile> parsed = javaParser.parse(source).toList();
            if (!parsed.isEmpty()) {
                generated.add(parsed.get(0).withSourcePath(filePath));
            }

            // Generate application.properties if needed and not skipped
            if (needsBasePathConfig && !module.hasApplicationProperties && !module.cxfPathAlreadyConfigured
                && !skipPropertiesGeneration && resourcePath != null) {
                String propertiesContent = "# CXF JAX-WS Configuration\n" +
                    "# Generated by EJB-to-Spring migration\n" +
                    "cxf.path=" + basePath + "\n";
                Path propertiesPath = resourcePath.resolve("application.properties");
                if (propertiesPath.isAbsolute() && propertiesPath.startsWith(module.moduleRoot)) {
                    propertiesPath = module.moduleRoot.relativize(propertiesPath);
                }
                PlainText propertiesFile = PlainText.builder()
                    .id(Tree.randomId())
                    .sourcePath(propertiesPath)
                    .text(propertiesContent)
                    .build();
                generated.add(propertiesFile);
            }
        }

        return generated;
    }

    /**
     * Normalizes the basePath: ensures leading slash, removes trailing slash.
     */
    private static String normalizeBasePath(String basePath) {
        if (basePath == null || basePath.isEmpty()) {
            return "/services";
        }
        String normalized = basePath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }


    private static boolean isWebServiceAnnotation(J.Annotation annotation) {
        return TypeUtils.isOfClassType(annotation.getType(), WEB_SERVICE_JAVAX) ||
               TypeUtils.isOfClassType(annotation.getType(), WEB_SERVICE_JAKARTA) ||
               TypeUtils.isOfClassType(annotation.getType(), WEB_SERVICE_PROVIDER_JAVAX) ||
               TypeUtils.isOfClassType(annotation.getType(), WEB_SERVICE_PROVIDER_JAKARTA) ||
               "WebService".equals(annotation.getSimpleName()) ||
               "WebServiceProvider".equals(annotation.getSimpleName());
    }

    private static boolean isSpringBootApplicationAnnotation(J.Annotation annotation) {
        return TypeUtils.isOfClassType(annotation.getType(), SPRING_BOOT_APP_FQN) ||
               "SpringBootApplication".equals(annotation.getSimpleName());
    }

    private static boolean isExistingCxfConfiguration(J.ClassDeclaration classDecl) {
        // Check if class is a @Configuration with CXF-related beans
        boolean hasConfigAnnotation = classDecl.getLeadingAnnotations().stream()
            .anyMatch(a -> "Configuration".equals(a.getSimpleName()));
        if (!hasConfigAnnotation) {
            return false;
        }

        // Look for CXF-related method names or return types
        if (classDecl.getBody() != null) {
            for (org.openrewrite.java.tree.Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                    String methodName = method.getSimpleName().toLowerCase();
                    if (methodName.contains("endpoint") || methodName.contains("cxf") || methodName.contains("jaxws")) {
                        // Check for @Bean annotation
                        if (method.getLeadingAnnotations().stream().anyMatch(a -> "Bean".equals(a.getSimpleName()))) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static void extractWebServiceAttributes(J.Annotation annotation, WebServiceInfo wsInfo) {
        if (annotation.getArguments() == null) {
            return;
        }

        for (org.openrewrite.java.tree.Expression arg : annotation.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment assign = (J.Assignment) arg;
                String attrName = ((J.Identifier) assign.getVariable()).getSimpleName();
                if (assign.getAssignment() instanceof J.Literal) {
                    String value = (String) ((J.Literal) assign.getAssignment()).getValue();
                    if ("name".equals(attrName)) {
                        wsInfo.nameAttribute = value;
                    } else if ("serviceName".equals(attrName)) {
                        wsInfo.serviceName = value;
                    }
                }
            }
        }
    }

    private static void deriveEndpointPath(WebServiceInfo wsInfo) {
        // Priority: name (if URL-safe) > className
        // Note: serviceName is typically used for WSDL service element, not endpoint path
        if (wsInfo.nameAttribute != null && URL_SAFE_PATTERN.matcher(wsInfo.nameAttribute).matches()) {
            wsInfo.endpointPath = "/" + wsInfo.nameAttribute;
        } else if (wsInfo.nameAttribute != null) {
            // Invalid name attribute (e.g., namespace or special chars)
            wsInfo.endpointPath = "/" + wsInfo.className;
            wsInfo.needsReview = true;
            wsInfo.reviewReason = "WebService name attribute contains non-URL-safe characters: " + wsInfo.nameAttribute;
        } else {
            wsInfo.endpointPath = "/" + wsInfo.className;
        }

        // Add review note if serviceName is specified (for WSDL awareness)
        if (wsInfo.serviceName != null && !wsInfo.serviceName.isEmpty()) {
            if (wsInfo.needsReview) {
                wsInfo.reviewReason += "; serviceName='" + wsInfo.serviceName + "' is used for WSDL, not endpoint path";
            } else {
                wsInfo.needsReview = true;
                wsInfo.reviewReason = "serviceName='" + wsInfo.serviceName + "' is used for WSDL service element, not endpoint path";
            }
        }
    }

    private static void resolvePathCollisions(List<WebServiceInfo> webServices) {
        Map<String, List<WebServiceInfo>> pathGroups = new HashMap<>();
        for (WebServiceInfo ws : webServices) {
            pathGroups.computeIfAbsent(ws.endpointPath, k -> new ArrayList<>()).add(ws);
        }

        for (Map.Entry<String, List<WebServiceInfo>> entry : pathGroups.entrySet()) {
            List<WebServiceInfo> group = entry.getValue();
            if (group.size() > 1) {
                int counter = 1;
                for (WebServiceInfo ws : group) {
                    String originalPath = ws.endpointPath;
                    ws.endpointPath = originalPath + "_" + counter;
                    ws.needsReview = true;
                    ws.reviewReason = "Path collision resolved with suffix. Original path: " + originalPath;
                    counter++;
                }
            }
        }
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
        String baseName = "JaxwsConfiguration";
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
     * Generates the Spring @Configuration class source code.
     *
     * @param basePackage package name for the generated class
     * @param className name of the generated configuration class
     * @param webServices list of web services to expose as endpoints
     * @param basePath the CXF servlet base path
     * @param needsManualConfig true if user needs to manually configure cxf.path
     * @param configSource where cxf.path is configured: "properties", "yaml", "profile", or null for generated
     */
    private static String generateConfigurationSource(String basePackage, String className,
                                                       List<WebServiceInfo> webServices,
                                                       String basePath, boolean needsManualConfig,
                                                       String configSource) {
        StringBuilder source = new StringBuilder();

        // Package declaration
        if (!basePackage.isEmpty()) {
            source.append("package ").append(basePackage).append(";\n\n");
        }

        // Imports
        source.append("import jakarta.xml.ws.Endpoint;\n");
        source.append("import org.apache.cxf.Bus;\n");
        source.append("import org.apache.cxf.jaxws.EndpointImpl;\n");
        source.append("import org.springframework.context.annotation.Bean;\n");
        source.append("import org.springframework.context.annotation.Configuration;\n");

        // Import all WebService classes
        Set<String> imports = new TreeSet<>();
        for (WebServiceInfo ws : webServices) {
            if (!ws.packageName.isEmpty() && !ws.packageName.equals(basePackage)) {
                imports.add(ws.fqn);
            }
        }
        for (String imp : imports) {
            source.append("import ").append(imp).append(";\n");
        }
        source.append("\n");

        // Add reference for non-default basePath
        if (!"/services".equals(basePath)) {
            if (needsManualConfig) {
                // User must add cxf.path manually
                source.append("// @NeedsReview: Add 'cxf.path=").append(basePath).append("' to application.properties\n");
            } else if (configSource != null) {
                // cxf.path was already configured - show where
                String configFile = switch (configSource) {
                    case "yaml" -> "application.yml";
                    case "profile" -> "application-*.properties";
                    default -> "application.properties";
                };
                source.append("// CXF base path: ").append(basePath).append(" (configured in ").append(configFile).append(")\n");
            } else {
                // application.properties was generated with cxf.path
                source.append("// CXF base path: ").append(basePath).append(" (configured in application.properties)\n");
            }
        }

        // Class declaration
        source.append("@Configuration\n");
        source.append("public class ").append(className).append(" {\n\n");

        // Generate endpoint beans
        for (WebServiceInfo ws : webServices) {
            String beanName = uncapitalize(ws.className);
            String endpointBeanName = beanName + "Endpoint";

            // Add @NeedsReview comment if needed
            if (ws.needsReview) {
                source.append("    // @NeedsReview: ").append(ws.reviewReason).append("\n");
            }

            // Endpoint bean
            source.append("    @Bean\n");
            source.append("    public Endpoint ").append(endpointBeanName).append("(Bus bus, ");
            source.append(ws.className).append(" service) {\n");
            source.append("        EndpointImpl endpoint = new EndpointImpl(bus, service);\n");
            source.append("        endpoint.publish(\"").append(ws.endpointPath).append("\");\n");
            source.append("        return endpoint;\n");
            source.append("    }\n\n");
        }

        source.append("}\n");
        return source.toString();
    }

    private static String uncapitalize(String str) {
        if (str == null || str.isEmpty()) {
            return str;
        }
        return Character.toLowerCase(str.charAt(0)) + str.substring(1);
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
     * Detects if cxf.path is configured in YAML content.
     * Supports both flat style (cxf.path: /api) and nested style:
     * <pre>
     * cxf:
     *   path: /api
     * </pre>
     */
    private static boolean hasCxfPathInYaml(String content) {
        // Check flat style: cxf.path:
        if (content.matches("(?s).*(?m)^\\s*cxf\\.path\\s*:.*")) {
            return true;
        }

        // Check nested style: cxf: followed by path: with greater indentation
        String[] lines = content.split("\n");
        int cxfIndent = -1;
        for (String line : lines) {
            String trimmed = line.trim();

            // Skip empty lines and comments
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }

            // YAML document markers reset block tracking to prevent cross-document bleed
            if (trimmed.equals("---") || trimmed.equals("...")) {
                cxfIndent = -1;  // Reset - new document starts
                continue;
            }

            // Calculate indentation (spaces + tabs*8)
            int indent = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ' ') {
                    indent++;
                } else if (c == '\t') {
                    indent += 8;
                } else {
                    break;
                }
            }

            // Found "cxf:" - remember its indentation
            if (trimmed.equals("cxf:") || (trimmed.startsWith("cxf:") && trimmed.substring(4).trim().isEmpty())) {
                cxfIndent = indent;
                continue;
            }

            // If we're inside cxf: block and find path:
            if (cxfIndent >= 0) {
                // New key at same or lesser indentation ends the cxf block
                if (indent <= cxfIndent) {
                    cxfIndent = -1;  // Reset - no longer in cxf block
                    continue;
                }

                // Check for path: inside cxf block (more indented)
                if (trimmed.equals("path:") || trimmed.startsWith("path:")) {
                    return true;
                }
            }
        }

        return false;
    }
}
