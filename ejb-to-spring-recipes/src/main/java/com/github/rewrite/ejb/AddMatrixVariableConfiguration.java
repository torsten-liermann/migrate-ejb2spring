package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.NameTree;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Adds a Spring WebMvcConfigurer that enables matrix variables when @MatrixVariable is used.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddMatrixVariableConfiguration extends ScanningRecipe<AddMatrixVariableConfiguration.Accumulator> {

    private static final String MATRIX_VARIABLE_FQN = "org.springframework.web.bind.annotation.MatrixVariable";
    private static final String MATRIX_PARAM_JAVAX = "javax.ws.rs.MatrixParam";
    private static final String MATRIX_PARAM_JAKARTA = "jakarta.ws.rs.MatrixParam";
    private static final String SPRING_BOOT_APP_FQN = "org.springframework.boot.autoconfigure.SpringBootApplication";
    private static final String MATRIX_CONFIG_METHOD = "setRemoveSemicolonContent";
    private static final String PATTERN_PARSER_METHOD = "setPatternParser";
    private static final String URL_PATH_HELPER_FQN = "org.springframework.web.util.UrlPathHelper";
    private static final String PATH_MATCH_CONFIGURER_FQN = "org.springframework.web.servlet.config.annotation.PathMatchConfigurer";

    static class Accumulator {
        Map<Path, ModuleState> modules = new HashMap<>();
    }

    static class ModuleState {
        final Path moduleRoot;
        boolean usesMatrixVariables = false;
        boolean hasMatrixConfig = false;
        String firstMatrixUsagePath = null;
        String mainSourceRoot = null;
        String springBootApplicationPath = null;
        String springBootApplicationPackage = null;
        Set<String> packages = new HashSet<>();
        Set<String> usagePackages = new HashSet<>();
        Set<String> classFqns = new HashSet<>();

        ModuleState(Path moduleRoot) {
            this.moduleRoot = moduleRoot;
        }
    }

    @Override
    public String getDisplayName() {
        return "Add WebMvcConfigurer to enable matrix variables";
    }

    @Override
    public String getDescription() {
        return "Generates a WebMvcConfigurer that enables matrix variables when @MatrixVariable is used.";
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
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

                    boolean isBootApp = classDecl.getLeadingAnnotations().stream()
                        .anyMatch(AddMatrixVariableConfiguration::isSpringBootApplicationAnnotation);
                    if (isBootApp && module.springBootApplicationPath == null) {
                        module.springBootApplicationPath = sourcePath;
                        module.springBootApplicationPackage = pkg;
                    }
                }

                new JavaIsoVisitor<ExecutionContext>() {
                    @Override
                    public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                        J.Annotation ann = super.visitAnnotation(annotation, ctx);
                        String simpleName = ann.getSimpleName();
                        if (TypeUtils.isOfClassType(ann.getType(), MATRIX_VARIABLE_FQN) ||
                            TypeUtils.isOfClassType(ann.getType(), MATRIX_PARAM_JAVAX) ||
                            TypeUtils.isOfClassType(ann.getType(), MATRIX_PARAM_JAKARTA) ||
                            "MatrixVariable".equals(simpleName) ||
                            "MatrixParam".equals(simpleName)) {
                            module.usesMatrixVariables = true;
                            module.usagePackages.add(pkg);
                            if (module.firstMatrixUsagePath == null && sourcePath != null) {
                                module.firstMatrixUsagePath = sourcePath;
                                if (module.mainSourceRoot == null && isMainSource) {
                                    module.mainSourceRoot = extractMainSourceRoot(normalizedPath, config);
                                }
                            }
                        }
                        return ann;
                    }

                    @Override
                    public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                        J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                        if (MATRIX_CONFIG_METHOD.equals(m.getSimpleName())) {
                            if (isMethodOnType(m, getCursor(), URL_PATH_HELPER_FQN)) {
                                List<Expression> args = m.getArguments();
                                if (args.size() == 1 && args.get(0) instanceof J.Literal) {
                                    Object value = ((J.Literal) args.get(0)).getValue();
                                    if (Boolean.FALSE.equals(value)) {
                                        module.hasMatrixConfig = true;
                                    }
                                }
                            }
                        } else if (PATTERN_PARSER_METHOD.equals(m.getSimpleName())) {
                            if (isMethodOnType(m, getCursor(), PATH_MATCH_CONFIGURER_FQN)) {
                                module.hasMatrixConfig = true;
                            }
                        }
                        return m;
                    }
                }.visit(cu, ctx);

                return cu;
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
            if (!module.usesMatrixVariables || module.hasMatrixConfig) {
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
            String source = generateConfigurationSource(basePackage, className);

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

    private static boolean isSpringBootApplicationAnnotation(J.Annotation annotation) {
        return TypeUtils.isOfClassType(annotation.getType(), SPRING_BOOT_APP_FQN) ||
               "SpringBootApplication".equals(annotation.getSimpleName());
    }

    private static boolean isMethodOnType(J.MethodInvocation method, Cursor cursor, String fqn) {
        JavaType.Method methodType = method.getMethodType();
        if (methodType != null && TypeUtils.isOfClassType(methodType.getDeclaringType(), fqn)) {
            return true;
        }
        Expression select = method.getSelect();
        if (select != null && TypeUtils.isOfClassType(select.getType(), fqn)) {
            return true;
        }
        if (!(select instanceof J.Identifier)) {
            return false;
        }

        String selectName = ((J.Identifier) select).getSimpleName();
        String simpleName = simpleNameFromFqn(fqn);

        J.MethodDeclaration methodDecl = cursor.firstEnclosing(J.MethodDeclaration.class);
        if (methodDecl != null) {
            if (matchesVariableDeclarations(methodDecl.getParameters(), selectName, simpleName, fqn)) {
                return true;
            }
            if (methodDecl.getBody() != null &&
                matchesVariableDeclarations(methodDecl.getBody().getStatements(), selectName, simpleName, fqn)) {
                return true;
            }
        }

        J.ClassDeclaration classDecl = cursor.firstEnclosing(J.ClassDeclaration.class);
        if (classDecl != null && classDecl.getBody() != null &&
            matchesVariableDeclarations(classDecl.getBody().getStatements(), selectName, simpleName, fqn)) {
            return true;
        }

        return false;
    }

    private static boolean matchesVariableDeclarations(List<Statement> statements,
                                                       String selectName,
                                                       String simpleName,
                                                       String fqn) {
        for (Statement statement : statements) {
            if (statement instanceof J.VariableDeclarations) {
                if (matchesVariableDeclaration((J.VariableDeclarations) statement, selectName, simpleName, fqn)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesVariableDeclaration(J.VariableDeclarations declaration,
                                                      String selectName,
                                                      String simpleName,
                                                      String fqn) {
        for (J.VariableDeclarations.NamedVariable variable : declaration.getVariables()) {
            if (!variable.getSimpleName().equals(selectName)) {
                continue;
            }
            String declaredType = typeNameFromTypeExpression(declaration.getTypeExpression());
            return simpleName.equals(declaredType) || fqn.equals(declaredType);
        }
        return false;
    }

    private static String typeNameFromTypeExpression(TypeTree typeExpression) {
        if (typeExpression == null) {
            return null;
        }
        if (typeExpression instanceof J.Identifier) {
            return ((J.Identifier) typeExpression).getSimpleName();
        }
        if (typeExpression instanceof J.FieldAccess) {
            return ((J.FieldAccess) typeExpression).getName().getSimpleName();
        }
        if (typeExpression instanceof J.ParameterizedType) {
            return typeNameFromNameTree(((J.ParameterizedType) typeExpression).getClazz());
        }
        return null;
    }

    private static String typeNameFromNameTree(NameTree nameTree) {
        if (nameTree instanceof J.Identifier) {
            return ((J.Identifier) nameTree).getSimpleName();
        }
        if (nameTree instanceof J.FieldAccess) {
            return ((J.FieldAccess) nameTree).getName().getSimpleName();
        }
        return null;
    }

    private static String simpleNameFromFqn(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
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
        String baseName = "MatrixVariableConfiguration";
        String candidate = baseName;
        int counter = 2;
        String prefix = basePackage.isEmpty() ? "" : basePackage + ".";
        while (existingFqns.contains(prefix + candidate)) {
            candidate = baseName + counter;
            counter++;
        }
        return candidate;
    }

    private static String generateConfigurationSource(String basePackage, String className) {
        StringBuilder source = new StringBuilder();
        if (!basePackage.isEmpty()) {
            source.append("package ").append(basePackage).append(";\n\n");
        }
        source.append("import org.springframework.context.annotation.Configuration;\n")
            .append("import org.springframework.http.server.PathContainer;\n")
            .append("import org.springframework.web.util.pattern.PathPatternParser;\n")
            .append("import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;\n")
            .append("import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;\n\n")
            .append("@Configuration\n")
            .append("public class ").append(className).append(" implements WebMvcConfigurer {\n")
            .append("    @Override\n")
            .append("    public void configurePathMatch(PathMatchConfigurer configurer) {\n")
            .append("        PathPatternParser parser = new PathPatternParser();\n")
            .append("        parser.setPathOptions(PathContainer.Options.HTTP_PATH);\n")
            .append("        configurer.setPatternParser(parser);\n")
            .append("    }\n")
            .append("}\n");
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
}
