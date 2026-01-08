package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.*;
import org.openrewrite.text.PlainText;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * GAP-INT-002: Migrates ejb-jar.xml interceptor-bindings to Spring AOP @Aspect skeletons.
 * <p>
 * This recipe:
 * <ul>
 *   <li>Scans META-INF/ejb-jar.xml for interceptor-binding elements</li>
 *   <li>Generates global @Aspect skeleton for ejb-name=* bindings</li>
 *   <li>Generates targeted @Aspect skeletons for specific EJB bindings</li>
 *   <li>Marks complex bindings (method-level, exclude-class-interceptors) with @NeedsReview</li>
 * </ul>
 * <p>
 * Depends on GAP-INT-001 which transforms the interceptor implementation classes.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEjbJarInterceptorsToAop extends ScanningRecipe<MigrateEjbJarInterceptorsToAop.Accumulator> {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Migrate ejb-jar.xml Interceptor Bindings to Spring AOP";
    }

    @Override
    public String getDescription() {
        return "Parses META-INF/ejb-jar.xml interceptor-bindings and generates Spring AOP @Aspect " +
               "pointcut configurations. Global bindings (ejb-name=*) become global pointcuts, " +
               "specific bindings become targeted pointcuts. Complex bindings are marked for manual review.";
    }

    static class Accumulator {
        List<InterceptorBindingInfo> bindings = new ArrayList<>();
        Set<Path> existingSourcePaths = new LinkedHashSet<>();
        Map<String, ModuleInfo> modules = new LinkedHashMap<>();
    }

    static class ModuleInfo {
        final String mainSourceRoot;
        final Set<String> packages = new LinkedHashSet<>();

        ModuleInfo(String mainSourceRoot) {
            this.mainSourceRoot = mainSourceRoot;
        }
    }

    static class InterceptorBindingInfo {
        final String ejbName;
        final List<String> interceptorClasses;
        final boolean isGlobal;
        final boolean hasMethodBinding;
        final boolean hasExcludeClassInterceptors;
        final boolean hasExcludeDefaultInterceptors;
        final boolean hasInterceptorOrder;
        final String sourcePath;
        final String moduleSourceRoot;

        InterceptorBindingInfo(String ejbName, List<String> interceptorClasses, boolean isGlobal,
                               boolean hasMethodBinding, boolean hasExcludeClassInterceptors,
                               boolean hasExcludeDefaultInterceptors, boolean hasInterceptorOrder,
                               String sourcePath, String moduleSourceRoot) {
            this.ejbName = ejbName;
            this.interceptorClasses = interceptorClasses;
            this.isGlobal = isGlobal;
            this.hasMethodBinding = hasMethodBinding;
            this.hasExcludeClassInterceptors = hasExcludeClassInterceptors;
            this.hasExcludeDefaultInterceptors = hasExcludeDefaultInterceptors;
            this.hasInterceptorOrder = hasInterceptorOrder;
            this.sourcePath = sourcePath;
            this.moduleSourceRoot = moduleSourceRoot;
        }

        boolean isComplex() {
            return hasMethodBinding || hasExcludeClassInterceptors || hasExcludeDefaultInterceptors;
        }

        /**
         * Returns true if this binding has ordering concerns (explicit order or multiple interceptors).
         */
        boolean hasOrderingConcerns() {
            return hasInterceptorOrder || interceptorClasses.size() > 1;
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
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    Path sourcePath = sourceFile.getSourcePath();
                    if (sourcePath != null) {
                        acc.existingSourcePaths.add(sourcePath);
                    }

                    // Track Java packages for determining target package
                    if (sourceFile instanceof org.openrewrite.java.tree.J.CompilationUnit) {
                        org.openrewrite.java.tree.J.CompilationUnit cu =
                                (org.openrewrite.java.tree.J.CompilationUnit) sourceFile;
                        String pathString = normalizePath(sourcePath);
                        ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                                extractProjectRoot(sourcePath));
                        if (!config.isMainSource(pathString)) {
                            return tree;
                        }
                        String mainSourceRoot = extractMainSourceRoot(pathString, config);
                        ModuleInfo module = acc.modules.computeIfAbsent(
                                mainSourceRoot, ModuleInfo::new);
                        if (cu.getPackageDeclaration() != null) {
                            module.packages.add(cu.getPackageDeclaration().getPackageName());
                        }
                        return tree;
                    }

                    // Parse ejb-jar.xml
                    if (sourceFile instanceof Xml.Document) {
                        String pathString = normalizePath(sourcePath);
                        if (pathString.endsWith("ejb-jar.xml")) {
                            Xml.Document doc = (Xml.Document) sourceFile;
                            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                                    extractProjectRoot(sourcePath));
                            String mainSourceRoot = deriveMainSourceRootFromResource(pathString, config);
                            parseEjbJarXml(doc, pathString, mainSourceRoot, acc);
                        }
                    }
                }
                return tree;
            }
        };
    }

    private void parseEjbJarXml(Xml.Document doc, String sourcePath, String moduleSourceRoot, Accumulator acc) {
        Xml.Tag root = doc.getRoot();
        if (root == null || !"ejb-jar".equals(root.getName())) {
            return;
        }

        // Find assembly-descriptor
        Xml.Tag assemblyDescriptor = findChildTag(root, "assembly-descriptor");
        if (assemblyDescriptor == null) {
            return;
        }

        // Parse interceptor-bindings
        for (Content content : assemblyDescriptor.getContent()) {
            if (content instanceof Xml.Tag) {
                Xml.Tag tag = (Xml.Tag) content;
                if ("interceptor-binding".equals(tag.getName())) {
                    InterceptorBindingInfo binding = parseInterceptorBinding(tag, sourcePath, moduleSourceRoot);
                    if (binding != null && !binding.interceptorClasses.isEmpty()) {
                        acc.bindings.add(binding);
                    }
                }
            }
        }
    }

    private InterceptorBindingInfo parseInterceptorBinding(Xml.Tag bindingTag, String sourcePath, String moduleSourceRoot) {
        String ejbName = null;
        List<String> interceptorClasses = new ArrayList<>();
        boolean hasMethodBinding = false;
        boolean hasExcludeClassInterceptors = false;
        boolean hasExcludeDefaultInterceptors = false;
        boolean hasInterceptorOrder = false;

        for (Content content : bindingTag.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag child = (Xml.Tag) content;
            String tagName = child.getName();

            if ("ejb-name".equals(tagName)) {
                ejbName = getTagValue(child);
            } else if ("interceptor-class".equals(tagName)) {
                String className = getTagValue(child);
                if (className != null && !className.isBlank()) {
                    interceptorClasses.add(className);
                }
            } else if ("interceptor-order".equals(tagName)) {
                // MKR-001: Track explicit interceptor-order for @Order guidance
                hasInterceptorOrder = true;
                // Extract ordered interceptor classes
                for (Content orderContent : child.getContent()) {
                    if (orderContent instanceof Xml.Tag) {
                        Xml.Tag orderChild = (Xml.Tag) orderContent;
                        if ("interceptor-class".equals(orderChild.getName())) {
                            String className = getTagValue(orderChild);
                            if (className != null && !className.isBlank() && !interceptorClasses.contains(className)) {
                                interceptorClasses.add(className);
                            }
                        }
                    }
                }
            } else if ("method".equals(tagName) || "method-name".equals(tagName)) {
                hasMethodBinding = true;
            } else if ("exclude-class-interceptors".equals(tagName)) {
                String value = getTagValue(child);
                hasExcludeClassInterceptors = "true".equalsIgnoreCase(value);
            } else if ("exclude-default-interceptors".equals(tagName)) {
                String value = getTagValue(child);
                hasExcludeDefaultInterceptors = "true".equalsIgnoreCase(value);
            }
        }

        boolean isGlobal = "*".equals(ejbName);
        return new InterceptorBindingInfo(ejbName, interceptorClasses, isGlobal, hasMethodBinding,
                hasExcludeClassInterceptors, hasExcludeDefaultInterceptors, hasInterceptorOrder, sourcePath, moduleSourceRoot);
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (acc.bindings.isEmpty()) {
            return Collections.emptyList();
        }

        List<SourceFile> generated = new ArrayList<>();

        // Group bindings by module
        Map<String, List<InterceptorBindingInfo>> bindingsByModule = new LinkedHashMap<>();
        for (InterceptorBindingInfo binding : acc.bindings) {
            bindingsByModule.computeIfAbsent(binding.moduleSourceRoot, k -> new ArrayList<>()).add(binding);
        }

        for (Map.Entry<String, List<InterceptorBindingInfo>> entry : bindingsByModule.entrySet()) {
            String moduleSourceRoot = entry.getKey();
            List<InterceptorBindingInfo> moduleBindings = entry.getValue();

            // Determine package
            String packageName = determinePackage(moduleSourceRoot, acc);

            // Generate AopConfig class with pointcut bindings
            String className = "EjbJarAopConfig";
            String packagePath = packageName.isEmpty() ? "" : packageName.replace('.', '/');
            Path targetPath = packagePath.isEmpty()
                ? Paths.get(moduleSourceRoot, className + ".java")
                : Paths.get(moduleSourceRoot, packagePath, className + ".java");

            if (acc.existingSourcePaths.contains(targetPath)) {
                continue;
            }

            String content = generateAopConfig(moduleBindings, packageName, className);
            generated.add(PlainText.builder()
                .sourcePath(targetPath)
                .text(content)
                .build());
        }

        return generated;
    }

    private String determinePackage(String moduleSourceRoot, Accumulator acc) {
        ModuleInfo module = acc.modules.get(moduleSourceRoot);
        if (module != null && !module.packages.isEmpty()) {
            String commonPrefix = commonPackagePrefix(module.packages);
            if (!commonPrefix.isEmpty()) {
                return commonPrefix + ".config";
            }
            return module.packages.iterator().next() + ".config";
        }
        return "aop.config";
    }

    private String generateAopConfig(List<InterceptorBindingInfo> bindings, String packageName, String className) {
        StringBuilder sb = new StringBuilder();
        sb.append("package ").append(packageName).append(";\n\n");
        sb.append("import ").append(NEEDS_REVIEW_FQN).append(";\n");
        sb.append("import org.aspectj.lang.ProceedingJoinPoint;\n");
        sb.append("import org.aspectj.lang.annotation.Around;\n");
        sb.append("import org.aspectj.lang.annotation.Aspect;\n");
        sb.append("import org.aspectj.lang.annotation.Pointcut;\n");
        sb.append("import org.springframework.stereotype.Component;\n");
        sb.append("\n");

        // Always add @NeedsReview - interceptor wiring is not automated
        List<String> reviewReasons = new ArrayList<>();
        reviewReasons.add("Interceptor wiring not generated; manual binding required");

        boolean hasGlobal = bindings.stream().anyMatch(b -> b.isGlobal);
        if (hasGlobal) {
            reviewReasons.add("Global binding (ejb-name=*) requires package-specific pointcut refinement");
        }

        boolean hasSpecific = bindings.stream().anyMatch(b -> !b.isGlobal);
        if (hasSpecific) {
            reviewReasons.add("ejb-name may not match class name; verify pointcut targets");
        }

        for (InterceptorBindingInfo binding : bindings) {
            if (binding.isComplex()) {
                reviewReasons.add(formatBindingReason(binding));
            }
        }

        // MKR-001: Check if any binding has ordering concerns
        boolean hasOrderingConcerns = bindings.stream().anyMatch(InterceptorBindingInfo::hasOrderingConcerns);

        sb.append("@NeedsReview(\n");
        sb.append("    reason = \"ejb-jar.xml interceptor-bindings require manual wiring\",\n");
        sb.append("    category = NeedsReview.Category.MANUAL_MIGRATION,\n");
        sb.append("    originalCode = \"").append(escapeJava(String.join("; ", reviewReasons))).append("\",\n");
        // MKR-001: Add @Order guidance when ordering is critical
        String suggestedAction = "1. Wire interceptor beans via constructor injection. " +
                  "2. Adjust pointcuts to match actual bean classes. " +
                  "3. Call interceptor's @AroundInvoke method in advice.";
        if (hasOrderingConcerns) {
            suggestedAction += " 4. Configure @Order on @Aspect classes to preserve interceptor execution sequence.";
        }
        sb.append("    suggestedAction = \"").append(suggestedAction).append("\"\n");
        sb.append(")\n");

        sb.append("@Aspect\n");
        sb.append("@Component\n");
        sb.append("public class ").append(className).append(" {\n\n");

        // Generate pointcuts and around advice for each binding
        int bindingIndex = 0;
        for (InterceptorBindingInfo binding : bindings) {
            bindingIndex++;
            generateBindingMethods(sb, binding, bindingIndex);
        }

        sb.append("}\n");
        return sb.toString();
    }

    private void generateBindingMethods(StringBuilder sb, InterceptorBindingInfo binding, int index) {
        String pointcutName = binding.isGlobal ? "globalInterceptorPointcut" : "ejbInterceptorPointcut" + index;
        String adviceName = binding.isGlobal ? "applyGlobalInterceptor" : "applyEjbInterceptor" + index;

        // Generate source reference comment
        sb.append("    // Source: ").append(binding.sourcePath).append("\n");
        sb.append("    // ejb-name: ").append(binding.ejbName).append("\n");
        for (String interceptorClass : binding.interceptorClasses) {
            sb.append("    // interceptor-class: ").append(interceptorClass).append("\n");
        }

        if (binding.isComplex()) {
            sb.append("    // WARNING: Complex binding detected - requires manual review\n");
            if (binding.hasMethodBinding) {
                sb.append("    // - Contains method-level binding\n");
            }
            if (binding.hasExcludeClassInterceptors) {
                sb.append("    // - Contains exclude-class-interceptors=true\n");
            }
            if (binding.hasExcludeDefaultInterceptors) {
                sb.append("    // - Contains exclude-default-interceptors=true\n");
            }
        }

        // Generate pointcut
        sb.append("    @Pointcut(\"");
        if (binding.isGlobal) {
            // Global binding in EJB means "all EJBs", not "all beans"
            // Use a placeholder token that must be replaced with the actual EJB package(s)
            sb.append("within(__YOUR_EJB_PACKAGE__..*) && execution(* *(..))");
            sb.append("\")\n");
            sb.append("    // PLACEHOLDER: Replace '__YOUR_EJB_PACKAGE__' with your actual EJB package (e.g., 'com.mycompany.ejb')\n");
        } else {
            // Targeted pointcut: PLACEHOLDER - ejb-name is not guaranteed to match class name
            // Using ejb-name as a starting point but it MUST be verified
            sb.append("within(__VERIFY_PACKAGE__..").append(sanitizeIdentifier(binding.ejbName)).append(") && execution(* *(..))");
            sb.append("\")\n");
            sb.append("    // PLACEHOLDER: This pointcut is likely INCORRECT. ejb-name '").append(binding.ejbName)
              .append("' may not match the actual class name. Replace '__VERIFY_PACKAGE__' and verify the class.\n");
        }
        sb.append("    public void ").append(pointcutName).append("() {}\n\n");

        // Generate around advice (delegating to interceptor)
        sb.append("    // TODO: Wire interceptor bean(s) via constructor injection\n");
        for (String interceptorClass : binding.interceptorClasses) {
            String simpleClassName = extractSimpleClassName(interceptorClass);
            sb.append("    // private final ").append(simpleClassName).append(" ")
              .append(toLowerCamelCase(simpleClassName)).append(";\n");
        }
        sb.append("\n");

        sb.append("    @Around(\"").append(pointcutName).append("()\")\n");
        sb.append("    public Object ").append(adviceName).append("(ProceedingJoinPoint pjp) throws Throwable {\n");
        sb.append("        // SKELETON: Interceptors are NOT invoked - this is a placeholder only!\n");
        sb.append("        // TODO: Wire interceptor beans and invoke their @AroundInvoke method\n");
        sb.append("        // The method name varies per interceptor class (find the method annotated with @AroundInvoke)\n");
        for (String interceptorClass : binding.interceptorClasses) {
            String simpleClassName = extractSimpleClassName(interceptorClass);
            sb.append("        // Example: return ").append(toLowerCamelCase(simpleClassName))
              .append(".<aroundInvokeMethod>(pjp);\n");
        }
        sb.append("        return pjp.proceed(); // PLACEHOLDER: Replace with actual interceptor chain\n");
        sb.append("    }\n\n");
    }

    private String formatBindingReason(InterceptorBindingInfo binding) {
        StringBuilder sb = new StringBuilder();
        sb.append("ejb-name=").append(binding.ejbName);
        if (binding.hasMethodBinding) {
            sb.append(", method-binding");
        }
        if (binding.hasExcludeClassInterceptors) {
            sb.append(", exclude-class-interceptors");
        }
        if (binding.hasExcludeDefaultInterceptors) {
            sb.append(", exclude-default-interceptors");
        }
        return sb.toString();
    }

    // Utility methods

    private static @Nullable Xml.Tag findChildTag(Xml.Tag parent, String name) {
        for (Content content : parent.getContent()) {
            if (content instanceof Xml.Tag) {
                Xml.Tag tag = (Xml.Tag) content;
                if (name.equals(tag.getName())) {
                    return tag;
                }
            }
        }
        return null;
    }

    private static @Nullable String getTagValue(Xml.Tag tag) {
        return tag.getValue().map(String::trim).orElse(null);
    }

    private static String normalizePath(Path sourcePath) {
        if (sourcePath == null) {
            return "";
        }
        return sourcePath.toString().replace('\\', '/');
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

    private static String deriveMainSourceRootFromResource(String resourcePath, ProjectConfiguration config) {
        if (resourcePath == null) {
            return "src/main/java";
        }
        String normalized = resourcePath.replace('\\', '/');
        String mainRoot = config.getMainSourceRoots().isEmpty() ? "src/main/java" : config.getMainSourceRoots().get(0);
        for (String root : config.getResourceRoots()) {
            String normalizedRoot = root.replace('\\', '/');
            String marker = "/" + normalizedRoot + "/";
            int idx = normalized.indexOf(marker);
            if (idx >= 0) {
                String modulePrefix = normalized.substring(0, idx);
                if (modulePrefix.isEmpty()) {
                    return mainRoot;
                }
                return modulePrefix + "/" + mainRoot;
            }
            if (normalized.startsWith(normalizedRoot + "/")) {
                return mainRoot;
            }
        }
        return mainRoot;
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
        return sourcePath.toAbsolutePath().getParent();
    }

    private static String commonPackagePrefix(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return "";
        }
        String first = packages.iterator().next();
        if (packages.size() == 1) {
            return first;
        }
        String[] baseParts = first.split("\\.");
        int commonLength = baseParts.length;
        for (String pkg : packages) {
            String[] parts = pkg.split("\\.");
            commonLength = Math.min(commonLength, parts.length);
            for (int i = 0; i < commonLength; i++) {
                if (!baseParts[i].equals(parts[i])) {
                    commonLength = i;
                    break;
                }
            }
            if (commonLength == 0) {
                break;
            }
        }
        if (commonLength == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < commonLength; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(baseParts[i]);
        }
        return sb.toString();
    }

    private static String sanitizeIdentifier(String raw) {
        if (raw == null || raw.isBlank()) {
            return "EjbBean";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                sb.append(ch);
            }
        }
        if (sb.length() == 0) {
            return "EjbBean";
        }
        if (!Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        return sb.toString();
    }

    private static String extractSimpleClassName(String fqn) {
        if (fqn == null) {
            return "Interceptor";
        }
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    private static String toLowerCamelCase(String name) {
        if (name == null || name.isEmpty()) {
            return "interceptor";
        }
        return Character.toLowerCase(name.charAt(0)) + name.substring(1);
    }

    private static String escapeJava(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
