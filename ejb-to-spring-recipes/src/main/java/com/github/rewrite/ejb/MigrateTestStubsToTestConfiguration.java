package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Migrates test stubs from @Component-based auto-scanning to @TestConfiguration with explicit @Bean methods.
 * <p>
 * This resolves bean ambiguity issues during spring-boot:test-run where both the production bean
 * and the test stub are loaded, causing NoUniqueBeanDefinitionException.
 * <p>
 * Transformation:
 * <pre>
 * BEFORE:
 * &#64;Component
 * public class ApplicationEventsStub implements ApplicationEvents { }
 *
 * &#64;SpringBootTest
 * public class PaymentServiceTest { }
 *
 * AFTER:
 * // ApplicationEventsStub.java - @Component removed
 * public class ApplicationEventsStub implements ApplicationEvents { }
 *
 * // PaymentServiceTestConfiguration.java - NEW generated file (name derived from test)
 * &#64;TestConfiguration
 * public class PaymentServiceTestConfiguration {
 *     &#64;Bean
 *     &#64;Primary
 *     public ApplicationEventsStub applicationEventsStub() {
 *         return new ApplicationEventsStub();
 *     }
 * }
 *
 * // PaymentServiceTest.java - @Import added
 * &#64;Import(PaymentServiceTestConfiguration.class)
 * &#64;SpringBootTest
 * public class PaymentServiceTest { }
 * </pre>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateTestStubsToTestConfiguration extends ScanningRecipe<MigrateTestStubsToTestConfiguration.Accumulator> {

    private static final String COMPONENT_FQN = "org.springframework.stereotype.Component";
    private static final String SERVICE_FQN = "org.springframework.stereotype.Service";
    private static final String REPOSITORY_FQN = "org.springframework.stereotype.Repository";
    private static final String TEST_CONFIGURATION_FQN = "org.springframework.boot.test.context.TestConfiguration";
    private static final String BEAN_FQN = "org.springframework.context.annotation.Bean";

    private static final Set<String> SPRING_STEREOTYPES = Set.of(COMPONENT_FQN, SERVICE_FQN, REPOSITORY_FQN);
    private static final Set<String> SPRING_STEREOTYPE_SIMPLE_NAMES = Set.of("Component", "Service", "Repository");
    private static final List<String> STUB_NAME_PATTERNS = List.of("Stub", "Mock", "Fake");

    // Stubs for Spring Boot Test annotations (no classpath dependency required)
    private static final String TEST_CONFIGURATION_STUB =
        "package org.springframework.boot.test.context;\n" +
        "import java.lang.annotation.*;\n" +
        "@Target(ElementType.TYPE)\n" +
        "@Retention(RetentionPolicy.RUNTIME)\n" +
        "public @interface TestConfiguration {}\n";

    private static final String BEAN_STUB =
        "package org.springframework.context.annotation;\n" +
        "import java.lang.annotation.*;\n" +
        "@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})\n" +
        "@Retention(RetentionPolicy.RUNTIME)\n" +
        "public @interface Bean {}\n";

    private static final String PRIMARY_STUB =
        "package org.springframework.context.annotation;\n" +
        "import java.lang.annotation.*;\n" +
        "@Target({ElementType.TYPE, ElementType.METHOD})\n" +
        "@Retention(RetentionPolicy.RUNTIME)\n" +
        "public @interface Primary {}\n";

    private static final String IMPORT_STUB =
        "package org.springframework.context.annotation;\n" +
        "import java.lang.annotation.*;\n" +
        "@Target(ElementType.TYPE)\n" +
        "@Retention(RetentionPolicy.RUNTIME)\n" +
        "public @interface Import { Class<?>[] value(); }\n";

    private static final String SPRING_BOOT_TEST_FQN = "org.springframework.boot.test.context.SpringBootTest";
    private static final String IMPORT_FQN = "org.springframework.context.annotation.Import";

    // HIGH fix Round 6: Custom annotation to mark tests that need manual stub review
    private static final String NEEDS_REVIEW_FQN = "com.github.migration.NeedsStubReview";
    private static final String NEEDS_REVIEW_STUB =
        "package com.github.migration;\n" +
        "import java.lang.annotation.*;\n" +
        "@Target(ElementType.TYPE)\n" +
        "@Retention(RetentionPolicy.SOURCE)\n" +
        "@Documented\n" +
        "/** Marker: This test has stubs in its package but doesn't directly reference them. " +
        "Review if stubs should be included in a @TestConfiguration. */\n" +
        "public @interface NeedsStubReview { String value() default \"\"; }\n";

    @Override
    public String getDisplayName() {
        return "Migrate test stubs to @TestConfiguration";
    }

    @Override
    public String getDescription() {
        return "Removes @Component/@Service/@Repository from test stubs and generates a @TestConfiguration " +
               "class with @Bean methods for each @SpringBootTest. This resolves bean ambiguity issues during spring-boot:test-run.";
    }

    /**
     * Information collected about a test stub class.
     */
    static class StubInfo {
        final String className;
        final String packageName;
        final String fqn;
        final Path sourceRoot;
        final boolean hasDefaultConstructor;
        final List<ConstructorParam> constructorParams;
        final Set<String> requiredImports; // FQNs needed for constructor params

        StubInfo(String className, String packageName, Path sourceRoot,
                 boolean hasDefaultConstructor, List<ConstructorParam> constructorParams,
                 Set<String> requiredImports) {
            this.className = className;
            this.packageName = packageName;
            this.fqn = packageName.isEmpty() ? className : packageName + "." + className;
            this.sourceRoot = sourceRoot;
            this.hasDefaultConstructor = hasDefaultConstructor;
            this.constructorParams = constructorParams;
            this.requiredImports = requiredImports;
        }
    }

    static class ConstructorParam {
        final String type;       // Simple type name for method signature
        final String typeFqn;    // Fully qualified name for imports
        final String name;

        ConstructorParam(String type, String typeFqn, String name) {
            this.type = type;
            this.typeFqn = typeFqn;
            this.name = name;
        }
    }

    /**
     * Information about a @SpringBootTest class that needs a config generated.
     */
    static class TestClassInfo {
        final String className;      // e.g., "PaymentServiceTest"
        final String packageName;
        final String fqn;
        final Path sourceRoot;
        final boolean hasExistingImport;
        String generatedConfigName;  // e.g., "PaymentServiceTestConfiguration" - set during generate()

        TestClassInfo(String className, String packageName, Path sourceRoot, boolean hasExistingImport) {
            this.className = className;
            this.packageName = packageName;
            this.fqn = packageName.isEmpty() ? className : packageName + "." + className;
            this.sourceRoot = sourceRoot;
            this.hasExistingImport = hasExistingImport;
        }
    }

    static class Accumulator {
        // Stubs grouped by sourceRoot then package
        final Map<Path, Map<String, List<StubInfo>>> stubsByRootAndPackage = new LinkedHashMap<>();
        // LOW fix Round 5: Removed unused processedStubsByRoot field
        // Test classes keyed by sourceRoot then FQN
        final Map<Path, Map<String, TestClassInfo>> testClassesByRoot = new LinkedHashMap<>();
        // Stubs that are actually covered by a generated config - only these should have @Component removed
        // Populated during generate(), checked during getVisitor()
        final Map<Path, Set<String>> stubsCoveredByConfig = new LinkedHashMap<>();
        // Existing config classes to avoid overwriting
        final Map<Path, Set<String>> existingConfigsByRoot = new LinkedHashMap<>();
        // HIGH fix #2: Track which stubs are actually used by each test
        // LOW fix Round 2: Key by sourceRoot then testFqn for multi-module support
        // HIGH fix Round 5: Now stores StubRef objects with FQN when available
        final Map<Path, Map<String, Set<StubRef>>> stubUsageByRootAndTest = new LinkedHashMap<>();
        // HIGH fix Round 6 + Round 7: Tests that have stubs in package but no direct references need @NeedsReview
        // MEDIUM fix Round 7: Scoped by source root for multi-module support
        final Map<Path, Set<String>> testsNeedingReviewByRoot = new LinkedHashMap<>();
    }

    /**
     * HIGH fix Round 5: Structured stub reference with FQN (when available) and simple name.
     * Allows matching by FQN first (accurate) then by simple name (fallback).
     */
    static class StubRef {
        final String fqn;        // e.g., "com.example.MyStub" (null if attribution missing)
        final String simpleName; // e.g., "MyStub"

        StubRef(String fqn, String simpleName) {
            this.fqn = fqn;
            this.simpleName = simpleName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            StubRef stubRef = (StubRef) o;
            // HIGH fix Round 6: StubRefs are equal only if FQN state matches
            // This prevents a null-FQN ref from blocking a FQN ref in Sets
            if (fqn != null && stubRef.fqn != null) {
                return fqn.equals(stubRef.fqn);
            }
            if (fqn == null && stubRef.fqn == null) {
                return simpleName.equals(stubRef.simpleName);
            }
            // One has FQN, other doesn't - not equal (allows both in Set)
            return false;
        }

        @Override
        public int hashCode() {
            // HIGH fix Round 6: Include FQN presence in hash to distinguish refs
            // Refs with FQN hash differently from those without
            if (fqn != null) {
                return fqn.hashCode();
            }
            return simpleName.hashCode() ^ 31; // XOR to distinguish from FQN hash
        }
        // LOW fix Round 6: Removed unused matchesStub() method
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Only process top-level classes
                if (getCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) {
                    return cd;
                }

                Cursor cursor = getCursor();
                J.CompilationUnit cu = cursor.firstEnclosingOrThrow(J.CompilationUnit.class);
                String sourcePath = cu.getSourcePath() != null ? cu.getSourcePath().toString().replace('\\', '/') : "";

                // Only process test sources (respects project.yaml if present)
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                        extractProjectRoot(cu.getSourcePath()));
                if (!config.isTestSource(sourcePath)) {
                    return cd;
                }

                String packageName = cu.getPackageDeclaration() != null
                        ? cu.getPackageDeclaration().getExpression().toString()
                        : "";
                String className = cd.getSimpleName();
                String fqn = packageName.isEmpty() ? className : packageName + "." + className;
                Path sourceRoot = extractTestSourceRoot(cu.getSourcePath(), config);

                // Check if this is a @SpringBootTest class
                if (hasSpringBootTestAnnotation(cd)) {
                    boolean hasExistingImport = hasImportAnnotation(cd);
                    acc.testClassesByRoot
                            .computeIfAbsent(sourceRoot, k -> new LinkedHashMap<>())
                            .put(fqn, new TestClassInfo(className, packageName, sourceRoot, hasExistingImport));

                    // HIGH fix #2: Collect stubs actually used by this test class
                    // LOW fix Round 2: Scope by sourceRoot for multi-module support
                    // HIGH fix Round 5: Now returns StubRef with FQN when available
                    Set<StubRef> usedStubs = collectUsedStubTypes(cd);
                    if (!usedStubs.isEmpty()) {
                        acc.stubUsageByRootAndTest
                                .computeIfAbsent(sourceRoot, k -> new LinkedHashMap<>())
                                .put(fqn, usedStubs);
                    }
                }

                // Track existing Configuration classes to avoid overwriting (HIGH fix #3)
                // HIGH fix Round 3: Store FQN instead of just className to avoid false matches across packages
                if (className.endsWith("Configuration") && hasConfigurationAnnotation(cd)) {
                    acc.existingConfigsByRoot
                            .computeIfAbsent(sourceRoot, k -> new HashSet<>())
                            .add(fqn);
                }

                // Check if class name matches stub pattern
                boolean isStubByName = STUB_NAME_PATTERNS.stream().anyMatch(className::endsWith);
                if (!isStubByName) {
                    return cd;
                }

                // Check if has Spring stereotype annotation
                if (!hasSpringStereotypeAnnotation(cd)) {
                    return cd;
                }

                // Collect stub info
                boolean hasDefaultConstructor = hasDefaultConstructor(cd);
                ConstructorExtractResult extractResult = extractConstructorParams(cd);

                StubInfo stubInfo = new StubInfo(className, packageName, sourceRoot,
                        hasDefaultConstructor, extractResult.params, extractResult.requiredImports);

                acc.stubsByRootAndPackage
                        .computeIfAbsent(sourceRoot, k -> new LinkedHashMap<>())
                        .computeIfAbsent(packageName, k -> new ArrayList<>())
                        .add(stubInfo);
                // LOW fix Round 5: Removed processedStubsByRoot - was unused

                return cd;
            }

            private boolean hasSpringBootTestAnnotation(J.ClassDeclaration classDecl) {
                for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                    if ("SpringBootTest".equals(ann.getSimpleName())) {
                        return true;
                    }
                    if (ann.getType() != null) {
                        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ann.getType());
                        if (fq != null && SPRING_BOOT_TEST_FQN.equals(fq.getFullyQualifiedName())) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean hasImportAnnotation(J.ClassDeclaration classDecl) {
                for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                    if ("Import".equals(ann.getSimpleName())) {
                        return true;
                    }
                    if (ann.getType() != null) {
                        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ann.getType());
                        if (fq != null && IMPORT_FQN.equals(fq.getFullyQualifiedName())) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean hasConfigurationAnnotation(J.ClassDeclaration classDecl) {
                for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                    String name = ann.getSimpleName();
                    if ("Configuration".equals(name) || "TestConfiguration".equals(name)) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * HIGH fix #2: Collect stub types used in fields, constructor params, and method params.
             * This allows filtering which stubs to include in a test's config.
             * Round 2 fix: Also check constructor parameters for stub usage.
             * LOW fix Round 3: Also try extracting from type expression when type attribution fails.
             * LOW fix Round 4: Also extract stub names from generic type arguments (e.g., List<MyStub>).
             * HIGH fix Round 5: Returns StubRef with FQN when available for accurate matching.
             */
            private Set<StubRef> collectUsedStubTypes(J.ClassDeclaration classDecl) {
                Set<StubRef> stubRefs = new HashSet<>();
                if (classDecl.getBody() == null) {
                    return stubRefs;
                }

                for (Statement stmt : classDecl.getBody().getStatements()) {
                    // Check fields
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        collectStubRefsFromDeclaration(vd.getType(), vd.getTypeExpression(), stubRefs);
                    }
                    // Check method/constructor parameters
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                        for (Statement param : md.getParameters()) {
                            if (param instanceof J.VariableDeclarations) {
                                J.VariableDeclarations vd = (J.VariableDeclarations) param;
                                collectStubRefsFromDeclaration(vd.getType(), vd.getTypeExpression(), stubRefs);
                            }
                        }
                    }
                }
                return stubRefs;
            }

            /**
             * Collect stub references from a variable declaration, including generic type arguments.
             * HIGH fix Round 5: Captures FQN when type attribution is available.
             */
            private void collectStubRefsFromDeclaration(JavaType type, TypeTree typeExpression, Set<StubRef> stubRefs) {
                // First try type attribution (more accurate - has FQN)
                if (type != null) {
                    collectStubRefsFromJavaType(type, stubRefs);
                    return;
                }
                // Fall back to type expression string (no FQN available)
                if (typeExpression != null) {
                    collectStubRefsFromTypeExpression(typeExpression.toString().trim(), stubRefs);
                }
            }

            /**
             * Recursively collect stub references from a JavaType (handles generics).
             * HIGH fix Round 5: Captures FQN for accurate cross-package matching.
             */
            private void collectStubRefsFromJavaType(JavaType type, Set<StubRef> stubRefs) {
                if (type instanceof JavaType.Parameterized) {
                    JavaType.Parameterized parameterized = (JavaType.Parameterized) type;
                    // Check the raw type
                    String rawClassName = parameterized.getClassName();
                    if (rawClassName != null && isStubTypeName(rawClassName)) {
                        String fqn = parameterized.getFullyQualifiedName();
                        stubRefs.add(new StubRef(fqn, rawClassName));
                    }
                    // Recursively check type arguments
                    for (JavaType typeArg : parameterized.getTypeParameters()) {
                        collectStubRefsFromJavaType(typeArg, stubRefs);
                    }
                } else if (type instanceof JavaType.FullyQualified) {
                    JavaType.FullyQualified fq = (JavaType.FullyQualified) type;
                    String className = fq.getClassName();
                    if (className != null && isStubTypeName(className)) {
                        stubRefs.add(new StubRef(fq.getFullyQualifiedName(), className));
                    }
                }
            }

            /**
             * Extract stub references from a type expression string, including generics.
             * LOW fix Round 4: Parse generic type arguments (e.g., "List<MyStub>" extracts "MyStub").
             * Note: No FQN available when parsing from string (type attribution missing).
             */
            private void collectStubRefsFromTypeExpression(String typeStr, Set<StubRef> stubRefs) {
                // Extract the main type name
                String mainType = typeStr;
                int genericStart = typeStr.indexOf('<');
                if (genericStart >= 0) {
                    mainType = typeStr.substring(0, genericStart).trim();
                }
                // Handle FQN in string (extract simple name)
                String fqn = null;
                int lastDot = mainType.lastIndexOf('.');
                if (lastDot >= 0) {
                    fqn = mainType; // Keep FQN if it was a fully qualified string
                    mainType = mainType.substring(lastDot + 1);
                }
                if (isStubTypeName(mainType)) {
                    stubRefs.add(new StubRef(fqn, mainType));
                }

                // Extract generic type arguments
                if (genericStart >= 0) {
                    int genericEnd = typeStr.lastIndexOf('>');
                    if (genericEnd > genericStart) {
                        String genericPart = typeStr.substring(genericStart + 1, genericEnd);
                        // MEDIUM fix Round 6: Use depth-aware splitting to handle nested generics
                        List<String> typeArgs = splitGenericArgs(genericPart);
                        for (String typeArg : typeArgs) {
                            typeArg = typeArg.trim();
                            // Handle wildcards like "? extends FooStub" or "? super FooStub"
                            if (typeArg.startsWith("?")) {
                                int extendsIdx = typeArg.indexOf("extends ");
                                int superIdx = typeArg.indexOf("super ");
                                if (extendsIdx >= 0) {
                                    typeArg = typeArg.substring(extendsIdx + 8).trim();
                                } else if (superIdx >= 0) {
                                    typeArg = typeArg.substring(superIdx + 6).trim();
                                } else {
                                    continue; // Just "?" - no type to check
                                }
                            }
                            // Handle nested generics by recursive processing
                            int nestedGeneric = typeArg.indexOf('<');
                            if (nestedGeneric >= 0) {
                                // Recursively process nested generic
                                collectStubRefsFromTypeExpression(typeArg, stubRefs);
                            } else {
                                // Simple type argument
                                String argFqn = null;
                                int argDot = typeArg.lastIndexOf('.');
                                if (argDot >= 0) {
                                    argFqn = typeArg;
                                    typeArg = typeArg.substring(argDot + 1);
                                }
                                if (isStubTypeName(typeArg)) {
                                    stubRefs.add(new StubRef(argFqn, typeArg));
                                }
                            }
                        }
                    }
                }
            }

            /**
             * MEDIUM fix Round 6: Depth-aware splitting of generic type arguments.
             * Handles nested generics like Map<String, List<FooStub>> correctly.
             */
            private List<String> splitGenericArgs(String genericPart) {
                List<String> args = new ArrayList<>();
                int depth = 0;
                int start = 0;
                for (int i = 0; i < genericPart.length(); i++) {
                    char c = genericPart.charAt(i);
                    if (c == '<') {
                        depth++;
                    } else if (c == '>') {
                        depth--;
                    } else if (c == ',' && depth == 0) {
                        args.add(genericPart.substring(start, i).trim());
                        start = i + 1;
                    }
                }
                // Add last argument
                if (start < genericPart.length()) {
                    args.add(genericPart.substring(start).trim());
                }
                return args;
            }

            private boolean isStubTypeName(String name) {
                return STUB_NAME_PATTERNS.stream().anyMatch(name::endsWith);
            }
        };
    }

    /**
     * Extracts the test source root from the given source path.
     * Uses the configured test source roots from ProjectConfiguration.
     */
    private static Path extractTestSourceRoot(Path sourcePath, ProjectConfiguration config) {
        if (sourcePath == null) {
            // Fallback to first configured test root
            return Paths.get(config.getTestSourceRoots().get(0));
        }
        String pathStr = sourcePath.toString().replace('\\', '/');

        // Try each configured test source root
        for (String testRoot : config.getTestSourceRoots()) {
            int idx = pathStr.indexOf(testRoot);
            if (idx >= 0) {
                return Paths.get(pathStr.substring(0, idx + testRoot.length()));
            }
        }

        // Fallback to first configured test root
        return Paths.get(config.getTestSourceRoots().get(0));
    }

    /**
     * Extracts the project root directory from the given source path.
     * Walks up until it finds pom.xml, build.gradle, build.gradle.kts, or project.yaml.
     * LOW fix: Added build.gradle.kts and settings.gradle support for Kotlin DSL projects.
     */
    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = sourcePath.toAbsolutePath().getParent();
        while (current != null) {
            if (java.nio.file.Files.exists(current.resolve("pom.xml")) ||
                java.nio.file.Files.exists(current.resolve("build.gradle")) ||
                java.nio.file.Files.exists(current.resolve("build.gradle.kts")) ||
                java.nio.file.Files.exists(current.resolve("settings.gradle")) ||
                java.nio.file.Files.exists(current.resolve("settings.gradle.kts")) ||
                java.nio.file.Files.exists(current.resolve("project.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get(System.getProperty("user.dir"));
    }

    private boolean hasSpringStereotypeAnnotation(J.ClassDeclaration classDecl) {
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            String simpleName = ann.getSimpleName();
            if (SPRING_STEREOTYPE_SIMPLE_NAMES.contains(simpleName)) {
                return true;
            }
            if (ann.getType() != null) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ann.getType());
                if (fq != null && SPRING_STEREOTYPES.contains(fq.getFullyQualifiedName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasDefaultConstructor(J.ClassDeclaration classDecl) {
        if (classDecl.getBody() == null) {
            return true;
        }

        boolean hasExplicitConstructor = false;
        for (Statement stmt : classDecl.getBody().getStatements()) {
            if (stmt instanceof J.MethodDeclaration) {
                J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                if (md.isConstructor()) {
                    hasExplicitConstructor = true;
                    if (md.getParameters().isEmpty() ||
                            (md.getParameters().size() == 1 && md.getParameters().get(0) instanceof J.Empty)) {
                        return true;
                    }
                }
            }
        }
        // No explicit constructor means default constructor exists
        return !hasExplicitConstructor;
    }

    /**
     * Result of extracting constructor parameters, including required imports.
     */
    static class ConstructorExtractResult {
        final List<ConstructorParam> params;
        final Set<String> requiredImports;

        ConstructorExtractResult(List<ConstructorParam> params, Set<String> requiredImports) {
            this.params = params;
            this.requiredImports = requiredImports;
        }
    }

    private ConstructorExtractResult extractConstructorParams(J.ClassDeclaration classDecl) {
        if (classDecl.getBody() == null) {
            return new ConstructorExtractResult(Collections.emptyList(), Collections.emptySet());
        }

        // Find the first constructor with params (prefer @Autowired/@Inject if present)
        J.MethodDeclaration selectedConstructor = null;
        for (Statement stmt : classDecl.getBody().getStatements()) {
            if (stmt instanceof J.MethodDeclaration) {
                J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                if (md.isConstructor() && !md.getParameters().isEmpty()) {
                    // Prefer constructor with @Autowired or @Inject
                    if (hasInjectionAnnotation(md)) {
                        selectedConstructor = md;
                        break;
                    }
                    if (selectedConstructor == null) {
                        selectedConstructor = md;
                    }
                }
            }
        }

        if (selectedConstructor == null) {
            return new ConstructorExtractResult(Collections.emptyList(), Collections.emptySet());
        }

        List<ConstructorParam> params = new ArrayList<>();
        Set<String> requiredImports = new LinkedHashSet<>();

        for (Statement param : selectedConstructor.getParameters()) {
            if (param instanceof J.VariableDeclarations) {
                J.VariableDeclarations vd = (J.VariableDeclarations) param;
                String simpleType = vd.getTypeExpression() != null
                        ? vd.getTypeExpression().toString()
                        : "Object";

                // Try to get FQN from type attribution and collect all types (including generics)
                String typeFqn = null;
                if (vd.getType() != null) {
                    // Collect all types including generic type arguments
                    collectAllTypes(vd.getType(), requiredImports);
                    // Get the raw type FQN for the ConstructorParam
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(vd.getType());
                    if (fq != null) {
                        typeFqn = fq.getFullyQualifiedName();
                    }
                }

                for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                    params.add(new ConstructorParam(simpleType, typeFqn, nv.getSimpleName()));
                }
            }
        }
        return new ConstructorExtractResult(params, requiredImports);
    }

    private boolean hasInjectionAnnotation(J.MethodDeclaration md) {
        for (J.Annotation ann : md.getLeadingAnnotations()) {
            String name = ann.getSimpleName();
            if ("Autowired".equals(name) || "Inject".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collect all fully-qualified type names from a JavaType, including generic type arguments.
     * For List&lt;Foo&gt;, this returns both "java.util.List" and the FQN of Foo.
     */
    private void collectAllTypes(JavaType type, Set<String> result) {
        if (type == null) return;

        if (type instanceof JavaType.Parameterized) {
            JavaType.Parameterized parameterized = (JavaType.Parameterized) type;
            // Add the raw type
            String rawFqn = parameterized.getFullyQualifiedName();
            if (rawFqn != null && !rawFqn.startsWith("java.lang.")) {
                result.add(rawFqn);
            }
            // Recursively process type arguments
            for (JavaType typeArg : parameterized.getTypeParameters()) {
                collectAllTypes(typeArg, result);
            }
        } else if (type instanceof JavaType.FullyQualified) {
            JavaType.FullyQualified fq = (JavaType.FullyQualified) type;
            String fqn = fq.getFullyQualifiedName();
            if (fqn != null && !fqn.startsWith("java.lang.")) {
                result.add(fqn);
            }
        } else if (type instanceof JavaType.Array) {
            JavaType.Array arr = (JavaType.Array) type;
            collectAllTypes(arr.getElemType(), result);
        } else if (type instanceof JavaType.GenericTypeVariable) {
            // For generic type variables like T, collect from bounds
            JavaType.GenericTypeVariable gtv = (JavaType.GenericTypeVariable) type;
            for (JavaType bound : gtv.getBounds()) {
                collectAllTypes(bound, result);
            }
        }
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        List<SourceFile> generated = new ArrayList<>();

        // For each sourceRoot
        for (Map.Entry<Path, Map<String, List<StubInfo>>> rootEntry : acc.stubsByRootAndPackage.entrySet()) {
            Path sourceRoot = rootEntry.getKey();
            Map<String, List<StubInfo>> stubsByPackage = rootEntry.getValue();

            // Get test classes for this sourceRoot
            Map<String, TestClassInfo> testClasses = acc.testClassesByRoot.get(sourceRoot);
            if (testClasses == null || testClasses.isEmpty()) {
                continue;
            }

            // Get existing configs in this sourceRoot
            Set<String> existingConfigs = acc.existingConfigsByRoot.getOrDefault(sourceRoot, Collections.emptySet());

            // For each test class, generate a config if there are stubs it uses
            for (TestClassInfo testInfo : testClasses.values()) {
                // HIGH fix #2: Filter stubs to only those actually used by the test
                // LOW fix Round 2: Use root-scoped stub usage map
                // HIGH fix Round 5: Now uses StubRef with FQN for accurate matching
                Map<String, Set<StubRef>> stubUsageInRoot = acc.stubUsageByRootAndTest.get(sourceRoot);
                Set<StubRef> usedStubRefs = stubUsageInRoot != null ? stubUsageInRoot.get(testInfo.fqn) : null;
                List<StubInfo> stubsForThisTest;
                if (usedStubRefs == null || usedStubRefs.isEmpty()) {
                    // HIGH fix Round 6 + MEDIUM fix Round 7: Check if stubs exist in the test's package
                    // If so, mark for @NeedsStubReview instead of silently skipping
                    // Scoped by source root for multi-module support
                    List<StubInfo> samePackageStubs = stubsByPackage.get(testInfo.packageName);
                    if (samePackageStubs != null && !samePackageStubs.isEmpty()) {
                        // Stubs exist but test doesn't reference them directly
                        // Mark for manual review instead of silently skipping
                        acc.testsNeedingReviewByRoot
                                .computeIfAbsent(sourceRoot, k -> new HashSet<>())
                                .add(testInfo.fqn);
                    }
                    continue;
                } else {
                    // HIGH fix Round 4/5: Match by FQN first (accurate), then by simple name for same-package
                    stubsForThisTest = new ArrayList<>();
                    Set<String> matchedStubFqns = new HashSet<>();

                    // First pass: match stubs by FQN (most accurate)
                    for (List<StubInfo> packageStubs : stubsByPackage.values()) {
                        for (StubInfo stub : packageStubs) {
                            for (StubRef ref : usedStubRefs) {
                                if (ref.fqn != null && ref.fqn.equals(stub.fqn)) {
                                    if (!matchedStubFqns.contains(stub.fqn)) {
                                        stubsForThisTest.add(stub);
                                        matchedStubFqns.add(stub.fqn);
                                    }
                                }
                            }
                        }
                    }

                    // Second pass: for refs without FQN, match by simple name from test's package
                    List<StubInfo> samePackageStubs = stubsByPackage.get(testInfo.packageName);
                    if (samePackageStubs != null) {
                        for (StubInfo stub : samePackageStubs) {
                            if (matchedStubFqns.contains(stub.fqn)) {
                                continue; // Already matched by FQN
                            }
                            for (StubRef ref : usedStubRefs) {
                                if (ref.fqn == null && ref.simpleName.equals(stub.className)) {
                                    stubsForThisTest.add(stub);
                                    matchedStubFqns.add(stub.fqn);
                                }
                            }
                        }
                    }

                    // Third pass: for refs with FQN that didn't match, try cross-package if unambiguous
                    for (StubRef ref : usedStubRefs) {
                        // Check if already matched
                        boolean alreadyMatched = false;
                        for (StubInfo matched : stubsForThisTest) {
                            if (ref.fqn != null && ref.fqn.equals(matched.fqn)) {
                                alreadyMatched = true;
                                break;
                            }
                            if (ref.fqn == null && ref.simpleName.equals(matched.className)) {
                                alreadyMatched = true;
                                break;
                            }
                        }
                        if (alreadyMatched) continue;

                        // For refs without FQN, find cross-package matches if unambiguous
                        if (ref.fqn == null) {
                            List<StubInfo> crossPackageMatches = new ArrayList<>();
                            for (Map.Entry<String, List<StubInfo>> pkgEntry : stubsByPackage.entrySet()) {
                                if (pkgEntry.getKey().equals(testInfo.packageName)) {
                                    continue; // Skip same package (already checked)
                                }
                                for (StubInfo stub : pkgEntry.getValue()) {
                                    if (stub.className.equals(ref.simpleName)) {
                                        crossPackageMatches.add(stub);
                                    }
                                }
                            }
                            // Only use cross-package match if unambiguous (exactly one match)
                            if (crossPackageMatches.size() == 1) {
                                stubsForThisTest.add(crossPackageMatches.get(0));
                            }
                        }
                    }

                    if (stubsForThisTest.isEmpty()) {
                        continue;
                    }
                }

                // Generate config name from test class name
                String configName = testInfo.className + "Configuration";
                // HIGH fix Round 3: Use FQN for comparison
                String configFqn = testInfo.packageName.isEmpty() ? configName : testInfo.packageName + "." + configName;

                // HIGH fix #3: Skip if config already exists (use FQN)
                // HIGH fix Round 4: Don't mark stubs as covered - can't verify existing config has the beans
                // This keeps @Component on stubs; developer must manually verify and remove if needed
                if (existingConfigs.contains(configFqn)) {
                    // Config exists, set name for potential @Import merge but DON'T mark stubs as covered
                    // This is safe: test gets @Import, stubs keep @Component (no risk of missing beans)
                    testInfo.generatedConfigName = configName;
                    // Do NOT add stubs to stubsCoveredByConfig - we can't verify existing config has them
                    continue;
                }

                testInfo.generatedConfigName = configName;

                String configSource = generateTestConfiguration(testInfo.packageName, configName, stubsForThisTest);
                Path configPath = buildConfigPath(sourceRoot, testInfo.packageName, configName);

                // Collect all required imports for constructor params
                List<String> additionalDeps = new ArrayList<>();
                for (StubInfo stub : stubsForThisTest) {
                    for (String fqn : stub.requiredImports) {
                        // Create stub for type if needed
                        String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
                        String pkg = fqn.substring(0, fqn.lastIndexOf('.'));
                        additionalDeps.add("package " + pkg + ";\npublic class " + simpleName + " {}\n");
                    }
                }

                JavaParser.Builder parserBuilder = JavaParser.fromJavaVersion()
                        .dependsOn(TEST_CONFIGURATION_STUB, BEAN_STUB, PRIMARY_STUB);
                for (String dep : additionalDeps) {
                    parserBuilder.dependsOn(dep);
                }

                List<SourceFile> parsed = parserBuilder.build().parse(configSource).toList();
                if (!parsed.isEmpty()) {
                    generated.add(parsed.get(0).withSourcePath(configPath));

                    // HIGH fix #1: Mark stubs as covered by this generated config (only used stubs)
                    for (StubInfo stub : stubsForThisTest) {
                        acc.stubsCoveredByConfig
                                .computeIfAbsent(sourceRoot, k -> new HashSet<>())
                                .add(stub.fqn);
                    }
                }
            }
        }

        // HIGH fix Round 7: Generate NeedsStubReview.java annotation file for source roots that need it
        for (Map.Entry<Path, Set<String>> reviewEntry : acc.testsNeedingReviewByRoot.entrySet()) {
            if (reviewEntry.getValue().isEmpty()) continue;
            Path sourceRoot = reviewEntry.getKey();
            // Generate annotation in com.github.migration package
            String annotationSource = generateNeedsStubReviewAnnotation();
            Path annotationPath = sourceRoot.resolve("de/example/migration/NeedsStubReview.java");

            List<SourceFile> parsed = JavaParser.fromJavaVersion().build().parse(annotationSource).toList();
            if (!parsed.isEmpty()) {
                generated.add(parsed.get(0).withSourcePath(annotationPath));
            }
        }

        return generated;
    }

    /**
     * HIGH fix Round 7: Generate the NeedsStubReview annotation source file.
     */
    private String generateNeedsStubReviewAnnotation() {
        return "package com.github.migration;\n\n" +
               "import java.lang.annotation.*;\n\n" +
               "/**\n" +
               " * Marker annotation: This test has stubs in its package but doesn't directly reference them.\n" +
               " * Review if stubs should be included in a @TestConfiguration.\n" +
               " * <p>\n" +
               " * Generated by MigrateTestStubsToTestConfiguration recipe.\n" +
               " */\n" +
               "@Target(ElementType.TYPE)\n" +
               "@Retention(RetentionPolicy.SOURCE)\n" +
               "@Documented\n" +
               "public @interface NeedsStubReview {\n" +
               "    String value() default \"\";\n" +
               "}\n";
    }

    private String generateTestConfiguration(String packageName, String configClassName, List<StubInfo> stubs) {
        StringBuilder sb = new StringBuilder();

        // Package declaration
        if (!packageName.isEmpty()) {
            sb.append("package ").append(packageName).append(";\n\n");
        }

        // Collect all required imports from constructor parameters
        Set<String> additionalImports = new LinkedHashSet<>();
        for (StubInfo stub : stubs) {
            // HIGH fix Round 3: Add import for stub class if it's from a different package
            if (!stub.packageName.equals(packageName) && !stub.packageName.isEmpty()) {
                additionalImports.add(stub.fqn);
            }
            // Add imports for constructor parameter types
            for (String fqn : stub.requiredImports) {
                // Only add import if it's from a different package
                String importPkg = fqn.substring(0, fqn.lastIndexOf('.'));
                if (!importPkg.equals(packageName)) {
                    additionalImports.add(fqn);
                }
            }
        }

        // Imports
        sb.append("import org.springframework.boot.test.context.TestConfiguration;\n");
        sb.append("import org.springframework.context.annotation.Bean;\n");
        sb.append("import org.springframework.context.annotation.Primary;\n");
        // MEDIUM fix #2: Add NeedsReview import if multiple stubs with potential @Primary conflict
        if (stubs.size() > 1) {
            sb.append("// @NeedsReview: Multiple @Primary beans - verify no type conflicts\n");
        }
        // Add imports for stub classes and constructor parameter types from other packages
        for (String fqn : additionalImports) {
            sb.append("import ").append(fqn).append(";\n");
        }
        sb.append("\n");

        // Class declaration with dynamic name
        sb.append("@TestConfiguration\n");
        sb.append("public class ").append(configClassName).append(" {\n\n");

        // Bean methods
        for (StubInfo stub : stubs) {
            String beanMethodName = Character.toLowerCase(stub.className.charAt(0)) + stub.className.substring(1);

            if (stub.hasDefaultConstructor || stub.constructorParams.isEmpty()) {
                // Simple @Bean method with no parameters
                sb.append("    @Bean\n");
                sb.append("    @Primary\n");
                sb.append("    public ").append(stub.className).append(" ").append(beanMethodName).append("() {\n");
                sb.append("        return new ").append(stub.className).append("();\n");
                sb.append("    }\n\n");
            } else {
                // @Bean method with constructor parameters
                String paramDecl = stub.constructorParams.stream()
                        .map(p -> p.type + " " + p.name)
                        .collect(Collectors.joining(", "));
                String paramNames = stub.constructorParams.stream()
                        .map(p -> p.name)
                        .collect(Collectors.joining(", "));

                sb.append("    @Bean\n");
                sb.append("    @Primary\n");
                sb.append("    public ").append(stub.className).append(" ").append(beanMethodName)
                        .append("(").append(paramDecl).append(") {\n");
                sb.append("        return new ").append(stub.className).append("(").append(paramNames).append(");\n");
                sb.append("    }\n\n");
            }
        }

        sb.append("}\n");
        return sb.toString();
    }

    private Path buildConfigPath(Path sourceRoot, String packageName, String configClassName) {
        String packagePath = packageName.replace('.', '/');
        if (packagePath.isEmpty()) {
            return sourceRoot.resolve(configClassName + ".java");
        }
        return sourceRoot.resolve(packagePath).resolve(configClassName + ".java");
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // HIGH fix #1: Only remove stereotypes from stubs that are covered by a generated config
        // MEDIUM fix Round 7: Check testsNeedingReviewByRoot map
        if (acc.stubsCoveredByConfig.isEmpty() && acc.testClassesByRoot.isEmpty() && acc.testsNeedingReviewByRoot.isEmpty()) {
            return TreeVisitor.noop();
        }

        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Only process top-level classes
                if (getCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) {
                    return cd;
                }

                Cursor cursor = getCursor();
                J.CompilationUnit cu = cursor.firstEnclosingOrThrow(J.CompilationUnit.class);
                String sourcePath = cu.getSourcePath() != null ? cu.getSourcePath().toString().replace('\\', '/') : "";

                // Only process test sources (respects project.yaml if present)
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                        extractProjectRoot(cu.getSourcePath()));
                if (!config.isTestSource(sourcePath)) {
                    return cd;
                }

                Path sourceRoot = extractTestSourceRoot(cu.getSourcePath(), config);
                String packageName = cu.getPackageDeclaration() != null
                        ? cu.getPackageDeclaration().getExpression().toString()
                        : "";
                String fqn = packageName.isEmpty() ? cd.getSimpleName() : packageName + "." + cd.getSimpleName();

                // Case 1: This is a stub that needs @Component removed
                // HIGH fix #1: Only remove if stub is covered by a generated config
                Set<String> coveredInRoot = acc.stubsCoveredByConfig.get(sourceRoot);
                if (coveredInRoot != null && coveredInRoot.contains(fqn)) {
                    return removeStereotypeAnnotation(cd);
                }

                // Case 2: This is a @SpringBootTest class that needs @Import added/merged
                Map<String, TestClassInfo> testClassesInRoot = acc.testClassesByRoot.get(sourceRoot);
                TestClassInfo testInfo = testClassesInRoot != null ? testClassesInRoot.get(fqn) : null;
                if (testInfo != null && testInfo.generatedConfigName != null) {
                    if (testInfo.hasExistingImport) {
                        return mergeImportAnnotation(cd, testInfo.generatedConfigName);
                    } else {
                        return addImportAnnotation(cd, testInfo.generatedConfigName);
                    }
                }

                // HIGH fix Round 6 + MEDIUM fix Round 7: Case 3: This test has stubs in package but no direct references
                // Now scoped by source root for multi-module support
                Set<String> testsNeedingReview = acc.testsNeedingReviewByRoot.get(sourceRoot);
                if (testsNeedingReview != null && testsNeedingReview.contains(fqn)) {
                    return addNeedsReviewAnnotation(cd);
                }

                return cd;
            }

            private J.ClassDeclaration removeStereotypeAnnotation(J.ClassDeclaration cd) {
                List<J.Annotation> newAnnotations = new ArrayList<>();
                boolean removed = false;
                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    if (isSpringStereotype(ann)) {
                        removed = true;
                        String fqnToRemove = getAnnotationFqn(ann);
                        if (fqnToRemove != null) {
                            maybeRemoveImport(fqnToRemove);
                        }
                    } else {
                        newAnnotations.add(ann);
                    }
                }

                if (removed) {
                    return cd.withLeadingAnnotations(newAnnotations);
                }
                return cd;
            }

            private J.ClassDeclaration addImportAnnotation(J.ClassDeclaration cd, String configClassName) {
                // Add import for the annotation
                maybeAddImport(IMPORT_FQN);

                // Build template with the specific config class name
                JavaTemplate addImportTemplate = JavaTemplate.builder("@Import(" + configClassName + ".class)")
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(IMPORT_STUB,
                                "package org.example.test;\npublic class " + configClassName + " {}"))
                        .imports(IMPORT_FQN)
                        .build();

                // Add @Import annotation
                return addImportTemplate.apply(
                        getCursor(),
                        cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
                );
            }

            /**
             * HIGH fix Round 6: Add @NeedsStubReview annotation to mark tests for manual review.
             * These tests have stubs in their package but don't directly reference them.
             */
            private J.ClassDeclaration addNeedsReviewAnnotation(J.ClassDeclaration cd) {
                // Check if already has @NeedsStubReview
                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    if ("NeedsStubReview".equals(ann.getSimpleName())) {
                        return cd; // Already has the annotation
                    }
                }

                // Add import for the annotation
                maybeAddImport(NEEDS_REVIEW_FQN);

                // Build template
                JavaTemplate addReviewTemplate = JavaTemplate.builder("@NeedsStubReview(\"Stubs in package not directly referenced\")")
                        .javaParser(JavaParser.fromJavaVersion().dependsOn(NEEDS_REVIEW_STUB))
                        .imports(NEEDS_REVIEW_FQN)
                        .build();

                // Add @NeedsStubReview annotation
                return addReviewTemplate.apply(
                        getCursor(),
                        cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
                );
            }

            private J.ClassDeclaration mergeImportAnnotation(J.ClassDeclaration cd, String configClassName) {
                // MEDIUM fix Round 4: First check ALL @Import annotations for the config class
                // to ensure global idempotency (not just within one annotation)
                String configClassLiteral = configClassName + ".class";
                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    if (!isImportAnnotation(ann)) continue;
                    if (ann.getArguments() == null || ann.getArguments().isEmpty()) continue;

                    Expression valueExpr = extractImportValue(ann.getArguments().get(0));
                    if (containsConfigClass(valueExpr, configClassName, configClassLiteral)) {
                        // Config is already imported in this or another @Import, no change needed
                        return cd;
                    }
                }

                // Config not found in any @Import, merge into the first one
                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    if (!isImportAnnotation(ann)) continue;
                    if (ann.getArguments() == null || ann.getArguments().isEmpty()) continue;

                    Expression firstArg = ann.getArguments().get(0);
                    Expression valueExpr = extractImportValue(firstArg);
                    String mergedImportValue = buildMergedImportValue(valueExpr, configClassName);

                    // Use template to replace the annotation with properly typed AST
                    JavaTemplate mergeTemplate = JavaTemplate.builder("@Import(" + mergedImportValue + ")")
                            .javaParser(JavaParser.fromJavaVersion().dependsOn(IMPORT_STUB,
                                    "package org.example.test;\npublic class " + configClassName + " {}",
                                    "package org.example.test;\npublic class SomeOtherConfig {}",
                                    "package org.example.test;\npublic class AnotherConfig {}"))
                            .imports(IMPORT_FQN)
                            .build();

                    // HIGH fix Round 3: Only remove THIS specific @Import, not all @Import annotations
                    final J.Annotation annotationToRemove = ann;
                    List<J.Annotation> withoutThisImport = new ArrayList<>();
                    for (J.Annotation a : cd.getLeadingAnnotations()) {
                        if (a != annotationToRemove) {
                            withoutThisImport.add(a);
                        }
                    }
                    J.ClassDeclaration withoutOldImport = cd.withLeadingAnnotations(withoutThisImport);

                    return mergeTemplate.apply(
                            updateCursor(withoutOldImport),
                            withoutOldImport.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
                    );
                }
                return cd;
            }

            private boolean isImportAnnotation(J.Annotation ann) {
                if ("Import".equals(ann.getSimpleName())) {
                    return true;
                }
                if (ann.getType() != null) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ann.getType());
                    return fq != null && IMPORT_FQN.equals(fq.getFullyQualifiedName());
                }
                return false;
            }

            /**
             * MEDIUM fix Round 5: Also check FQN-based imports (e.g., "org.example.MyConfig.class").
             */
            private boolean containsConfigClass(Expression valueExpr, String configClassName, String configClassLiteral) {
                if (valueExpr instanceof J.NewArray) {
                    J.NewArray arr = (J.NewArray) valueExpr;
                    List<Expression> elements = arr.getInitializer();
                    if (elements != null) {
                        for (Expression element : elements) {
                            if (matchesConfigClass(element, configClassName, configClassLiteral)) {
                                return true;
                            }
                        }
                    }
                } else {
                    // Single class
                    if (matchesConfigClass(valueExpr, configClassName, configClassLiteral)) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Check if an expression matches the config class, handling both simple and FQN forms.
             * MEDIUM fix Round 5: Handles "MyConfig.class" and "org.example.MyConfig.class".
             */
            private boolean matchesConfigClass(Expression element, String configClassName, String configClassLiteral) {
                String className = extractClassName(element);
                // Direct match: "MyConfig.class"
                if (className.equals(configClassLiteral) || className.equals(configClassName + ".class")) {
                    return true;
                }
                // FQN match: "org.example.MyConfig.class" should match "MyConfig"
                if (className.endsWith("." + configClassName + ".class")) {
                    return true;
                }
                return false;
            }

            /**
             * Extract the actual value from @Import argument, handling both:
             * - @Import(A.class) or @Import({A.class, B.class}) -> returns the argument directly
             * - @Import(value = A.class) or @Import(value = {A.class}) -> returns the RHS of assignment
             */
            private Expression extractImportValue(Expression arg) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    return assignment.getAssignment();
                }
                return arg;
            }

            /**
             * Build the merged import value string for the template.
             * HIGH fix #4: Check if config is already present to make merge idempotent.
             */
            private String buildMergedImportValue(Expression valueExpr, String configClassName) {
                String configClassLiteral = configClassName + ".class";

                if (valueExpr instanceof J.NewArray) {
                    // Already an array like {A.class, B.class}
                    J.NewArray arr = (J.NewArray) valueExpr;
                    List<Expression> elements = arr.getInitializer();
                    if (elements == null || elements.isEmpty()) {
                        return "{" + configClassLiteral + "}";
                    }

                    // HIGH fix #4 + MEDIUM fix Round 5: Check if config is already in the array (FQN-aware)
                    List<String> existingClasses = new ArrayList<>();
                    boolean alreadyPresent = false;
                    for (Expression element : elements) {
                        String className = extractClassName(element);
                        existingClasses.add(className);
                        if (className.equals(configClassLiteral) ||
                            className.equals(configClassName + ".class") ||
                            className.endsWith("." + configClassName + ".class")) {
                            alreadyPresent = true;
                        }
                    }

                    // If already present, return existing array unchanged
                    if (alreadyPresent) {
                        StringBuilder sb = new StringBuilder("{");
                        for (int i = 0; i < existingClasses.size(); i++) {
                            if (i > 0) sb.append(", ");
                            sb.append(existingClasses.get(i));
                        }
                        sb.append("}");
                        return sb.toString();
                    }

                    // Build array with existing elements + config class
                    StringBuilder sb = new StringBuilder("{");
                    for (int i = 0; i < existingClasses.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(existingClasses.get(i));
                    }
                    sb.append(", ").append(configClassLiteral).append("}");
                    return sb.toString();
                } else {
                    // Single class like A.class
                    String existingClass = extractClassName(valueExpr);

                    // HIGH fix #4 + MEDIUM fix Round 5: Check if it's already the config class (FQN-aware)
                    if (existingClass.equals(configClassLiteral) ||
                        existingClass.equals(configClassName + ".class") ||
                        existingClass.endsWith("." + configClassName + ".class")) {
                        return existingClass;
                    }

                    // -> {A.class, ConfigClass.class}
                    return "{" + existingClass + ", " + configClassLiteral + "}";
                }
            }

            /**
             * Extract simple class name from a .class expression for template building.
             */
            private String extractClassName(Expression expr) {
                if (expr instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) expr;
                    if ("class".equals(fa.getSimpleName())) {
                        return fa.getTarget().toString() + ".class";
                    }
                }
                // Fallback to toString but trim whitespace
                return expr.toString().trim();
            }

            private boolean isSpringStereotype(J.Annotation ann) {
                String simpleName = ann.getSimpleName();
                if (SPRING_STEREOTYPE_SIMPLE_NAMES.contains(simpleName)) {
                    return true;
                }
                if (ann.getType() != null) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ann.getType());
                    return fq != null && SPRING_STEREOTYPES.contains(fq.getFullyQualifiedName());
                }
                return false;
            }

            private String getAnnotationFqn(J.Annotation ann) {
                if (ann.getType() != null) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ann.getType());
                    if (fq != null) {
                        return fq.getFullyQualifiedName();
                    }
                }
                // Fallback to simple name mapping
                String simpleName = ann.getSimpleName();
                switch (simpleName) {
                    case "Component": return COMPONENT_FQN;
                    case "Service": return SERVICE_FQN;
                    case "Repository": return REPOSITORY_FQN;
                    default: return null;
                }
            }
        };
    }
}
