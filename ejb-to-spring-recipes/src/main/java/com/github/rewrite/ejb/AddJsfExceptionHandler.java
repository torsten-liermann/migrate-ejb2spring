/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - ViewExpiredException handling
 * Modified: Codex P2.2 - Added multi-module support, default-package support, AST-based handler detection
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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates a JSF ErrorPageConfiguration for Spring Boot to handle ViewExpiredException.
 * <p>
 * In Spring Boot with JoinFaces, ViewExpiredException needs special handling via
 * ErrorPageRegistrar, not @ControllerAdvice. JSF exceptions occur inside FacesServlet
 * and don't reach Spring MVC's exception handling.
 * <p>
 * <b>Multi-module support:</b> Tracks JSF usage and existing handlers per source root.
 * Generates one config per module that has JSF usage but no existing handler.
 * <p>
 * <b>Generated class:</b>
 * <pre>
 * &#64;Configuration
 * public class JsfErrorPageConfig {
 *     &#64;Bean
 *     public ErrorPageRegistrar errorPageRegistrar() {
 *         return registry -> {
 *             registry.addErrorPages(new ErrorPage(ViewExpiredException.class, "/"));
 *         };
 *     }
 * }
 * </pre>
 * <p>
 * <b>Idempotent:</b> Only generates if no existing ErrorPageRegistrar for ViewExpiredException is found.
 * <p>
 * <b>Detection:</b>
 * <ul>
 *   <li>JSF usage: imports starting with jakarta.faces.* or javax.faces.*, OR faces-config.xml, OR FacesServlet in web.xml</li>
 *   <li>Existing handler: @Bean method returning ErrorPageRegistrar that registers ViewExpiredException (AST-based)</li>
 * </ul>
 * <p>
 * References:
 * <ul>
 *   <li>https://github.com/joinfaces/joinfaces/issues/367</li>
 *   <li>https://github.com/joinfaces/joinfaces/issues/513</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddJsfExceptionHandler extends ScanningRecipe<AddJsfExceptionHandler.Accumulator> {

    // JSF imports to detect
    private static final String JAKARTA_FACES_IMPORT = "jakarta.faces";
    private static final String JAVAX_FACES_IMPORT = "javax.faces";

    // Generated file name for idempotency
    private static final String GENERATED_FILE_NAME = "JsfErrorPageConfig.java";

    @Override
    public String getDisplayName() {
        return "Add JSF ErrorPageConfig for ViewExpiredException";
    }

    @Override
    public String getDescription() {
        return "Generates a Spring ErrorPageRegistrar configuration to handle JSF ViewExpiredException. " +
               "This prevents 500 errors when a view expires by redirecting to the context root. " +
               "Uses ErrorPageRegistrar because @ControllerAdvice cannot catch exceptions from FacesServlet. " +
               "Only generated when JSF usage is detected and no existing handler is present.";
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
                    return visitJavaSource((J.CompilationUnit) tree, acc);
                } else if (tree instanceof Xml.Document) {
                    return visitXmlSource((Xml.Document) tree, acc);
                }
                return tree;
            }

            private Tree visitJavaSource(J.CompilationUnit cu, Accumulator acc) {
                String sourcePath = cu.getSourcePath().toString().replace('\\', '/');

                // Skip test sources - they should not trigger production config generation
                if (isTestSource(sourcePath)) {
                    return cu;
                }

                // Detect source root - always returns a valid source root
                String sourceRoot = detectSourceRoot(cu);

                // Get or create module state for this source root
                ModuleState moduleState = acc.modules.computeIfAbsent(sourceRoot, k -> new ModuleState());

                // Skip if this is the generated file (idempotency check)
                // The generated file imports ViewExpiredException which would trigger JSF detection
                // Mark as existing handler to prevent regeneration
                if (sourcePath.endsWith(GENERATED_FILE_NAME)) {
                    moduleState.hasExistingHandler = true;
                    // Don't process further - we don't want the imports to trigger JSF detection
                    return cu;
                }

                // Check for imports to determine JSF usage
                for (J.Import imp : cu.getImports()) {
                    String importPath = imp.getQualid().toString();
                    if (importPath.startsWith(JAKARTA_FACES_IMPORT) ||
                        importPath.startsWith(JAVAX_FACES_IMPORT)) {
                        moduleState.hasJsfUsage = true;
                    }
                }

                // Check for @SpringBootApplication to find the root package
                for (J.ClassDeclaration classDecl : cu.getClasses()) {
                    for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                        if ("SpringBootApplication".equals(ann.getSimpleName())) {
                            moduleState.springBootAppPackage = cu.getPackageDeclaration() != null
                                ? cu.getPackageDeclaration().getPackageName()
                                : "";
                        }
                    }

                    // AST-based check: look for @Bean method returning ErrorPageRegistrar with ViewExpiredException
                    // No import gate - detect both imported and FQCN usage
                    if (hasViewExpiredErrorPageRegistrarBean(classDecl)) {
                        moduleState.hasExistingHandler = true;
                    }
                }

                // Collect packages for this module
                if (cu.getPackageDeclaration() != null) {
                    String pkg = cu.getPackageDeclaration().getPackageName();
                    moduleState.packages.add(pkg);
                }

                return cu;
            }

            private Tree visitXmlSource(Xml.Document doc, Accumulator acc) {
                String path = doc.getSourcePath().toString().replace('\\', '/');

                // Skip test resources
                if (isTestSource(path)) {
                    return doc;
                }

                // Determine the source root for XML files
                // For webapp resources, derive the corresponding main source root
                String sourceRoot = deriveSourceRootFromResourcePath(path);

                // Get or create module state for this source root
                ModuleState moduleState = acc.modules.computeIfAbsent(sourceRoot, k -> new ModuleState());

                // Detect faces-config.xml
                if (path.endsWith("faces-config.xml")) {
                    moduleState.hasJsfUsage = true;
                }

                // Detect FacesServlet in web.xml
                if (path.endsWith("web.xml")) {
                    new org.openrewrite.xml.XmlIsoVisitor<ModuleState>() {
                        @Override
                        public Xml.Tag visitTag(Xml.Tag tag, ModuleState state) {
                            Xml.Tag t = super.visitTag(tag, state);
                            if ("servlet-class".equals(t.getName())) {
                                String content = t.getValue().orElse("");
                                if (content.contains("FacesServlet")) {
                                    state.hasJsfUsage = true;
                                }
                            }
                            return t;
                        }
                    }.visit(doc, moduleState);
                }

                return doc;
            }

            /**
             * Detects the source root from a CompilationUnit by comparing the package path with the source path.
             * Returns a canonical source root string.
             *
             * For paths like "src/main/java/com/example/MyClass.java" with package "com.example",
             * returns "src/main/java".
             *
             * For test framework paths like "com/example/MyClass.java" (no src/main/java prefix),
             * returns a canonical default "src/main/java" to ensure all files in the same
             * "virtual" project are grouped together.
             */
            private String detectSourceRoot(J.CompilationUnit cu) {
                Path sourcePath = cu.getSourcePath();
                String sourcePathStr = sourcePath.toString().replace('\\', '/');

                if (cu.getPackageDeclaration() != null) {
                    String packageName = cu.getPackageDeclaration().getPackageName();
                    String packagePath = packageName.replace('.', '/');

                    // Find where the package path starts in the source path
                    int packageIdx = sourcePathStr.indexOf(packagePath);
                    if (packageIdx > 0) {
                        // Source root is everything before the package path
                        return sourcePathStr.substring(0, packageIdx - 1); // -1 for the trailing /
                    }

                    // Package path is at the beginning (test framework style paths like "com/example/MyClass.java")
                    // Use canonical default to group all such files together
                    if (packageIdx == 0) {
                        return "src/main/java";
                    }
                }

                // For default package or non-standard layouts, try to detect from common patterns
                String[] sourceRootPatterns = {
                    "/src/main/java/", "/src/java/", "/main/java/", "/source/java/",
                    "src/main/java/", "src/java/", "main/java/", "source/java/"
                };
                for (String pattern : sourceRootPatterns) {
                    int idx = sourcePathStr.indexOf(pattern);
                    if (idx >= 0) {
                        return sourcePathStr.substring(0, idx + pattern.length() - 1);
                    }
                }

                // Ultimate fallback: canonical default
                return "src/main/java";
            }

            /**
             * Derives the main source root from a resource path (e.g., webapp path).
             * Maps src/main/webapp -> src/main/java, src/main/resources -> src/main/java
             */
            private String deriveSourceRootFromResourcePath(String resourcePath) {
                // Common patterns for webapp/resources to source root mapping
                String[] resourcePatterns = {
                    "/src/main/webapp/", "/src/main/resources/", "/webapp/", "/resources/"
                };
                String[] sourcePatterns = {
                    "/src/main/java", "/src/main/java", "/src/main/java", "/src/main/java"
                };

                for (int i = 0; i < resourcePatterns.length; i++) {
                    int idx = resourcePath.indexOf(resourcePatterns[i]);
                    if (idx >= 0) {
                        String modulePrefix = idx > 0 ? resourcePath.substring(0, idx) : "";
                        return modulePrefix + sourcePatterns[i];
                    }
                }

                // Fallback: try to find any common pattern and derive
                if (resourcePath.contains("/src/main/")) {
                    int idx = resourcePath.indexOf("/src/main/");
                    String modulePrefix = idx > 0 ? resourcePath.substring(0, idx) : "";
                    return modulePrefix + "/src/main/java";
                }

                // Default fallback
                return "src/main/java";
            }

            /**
             * AST-based check: Looks for a @Bean method that returns ErrorPageRegistrar
             * and has ViewExpiredException usage in its body.
             *
             * Uses AST inspection instead of toString() to avoid false positives from
             * comments/strings and to handle both imported and FQCN usage.
             */
            private boolean hasViewExpiredErrorPageRegistrarBean(J.ClassDeclaration classDecl) {
                if (classDecl.getBody() == null) {
                    return false;
                }

                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) stmt;

                        // Check if method has @Bean annotation
                        boolean hasBeanAnnotation = method.getLeadingAnnotations().stream()
                            .anyMatch(ann -> "Bean".equals(ann.getSimpleName()));

                        if (!hasBeanAnnotation) {
                            continue;
                        }

                        // Check return type is ErrorPageRegistrar using AST
                        if (!hasErrorPageRegistrarReturnType(method)) {
                            continue;
                        }

                        // Check method body for ViewExpiredException usage via AST traversal
                        if (hasViewExpiredExceptionUsage(method)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            /**
             * Checks if the method return type is ErrorPageRegistrar.
             * Handles simple names (ErrorPageRegistrar) and FQCN (org.springframework...ErrorPageRegistrar).
             */
            private boolean hasErrorPageRegistrarReturnType(J.MethodDeclaration method) {
                if (method.getReturnTypeExpression() == null) {
                    return false;
                }

                // Use type tree inspection instead of toString()
                return hasTypeReference(method.getReturnTypeExpression(), "ErrorPageRegistrar");
            }

            /**
             * Checks if the method body contains ViewExpiredException usage.
             * Traverses the AST to find identifier or field access nodes.
             */
            private boolean hasViewExpiredExceptionUsage(J.MethodDeclaration method) {
                if (method.getBody() == null) {
                    return false;
                }

                // Use a visitor to find ViewExpiredException identifiers/field accesses
                final boolean[] found = {false};
                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.Identifier visitIdentifier(J.Identifier identifier, ExecutionContext ctx) {
                        if ("ViewExpiredException".equals(identifier.getSimpleName())) {
                            found[0] = true;
                        }
                        return super.visitIdentifier(identifier, ctx);
                    }

                    @Override
                    public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                        // Check for FQCN: jakarta.faces.application.ViewExpiredException
                        // Use AST name inspection instead of toString() to avoid matching comments/strings
                        J.Identifier name = fieldAccess.getName();
                        if ("ViewExpiredException".equals(name.getSimpleName()) ||
                            "class".equals(name.getSimpleName())) {
                            // For ViewExpiredException.class, check the target
                            if ("class".equals(name.getSimpleName()) && fieldAccess.getTarget() instanceof J.Identifier) {
                                J.Identifier target = (J.Identifier) fieldAccess.getTarget();
                                if ("ViewExpiredException".equals(target.getSimpleName())) {
                                    found[0] = true;
                                }
                            } else if ("ViewExpiredException".equals(name.getSimpleName())) {
                                found[0] = true;
                            }
                        }
                        return super.visitFieldAccess(fieldAccess, ctx);
                    }
                }.visit(method.getBody(), null);

                return found[0];
            }

            /**
             * Checks if a type tree contains a reference to the given simple type name.
             * Uses AST inspection instead of toString() to avoid matching comments/strings.
             * Handles annotated types (@NonNull ErrorPageRegistrar) and parameterized types.
             */
            private boolean hasTypeReference(org.openrewrite.java.tree.TypeTree typeTree, String simpleName) {
                // Unwrap annotated types (e.g., @NonNull ErrorPageRegistrar)
                if (typeTree instanceof J.AnnotatedType) {
                    J.AnnotatedType annotatedType = (J.AnnotatedType) typeTree;
                    return hasTypeReference(annotatedType.getTypeExpression(), simpleName);
                }
                // Unwrap parameterized types (e.g., Supplier<ErrorPageRegistrar>)
                if (typeTree instanceof J.ParameterizedType) {
                    J.ParameterizedType parameterizedType = (J.ParameterizedType) typeTree;
                    // Check the base type (e.g., Supplier in Supplier<ErrorPageRegistrar>)
                    // getClazz() returns NameTree, check its simple name
                    org.openrewrite.java.tree.NameTree clazz = parameterizedType.getClazz();
                    return hasNameTreeReference(clazz, simpleName);
                }
                if (typeTree instanceof J.Identifier) {
                    return simpleName.equals(((J.Identifier) typeTree).getSimpleName());
                }
                if (typeTree instanceof J.FieldAccess) {
                    // For FQCN like org.springframework.boot.web.server.ErrorPageRegistrar
                    // Check the name part of the field access (rightmost identifier)
                    J.FieldAccess fieldAccess = (J.FieldAccess) typeTree;
                    return simpleName.equals(fieldAccess.getName().getSimpleName());
                }
                return false;
            }

            /**
             * Checks if a NameTree contains a reference to the given simple type name.
             */
            private boolean hasNameTreeReference(org.openrewrite.java.tree.NameTree nameTree, String simpleName) {
                if (nameTree instanceof J.Identifier) {
                    return simpleName.equals(((J.Identifier) nameTree).getSimpleName());
                }
                if (nameTree instanceof J.FieldAccess) {
                    J.FieldAccess fieldAccess = (J.FieldAccess) nameTree;
                    return simpleName.equals(fieldAccess.getName().getSimpleName());
                }
                return false;
            }

            /**
             * Checks if the given path is a test source (src/test/*, src/it/*, etc.).
             */
            private boolean isTestSource(String path) {
                return path.contains("/src/test/") ||
                       path.contains("/src/it/") ||
                       path.contains("/test/") && !path.contains("/main/") ||
                       path.contains("/test-") ||
                       path.startsWith("test/");
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        List<SourceFile> generatedFiles = new ArrayList<>();

        // Process each module separately
        for (Map.Entry<String, ModuleState> entry : acc.modules.entrySet()) {
            String sourceRoot = entry.getKey();
            ModuleState moduleState = entry.getValue();

            // Skip if no JSF detected in this module
            if (!moduleState.hasJsfUsage) {
                continue;
            }

            // Skip if handler already exists in this module
            if (moduleState.hasExistingHandler) {
                continue;
            }

            // Determine the target package for this module
            String targetPackage = determineTargetPackage(moduleState);

            // Handle default package case: generate with warning comment but don't skip
            boolean isDefaultPackage = targetPackage.isEmpty() && moduleState.packages.isEmpty();

            if (targetPackage.isEmpty() && !moduleState.packages.isEmpty()) {
                targetPackage = findBasePackage(moduleState.packages);
            }

            // Generate the error page configuration source
            String source = generateErrorPageConfigSource(targetPackage, isDefaultPackage);

            // Determine the file path using the source root directly
            String packagePath = targetPackage.isEmpty() ? "" : targetPackage.replace('.', '/') + "/";
            Path filePath = Paths.get(sourceRoot + "/" + packagePath + GENERATED_FILE_NAME);

            // Parse the source to create a proper J.CompilationUnit
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
     * Determines the target package for the generated configuration.
     * Prefers the @SpringBootApplication package, then common prefix, then shortest package.
     */
    private String determineTargetPackage(ModuleState moduleState) {
        // Prefer @SpringBootApplication package
        if (moduleState.springBootAppPackage != null) {
            return moduleState.springBootAppPackage;
        }

        // Find common prefix of all packages
        if (!moduleState.packages.isEmpty()) {
            return findCommonPrefix(moduleState.packages);
        }

        return "";
    }

    /**
     * Finds the common prefix of all packages.
     */
    private String findCommonPrefix(Set<String> packages) {
        if (packages.isEmpty()) {
            return "";
        }

        List<String> sortedPackages = new ArrayList<>(packages);
        Collections.sort(sortedPackages);

        String first = sortedPackages.get(0);
        String last = sortedPackages.get(sortedPackages.size() - 1);

        int minLength = Math.min(first.length(), last.length());
        int commonEnd = 0;
        int lastDot = 0;

        for (int i = 0; i < minLength; i++) {
            if (first.charAt(i) == last.charAt(i)) {
                if (first.charAt(i) == '.') {
                    lastDot = i;
                }
                commonEnd = i + 1;
            } else {
                break;
            }
        }

        // Return up to the last complete package segment
        if (lastDot > 0) {
            return first.substring(0, lastDot);
        }

        // If no dot found, check if entire first package is prefix
        if (commonEnd == first.length() && (last.length() == first.length() || last.charAt(first.length()) == '.')) {
            return first;
        }

        return "";
    }

    /**
     * Finds the base package (shortest common prefix).
     */
    private String findBasePackage(Set<String> packages) {
        if (packages.isEmpty()) {
            return "";  // Return empty for default package support
        }

        // Find shortest package as base package
        return packages.stream()
            .min(Comparator.comparingInt(String::length))
            .orElse("");
    }

    /**
     * Generates the JSF ErrorPageConfig source code.
     *
     * @param packageName the target package name (empty for default package)
     * @param isDefaultPackage true if generating into the default package (adds warning)
     */
    private String generateErrorPageConfigSource(String packageName, boolean isDefaultPackage) {
        StringBuilder sb = new StringBuilder();

        // Add warning for default package case
        if (isDefaultPackage) {
            sb.append("""
                /*
                 * WARNING: This class was generated in the default package because
                 * no package declarations were found in the JSF project.
                 * The default package is an anti-pattern in Java.
                 *
                 * @NeedsReview Please move this class to an appropriate package.
                 */
                """);
        }

        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }

        sb.append("""
            import jakarta.faces.application.ViewExpiredException;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import org.springframework.boot.web.server.ErrorPage;
            import org.springframework.boot.web.server.ErrorPageRegistrar;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;

            /**
             * Configuration for handling JSF ViewExpiredException.
             * <p>
             * When a JSF view expires (e.g., due to session timeout or server restart),
             * this configuration redirects the user to the context root instead of
             * showing a 500 error.
             * <p>
             * Note: @ControllerAdvice cannot catch JSF exceptions because they occur
             * inside FacesServlet, not Spring MVC controllers. ErrorPageRegistrar
             * is the correct approach for embedded containers.
             * <p>
             * Generated by JSF-JoinFaces migration recipe.
             *
             * @see <a href="https://github.com/joinfaces/joinfaces/issues/367">JoinFaces Error Page Mapping</a>
             * @see <a href="https://github.com/joinfaces/joinfaces/issues/513">JoinFaces ViewExpiredException</a>
             */
            @Configuration
            public class JsfErrorPageConfig {

                private static final Logger LOG = LoggerFactory.getLogger(JsfErrorPageConfig.class);

                /**
                 * Registers error pages for JSF-specific exceptions.
                 * <p>
                 * ViewExpiredException is thrown when a postback is made to a view
                 * that cannot be restored (usually due to session timeout).
                 *
                 * @return ErrorPageRegistrar for ViewExpiredException
                 */
                @Bean
                public ErrorPageRegistrar jsfErrorPageRegistrar() {
                    return registry -> {
                        // Redirect ViewExpiredException to context root
                        // The user will be redirected to the home page / login page
                        registry.addErrorPages(new ErrorPage(ViewExpiredException.class, "/"));
                        LOG.info("Registered error page for ViewExpiredException -> /");
                    };
                }
            }
            """);

        return sb.toString();
    }

    /**
     * Tracks per-module state for multi-module support.
     */
    static class ModuleState {
        boolean hasJsfUsage = false;
        boolean hasExistingHandler = false;
        String springBootAppPackage = null;
        Set<String> packages = new LinkedHashSet<>();
    }

    /**
     * Accumulator that tracks state per source root for multi-module support.
     */
    static class Accumulator {
        // Map from source root path to module state
        final Map<String, ModuleState> modules = new ConcurrentHashMap<>();
    }
}
