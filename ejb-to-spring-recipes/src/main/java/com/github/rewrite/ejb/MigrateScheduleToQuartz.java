package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.text.PlainText;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.rewrite.ejb.TimerScheduleUtils.*;

/**
 * Migrates EJB @Schedule methods to Quartz Job/Trigger pattern.
 * <p>
 * This recipe performs automatic transformation for standard @Schedule methods:
 * <ul>
 *   <li>Creates inner Job class with constructor injection (delegate pattern)</li>
 *   <li>Creates nested @Configuration class with @Bean methods</li>
 *   <li>Generates JobDetail and Trigger beans</li>
 *   <li>Removes @Schedule annotation and Timer parameter</li>
 * </ul>
 * <p>
 * Falls back to @EjbQuartzSchedule marker annotation for complex cases:
 * <ul>
 *   <li>Non-literal values in @Schedule attributes</li>
 *   <li>Multiple @Schedules on same method</li>
 *   <li>Timer.cancel() or other Timer API usage</li>
 *   <li>Year or timezone attributes specified</li>
 * </ul>
 * <p>
 * Bean names include FQN-based prefix to avoid collisions across packages.
 * JobFactory is generated with @ConditionalOnMissingBean to enable constructor injection.
 * SchedulerFactoryBeanCustomizer wires the JobFactory with @ConditionalOnMissingBean.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateScheduleToQuartz extends ScanningRecipe<MigrateScheduleToQuartz.SourceRootAccumulator> {

    // ========== FQN Constants ==========

    // EJB Types
    private static final String EJB_TIMER_FQN = "javax.ejb.Timer";
    private static final String JAKARTA_TIMER_FQN = "jakarta.ejb.Timer";

    // Quartz Types
    private static final String QUARTZ_JOB_FQN = "org.quartz.Job";
    private static final String QUARTZ_JOB_DETAIL_FQN = "org.quartz.JobDetail";
    private static final String QUARTZ_JOB_BUILDER_FQN = "org.quartz.JobBuilder";
    private static final String QUARTZ_TRIGGER_FQN = "org.quartz.Trigger";
    private static final String QUARTZ_TRIGGER_BUILDER_FQN = "org.quartz.TriggerBuilder";
    private static final String QUARTZ_CRON_SCHEDULE_BUILDER_FQN = "org.quartz.CronScheduleBuilder";
    private static final String QUARTZ_JOB_EXECUTION_CONTEXT_FQN = "org.quartz.JobExecutionContext";
    private static final String QUARTZ_JOB_EXECUTION_EXCEPTION_FQN = "org.quartz.JobExecutionException";

    // Spring Types
    private static final String SPRING_BEAN_FQN = "org.springframework.context.annotation.Bean";
    private static final String SPRING_CONFIGURATION_FQN = "org.springframework.context.annotation.Configuration";

    // Note: Boot-specific types (ConditionalOnMissingBean, JobFactory, etc.) moved to
    // shared QuartzJobFactoryAutoConfiguration generated via ScanningRecipe.generate()

    // Marker annotation for fallback cases
    private static final String EJB_QUARTZ_SCHEDULE_MARKER_FQN = "com.github.migration.annotations.EjbQuartzSchedule";

    @Override
    public String getDisplayName() {
        return "Migrate @Schedule to Quartz Job/Trigger pattern";
    }

    @Override
    public String getDescription() {
        return "Converts EJB @Schedule methods to Quartz Job classes with Spring @Bean configuration. " +
               "Creates inner Job class (delegate pattern), nested @Configuration class with JobDetail and Trigger beans. " +
               "Falls back to @EjbQuartzSchedule marker for complex cases (non-literals, multiple schedules, Timer API usage). " +
               "Generates JobFactory with @ConditionalOnMissingBean for constructor injection support.";
    }

    // ========== ScanningRecipe Methods ==========

    // Key for storing actual transformation flag in ExecutionContext (per-module)
    private static final String ACTUAL_TRANSFORMATION_KEY = "MigrateScheduleToQuartz.actualTransformation.";
    // The auto-configuration class we generate
    private static final String AUTO_CONFIG_FQN = "com.github.migration.config.QuartzJobFactoryAutoConfiguration";

    /**
     * Accumulator that tracks per-source-root information for multi-module support.
     * Stores which source roots had transformations and existing imports content per root.
     */
    static class SourceRootAccumulator {
        // Map from source root path to info about that source root
        final Map<String, SourceRootInfo> sourceRoots = new ConcurrentHashMap<>();
        // Pending imports: stores imports content keyed by normalized resources root for files scanned before source roots
        final Map<String, String> pendingImports = new ConcurrentHashMap<>();

        /**
         * Normalizes a path for exact comparison: removes trailing slashes and standardizes separators.
         */
        private static String normalizePath(String path) {
            if (path == null) return null;
            String normalized = path.replace('\\', '/');
            while (normalized.endsWith("/")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            return normalized;
        }

        void recordSourceRoot(String sourceRoot, boolean isTestSource) {
            SourceRootInfo info = sourceRoots.computeIfAbsent(sourceRoot, k -> new SourceRootInfo(k, isTestSource));
            // Check if there are pending imports for this source root - use exact path match only
            String expectedResources = normalizePath(deriveResourcesRootStatic(sourceRoot));
            if (expectedResources != null) {
                String matchedKey = null;
                for (Map.Entry<String, String> entry : pendingImports.entrySet()) {
                    String resourcesRoot = normalizePath(entry.getKey());
                    // Strict exact match only: paths must be identical after normalization
                    if (resourcesRoot != null && resourcesRoot.equals(expectedResources)) {
                        info.existingImportsContent = entry.getValue();
                        matchedKey = entry.getKey();
                        break;
                    }
                }
                if (matchedKey != null) {
                    pendingImports.remove(matchedKey);
                }
            }
        }

        void recordTransformation(String sourceRoot) {
            SourceRootInfo info = sourceRoots.computeIfAbsent(sourceRoot, k ->
                new SourceRootInfo(k, sourceRoot.contains("/test/") ||
                                      sourceRoot.endsWith("/test/java") ||
                                      sourceRoot.contains("src/test")));
            info.hasTransformation = true;
        }

        void recordExistingImports(String resourcesRoot, String content) {
            String normalizedResourcesRoot = normalizePath(resourcesRoot);
            // Try to match to an existing source root - use strict exact path match only
            for (SourceRootInfo info : sourceRoots.values()) {
                // Strict exact match only: paths must be identical after normalization
                String expectedResources = normalizePath(deriveResourcesRootStatic(info.sourceRoot));
                if (expectedResources != null && normalizedResourcesRoot != null &&
                        normalizedResourcesRoot.equals(expectedResources)) {
                    info.existingImportsContent = content;
                    return;
                }
            }
            // No matching source root yet - store in pending map keyed by normalized path for later exact match
            pendingImports.put(normalizedResourcesRoot, content);
        }
    }

    static class SourceRootInfo {
        final String sourceRoot;
        final boolean isTestSource;
        volatile boolean hasTransformation;
        volatile String existingImportsContent;

        SourceRootInfo(String sourceRoot, boolean isTestSource) {
            this.sourceRoot = sourceRoot;
            this.isTestSource = isTestSource;
        }
    }

    @Override
    public SourceRootAccumulator getInitialValue(ExecutionContext ctx) {
        return new SourceRootAccumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(SourceRootAccumulator acc) {
        // Scanner pass: check for @Schedule annotations AND detect source roots per module
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    Path sourcePath = sourceFile.getSourcePath();
                    String sourcePathStr = sourcePath.toString().replace('\\', '/');

                    // Check for existing AutoConfiguration.imports file and store content per module
                    if (sourcePathStr.endsWith("org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
                        if (tree instanceof PlainText) {
                            String existingContent = ((PlainText) tree).getText();
                            // Derive resource root from path
                            String resourcesRoot = sourcePathStr.substring(0,
                                sourcePathStr.indexOf("/META-INF"));
                            acc.recordExistingImports(resourcesRoot, existingContent);
                        }
                    }

                    // Detect source root from Java files
                    if (tree instanceof J.CompilationUnit) {
                        J.CompilationUnit cu = (J.CompilationUnit) tree;
                        String sourceRoot = detectSourceRoot(cu);

                        if (sourceRoot != null) {
                            boolean isTestSource = sourceRoot.contains("/test/") ||
                                                   sourceRoot.endsWith("/test/java") ||
                                                   sourceRoot.contains("src/test");
                            acc.recordSourceRoot(sourceRoot, isTestSource);
                        }
                    }
                }
                return tree;
            }
        };
    }

    /**
     * Detects the source root from a CompilationUnit by comparing the package path with the source path.
     * Returns null if unable to determine.
     */
    private static String detectSourceRoot(J.CompilationUnit cu) {
        Path sourcePath = cu.getSourcePath();
        if (cu.getPackageDeclaration() != null) {
            String packageName = cu.getPackageDeclaration().getPackageName();
            String packagePath = packageName.replace('.', '/');
            String sourcePathStr = sourcePath.toString().replace('\\', '/');

            // Find where the package path starts in the source path
            int packageIdx = sourcePathStr.indexOf(packagePath);
            if (packageIdx > 0) {
                // Source root is everything before the package path
                return sourcePathStr.substring(0, packageIdx - 1); // -1 for the trailing /
            }
        }
        return null;
    }

    /**
     * Checks if a class declaration has @Schedule or @Schedules annotations on any methods.
     */
    private static boolean hasScheduleAnnotations(J.ClassDeclaration classDecl) {
        for (Statement stmt : classDecl.getBody().getStatements()) {
            if (stmt instanceof J.MethodDeclaration) {
                J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                for (J.Annotation ann : md.getLeadingAnnotations()) {
                    if (ann.getSimpleName().equals("Schedule") || ann.getSimpleName().equals("Schedules")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public Collection<SourceFile> generate(SourceRootAccumulator acc, ExecutionContext ctx) {
        List<SourceFile> generated = new ArrayList<>();

        // Find source roots that had actual transformations (prefer main over test)
        List<SourceRootInfo> transformedRoots = new ArrayList<>();
        for (SourceRootInfo info : acc.sourceRoots.values()) {
            if (info.hasTransformation && !info.isTestSource) {
                transformedRoots.add(info);
            }
        }

        // If no main source transformations, check test sources
        if (transformedRoots.isEmpty()) {
            for (SourceRootInfo info : acc.sourceRoots.values()) {
                if (info.hasTransformation) {
                    transformedRoots.add(info);
                }
            }
        }

        // Fallback: If no source roots tracked but context indicates transformation happened,
        // generate with default paths (for backward compatibility with tests)
        if (transformedRoots.isEmpty()) {
            Boolean actualTransformation = ctx.getMessage(ACTUAL_TRANSFORMATION_KEY + "default");
            if (actualTransformation != null && actualTransformation) {
                transformedRoots.add(new SourceRootInfo("src/main/java", false));
            }
        }

        // Generate files for each source root that had transformations
        for (SourceRootInfo info : transformedRoots) {
            String sourceRoot = info.sourceRoot;
            if (sourceRoot == null) {
                sourceRoot = "src/main/java";
            }
            String resourcesRoot = deriveResourcesRootStatic(sourceRoot);

            // Generate shared QuartzJobFactoryAutoConfiguration
            String autoConfigContent = generateAutoConfigurationClass();
            Path autoConfigPath = Paths.get(sourceRoot + "/com/github/rewrite/migration/config/QuartzJobFactoryAutoConfiguration.java");
            generated.add(PlainText.builder()
                .sourcePath(autoConfigPath)
                .text(autoConfigContent)
                .build());

            // Merge existing imports content with our new entry (per module)
            String mergedImportsContent = mergeAutoConfigImports(info.existingImportsContent);
            Path importsPath = Paths.get(resourcesRoot + "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");
            generated.add(PlainText.builder()
                .sourcePath(importsPath)
                .text(mergedImportsContent)
                .build());
        }

        return generated;
    }

    /**
     * Derives the resources root from the Java source root.
     * e.g., "src/main/java" -> "src/main/resources"
     *       "module-a/src/main/java" -> "module-a/src/main/resources"
     */
    private static String deriveResourcesRootStatic(String sourceRoot) {
        if (sourceRoot == null) {
            return "src/main/resources";
        }
        if (sourceRoot.endsWith("/java")) {
            return sourceRoot.substring(0, sourceRoot.length() - 5) + "/resources";
        }
        // Fallback: just replace the last segment
        int lastSlash = sourceRoot.lastIndexOf('/');
        if (lastSlash > 0) {
            return sourceRoot.substring(0, lastSlash) + "/resources";
        }
        return "src/main/resources";
    }

    /**
     * Merges existing AutoConfiguration.imports content with our new entry.
     * Preserves existing lines and avoids duplicates.
     */
    private static String mergeAutoConfigImports(String existingContent) {
        Set<String> entries = new LinkedHashSet<>();  // Preserve order

        // Add existing entries (if any)
        if (existingContent != null && !existingContent.isBlank()) {
            for (String line : existingContent.split("\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                    entries.add(trimmed);
                }
            }
        }

        // Add our entry (will not duplicate if already present)
        entries.add(AUTO_CONFIG_FQN);

        // Build merged content
        StringBuilder sb = new StringBuilder();
        for (String entry : entries) {
            sb.append(entry).append("\n");
        }
        return sb.toString();
    }

    /**
     * Generates the shared QuartzJobFactoryAutoConfiguration class content.
     */
    private String generateAutoConfigurationClass() {
        return """
            package com.github.migration.config;

            import org.quartz.Job;
            import org.quartz.Scheduler;
            import org.quartz.SchedulerException;
            import org.quartz.spi.JobFactory;
            import org.quartz.spi.TriggerFiredBundle;
            import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
            import org.springframework.boot.autoconfigure.AutoConfiguration;
            import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
            import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
            import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
            import org.springframework.context.annotation.Bean;

            /**
             * Auto-configuration for Quartz JobFactory with constructor injection support.
             * Generated by MigrateScheduleToQuartz recipe.
             * <p>
             * This configuration enables Quartz to instantiate Job classes using Spring's
             * AutowireCapableBeanFactory, which supports constructor injection.
             */
            @AutoConfiguration
            @ConditionalOnClass(Job.class)
            public class QuartzJobFactoryAutoConfiguration {

                @Bean
                @ConditionalOnMissingBean(JobFactory.class)
                public JobFactory quartzJobFactory(AutowireCapableBeanFactory beanFactory) {
                    return new JobFactory() {
                        @Override
                        public Job newJob(TriggerFiredBundle bundle, Scheduler scheduler) throws SchedulerException {
                            return beanFactory.createBean(bundle.getJobDetail().getJobClass());
                        }
                    };
                }

                @Bean
                @ConditionalOnMissingBean(SchedulerFactoryBeanCustomizer.class)
                public SchedulerFactoryBeanCustomizer schedulerCustomizer(JobFactory quartzJobFactory) {
                    return schedulerFactoryBean -> schedulerFactoryBean.setJobFactory(quartzJobFactory);
                }
            }
            """;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(SourceRootAccumulator acc) {
        TreeVisitor<?, ExecutionContext> delegate = Preconditions.check(
            new UsesType<>(SCHEDULE_FQN, false),
            new QuartzTransformationVisitor(acc)
        );
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (!TimerStrategySupport.isStrategy(cu, ProjectConfiguration.TimerStrategy.QUARTZ)) {
                    return cu;
                }
                return (J.CompilationUnit) delegate.visit(cu, ctx);
            }
        };
    }

    // ========== Helper Classes ==========

    /**
     * Holds analysis results for a class containing @Schedule methods.
     */
    private static class ScheduleAnalysis {
        final List<TransformableMethod> transformableMethods = new ArrayList<>();
        final List<J.MethodDeclaration> fallbackMethods = new ArrayList<>();
    }

    /**
     * Represents a method that can be automatically transformed to Quartz pattern.
     * Supports multiple schedules (from @Schedules container).
     */
    private static class TransformableMethod {
        final J.MethodDeclaration method;
        final List<ScheduleConfig> configs;  // Multiple configs for @Schedules
        final boolean hasTimerParameter;
        final String timerParameterName;  // Actual name of Timer parameter (if any)
        final String timerInfo;
        final String signatureSuffix;  // Suffix for overloaded method disambiguation

        TransformableMethod(J.MethodDeclaration method, List<ScheduleConfig> configs, boolean hasTimerParameter, String timerParameterName, String timerInfo, String signatureSuffix) {
            this.method = method;
            this.configs = configs;
            this.hasTimerParameter = hasTimerParameter;
            this.timerParameterName = timerParameterName;
            this.timerInfo = timerInfo;
            this.signatureSuffix = signatureSuffix;
        }
    }

    /**
     * Computes a signature suffix from method parameters to disambiguate overloaded methods.
     * Returns empty string if no parameters (after excluding Timer).
     * Returns camelCase type names for parameters, e.g., "StringInt" for (String s, int i).
     * Preserves original type casing (e.g., "URL" stays "URL", "ListOfString" stays "ListOfString").
     */
    private static String computeSignatureSuffix(J.MethodDeclaration method) {
        if (method.getParameters().isEmpty()) {
            return "";
        }
        StringBuilder suffix = new StringBuilder();
        for (Statement param : method.getParameters()) {
            if (param instanceof J.Empty) {
                continue;
            }
            if (param instanceof J.VariableDeclarations) {
                J.VariableDeclarations vd = (J.VariableDeclarations) param;
                if (vd.getTypeExpression() != null) {
                    String typeStr = getTypeName(vd.getTypeExpression());
                    // Skip Timer parameter
                    if ("Timer".equals(typeStr) || typeStr.endsWith(".Timer")) {
                        continue;
                    }
                    // Extract simple type name (remove package, handle arrays/generics)
                    String simpleName = extractSimpleTypeName(typeStr);
                    // Capitalize only the first letter, preserve the rest of the casing
                    suffix.append(capitalize(simpleName));
                }
            }
        }
        return suffix.toString();
    }

    /**
     * Gets the type name from a type expression AST node without using print().
     * Includes generic type arguments for parameterized types.
     */
    private static String getTypeName(TypeTree typeExpr) {
        if (typeExpr instanceof J.Identifier) {
            return ((J.Identifier) typeExpr).getSimpleName();
        } else if (typeExpr instanceof J.ArrayType) {
            return getTypeName(((J.ArrayType) typeExpr).getElementType()) + "[]";
        } else if (typeExpr instanceof J.ParameterizedType) {
            J.ParameterizedType pt = (J.ParameterizedType) typeExpr;
            StringBuilder sb = new StringBuilder();
            sb.append(getTypeNameFromNameTree(pt.getClazz()));
            if (pt.getTypeParameters() != null && !pt.getTypeParameters().isEmpty()) {
                sb.append("<");
                boolean first = true;
                for (Expression typeArg : pt.getTypeParameters()) {
                    if (!first) {
                        sb.append(",");
                    }
                    first = false;
                    if (typeArg instanceof TypeTree) {
                        sb.append(getTypeName((TypeTree) typeArg));
                    } else if (typeArg instanceof J.Identifier) {
                        sb.append(((J.Identifier) typeArg).getSimpleName());
                    } else {
                        sb.append(typeArg.toString());
                    }
                }
                sb.append(">");
            }
            return sb.toString();
        } else if (typeExpr instanceof J.FieldAccess) {
            return ((J.FieldAccess) typeExpr).getSimpleName();
        } else if (typeExpr instanceof J.Primitive) {
            return ((J.Primitive) typeExpr).getType().getKeyword();
        } else if (typeExpr instanceof J.Wildcard) {
            J.Wildcard wc = (J.Wildcard) typeExpr;
            if (wc.getBoundedType() != null) {
                String boundType = getTypeNameFromNameTree(wc.getBoundedType());
                // J.Wildcard.Bound enum: EXTENDS or SUPER
                J.Wildcard.Bound bound = wc.getBound();
                if (bound == J.Wildcard.Bound.Extends) {
                    return "?extends" + boundType;
                } else if (bound == J.Wildcard.Bound.Super) {
                    return "?super" + boundType;
                }
            }
            return "?";
        } else {
            // Fallback: try to get toString representation
            return typeExpr.toString();
        }
    }

    /**
     * Gets the type name from a NameTree (used for ParameterizedType.getClazz()).
     */
    private static String getTypeNameFromNameTree(NameTree nameTree) {
        if (nameTree instanceof J.Identifier) {
            return ((J.Identifier) nameTree).getSimpleName();
        } else if (nameTree instanceof J.FieldAccess) {
            return ((J.FieldAccess) nameTree).getSimpleName();
        } else {
            return nameTree.toString();
        }
    }

    /**
     * Extracts simple type name from a full type string.
     * Handles arrays, generics, and fully qualified names.
     * Preserves generic type arguments in sanitized form to avoid overload collisions.
     * e.g., "List<String>" -> "ListOfString", "Map<String,Integer>" -> "MapOfStringAndInteger"
     */
    private static String extractSimpleTypeName(String typeStr) {
        StringBuilder result = new StringBuilder();

        int genericIdx = typeStr.indexOf('<');
        if (genericIdx > 0) {
            // Extract raw type and generic part
            String rawType = typeStr.substring(0, genericIdx);
            String genericPart = typeStr.substring(genericIdx + 1, typeStr.lastIndexOf('>'));

            // Get simple name from raw type
            result.append(getSimpleNameFromFQN(rawType.replace("[]", "Array")));

            // Process generic arguments
            result.append("Of");
            String[] typeArgs = splitGenericArgs(genericPart);
            for (int i = 0; i < typeArgs.length; i++) {
                if (i > 0) {
                    result.append("And");
                }
                String arg = typeArgs[i].trim();
                // Handle wildcards
                if (arg.startsWith("?")) {
                    if (arg.contains("extends")) {
                        result.append("Extends");
                        arg = arg.substring(arg.indexOf("extends") + 7).trim();
                    } else if (arg.contains("super")) {
                        result.append("Super");
                        arg = arg.substring(arg.indexOf("super") + 5).trim();
                    } else {
                        result.append("Wildcard");
                        continue;
                    }
                }
                // Recursively process nested generics
                result.append(extractSimpleTypeName(arg));
            }
        } else {
            // No generics - just get simple name
            result.append(getSimpleNameFromFQN(typeStr.replace("[]", "Array")));
        }

        return result.toString();
    }

    /**
     * Gets simple class name from a potentially fully qualified name.
     */
    private static String getSimpleNameFromFQN(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot >= 0) {
            return fqn.substring(lastDot + 1);
        }
        return fqn;
    }

    /**
     * Splits generic arguments respecting nested angle brackets.
     * e.g., "String, Map<K,V>" -> ["String", "Map<K,V>"]
     */
    private static String[] splitGenericArgs(String genericPart) {
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
        args.add(genericPart.substring(start).trim());
        return args.toArray(new String[0]);
    }

    /**
     * Capitalizes the first character of a string (static version for use in static methods).
     */
    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ========== Main Visitor ==========

    private class QuartzTransformationVisitor extends JavaIsoVisitor<ExecutionContext> {

        private final SourceRootAccumulator acc;

        QuartzTransformationVisitor(SourceRootAccumulator acc) {
            this.acc = acc;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Skip inner classes - we only transform top-level classes
            if (getCursor().getParentTreeCursor().getValue() instanceof J.ClassDeclaration) {
                return cd;
            }

            // Analyze all methods in this class
            ScheduleAnalysis analysis = analyzeClass(cd);

            if (analysis.transformableMethods.isEmpty() && analysis.fallbackMethods.isEmpty()) {
                return cd;
            }

            // Compute bean name prefix from package (Round 12 fix: use package declaration, not type info)
            String beanNamePrefix = computeBeanNamePrefix(cd);

            // Apply transformations
            if (!analysis.transformableMethods.isEmpty()) {
                cd = applyAutomaticTransformation(cd, analysis.transformableMethods, beanNamePrefix, ctx);

                // Record transformation for the source root of this compilation unit
                J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                String sourceRoot = detectSourceRoot(cu);
                // Fall back to default source root if detection fails (e.g., in tests without full paths)
                if (sourceRoot == null) {
                    sourceRoot = "src/main/java";
                    // Also set context message for fallback in generate()
                    ctx.putMessage(ACTUAL_TRANSFORMATION_KEY + "default", true);
                }
                acc.recordTransformation(sourceRoot);
            }

            if (!analysis.fallbackMethods.isEmpty()) {
                cd = applyFallbackTransformation(cd, analysis.fallbackMethods, ctx);
            }

            // Only remove Timer imports if there are NO fallback methods that use Timer
            boolean fallbackUsesTimer = analysis.fallbackMethods.stream()
                .anyMatch(m -> hasTimerParameter(m));
            if (!fallbackUsesTimer) {
                doAfterVisit(new RemoveImport<>(EJB_TIMER_FQN, true));
                doAfterVisit(new RemoveImport<>(JAKARTA_TIMER_FQN, true));
            }

            return cd;
        }

        // ========== Analysis Methods ==========

        /**
         * Analyzes a class for @Schedule methods and determines transformation strategy.
         */
        private ScheduleAnalysis analyzeClass(J.ClassDeclaration classDecl) {
            ScheduleAnalysis analysis = new ScheduleAnalysis();

            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (!(stmt instanceof J.MethodDeclaration)) {
                    continue;
                }
                J.MethodDeclaration method = (J.MethodDeclaration) stmt;

                if (!hasScheduleAnnotation(method)) {
                    continue;
                }

                // Check if THIS method uses unsupported Timer APIs (per-method check)
                // Unsupported: cancel(), getTimeRemaining(), getNextTimeout(), isPersistent(),
                //              getHandle(), getSchedule(), and Timer passed as argument
                boolean hasUnsupportedTimer = hasUnsupportedTimerUsage(method);

                // Check if method needs fallback (non-literal values, year/timezone, unsupported Timer usage)
                if (needsFallback(method) || hasUnsupportedTimer) {
                    analysis.fallbackMethods.add(method);
                } else {
                    // Can be automatically transformed - extract ALL schedule configs
                    List<ScheduleConfig> configs = extractAllScheduleConfigs(method);
                    String timerParamName = getTimerParameterName(method);
                    boolean hasTimer = timerParamName != null;
                    String timerInfo = configs.isEmpty() ? null : configs.get(0).getInfo();
                    String signatureSuffix = computeSignatureSuffix(method);
                    analysis.transformableMethods.add(new TransformableMethod(method, configs, hasTimer, timerParamName, timerInfo, signatureSuffix));
                }
            }

            // Post-process: detect Timer-only overload collisions
            // If transforming multiple methods would result in the same signature, fall back to marker
            detectAndHandleOverloadCollisions(analysis);

            return analysis;
        }

        /**
         * Detects Timer-only overload collisions and moves colliding methods to fallback.
         * Example: foo() and foo(Timer) would both become foo() after transformation,
         * causing duplicate method signatures.
         *
         * Strategy: Keep non-Timer methods, only fallback Timer-param methods in collision groups.
         * This preserves automation for safe cases like foo(String) even when foo(Timer) exists.
         */
        private void detectAndHandleOverloadCollisions(ScheduleAnalysis analysis) {
            // Compute post-transformation signature for each method
            // Signature = method name + non-Timer parameters (which equals signatureSuffix)
            java.util.Map<String, java.util.List<TransformableMethod>> signatureGroups = new java.util.HashMap<>();
            for (TransformableMethod tm : analysis.transformableMethods) {
                String postTransformSignature = tm.method.getSimpleName() + tm.signatureSuffix;
                signatureGroups.computeIfAbsent(postTransformSignature, k -> new java.util.ArrayList<>()).add(tm);
            }

            // Find collisions and determine which methods to fallback
            java.util.Set<TransformableMethod> methodsToFallback = new java.util.HashSet<>();
            for (java.util.Map.Entry<String, java.util.List<TransformableMethod>> entry : signatureGroups.entrySet()) {
                java.util.List<TransformableMethod> group = entry.getValue();
                if (group.size() > 1) {
                    // Collision detected - keep non-Timer methods, fallback Timer-param methods
                    java.util.List<TransformableMethod> withTimer = new java.util.ArrayList<>();
                    java.util.List<TransformableMethod> withoutTimer = new java.util.ArrayList<>();
                    for (TransformableMethod tm : group) {
                        if (tm.hasTimerParameter) {
                            withTimer.add(tm);
                        } else {
                            withoutTimer.add(tm);
                        }
                    }

                    if (!withoutTimer.isEmpty()) {
                        // Keep all non-Timer methods, fallback all Timer-param methods
                        methodsToFallback.addAll(withTimer);
                    } else {
                        // All methods have Timer - keep one (first), fallback others
                        for (int i = 1; i < group.size(); i++) {
                            methodsToFallback.add(group.get(i));
                        }
                    }
                }
            }

            // Move colliding methods from transformable to fallback
            for (TransformableMethod tm : methodsToFallback) {
                analysis.transformableMethods.remove(tm);
                analysis.fallbackMethods.add(tm.method);
            }
        }

        /**
         * Determines if a method needs fallback (marker annotation) instead of automatic transformation.
         * Note: Multiple @Schedules entries do NOT trigger fallback - we generate multiple triggers.
         */
        private boolean needsFallback(J.MethodDeclaration method) {
            // Check ALL schedule configs for non-literal values or year/timezone
            List<ScheduleConfig> configs = extractAllScheduleConfigs(method);
            for (ScheduleConfig config : configs) {
                if (config.hasNonLiterals()) {
                    return true;
                }
                if (config.getYear() != null && !"*".equals(config.getYear())) {
                    return true;
                }
                if (config.getTimezone() != null && !config.getTimezone().isEmpty()) {
                    return true;
                }
            }

            return false;
        }

        /**
         * Extracts ALL ScheduleConfigs from a method (handles both @Schedule and @Schedules).
         */
        private List<ScheduleConfig> extractAllScheduleConfigs(J.MethodDeclaration method) {
            List<ScheduleConfig> configs = new ArrayList<>();
            for (J.Annotation ann : method.getLeadingAnnotations()) {
                if (isScheduleAnnotation(ann)) {
                    configs.add(extractScheduleConfig(ann));
                } else if (isSchedulesAnnotation(ann)) {
                    List<J.Annotation> inner = extractSchedulesFromContainer(ann);
                    for (J.Annotation innerAnn : inner) {
                        configs.add(extractScheduleConfig(innerAnn));
                    }
                }
            }
            return configs;
        }

        /**
         * Checks if any unsupported Timer API is used in a method's body.
         * Supported: timer.getInfo() (transformed to timerInfo field)
         * Unsupported: cancel(), getTimeRemaining(), getNextTimeout(), isPersistent(),
         *              getHandle(), getSchedule(), and Timer passed as argument.
         */
        private boolean hasUnsupportedTimerUsage(J.MethodDeclaration method) {
            if (method.getBody() == null) {
                return false;
            }
            // Get the actual Timer parameter name from this method (if any)
            final String timerParamName = getTimerParameterName(method);

            // Set of unsupported Timer methods (getInfo is supported and handled separately)
            java.util.Set<String> unsupportedMethods = java.util.Set.of(
                "cancel", "getTimeRemaining", "getNextTimeout", "isPersistent", "getHandle", "getSchedule"
            );

            Boolean[] result = {false};
            new JavaIsoVisitor<Boolean[]>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Boolean[] ctx) {
                    String methodName = mi.getSimpleName();
                    Expression select = mi.getSelect();

                    // Check if Timer method is called
                    if (unsupportedMethods.contains(methodName) && select != null) {
                        if (isTimerExpression(select, timerParamName)) {
                            ctx[0] = true;
                        }
                    }

                    // Check if Timer is passed as argument to any method
                    for (Expression arg : mi.getArguments()) {
                        if (isTimerExpression(arg, timerParamName)) {
                            ctx[0] = true;
                        }
                    }

                    return super.visitMethodInvocation(mi, ctx);
                }

                /**
                 * Checks if an expression is a Timer instance.
                 */
                private boolean isTimerExpression(Expression expr, String timerParamName) {
                    // Check by type if available
                    if (expr.getType() != null && expr.getType() instanceof JavaType.FullyQualified) {
                        String typeFqn = ((JavaType.FullyQualified) expr.getType()).getFullyQualifiedName();
                        if (EJB_TIMER_FQN.equals(typeFqn) || JAKARTA_TIMER_FQN.equals(typeFqn)) {
                            return true;
                        }
                    }
                    // Also check by identifier name since type info may be absent
                    // Use the actual Timer parameter name instead of hardcoded "timer"
                    if (expr instanceof J.Identifier && timerParamName != null) {
                        String name = ((J.Identifier) expr).getSimpleName();
                        if (timerParamName.equals(name)) {
                            return true;
                        }
                    }
                    return false;
                }
            }.visit(method.getBody(), result);
            return result[0];
        }
        // ========== Bean Name Prefix Computation (Round 12 Fix) ==========

        /**
         * Computes bean name prefix from package declaration (not type info).
         * This is reliable because package declaration is always available,
         * unlike type attribution which may be absent during rewrite.
         *
         * Example: com.example.scheduling.MyScheduler → comExampleSchedulingMyScheduler
         */
        private String computeBeanNamePrefix(J.ClassDeclaration classDecl) {
            // Get package from CompilationUnit (reliable, always available)
            String packageName = "";
            Cursor cuCursor = getCursor().dropParentUntil(c -> c instanceof J.CompilationUnit);
            if (cuCursor.getValue() instanceof J.CompilationUnit) {
                J.CompilationUnit cu = cuCursor.getValue();
                if (cu.getPackageDeclaration() != null) {
                    J.Package pkg = cu.getPackageDeclaration();
                    packageName = pkg.getExpression().print(getCursor()).trim();
                }
            }

            String simpleName = classDecl.getSimpleName();

            if (packageName.isEmpty()) {
                // No package - just decapitalize class name
                return decapitalize(simpleName);
            }

            // Build camelCase prefix from package + class name
            String[] parts = packageName.split("\\.");
            StringBuilder sb = new StringBuilder();

            for (int i = 0; i < parts.length; i++) {
                if (i == 0) {
                    sb.append(parts[i].toLowerCase());
                } else {
                    sb.append(capitalize(parts[i]));
                }
            }
            sb.append(capitalize(simpleName));

            return sb.toString();
        }

        // ========== Automatic Transformation (Phase 2-5) ==========

        /**
         * Applies automatic transformation:
         * 1. Adds timerInfo field when info attribute is present
         * 2. Removes @Schedule annotation from methods
         * 3. Removes Timer parameter and replaces timer.getInfo() calls
         * 4. Creates inner Job class with constructor injection
         * 5. Creates nested @Configuration class with @Bean methods
         *
         * Statement order: [timerInfo field] + [all methods] + [Job classes] + [QuartzConfig]
         */
        private J.ClassDeclaration applyAutomaticTransformation(
                J.ClassDeclaration classDecl,
                List<TransformableMethod> methods,
                String beanNamePrefix,
                ExecutionContext ctx) {

            String enclosingClassName = classDecl.getSimpleName();
            List<Statement> newStatements = new ArrayList<>();
            List<J.ClassDeclaration> jobClasses = new ArrayList<>();

            // Check if any method needs timerInfo field (has info attribute)
            String timerInfoValue = null;
            for (TransformableMethod tm : methods) {
                if (tm.timerInfo != null && !tm.timerInfo.isEmpty()) {
                    timerInfoValue = tm.timerInfo;
                    break;
                }
            }

            // Add timerInfo field at beginning if needed
            if (timerInfoValue != null) {
                J.VariableDeclarations timerInfoField = createTimerInfoField(timerInfoValue);
                newStatements.add(timerInfoField);
            }

            // Track if we need to add blank line before first method (after timerInfo field)
            boolean needsBlankLineBeforeFirstMethod = timerInfoValue != null;

            // Process existing statements: transform methods, keep others
            // Collect Job classes separately to add after ALL methods
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    // Capture method ID in final variable for lambda (md gets reassigned later)
                    final UUID methodId = md.getId();

                    // Check if this method should be transformed - match by UUID for correct overload handling
                    TransformableMethod tm = methods.stream()
                        .filter(m -> m.method.getId().equals(methodId))
                        .findFirst()
                        .orElse(null);

                    if (tm != null) {
                        // Transform the method: remove @Schedule, remove Timer param, replace timer.getInfo()
                        md = transformScheduledMethod(md, tm);

                        // If this is the first method after timerInfo field, add blank line prefix
                        if (needsBlankLineBeforeFirstMethod && !md.getModifiers().isEmpty()) {
                            // Clear method's own prefix and set proper spacing on first modifier
                            md = md.withPrefix(Space.EMPTY);
                            List<J.Modifier> modifiers = new ArrayList<>(md.getModifiers());
                            J.Modifier firstMod = modifiers.get(0);
                            modifiers.set(0, firstMod.withPrefix(Space.format("\n\n    ")));
                            md = md.withModifiers(modifiers);
                            needsBlankLineBeforeFirstMethod = false;
                        }

                        newStatements.add(md);

                        // Create inner Job class for this method (collect to add later)
                        // Include signature suffix to distinguish overloaded methods
                        J.ClassDeclaration jobClass = createDelegatingJobClass(
                            enclosingClassName, md.getSimpleName(), tm.signatureSuffix, jobClasses.isEmpty());
                        jobClasses.add(jobClass);
                    } else {
                        newStatements.add(md);
                    }
                } else {
                    newStatements.add(stmt);
                }
            }

            // Add all Job classes AFTER all methods
            newStatements.addAll(jobClasses);

            // Create nested @Configuration class with @Bean methods
            J.ClassDeclaration quartzConfig = createQuartzConfigClass(
                enclosingClassName, methods, beanNamePrefix);
            newStatements.add(quartzConfig);

            // Add Quartz imports (Boot-specific imports removed - now in shared AutoConfiguration)
            maybeAddImport("org.quartz.*");
            maybeAddImport(SPRING_BEAN_FQN);
            maybeAddImport(SPRING_CONFIGURATION_FQN);

            // Remove EJB imports - but only Timer imports if no Timer usage remains
            doAfterVisit(new RemoveImport<>(SCHEDULE_FQN, true));
            doAfterVisit(new RemoveImport<>(SCHEDULES_FQN, true));
            // Note: Timer imports are conditionally removed in visitClassDeclaration
            // based on whether any fallback methods still use Timer

            return classDecl.withBody(classDecl.getBody().withStatements(newStatements));
        }

        /**
         * Transforms a @Schedule method:
         * - Removes @Schedule annotation
         * - Removes Timer parameter
         * - Replaces timer.getInfo() with timerInfo field or null
         */
        private J.MethodDeclaration transformScheduledMethod(J.MethodDeclaration method, TransformableMethod tm) {
            // Save the prefix from the first annotation (usually has proper spacing like \n    )
            Space firstAnnotationPrefix = null;
            if (!method.getLeadingAnnotations().isEmpty()) {
                firstAnnotationPrefix = method.getLeadingAnnotations().get(0).getPrefix();
            }

            // Remove @Schedule annotation
            List<J.Annotation> newAnnotations = new ArrayList<>();
            for (J.Annotation ann : method.getLeadingAnnotations()) {
                if (!isScheduleAnnotation(ann) && !isSchedulesAnnotation(ann)) {
                    newAnnotations.add(ann);
                }
            }
            method = method.withLeadingAnnotations(newAnnotations);

            // If all annotations were removed, transfer the first annotation's prefix to method modifiers
            if (newAnnotations.isEmpty() && firstAnnotationPrefix != null && !method.getModifiers().isEmpty()) {
                List<J.Modifier> modifiers = new ArrayList<>(method.getModifiers());
                J.Modifier firstMod = modifiers.get(0);
                modifiers.set(0, firstMod.withPrefix(firstAnnotationPrefix));
                method = method.withModifiers(modifiers);
            }

            // Remove Timer parameter
            if (tm.hasTimerParameter) {
                List<Statement> newParams = new ArrayList<>();
                for (Statement param : method.getParameters()) {
                    if (param instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) param;
                        String typeStr = "";
                        if (vd.getTypeExpression() != null) {
                            typeStr = vd.getTypeExpression().print(getCursor()).trim();
                        }
                        if (!"Timer".equals(typeStr) && !typeStr.endsWith(".Timer")) {
                            newParams.add(param);
                        }
                    } else if (!(param instanceof J.Empty)) {
                        newParams.add(param);
                    }
                }
                if (newParams.isEmpty()) {
                    // Empty params: use J.Empty
                    method = method.withParameters(Collections.singletonList(
                        new J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY)));
                } else {
                    method = method.withParameters(newParams);
                }

                // Replace timer.getInfo() calls in method body
                if (method.getBody() != null) {
                    method = method.withBody((J.Block) new TimerGetInfoReplacer(tm.timerInfo, tm.timerParameterName).visit(
                        method.getBody(), new InMemoryExecutionContext()));
                }
            }

            return method;
        }

        // ========== Phase 2: Job Class Generation ==========

        /**
         * Creates an inner static Job class that delegates to the original method.
         *
         * Example output:
         * public static class ScheduledTaskJob implements Job {
         *     private final QuartzScheduler delegate;
         *
         *     public ScheduledTaskJob(QuartzScheduler delegate) {
         *         this.delegate = delegate;
         *     }
         *
         *     @Override
         *     public final void execute(JobExecutionContext context) throws JobExecutionException {
         *         delegate.scheduledTask();
         *     }
         * }
         */
        private J.ClassDeclaration createDelegatingJobClass(
                String enclosingClassName,
                String methodName,
                String signatureSuffix,
                boolean isFirstMember) {

            // Include signature suffix to distinguish overloaded methods
            // e.g., doWork() -> DoWorkJob, doWork(String) -> DoWorkStringJob
            String jobClassName = capitalize(methodName) + signatureSuffix + "Job";

            // Create field: private final EnclosingClass delegate;
            J.VariableDeclarations delegateField = createDelegateField(enclosingClassName);

            // Create constructor: public JobClass(EnclosingClass delegate) { this.delegate = delegate; }
            J.MethodDeclaration constructor = createJobConstructor(jobClassName, enclosingClassName);

            // Create execute method: public final void execute(JobExecutionContext ctx) throws JobExecutionException { delegate.method(); }
            J.MethodDeclaration executeMethod = createExecuteMethod(methodName);

            // Build class body - use empty after-padding for statements, spacing handled by prefixes
            List<JRightPadded<Statement>> statements = Arrays.asList(
                new JRightPadded<>(delegateField, Space.EMPTY, Markers.EMPTY),
                new JRightPadded<>(constructor, Space.EMPTY, Markers.EMPTY),
                new JRightPadded<>(executeMethod, Space.EMPTY, Markers.EMPTY)
            );

            J.Block body = new J.Block(
                Tree.randomId(),
                Space.format(" "),  // space before opening {
                Markers.EMPTY,
                new JRightPadded<>(false, Space.EMPTY, Markers.EMPTY),
                statements,
                Space.format("\n    ")  // newline + indent before closing }
            );

            // Create "implements Job" clause
            JavaType.ShallowClass jobType = JavaType.ShallowClass.build(QUARTZ_JOB_FQN);
            J.Identifier jobIdent = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "Job",
                jobType,
                null
            );
            JContainer<TypeTree> implementsClause = JContainer.build(
                Space.format(" "),
                Collections.singletonList(new JRightPadded<>(jobIdent, Space.EMPTY, Markers.EMPTY)),
                Markers.EMPTY
            );

            // Create class modifiers: public static
            List<J.Modifier> modifiers = Arrays.asList(
                new J.Modifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, null, J.Modifier.Type.Public, Collections.emptyList()),
                new J.Modifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, null, J.Modifier.Type.Static, Collections.emptyList())
            );

            // Create class name identifier
            J.Identifier classNameIdent = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                jobClassName,
                null,
                null
            );

            Space classPrefix = isFirstMember ? Space.format("\n\n    ") : Space.format("\n\n    ");

            return new J.ClassDeclaration(
                Tree.randomId(),
                classPrefix,
                Markers.EMPTY,
                Collections.emptyList(),  // annotations
                modifiers,
                new J.ClassDeclaration.Kind(Tree.randomId(), Space.format(" "), Markers.EMPTY, Collections.emptyList(), J.ClassDeclaration.Kind.Type.Class),
                classNameIdent,
                null,  // type parameters
                null,  // primary constructor
                null,  // extends
                implementsClause,
                null,  // permits
                body,
                null   // type
            );
        }

        /**
         * Creates: private final EnclosingClass delegate;
         */
        private J.VariableDeclarations createDelegateField(String enclosingClassName) {
            List<J.Modifier> modifiers = Arrays.asList(
                new J.Modifier(Tree.randomId(), Space.format("\n        "), Markers.EMPTY, null, J.Modifier.Type.Private, Collections.emptyList()),
                new J.Modifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, null, J.Modifier.Type.Final, Collections.emptyList())
            );

            J.Identifier typeIdent = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                enclosingClassName,
                null,
                null
            );

            J.Identifier varName = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "delegate",
                null,
                null
            );

            J.VariableDeclarations.NamedVariable namedVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                varName,
                Collections.emptyList(),
                null,
                null
            );

            return new J.VariableDeclarations(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                modifiers,
                typeIdent,
                null,
                Collections.emptyList(),
                Collections.singletonList(new JRightPadded<>(namedVar, Space.EMPTY, Markers.EMPTY))
            );
        }

        /**
         * Creates: private final Object timerInfo = "value";
         * This field is added to the enclosing class when info attribute is present.
         */
        private J.VariableDeclarations createTimerInfoField(String infoValue) {
            List<J.Modifier> modifiers = Arrays.asList(
                new J.Modifier(Tree.randomId(), Space.format("\n\n    "), Markers.EMPTY, null, J.Modifier.Type.Private, Collections.emptyList()),
                new J.Modifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, null, J.Modifier.Type.Final, Collections.emptyList())
            );

            J.Identifier typeIdent = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "Object",
                JavaType.ShallowClass.build("java.lang.Object"),
                null
            );

            J.Identifier varName = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "timerInfo",
                null,
                null
            );

            // Escape value for source representation
            String escapedValue = infoValue.replace("\\", "\\\\").replace("\"", "\\\"");
            J.Literal initializer = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                infoValue,
                "\"" + escapedValue + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            J.VariableDeclarations.NamedVariable namedVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                varName,
                Collections.emptyList(),
                new JLeftPadded<>(Space.format(" "), initializer, Markers.EMPTY),
                null
            );

            return new J.VariableDeclarations(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                modifiers,
                typeIdent,
                null,
                Collections.emptyList(),
                Collections.singletonList(new JRightPadded<>(namedVar, Space.EMPTY, Markers.EMPTY))
            );
        }

        /**
         * Creates: public JobClass(EnclosingClass delegate) { this.delegate = delegate; }
         */
        private J.MethodDeclaration createJobConstructor(String jobClassName, String enclosingClassName) {
            // Modifier: public
            List<J.Modifier> modifiers = Collections.singletonList(
                new J.Modifier(Tree.randomId(), Space.format("\n\n        "), Markers.EMPTY, null, J.Modifier.Type.Public, Collections.emptyList())
            );

            // Parameter: EnclosingClass delegate
            J.Identifier paramType = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                enclosingClassName,
                null,
                null
            );

            J.Identifier paramName = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "delegate",
                null,
                null
            );

            J.VariableDeclarations.NamedVariable paramVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                paramName,
                Collections.emptyList(),
                null,
                null
            );

            J.VariableDeclarations param = new J.VariableDeclarations(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                Collections.emptyList(),
                paramType,
                null,
                Collections.emptyList(),
                Collections.singletonList(new JRightPadded<>(paramVar, Space.EMPTY, Markers.EMPTY))
            );

            // Body: this.delegate = delegate;
            J.Identifier thisIdent = new J.Identifier(
                Tree.randomId(),
                Space.format("\n            "),
                Markers.EMPTY,
                Collections.emptyList(),
                "this",
                null,
                null
            );

            J.Identifier delegateField = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "delegate",
                null,
                null
            );

            J.FieldAccess fieldAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                thisIdent,
                new JLeftPadded<>(Space.EMPTY, delegateField, Markers.EMPTY),
                null
            );

            J.Identifier delegateValue = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "delegate",
                null,
                null
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                fieldAccess,
                new JLeftPadded<>(Space.format(" "), delegateValue, Markers.EMPTY),
                null
            );

            J.Block body = new J.Block(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                new JRightPadded<>(false, Space.EMPTY, Markers.EMPTY),
                Collections.singletonList(new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY)),
                Space.format("\n        ")  // newline + indent before closing }
            );

            J.Identifier methodName = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                jobClassName,
                null,
                null
            );

            return new J.MethodDeclaration(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                modifiers,
                null,  // type params
                null,  // return type (constructor)
                new J.MethodDeclaration.IdentifierWithAnnotations(methodName, Collections.emptyList()),
                JContainer.build(Space.EMPTY, Collections.singletonList(new JRightPadded<>(param, Space.EMPTY, Markers.EMPTY)), Markers.EMPTY),
                null,  // throws
                body,
                null,  // default value
                null   // method type
            );
        }

        /**
         * Creates: @Override public final void execute(JobExecutionContext context) throws JobExecutionException { delegate.method(); }
         */
        private J.MethodDeclaration createExecuteMethod(String delegateMethodName) {
            // @Override annotation
            J.Identifier overrideIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Override",
                JavaType.ShallowClass.build("java.lang.Override"),
                null
            );
            J.Annotation overrideAnn = new J.Annotation(
                Tree.randomId(),
                Space.format("\n\n        "),
                Markers.EMPTY,
                overrideIdent,
                null
            );

            // Modifiers: public final
            List<J.Modifier> modifiers = Arrays.asList(
                new J.Modifier(Tree.randomId(), Space.format("\n        "), Markers.EMPTY, null, J.Modifier.Type.Public, Collections.emptyList()),
                new J.Modifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, null, J.Modifier.Type.Final, Collections.emptyList())
            );

            // Return type: void
            J.Primitive voidType = new J.Primitive(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                JavaType.Primitive.Void
            );

            // Method name: execute
            J.Identifier methodName = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "execute",
                null,
                null
            );

            // Parameter: JobExecutionContext context
            J.Identifier paramType = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "JobExecutionContext",
                JavaType.ShallowClass.build(QUARTZ_JOB_EXECUTION_CONTEXT_FQN),
                null
            );

            J.Identifier paramName = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "context",
                null,
                null
            );

            J.VariableDeclarations.NamedVariable paramVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                paramName,
                Collections.emptyList(),
                null,
                null
            );

            J.VariableDeclarations param = new J.VariableDeclarations(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                Collections.emptyList(),
                paramType,
                null,
                Collections.emptyList(),
                Collections.singletonList(new JRightPadded<>(paramVar, Space.EMPTY, Markers.EMPTY))
            );

            // Throws: JobExecutionException
            J.Identifier exceptionType = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "JobExecutionException",
                JavaType.ShallowClass.build(QUARTZ_JOB_EXECUTION_EXCEPTION_FQN),
                null
            );
            JContainer<NameTree> throwsClause = JContainer.build(
                Space.format(" "),
                Collections.singletonList(new JRightPadded<>(exceptionType, Space.EMPTY, Markers.EMPTY)),
                Markers.EMPTY
            );

            // Body: delegate.methodName();
            J.Identifier delegateIdent = new J.Identifier(
                Tree.randomId(),
                Space.format("\n            "),
                Markers.EMPTY,
                Collections.emptyList(),
                "delegate",
                null,
                null
            );

            J.Identifier methodIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                delegateMethodName,
                null,
                null
            );

            J.MethodInvocation invocation = new J.MethodInvocation(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                new JRightPadded<>(delegateIdent, Space.EMPTY, Markers.EMPTY),
                null,
                methodIdent,
                JContainer.empty(),
                null
            );

            J.Block body = new J.Block(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                new JRightPadded<>(false, Space.EMPTY, Markers.EMPTY),
                Collections.singletonList(new JRightPadded<>(invocation, Space.EMPTY, Markers.EMPTY)),
                Space.format("\n        ")  // newline + indent before closing }
            );

            return new J.MethodDeclaration(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.singletonList(overrideAnn),
                modifiers,
                null,
                voidType,
                new J.MethodDeclaration.IdentifierWithAnnotations(methodName, Collections.emptyList()),
                JContainer.build(Space.EMPTY, Collections.singletonList(new JRightPadded<>(param, Space.EMPTY, Markers.EMPTY)), Markers.EMPTY),
                throwsClause,
                body,
                null,
                null
            );
        }

        // ========== Phase 3-4: QuartzConfig and Bean Methods ==========

        /**
         * Creates the nested @Configuration class with @Bean methods for JobDetail and Trigger.
         */
        private J.ClassDeclaration createQuartzConfigClass(
                String enclosingClassName,
                List<TransformableMethod> methods,
                String beanNamePrefix) {

            List<JRightPadded<Statement>> beanMethods = new ArrayList<>();

            for (TransformableMethod tm : methods) {
                String methodName = tm.method.getSimpleName();
                String signatureSuffix = tm.signatureSuffix;
                // Include signature suffix to distinguish overloaded methods
                String jobClassName = capitalize(methodName) + signatureSuffix + "Job";
                String timerInfo = tm.timerInfo;

                // Create JobDetail bean - empty after-padding, spacing via prefixes
                // Pass signature suffix for unique bean names
                J.MethodDeclaration jobDetailBean = createJobDetailBeanMethod(
                    beanNamePrefix, methodName, signatureSuffix, jobClassName, timerInfo);
                beanMethods.add(new JRightPadded<>(jobDetailBean, Space.EMPTY, Markers.EMPTY));

                // Create Trigger bean(s) - one per ScheduleConfig
                // If multiple schedules, append _1, _2 suffix
                boolean multipleSchedules = tm.configs.size() > 1;
                int triggerIndex = 1;
                for (ScheduleConfig config : tm.configs) {
                    String triggerSuffix = multipleSchedules ? "_" + triggerIndex : "";
                    J.MethodDeclaration triggerBean = createTriggerBeanMethod(
                        beanNamePrefix, methodName, signatureSuffix, config, triggerSuffix);
                    beanMethods.add(new JRightPadded<>(triggerBean, Space.EMPTY, Markers.EMPTY));
                    triggerIndex++;
                }
            }
            // Note: JobFactory and SchedulerCustomizer beans are now in shared QuartzJobFactoryAutoConfiguration

            J.Block body = new J.Block(
                Tree.randomId(),
                Space.format(" "),  // space before opening {
                Markers.EMPTY,
                new JRightPadded<>(false, Space.EMPTY, Markers.EMPTY),
                beanMethods,
                Space.format("\n    ")  // newline + indent before closing }
            );

            // @Configuration annotation
            J.Identifier configIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Configuration",
                JavaType.ShallowClass.build(SPRING_CONFIGURATION_FQN),
                null
            );
            J.Annotation configAnn = new J.Annotation(
                Tree.randomId(),
                Space.format("\n\n    "),
                Markers.EMPTY,
                configIdent,
                null
            );

            // Modifiers: public static
            List<J.Modifier> modifiers = Arrays.asList(
                new J.Modifier(Tree.randomId(), Space.format("\n    "), Markers.EMPTY, null, J.Modifier.Type.Public, Collections.emptyList()),
                new J.Modifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, null, J.Modifier.Type.Static, Collections.emptyList())
            );

            J.Identifier className = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "QuartzConfig",
                null,
                null
            );

            return new J.ClassDeclaration(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.singletonList(configAnn),
                modifiers,
                new J.ClassDeclaration.Kind(Tree.randomId(), Space.format(" "), Markers.EMPTY, Collections.emptyList(), J.ClassDeclaration.Kind.Type.Class),
                className,
                null,
                null,
                null,
                null,
                null,
                body,
                null
            );
        }

        /**
         * Creates @Bean method for JobDetail.
         */
        private J.MethodDeclaration createJobDetailBeanMethod(
                String beanNamePrefix,
                String methodName,
                String signatureSuffix,
                String jobClassName,
                String timerInfo) {

            // Include signature suffix to distinguish overloaded methods
            String beanMethodName = beanNamePrefix + capitalize(methodName) + signatureSuffix + "JobDetail";

            // @Bean annotation
            J.Identifier beanIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Bean",
                JavaType.ShallowClass.build(SPRING_BEAN_FQN),
                null
            );
            J.Annotation beanAnn = new J.Annotation(
                Tree.randomId(),
                Space.format("\n\n        "),
                Markers.EMPTY,
                beanIdent,
                null
            );

            // Modifier: public
            List<J.Modifier> modifiers = Collections.singletonList(
                new J.Modifier(Tree.randomId(), Space.format("\n        "), Markers.EMPTY, null, J.Modifier.Type.Public, Collections.emptyList())
            );

            // Return type: JobDetail
            J.Identifier returnType = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "JobDetail",
                JavaType.ShallowClass.build(QUARTZ_JOB_DETAIL_FQN),
                null
            );

            // Method name
            J.Identifier methodNameIdent = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                beanMethodName,
                null,
                null
            );

            // Create return statement - includes .usingJobData() when timerInfo is present
            J.Return returnStmt = createJobDetailReturnStatement(jobClassName, beanMethodName, timerInfo);

            J.Block body = new J.Block(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                new JRightPadded<>(false, Space.EMPTY, Markers.EMPTY),
                Collections.singletonList(new JRightPadded<>(returnStmt, Space.EMPTY, Markers.EMPTY)),
                Space.format("\n    ")  // newline + indent before closing }
            );

            return new J.MethodDeclaration(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.singletonList(beanAnn),
                modifiers,
                null,
                returnType,
                new J.MethodDeclaration.IdentifierWithAnnotations(methodNameIdent, Collections.emptyList()),
                JContainer.build(Space.EMPTY, Collections.emptyList(), Markers.EMPTY),
                null,
                body,
                null,
                null
            );
        }

        /**
         * Creates the return statement for JobDetail bean method.
         * Output format:
         * return
         *     JobBuilder.newJob(ScheduledTaskJob.class).
         *         withIdentity("...").
         *         storeDurably().
         *         [usingJobData("info", "...").] // when timerInfo present
         *         build();
         */
        private J.Return createJobDetailReturnStatement(String jobClassName, String identity, String timerInfo) {
            // JobBuilder identifier
            J.Identifier jobBuilder = new J.Identifier(
                Tree.randomId(), Space.format("\n            "), Markers.EMPTY,
                Collections.emptyList(), "JobBuilder",
                JavaType.ShallowClass.build(QUARTZ_JOB_BUILDER_FQN), null
            );

            // JobClass.class argument
            J.FieldAccess classAccess = new J.FieldAccess(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), jobClassName, null, null),
                new JLeftPadded<>(Space.EMPTY,
                    new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "class", null, null),
                    Markers.EMPTY),
                null
            );

            // JobBuilder.newJob(JobClass.class)
            J.MethodInvocation newJobCall = new J.MethodInvocation(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new JRightPadded<>(jobBuilder, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "newJob", null, null),
                JContainer.build(Space.EMPTY, Collections.singletonList(
                    new JRightPadded<>(classAccess, Space.EMPTY, Markers.EMPTY)), Markers.EMPTY),
                null
            );

            // .withIdentity("...") - name has prefix with newline for dot-at-end style
            J.MethodInvocation withIdentityCall = new J.MethodInvocation(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new JRightPadded<>(newJobCall, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.format("\n                "), Markers.EMPTY, Collections.emptyList(), "withIdentity", null, null),
                JContainer.build(Space.EMPTY, Collections.singletonList(
                    new JRightPadded<>(new J.Literal(Tree.randomId(), Space.EMPTY, Markers.EMPTY, identity,
                        "\"" + identity + "\"", Collections.emptyList(), JavaType.Primitive.String),
                        Space.EMPTY, Markers.EMPTY)), Markers.EMPTY),
                null
            );

            // .storeDurably()
            J.MethodInvocation storeDurablyCall = new J.MethodInvocation(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new JRightPadded<>(withIdentityCall, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.format("\n                "), Markers.EMPTY, Collections.emptyList(), "storeDurably", null, null),
                JContainer.empty(),
                null
            );

            // Chain before .build() - either storeDurablyCall or usingJobDataCall
            Expression chainBeforeBuild = storeDurablyCall;

            // .usingJobData("info", "...") when timerInfo is present
            if (timerInfo != null && !timerInfo.isEmpty()) {
                // Escape timerInfo for source representation
                String escapedInfo = timerInfo.replace("\\", "\\\\").replace("\"", "\\\"");
                J.MethodInvocation usingJobDataCall = new J.MethodInvocation(
                    Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                    new JRightPadded<>(storeDurablyCall, Space.EMPTY, Markers.EMPTY),
                    null,
                    new J.Identifier(Tree.randomId(), Space.format("\n                "), Markers.EMPTY, Collections.emptyList(), "usingJobData", null, null),
                    JContainer.build(Space.EMPTY, Arrays.asList(
                        new JRightPadded<>(new J.Literal(Tree.randomId(), Space.EMPTY, Markers.EMPTY, "info",
                            "\"info\"", Collections.emptyList(), JavaType.Primitive.String), Space.EMPTY, Markers.EMPTY),
                        new JRightPadded<>(new J.Literal(Tree.randomId(), Space.format(" "), Markers.EMPTY, timerInfo,
                            "\"" + escapedInfo + "\"", Collections.emptyList(), JavaType.Primitive.String), Space.EMPTY, Markers.EMPTY)
                    ), Markers.EMPTY),
                    null
                );
                chainBeforeBuild = usingJobDataCall;
            }

            // .build()
            J.MethodInvocation buildCall = new J.MethodInvocation(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new JRightPadded<>(chainBeforeBuild, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.format("\n                "), Markers.EMPTY, Collections.emptyList(), "build", null, null),
                JContainer.empty(),
                null
            );

            return new J.Return(
                Tree.randomId(),
                Space.format("\n        "),
                Markers.EMPTY,
                buildCall
            );
        }

        /**
         * Creates @Bean method for Trigger.
         * @param triggerSuffix Suffix for multiple triggers (e.g., "_1", "_2") or empty string for single trigger
         */
        private J.MethodDeclaration createTriggerBeanMethod(
                String beanNamePrefix,
                String methodName,
                String signatureSuffix,
                ScheduleConfig config,
                String triggerSuffix) {

            // Include signature suffix to distinguish overloaded methods
            String jobDetailBeanName = beanNamePrefix + capitalize(methodName) + signatureSuffix + "JobDetail";
            String triggerBeanName = beanNamePrefix + capitalize(methodName) + signatureSuffix + "Trigger" + triggerSuffix;

            // @Bean annotation
            J.Identifier beanIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Bean",
                JavaType.ShallowClass.build(SPRING_BEAN_FQN),
                null
            );
            J.Annotation beanAnn = new J.Annotation(
                Tree.randomId(),
                Space.format("\n\n        "),
                Markers.EMPTY,
                beanIdent,
                null
            );

            // Modifier: public
            List<J.Modifier> modifiers = Collections.singletonList(
                new J.Modifier(Tree.randomId(), Space.format("\n        "), Markers.EMPTY, null, J.Modifier.Type.Public, Collections.emptyList())
            );

            // Return type: Trigger
            J.Identifier returnType = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "Trigger",
                JavaType.ShallowClass.build(QUARTZ_TRIGGER_FQN),
                null
            );

            // Method name
            J.Identifier methodNameIdent = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                triggerBeanName,
                null,
                null
            );

            // Parameter: JobDetail jobDetailBeanName
            J.Identifier paramType = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "JobDetail",
                JavaType.ShallowClass.build(QUARTZ_JOB_DETAIL_FQN),
                null
            );

            J.Identifier paramName = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                jobDetailBeanName,
                null,
                null
            );

            J.VariableDeclarations.NamedVariable paramVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                paramName,
                Collections.emptyList(),
                null,
                null
            );

            J.VariableDeclarations param = new J.VariableDeclarations(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                Collections.emptyList(),
                paramType,
                null,
                Collections.emptyList(),
                Collections.singletonList(new JRightPadded<>(paramVar, Space.EMPTY, Markers.EMPTY))
            );

            // Body
            String cronExpression = buildCronExpression(config);
            J.Return returnStmt = createTriggerReturnStatement(jobDetailBeanName, triggerBeanName, cronExpression);

            J.Block body = new J.Block(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                new JRightPadded<>(false, Space.EMPTY, Markers.EMPTY),
                Collections.singletonList(new JRightPadded<>(returnStmt, Space.EMPTY, Markers.EMPTY)),
                Space.format("\n    ")  // newline + indent before closing }
            );

            return new J.MethodDeclaration(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.singletonList(beanAnn),
                modifiers,
                null,
                returnType,
                new J.MethodDeclaration.IdentifierWithAnnotations(methodNameIdent, Collections.emptyList()),
                JContainer.build(Space.EMPTY, Collections.singletonList(new JRightPadded<>(param, Space.EMPTY, Markers.EMPTY)), Markers.EMPTY),
                null,
                body,
                null,
                null
            );
        }

        /**
         * Creates the return statement for Trigger bean method.
         */
        private J.Return createTriggerReturnStatement(String jobDetailBeanName, String triggerIdentity, String cronExpression) {
            // TriggerBuilder identifier
            J.Identifier triggerBuilder = new J.Identifier(
                Tree.randomId(), Space.format("\n            "), Markers.EMPTY,
                Collections.emptyList(), "TriggerBuilder",
                JavaType.ShallowClass.build(QUARTZ_TRIGGER_BUILDER_FQN), null
            );

            // TriggerBuilder.newTrigger()
            J.MethodInvocation newTriggerCall = new J.MethodInvocation(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new JRightPadded<>(triggerBuilder, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "newTrigger", null, null),
                JContainer.empty(),
                null
            );

            // .forJob(jobDetail) - name has prefix with newline
            J.MethodInvocation forJobCall = new J.MethodInvocation(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new JRightPadded<>(newTriggerCall, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.format("\n                "), Markers.EMPTY, Collections.emptyList(), "forJob", null, null),
                JContainer.build(Space.EMPTY, Collections.singletonList(
                    new JRightPadded<>(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        Collections.emptyList(), jobDetailBeanName, null, null), Space.EMPTY, Markers.EMPTY)),
                    Markers.EMPTY),
                null
            );

            // .withIdentity("...")
            J.MethodInvocation withIdentityCall = new J.MethodInvocation(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new JRightPadded<>(forJobCall, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.format("\n                "), Markers.EMPTY, Collections.emptyList(), "withIdentity", null, null),
                JContainer.build(Space.EMPTY, Collections.singletonList(
                    new JRightPadded<>(new J.Literal(Tree.randomId(), Space.EMPTY, Markers.EMPTY, triggerIdentity,
                        "\"" + triggerIdentity + "\"", Collections.emptyList(), JavaType.Primitive.String),
                        Space.EMPTY, Markers.EMPTY)), Markers.EMPTY),
                null
            );

            // CronScheduleBuilder.cronSchedule("...")
            J.MethodInvocation cronScheduleCall = new J.MethodInvocation(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new JRightPadded<>(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                    Collections.emptyList(), "CronScheduleBuilder",
                    JavaType.ShallowClass.build(QUARTZ_CRON_SCHEDULE_BUILDER_FQN), null), Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "cronSchedule", null, null),
                JContainer.build(Space.EMPTY, Collections.singletonList(
                    new JRightPadded<>(new J.Literal(Tree.randomId(), Space.EMPTY, Markers.EMPTY, cronExpression,
                        "\"" + cronExpression + "\"", Collections.emptyList(), JavaType.Primitive.String),
                        Space.EMPTY, Markers.EMPTY)
                ), Markers.EMPTY),
                null
            );

            // .withSchedule(...) - name has prefix with newline
            J.MethodInvocation withScheduleCall = new J.MethodInvocation(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                new JRightPadded<>(withIdentityCall, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.format("\n                "), Markers.EMPTY, Collections.emptyList(), "withSchedule", null, null),
                JContainer.build(Space.EMPTY, Collections.singletonList(
                    new JRightPadded<>(cronScheduleCall, Space.EMPTY, Markers.EMPTY)
                ), Markers.EMPTY),
                null
            );

            // .build() - name has prefix with newline
            J.MethodInvocation buildCall = new J.MethodInvocation(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                new JRightPadded<>(withScheduleCall, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.format("\n                "), Markers.EMPTY, Collections.emptyList(), "build", null, null),
                JContainer.empty(),
                null
            );

            return new J.Return(
                Tree.randomId(),
                Space.format("\n        "),
                Markers.EMPTY,
                buildCall
            );
        }

        /**
         * Builds Quartz cron expression from EJB @Schedule config.
         * Format: second minute hour dayOfMonth month dayOfWeek
         */
        private String buildCronExpression(ScheduleConfig config) {
            return String.format("%s %s %s %s %s ?",
                config.getSecond() != null ? config.getSecond() : "0",
                config.getMinute() != null ? config.getMinute() : "0",
                config.getHour() != null ? config.getHour() : "*",
                config.getDayOfMonth() != null ? config.getDayOfMonth() : "*",
                config.getMonth() != null ? config.getMonth() : "*"
            );
        }
        // JobFactory and SchedulerCustomizer beans are now generated in shared QuartzJobFactoryAutoConfiguration

        // JobFactory Wiring Methods removed - now in shared QuartzJobFactoryAutoConfiguration
        // Generated via ScanningRecipe.generate() with META-INF/spring/AutoConfiguration.imports
        // See generateAutoConfigurationClass() method


        // ========== Timer.getInfo() Replacement ==========

        /**
         * Visitor that replaces timer.getInfo() calls with the info value or null.
         * Uses JavaVisitor (not IsoVisitor) to allow returning different J types.
         */
        private static class TimerGetInfoReplacer extends JavaVisitor<ExecutionContext> {
            private final String infoValue;
            private final String timerParamName;

            TimerGetInfoReplacer(String infoValue, String timerParamName) {
                this.infoValue = infoValue;
                this.timerParamName = timerParamName;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation mi = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);

                // Check for .getInfo() call with no arguments
                if ("getInfo".equals(mi.getSimpleName())) {
                    // Check if arguments are empty (no-arg method call)
                    List<Expression> args = mi.getArguments();
                    boolean noArgs = args.isEmpty() || (args.size() == 1 && args.get(0) instanceof J.Empty);

                    if (noArgs) {
                        Expression select = mi.getSelect();
                        if (select != null) {
                            // Check if select is Timer type by variable name
                            String selectName = "";
                            if (select instanceof J.Identifier) {
                                selectName = ((J.Identifier) select).getSimpleName();
                            }
                            // Use actual Timer parameter name instead of hardcoded "timer"
                            if (timerParamName != null && timerParamName.equals(selectName)) {
                                // Replace with this.timerInfo or null
                                if (infoValue != null && !infoValue.isEmpty()) {
                                    // Create this.timerInfo field access
                                    J.FieldAccess thisTimerInfo = new J.FieldAccess(
                                        Tree.randomId(),
                                        mi.getPrefix(),
                                        Markers.EMPTY,
                                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "this", null, null),
                                        new JLeftPadded<>(Space.EMPTY,
                                            new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "timerInfo", null, null),
                                            Markers.EMPTY),
                                        null
                                    );
                                    return thisTimerInfo;
                                } else {
                                    // Replace with null literal
                                    return new J.Literal(
                                        Tree.randomId(),
                                        mi.getPrefix(),
                                        Markers.EMPTY,
                                        null,
                                        "null",
                                        Collections.emptyList(),
                                        JavaType.Primitive.Null
                                    );
                                }
                            }
                        }
                    }
                }

                return mi;
            }
        }

        /**
         * Applies fallback transformation: replaces @Schedule with @EjbQuartzSchedule marker.
         */
        private J.ClassDeclaration applyFallbackTransformation(
                J.ClassDeclaration classDecl,
                List<J.MethodDeclaration> methods,
                ExecutionContext ctx) {

            List<Statement> newStatements = new ArrayList<>();

            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    // Capture method ID in final variable for lambda (md gets reassigned later)
                    final UUID methodId = md.getId();
                    // Match by UUID for correct overload handling
                    if (methods.stream().anyMatch(m -> m.getId().equals(methodId))) {
                        md = transformToMarkerAnnotation(md);
                    }
                    newStatements.add(md);
                } else {
                    newStatements.add(stmt);
                }
            }

            return classDecl.withBody(classDecl.getBody().withStatements(newStatements));
        }

        /**
         * Transforms a method's @Schedule to @EjbQuartzSchedule marker.
         * Fallback cases ALWAYS use rawExpression to preserve original annotation.
         */
        private J.MethodDeclaration transformToMarkerAnnotation(J.MethodDeclaration method) {
            List<J.Annotation> newAnnotations = new ArrayList<>();

            for (J.Annotation ann : method.getLeadingAnnotations()) {
                if (isScheduleAnnotation(ann)) {
                    // Always use rawExpression for fallback cases
                    String rawExpr = ann.print(getCursor());
                    newAnnotations.add(createEjbQuartzScheduleMarkerWithRaw(rawExpr, ann.getPrefix()));
                    maybeAddImport(EJB_QUARTZ_SCHEDULE_MARKER_FQN);
                    doAfterVisit(new RemoveImport<>(SCHEDULE_FQN, true));
                } else if (isSchedulesAnnotation(ann)) {
                    // Always use rawExpression for @Schedules fallback
                    String rawExpr = ann.print(getCursor());
                    newAnnotations.add(createEjbQuartzScheduleMarkerWithRaw(rawExpr, ann.getPrefix()));
                    maybeAddImport(EJB_QUARTZ_SCHEDULE_MARKER_FQN);
                    doAfterVisit(new RemoveImport<>(SCHEDULE_FQN, true));
                    doAfterVisit(new RemoveImport<>(SCHEDULES_FQN, true));
                } else {
                    newAnnotations.add(ann);
                }
            }

            return method.withLeadingAnnotations(newAnnotations);
        }

        // ========== Marker Annotation Creation ==========

        private J.Annotation createEjbQuartzScheduleMarker(ScheduleConfig config, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(EJB_QUARTZ_SCHEDULE_MARKER_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "EjbQuartzSchedule",
                type,
                null
            );

            List<JRightPadded<Expression>> args = new ArrayList<>();

            if (!"0".equals(config.getSecond())) {
                args.add(createStringArg("second", config.getSecond(), args.isEmpty()));
            }
            if (!"0".equals(config.getMinute())) {
                args.add(createStringArg("minute", config.getMinute(), args.isEmpty()));
            }
            if (!"0".equals(config.getHour())) {
                args.add(createStringArg("hour", config.getHour(), args.isEmpty()));
            }
            if (!"*".equals(config.getDayOfMonth())) {
                args.add(createStringArg("dayOfMonth", config.getDayOfMonth(), args.isEmpty()));
            }
            if (!"*".equals(config.getMonth())) {
                args.add(createStringArg("month", config.getMonth(), args.isEmpty()));
            }
            if (!"*".equals(config.getDayOfWeek())) {
                args.add(createStringArg("dayOfWeek", config.getDayOfWeek(), args.isEmpty()));
            }
            if (!"*".equals(config.getYear())) {
                args.add(createStringArg("year", config.getYear(), args.isEmpty()));
            }

            if (config.getTimezone() != null && !config.getTimezone().isEmpty()) {
                args.add(createStringArg("timezone", config.getTimezone(), args.isEmpty()));
            }

            if (config.getInfo() != null && !config.getInfo().isEmpty()) {
                args.add(createStringArg("info", config.getInfo(), args.isEmpty()));
            }

            if (Boolean.FALSE.equals(config.getPersistent())) {
                args.add(createBooleanArg("persistent", false, args.isEmpty()));
            }

            JContainer<Expression> argsContainer = args.isEmpty() ? null :
                JContainer.build(Space.EMPTY, args, Markers.EMPTY);

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                argsContainer
            );
        }

        private J.Annotation createEjbQuartzScheduleMarkerWithRaw(String rawExpression, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(EJB_QUARTZ_SCHEDULE_MARKER_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "EjbQuartzSchedule",
                type,
                null
            );

            List<JRightPadded<Expression>> args = new ArrayList<>();
            args.add(createRawExpressionArg(rawExpression));

            JContainer<Expression> argsContainer = JContainer.build(Space.EMPTY, args, Markers.EMPTY);

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                argsContainer
            );
        }

        private JRightPadded<Expression> createRawExpressionArg(String rawValue) {
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "rawExpression",
                null,
                null
            );

            String escapedForSource = rawValue
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                rawValue,
                "\"" + escapedForSource + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }

        private JRightPadded<Expression> createStringArg(String key, String value, boolean isFirst) {
            Space keySpace = isFirst ? Space.EMPTY : Space.format(" ");
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                keySpace,
                Markers.EMPTY,
                Collections.emptyList(),
                key,
                null,
                null
            );

            String escapedForSource = value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                value,
                "\"" + escapedForSource + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }

        private JRightPadded<Expression> createBooleanArg(String key, boolean value, boolean isFirst) {
            Space keySpace = isFirst ? Space.EMPTY : Space.format(" ");
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                keySpace,
                Markers.EMPTY,
                Collections.emptyList(),
                key,
                null,
                null
            );

            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                value,
                String.valueOf(value),
                Collections.emptyList(),
                JavaType.Primitive.Boolean
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }

        // ========== Utility Methods ==========

        private boolean hasTimerParameter(J.MethodDeclaration method) {
            return getTimerParameterName(method) != null;
        }

        /**
         * Gets the actual name of the Timer parameter in a method, if present.
         * Returns null if no Timer parameter exists.
         * Uses type attribution when available, falls back to import-based and AST-based resolution.
         */
        private String getTimerParameterName(J.MethodDeclaration method) {
            if (method.getParameters().isEmpty()) {
                return null;
            }
            // Get CompilationUnit for import checking
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            boolean hasTimerImport = cu != null && hasTimerImport(cu);

            for (Statement param : method.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    boolean isTimer = false;
                    // Primary: check by JavaType when type attribution is available
                    if (vd.getType() != null && vd.getType() instanceof JavaType.FullyQualified) {
                        String fqn = ((JavaType.FullyQualified) vd.getType()).getFullyQualifiedName();
                        if (EJB_TIMER_FQN.equals(fqn) || JAKARTA_TIMER_FQN.equals(fqn)) {
                            isTimer = true;
                        }
                    }
                    // Fallback: check type expression with multiple resolution strategies
                    if (!isTimer && vd.getTypeExpression() != null) {
                        TypeTree typeExpr = vd.getTypeExpression();
                        String typeStr = typeExpr.print(getCursor()).trim();

                        // Check for fully qualified names
                        if (EJB_TIMER_FQN.equals(typeStr) || JAKARTA_TIMER_FQN.equals(typeStr)) {
                            isTimer = true;
                        }
                        // Check for FieldAccess ending in .Timer (handles static/qualified references)
                        if (!isTimer && typeExpr instanceof J.FieldAccess) {
                            J.FieldAccess fa = (J.FieldAccess) typeExpr;
                            if ("Timer".equals(fa.getSimpleName())) {
                                isTimer = true;
                            }
                        }
                        // Check type string ending with .Timer (catches printed qualified refs)
                        if (!isTimer && typeStr.endsWith(".Timer")) {
                            isTimer = true;
                        }
                        // Check simple name "Timer" with import verification
                        if (!isTimer && "Timer".equals(typeStr) && hasTimerImport) {
                            isTimer = true;
                        }
                    }
                    if (isTimer && !vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
            }
            return null;
        }

        /**
         * Checks if the CompilationUnit has an import for EJB Timer.
         */
        private boolean hasTimerImport(J.CompilationUnit cu) {
            if (cu.getImports() == null) {
                return false;
            }
            for (J.Import imp : cu.getImports()) {
                String importStr = imp.getQualid().print(getCursor()).trim();
                if (EJB_TIMER_FQN.equals(importStr) || JAKARTA_TIMER_FQN.equals(importStr) ||
                        "javax.ejb.*".equals(importStr) || "jakarta.ejb.*".equals(importStr)) {
                    return true;
                }
            }
            return false;
        }

        private String capitalize(String s) {
            if (s == null || s.isEmpty()) {
                return s;
            }
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        private String decapitalize(String s) {
            if (s == null || s.isEmpty()) {
                return s;
            }
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
    }
}
