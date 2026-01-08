package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Adds @EnableJms, @EnableScheduling, and @EnableAsync annotations to Spring configuration classes.
 * <p>
 * This recipe scans for usage of:
 * - @JmsListener -> adds @EnableJms to a @Configuration class
 * - @Scheduled -> adds @EnableScheduling to a @Configuration class
 * - @Async -> adds @EnableAsync to a @Configuration class
 * <p>
 * If no @Configuration class exists, a new configuration class is generated.
 * When no package is declared, the configuration is generated in the default package.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddEnableJmsAndScheduling extends ScanningRecipe<AddEnableJmsAndScheduling.Accumulator> {

    private static final String JMS_LISTENER_FQN = "org.springframework.jms.annotation.JmsListener";
    private static final String SCHEDULED_FQN = "org.springframework.scheduling.annotation.Scheduled";
    private static final String ASYNC_FQN = "org.springframework.scheduling.annotation.Async";
    private static final String CONFIGURATION_FQN = "org.springframework.context.annotation.Configuration";
    private static final String SPRING_BOOT_APP_FQN = "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String SPRING_BOOT_CONFIG_FQN = "org.springframework.boot.SpringBootConfiguration";
    private static final String ENABLE_JMS_FQN = "org.springframework.jms.annotation.EnableJms";
    private static final String ENABLE_SCHEDULING_FQN = "org.springframework.scheduling.annotation.EnableScheduling";
    private static final String ENABLE_ASYNC_FQN = "org.springframework.scheduling.annotation.EnableAsync";

    @Override
    public String getDisplayName() {
        return "Add @EnableJms, @EnableScheduling, and @EnableAsync";
    }

    @Override
    public String getDescription() {
        return "Adds @EnableJms when @JmsListener is used, @EnableScheduling when @Scheduled is used, " +
               "and @EnableAsync when @Async is used. " +
               "Annotations are added to an existing @Configuration class or a new configuration class is generated.";
    }

    static class Accumulator {
        Map<Path, ModuleState> modules = new HashMap<>();
    }

    static class ModuleState {
        final Path moduleRoot;
        boolean hasJmsListener = false;
        boolean hasScheduled = false;
        boolean hasAsync = false;
        boolean hasEnableJms = false;
        boolean hasEnableScheduling = false;
        boolean hasEnableAsync = false;
        String configurationClassPath = null;
        String configurationClassName = null;
        String springBootApplicationPath = null;
        String springBootApplicationPackage = null;
        String springBootApplicationClassName = null;
        String firstUsagePath = null;
        String mainSourceRoot = null;
        Set<String> packages = new HashSet<>();
        Set<String> usagePackages = new HashSet<>();
        Set<String> classFqns = new HashSet<>();

        ModuleState(Path moduleRoot) {
            this.moduleRoot = moduleRoot;
        }
    }

    static class EnableTargets {
        final boolean needsEnableJms;
        final boolean needsEnableScheduling;
        final boolean needsEnableAsync;
        final String targetClassName;

        EnableTargets(boolean needsEnableJms,
                      boolean needsEnableScheduling,
                      boolean needsEnableAsync,
                      String targetClassName) {
            this.needsEnableJms = needsEnableJms;
            this.needsEnableScheduling = needsEnableScheduling;
            this.needsEnableAsync = needsEnableAsync;
            this.targetClassName = targetClassName;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    /**
     * Checks if an annotation marks a class as a Spring configuration class.
     * Recognizes @Configuration, @SpringBootApplication, and @SpringBootConfiguration.
     */
    private static boolean isConfigurationAnnotation(J.Annotation a) {
        return TypeUtils.isOfClassType(a.getType(), CONFIGURATION_FQN) ||
               TypeUtils.isOfClassType(a.getType(), SPRING_BOOT_APP_FQN) ||
               TypeUtils.isOfClassType(a.getType(), SPRING_BOOT_CONFIG_FQN) ||
               "Configuration".equals(a.getSimpleName()) ||
               "SpringBootApplication".equals(a.getSimpleName()) ||
               "SpringBootConfiguration".equals(a.getSimpleName());
    }

    private static boolean isSpringBootApplicationAnnotation(J.Annotation a) {
        return TypeUtils.isOfClassType(a.getType(), SPRING_BOOT_APP_FQN) ||
               "SpringBootApplication".equals(a.getSimpleName());
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                Path cuSourcePath = cu.getSourcePath();
                if (cuSourcePath == null) {
                    return cu;
                }

                Path moduleRoot = extractProjectRoot(cuSourcePath);
                ModuleState module = acc.modules.computeIfAbsent(moduleRoot, ModuleState::new);

                String sourcePath = cuSourcePath.toString();
                String normalizedPath = sourcePath.replace('\\', '/');
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(moduleRoot);
                boolean isMainSource = config.isMainSource(normalizedPath);
                boolean isTestSource = config.isTestSource(normalizedPath);

                String pkg = cu.getPackageDeclaration() != null
                    ? cu.getPackageDeclaration().getPackageName()
                    : "";
                if (isTestSource) {
                    return cu;
                }
                module.packages.add(pkg);

                for (J.ClassDeclaration classDecl : cu.getClasses()) {
                    String fqn = pkg.isEmpty()
                        ? classDecl.getSimpleName()
                        : pkg + "." + classDecl.getSimpleName();
                    module.classFqns.add(fqn);

                    boolean isConfig = classDecl.getLeadingAnnotations().stream()
                        .anyMatch(AddEnableJmsAndScheduling::isConfigurationAnnotation);

                    if (isConfig) {
                        if (module.configurationClassPath == null) {
                            module.configurationClassPath = sourcePath;
                            module.configurationClassName = classDecl.getSimpleName();
                        }

                        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                            if (TypeUtils.isOfClassType(ann.getType(), ENABLE_JMS_FQN) ||
                                "EnableJms".equals(ann.getSimpleName())) {
                                module.hasEnableJms = true;
                            }
                            if (TypeUtils.isOfClassType(ann.getType(), ENABLE_SCHEDULING_FQN) ||
                                "EnableScheduling".equals(ann.getSimpleName())) {
                                module.hasEnableScheduling = true;
                            }
                            if (TypeUtils.isOfClassType(ann.getType(), ENABLE_ASYNC_FQN) ||
                                "EnableAsync".equals(ann.getSimpleName())) {
                                module.hasEnableAsync = true;
                            }
                        }
                    }

                    boolean isBootApp = classDecl.getLeadingAnnotations().stream()
                        .anyMatch(AddEnableJmsAndScheduling::isSpringBootApplicationAnnotation);
                    if (isBootApp && module.springBootApplicationPath == null) {
                        module.springBootApplicationPath = sourcePath;
                        module.springBootApplicationPackage = pkg;
                        module.springBootApplicationClassName = classDecl.getSimpleName();
                    }
                }

                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                        J.Annotation ann = super.visitAnnotation(annotation, ctx);
                        if (TypeUtils.isOfClassType(ann.getType(), JMS_LISTENER_FQN) ||
                            "JmsListener".equals(ann.getSimpleName())) {
                            module.hasJmsListener = true;
                            module.usagePackages.add(pkg);
                            if (module.firstUsagePath == null) {
                                module.firstUsagePath = sourcePath;
                                if (module.mainSourceRoot == null && isMainSource) {
                                    module.mainSourceRoot = extractMainSourceRoot(normalizedPath, config);
                                }
                            }
                        }
                        if (TypeUtils.isOfClassType(ann.getType(), SCHEDULED_FQN) ||
                            "Scheduled".equals(ann.getSimpleName())) {
                            module.hasScheduled = true;
                            module.usagePackages.add(pkg);
                            if (module.firstUsagePath == null) {
                                module.firstUsagePath = sourcePath;
                                if (module.mainSourceRoot == null && isMainSource) {
                                    module.mainSourceRoot = extractMainSourceRoot(normalizedPath, config);
                                }
                            }
                        }
                        if (TypeUtils.isOfClassType(ann.getType(), ASYNC_FQN) ||
                            "Async".equals(ann.getSimpleName())) {
                            module.hasAsync = true;
                            module.usagePackages.add(pkg);
                            if (module.firstUsagePath == null) {
                                module.firstUsagePath = sourcePath;
                                if (module.mainSourceRoot == null && isMainSource) {
                                    module.mainSourceRoot = extractMainSourceRoot(normalizedPath, config);
                                }
                            }
                        }
                        return ann;
                    }
                }.visit(cu, ctx);

                return cu;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        Map<String, EnableTargets> targetsByPath = new HashMap<>();

        for (ModuleState module : acc.modules.values()) {
            boolean needsEnableJms = module.hasJmsListener && !module.hasEnableJms;
            boolean needsEnableScheduling = module.hasScheduled && !module.hasEnableScheduling;
            boolean needsEnableAsync = module.hasAsync && !module.hasEnableAsync;

            if (!needsEnableJms && !needsEnableScheduling && !needsEnableAsync) {
                continue;
            }

            String targetPath = module.springBootApplicationPath != null
                ? module.springBootApplicationPath
                : module.configurationClassPath;
            String targetClassName = module.springBootApplicationPath != null
                ? module.springBootApplicationClassName
                : module.configurationClassName;

            if (targetPath != null) {
                targetsByPath.put(targetPath, new EnableTargets(
                    needsEnableJms, needsEnableScheduling, needsEnableAsync, targetClassName));
            }
        }

        if (targetsByPath.isEmpty()) {
            return TreeVisitor.noop();
        }

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                String sourcePath = cu.getSourcePath().toString();
                EnableTargets targets = targetsByPath.get(sourcePath);
                if (targets == null) {
                    return cd;
                }
                if (targets.targetClassName != null &&
                    !targets.targetClassName.equals(cd.getSimpleName())) {
                    return cd;
                }

                boolean isConfig = cd.getLeadingAnnotations().stream()
                    .anyMatch(AddEnableJmsAndScheduling::isConfigurationAnnotation);
                if (!isConfig) {
                    return cd;
                }

                List<J.Annotation> newAnnotations = new ArrayList<>(cd.getLeadingAnnotations());
                boolean modified = false;

                if (targets.needsEnableJms) {
                    boolean alreadyHas = cd.getLeadingAnnotations().stream()
                        .anyMatch(a -> TypeUtils.isOfClassType(a.getType(), ENABLE_JMS_FQN) ||
                                      "EnableJms".equals(a.getSimpleName()));
                    if (!alreadyHas) {
                        newAnnotations.add(createSimpleAnnotation("EnableJms", ENABLE_JMS_FQN, Space.format("\n")));
                        maybeAddImport(ENABLE_JMS_FQN);
                        modified = true;
                    }
                }

                if (targets.needsEnableScheduling) {
                    boolean alreadyHas = cd.getLeadingAnnotations().stream()
                        .anyMatch(a -> TypeUtils.isOfClassType(a.getType(), ENABLE_SCHEDULING_FQN) ||
                                      "EnableScheduling".equals(a.getSimpleName()));
                    if (!alreadyHas) {
                        newAnnotations.add(createSimpleAnnotation("EnableScheduling", ENABLE_SCHEDULING_FQN, Space.format("\n")));
                        maybeAddImport(ENABLE_SCHEDULING_FQN);
                        modified = true;
                    }
                }

                if (targets.needsEnableAsync) {
                    boolean alreadyHas = cd.getLeadingAnnotations().stream()
                        .anyMatch(a -> TypeUtils.isOfClassType(a.getType(), ENABLE_ASYNC_FQN) ||
                                      "EnableAsync".equals(a.getSimpleName()));
                    if (!alreadyHas) {
                        newAnnotations.add(createSimpleAnnotation("EnableAsync", ENABLE_ASYNC_FQN, Space.format("\n")));
                        maybeAddImport(ENABLE_ASYNC_FQN);
                        modified = true;
                    }
                }

                if (modified) {
                    cd = cd.withLeadingAnnotations(newAnnotations);
                }

                return cd;
            }

            private J.Annotation createSimpleAnnotation(String simpleName, String fqn, Space prefix) {
                JavaType.ShallowClass type = JavaType.ShallowClass.build(fqn);
                J.Identifier ident = new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    simpleName,
                    type,
                    null
                );

                return new J.Annotation(
                    Tree.randomId(),
                    prefix,
                    Markers.EMPTY,
                    ident,
                    null
                );
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
            boolean needsEnableJms = module.hasJmsListener && !module.hasEnableJms;
            boolean needsEnableScheduling = module.hasScheduled && !module.hasEnableScheduling;
            boolean needsEnableAsync = module.hasAsync && !module.hasEnableAsync;

            if (!needsEnableJms && !needsEnableScheduling && !needsEnableAsync) {
                continue;
            }
            if (module.springBootApplicationPath != null || module.configurationClassPath != null) {
                continue;
            }

            String basePackage = module.springBootApplicationPackage != null
                ? module.springBootApplicationPackage
                : findCommonPackagePrefix(module.usagePackages.isEmpty()
                    ? module.packages
                    : module.usagePackages);
            if (basePackage == null) {
                basePackage = "";
            }

            String className = deriveConfigurationClassName(basePackage, module.classFqns);
            String source = generateConfigurationSource(basePackage, className, needsEnableJms,
                needsEnableScheduling, needsEnableAsync);

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
        String baseName = "MigrationConfiguration";
        String candidate = baseName;
        int counter = 2;
        String prefix = basePackage.isEmpty() ? "" : basePackage + ".";
        while (existingFqns.contains(prefix + candidate)) {
            candidate = baseName + counter;
            counter++;
        }
        return candidate;
    }

    private static String generateConfigurationSource(String basePackage,
                                                      String className,
                                                      boolean needsEnableJms,
                                                      boolean needsEnableScheduling,
                                                      boolean needsEnableAsync) {
        StringBuilder builder = new StringBuilder();
        if (!basePackage.isEmpty()) {
            builder.append("package ").append(basePackage).append(";\n\n");
        }

        List<String> imports = new ArrayList<>();
        imports.add("org.springframework.context.annotation.Configuration");
        if (needsEnableJms) {
            imports.add("org.springframework.jms.annotation.EnableJms");
        }
        if (needsEnableScheduling) {
            imports.add("org.springframework.scheduling.annotation.EnableScheduling");
        }
        if (needsEnableAsync) {
            imports.add("org.springframework.scheduling.annotation.EnableAsync");
        }

        for (String fqn : imports) {
            builder.append("import ").append(fqn).append(";\n");
        }
        builder.append("\n");

        builder.append("@Configuration\n");
        if (needsEnableJms) {
            builder.append("@EnableJms\n");
        }
        if (needsEnableScheduling) {
            builder.append("@EnableScheduling\n");
        }
        if (needsEnableAsync) {
            builder.append("@EnableAsync\n");
        }

        builder.append("public class ").append(className).append(" {\n");
        builder.append("}\n");

        return builder.toString();
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
}
