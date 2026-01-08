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
import java.util.*;

/**
 * Migrates @Remote EJB interfaces to REST controllers with delegation.
 * <p>
 * This recipe generates:
 * <ul>
 *   <li>{Interface}RestController with constructor-injection of the implementation</li>
 *   <li>Request DTOs for methods with multiple primitive parameters</li>
 *   <li>POST + @RequestBody endpoints for RPC-style API</li>
 * </ul>
 * <p>
 * Implementation binding uses prioritization:
 * <ol>
 *   <li>@Stateless/@Singleton annotated implementations</li>
 *   <li>@Service/@Component annotated implementations</li>
 *   <li>Unannotated implementations (last resort)</li>
 * </ol>
 *
 * <h3>Overloaded Method Handling</h3>
 * <p>
 * Overloaded methods receive path suffixes to ensure unique endpoints:
 * {@code /methodName/1}, {@code /methodName/2}, {@code /methodName/3}, etc.
 * The corresponding DTOs are named {@code MethodName1Request}, {@code MethodName2Request}, etc.
 * <p>
 * <strong>Important:</strong> The path suffix numbering (1, 2, 3...) is determined by the
 * <em>source order</em> of methods in the {@code @Remote} interface. If the order of overloaded
 * methods in the interface is changed, the generated paths will also change, which may break
 * existing clients. To maintain API stability, do not reorder overloaded method declarations
 * in the source interface.
 *
 * @see GenerateHttpExchangeClient
 * @see ProjectConfiguration.RemoteStrategy#REST
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateRemoteToRest extends ScanningRecipe<MigrateRemoteToRest.Accumulator> {

    @Option(displayName = "Target package suffix",
            description = "Suffix appended to the remote type package for generated controllers.",
            example = "rest",
            required = false)
    @Nullable
    String targetPackageSuffix;

    public MigrateRemoteToRest() {
        this.targetPackageSuffix = null;
    }

    public MigrateRemoteToRest(@Nullable String targetPackageSuffix) {
        this.targetPackageSuffix = targetPackageSuffix;
    }

    @Override
    public String getDisplayName() {
        return "Migrate @Remote to REST controller with delegation";
    }

    @Override
    public String getDescription() {
        return "Generates REST controllers for @Remote EJB interfaces with constructor-injection " +
               "and delegation to the actual implementation. Creates DTOs for multi-parameter methods.";
    }

    static class Accumulator {
        Map<String, RemoteInterfaceInfo> remoteInterfaces = new LinkedHashMap<>();
        Map<String, List<ImplementationInfo>> implementationsByInterface = new LinkedHashMap<>();
        Set<Path> existingSourcePaths = new HashSet<>();
    }

    static class RemoteInterfaceInfo {
        final String fqn;
        final String packageName;
        final String simpleName;
        final String mainSourceRoot;
        final List<MethodInfo> methods;

        RemoteInterfaceInfo(String fqn, String packageName, String simpleName,
                           String mainSourceRoot, List<MethodInfo> methods) {
            this.fqn = fqn;
            this.packageName = packageName;
            this.simpleName = simpleName;
            this.mainSourceRoot = mainSourceRoot;
            this.methods = methods;
        }
    }

    static class MethodInfo {
        final String name;
        final String returnType;
        final String returnTypeSimple;
        final List<ParamInfo> params;
        final boolean needsDto;
        final String dtoName; // Unique DTO name for overloaded methods
        final int overloadIndex; // Index for overloaded methods (0 = first/only occurrence)

        MethodInfo(String name, String returnType, String returnTypeSimple, List<ParamInfo> params,
                   String dtoName, int overloadIndex) {
            this.name = name;
            this.returnType = returnType;
            this.returnTypeSimple = returnTypeSimple;
            this.params = params;
            this.dtoName = dtoName;
            this.overloadIndex = overloadIndex;
            // Need DTO if multiple parameters or any primitive parameter
            this.needsDto = params.size() > 1 ||
                           (params.size() == 1 && isPrimitive(params.get(0).type));
        }

        private static boolean isPrimitive(String type) {
            return PRIMITIVE_TYPES.contains(type);
        }

        private static final Set<String> PRIMITIVE_TYPES = Set.of(
            "int", "long", "short", "byte", "float", "double", "boolean", "char",
            "Integer", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Character",
            "java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte",
            "java.lang.Float", "java.lang.Double", "java.lang.Boolean", "java.lang.Character",
            "String", "java.lang.String"
        );
    }

    static class ParamInfo {
        final String type;        // FQN (e.g., "com.example.Customer" or "com.example.Customer[]" for varargs)
        final String typeSimple;  // Simple name (e.g., "Customer" or "Customer[]" for varargs)
        final String name;
        final boolean isVarargs;  // True if parameter was declared with varargs syntax

        ParamInfo(String type, String typeSimple, String name, boolean isVarargs) {
            this.type = type;
            this.typeSimple = typeSimple;
            this.name = name;
            this.isVarargs = isVarargs;
        }

        /**
         * Returns the FQN if it requires an import (not java.lang, not primitive).
         */
        String getImportableFqn() {
            String t = type;
            if (t == null) {
                return null;
            }
            // Strip array suffix before processing
            if (t.endsWith("[]")) {
                t = t.substring(0, t.length() - 2);
            }
            if (t.startsWith("java.lang.") || !t.contains(".") || isPrimitive(t)) {
                return null;
            }
            // Handle generic types - extract raw type
            if (t.contains("<")) {
                return t.substring(0, t.indexOf('<'));
            }
            return t;
        }

        private boolean isPrimitive(String t) {
            return MethodInfo.PRIMITIVE_TYPES.contains(t);
        }
    }

    static class ImplementationInfo {
        final String fqn;
        final String simpleName;
        final int priority; // Lower = higher priority

        ImplementationInfo(String fqn, String simpleName, int priority) {
            this.fqn = fqn;
            this.simpleName = simpleName;
            this.priority = priority;
        }
    }

    private static final AnnotationMatcher REMOTE_JAVAX = new AnnotationMatcher("@javax.ejb.Remote");
    private static final AnnotationMatcher REMOTE_JAKARTA = new AnnotationMatcher("@jakarta.ejb.Remote");
    private static final AnnotationMatcher STATELESS_JAVAX = new AnnotationMatcher("@javax.ejb.Stateless");
    private static final AnnotationMatcher STATELESS_JAKARTA = new AnnotationMatcher("@jakarta.ejb.Stateless");
    private static final AnnotationMatcher SINGLETON_JAVAX = new AnnotationMatcher("@javax.ejb.Singleton");
    private static final AnnotationMatcher SINGLETON_JAKARTA = new AnnotationMatcher("@jakarta.ejb.Singleton");
    private static final AnnotationMatcher SERVICE = new AnnotationMatcher("@org.springframework.stereotype.Service");
    private static final AnnotationMatcher COMPONENT = new AnnotationMatcher("@org.springframework.stereotype.Component");

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof PlainText) {
                    PlainText text = (PlainText) tree;
                    acc.existingSourcePaths.add(text.getSourcePath());
                    return text;
                }
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    scanCompilationUnit(cu, acc);
                    return cu;
                }
                return tree;
            }
        };
    }

    private void scanCompilationUnit(J.CompilationUnit cu, Accumulator acc) {
        if (cu.getSourcePath() != null) {
            acc.existingSourcePaths.add(cu.getSourcePath());
        }

        if (cu.getPackageDeclaration() == null || cu.getSourcePath() == null) {
            return;
        }

        String sourcePath = cu.getSourcePath().toString().replace('\\', '/');
        ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(cu.getSourcePath()));
        if (!config.isMainSource(sourcePath)) {
            return;
        }
        String mainSourceRoot = extractMainSourceRoot(sourcePath, config);

        for (J.ClassDeclaration classDecl : cu.getClasses()) {
            if (classDecl.getType() == null) {
                continue;
            }

            // Scan for @Remote interfaces
            if (hasRemoteAnnotation(classDecl) && isInterface(classDecl)) {
                String fqn = classDecl.getType().getFullyQualifiedName();
                if (!acc.remoteInterfaces.containsKey(fqn)) {
                    String packageName = cu.getPackageDeclaration().getPackageName();
                    List<MethodInfo> methods = extractMethods(classDecl);
                    acc.remoteInterfaces.put(fqn, new RemoteInterfaceInfo(
                            fqn, packageName, classDecl.getSimpleName(), mainSourceRoot, methods));
                }
            }

            // Scan for implementations
            scanForImplementations(classDecl, acc);
        }
    }

    private void scanForImplementations(J.ClassDeclaration classDecl, Accumulator acc) {
        if (isInterface(classDecl) || classDecl.getType() == null) {
            return;
        }

        JavaType.FullyQualified type = classDecl.getType();
        List<JavaType.FullyQualified> interfaces = type.getInterfaces();
        if (interfaces == null || interfaces.isEmpty()) {
            return;
        }

        int priority = determinePriority(classDecl);
        String implFqn = type.getFullyQualifiedName();
        String implSimpleName = classDecl.getSimpleName();

        for (JavaType.FullyQualified iface : interfaces) {
            String ifaceFqn = iface.getFullyQualifiedName();
            acc.implementationsByInterface
                    .computeIfAbsent(ifaceFqn, k -> new ArrayList<>())
                    .add(new ImplementationInfo(implFqn, implSimpleName, priority));
        }
    }

    private int determinePriority(J.ClassDeclaration classDecl) {
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            if (STATELESS_JAVAX.matches(ann) || STATELESS_JAKARTA.matches(ann) ||
                SINGLETON_JAVAX.matches(ann) || SINGLETON_JAKARTA.matches(ann)) {
                return 1; // Highest priority: EJB annotations
            }
            if (SERVICE.matches(ann) || COMPONENT.matches(ann)) {
                return 2; // Medium priority: Spring annotations
            }
        }
        return 3; // Lowest priority: unannotated
    }

    private boolean hasRemoteAnnotation(J.ClassDeclaration classDecl) {
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            if (REMOTE_JAVAX.matches(ann) || REMOTE_JAKARTA.matches(ann)) {
                return true;
            }
        }
        return false;
    }

    private boolean isInterface(J.ClassDeclaration classDecl) {
        return classDecl.getKind() == J.ClassDeclaration.Kind.Type.Interface;
    }

    private List<MethodInfo> extractMethods(J.ClassDeclaration classDecl) {
        List<MethodInfo> methods = new ArrayList<>();
        if (classDecl.getBody() == null) {
            return methods;
        }

        // First pass: collect all method data
        List<MethodData> methodDataList = new ArrayList<>();
        for (Statement stmt : classDecl.getBody().getStatements()) {
            if (!(stmt instanceof J.MethodDeclaration)) {
                continue;
            }
            J.MethodDeclaration method = (J.MethodDeclaration) stmt;
            if (method.isConstructor() || isStatic(method)) {
                continue;
            }

            String returnType = resolveTypeName(method.getReturnTypeExpression());
            String returnTypeSimple = resolveSimpleTypeName(method.getReturnTypeExpression());
            List<ParamInfo> params = new ArrayList<>();

            for (Statement param : method.getParameters()) {
                if (!(param instanceof J.VariableDeclarations)) {
                    continue;
                }
                J.VariableDeclarations varDecls = (J.VariableDeclarations) param;
                String typeName = resolveTypeName(varDecls.getTypeExpression());
                String typeSimple = resolveSimpleTypeName(varDecls.getTypeExpression());
                boolean isVarargs = varDecls.getVarargs() != null;
                if (isVarargs) {
                    // Store as array syntax for DTOs (valid field type), not varargs syntax
                    typeName = typeName + "[]";
                    typeSimple = typeSimple + "[]";
                }
                for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                    params.add(new ParamInfo(typeName, typeSimple, var.getSimpleName(), isVarargs));
                }
            }

            methodDataList.add(new MethodData(method.getSimpleName(), returnType, returnTypeSimple, params));
        }

        // Second pass: count overloads and assign unique DTO names
        Map<String, Integer> methodCounts = new LinkedHashMap<>();
        for (MethodData md : methodDataList) {
            methodCounts.merge(md.name, 1, Integer::sum);
        }

        Map<String, Integer> methodIndexes = new LinkedHashMap<>();
        for (MethodData md : methodDataList) {
            int count = methodCounts.get(md.name);
            int index = methodIndexes.merge(md.name, 0, (old, v) -> old + 1);

            String dtoName;
            if (count == 1) {
                // No overloading: simple name
                dtoName = capitalize(md.name) + "Request";
            } else {
                // Overloaded: append index (1-based for human readability)
                dtoName = capitalize(md.name) + (index + 1) + "Request";
            }

            methods.add(new MethodInfo(md.name, md.returnType, md.returnTypeSimple, md.params, dtoName, index));
        }

        return methods;
    }

    /** Temporary holder for method extraction */
    private static class MethodData {
        final String name;
        final String returnType;
        final String returnTypeSimple;
        final List<ParamInfo> params;

        MethodData(String name, String returnType, String returnTypeSimple, List<ParamInfo> params) {
            this.name = name;
            this.returnType = returnType;
            this.returnTypeSimple = returnTypeSimple;
            this.params = params;
        }
    }

    private boolean isStatic(J.MethodDeclaration method) {
        for (J.Modifier modifier : method.getModifiers()) {
            if (modifier.getType() == J.Modifier.Type.Static) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (acc.remoteInterfaces.isEmpty()) {
            return List.of();
        }

        String packageSuffix = targetPackageSuffix == null ? "rest" : targetPackageSuffix.trim();
        List<SourceFile> generated = new ArrayList<>();

        for (RemoteInterfaceInfo info : acc.remoteInterfaces.values()) {
            List<ImplementationInfo> impls = acc.implementationsByInterface.get(info.fqn);
            ImplementationInfo bestImpl = findBestImplementation(info.fqn, acc);

            String targetPackage = resolveTargetPackage(info.packageName, packageSuffix);
            String controllerName = info.simpleName + "RestController";
            Path controllerPath = resolveTargetPath(info.mainSourceRoot, targetPackage, controllerName);

            if (acc.existingSourcePaths.contains(controllerPath)) {
                continue;
            }

            // Handle missing or ambiguous implementation with marker stub
            if (bestImpl == null || hasMultipleSamePriority(impls)) {
                String stubSource = generateControllerStub(info, targetPackage, controllerName, impls);
                generated.add(PlainText.builder()
                        .sourcePath(controllerPath)
                        .text(stubSource)
                        .build());
                continue;
            }

            // Collect types requiring imports
            Set<String> requiredImports = collectRequiredImports(info, targetPackage);

            // Generate DTOs for methods that need them
            for (MethodInfo method : info.methods) {
                if (method.needsDto) {
                    Path dtoPath = resolveTargetPath(info.mainSourceRoot, targetPackage, method.dtoName);
                    if (!acc.existingSourcePaths.contains(dtoPath)) {
                        String dtoSource = generateDto(targetPackage, method.dtoName, method.params);
                        generated.add(PlainText.builder()
                                .sourcePath(dtoPath)
                                .text(dtoSource)
                                .build());
                        acc.existingSourcePaths.add(dtoPath);
                    }
                }
            }

            // Generate controller
            String controllerSource = generateController(info, targetPackage, controllerName, bestImpl, requiredImports);
            generated.add(PlainText.builder()
                    .sourcePath(controllerPath)
                    .text(controllerSource)
                    .build());
        }

        return generated;
    }

    /**
     * Checks if there are multiple implementations with the same (best) priority.
     */
    private boolean hasMultipleSamePriority(List<ImplementationInfo> impls) {
        if (impls == null || impls.size() <= 1) {
            return false;
        }
        int bestPriority = impls.stream().mapToInt(i -> i.priority).min().orElse(Integer.MAX_VALUE);
        return impls.stream().filter(i -> i.priority == bestPriority).count() > 1;
    }

    /**
     * Collects FQNs that need to be imported for the generated controller.
     */
    private Set<String> collectRequiredImports(RemoteInterfaceInfo info, String targetPackage) {
        Set<String> imports = new LinkedHashSet<>();
        for (MethodInfo method : info.methods) {
            // Return type
            String returnImport = extractImportableFqn(method.returnType);
            if (returnImport != null && !isInPackage(returnImport, targetPackage)) {
                imports.add(returnImport);
            }
            // Generic type arguments in return type
            imports.addAll(extractGenericTypeImports(method.returnType, targetPackage));

            // Parameters (for non-DTO methods)
            if (!method.needsDto) {
                for (ParamInfo param : method.params) {
                    String paramImport = param.getImportableFqn();
                    if (paramImport != null && !isInPackage(paramImport, targetPackage)) {
                        imports.add(paramImport);
                    }
                    imports.addAll(extractGenericTypeImports(param.type, targetPackage));
                }
            }
        }
        return imports;
    }

    /**
     * Extracts importable FQN from a type, handling generics.
     */
    private String extractImportableFqn(String type) {
        if (type == null || "void".equals(type) || !type.contains(".")) {
            return null;
        }
        if (type.startsWith("java.lang.") && !type.substring("java.lang.".length()).contains(".")) {
            return null;
        }
        // Extract raw type for generics
        String rawType = type.contains("<") ? type.substring(0, type.indexOf('<')) : type;
        if (!rawType.contains(".")) {
            return null;
        }
        return rawType;
    }

    /**
     * Extracts generic type argument imports from a type like List&lt;com.example.Customer&gt;.
     */
    private Set<String> extractGenericTypeImports(String type, String targetPackage) {
        Set<String> imports = new LinkedHashSet<>();
        if (type == null || !type.contains("<")) {
            return imports;
        }
        int start = type.indexOf('<');
        int end = type.lastIndexOf('>');
        if (start >= 0 && end > start) {
            String inner = type.substring(start + 1, end);
            // Handle nested generics and multiple type args
            for (String arg : splitTypeArguments(inner)) {
                String trimmed = arg.trim();
                String fqn = extractImportableFqn(trimmed);
                if (fqn != null && !isInPackage(fqn, targetPackage)) {
                    imports.add(fqn);
                }
                // Recurse for nested generics
                imports.addAll(extractGenericTypeImports(trimmed, targetPackage));
            }
        }
        return imports;
    }

    /**
     * Splits type arguments, respecting nested generics.
     */
    private List<String> splitTypeArguments(String inner) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) {
                args.add(inner.substring(start, i));
                start = i + 1;
            }
        }
        if (start < inner.length()) {
            args.add(inner.substring(start));
        }
        return args;
    }

    private boolean isInPackage(String fqn, String targetPackage) {
        if (fqn == null || targetPackage == null || targetPackage.isEmpty()) {
            return false;
        }
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot < 0) {
            return targetPackage.isEmpty();
        }
        return fqn.substring(0, lastDot).equals(targetPackage);
    }

    /**
     * Generates a stub controller with @NeedsReview for missing/ambiguous implementation.
     */
    private String generateControllerStub(RemoteInterfaceInfo info, String targetPackage,
                                          String controllerName, List<ImplementationInfo> impls) {
        StringBuilder sb = new StringBuilder();

        if (!targetPackage.isEmpty()) {
            sb.append("package ").append(targetPackage).append(";\n\n");
        }

        // Imports
        sb.append("import ").append(info.fqn).append(";\n");
        sb.append("import com.github.rewrite.ejb.annotations.NeedsReview;\n");
        sb.append("import org.springframework.web.bind.annotation.RequestMapping;\n");
        sb.append("import org.springframework.web.bind.annotation.RestController;\n\n");

        // Determine the reason
        String reason;
        String suggestedAction;
        if (impls == null || impls.isEmpty()) {
            reason = "No implementation found for @Remote interface " + info.simpleName;
            suggestedAction = "Implement " + info.fqn + " with @Service or @Stateless annotation";
        } else {
            reason = "Multiple implementations found for @Remote interface " + info.simpleName +
                    " with same priority: " + impls.stream()
                    .map(i -> i.simpleName)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
            suggestedAction = "Configure explicit binding in project.yaml or reduce to single implementation";
        }

        // Class declaration with @NeedsReview
        sb.append("@NeedsReview(reason = \"").append(escapeString(reason)).append("\",\n");
        sb.append("    category = NeedsReview.Category.REMOTE_ACCESS,\n");
        sb.append("    originalCode = \"@Remote\",\n");
        sb.append("    suggestedAction = \"").append(escapeString(suggestedAction)).append("\")\n");
        sb.append("@RestController\n");
        sb.append("@RequestMapping(\"/api/").append(info.simpleName).append("\")\n");
        sb.append("public class ").append(controllerName).append(" {\n\n");

        // TODO placeholder
        sb.append("    // TODO: Inject implementation and delegate methods\n");
        sb.append("    // private final ").append(info.simpleName).append(" delegate;\n\n");

        sb.append("}\n");
        return sb.toString();
    }

    private String escapeString(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private ImplementationInfo findBestImplementation(String interfaceFqn, Accumulator acc) {
        List<ImplementationInfo> impls = acc.implementationsByInterface.get(interfaceFqn);
        if (impls == null || impls.isEmpty()) {
            return null;
        }
        return impls.stream()
                .min(Comparator.comparingInt(i -> i.priority))
                .orElse(null);
    }

    private String generateController(RemoteInterfaceInfo info, String targetPackage,
                                      String controllerName, ImplementationInfo impl,
                                      Set<String> requiredImports) {
        StringBuilder sb = new StringBuilder();

        if (!targetPackage.isEmpty()) {
            sb.append("package ").append(targetPackage).append(";\n\n");
        }

        // Imports - interface
        sb.append("import ").append(info.fqn).append(";\n");

        // Custom type imports (sorted for deterministic output)
        List<String> sortedImports = new ArrayList<>(requiredImports);
        Collections.sort(sortedImports);
        for (String imp : sortedImports) {
            sb.append("import ").append(imp).append(";\n");
        }

        // Check if any method needs @RequestBody
        boolean needsRequestBodyImport = info.methods.stream()
                .anyMatch(m -> m.needsDto || m.params.size() == 1);

        // Spring imports
        sb.append("import org.springframework.web.bind.annotation.PostMapping;\n");
        if (needsRequestBodyImport) {
            sb.append("import org.springframework.web.bind.annotation.RequestBody;\n");
        }
        sb.append("import org.springframework.web.bind.annotation.RequestMapping;\n");
        sb.append("import org.springframework.web.bind.annotation.RestController;\n\n");

        // Class declaration
        sb.append("@RestController\n");
        sb.append("@RequestMapping(\"/api/").append(info.simpleName).append("\")\n");
        sb.append("public class ").append(controllerName).append(" {\n\n");

        // Field
        String fieldName = decapitalize(info.simpleName);
        sb.append("    private final ").append(info.simpleName).append(" ").append(fieldName).append(";\n\n");

        // Constructor
        sb.append("    public ").append(controllerName).append("(").append(info.simpleName)
          .append(" ").append(fieldName).append(") {\n");
        sb.append("        this.").append(fieldName).append(" = ").append(fieldName).append(";\n");
        sb.append("    }\n");

        // Build overload count map to detect which methods need path disambiguation
        Map<String, Long> overloadCounts = info.methods.stream()
                .collect(java.util.stream.Collectors.groupingBy(m -> m.name, java.util.stream.Collectors.counting()));

        // Methods
        for (MethodInfo method : info.methods) {
            sb.append("\n");
            // Add path suffix for overloaded methods (e.g., /search/1, /search/2)
            String pathSuffix = overloadCounts.get(method.name) > 1 ? "/" + (method.overloadIndex + 1) : "";
            sb.append("    @PostMapping(\"/").append(method.name).append(pathSuffix).append("\")\n");

            if (method.needsDto) {
                sb.append("    public ").append(method.returnTypeSimple).append(" ")
                  .append(method.name).append("(@RequestBody ").append(method.dtoName).append(" request) {\n");

                // Delegate call with DTO field extraction
                if (!"void".equals(method.returnType)) {
                    sb.append("        return ");
                } else {
                    sb.append("        ");
                }
                sb.append(fieldName).append(".").append(method.name).append("(");
                for (int i = 0; i < method.params.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append("request.get").append(capitalize(method.params.get(i).name)).append("()");
                }
                sb.append(");\n");
            } else if (method.params.size() == 1) {
                // Single non-primitive parameter - use @RequestBody directly
                ParamInfo param = method.params.get(0);
                sb.append("    public ").append(method.returnTypeSimple).append(" ")
                  .append(method.name).append("(@RequestBody ").append(param.typeSimple)
                  .append(" ").append(param.name).append(") {\n");

                if (!"void".equals(method.returnType)) {
                    sb.append("        return ");
                } else {
                    sb.append("        ");
                }
                sb.append(fieldName).append(".").append(method.name).append("(").append(param.name).append(");\n");
            } else {
                // No parameters
                sb.append("    public ").append(method.returnTypeSimple).append(" ")
                  .append(method.name).append("() {\n");

                if (!"void".equals(method.returnType)) {
                    sb.append("        return ");
                } else {
                    sb.append("        ");
                }
                sb.append(fieldName).append(".").append(method.name).append("();\n");
            }
            sb.append("    }\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private String generateDto(String targetPackage, String dtoName, List<ParamInfo> params) {
        StringBuilder sb = new StringBuilder();

        if (!targetPackage.isEmpty()) {
            sb.append("package ").append(targetPackage).append(";\n\n");
        }

        // Collect and emit imports for non-java.lang field types
        Set<String> dtoImports = new LinkedHashSet<>();
        for (ParamInfo param : params) {
            String fqn = param.getImportableFqn();
            if (fqn != null && !isInPackage(fqn, targetPackage)) {
                dtoImports.add(fqn);
            }
            dtoImports.addAll(extractGenericTypeImports(param.type, targetPackage));
        }
        if (!dtoImports.isEmpty()) {
            List<String> sortedImports = new ArrayList<>(dtoImports);
            Collections.sort(sortedImports);
            for (String imp : sortedImports) {
                sb.append("import ").append(imp).append(";\n");
            }
            sb.append("\n");
        }

        sb.append("public class ").append(dtoName).append(" {\n\n");

        // Fields
        for (ParamInfo param : params) {
            sb.append("    private ").append(param.typeSimple).append(" ").append(param.name).append(";\n");
        }

        // Default constructor
        sb.append("\n    public ").append(dtoName).append("() {\n    }\n");

        // All-args constructor
        sb.append("\n    public ").append(dtoName).append("(");
        for (int i = 0; i < params.size(); i++) {
            if (i > 0) sb.append(", ");
            ParamInfo param = params.get(i);
            sb.append(param.typeSimple).append(" ").append(param.name);
        }
        sb.append(") {\n");
        for (ParamInfo param : params) {
            sb.append("        this.").append(param.name).append(" = ").append(param.name).append(";\n");
        }
        sb.append("    }\n");

        // Getters and Setters
        for (ParamInfo param : params) {
            String capitalized = capitalize(param.name);
            // Getter
            sb.append("\n    public ").append(param.typeSimple).append(" get")
              .append(capitalized).append("() {\n");
            sb.append("        return ").append(param.name).append(";\n");
            sb.append("    }\n");
            // Setter
            sb.append("\n    public void set").append(capitalized).append("(")
              .append(param.typeSimple).append(" ").append(param.name).append(") {\n");
            sb.append("        this.").append(param.name).append(" = ").append(param.name).append(";\n");
            sb.append("    }\n");
        }

        sb.append("}\n");
        return sb.toString();
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

    private Path resolveTargetPath(String mainSourceRoot, String targetPackage, String className) {
        String packagePath = targetPackage.isEmpty() ? "" : targetPackage.replace('.', '/');
        return packagePath.isEmpty()
                ? Paths.get(mainSourceRoot, className + ".java")
                : Paths.get(mainSourceRoot, packagePath, className + ".java");
    }

    private String resolveTypeName(@Nullable TypeTree typeExpr) {
        if (typeExpr == null) {
            return "void";
        }
        if (typeExpr instanceof TypedTree) {
            TypedTree typed = (TypedTree) typeExpr;
            JavaType type = typed.getType();
            if (type != null) {
                return TypeUtils.toString(type).replace('$', '.');
            }
        }
        return typeExpr.printTrimmed();
    }

    private String resolveSimpleTypeName(@Nullable TypeTree typeExpr) {
        String fullName = resolveTypeName(typeExpr);
        // Convert java.lang.String to String, etc.
        if (fullName.startsWith("java.lang.")) {
            return fullName.substring("java.lang.".length());
        }
        // For generic types, keep simple
        int lastDot = fullName.lastIndexOf('.');
        if (lastDot > 0 && !fullName.contains("<")) {
            return fullName.substring(lastDot + 1);
        }
        return fullName;
    }

    private static String capitalize(String str) {
        if (str == null || str.isEmpty()) return str;
        return Character.toUpperCase(str.charAt(0)) + str.substring(1);
    }

    private static String decapitalize(String str) {
        if (str == null || str.isEmpty()) return str;
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
