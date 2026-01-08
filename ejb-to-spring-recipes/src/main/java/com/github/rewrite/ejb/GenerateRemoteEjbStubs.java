package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.text.PlainText;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Generates REST/gRPC stub classes for @Remote EJB interfaces/classes.
 * <p>
 * This is a prototype to scaffold remote migration by generating placeholder
 * stubs with method signatures and TODO bodies.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class GenerateRemoteEjbStubs extends ScanningRecipe<GenerateRemoteEjbStubs.Accumulator> {

    @Option(displayName = "Strategy",
            description = "Stub strategy: 'rest' (default) or 'grpc'.",
            example = "rest",
            required = false)
    @Nullable
    String strategy;

    @Option(displayName = "Target package suffix",
            description = "Suffix appended to the remote type package. Empty keeps original package.",
            example = "remote.stub",
            required = false)
    @Nullable
    String targetPackageSuffix;

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final Set<String> VALID_STRATEGIES = Set.of("rest", "grpc");

    public GenerateRemoteEjbStubs() {
        this.strategy = null;
        this.targetPackageSuffix = null;
    }

    public GenerateRemoteEjbStubs(@Nullable String strategy, @Nullable String targetPackageSuffix) {
        this.strategy = strategy;
        this.targetPackageSuffix = targetPackageSuffix;
    }

    @Override
    public String getDisplayName() {
        return "Generate Remote EJB stubs (REST/gRPC)";
    }

    @Override
    public String getDescription() {
        return "Generates placeholder REST or gRPC stubs for @Remote EJB interfaces/classes. " +
               "Produces skeleton methods with TODO bodies and @NeedsReview guidance.";
    }

    static class Accumulator {
        Map<String, RemoteTypeInfo> remoteTypes = new LinkedHashMap<>();
        Set<Path> existingSourcePaths = new HashSet<>();
        String strategyWarning;
    }

    static class RemoteTypeInfo {
        final String fqn;
        final String packageName;
        final String simpleName;
        final String mainSourceRoot;
        final List<MethodStub> methods;

        RemoteTypeInfo(String fqn, String packageName, String simpleName, String mainSourceRoot, List<MethodStub> methods) {
            this.fqn = fqn;
            this.packageName = packageName;
            this.simpleName = simpleName;
            this.mainSourceRoot = mainSourceRoot;
            this.methods = methods;
        }
    }

    static class MethodStub {
        final String name;
        final String returnType;
        final List<ParamStub> params;

        MethodStub(String name, String returnType, List<ParamStub> params) {
            this.name = name;
            this.returnType = returnType;
            this.params = params;
        }
    }

    static class ParamStub {
        final String type;
        final String name;

        ParamStub(String type, String name) {
            this.type = type;
            this.name = name;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        JavaIsoVisitor<ExecutionContext> javaScanner = new JavaIsoVisitor<ExecutionContext>() {
            private static final AnnotationMatcher REMOTE_JAVAX_MATCHER =
                new AnnotationMatcher("@javax.ejb.Remote");
            private static final AnnotationMatcher REMOTE_JAKARTA_MATCHER =
                new AnnotationMatcher("@jakarta.ejb.Remote");

            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                J.CompilationUnit updated = super.visitCompilationUnit(cu, ctx);
                if (cu.getSourcePath() != null) {
                    acc.existingSourcePaths.add(cu.getSourcePath());
                }

                if (cu.getPackageDeclaration() == null || cu.getSourcePath() == null) {
                    return updated;
                }

                String sourcePath = cu.getSourcePath().toString().replace('\\', '/');
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(cu.getSourcePath()));
                if (!config.isMainSource(sourcePath)) {
                    return updated;
                }
                String mainSourceRoot = extractMainSourceRoot(sourcePath, config);

                for (J.ClassDeclaration classDecl : cu.getClasses()) {
                    if (!hasRemoteAnnotation(classDecl)) {
                        continue;
                    }
                    if (classDecl.getType() == null) {
                        continue;
                    }
                    String fqn = classDecl.getType().getFullyQualifiedName();
                    if (acc.remoteTypes.containsKey(fqn)) {
                        continue;
                    }
                    String packageName = cu.getPackageDeclaration().getPackageName();
                    String simpleName = classDecl.getSimpleName();
                    List<MethodStub> methods = extractMethods(classDecl);
                    acc.remoteTypes.put(fqn, new RemoteTypeInfo(fqn, packageName, simpleName, mainSourceRoot, methods));
                }
                return updated;
            }

            private boolean hasRemoteAnnotation(J.ClassDeclaration classDecl) {
                for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                    if (REMOTE_JAVAX_MATCHER.matches(ann) || REMOTE_JAKARTA_MATCHER.matches(ann)) {
                        return true;
                    }
                }
                return false;
            }

            private List<MethodStub> extractMethods(J.ClassDeclaration classDecl) {
                List<MethodStub> methods = new ArrayList<>();
                if (classDecl.getBody() == null) {
                    return methods;
                }
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (!(stmt instanceof J.MethodDeclaration)) {
                        continue;
                    }
                    J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                    if (method.isConstructor() || isStatic(method)) {
                        continue;
                    }
                    String returnType = resolveTypeName(method.getReturnTypeExpression());
                    List<ParamStub> params = new ArrayList<>();
                    for (Statement param : method.getParameters()) {
                        if (!(param instanceof J.VariableDeclarations)) {
                            continue;
                        }
                        J.VariableDeclarations varDecls = (J.VariableDeclarations) param;
                        String typeName = resolveTypeName(varDecls.getTypeExpression());
                        if (varDecls.getVarargs() != null) {
                            typeName = typeName + "...";
                        }
                        for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                            params.add(new ParamStub(typeName, var.getSimpleName()));
                        }
                    }
                    methods.add(new MethodStub(method.getSimpleName(), returnType, params));
                }
                return methods;
            }

            private boolean isStatic(J.MethodDeclaration method) {
                for (J.Modifier modifier : method.getModifiers()) {
                    if (modifier.getType() == J.Modifier.Type.Static) {
                        return true;
                    }
                }
                return false;
            }
        };
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof PlainText) {
                    PlainText text = (PlainText) tree;
                    acc.existingSourcePaths.add(text.getSourcePath());
                    return text;
                }
                if (tree instanceof J.CompilationUnit) {
                    return javaScanner.visit(tree, ctx);
                }
                return tree;
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (acc.remoteTypes.isEmpty()) {
            return List.of();
        }

        String effectiveStrategy = resolveStrategy(acc);
        if (acc.strategyWarning != null) {
            ctx.getOnError().accept(new IllegalArgumentException(acc.strategyWarning));
        }
        String packageSuffix = targetPackageSuffix == null ? "remote.stub" : targetPackageSuffix.trim();

        List<SourceFile> generated = new ArrayList<>();
        for (RemoteTypeInfo info : acc.remoteTypes.values()) {
            String targetPackage = resolveTargetPackage(info.packageName, packageSuffix);
            String className = deriveStubClassName(info.simpleName, effectiveStrategy);
            String packagePath = targetPackage.isEmpty() ? "" : targetPackage.replace('.', '/');
            Path targetPath = packagePath.isEmpty()
                ? Paths.get(info.mainSourceRoot, className + ".java")
                : Paths.get(info.mainSourceRoot, packagePath, className + ".java");

            if (acc.existingSourcePaths.contains(targetPath)) {
                continue;
            }

            String source = generateStubSource(info, targetPackage, className, effectiveStrategy);
            generated.add(PlainText.builder()
                .sourcePath(targetPath)
                .text(source)
                .build());
        }
        return generated;
    }

    private String resolveStrategy(Accumulator acc) {
        String effective = strategy == null ? "" : strategy.trim().toLowerCase(Locale.ROOT);
        if (effective.isEmpty()) {
            return "rest";
        }
        if (!VALID_STRATEGIES.contains(effective)) {
            if (acc.strategyWarning == null) {
                acc.strategyWarning = "GenerateRemoteEjbStubs: Unknown strategy '" + strategy +
                    "', defaulting to 'rest'. Valid strategies: " + VALID_STRATEGIES;
            }
            return "rest";
        }
        return effective;
    }

    private String resolveTargetPackage(String basePackage, String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return basePackage == null ? "" : basePackage;
        }
        String trimmed = suffix.trim();
        if (basePackage == null || basePackage.isBlank()) {
            return trimmed;
        }
        if (basePackage.endsWith("." + trimmed) || basePackage.equals(trimmed)) {
            return basePackage;
        }
        return basePackage + "." + trimmed;
    }

    private String deriveStubClassName(String simpleName, String strategy) {
        if ("grpc".equals(strategy)) {
            return simpleName + "GrpcService";
        }
        return simpleName + "RemoteController";
    }

    private String generateStubSource(RemoteTypeInfo info, String targetPackage, String className, String strategy) {
        StringBuilder sb = new StringBuilder();
        if (!targetPackage.isEmpty()) {
            sb.append("package ").append(targetPackage).append(";\n\n");
        }

        sb.append("import ").append(NEEDS_REVIEW_FQN).append(";\n");
        if ("grpc".equals(strategy)) {
            sb.append("import org.springframework.stereotype.Service;\n\n");
        } else {
            sb.append("import org.springframework.web.bind.annotation.PostMapping;\n");
            sb.append("import org.springframework.web.bind.annotation.RequestMapping;\n");
            sb.append("import org.springframework.web.bind.annotation.RestController;\n\n");
        }

        String reason = "grpc".equals(strategy)
            ? "Remote EJB should be migrated to gRPC service"
            : "Remote EJB should be migrated to REST controller";
        String suggestedAction = "grpc".equals(strategy)
            ? "Define .proto and implement gRPC service for " + info.fqn
            : "Design REST endpoints and implement controller for " + info.fqn;

        sb.append("@NeedsReview(reason = \"").append(escape(reason)).append("\", ");
        sb.append("category = NeedsReview.Category.REMOTE_ACCESS, ");
        sb.append("originalCode = \"@Remote\", ");
        sb.append("suggestedAction = \"").append(escape(suggestedAction)).append("\")\n");

        if ("grpc".equals(strategy)) {
            sb.append("@Service\n");
        } else {
            sb.append("@RestController\n");
            sb.append("@RequestMapping(\"/remote/").append(info.simpleName).append("\")\n");
        }

        sb.append("public class ").append(className).append(" {\n");
        sb.append("    // TODO: Generated from ").append(info.fqn).append("\n");
        if (info.methods.isEmpty()) {
            sb.append("}\n");
            return sb.toString();
        }
        for (MethodStub method : info.methods) {
            if (!"grpc".equals(strategy)) {
                sb.append("    @PostMapping(\"/").append(method.name).append("\")\n");
            }
            sb.append("    public ").append(method.returnType).append(" ").append(method.name).append("(");
            for (int i = 0; i < method.params.size(); i++) {
                ParamStub param = method.params.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(param.type).append(" ").append(param.name);
            }
            sb.append(") {\n");
            sb.append("        throw new UnsupportedOperationException(\"TODO: implement ").append(method.name).append("\");\n");
            sb.append("    }\n\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String resolveTypeName(@Nullable TypeTree typeExpr) {
        if (typeExpr == null) {
            return "void";
        }
        if (typeExpr instanceof TypedTree) {
            JavaType type = ((TypedTree) typeExpr).getType();
            if (type != null) {
                return TypeUtils.toString(type).replace('$', '.');
            }
        }
        return typeExpr.printTrimmed();
    }

    private static String escape(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
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
}
