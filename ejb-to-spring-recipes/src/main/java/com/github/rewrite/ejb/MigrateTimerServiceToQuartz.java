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
import java.util.HashSet;
import java.util.regex.Pattern;
import org.openrewrite.InMemoryExecutionContext;

/**
 * Migrates EJB programmatic timers (TimerService) to Quartz Scheduler.
 * <p>
 * This recipe performs automatic transformation for simple programmatic timer cases:
 * <ul>
 *   <li>Replaces TimerService with Scheduler injection</li>
 *   <li>Creates inner Job class implementing org.quartz.Job</li>
 *   <li>Converts createTimer/createIntervalTimer to scheduleJob()</li>
 *   <li>Replaces Timer.getInfo() with JobDataMap access</li>
 *   <li>Replaces @Timeout with regular method call from Job</li>
 * </ul>
 * <p>
 * Falls back to @EjbQuartzTimerService marker annotation for complex cases:
 * <ul>
 *   <li>Multiple @Timeout methods</li>
 *   <li>getTimers() calls (dynamic timer management)</li>
 *   <li>Timer.cancel() in non-trivial patterns</li>
 *   <li>createCalendarTimer (complex calendar expressions)</li>
 * </ul>
 * <p>
 * JobFactory is generated with @ConditionalOnMissingBean to enable constructor injection.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateTimerServiceToQuartz extends ScanningRecipe<MigrateTimerServiceToQuartz.SourceRootAccumulator> {

    // ========== FQN Constants ==========

    // EJB Types
    private static final String JAKARTA_TIMER_SERVICE = "jakarta.ejb.TimerService";
    private static final String JAVAX_TIMER_SERVICE = "javax.ejb.TimerService";
    private static final String JAKARTA_TIMEOUT = "jakarta.ejb.Timeout";
    private static final String JAVAX_TIMEOUT = "javax.ejb.Timeout";
    private static final String JAKARTA_TIMER = "jakarta.ejb.Timer";
    private static final String JAVAX_TIMER = "javax.ejb.Timer";
    private static final String JAKARTA_RESOURCE = "jakarta.annotation.Resource";
    private static final String JAVAX_RESOURCE = "javax.annotation.Resource";
    private static final String JAKARTA_TIMER_CONFIG = "jakarta.ejb.TimerConfig";
    private static final String JAVAX_TIMER_CONFIG = "javax.ejb.TimerConfig";
    private static final String JAKARTA_TIMER_HANDLE = "jakarta.ejb.TimerHandle";
    private static final String JAVAX_TIMER_HANDLE = "javax.ejb.TimerHandle";

    // Quartz Types
    private static final String QUARTZ_SCHEDULER_FQN = "org.quartz.Scheduler";
    private static final String QUARTZ_SCHEDULER_EXCEPTION_FQN = "org.quartz.SchedulerException";
    private static final String QUARTZ_JOB_FQN = "org.quartz.Job";
    private static final String QUARTZ_JOB_DETAIL_FQN = "org.quartz.JobDetail";
    private static final String QUARTZ_JOB_BUILDER_FQN = "org.quartz.JobBuilder";
    private static final String QUARTZ_TRIGGER_FQN = "org.quartz.Trigger";
    private static final String QUARTZ_TRIGGER_BUILDER_FQN = "org.quartz.TriggerBuilder";
    private static final String QUARTZ_SIMPLE_SCHEDULE_BUILDER_FQN = "org.quartz.SimpleScheduleBuilder";
    private static final String QUARTZ_CRON_SCHEDULE_BUILDER_FQN = "org.quartz.CronScheduleBuilder";
    private static final String QUARTZ_JOB_EXECUTION_CONTEXT_FQN = "org.quartz.JobExecutionContext";

    // Spring Types
    private static final String SPRING_AUTOWIRED_FQN = "org.springframework.beans.factory.annotation.Autowired";
    private static final String SPRING_COMPONENT_FQN = "org.springframework.stereotype.Component";

    // Migration helper classes
    private static final String MIGRATED_TIMER_HANDLE_FQN = "com.github.migration.timer.MigratedTimerHandle";
    private static final String MIGRATED_SCHEDULE_INFO_FQN = "com.github.migration.timer.MigratedScheduleInfo";

    // Marker annotation for fallback cases
    private static final String EJB_QUARTZ_TIMER_SERVICE_FQN = "com.github.migration.annotations.EjbQuartzTimerService";

    // WFQ-009: NeedsReview annotation for fallback cases
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final String NEEDS_REVIEW_STUB =
        "package com.github.rewrite.ejb.annotations;\n" +
        "import java.lang.annotation.*;\n" +
        "@Retention(RetentionPolicy.RUNTIME)\n" +
        "@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})\n" +
        "public @interface NeedsReview {\n" +
        "    String reason() default \"\";\n" +
        "    Category category();\n" +
        "    String originalCode() default \"\";\n" +
        "    String suggestedAction() default \"\";\n" +
        "    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }\n" +
        "}";
    private static final String EJB_QUARTZ_TIMER_SERVICE_STUB =
        "package com.github.migration.annotations;\n" +
        "\n" +
        "import java.lang.annotation.*;\n" +
        "\n" +
        "@Target({ElementType.TYPE})\n" +
        "@Retention(RetentionPolicy.SOURCE)\n" +
        "public @interface EjbQuartzTimerService {\n" +
        "    String timerPattern() default \"\";\n" +
        "    boolean usesTimerInfo() default false;\n" +
        "    boolean dynamicTimerCreation() default false;\n" +
        "    int timeoutMethodCount() default 0;\n" +
        "    boolean usesTimerHandle() default false;\n" +
        "    boolean timerHandleEscapes() default false;\n" +
        "    boolean usesTimerHandleParamInTimeout() default false;\n" +
        "    boolean usesTimerGetSchedule() default false;\n" +
        "    boolean hasSingleTimer() default false;\n" +
        "    boolean hasIntervalTimer() default false;\n" +
        "    boolean hasCalendarTimer() default false;\n" +
        "    String migrationNotes() default \"\";\n" +
        "}\n";

    // Auto-configuration class
    private static final String AUTO_CONFIG_FQN = "com.github.migration.config.QuartzJobFactoryAutoConfiguration";
    private static final String ACTUAL_TRANSFORMATION_KEY = "MigrateTimerServiceToQuartz.actualTransformation.";

    // ========== Shared Static Helpers ==========
    // These helpers are used by both transform and analysis visitors

    /**
     * Extracts simple type name from TypeTree without cursor-based print.
     */
    private static String extractSimpleTypeName(TypeTree typeTree) {
        if (typeTree instanceof J.Identifier) {
            return ((J.Identifier) typeTree).getSimpleName();
        }
        if (typeTree instanceof J.FieldAccess) {
            return ((J.FieldAccess) typeTree).getSimpleName();
        }
        return "";
    }

    /**
     * Extracts fully qualified name from FieldAccess without cursor-based print.
     */
    private static String extractFullyQualifiedName(J.FieldAccess fa) {
        StringBuilder sb = new StringBuilder();
        Expression target = fa.getTarget();
        while (target instanceof J.FieldAccess) {
            J.FieldAccess inner = (J.FieldAccess) target;
            sb.insert(0, "." + inner.getSimpleName());
            target = inner.getTarget();
        }
        if (target instanceof J.Identifier) {
            sb.insert(0, ((J.Identifier) target).getSimpleName());
        }
        sb.append(".").append(fa.getSimpleName());
        return sb.toString();
    }

    /**
     * Extracts FQN from import qualid without cursor-based print.
     */
    private static String extractFqnFromQualid(J qualid) {
        if (qualid instanceof J.FieldAccess) {
            J.FieldAccess fa = (J.FieldAccess) qualid;
            String target = extractFqnFromQualid(fa.getTarget());
            return target + "." + fa.getSimpleName();
        }
        if (qualid instanceof J.Identifier) {
            return ((J.Identifier) qualid).getSimpleName();
        }
        return "";
    }

    @Override
    public String getDisplayName() {
        return "Migrate TimerService to Quartz Scheduler";
    }

    @Override
    public String getDescription() {
        return "Converts EJB TimerService/@Timeout to Quartz Scheduler pattern. " +
               "Creates inner Job class (delegate pattern), replaces TimerService with Scheduler, " +
               "converts createTimer to scheduleJob. Falls back to @EjbQuartzTimerService marker for complex cases.";
    }

    // ========== ScanningRecipe Infrastructure ==========

    static class SourceRootAccumulator {
        final Map<String, SourceRootInfo> sourceRoots = new ConcurrentHashMap<>();
        final Map<String, String> pendingImports = new ConcurrentHashMap<>();
        final Map<String, String> pendingApplicationProperties = new ConcurrentHashMap<>();

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
            String expectedResources = normalizePath(deriveResourcesRootStatic(sourceRoot));
            if (expectedResources != null) {
                // Match pending AutoConfiguration.imports
                String matchedKey = null;
                for (Map.Entry<String, String> entry : pendingImports.entrySet()) {
                    String resourcesRoot = normalizePath(entry.getKey());
                    if (resourcesRoot != null && resourcesRoot.equals(expectedResources)) {
                        info.existingImportsContent = entry.getValue();
                        matchedKey = entry.getKey();
                        break;
                    }
                }
                if (matchedKey != null) {
                    pendingImports.remove(matchedKey);
                }
                // Match pending application.properties
                matchedKey = null;
                for (Map.Entry<String, String> entry : pendingApplicationProperties.entrySet()) {
                    String resourcesRoot = normalizePath(entry.getKey());
                    if (resourcesRoot != null && resourcesRoot.equals(expectedResources)) {
                        info.existingApplicationPropertiesContent = entry.getValue();
                        matchedKey = entry.getKey();
                        break;
                    }
                }
                if (matchedKey != null) {
                    pendingApplicationProperties.remove(matchedKey);
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
            for (SourceRootInfo info : sourceRoots.values()) {
                String expectedResources = normalizePath(deriveResourcesRootStatic(info.sourceRoot));
                if (expectedResources != null && normalizedResourcesRoot != null &&
                        normalizedResourcesRoot.equals(expectedResources)) {
                    info.existingImportsContent = content;
                    return;
                }
            }
            pendingImports.put(normalizedResourcesRoot, content);
        }

        void recordExistingApplicationProperties(String resourcesRoot, String content) {
            String normalizedResourcesRoot = normalizePath(resourcesRoot);
            for (SourceRootInfo info : sourceRoots.values()) {
                String expectedResources = normalizePath(deriveResourcesRootStatic(info.sourceRoot));
                if (expectedResources != null && normalizedResourcesRoot != null &&
                        normalizedResourcesRoot.equals(expectedResources)) {
                    info.existingApplicationPropertiesContent = content;
                    return;
                }
            }
            pendingApplicationProperties.put(normalizedResourcesRoot, content);
        }

        static String deriveResourcesRootStatic(String sourceRoot) {
            if (sourceRoot == null) return null;
            String normalized = sourceRoot.replace('\\', '/');
            if (normalized.contains("/src/main/java")) {
                return normalized.replace("/src/main/java", "/src/main/resources");
            } else if (normalized.contains("/src/test/java")) {
                return normalized.replace("/src/test/java", "/src/test/resources");
            }
            return null;
        }
    }

    static class SourceRootInfo {
        final String sourceRoot;
        final boolean isTestSource;
        volatile boolean hasTransformation;
        volatile boolean hasPersistentTimer; // Track if any TimerConfig.isPersistent() usage
        volatile boolean needsMigratedTimerHandle; // P1.5: Track if MigratedTimerHandle is needed
        volatile boolean needsMigratedScheduleInfo; // P1.6: Track if MigratedScheduleInfo is needed
        volatile String existingImportsContent;
        volatile String existingApplicationPropertiesContent;
        // Track Job classes to generate for auto-transform
        final List<JobClassInfo> jobClasses = Collections.synchronizedList(new ArrayList<>());

        SourceRootInfo(String sourceRoot, boolean isTestSource) {
            this.sourceRoot = sourceRoot;
            this.isTestSource = isTestSource;
        }

        void recordPersistentTimerUsage() {
            this.hasPersistentTimer = true;
        }

        void recordTimerHandleUsage() {
            this.needsMigratedTimerHandle = true;
        }

        void recordScheduleInfoUsage() {
            this.needsMigratedScheduleInfo = true;
        }
    }

    static class JobClassInfo {
        final String packageName;
        final String className;
        final String jobClassName;
        final String delegateClassName;
        final String timeoutMethodName;
        final boolean hasContextParam;
        final boolean usesTimerInfo;

        JobClassInfo(String packageName, String className, String timeoutMethodName,
                     boolean hasContextParam, boolean usesTimerInfo) {
            this.packageName = packageName;
            this.className = className;
            this.jobClassName = className + "Job";
            this.delegateClassName = className;
            this.timeoutMethodName = timeoutMethodName;
            this.hasContextParam = hasContextParam;
            this.usesTimerInfo = usesTimerInfo;
        }
    }

    @Override
    public SourceRootAccumulator getInitialValue(ExecutionContext ctx) {
        return new SourceRootAccumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(SourceRootAccumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    Path sourcePath = sourceFile.getSourcePath();
                    String sourcePathStr = sourcePath.toString().replace('\\', '/');

                    if (sourcePathStr.endsWith("org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
                        if (tree instanceof PlainText) {
                            String existingContent = ((PlainText) tree).getText();
                            String resourcesRoot = sourcePathStr.substring(0,
                                sourcePathStr.indexOf("/META-INF"));
                            acc.recordExistingImports(resourcesRoot, existingContent);
                        }
                    }

                    // Scan for existing application.properties
                    if (sourcePathStr.endsWith("/application.properties")) {
                        if (tree instanceof PlainText) {
                            String existingContent = ((PlainText) tree).getText();
                            // Extract resources root (e.g., "src/main/resources")
                            int idx = sourcePathStr.lastIndexOf("/application.properties");
                            if (idx > 0) {
                                String resourcesRoot = sourcePathStr.substring(0, idx);
                                acc.recordExistingApplicationProperties(resourcesRoot, existingContent);
                            }
                        }
                    }

                    if (tree instanceof J.CompilationUnit) {
                        J.CompilationUnit cu = (J.CompilationUnit) tree;
                        String sourceRoot = detectSourceRoot(cu);
                        if (sourceRoot != null) {
                            boolean isTest = sourcePathStr.contains("/test/") ||
                                           sourcePathStr.contains("/src/test/");
                            acc.recordSourceRoot(sourceRoot, isTest);
                        }
                    }
                }
                return tree;
            }

            private String detectSourceRoot(J.CompilationUnit cu) {
                if (cu.getPackageDeclaration() == null) {
                    return null;
                }
                String pkg = cu.getPackageDeclaration().getExpression().print(new Cursor(null, cu)).trim();
                String pkgPath = pkg.replace('.', '/');
                String sourcePath = cu.getSourcePath().toString().replace('\\', '/');
                int idx = sourcePath.lastIndexOf("/" + pkgPath + "/");
                if (idx > 0) {
                    return sourcePath.substring(0, idx);
                }
                return null;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(SourceRootAccumulator acc) {
        TreeVisitor<?, ExecutionContext> delegate = Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_TIMER_SERVICE, false),
                new UsesType<>(JAVAX_TIMER_SERVICE, false),
                new UsesType<>(JAKARTA_TIMEOUT, false),
                new UsesType<>(JAVAX_TIMEOUT, false)
            ),
            new TimerServiceToQuartzVisitor(acc)
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

    @Override
    public Collection<SourceFile> generate(SourceRootAccumulator acc, ExecutionContext ctx) {
        List<SourceFile> generated = new ArrayList<>();

        for (SourceRootInfo info : acc.sourceRoots.values()) {
            if (info.hasTransformation && !info.isTestSource) {
                // Generate AutoConfiguration.imports for this module
                String resourcesRoot = SourceRootAccumulator.deriveResourcesRootStatic(info.sourceRoot);
                if (resourcesRoot != null) {
                    String importsPath = resourcesRoot + "/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";
                    Path path = Paths.get(importsPath);

                    String existingContent = info.existingImportsContent;
                    String newEntry = AUTO_CONFIG_FQN;

                    String finalContent;
                    if (existingContent != null && !existingContent.isBlank()) {
                        if (existingContent.contains(newEntry)) {
                            // Already present, don't duplicate
                        } else {
                            finalContent = existingContent.trim() + "\n" + newEntry + "\n";
                            PlainText importsFile = PlainText.builder()
                                .sourcePath(path)
                                .text(finalContent)
                                .build();
                            generated.add(importsFile);
                        }
                    } else {
                        finalContent = newEntry + "\n";
                        PlainText importsFile = PlainText.builder()
                            .sourcePath(path)
                            .text(finalContent)
                            .build();
                        generated.add(importsFile);
                    }
                }

                // Generate JobFactory auto-configuration class
                String autoConfigPath = info.sourceRoot + "/com/github/rewrite/migration/config/QuartzJobFactoryAutoConfiguration.java";
                String autoConfigContent = generateJobFactoryAutoConfig();
                PlainText autoConfigFile = PlainText.builder()
                    .sourcePath(Paths.get(autoConfigPath))
                    .text(autoConfigContent)
                    .build();
                generated.add(autoConfigFile);

                // Generate Job classes for auto-transformed classes
                for (JobClassInfo jobInfo : info.jobClasses) {
                    String jobClassPath = info.sourceRoot + "/" +
                        jobInfo.packageName.replace('.', '/') + "/" +
                        jobInfo.jobClassName + ".java";
                    String jobClassContent = generateJobClass(jobInfo);
                    PlainText jobFile = PlainText.builder()
                        .sourcePath(Paths.get(jobClassPath))
                        .text(jobClassContent)
                        .build();
                    generated.add(jobFile);
                }

                // P1.5: Generate MigratedTimerHandle class when local handles are transformed
                if (info.needsMigratedTimerHandle) {
                    String handlePath = info.sourceRoot + "/com/github/rewrite/migration/timer/MigratedTimerHandle.java";
                    String handleContent = generateMigratedTimerHandleClass();
                    PlainText handleFile = PlainText.builder()
                        .sourcePath(Paths.get(handlePath))
                        .text(handleContent)
                        .build();
                    generated.add(handleFile);
                }

                // P1.6: Generate MigratedScheduleInfo class when timer.getSchedule() is transformed
                if (info.needsMigratedScheduleInfo) {
                    String scheduleInfoPath = info.sourceRoot + "/com/github/rewrite/migration/timer/MigratedScheduleInfo.java";
                    String scheduleInfoContent = generateMigratedScheduleInfoClass();
                    PlainText scheduleInfoFile = PlainText.builder()
                        .sourcePath(Paths.get(scheduleInfoPath))
                        .text(scheduleInfoContent)
                        .build();
                    generated.add(scheduleInfoFile);
                }

                // Append Quartz persistence configuration to application.properties when TimerConfig is used
                // This ensures that isPersistent() = true actually persists jobs across restarts
                if (info.hasPersistentTimer && resourcesRoot != null) {
                    String propertiesPath = resourcesRoot + "/application.properties";
                    String existingProps = info.existingApplicationPropertiesContent;
                    String quartzProps = generateQuartzPersistenceProperties();

                    String finalContent;
                    if (existingProps != null && !existingProps.isBlank()) {
                        // Check if already contains the key property (idempotency)
                        // Use regex to handle whitespace and different values robustly
                        Pattern jobStoreKeyPattern = Pattern.compile("(?m)^\\s*spring\\.quartz\\.job-store-type\\s*=");
                        if (jobStoreKeyPattern.matcher(existingProps).find()) {
                            // Already configured, skip
                            finalContent = null;
                        } else {
                            // Append to existing content
                            finalContent = existingProps.trim() + "\n\n" + quartzProps;
                        }
                    } else {
                        // No existing application.properties, create with Quartz config
                        finalContent = quartzProps;
                    }

                    if (finalContent != null) {
                        PlainText propsFile = PlainText.builder()
                            .sourcePath(Paths.get(propertiesPath))
                            .text(finalContent)
                            .build();
                        generated.add(propsFile);
                    }
                }
            }
        }

        return generated;
    }

    /**
     * Generates Quartz persistence properties for JDBC JobStore in Spring Boot format.
     * These properties are appended directly to application.properties.
     * Required when TimerConfig.isPersistent() is used to preserve EJB persistence semantics.
     *
     * Note: useProperties is NOT set (defaults to false) to allow Serializable objects
     * in JobDataMap, preserving timer.getInfo() semantics from EJB.
     */
    private String generateQuartzPersistenceProperties() {
        return "# Quartz Persistence Configuration (generated by MigrateTimerServiceToQuartz)\n" +
               "# Required for EJB timers with isPersistent()=true\n" +
               "spring.quartz.job-store-type=jdbc\n" +
               "spring.quartz.jdbc.initialize-schema=always\n" +
               "spring.quartz.properties.org.quartz.jobStore.class=org.quartz.impl.jdbcjobstore.JobStoreTX\n" +
               "spring.quartz.properties.org.quartz.jobStore.driverDelegateClass=org.quartz.impl.jdbcjobstore.StdJDBCDelegate\n" +
               "spring.quartz.properties.org.quartz.jobStore.tablePrefix=QRTZ_\n" +
               "spring.quartz.properties.org.quartz.jobStore.isClustered=false\n";
    }

    private String generateJobClass(JobClassInfo info) {
        String paramCall = info.hasContextParam ? "context" : "";

        // Handle empty package (default package case)
        String packageDecl = info.packageName.isEmpty() ? "" : "package " + info.packageName + ";\n\n";

        // Note: No @Component - JobFactory instantiates via AutowireCapableBeanFactory.createBean()
        // This avoids forcing component scanning and follows constructor injection patterns
        return packageDecl +
               "import org.quartz.Job;\n" +
               "import org.quartz.JobExecutionContext;\n" +
               "import org.quartz.JobExecutionException;\n\n" +
               "/**\n" +
               " * Quartz Job that delegates to " + info.delegateClassName + ".\n" +
               " * Generated by MigrateTimerServiceToQuartz recipe.\n" +
               " * Instantiated by JobFactory via AutowireCapableBeanFactory.createBean().\n" +
               " */\n" +
               "public class " + info.jobClassName + " implements Job {\n\n" +
               "    private final " + info.delegateClassName + " delegate;\n\n" +
               "    public " + info.jobClassName + "(" + info.delegateClassName + " delegate) {\n" +
               "        this.delegate = delegate;\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public void execute(JobExecutionContext context) throws JobExecutionException {\n" +
               "        delegate." + info.timeoutMethodName + "(" + paramCall + ");\n" +
               "    }\n" +
               "}\n";
    }

    /**
     * P1.5: Generates the MigratedTimerHandle class for the target project.
     * This class wraps Quartz JobKey to provide TimerHandle-like semantics.
     */
    private String generateMigratedTimerHandleClass() {
        return "package com.github.migration.timer;\n\n" +
               "import java.io.Serializable;\n\n" +
               "/**\n" +
               " * Migration helper class that wraps Quartz JobKey to provide EJB TimerHandle-like semantics.\n" +
               " * Generated by MigrateTimerServiceToQuartz recipe.\n" +
               " */\n" +
               "public class MigratedTimerHandle implements Serializable {\n\n" +
               "    private static final long serialVersionUID = 1L;\n\n" +
               "    private final String jobName;\n" +
               "    private final String jobGroup;\n\n" +
               "    /**\n" +
               "     * Creates a MigratedTimerHandle for Quartz scheduler.\n" +
               "     *\n" +
               "     * @param jobName  the Quartz job name\n" +
               "     * @param jobGroup the Quartz job group\n" +
               "     */\n" +
               "    public MigratedTimerHandle(String jobName, String jobGroup) {\n" +
               "        this.jobName = jobName;\n" +
               "        this.jobGroup = jobGroup;\n" +
               "    }\n\n" +
               "    /**\n" +
               "     * Returns the Quartz job name.\n" +
               "     * @return the job name\n" +
               "     */\n" +
               "    public String getJobName() {\n" +
               "        return jobName;\n" +
               "    }\n\n" +
               "    /**\n" +
               "     * Returns the Quartz job group.\n" +
               "     * @return the job group\n" +
               "     */\n" +
               "    public String getJobGroup() {\n" +
               "        return jobGroup;\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public String toString() {\n" +
               "        return \"MigratedTimerHandle[\" + jobGroup + \".\" + jobName + \"]\";\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public boolean equals(Object o) {\n" +
               "        if (this == o) return true;\n" +
               "        if (o == null || getClass() != o.getClass()) return false;\n" +
               "        MigratedTimerHandle that = (MigratedTimerHandle) o;\n" +
               "        return java.util.Objects.equals(jobName, that.jobName) &&\n" +
               "               java.util.Objects.equals(jobGroup, that.jobGroup);\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public int hashCode() {\n" +
               "        return java.util.Objects.hash(jobName, jobGroup);\n" +
               "    }\n" +
               "}\n";
    }

    /**
     * P1.6: Generates the MigratedScheduleInfo helper class.
     * This class replaces timer.getSchedule() calls after migration to Quartz.
     */
    private String generateMigratedScheduleInfoClass() {
        return "/*\n" +
               " * Copyright 2021 - 2023 the original author or authors.\n" +
               " * Modifications Copyright 2026 Torsten Liermann\n" +
               " *\n" +
               " * Modified: P1.6 - Created MigratedScheduleInfo DTO for timer.getSchedule() migration\n" +
               " *\n" +
               " * Licensed under the Apache License, Version 2.0 (the \"License\");\n" +
               " * you may not use this file except in compliance with the License.\n" +
               " * You may obtain a copy of the License at\n" +
               " *\n" +
               " *      https://www.apache.org/licenses/LICENSE-2.0\n" +
               " *\n" +
               " * Unless required by applicable law or agreed to in writing, software\n" +
               " * distributed under the License is distributed on an \"AS IS\" BASIS,\n" +
               " * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\n" +
               " * See the License for the specific language governing permissions and\n" +
               " * limitations under the License.\n" +
               " */\n" +
               "package com.github.migration.timer;\n\n" +
               "import java.io.Serializable;\n\n" +
               "/**\n" +
               " * P1.6: Migration helper DTO that preserves EJB ScheduleExpression information.\n" +
               " * This class replaces timer.getSchedule() calls after migration to Quartz.\n" +
               " * It provides the same getter methods as EJB's ScheduleExpression for compatibility.\n" +
               " *\n" +
               " * Generated by MigrateTimerServiceToQuartz recipe.\n" +
               " */\n" +
               "public class MigratedScheduleInfo implements Serializable {\n\n" +
               "    private static final long serialVersionUID = 1L;\n\n" +
               "    private final String second;\n" +
               "    private final String minute;\n" +
               "    private final String hour;\n" +
               "    private final String dayOfMonth;\n" +
               "    private final String month;\n" +
               "    private final String dayOfWeek;\n" +
               "    private final String cronExpression;\n\n" +
               "    public MigratedScheduleInfo(String second, String minute, String hour,\n" +
               "                                 String dayOfMonth, String month, String dayOfWeek,\n" +
               "                                 String cronExpression) {\n" +
               "        this.second = second;\n" +
               "        this.minute = minute;\n" +
               "        this.hour = hour;\n" +
               "        this.dayOfMonth = dayOfMonth;\n" +
               "        this.month = month;\n" +
               "        this.dayOfWeek = dayOfWeek;\n" +
               "        this.cronExpression = cronExpression;\n" +
               "    }\n\n" +
               "    /** Returns the second field. Compatible with EJB ScheduleExpression.getSecond(). */\n" +
               "    public String getSecond() { return second; }\n\n" +
               "    /** Returns the minute field. Compatible with EJB ScheduleExpression.getMinute(). */\n" +
               "    public String getMinute() { return minute; }\n\n" +
               "    /** Returns the hour field. Compatible with EJB ScheduleExpression.getHour(). */\n" +
               "    public String getHour() { return hour; }\n\n" +
               "    /** Returns the day of month field. Compatible with EJB ScheduleExpression.getDayOfMonth(). */\n" +
               "    public String getDayOfMonth() { return dayOfMonth; }\n\n" +
               "    /** Returns the month field. Compatible with EJB ScheduleExpression.getMonth(). */\n" +
               "    public String getMonth() { return month; }\n\n" +
               "    /** Returns the day of week field. Compatible with EJB ScheduleExpression.getDayOfWeek(). */\n" +
               "    public String getDayOfWeek() { return dayOfWeek; }\n\n" +
               "    /** Returns the compiled cron expression. No EJB equivalent. */\n" +
               "    public String getCronExpression() { return cronExpression; }\n\n" +
               "    @Override\n" +
               "    public String toString() {\n" +
               "        return \"MigratedScheduleInfo[cron=\" + cronExpression + \"]\";\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public boolean equals(Object o) {\n" +
               "        if (this == o) return true;\n" +
               "        if (o == null || getClass() != o.getClass()) return false;\n" +
               "        MigratedScheduleInfo that = (MigratedScheduleInfo) o;\n" +
               "        return java.util.Objects.equals(second, that.second) &&\n" +
               "               java.util.Objects.equals(minute, that.minute) &&\n" +
               "               java.util.Objects.equals(hour, that.hour) &&\n" +
               "               java.util.Objects.equals(dayOfMonth, that.dayOfMonth) &&\n" +
               "               java.util.Objects.equals(month, that.month) &&\n" +
               "               java.util.Objects.equals(dayOfWeek, that.dayOfWeek) &&\n" +
               "               java.util.Objects.equals(cronExpression, that.cronExpression);\n" +
               "    }\n\n" +
               "    @Override\n" +
               "    public int hashCode() {\n" +
               "        return java.util.Objects.hash(second, minute, hour, dayOfMonth, month, dayOfWeek, cronExpression);\n" +
               "    }\n" +
               "}\n";
    }

    private String generateJobFactoryAutoConfig() {
        return "package com.github.migration.config;\n\n" +
               "import org.quartz.Job;\n" +
               "import org.quartz.Scheduler;\n" +
               "import org.quartz.SchedulerException;\n" +
               "import org.quartz.spi.JobFactory;\n" +
               "import org.quartz.spi.TriggerFiredBundle;\n" +
               "import org.springframework.beans.factory.config.AutowireCapableBeanFactory;\n" +
               "import org.springframework.boot.autoconfigure.AutoConfiguration;\n" +
               "import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;\n" +
               "import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;\n" +
               "import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;\n" +
               "import org.springframework.context.annotation.Bean;\n\n" +
               "/**\n" +
               " * Auto-configuration for Quartz JobFactory with constructor injection support.\n" +
               " * Uses createBean() to enable constructor injection for generated Job classes.\n" +
               " * Generated by MigrateTimerServiceToQuartz recipe.\n" +
               " */\n" +
               "@AutoConfiguration\n" +
               "@ConditionalOnClass(Scheduler.class)\n" +
               "public class QuartzJobFactoryAutoConfiguration {\n\n" +
               "    @Bean\n" +
               "    @ConditionalOnMissingBean(JobFactory.class)\n" +
               "    public JobFactory quartzJobFactory(AutowireCapableBeanFactory beanFactory) {\n" +
               "        return (bundle, scheduler) -> {\n" +
               "            Class<? extends Job> jobClass = bundle.getJobDetail().getJobClass();\n" +
               "            return beanFactory.createBean(jobClass);\n" +
               "        };\n" +
               "    }\n\n" +
               "    @Bean\n" +
               "    @ConditionalOnMissingBean(name = \"quartzJobFactoryCustomizer\")\n" +
               "    public SchedulerFactoryBeanCustomizer quartzJobFactoryCustomizer(JobFactory jobFactory) {\n" +
               "        return schedulerFactoryBean -> schedulerFactoryBean.setJobFactory(jobFactory);\n" +
               "    }\n" +
               "}\n";
    }

    // ========== Main Visitor ==========

    private class TimerServiceToQuartzVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final SourceRootAccumulator acc;
        private boolean transformedTimerToJobContext = false;

        TimerServiceToQuartzVisitor(SourceRootAccumulator acc) {
            this.acc = acc;
        }

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            // Reset flag for each compilation unit
            transformedTimerToJobContext = false;

            // Process the compilation unit (classes, methods, etc.)
            J.CompilationUnit result = super.visitCompilationUnit(cu, ctx);

            // If we transformed Timer param to JobExecutionContext, remove Timer import directly
            if (transformedTimerToJobContext) {
                List<J.Import> newImports = new ArrayList<>();
                for (J.Import imp : result.getImports()) {
                    String fqn = imp.getTypeName();
                    if (!JAKARTA_TIMER.equals(fqn) && !JAVAX_TIMER.equals(fqn)) {
                        newImports.add(imp);
                    }
                }
                if (newImports.size() != result.getImports().size()) {
                    result = result.withImports(newImports);
                }
            }

            return result;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Analyze class for timer usage
            TimerUsageAnalysis analysis = analyzeTimerUsage(cd);

            if (!analysis.hasTimerUsage()) {
                return cd;
            }

            // Check if already processed
            if (hasQuartzTimerServiceMarker(cd) || hasSchedulerField(cd)) {
                return cd;
            }

            // Decide: automatic transformation or fallback to marker
            if (canAutoTransform(analysis)) {
                return applyAutomaticTransformation(cd, analysis, ctx);
            } else {
                return applyFallbackMarker(cd, analysis, ctx);
            }
        }

        private boolean canAutoTransform(TimerUsageAnalysis analysis) {
            // Auto-transform conditions:
            // 1. Must have exactly one @Timeout method (Job delegation requires single entry point)
            // 2. Calendar timers only if analysis shows safe for migration (P1.4)
            // 3. Must NOT use getTimers() (dynamic timer management not supported)
            // 4. Must NOT use timer.cancel() or other unsupported Timer APIs
            //
            // SUPPORTED for auto-transform:
            // - createTimer/createIntervalTimer with any argument type:
            //   - long delay: direct pass-through to scheduleQuartzJob(long, ...)
            //   - Date expiration: uses Date overload helper that converts to delay
            //   - Duration: uses Duration overload helper that converts to millis
            // - createCalendarTimer with safe ScheduleExpression (P1.4):
            //   - Literal setter chain pattern
            //   - Only supported attributes (second, minute, hour, dayOfMonth, month, dayOfWeek)
            //   - No unsupported special values (Last, 1st, negative offsets)
            // - timer.getInfo() (mapped to JobDataMap with original Serializable preserved)
            //
            // Argument type safety: The generated helper method overloads handle
            // long, Date, and Duration first arguments. Java method overloading
            // resolves the correct helper at compile time. If an unsupported type
            // is used, a compile error in the transformed code prompts manual review.

            if (analysis.timeoutMethodCount != 1) {
                return false; // Multiple @Timeout methods need manual review
            }

            // P1.4: Calendar timers are allowed if analysis determined they are safe
            if (analysis.hasCalendarTimer && !analysis.calendarTimerIsSafe) {
                return false; // Unsafe calendar expressions need manual review
            }

            if (analysis.dynamicTimerCreation) {
                return false; // getTimers() indicates dynamic timer management
            }

            // Check for unsupported Timer APIs that would cause compile errors
            // timer.cancel(), timer.getTimeRemaining(), timer.getNextTimeout(), etc.
            if (analysis.usesTimerCancel || analysis.usesUnsupportedTimerApis) {
                return false;
            }

            // P1.5: TimerHandle detection and transformation
            // Local-only handles (no escape) can be auto-transformed to MigratedTimerHandle.
            // Escaped handles (stored in fields, returned, or passed to methods) need marker.
            if (analysis.usesTimerHandle && analysis.timerHandleEscapes) {
                return false; // Escaped handles need manual review
            }

            // P1.6: timer.getSchedule() detection and transformation
            // getSchedule() can only be transformed if we have a calendar timer with safe ScheduleExpression
            // because we need to know the schedule values at migration time
            // P1.6 Review 1: Also check for escape conditions (outside @Timeout or on non-timer param)
            if (analysis.usesTimerGetSchedule) {
                if (analysis.timerGetScheduleEscapes) {
                    // P1.6 Review 1: getSchedule() escapes (outside @Timeout or on non-timer variable)
                    return false;
                }
                if (analysis.hasCalendarTimer && analysis.calendarTimerIsSafe) {
                    // Safe to transform: we have the schedule values from createCalendarTimer analysis
                    analysis.canTransformGetSchedule = true;
                } else {
                    // Cannot transform: no static schedule information available
                    return false;
                }
            }

            // Auto-transform IS supported for:
            // - @Timeout only (scheduling handled externally)
            // - createTimer/createIntervalTimer (transformed to scheduleJob helper overloads)
            // - createCalendarTimer with safe ScheduleExpression (P1.4)
            // - timer.getInfo() (transformed to JobDataMap access)
            // - timer.getHandle() local-only (P1.5: transformed to MigratedTimerHandle)
            // - handle.getTimer().cancel() (P1.5: transformed to scheduler.deleteJob)
            // - timer.getSchedule() with safe calendar timer (P1.6: transformed to MigratedScheduleInfo)
            return true;
        }

        private J.ClassDeclaration applyAutomaticTransformation(J.ClassDeclaration cd, TimerUsageAnalysis analysis, ExecutionContext ctx) {
            // Record transformation for this source root
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            String sourceRoot = null;
            String packageName = "";
            if (cu != null) {
                sourceRoot = detectSourceRoot(cu);
                if (sourceRoot != null) {
                    acc.recordTransformation(sourceRoot);
                }
                if (cu.getPackageDeclaration() != null) {
                    packageName = cu.getPackageDeclaration().getExpression()
                        .print(new Cursor(null, cu)).trim();
                }
            }

            String className = cd.getSimpleName();
            String jobClassName = className + "Job";
            String timeoutMethodName = analysis.timeoutMethodName;

            // Record Job class to generate
            if (sourceRoot != null) {
                SourceRootInfo info = acc.sourceRoots.get(sourceRoot);
                if (info != null) {
                    info.jobClasses.add(new JobClassInfo(
                        packageName,
                        className,
                        timeoutMethodName,
                        analysis.timeoutHasTimerParam,
                        analysis.usesTimerInfo
                    ));
                    // Record if TimerConfig with potentially persistent timers is used
                    // Only triggers persistence config when isPersistent might be true
                    // (skips when all TimerConfig uses are explicitly new TimerConfig(info, false))
                    if (analysis.mayUsePersistentTimers) {
                        info.recordPersistentTimerUsage();
                    }
                    // P1.5: Record if MigratedTimerHandle is needed (local handles transformed)
                    if (analysis.usesTimerHandle && !analysis.timerHandleEscapes) {
                        info.recordTimerHandleUsage();
                    }
                    // P1.6: Record if MigratedScheduleInfo is needed (getSchedule() transformation)
                    if (analysis.canTransformGetSchedule) {
                        info.recordScheduleInfoUsage();
                    }
                }
            }

            // Check if any timer creation exists (for field and helper generation)
            boolean hasAnyTimerCreation = analysis.hasSingleTimer || analysis.hasIntervalTimer ||
                                          (analysis.hasCalendarTimer && analysis.calendarTimerIsSafe);
            // P1.5: Also need scheduler field if using handle.getTimer().cancel()
            boolean needsSchedulerField = hasAnyTimerCreation || analysis.usesTimerCancelViaHandle;

            // 1. Handle TimerService field
            if (analysis.hasTimerService) {
                if (needsSchedulerField) {
                    // Replace TimerService with Scheduler (needed for scheduleJob/deleteJob calls)
                    cd = replaceTimerServiceWithScheduler(cd, analysis);
                } else {
                    // No timer creation or cancel - just remove the field
                    cd = removeTimerServiceField(cd);
                }
            } else if (analysis.usesTimerCancelViaHandle) {
                // P1.5: No TimerService but has handle.getTimer().cancel() - inject scheduler
                cd = injectSchedulerField(cd);
            }

            // 2. Transform @Timeout method (remove annotation, change Timer param to JobExecutionContext)
            cd = transformTimeoutMethod(cd, analysis);

            // 3. Transform createTimer/createIntervalTimer/createCalendarTimer calls to scheduleJob
            if (hasAnyTimerCreation) {
                cd = transformTimerCreationCalls(cd, analysis, jobClassName);
                // 4. Add scheduling helper methods
                cd = addSchedulingHelperMethods(cd, analysis, jobClassName);
            }

            // 5. If there's getInfo() usage but no local scheduling, add a public entry point
            // This ensures external callers have a way to schedule the job with proper JobDataMap population
            if (analysis.usesTimerInfo && !hasAnyTimerCreation) {
                cd = addExternalSchedulingEntryPoint(cd, jobClassName);
            }

            // Add required imports using doAfterVisit to ensure they're added
            if (analysis.timeoutHasTimerParam || analysis.usesTimerInfo) {
                doAfterVisit(new AddImport<>(QUARTZ_JOB_EXECUTION_CONTEXT_FQN, null, false));
            }
            if (hasAnyTimerCreation) {
                doAfterVisit(new AddImport<>(QUARTZ_SCHEDULER_FQN, null, false));
                doAfterVisit(new AddImport<>(QUARTZ_JOB_DETAIL_FQN, null, false));
                doAfterVisit(new AddImport<>(QUARTZ_JOB_BUILDER_FQN, null, false));
                doAfterVisit(new AddImport<>(QUARTZ_TRIGGER_FQN, null, false));
                doAfterVisit(new AddImport<>(QUARTZ_TRIGGER_BUILDER_FQN, null, false));
                doAfterVisit(new AddImport<>(QUARTZ_SCHEDULER_EXCEPTION_FQN, null, false));
                doAfterVisit(new AddImport<>("java.util.Date", null, false));
                doAfterVisit(new AddImport<>("java.time.Instant", null, false));
                if (analysis.hasIntervalTimer) {
                    doAfterVisit(new AddImport<>(QUARTZ_SIMPLE_SCHEDULE_BUILDER_FQN, null, false));
                }
                // P1.4: Calendar timers need CronScheduleBuilder
                if (analysis.hasCalendarTimer && analysis.calendarTimerIsSafe) {
                    doAfterVisit(new AddImport<>(QUARTZ_CRON_SCHEDULE_BUILDER_FQN, null, false));
                }
            }
            // Add imports for external scheduling entry point (uses getInfo without local createTimer)
            if (analysis.usesTimerInfo && !hasAnyTimerCreation) {
                doAfterVisit(new AddImport<>(QUARTZ_SCHEDULER_FQN, null, false));
                doAfterVisit(new AddImport<>(QUARTZ_JOB_DETAIL_FQN, null, false));
                doAfterVisit(new AddImport<>(QUARTZ_JOB_BUILDER_FQN, null, false));
                doAfterVisit(new AddImport<>(QUARTZ_TRIGGER_FQN, null, false));
                doAfterVisit(new AddImport<>(QUARTZ_TRIGGER_BUILDER_FQN, null, false));
                doAfterVisit(new AddImport<>(QUARTZ_SCHEDULER_EXCEPTION_FQN, null, false));
                doAfterVisit(new AddImport<>("java.util.Date", null, false));
                doAfterVisit(new AddImport<>("java.time.Instant", null, false));
                doAfterVisit(new AddImport<>("org.quartz.JobDataMap", null, false));
            }

            // P1.5: Add MigratedTimerHandle and JobKey imports for local handle transformation
            if (analysis.usesTimerHandle && !analysis.timerHandleEscapes) {
                doAfterVisit(new AddImport<>(MIGRATED_TIMER_HANDLE_FQN, null, false));
                doAfterVisit(new AddImport<>("org.quartz.JobKey", null, false));
            }
            // P1.6: Add MigratedScheduleInfo import for getSchedule() transformation
            if (analysis.canTransformGetSchedule) {
                doAfterVisit(new AddImport<>(MIGRATED_SCHEDULE_INFO_FQN, null, false));
            }
            // P1.5: Add Scheduler import when using handle.getTimer().cancel() and not already added
            if (analysis.usesTimerCancelViaHandle && !hasAnyTimerCreation && !(analysis.usesTimerInfo && !hasAnyTimerCreation)) {
                doAfterVisit(new AddImport<>(QUARTZ_SCHEDULER_FQN, null, false));
            }

            // Remove EJB imports
            maybeRemoveImport(JAKARTA_TIMER_SERVICE);
            maybeRemoveImport(JAVAX_TIMER_SERVICE);
            maybeRemoveImport(JAKARTA_TIMEOUT);
            maybeRemoveImport(JAVAX_TIMEOUT);
            maybeRemoveImport(JAKARTA_RESOURCE);
            maybeRemoveImport(JAVAX_RESOURCE);
            maybeRemoveImport(JAKARTA_TIMER);
            maybeRemoveImport(JAVAX_TIMER);
            // P1.5: Remove TimerHandle imports when transformed
            if (analysis.usesTimerHandle && !analysis.timerHandleEscapes) {
                maybeRemoveImport(JAKARTA_TIMER_HANDLE);
                maybeRemoveImport(JAVAX_TIMER_HANDLE);
            }
            // Clean up all unused imports at the end
            doAfterVisit(new RemoveUnusedImports().getVisitor());

            return cd;
        }

        private J.ClassDeclaration removeTimerServiceField(J.ClassDeclaration cd) {
            // In auto-transform mode without timer creation, the TimerService field is not needed
            List<Statement> newStatements = new ArrayList<>();

            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (!isTimerServiceField(vd)) {
                        newStatements.add(stmt);
                    }
                    // TimerService fields are simply omitted
                } else {
                    newStatements.add(stmt);
                }
            }

            return cd.withBody(cd.getBody().withStatements(newStatements));
        }

        private J.ClassDeclaration replaceTimerServiceWithScheduler(J.ClassDeclaration cd, TimerUsageAnalysis analysis) {
            // Replace @Resource TimerService with private final Scheduler scheduler
            // and add constructor injection
            List<Statement> newStatements = new ArrayList<>();
            boolean schedulerFieldAdded = false;
            String schedulerFieldName = "scheduler";

            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (isTimerServiceField(vd)) {
                        // Transform TimerService field to Scheduler field
                        if (!schedulerFieldAdded) {
                            // Remove @Resource annotation
                            List<J.Annotation> newAnnotations = new ArrayList<>();
                            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                                if (!isResourceAnnotation(ann)) {
                                    newAnnotations.add(ann);
                                }
                            }
                            vd = vd.withLeadingAnnotations(newAnnotations);

                            // Change type from TimerService to Scheduler
                            JavaType.ShallowClass schedulerType = JavaType.ShallowClass.build(QUARTZ_SCHEDULER_FQN);
                            J.Identifier schedulerTypeIdent = new J.Identifier(
                                Tree.randomId(),
                                Space.SINGLE_SPACE,  // Space before type name after modifiers
                                Markers.EMPTY,
                                Collections.emptyList(),
                                "Scheduler",
                                schedulerType,
                                null
                            );
                            vd = vd.withTypeExpression(schedulerTypeIdent);

                            // Add 'final' modifier if not present
                            boolean hasFinal = false;
                            for (J.Modifier mod : vd.getModifiers()) {
                                if (mod.getType() == J.Modifier.Type.Final) {
                                    hasFinal = true;
                                    break;
                                }
                            }
                            if (!hasFinal) {
                                List<J.Modifier> newModifiers = new ArrayList<>(vd.getModifiers());
                                J.Modifier finalMod = new J.Modifier(
                                    Tree.randomId(),
                                    Space.SINGLE_SPACE,
                                    Markers.EMPTY,
                                    null,
                                    J.Modifier.Type.Final,
                                    Collections.emptyList()
                                );
                                newModifiers.add(finalMod);
                                vd = vd.withModifiers(newModifiers);
                            }

                            // Update variable name to 'scheduler' and update its type
                            List<J.VariableDeclarations.NamedVariable> newVars = new ArrayList<>();
                            for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                                JavaType.Variable newVarType = var.getVariableType() != null
                                    ? var.getVariableType().withType(schedulerType).withName(schedulerFieldName)
                                    : null;
                                J.Identifier nameIdent = var.getName()
                                    .withSimpleName(schedulerFieldName)
                                    .withType(schedulerType)
                                    .withFieldType(newVarType);
                                newVars.add(var.withName(nameIdent).withVariableType(newVarType));
                            }
                            vd = vd.withVariables(newVars);

                            newStatements.add(vd);
                            schedulerFieldAdded = true;
                        }
                        // Skip the original TimerService field (if multiple, only replace first)
                    } else {
                        newStatements.add(stmt);
                    }
                } else {
                    newStatements.add(stmt);
                }
            }

            cd = cd.withBody(cd.getBody().withStatements(newStatements));

            // Add constructor with Scheduler parameter
            cd = addSchedulerConstructor(cd, schedulerFieldName);

            return cd;
        }

        /**
         * P1.5: Inject a scheduler field when handle.getTimer().cancel() is used
         * but there's no TimerService field to replace.
         */
        private J.ClassDeclaration injectSchedulerField(J.ClassDeclaration cd) {
            String schedulerFieldName = "scheduler";

            // Create scheduler field: private final Scheduler scheduler;
            JavaType.ShallowClass schedulerType = JavaType.ShallowClass.build(QUARTZ_SCHEDULER_FQN);
            J.Identifier typeIdent = new J.Identifier(
                Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, Collections.emptyList(),
                "Scheduler", schedulerType, null
            );

            J.Identifier nameIdent = new J.Identifier(
                Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, Collections.emptyList(),
                schedulerFieldName, schedulerType, null
            );

            J.VariableDeclarations.NamedVariable namedVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                nameIdent, Collections.emptyList(), null, null
            );

            List<J.Modifier> modifiers = new ArrayList<>();
            modifiers.add(new J.Modifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, null, J.Modifier.Type.Private, Collections.emptyList()));
            modifiers.add(new J.Modifier(Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, null, J.Modifier.Type.Final, Collections.emptyList()));

            J.VariableDeclarations schedulerField = new J.VariableDeclarations(
                Tree.randomId(),
                Space.format("\n    "),
                Markers.EMPTY,
                Collections.emptyList(),
                modifiers,
                typeIdent,
                null,
                Collections.emptyList(),
                Collections.singletonList(JRightPadded.build(namedVar))
            );

            // Add field at beginning of class body
            List<Statement> newStatements = new ArrayList<>();
            newStatements.add(schedulerField);
            newStatements.addAll(cd.getBody().getStatements());
            cd = cd.withBody(cd.getBody().withStatements(newStatements));

            // Add constructor injection
            cd = addSchedulerConstructor(cd, schedulerFieldName);

            return cd;
        }

        private J.ClassDeclaration addSchedulerConstructor(J.ClassDeclaration cd, String schedulerFieldName) {
            // Find all existing constructors
            List<J.MethodDeclaration> existingConstructors = new ArrayList<>();
            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    if (md.isConstructor()) {
                        existingConstructors.add(md);
                    }
                }
            }

            if (existingConstructors.isEmpty()) {
                // Add new constructor with Scheduler parameter
                String className = cd.getSimpleName();
                String constructorCode = String.format(
                    "public %s(Scheduler %s) { this.%s = %s; }",
                    className, schedulerFieldName, schedulerFieldName, schedulerFieldName
                );

                JavaTemplate constructorTemplate = JavaTemplate.builder(constructorCode)
                    .imports(QUARTZ_SCHEDULER_FQN)
                    .contextSensitive()
                    .build();

                // Apply template
                cd = constructorTemplate.apply(
                    new Cursor(getCursor(), cd),
                    cd.getBody().getCoordinates().lastStatement()
                );
            } else {
                // Existing constructors - add Scheduler parameter to each and field assignment
                cd = addSchedulerToExistingConstructors(cd, existingConstructors, schedulerFieldName);
            }

            return cd;
        }

        private J.ClassDeclaration addSchedulerToExistingConstructors(J.ClassDeclaration cd,
                List<J.MethodDeclaration> constructors, String schedulerFieldName) {
            // Modify each existing constructor to add Scheduler parameter and field assignment
            List<Statement> newStatements = new ArrayList<>();

            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    if (md.isConstructor()) {
                        // Add Scheduler parameter to constructor
                        md = addSchedulerParameterToConstructor(md, schedulerFieldName);
                        // Add field assignment as first statement in body
                        md = addSchedulerFieldAssignment(md, schedulerFieldName);
                        newStatements.add(md);
                    } else {
                        newStatements.add(stmt);
                    }
                } else {
                    newStatements.add(stmt);
                }
            }

            return cd.withBody(cd.getBody().withStatements(newStatements));
        }

        private J.MethodDeclaration addSchedulerParameterToConstructor(J.MethodDeclaration md, String schedulerFieldName) {
            // Create new Scheduler parameter
            JavaType.ShallowClass schedulerType = JavaType.ShallowClass.build(QUARTZ_SCHEDULER_FQN);

            J.Identifier schedulerTypeIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Scheduler",
                schedulerType,
                null
            );

            J.Identifier paramNameIdent = new J.Identifier(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                Collections.emptyList(),
                schedulerFieldName,
                schedulerType,
                null
            );

            J.VariableDeclarations.NamedVariable paramVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                paramNameIdent,
                Collections.emptyList(),
                null,
                null
            );

            J.VariableDeclarations schedulerParam = new J.VariableDeclarations(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                Collections.emptyList(),
                Collections.emptyList(),
                schedulerTypeIdent,
                null,
                Collections.emptyList(),
                Collections.singletonList(JRightPadded.build(paramVar))
            );

            // Add to existing parameters
            List<Statement> existingParams = md.getParameters();
            List<Statement> newParams = new ArrayList<>(existingParams);

            // Handle empty parameter list (just J.Empty)
            if (newParams.size() == 1 && newParams.get(0) instanceof J.Empty) {
                newParams.clear();
            }

            newParams.add(schedulerParam);

            return md.withParameters(newParams);
        }

        private J.MethodDeclaration addSchedulerFieldAssignment(J.MethodDeclaration md, String schedulerFieldName) {
            if (md.getBody() == null) {
                return md;
            }

            // Create: this.scheduler = scheduler;
            J.Identifier thisIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "this",
                null,
                null
            );

            J.Identifier fieldIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                schedulerFieldName,
                null,
                null
            );

            J.FieldAccess thisField = new J.FieldAccess(
                Tree.randomId(),
                Space.format("\n        "), // Add newline and indentation
                Markers.EMPTY,
                thisIdent,
                JLeftPadded.build(fieldIdent),
                null
            );

            J.Identifier paramIdent = new J.Identifier(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                Collections.emptyList(),
                schedulerFieldName,
                null,
                null
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                thisField,
                JLeftPadded.build((Expression) paramIdent).withBefore(Space.SINGLE_SPACE),
                null
            );

            List<Statement> existingStatements = md.getBody().getStatements();
            List<Statement> bodyStatements = new ArrayList<>();

            // Check if first statement is this() or super() constructor call
            // Java requires these to be the first statement in a constructor
            if (!existingStatements.isEmpty()) {
                Statement firstStmt = existingStatements.get(0);
                if (isConstructorDelegation(firstStmt)) {
                    // For this() delegation: add scheduler to the call AND don't add assignment
                    // (the target constructor will set the field)
                    if (isThisCall(firstStmt)) {
                        Statement updatedThisCall = addSchedulerArgumentToThisCall(firstStmt, schedulerFieldName);
                        bodyStatements.add(updatedThisCall);
                        // Don't add assignment - target constructor handles it
                        bodyStatements.addAll(existingStatements.subList(1, existingStatements.size()));
                        return md.withBody(md.getBody().withStatements(bodyStatements));
                    }
                    // For super() delegation: add assignment AFTER super call
                    bodyStatements.add(firstStmt);
                    bodyStatements.add(assignment);
                    bodyStatements.addAll(existingStatements.subList(1, existingStatements.size()));
                    return md.withBody(md.getBody().withStatements(bodyStatements));
                }
            }

            // No constructor delegation - add assignment as first statement
            bodyStatements.add(assignment);
            bodyStatements.addAll(existingStatements);

            return md.withBody(md.getBody().withStatements(bodyStatements));
        }

        /**
         * Checks if a statement is a constructor delegation (this() or super() call).
         */
        private boolean isConstructorDelegation(Statement stmt) {
            if (!(stmt instanceof J.MethodInvocation)) {
                return false;
            }
            J.MethodInvocation mi = (J.MethodInvocation) stmt;
            String name = mi.getSimpleName();
            return "this".equals(name) || "super".equals(name);
        }

        /**
         * Checks if a statement is a this() constructor delegation.
         */
        private boolean isThisCall(Statement stmt) {
            if (!(stmt instanceof J.MethodInvocation)) {
                return false;
            }
            J.MethodInvocation mi = (J.MethodInvocation) stmt;
            return "this".equals(mi.getSimpleName());
        }

        /**
         * Adds scheduler parameter to this() constructor delegation call.
         */
        private Statement addSchedulerArgumentToThisCall(Statement stmt, String schedulerFieldName) {
            J.MethodInvocation mi = (J.MethodInvocation) stmt;

            // Create scheduler identifier for the new argument
            J.Identifier schedulerArg = new J.Identifier(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                Collections.emptyList(),
                schedulerFieldName,
                null,
                null
            );

            // Add scheduler to the end of existing arguments
            List<JRightPadded<Expression>> existingArgs = mi.getPadding().getArguments().getPadding().getElements();
            List<JRightPadded<Expression>> newArgsList = new ArrayList<>(existingArgs);
            newArgsList.add(JRightPadded.build((Expression) schedulerArg).withAfter(Space.EMPTY));

            JContainer<Expression> newArgs = JContainer.build(
                mi.getPadding().getArguments().getBefore(),
                newArgsList,
                mi.getPadding().getArguments().getMarkers()
            );

            return mi.getPadding().withArguments(newArgs);
        }

        private J.ClassDeclaration transformTimerCreationCalls(J.ClassDeclaration cd, TimerUsageAnalysis analysis, String jobClassName) {
            // Transform createTimer/createIntervalTimer/createCalendarTimer calls to Quartz scheduleJob pattern
            // We build new method invocations directly, preserving original arguments
            final Set<String> timerServiceFieldNames = analysis.timerServiceFieldNames;
            final ScheduleExpressionAnalysis calendarAnalysis = analysis.calendarTimerAnalysis;
            final boolean needsScheduleInfo = analysis.canTransformGetSchedule; // P1.6

            return (J.ClassDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext ctx) {
                    mi = super.visitMethodInvocation(mi, ctx);

                    String methodName = mi.getSimpleName();
                    if (!isTimerServiceInvocation(mi, timerServiceFieldNames)) {
                        return mi;
                    }

                    if ("createTimer".equals(methodName) || "createSingleActionTimer".equals(methodName)) {
                        return transformSingleTimerCreation(mi, jobClassName);
                    } else if ("createIntervalTimer".equals(methodName)) {
                        return transformIntervalTimerCreation(mi, jobClassName);
                    } else if ("createCalendarTimer".equals(methodName) && calendarAnalysis != null && calendarAnalysis.isSafeForMigration) {
                        // P1.4: Transform safe calendar timers
                        // P1.6: Pass needsScheduleInfo flag for extended helper method
                        return transformCalendarTimerCreation(mi, jobClassName, calendarAnalysis, needsScheduleInfo);
                    }

                    return mi;
                }

                private boolean isTimerServiceInvocation(J.MethodInvocation mi, Set<String> fieldNames) {
                    if (mi.getSelect() == null) return false;
                    Expression select = mi.getSelect();
                    if (select.getType() != null) {
                        return TypeUtils.isOfClassType(select.getType(), JAKARTA_TIMER_SERVICE) ||
                               TypeUtils.isOfClassType(select.getType(), JAVAX_TIMER_SERVICE);
                    }
                    if (select instanceof J.Identifier) {
                        String selectName = ((J.Identifier) select).getSimpleName();
                        return fieldNames.contains(selectName);
                    }
                    return false;
                }

                private J.MethodInvocation transformSingleTimerCreation(J.MethodInvocation mi, String jobClassName) {
                    // timerService.createTimer(duration, info) ->
                    // scheduleQuartzJob(duration, info, persistent, JobClass.class)
                    // Build new method invocation directly with the original arguments
                    //
                    // Handle TimerConfig: createTimer(duration, TimerConfig) or createSingleActionTimer(duration, TimerConfig)
                    // For TimerConfig, we extract config.getInfo() as the info argument and
                    // config.isPersistent() as the persistent flag

                    List<Expression> originalArgs = mi.getArguments();

                    // Build new argument list: [delay, info, persistent, JobClass.class]
                    List<JRightPadded<Expression>> newArgsList = new ArrayList<>();

                    // Arg 1: delay (from original arg 0, or literal 0L)
                    Expression delayArg = !originalArgs.isEmpty() && !(originalArgs.get(0) instanceof J.Empty)
                        ? originalArgs.get(0).withPrefix(Space.EMPTY)
                        : createLiteralLong(0L);
                    newArgsList.add(JRightPadded.build(delayArg).withAfter(Space.EMPTY));

                    // Arg 2: info (from original arg 1, or null literal)
                    // If arg 1 is a TimerConfig, wrap it with .getInfo() to extract the actual info object
                    // Arg 3: persistent (from TimerConfig.isPersistent() or false if no TimerConfig)
                    Expression infoArg;
                    Expression persistentArg;
                    Expression secondArgForPersistence = null;
                    if (originalArgs.size() > 1 && !(originalArgs.get(1) instanceof J.Empty)) {
                        Expression secondArg = originalArgs.get(1);
                        if (isTimerConfig(secondArg)) {
                            // TimerConfig -> config.getInfo()
                            infoArg = wrapWithGetInfo(secondArg).withPrefix(Space.SINGLE_SPACE);
                            // Store the original expression for isPersistent() call
                            secondArgForPersistence = secondArg;
                        } else {
                            infoArg = secondArg.withPrefix(Space.SINGLE_SPACE);
                        }
                    } else {
                        infoArg = createNullLiteral();
                    }
                    newArgsList.add(JRightPadded.build(infoArg).withAfter(Space.EMPTY));

                    // Arg 3: persistent
                    if (secondArgForPersistence != null) {
                        // TimerConfig -> config.isPersistent()
                        persistentArg = wrapWithIsPersistent(secondArgForPersistence).withPrefix(Space.SINGLE_SPACE);
                    } else {
                        // No TimerConfig -> default to false (non-persistent)
                        persistentArg = createBooleanLiteral(false);
                    }
                    newArgsList.add(JRightPadded.build(persistentArg).withAfter(Space.EMPTY));

                    // Arg 4: JobClass.class
                    Expression classArg = createClassLiteral(jobClassName);
                    newArgsList.add(JRightPadded.build(classArg).withAfter(Space.EMPTY));

                    JContainer<Expression> newArgs = JContainer.build(
                        Space.EMPTY,
                        newArgsList,
                        Markers.EMPTY
                    );

                    // Create method name identifier
                    J.Identifier methodName = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "scheduleQuartzJob",
                        null,
                        null
                    );

                    // Create new method invocation (no select - it's a local method)
                    return new J.MethodInvocation(
                        Tree.randomId(),
                        mi.getPrefix(),
                        Markers.EMPTY,
                        null,  // no select
                        null,  // no type params
                        methodName,
                        newArgs,
                        null   // no method type
                    );
                }

                private J.MethodInvocation transformIntervalTimerCreation(J.MethodInvocation mi, String jobClassName) {
                    // timerService.createIntervalTimer(initialDelay, interval, info) ->
                    // scheduleQuartzIntervalJob(initialDelay, interval, info, persistent, JobClass.class)
                    //
                    // Handle TimerConfig: createIntervalTimer(initialDelay, interval, TimerConfig)
                    // For TimerConfig, we extract config.getInfo() as the info argument and
                    // config.isPersistent() as the persistent flag

                    List<Expression> originalArgs = mi.getArguments();

                    // Build new argument list: [initialDelay, interval, info, persistent, JobClass.class]
                    List<JRightPadded<Expression>> newArgsList = new ArrayList<>();

                    // Arg 1: initialDelay (from original arg 0, or literal 0L)
                    Expression initialDelayArg = !originalArgs.isEmpty() && !(originalArgs.get(0) instanceof J.Empty)
                        ? originalArgs.get(0).withPrefix(Space.EMPTY)
                        : createLiteralLong(0L);
                    newArgsList.add(JRightPadded.build(initialDelayArg).withAfter(Space.EMPTY));

                    // Arg 2: interval (from original arg 1, or literal 1000L)
                    Expression intervalArg = originalArgs.size() > 1 && !(originalArgs.get(1) instanceof J.Empty)
                        ? originalArgs.get(1).withPrefix(Space.SINGLE_SPACE)
                        : createLiteralLong(1000L);
                    newArgsList.add(JRightPadded.build(intervalArg).withAfter(Space.EMPTY));

                    // Arg 3: info (from original arg 2, or null)
                    // If arg 2 is a TimerConfig, wrap it with .getInfo() to extract the actual info object
                    // Arg 4: persistent (from TimerConfig.isPersistent() or false if no TimerConfig)
                    Expression infoArg;
                    Expression persistentArg;
                    Expression thirdArgForPersistence = null;
                    if (originalArgs.size() > 2 && !(originalArgs.get(2) instanceof J.Empty)) {
                        Expression thirdArg = originalArgs.get(2);
                        if (isTimerConfig(thirdArg)) {
                            // TimerConfig -> config.getInfo()
                            infoArg = wrapWithGetInfo(thirdArg).withPrefix(Space.SINGLE_SPACE);
                            // Store the original expression for isPersistent() call
                            thirdArgForPersistence = thirdArg;
                        } else {
                            infoArg = thirdArg.withPrefix(Space.SINGLE_SPACE);
                        }
                    } else {
                        infoArg = createNullLiteral();
                    }
                    newArgsList.add(JRightPadded.build(infoArg).withAfter(Space.EMPTY));

                    // Arg 4: persistent
                    if (thirdArgForPersistence != null) {
                        // TimerConfig -> config.isPersistent()
                        persistentArg = wrapWithIsPersistent(thirdArgForPersistence).withPrefix(Space.SINGLE_SPACE);
                    } else {
                        // No TimerConfig -> default to false (non-persistent)
                        persistentArg = createBooleanLiteral(false);
                    }
                    newArgsList.add(JRightPadded.build(persistentArg).withAfter(Space.EMPTY));

                    // Arg 5: JobClass.class
                    Expression classArg = createClassLiteral(jobClassName);
                    newArgsList.add(JRightPadded.build(classArg).withAfter(Space.EMPTY));

                    JContainer<Expression> newArgs = JContainer.build(
                        Space.EMPTY,
                        newArgsList,
                        Markers.EMPTY
                    );

                    // Create method name identifier
                    J.Identifier methodName = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "scheduleQuartzIntervalJob",
                        null,
                        null
                    );

                    // Create new method invocation (no select - it's a local method)
                    return new J.MethodInvocation(
                        Tree.randomId(),
                        mi.getPrefix(),
                        Markers.EMPTY,
                        null,  // no select
                        null,  // no type params
                        methodName,
                        newArgs,
                        null   // no method type
                    );
                }

                /**
                 * P1.4: Transforms createCalendarTimer call to scheduleQuartzCronJob.
                 * timerService.createCalendarTimer(scheduleExpr, timerConfig) ->
                 * scheduleQuartzCronJob("0 0 2 * * Mon-Fri", info, persistent, JobClass.class)
                 *
                 * P1.6: When needsScheduleInfo is true, includes schedule component arguments:
                 * scheduleQuartzCronJob("0 0 2 ? * Mon-Fri", info, persistent, JobClass.class,
                 *     "0", "0", "2", "*", "*", "Mon-Fri")
                 */
                private J.MethodInvocation transformCalendarTimerCreation(J.MethodInvocation mi, String jobClassName,
                        ScheduleExpressionAnalysis seAnalysis, boolean needsScheduleInfo) {
                    List<Expression> originalArgs = mi.getArguments();

                    // Build new argument list: [cronExpression, info, persistent, JobClass.class, [scheduleComponents...]]
                    List<JRightPadded<Expression>> newArgsList = new ArrayList<>();

                    // Arg 1: cronExpression (built from ScheduleExpressionAnalysis)
                    String cronExpr = seAnalysis.buildQuartzCronExpression();
                    J.Literal cronLiteral = new J.Literal(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        cronExpr,
                        "\"" + cronExpr + "\"",
                        null,
                        JavaType.Primitive.String
                    );
                    newArgsList.add(JRightPadded.build((Expression) cronLiteral).withAfter(Space.EMPTY));

                    // Arg 2 & 3: info and persistent (from TimerConfig if present)
                    Expression infoArg;
                    Expression persistentArg;
                    Expression timerConfigArg = null;

                    // createCalendarTimer(ScheduleExpression, TimerConfig) - second arg is TimerConfig
                    // P1.4 Review 3 Fix: Return original mi instead of null to preserve the call
                    // (returning null in JavaIsoVisitor removes the node!)
                    if (originalArgs.size() < 2 || originalArgs.get(1) instanceof J.Empty) {
                        // Missing second argument - this is an invalid API call or AST parsing issue
                        // Return original method invocation - marker annotation will handle it
                        return mi;
                    }

                    Expression secondArg = originalArgs.get(1);
                    if (isTimerConfig(secondArg)) {
                        infoArg = wrapWithGetInfo(secondArg).withPrefix(Space.SINGLE_SPACE);
                        timerConfigArg = secondArg;
                    } else if (isNullLiteral(secondArg)) {
                        // null TimerConfig: EJB default is persistent=true, info=null
                        infoArg = createNullLiteral();
                        // timerConfigArg stays null, persistent will be set to true below
                    } else {
                        // Unknown second argument type - not safe to migrate
                        // Return original method invocation - marker annotation will handle it
                        return mi;
                    }
                    newArgsList.add(JRightPadded.build(infoArg).withAfter(Space.EMPTY));

                    // Arg 3: persistent
                    // EJB default: persistent=true when TimerConfig is null or not provided
                    if (timerConfigArg != null) {
                        persistentArg = wrapWithIsPersistent(timerConfigArg).withPrefix(Space.SINGLE_SPACE);
                    } else {
                        // EJB spec: default is persistent=true
                        persistentArg = createBooleanLiteral(true);
                    }
                    newArgsList.add(JRightPadded.build(persistentArg).withAfter(Space.EMPTY));

                    // Arg 4: JobClass.class
                    Expression classArg = createClassLiteral(jobClassName);
                    newArgsList.add(JRightPadded.build(classArg).withAfter(Space.EMPTY));

                    // P1.6: Add schedule component arguments for MigratedScheduleInfo construction
                    if (needsScheduleInfo) {
                        // Args 5-10: second, minute, hour, dayOfMonth, month, dayOfWeek
                        newArgsList.add(JRightPadded.build((Expression) createStringLiteral(seAnalysis.second)).withAfter(Space.EMPTY));
                        newArgsList.add(JRightPadded.build((Expression) createStringLiteral(seAnalysis.minute)).withAfter(Space.EMPTY));
                        newArgsList.add(JRightPadded.build((Expression) createStringLiteral(seAnalysis.hour)).withAfter(Space.EMPTY));
                        newArgsList.add(JRightPadded.build((Expression) createStringLiteral(seAnalysis.dayOfMonth)).withAfter(Space.EMPTY));
                        newArgsList.add(JRightPadded.build((Expression) createStringLiteral(seAnalysis.month)).withAfter(Space.EMPTY));
                        newArgsList.add(JRightPadded.build((Expression) createStringLiteral(seAnalysis.dayOfWeek)).withAfter(Space.EMPTY));
                    }

                    JContainer<Expression> newArgs = JContainer.build(
                        Space.EMPTY,
                        newArgsList,
                        Markers.EMPTY
                    );

                    // Create method name identifier
                    J.Identifier methodName = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "scheduleQuartzCronJob",
                        null,
                        null
                    );

                    // Create new method invocation (no select - it's a local method)
                    return new J.MethodInvocation(
                        Tree.randomId(),
                        mi.getPrefix(),
                        Markers.EMPTY,
                        null,  // no select
                        null,  // no type params
                        methodName,
                        newArgs,
                        null   // no method type
                    );
                }

                /**
                 * P1.6: Creates a String literal expression.
                 */
                private J.Literal createStringLiteral(String value) {
                    return new J.Literal(
                        Tree.randomId(),
                        Space.SINGLE_SPACE,
                        Markers.EMPTY,
                        value,
                        "\"" + value + "\"",
                        null,
                        JavaType.Primitive.String
                    );
                }

                private J.Literal createLiteralLong(long value) {
                    return new J.Literal(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        value,
                        value + "L",
                        null,
                        JavaType.Primitive.Long
                    );
                }

                private J.Literal createNullLiteral() {
                    return new J.Literal(
                        Tree.randomId(),
                        Space.SINGLE_SPACE,
                        Markers.EMPTY,
                        null,
                        "null",
                        null,
                        null
                    );
                }

                /**
                 * Checks if an expression is a null literal.
                 */
                private boolean isNullLiteral(Expression expr) {
                    if (expr instanceof J.Literal) {
                        J.Literal lit = (J.Literal) expr;
                        return lit.getValue() == null && "null".equals(lit.getValueSource());
                    }
                    return false;
                }

                private J.Literal createBooleanLiteral(boolean value) {
                    return new J.Literal(
                        Tree.randomId(),
                        Space.SINGLE_SPACE,
                        Markers.EMPTY,
                        value,
                        value ? "true" : "false",
                        null,
                        JavaType.Primitive.Boolean
                    );
                }

                private J.FieldAccess createClassLiteral(String className) {
                    // Creates: ClassName.class
                    J.Identifier classIdent = new J.Identifier(
                        Tree.randomId(),
                        Space.SINGLE_SPACE,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        className,
                        null,
                        null
                    );
                    J.Identifier classKeyword = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "class",
                        JavaType.Primitive.Void,
                        null
                    );
                    return new J.FieldAccess(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        classIdent,
                        JLeftPadded.build(classKeyword),
                        null
                    );
                }

                /**
                 * Checks if an expression is of type TimerConfig (jakarta.ejb.TimerConfig or javax.ejb.TimerConfig).
                 * Uses multiple detection strategies for robustness:
                 * 1. Type attribution (most reliable when available)
                 * 2. Import-aware simple name matching
                 * 3. FQN detection in new expressions
                 */
                private boolean isTimerConfig(Expression expr) {
                    if (expr == null) {
                        return false;
                    }

                    // 1. Check type attribution first (most reliable)
                    JavaType type = expr.getType();
                    if (type != null) {
                        if (TypeUtils.isOfClassType(type, JAKARTA_TIMER_CONFIG) ||
                            TypeUtils.isOfClassType(type, JAVAX_TIMER_CONFIG)) {
                            return true;
                        }
                    }

                    // 2. Check for "new TimerConfig(...)" expressions
                    if (expr instanceof J.NewClass) {
                        J.NewClass newClass = (J.NewClass) expr;
                        TypeTree clazz = newClass.getClazz();
                        if (clazz instanceof J.Identifier) {
                            J.Identifier ident = (J.Identifier) clazz;
                            if ("TimerConfig".equals(ident.getSimpleName())) {
                                return hasEjbTimerConfigImport();
                            }
                        }
                        if (clazz instanceof J.FieldAccess) {
                            J.FieldAccess fa = (J.FieldAccess) clazz;
                            if ("TimerConfig".equals(fa.getSimpleName())) {
                                // Use extractFullyQualifiedName instead of print(getCursor())
                                String fullName = extractFullyQualifiedName(fa);
                                return JAKARTA_TIMER_CONFIG.equals(fullName) || JAVAX_TIMER_CONFIG.equals(fullName);
                            }
                        }
                    }

                    // 3. Check for identifier - look up variable declaration type
                    if (expr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) expr;
                        String varName = ident.getSimpleName();
                        // Look for variable declaration in enclosing method/block
                        if (isVariableDeclaredAsTimerConfig(varName)) {
                            return true;
                        }
                    }

                    return false;
                }

                /**
                 * Checks if a variable is declared as TimerConfig in the enclosing scope.
                 * This checks method parameters, local variables, and class fields.
                 * This helps detect TimerConfig when type attribution is missing.
                 */
                private boolean isVariableDeclaredAsTimerConfig(String varName) {
                    // Walk up the cursor to find enclosing method
                    J.MethodDeclaration method = getCursor().firstEnclosing(J.MethodDeclaration.class);

                    // 1. Check method parameters
                    if (method != null) {
                        for (Statement param : method.getParameters()) {
                            if (param instanceof J.VariableDeclarations) {
                                if (checkVariableDeclarationsForTimerConfig((J.VariableDeclarations) param, varName)) {
                                    return true;
                                }
                            }
                        }
                    }

                    // 2. Check local variable declarations in the method body
                    if (method != null && method.getBody() != null) {
                        for (Statement stmt : method.getBody().getStatements()) {
                            if (stmt instanceof J.VariableDeclarations) {
                                if (checkVariableDeclarationsForTimerConfig((J.VariableDeclarations) stmt, varName)) {
                                    return true;
                                }
                            }
                        }
                    }

                    // 3. Check class fields
                    J.ClassDeclaration classDecl = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    if (classDecl != null && classDecl.getBody() != null) {
                        for (Statement stmt : classDecl.getBody().getStatements()) {
                            if (stmt instanceof J.VariableDeclarations) {
                                if (checkVariableDeclarationsForTimerConfig((J.VariableDeclarations) stmt, varName)) {
                                    return true;
                                }
                            }
                        }
                    }

                    return false;
                }

                /**
                 * Helper method to check if a VariableDeclarations contains the named variable
                 * declared as TimerConfig type.
                 * Uses unified detection: type attribution first, then import-aware simple name check.
                 */
                private boolean checkVariableDeclarationsForTimerConfig(J.VariableDeclarations decl, String varName) {
                    for (J.VariableDeclarations.NamedVariable var : decl.getVariables()) {
                        if (varName.equals(var.getSimpleName())) {
                            // 1. Check type attribution first (most reliable)
                            JavaType declType = decl.getType();
                            if (declType != null) {
                                if (TypeUtils.isOfClassType(declType, JAKARTA_TIMER_CONFIG) ||
                                    TypeUtils.isOfClassType(declType, JAVAX_TIMER_CONFIG)) {
                                    return true;
                                }
                            }
                            // 2. Fallback: check type expression without cursor-based print
                            TypeTree typeTree = decl.getTypeExpression();
                            if (typeTree != null) {
                                String typeName = extractSimpleTypeName(typeTree);
                                if ("TimerConfig".equals(typeName)) {
                                    // Simple name - verify EJB import to avoid false positives
                                    return hasEjbTimerConfigImport();
                                }
                                // 3. Check fully qualified name in FieldAccess
                                if (typeTree instanceof J.FieldAccess) {
                                    J.FieldAccess fa = (J.FieldAccess) typeTree;
                                    String fqn = extractFullyQualifiedName(fa);
                                    if (JAKARTA_TIMER_CONFIG.equals(fqn) || JAVAX_TIMER_CONFIG.equals(fqn)) {
                                        return true;
                                    }
                                }
                            }
                            return false; // Found the variable but it's not TimerConfig
                        }
                    }
                    return false; // Variable not found in this declaration
                }

                /**
                 * Checks if there's an import for EJB TimerConfig.
                 * Uses type attribution and FQN extraction (no cursor-based print).
                 */
                private boolean hasEjbTimerConfigImport() {
                    J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                    if (cu == null || cu.getImports() == null) return false;

                    for (J.Import imp : cu.getImports()) {
                        // Check using type if available
                        if (imp.getQualid().getType() != null) {
                            String typeFqn = imp.getQualid().getType().toString();
                            if (typeFqn.equals(JAKARTA_TIMER_CONFIG) || typeFqn.equals(JAVAX_TIMER_CONFIG)) {
                                return true;
                            }
                        }
                        // Fallback: extract FQN from qualid structure
                        String importFqn = extractFqnFromQualid(imp.getQualid());
                        if (JAKARTA_TIMER_CONFIG.equals(importFqn) || JAVAX_TIMER_CONFIG.equals(importFqn)) {
                            return true;
                        }
                        if ("jakarta.ejb.*".equals(importFqn) || "javax.ejb.*".equals(importFqn)) {
                            return true;
                        }
                    }
                    return false;
                }

                // Note: extractSimpleTypeName, extractFullyQualifiedName, extractFqnFromQualid
                // are now shared static methods at class level

                /**
                 * Wraps a TimerConfig expression with .getInfo() to extract the actual info object.
                 * TimerConfig.getInfo() returns the Serializable info.
                 */
                private J.MethodInvocation wrapWithGetInfo(Expression configExpr) {
                    // Create: configExpr.getInfo()
                    J.Identifier getInfoIdent = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "getInfo",
                        null,
                        null
                    );

                    return new J.MethodInvocation(
                        Tree.randomId(),
                        configExpr.getPrefix(),
                        Markers.EMPTY,
                        JRightPadded.build(configExpr.withPrefix(Space.EMPTY)),
                        null,  // no type params
                        getInfoIdent,
                        JContainer.build(
                            Space.EMPTY,
                            Collections.emptyList(),
                            Markers.EMPTY
                        ),
                        null   // no method type
                    );
                }

                /**
                 * Wraps a TimerConfig expression with .isPersistent() to extract the persistence flag.
                 * TimerConfig.isPersistent() returns whether the timer should survive server restarts.
                 * In Quartz, this is mapped to storeDurably(true) + requestRecovery(true).
                 */
                private J.MethodInvocation wrapWithIsPersistent(Expression configExpr) {
                    // Create: configExpr.isPersistent()
                    J.Identifier isPersistentIdent = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "isPersistent",
                        null,
                        null
                    );

                    return new J.MethodInvocation(
                        Tree.randomId(),
                        configExpr.getPrefix(),
                        Markers.EMPTY,
                        JRightPadded.build(configExpr.withPrefix(Space.EMPTY)),
                        null,  // no type params
                        isPersistentIdent,
                        JContainer.build(
                            Space.EMPTY,
                            Collections.emptyList(),
                            Markers.EMPTY
                        ),
                        null   // no method type
                    );
                }
            }.visitClassDeclaration(cd, new InMemoryExecutionContext());
        }

        private J.ClassDeclaration addSchedulingHelperMethods(J.ClassDeclaration cd, TimerUsageAnalysis analysis, String jobClassName) {
            // Add helper methods for Quartz job scheduling
            // These encapsulate the JobDetail/Trigger/scheduleJob pattern

            if (analysis.hasSingleTimer) {
                // Use JobDataMap to preserve original Serializable info object (not toString())
                // The 'persistent' flag maps EJB TimerConfig.isPersistent() to Quartz settings:
                // - storeDurably(true): Job definition survives even when not scheduled
                // - requestRecovery(true): Job re-executes if server crashes during execution
                String singleTimerHelper = String.format(
                    "private void scheduleQuartzJob(long delayMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {\n" +
                    "    try {\n" +
                    "        JobDataMap jobDataMap = new JobDataMap();\n" +
                    "        if (info != null) {\n" +
                    "            jobDataMap.put(\"info\", info);\n" +
                    "        }\n" +
                    "        JobBuilder jobBuilder = JobBuilder.newJob(jobClass)\n" +
                    "            .usingJobData(jobDataMap);\n" +
                    "        if (persistent) {\n" +
                    "            jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);\n" +
                    "        }\n" +
                    "        JobDetail job = jobBuilder.build();\n" +
                    "        Trigger trigger = TriggerBuilder.newTrigger()\n" +
                    "            .startAt(Date.from(Instant.now().plusMillis(delayMs)))\n" +
                    "            .build();\n" +
                    "        scheduler.scheduleJob(job, trigger);\n" +
                    "    } catch (SchedulerException e) {\n" +
                    "        throw new RuntimeException(\"Failed to schedule job\", e);\n" +
                    "    }\n" +
                    "}"
                );

                JavaTemplate helperTemplate = JavaTemplate.builder(singleTimerHelper)
                    .imports(QUARTZ_JOB_DETAIL_FQN, QUARTZ_JOB_BUILDER_FQN,
                             QUARTZ_TRIGGER_FQN, QUARTZ_TRIGGER_BUILDER_FQN,
                             QUARTZ_SCHEDULER_EXCEPTION_FQN, QUARTZ_JOB_FQN,
                             "org.quartz.JobDataMap",
                             "java.util.Date", "java.time.Instant")
                    .contextSensitive()
                    .build();

                cd = helperTemplate.apply(
                    new Cursor(getCursor(), cd),
                    cd.getBody().getCoordinates().lastStatement()
                );
            }

            if (analysis.hasIntervalTimer) {
                // Use JobDataMap to preserve original Serializable info object (not toString())
                // The 'persistent' flag maps EJB TimerConfig.isPersistent() to Quartz settings
                String intervalTimerHelper = String.format(
                    "private void scheduleQuartzIntervalJob(long initialDelayMs, long intervalMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {\n" +
                    "    try {\n" +
                    "        JobDataMap jobDataMap = new JobDataMap();\n" +
                    "        if (info != null) {\n" +
                    "            jobDataMap.put(\"info\", info);\n" +
                    "        }\n" +
                    "        JobBuilder jobBuilder = JobBuilder.newJob(jobClass)\n" +
                    "            .usingJobData(jobDataMap);\n" +
                    "        if (persistent) {\n" +
                    "            jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);\n" +
                    "        }\n" +
                    "        JobDetail job = jobBuilder.build();\n" +
                    "        Trigger trigger = TriggerBuilder.newTrigger()\n" +
                    "            .startAt(Date.from(Instant.now().plusMillis(initialDelayMs)))\n" +
                    "            .withSchedule(SimpleScheduleBuilder.simpleSchedule()\n" +
                    "                .withIntervalInMilliseconds(intervalMs)\n" +
                    "                .repeatForever())\n" +
                    "            .build();\n" +
                    "        scheduler.scheduleJob(job, trigger);\n" +
                    "    } catch (SchedulerException e) {\n" +
                    "        throw new RuntimeException(\"Failed to schedule interval job\", e);\n" +
                    "    }\n" +
                    "}"
                );

                JavaTemplate helperTemplate = JavaTemplate.builder(intervalTimerHelper)
                    .imports(QUARTZ_JOB_DETAIL_FQN, QUARTZ_JOB_BUILDER_FQN,
                             QUARTZ_TRIGGER_FQN, QUARTZ_TRIGGER_BUILDER_FQN,
                             QUARTZ_SIMPLE_SCHEDULE_BUILDER_FQN, QUARTZ_SCHEDULER_EXCEPTION_FQN,
                             QUARTZ_JOB_FQN, "org.quartz.JobDataMap",
                             "java.util.Date", "java.time.Instant")
                    .contextSensitive()
                    .build();

                cd = helperTemplate.apply(
                    new Cursor(getCursor(), cd),
                    cd.getBody().getCoordinates().lastStatement()
                );
            }

            // Always add Date overload for single timer when timer is used
            // This ensures Java method resolution works even if type attribution failed
            // (having an unused overload is better than a compile error)
            if (analysis.hasSingleTimer) {
                String dateTimerHelper =
                    "private void scheduleQuartzJob(java.util.Date expirationDate, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {\n" +
                    "    long delayMs = java.time.Duration.between(Instant.now(), expirationDate.toInstant()).toMillis();\n" +
                    "    if (delayMs < 0) delayMs = 0;\n" +
                    "    scheduleQuartzJob(delayMs, info, persistent, jobClass);\n" +
                    "}";

                JavaTemplate dateHelperTemplate = JavaTemplate.builder(dateTimerHelper)
                    .imports("java.util.Date", "java.time.Instant", "java.time.Duration")
                    .contextSensitive()
                    .build();

                cd = dateHelperTemplate.apply(
                    new Cursor(getCursor(), cd),
                    cd.getBody().getCoordinates().lastStatement()
                );
            }

            // Always add Duration overload for single timer when timer is used
            // This ensures Java method resolution works even if type attribution failed
            if (analysis.hasSingleTimer) {
                String durationTimerHelper =
                    "private void scheduleQuartzJob(java.time.Duration duration, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {\n" +
                    "    scheduleQuartzJob(duration.toMillis(), info, persistent, jobClass);\n" +
                    "}";

                JavaTemplate durationHelperTemplate = JavaTemplate.builder(durationTimerHelper)
                    .imports("java.time.Duration")
                    .contextSensitive()
                    .build();

                cd = durationHelperTemplate.apply(
                    new Cursor(getCursor(), cd),
                    cd.getBody().getCoordinates().lastStatement()
                );
            }

            // Always add Date overload for interval timer when interval timer is used
            // This ensures Java method resolution works even if type attribution failed
            if (analysis.hasIntervalTimer) {
                String dateIntervalHelper =
                    "private void scheduleQuartzIntervalJob(java.util.Date startDate, long intervalMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {\n" +
                    "    long delayMs = java.time.Duration.between(Instant.now(), startDate.toInstant()).toMillis();\n" +
                    "    if (delayMs < 0) delayMs = 0;\n" +
                    "    scheduleQuartzIntervalJob(delayMs, intervalMs, info, persistent, jobClass);\n" +
                    "}";

                JavaTemplate dateIntervalTemplate = JavaTemplate.builder(dateIntervalHelper)
                    .imports("java.util.Date", "java.time.Instant", "java.time.Duration")
                    .contextSensitive()
                    .build();

                cd = dateIntervalTemplate.apply(
                    new Cursor(getCursor(), cd),
                    cd.getBody().getCoordinates().lastStatement()
                );
            }

            // P1.4: Add cron-based scheduling helper for calendar timers
            // P1.6: Extended to support MigratedScheduleInfo for timer.getSchedule() transformation
            if (analysis.hasCalendarTimer && analysis.calendarTimerIsSafe) {
                String cronTimerHelper;
                String[] imports;

                if (analysis.canTransformGetSchedule) {
                    // P1.6: Include MigratedScheduleInfo creation for getSchedule() support
                    cronTimerHelper =
                        "private void scheduleQuartzCronJob(String cronExpression, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass,\n" +
                        "        String second, String minute, String hour, String dayOfMonth, String month, String dayOfWeek) {\n" +
                        "    try {\n" +
                        "        JobDataMap jobDataMap = new JobDataMap();\n" +
                        "        if (info != null) {\n" +
                        "            jobDataMap.put(\"info\", info);\n" +
                        "        }\n" +
                        "        // P1.6: Store schedule info for timer.getSchedule() compatibility\n" +
                        "        MigratedScheduleInfo scheduleInfo = new MigratedScheduleInfo(\n" +
                        "            second, minute, hour, dayOfMonth, month, dayOfWeek, cronExpression);\n" +
                        "        jobDataMap.put(\"scheduleInfo\", scheduleInfo);\n" +
                        "        JobBuilder jobBuilder = JobBuilder.newJob(jobClass)\n" +
                        "            .usingJobData(jobDataMap);\n" +
                        "        if (persistent) {\n" +
                        "            jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);\n" +
                        "        }\n" +
                        "        JobDetail job = jobBuilder.build();\n" +
                        "        Trigger trigger = TriggerBuilder.newTrigger()\n" +
                        "            .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))\n" +
                        "            .build();\n" +
                        "        scheduler.scheduleJob(job, trigger);\n" +
                        "    } catch (SchedulerException e) {\n" +
                        "        throw new RuntimeException(\"Failed to schedule cron job\", e);\n" +
                        "    }\n" +
                        "}";
                    imports = new String[]{QUARTZ_JOB_DETAIL_FQN, QUARTZ_JOB_BUILDER_FQN,
                                           QUARTZ_TRIGGER_FQN, QUARTZ_TRIGGER_BUILDER_FQN,
                                           QUARTZ_CRON_SCHEDULE_BUILDER_FQN, QUARTZ_SCHEDULER_EXCEPTION_FQN,
                                           QUARTZ_JOB_FQN, "org.quartz.JobDataMap", MIGRATED_SCHEDULE_INFO_FQN};
                } else {
                    // Original version without MigratedScheduleInfo
                    cronTimerHelper =
                        "private void scheduleQuartzCronJob(String cronExpression, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {\n" +
                        "    try {\n" +
                        "        JobDataMap jobDataMap = new JobDataMap();\n" +
                        "        if (info != null) {\n" +
                        "            jobDataMap.put(\"info\", info);\n" +
                        "        }\n" +
                        "        JobBuilder jobBuilder = JobBuilder.newJob(jobClass)\n" +
                        "            .usingJobData(jobDataMap);\n" +
                        "        if (persistent) {\n" +
                        "            jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);\n" +
                        "        }\n" +
                        "        JobDetail job = jobBuilder.build();\n" +
                        "        Trigger trigger = TriggerBuilder.newTrigger()\n" +
                        "            .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))\n" +
                        "            .build();\n" +
                        "        scheduler.scheduleJob(job, trigger);\n" +
                        "    } catch (SchedulerException e) {\n" +
                        "        throw new RuntimeException(\"Failed to schedule cron job\", e);\n" +
                        "    }\n" +
                        "}";
                    imports = new String[]{QUARTZ_JOB_DETAIL_FQN, QUARTZ_JOB_BUILDER_FQN,
                                           QUARTZ_TRIGGER_FQN, QUARTZ_TRIGGER_BUILDER_FQN,
                                           QUARTZ_CRON_SCHEDULE_BUILDER_FQN, QUARTZ_SCHEDULER_EXCEPTION_FQN,
                                           QUARTZ_JOB_FQN, "org.quartz.JobDataMap"};
                }

                JavaTemplate cronHelperTemplate = JavaTemplate.builder(cronTimerHelper)
                    .imports(imports)
                    .contextSensitive()
                    .build();

                cd = cronHelperTemplate.apply(
                    new Cursor(getCursor(), cd),
                    cd.getBody().getCoordinates().lastStatement()
                );
            }

            return cd;
        }

        /**
         * Adds a public entry point method for scheduling this bean's Job when timer.getInfo() is used
         * but there's no local timer creation (createTimer/createSingleActionTimer/createIntervalTimer).
         *
         * This ensures that external callers have a way to schedule the job with proper JobDataMap
         * population, so that getMergedJobDataMap().get("info") returns the correct value.
         */
        private J.ClassDeclaration addExternalSchedulingEntryPoint(J.ClassDeclaration cd, String jobClassName) {
            // Generate a public method for external scheduling with info parameter
            // This provides an entry point for code that previously created timers for this bean
            String entryPointHelper = String.format(
                "/**\n" +
                " * Schedule this bean's job with info object for external callers.\n" +
                " * Use this method when scheduling from another class that needs to pass timer info.\n" +
                " * The info object will be available via context.getMergedJobDataMap().get(\"info\") in the job.\n" +
                " * @param scheduler the Quartz scheduler to use\n" +
                " * @param delayMs delay in milliseconds before job execution\n" +
                " * @param info the info object (formerly Timer.getInfo())\n" +
                " */\n" +
                "public void scheduleJobWithInfo(Scheduler scheduler, long delayMs, Object info) {\n" +
                "    try {\n" +
                "        JobDataMap jobDataMap = new JobDataMap();\n" +
                "        if (info != null) {\n" +
                "            jobDataMap.put(\"info\", info);\n" +
                "        }\n" +
                "        JobDetail job = JobBuilder.newJob(%s.class)\n" +
                "            .usingJobData(jobDataMap)\n" +
                "            .build();\n" +
                "        Trigger trigger = TriggerBuilder.newTrigger()\n" +
                "            .startAt(Date.from(Instant.now().plusMillis(delayMs)))\n" +
                "            .build();\n" +
                "        scheduler.scheduleJob(job, trigger);\n" +
                "    } catch (SchedulerException e) {\n" +
                "        throw new RuntimeException(\"Failed to schedule job\", e);\n" +
                "    }\n" +
                "}",
                jobClassName
            );

            JavaTemplate entryPointTemplate = JavaTemplate.builder(entryPointHelper)
                .imports(QUARTZ_SCHEDULER_FQN, QUARTZ_JOB_DETAIL_FQN, QUARTZ_JOB_BUILDER_FQN,
                         QUARTZ_TRIGGER_FQN, QUARTZ_TRIGGER_BUILDER_FQN, QUARTZ_SCHEDULER_EXCEPTION_FQN,
                         "org.quartz.JobDataMap", "java.util.Date", "java.time.Instant")
                .contextSensitive()
                .build();

            return entryPointTemplate.apply(
                new Cursor(getCursor(), cd),
                cd.getBody().getCoordinates().lastStatement()
            );
        }

        private J.ClassDeclaration transformTimeoutMethod(J.ClassDeclaration cd, TimerUsageAnalysis analysis) {
            List<Statement> newStatements = new ArrayList<>();

            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    if (hasTimeoutAnnotation(md)) {
                        // Remove @Timeout annotation
                        List<J.Annotation> newAnnotations = new ArrayList<>();
                        for (J.Annotation ann : md.getLeadingAnnotations()) {
                            if (!isTimeoutAnnotation(ann)) {
                                newAnnotations.add(ann);
                            }
                        }
                        md = md.withLeadingAnnotations(newAnnotations);

                        // If method has Timer parameter, change it to JobExecutionContext
                        if (analysis.timeoutHasTimerParam) {
                            md = transformTimerParameter(md);
                            transformedTimerToJobContext = true; // Flag for import removal
                        }

                        // Transform Timer.getInfo() calls in method body
                        if (analysis.usesTimerInfo && md.getBody() != null) {
                            md = transformTimerGetInfoCalls(md, analysis.timerParamName);
                        }

                        // P1.5: Transform Timer.getHandle() calls in method body (local handles only)
                        if (analysis.usesTimerHandle && !analysis.timerHandleEscapes && md.getBody() != null) {
                            md = transformTimerGetHandleCalls(md, analysis.timerParamName, analysis.localTimerHandleVariables);
                        }

                        // P1.6: Transform Timer.getSchedule() calls in method body
                        if (analysis.canTransformGetSchedule && md.getBody() != null) {
                            md = transformTimerGetScheduleCalls(md, analysis.timerParamName);
                        }
                    }
                    newStatements.add(md);
                } else {
                    newStatements.add(stmt);
                }
            }

            return cd.withBody(cd.getBody().withStatements(newStatements));
        }

        private J.MethodDeclaration transformTimerParameter(J.MethodDeclaration md) {
            List<Statement> newParams = new ArrayList<>();

            for (Statement param : md.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    if (isTimerParameter(vd)) {
                        // Change Timer to JobExecutionContext but KEEP original variable name
                        // This is critical: renaming only the type ensures all other uses of the
                        // parameter (logging, comparisons, etc.) remain valid
                        TypeTree typeExpr = vd.getTypeExpression();
                        if (typeExpr instanceof J.Identifier) {
                            J.Identifier ident = (J.Identifier) typeExpr;
                            JavaType.ShallowClass contextType = JavaType.ShallowClass.build(QUARTZ_JOB_EXECUTION_CONTEXT_FQN);
                            ident = ident.withSimpleName("JobExecutionContext").withType(contextType);
                            vd = vd.withTypeExpression(ident);

                            // Update variable type but KEEP original name
                            List<J.VariableDeclarations.NamedVariable> updatedVars = new ArrayList<>();
                            for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                                String originalName = var.getSimpleName(); // preserve original name
                                // Update the VariableType (needed for the identifier's fieldType)
                                JavaType.Variable newVarType = null;
                                if (var.getVariableType() != null) {
                                    newVarType = var.getVariableType()
                                        .withType(contextType)
                                        .withName(originalName); // keep original name
                                    var = var.withVariableType(newVarType);
                                }
                                // Update the identifier's type but KEEP original name
                                J.Identifier nameIdent = var.getName();
                                nameIdent = nameIdent.withSimpleName(originalName) // keep original name
                                    .withType(contextType)
                                    .withFieldType(newVarType);
                                var = var.withName(nameIdent);
                                updatedVars.add(var);
                            }
                            vd = vd.withVariables(updatedVars);
                        }
                    }
                    newParams.add(vd);
                } else {
                    newParams.add(param);
                }
            }

            return md.withParameters(newParams);
        }

        private J.MethodDeclaration transformTimerGetInfoCalls(J.MethodDeclaration md, String timerParamName) {
            // Replace timer.getInfo() with timer.getMergedJobDataMap().get("info")
            // Using the original parameter name (not renaming to "context")
            final String finalTimerParamName = timerParamName;

            return (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext executionCtx) {
                    mi = super.visitMethodInvocation(mi, executionCtx);

                    if ("getInfo".equals(mi.getSimpleName()) && mi.getSelect() != null) {
                        Expression select = mi.getSelect();
                        // Check if it's a call on the timer parameter
                        if (select instanceof J.Identifier) {
                            String selectName = ((J.Identifier) select).getSimpleName();
                            if (finalTimerParamName.equals(selectName) || "timer".equals(selectName)) {
                                // Build: timer.getMergedJobDataMap().get("info")
                                // First: timer.getMergedJobDataMap()
                                J.Identifier getMergedJobDataMapName = new J.Identifier(
                                    Tree.randomId(),
                                    Space.EMPTY,
                                    Markers.EMPTY,
                                    Collections.emptyList(),
                                    "getMergedJobDataMap",
                                    null,
                                    null
                                );

                                J.MethodInvocation getMergedCall = new J.MethodInvocation(
                                    Tree.randomId(),
                                    Space.EMPTY,
                                    Markers.EMPTY,
                                    JRightPadded.build(select.withPrefix(Space.EMPTY)),
                                    null,
                                    getMergedJobDataMapName,
                                    JContainer.empty(),
                                    null
                                );

                                // Then: getMergedJobDataMap().get("info")
                                J.Identifier getName = new J.Identifier(
                                    Tree.randomId(),
                                    Space.EMPTY,
                                    Markers.EMPTY,
                                    Collections.emptyList(),
                                    "get",
                                    null,
                                    null
                                );

                                J.Literal infoLiteral = new J.Literal(
                                    Tree.randomId(),
                                    Space.EMPTY,
                                    Markers.EMPTY,
                                    "info",
                                    "\"info\"",
                                    null,
                                    JavaType.Primitive.String
                                );

                                List<JRightPadded<Expression>> getArgs = new ArrayList<>();
                                getArgs.add(JRightPadded.build((Expression) infoLiteral).withAfter(Space.EMPTY));

                                JContainer<Expression> getArgsContainer = JContainer.build(
                                    Space.EMPTY,
                                    getArgs,
                                    Markers.EMPTY
                                );

                                J.MethodInvocation getCall = new J.MethodInvocation(
                                    Tree.randomId(),
                                    mi.getPrefix(),
                                    Markers.EMPTY,
                                    JRightPadded.build(getMergedCall),
                                    null,
                                    getName,
                                    getArgsContainer,
                                    null
                                );

                                return getCall;
                            }
                        }
                    }
                    return mi;
                }
            }.visitMethodDeclaration(md, new InMemoryExecutionContext());
        }

        /**
         * P1.6: Transform Timer.getSchedule() calls to MigratedScheduleInfo access.
         * Transforms:
         * - timer.getSchedule() -> (MigratedScheduleInfo) timer.getMergedJobDataMap().get("scheduleInfo")
         * - timer.getSchedule().getHour() -> ((MigratedScheduleInfo) timer.getMergedJobDataMap().get("scheduleInfo")).getHour()
         *
         * @param md the method declaration to transform
         * @param timerParamName the name of the Timer parameter (now JobExecutionContext)
         * @return the transformed method declaration
         */
        private J.MethodDeclaration transformTimerGetScheduleCalls(J.MethodDeclaration md, String timerParamName) {
            final String finalTimerParamName = timerParamName;

            return (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext executionCtx) {
                    mi = super.visitMethodInvocation(mi, executionCtx);

                    // Pattern 1: timer.getSchedule().getXxx() - chained call
                    // The select is timer.getSchedule(), we need to transform the whole chain
                    if (mi.getSelect() instanceof J.MethodInvocation) {
                        J.MethodInvocation selectMi = (J.MethodInvocation) mi.getSelect();
                        if ("getSchedule".equals(selectMi.getSimpleName()) && selectMi.getSelect() != null) {
                            Expression selectSelect = selectMi.getSelect();
                            if (selectSelect instanceof J.Identifier) {
                                String selectName = ((J.Identifier) selectSelect).getSimpleName();
                                if (finalTimerParamName.equals(selectName) || "timer".equals(selectName)) {
                                    // Build: ((MigratedScheduleInfo) timer.getMergedJobDataMap().get("scheduleInfo")).getXxx()
                                    Expression scheduleInfoAccess = buildScheduleInfoAccess(selectSelect);

                                    // Create parenthesized cast: (scheduleInfoAccess)
                                    J.Parentheses<Expression> parens = new J.Parentheses<>(
                                        Tree.randomId(),
                                        Space.EMPTY,
                                        Markers.EMPTY,
                                        JRightPadded.build(scheduleInfoAccess)
                                    );

                                    // Now call the original getter method on the cast result
                                    return mi.withSelect(parens);
                                }
                            }
                        }
                    }

                    // Note: Pattern 2 (timer.getSchedule() standalone) is handled in visitVariableDeclarations below
                    return mi;
                }

                /**
                 * P1.6 Review 1: Pattern 2 - Transform direct variable assignments.
                 * ScheduleExpression s = timer.getSchedule() -> MigratedScheduleInfo s = (MigratedScheduleInfo) timer.getMergedJobDataMap().get("scheduleInfo")
                 */
                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext ctx) {
                    vd = super.visitVariableDeclarations(vd, ctx);

                    // Check if this is a ScheduleExpression variable declaration
                    TypeTree typeExpr = vd.getTypeExpression();
                    if (typeExpr != null && isScheduleExpressionType(typeExpr)) {
                        // Change type from ScheduleExpression to MigratedScheduleInfo
                        JavaType.ShallowClass scheduleInfoType = JavaType.ShallowClass.build(MIGRATED_SCHEDULE_INFO_FQN);
                        if (typeExpr instanceof J.Identifier) {
                            J.Identifier ident = (J.Identifier) typeExpr;
                            ident = ident.withSimpleName("MigratedScheduleInfo").withType(scheduleInfoType);
                            vd = vd.withTypeExpression(ident);
                        }

                        // Transform the initializer if it's timer.getSchedule()
                        List<J.VariableDeclarations.NamedVariable> updatedVars = new ArrayList<>();
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            Expression init = var.getInitializer();
                            if (init instanceof J.MethodInvocation) {
                                J.MethodInvocation mi = (J.MethodInvocation) init;
                                if ("getSchedule".equals(mi.getSimpleName()) && mi.getSelect() != null) {
                                    Expression select = mi.getSelect();
                                    if (select instanceof J.Identifier) {
                                        String selectName = ((J.Identifier) select).getSimpleName();
                                        if (finalTimerParamName.equals(selectName) || "timer".equals(selectName)) {
                                            // Transform timer.getSchedule() to (MigratedScheduleInfo) timer.getMergedJobDataMap().get("scheduleInfo")
                                            Expression newInit = buildScheduleInfoAccess(select);
                                            var = var.withInitializer(newInit);
                                        }
                                    }
                                }
                            }
                            updatedVars.add(var);
                        }
                        vd = vd.withVariables(updatedVars);
                    }

                    return vd;
                }

                /**
                 * Check if a type expression refers to ScheduleExpression.
                 */
                private boolean isScheduleExpressionType(TypeTree typeExpr) {
                    if (typeExpr instanceof J.Identifier) {
                        String name = ((J.Identifier) typeExpr).getSimpleName();
                        return "ScheduleExpression".equals(name);
                    }
                    if (typeExpr instanceof J.FieldAccess) {
                        J.FieldAccess fa = (J.FieldAccess) typeExpr;
                        return "ScheduleExpression".equals(fa.getSimpleName());
                    }
                    return false;
                }

                /**
                 * Builds the expression: (MigratedScheduleInfo) timerParam.getMergedJobDataMap().get("scheduleInfo")
                 */
                private J.TypeCast buildScheduleInfoAccess(Expression timerParam) {
                    // Build: timerParam.getMergedJobDataMap()
                    J.Identifier getMergedJobDataMapName = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "getMergedJobDataMap",
                        null,
                        null
                    );

                    J.MethodInvocation getMergedCall = new J.MethodInvocation(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        JRightPadded.build(timerParam.withPrefix(Space.EMPTY)),
                        null,
                        getMergedJobDataMapName,
                        JContainer.empty(),
                        null
                    );

                    // Build: getMergedJobDataMap().get("scheduleInfo")
                    J.Identifier getName = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "get",
                        null,
                        null
                    );

                    J.Literal scheduleInfoLiteral = new J.Literal(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        "scheduleInfo",
                        "\"scheduleInfo\"",
                        null,
                        JavaType.Primitive.String
                    );

                    List<JRightPadded<Expression>> getArgs = new ArrayList<>();
                    getArgs.add(JRightPadded.build((Expression) scheduleInfoLiteral).withAfter(Space.EMPTY));

                    JContainer<Expression> getArgsContainer = JContainer.build(
                        Space.EMPTY,
                        getArgs,
                        Markers.EMPTY
                    );

                    J.MethodInvocation getCall = new J.MethodInvocation(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        JRightPadded.build(getMergedCall),
                        null,
                        getName,
                        getArgsContainer,
                        null
                    );

                    // Build the cast: (MigratedScheduleInfo) getCall
                    JavaType.ShallowClass scheduleInfoType = JavaType.ShallowClass.build(MIGRATED_SCHEDULE_INFO_FQN);
                    J.Identifier castTypeIdent = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "MigratedScheduleInfo",
                        scheduleInfoType,
                        null
                    );

                    J.ControlParentheses<TypeTree> castControl = new J.ControlParentheses<>(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        JRightPadded.build((TypeTree) castTypeIdent)
                    );

                    return new J.TypeCast(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        castControl,
                        getCall
                    );
                }
            }.visitMethodDeclaration(md, new InMemoryExecutionContext());
        }

        /**
         * P1.5: Transform Timer.getHandle() calls to MigratedTimerHandle constructor.
         * Also transforms:
         * - TimerHandle h = timer.getHandle() -> MigratedTimerHandle h = new MigratedTimerHandle(...)
         * - handle.getTimer().cancel() -> scheduler.deleteJob(new JobKey(...))
         *
         * @param md the method declaration to transform
         * @param timerParamName the name of the Timer parameter (now JobExecutionContext)
         * @param localHandleVars set of local variable names holding TimerHandle references
         * @return the transformed method declaration
         */
        private J.MethodDeclaration transformTimerGetHandleCalls(J.MethodDeclaration md, String timerParamName, Set<String> localHandleVars) {
            final String finalTimerParamName = timerParamName;

            return (J.MethodDeclaration) new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext ctx) {
                    vd = super.visitVariableDeclarations(vd, ctx);

                    // Check if this is a TimerHandle variable declaration
                    TypeTree typeExpr = vd.getTypeExpression();
                    if (typeExpr != null && isTimerHandleType(typeExpr)) {
                        // Change type from TimerHandle to MigratedTimerHandle
                        JavaType.ShallowClass handleType = JavaType.ShallowClass.build(MIGRATED_TIMER_HANDLE_FQN);
                        if (typeExpr instanceof J.Identifier) {
                            J.Identifier ident = (J.Identifier) typeExpr;
                            ident = ident.withSimpleName("MigratedTimerHandle").withType(handleType);
                            vd = vd.withTypeExpression(ident);
                        }

                        // Transform the initializer if it's timer.getHandle()
                        List<J.VariableDeclarations.NamedVariable> updatedVars = new ArrayList<>();
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            Expression init = var.getInitializer();
                            if (init instanceof J.MethodInvocation) {
                                J.MethodInvocation mi = (J.MethodInvocation) init;
                                if ("getHandle".equals(mi.getSimpleName())) {
                                    // Transform timer.getHandle() to new MigratedTimerHandle(...)
                                    Expression newInit = buildMigratedTimerHandleConstructor(mi, finalTimerParamName);
                                    var = var.withInitializer(newInit);
                                }
                            }
                            updatedVars.add(var);
                        }
                        vd = vd.withVariables(updatedVars);
                    }

                    return vd;
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, ExecutionContext ctx) {
                    mi = super.visitMethodInvocation(mi, ctx);

                    // Transform handle.getTimer().cancel() to scheduler.deleteJob(new JobKey(...))
                    if ("cancel".equals(mi.getSimpleName()) && mi.getSelect() != null) {
                        Expression select = mi.getSelect();
                        // Check if it's handle.getTimer().cancel()
                        if (select instanceof J.MethodInvocation) {
                            J.MethodInvocation getTimerCall = (J.MethodInvocation) select;
                            if ("getTimer".equals(getTimerCall.getSimpleName()) && getTimerCall.getSelect() != null) {
                                Expression handleRef = getTimerCall.getSelect();
                                // Verify handleRef is a local handle variable
                                if (handleRef instanceof J.Identifier) {
                                    String varName = ((J.Identifier) handleRef).getSimpleName();
                                    if (localHandleVars.contains(varName)) {
                                        // Build: scheduler.deleteJob(new JobKey(handle.getJobName(), handle.getJobGroup()))
                                        return buildSchedulerDeleteJob(mi, varName);
                                    }
                                }
                            }
                        }
                    }

                    return mi;
                }

                private boolean isTimerHandleType(TypeTree typeExpr) {
                    String typeName = extractSimpleTypeName(typeExpr);
                    return "TimerHandle".equals(typeName);
                }

                /**
                 * Build: new MigratedTimerHandle(timer.getJobDetail().getKey().getName(), timer.getJobDetail().getKey().getGroup())
                 */
                private Expression buildMigratedTimerHandleConstructor(J.MethodInvocation originalGetHandle, String timerParamName) {
                    // The timer parameter is now JobExecutionContext
                    // Build: timer.getJobDetail().getKey()
                    J.Identifier timerIdent = new J.Identifier(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                        timerParamName, null, null
                    );

                    // timer.getJobDetail()
                    J.MethodInvocation getJobDetail = new J.MethodInvocation(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        JRightPadded.build(timerIdent),
                        null,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "getJobDetail", null, null),
                        JContainer.empty(),
                        null
                    );

                    // timer.getJobDetail().getKey()
                    J.MethodInvocation getKey = new J.MethodInvocation(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        JRightPadded.build(getJobDetail),
                        null,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "getKey", null, null),
                        JContainer.empty(),
                        null
                    );

                    // timer.getJobDetail().getKey().getName()
                    J.MethodInvocation getName = new J.MethodInvocation(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        JRightPadded.build(getKey),
                        null,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "getName", null, null),
                        JContainer.empty(),
                        null
                    );

                    // timer.getJobDetail().getKey().getGroup() (need fresh copy of getKey chain)
                    J.Identifier timerIdent2 = new J.Identifier(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                        timerParamName, null, null
                    );
                    J.MethodInvocation getJobDetail2 = new J.MethodInvocation(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        JRightPadded.build(timerIdent2),
                        null,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "getJobDetail", null, null),
                        JContainer.empty(),
                        null
                    );
                    J.MethodInvocation getKey2 = new J.MethodInvocation(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        JRightPadded.build(getJobDetail2),
                        null,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "getKey", null, null),
                        JContainer.empty(),
                        null
                    );
                    J.MethodInvocation getGroup = new J.MethodInvocation(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        JRightPadded.build(getKey2),
                        null,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "getGroup", null, null),
                        JContainer.empty(),
                        null
                    );

                    // new MigratedTimerHandle(getName, getGroup)
                    // The second argument needs a space prefix for proper formatting
                    J.MethodInvocation getGroupWithSpace = getGroup.withPrefix(Space.SINGLE_SPACE);
                    List<JRightPadded<Expression>> args = new ArrayList<>();
                    args.add(JRightPadded.build((Expression) getName).withAfter(Space.EMPTY));
                    args.add(JRightPadded.build((Expression) getGroupWithSpace).withAfter(Space.EMPTY));

                    JavaType.ShallowClass handleType = JavaType.ShallowClass.build(MIGRATED_TIMER_HANDLE_FQN);
                    J.Identifier classIdent = new J.Identifier(
                        Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, Collections.emptyList(),
                        "MigratedTimerHandle", handleType, null
                    );

                    return new J.NewClass(
                        Tree.randomId(),
                        originalGetHandle.getPrefix(),
                        Markers.EMPTY,
                        null,
                        Space.EMPTY,
                        classIdent,
                        JContainer.build(Space.EMPTY, args, Markers.EMPTY),
                        null,
                        null  // constructor type not needed for AST construction
                    );
                }

                /**
                 * Build: scheduler.deleteJob(new JobKey(handle.getJobName(), handle.getJobGroup()))
                 */
                private J.MethodInvocation buildSchedulerDeleteJob(J.MethodInvocation originalCancel, String handleVarName) {
                    // handle.getJobName()
                    J.Identifier handleIdent = new J.Identifier(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                        handleVarName, null, null
                    );
                    J.MethodInvocation getJobName = new J.MethodInvocation(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        JRightPadded.build(handleIdent),
                        null,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "getJobName", null, null),
                        JContainer.empty(),
                        null
                    );

                    // handle.getJobGroup() - with leading space after comma
                    J.Identifier handleIdent2 = new J.Identifier(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                        handleVarName, null, null
                    );
                    J.MethodInvocation getJobGroup = new J.MethodInvocation(
                        Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY,  // Add space before second arg
                        JRightPadded.build(handleIdent2),
                        null,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "getJobGroup", null, null),
                        JContainer.empty(),
                        null
                    );

                    // new JobKey(getJobName, getJobGroup)
                    List<JRightPadded<Expression>> jobKeyArgs = new ArrayList<>();
                    jobKeyArgs.add(JRightPadded.build((Expression) getJobName).withAfter(Space.EMPTY));
                    jobKeyArgs.add(JRightPadded.build((Expression) getJobGroup).withAfter(Space.EMPTY));

                    J.Identifier jobKeyIdent = new J.Identifier(
                        Tree.randomId(), Space.SINGLE_SPACE, Markers.EMPTY, Collections.emptyList(),
                        "JobKey", null, null
                    );

                    J.NewClass jobKeyNew = new J.NewClass(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY, null, Space.EMPTY,
                        jobKeyIdent, JContainer.build(Space.EMPTY, jobKeyArgs, Markers.EMPTY),
                        null, null
                    );

                    // scheduler.deleteJob(jobKeyNew)
                    J.Identifier schedulerIdent = new J.Identifier(
                        Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                        "scheduler", null, null
                    );

                    List<JRightPadded<Expression>> deleteJobArgs = new ArrayList<>();
                    deleteJobArgs.add(JRightPadded.build((Expression) jobKeyNew).withAfter(Space.EMPTY));

                    return new J.MethodInvocation(
                        Tree.randomId(),
                        originalCancel.getPrefix(),
                        Markers.EMPTY,
                        JRightPadded.build(schedulerIdent),
                        null,
                        new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(), "deleteJob", null, null),
                        JContainer.build(Space.EMPTY, deleteJobArgs, Markers.EMPTY),
                        null
                    );
                }
            }.visitMethodDeclaration(md, new InMemoryExecutionContext());
        }

        private J.ClassDeclaration applyFallbackMarker(J.ClassDeclaration cd, TimerUsageAnalysis analysis, ExecutionContext ctx) {
            // WFQ-009: First add @NeedsReview for explicit manual migration requirement
            J.ClassDeclaration result = addNeedsReviewForTimerFallback(cd, analysis, ctx);

            // Then add the detailed @EjbQuartzTimerService marker
            String annotationStr = buildMarkerAnnotationString(analysis);

            JavaTemplate template = JavaTemplate.builder(annotationStr)
                .javaParser(JavaParser.fromJavaVersion().dependsOn(EJB_QUARTZ_TIMER_SERVICE_STUB))
                .imports(EJB_QUARTZ_TIMER_SERVICE_FQN)
                .build();

            doAfterVisit(new AddImport<>(EJB_QUARTZ_TIMER_SERVICE_FQN, null, false));

            Comparator<J.Annotation> markerFirst = (a, b) -> {
                boolean aIsMarker = "EjbQuartzTimerService".equals(a.getSimpleName());
                boolean bIsMarker = "EjbQuartzTimerService".equals(b.getSimpleName());
                if (aIsMarker && !bIsMarker) return -1;
                if (!aIsMarker && bIsMarker) return 1;
                return 0;
            };

            result = template.apply(new Cursor(getCursor(), result), result.getCoordinates().addAnnotation(markerFirst));

            // WFQ-017: Also remove EJB Timer API in fallback cases to meet acceptance criteria
            // "Keine jakarta.ejb.Timer* Imports bei Quartz-Strategie"
            result = cleanupEjbTimerApiInFallback(result, analysis);

            // Remove EJB Timer imports (will be cleaned up by RemoveUnusedImports)
            maybeRemoveImport(JAKARTA_TIMER_SERVICE);
            maybeRemoveImport(JAVAX_TIMER_SERVICE);
            maybeRemoveImport(JAKARTA_TIMEOUT);
            maybeRemoveImport(JAVAX_TIMEOUT);
            maybeRemoveImport(JAKARTA_TIMER);
            maybeRemoveImport(JAVAX_TIMER);
            maybeRemoveImport(JAKARTA_TIMER_CONFIG);
            maybeRemoveImport(JAVAX_TIMER_CONFIG);
            maybeRemoveImport(JAKARTA_TIMER_HANDLE);
            maybeRemoveImport(JAVAX_TIMER_HANDLE);
            maybeRemoveImport("jakarta.ejb.ScheduleExpression");
            maybeRemoveImport("javax.ejb.ScheduleExpression");
            // Also remove @Resource if no longer used
            maybeRemoveImport(JAKARTA_RESOURCE);
            maybeRemoveImport(JAVAX_RESOURCE);

            // Clean up unused imports at the end
            doAfterVisit(new RemoveUnusedImports().getVisitor());

            return result;
        }

        /**
         * WFQ-017: Clean up EJB Timer API in fallback mode.
         * Removes TimerService field, @Timeout annotation, Timer parameter,
         * and replaces method bodies that use Timer API with throw statements.
         * This ensures no jakarta.ejb.Timer* imports remain.
         */
        private J.ClassDeclaration cleanupEjbTimerApiInFallback(J.ClassDeclaration cd, TimerUsageAnalysis analysis) {
            List<Statement> newStatements = new ArrayList<>();

            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (isTimerServiceField(vd) || isTimerHandleField(vd)) {
                        // Remove TimerService/TimerHandle fields entirely (info preserved in @NeedsReview originalCode)
                        continue;
                    }
                    newStatements.add(stmt);
                } else if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    boolean isTimeoutMethod = hasTimeoutAnnotation(md);
                    boolean usesTimerApi = methodUsesTimerApi(md, analysis.timerServiceFieldNames);

                    if (isTimeoutMethod) {
                        // Remove @Timeout annotation
                        List<J.Annotation> newAnnotations = new ArrayList<>();
                        for (J.Annotation ann : md.getLeadingAnnotations()) {
                            if (!isTimeoutAnnotation(ann)) {
                                newAnnotations.add(ann);
                            }
                        }
                        md = md.withLeadingAnnotations(newAnnotations);

                        // Remove Timer and TimerHandle parameters
                        if (hasTimerParameter(md) || hasTimerHandleParameter(md)) {
                            md = removeTimerParameter(md);
                        }

                        // For @Timeout methods in fallback mode, ALWAYS empty the body
                        // The @NeedsReview on the class indicates manual migration is needed
                        // and the Timer parameter removal makes original body invalid anyway
                        if (md.getBody() != null) {
                            md = replaceBodyWithEmptyBlock(md);
                        }
                    } else if (usesTimerApi || hasTimerParameter(md) || hasTimerHandleParameter(md)) {
                        // For non-@Timeout methods that use Timer API or have Timer/TimerHandle parameters,
                        // remove the parameters and empty the body
                        if (hasTimerParameter(md) || hasTimerHandleParameter(md)) {
                            md = removeTimerParameter(md);
                        }
                        if (md.getBody() != null) {
                            md = replaceBodyWithEmptyBlock(md);
                        }
                    }

                    newStatements.add(md);
                } else {
                    newStatements.add(stmt);
                }
            }

            return cd.withBody(cd.getBody().withStatements(newStatements));
        }

        /**
         * WFQ-017: Check if a method uses Timer API by scanning its source code.
         * Uses string-based detection to avoid cursor context issues.
         */
        private boolean methodUsesTimerApi(J.MethodDeclaration md, Set<String> timerServiceFieldNames) {
            if (md.getBody() == null) return false;

            // Get the method body as string - use printTrimmed for cleaner output
            // Note: We print the entire method declaration to ensure we capture all code,
            // then check for Timer API patterns in it
            String bodySource = md.printTrimmed(getCursor());

            // Check for TimerService method calls
            for (String fieldName : timerServiceFieldNames) {
                if (bodySource.contains(fieldName + ".")) {
                    return true;
                }
            }

            // Check common patterns
            if (bodySource.contains("timerService.") ||
                bodySource.contains("TimerService") ||
                bodySource.contains("createTimer(") ||
                bodySource.contains("createIntervalTimer(") ||
                bodySource.contains("createCalendarTimer(") ||
                bodySource.contains("getTimers(")) {
                return true;
            }

            // Check for Timer API usage
            if (bodySource.contains("timer.getInfo(") ||
                bodySource.contains("timer.cancel(") ||
                bodySource.contains("timer.getSchedule(") ||
                bodySource.contains("timer.getHandle(") ||
                bodySource.contains("timer.getNextTimeout(") ||
                bodySource.contains("timer.getTimeRemaining(") ||
                bodySource.contains(".getInfo(") ||
                bodySource.contains(".cancel(") ||
                bodySource.contains(".getSchedule(") ||
                bodySource.contains(".getHandle(") ||
                bodySource.contains(".getNextTimeout(") ||
                bodySource.contains(".getTimeRemaining(")) {
                return true;
            }

            // Check for Timer-related constructors
            if (bodySource.contains("new ScheduleExpression(") ||
                bodySource.contains("new TimerConfig(")) {
                return true;
            }

            // Check for TimerHandle usage in method body only (not signature)
            // Get body-only source for TimerHandle detection to avoid matching return types
            String bodyOnlySource = md.getBody().printTrimmed(getCursor());
            if (bodyOnlySource.contains(".getTimer(") ||
                bodyOnlySource.contains("handle.getTimer(")) {
                return true;
            }

            return false;
        }

        /**
         * WFQ-017: Replace method body with an empty block.
         * This removes all Timer API references. The @NeedsReview annotation
         * indicates manual migration is needed.
         */
        private J.MethodDeclaration replaceBodyWithEmptyBlock(J.MethodDeclaration md) {
            // Create an empty block body - the @NeedsReview annotation on the class
            // already indicates manual migration is required
            J.Block emptyBody = new J.Block(
                Tree.randomId(),
                md.getBody() != null ? md.getBody().getPrefix() : Space.EMPTY,
                Markers.EMPTY,
                JRightPadded.build(false),
                Collections.emptyList(),
                Space.format("\n    ")
            );

            return md.withBody(emptyBody);
        }

        /**
         * WFQ-017: Remove Timer parameter from method declaration.
         * Used in fallback mode to ensure no Timer type references remain.
         */
        /**
         * WFQ-017: Remove Timer and TimerHandle parameters from @Timeout methods in fallback mode.
         */
        private J.MethodDeclaration removeTimerParameter(J.MethodDeclaration md) {
            List<Statement> newParams = new ArrayList<>();

            for (Statement param : md.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    // Remove both Timer and TimerHandle parameters
                    if (!isTimerParameter(vd) && !isTimerHandleParameter(vd)) {
                        newParams.add(vd);
                    }
                    // Timer/TimerHandle parameters are simply omitted
                } else if (!(param instanceof J.Empty)) {
                    newParams.add(param);
                }
            }

            // If all parameters were removed, add empty params marker
            if (newParams.isEmpty()) {
                newParams.add(new J.Empty(Tree.randomId(), Space.EMPTY, Markers.EMPTY));
            }

            return md.withParameters(newParams);
        }

        /**
         * WFQ-009: Add @NeedsReview annotation when timer migration falls back to marker.
         * This makes the manual migration requirement explicit and visible in migration reports.
         */
        private J.ClassDeclaration addNeedsReviewForTimerFallback(J.ClassDeclaration cd, TimerUsageAnalysis analysis, ExecutionContext ctx) {
            // Check if already has timer-related @NeedsReview
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if ("NeedsReview".equals(ann.getSimpleName())) {
                    // Only skip if this @NeedsReview is already timer-related
                    if (isTimerRelatedNeedsReview(ann)) {
                        return cd;
                    }
                    // Otherwise, continue to add a separate timer @NeedsReview
                }
            }

            // Build reason based on analysis
            String reason = buildNeedsReviewReason(analysis);
            String suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. " +
                "See @EjbQuartzTimerService annotation for specific timer usage patterns.";

            String annotationStr = String.format(
                "@NeedsReview(reason = \"%s\", " +
                "category = NeedsReview.Category.TIMER, " +
                "originalCode = \"TimerService/%s\", " +
                "suggestedAction = \"%s\")",
                reason,
                analysis.getTimerPattern(),
                suggestedAction
            );

            JavaTemplate template = JavaTemplate.builder(annotationStr)
                .javaParser(JavaParser.fromJavaVersion().dependsOn(NEEDS_REVIEW_STUB))
                .imports(NEEDS_REVIEW_FQN)
                .build();

            doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN, null, false));

            return template.apply(
                new Cursor(getCursor(), cd),
                cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
            );
        }

        /**
         * Check if a @NeedsReview annotation is timer-related.
         * Timer-related annotations are identified by:
         * - originalCode starting with "TimerService/" AND
         * - (suggestedAction contains "Quartz Scheduler pattern" OR
         *    reason starts with "EJB Timer requires manual Quartz migration")
         * This ensures we only match annotations from this recipe's fallback.
         */
        private boolean isTimerRelatedNeedsReview(J.Annotation ann) {
            if (ann.getArguments() == null) {
                return false;
            }
            boolean hasTimerServiceOriginalCode = false;
            boolean hasQuartzSuggestedAction = false;
            boolean hasTimerMigrationReason = false;

            for (Expression arg : ann.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        Expression value = assignment.getAssignment();
                        if (value instanceof J.Literal) {
                            Object literalValue = ((J.Literal) value).getValue();
                            if (literalValue instanceof String) {
                                String strValue = (String) literalValue;
                                if ("originalCode".equals(name) && strValue.startsWith("TimerService/")) {
                                    hasTimerServiceOriginalCode = true;
                                } else if ("suggestedAction".equals(name) && strValue.contains("Quartz Scheduler pattern")) {
                                    hasQuartzSuggestedAction = true;
                                } else if ("reason".equals(name) && strValue.startsWith("EJB Timer requires manual Quartz migration")) {
                                    hasTimerMigrationReason = true;
                                }
                            }
                        }
                    }
                }
            }
            // Must have originalCode AND either suggestedAction or reason matching
            return hasTimerServiceOriginalCode && (hasQuartzSuggestedAction || hasTimerMigrationReason);
        }

        private String buildNeedsReviewReason(TimerUsageAnalysis analysis) {
            List<String> reasons = new ArrayList<>();

            if (analysis.dynamicTimerCreation) {
                reasons.add("getTimers() usage");
            }
            if (analysis.usesTimerCancel) {
                reasons.add("timer.cancel() usage");
            }
            if (analysis.usesUnsupportedTimerApis) {
                reasons.add("unsupported Timer APIs (getTimeRemaining/getNextTimeout)");
            }
            if (analysis.timeoutMethodCount > 1) {
                reasons.add("multiple @Timeout methods");
            }
            if (analysis.hasCalendarTimer) {
                reasons.add("calendar-based timer expressions");
            }
            if (analysis.usesTimerHandle) {
                reasons.add("TimerHandle usage");
            }
            if (analysis.usesTimerGetSchedule) {
                reasons.add("Timer.getSchedule() usage");
            }

            if (reasons.isEmpty()) {
                return "Complex EJB Timer pattern requires manual Quartz migration";
            }
            return "EJB Timer requires manual Quartz migration: " + String.join(", ", reasons);
        }

        private String buildMarkerAnnotationString(TimerUsageAnalysis analysis) {
            StringBuilder sb = new StringBuilder("@EjbQuartzTimerService(");
            List<String> attrs = new ArrayList<>();

            String pattern = analysis.getTimerPattern();
            if (!pattern.isEmpty()) {
                attrs.add("timerPattern = \"" + pattern + "\"");
            }
            if (analysis.usesTimerInfo) {
                attrs.add("usesTimerInfo = true");
            }
            if (analysis.dynamicTimerCreation) {
                attrs.add("dynamicTimerCreation = true");
            }
            if (analysis.timeoutMethodCount > 0) {
                attrs.add("timeoutMethodCount = " + analysis.timeoutMethodCount);
            }
            // P1.5: Add TimerHandle attributes
            if (analysis.usesTimerHandle) {
                attrs.add("usesTimerHandle = true");
            }
            if (analysis.timerHandleEscapes) {
                attrs.add("timerHandleEscapes = true");
            }
            // P1.5 Review 8: Indicate TimerHandle injected as parameter in @Timeout
            if (analysis.usesTimerHandleParamInTimeout) {
                attrs.add("usesTimerHandleParamInTimeout = true");
            }
            // P1.6: Add timer.getSchedule() usage
            if (analysis.usesTimerGetSchedule) {
                attrs.add("usesTimerGetSchedule = true");
            }
            // P1.6 Review 3: Indicate getSchedule() escapes safe transformation scope
            if (analysis.timerGetScheduleEscapes) {
                attrs.add("timerGetScheduleEscapes = true");
            }
            // Add timer type attributes
            if (analysis.hasSingleTimer) {
                attrs.add("hasSingleTimer = true");
            }
            if (analysis.hasIntervalTimer) {
                attrs.add("hasIntervalTimer = true");
            }
            if (analysis.hasCalendarTimer) {
                attrs.add("hasCalendarTimer = true");
            }

            sb.append(String.join(", ", attrs));
            sb.append(")");
            return sb.toString();
        }

        // ========== Helper Methods ==========

        private String detectSourceRoot(J.CompilationUnit cu) {
            if (cu.getPackageDeclaration() == null) return null;
            String pkg = cu.getPackageDeclaration().getExpression().print(new Cursor(null, cu)).trim();
            String pkgPath = pkg.replace('.', '/');
            String sourcePath = cu.getSourcePath().toString().replace('\\', '/');
            int idx = sourcePath.lastIndexOf("/" + pkgPath + "/");
            if (idx > 0) {
                return sourcePath.substring(0, idx);
            }
            return null;
        }

        private boolean hasQuartzTimerServiceMarker(J.ClassDeclaration cd) {
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if ("EjbQuartzTimerService".equals(ann.getSimpleName()) ||
                    TypeUtils.isOfClassType(ann.getType(), EJB_QUARTZ_TIMER_SERVICE_FQN)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasSchedulerField(J.ClassDeclaration cd) {
            if (cd.getBody() == null) return false;
            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (vd.getTypeExpression() != null) {
                        String typeName = vd.getTypeExpression().print(getCursor()).trim();
                        if ("Scheduler".equals(typeName) || typeName.endsWith(".Scheduler")) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        private TimerUsageAnalysis analyzeTimerUsage(J.ClassDeclaration cd) {
            TimerUsageAnalysis analysis = new TimerUsageAnalysis();

            if (cd.getBody() == null) return analysis;

            // First pass: collect all TimerService field names
            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (isTimerServiceField(vd)) {
                        analysis.hasTimerService = true;
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            analysis.timerServiceFieldNames.add(var.getSimpleName());
                        }
                    }
                }
            }

            // Second pass: analyze methods
            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    boolean isTimeoutMethod = hasTimeoutAnnotation(md);
                    if (isTimeoutMethod) {
                        analysis.timeoutMethodCount++;
                        analysis.timeoutMethodName = md.getSimpleName();
                        analysis.timeoutHasTimerParam = hasTimerParameter(md);
                        if (analysis.timeoutHasTimerParam) {
                            String paramName = getTimerParameterName(md);
                            analysis.timerParamName = paramName;
                            analysis.timerParamNames.add(paramName);
                        }
                    }
                    analyzeMethodBody(md, analysis, isTimeoutMethod);
                }
            }

            return analysis;
        }

        private void analyzeMethodBody(J.MethodDeclaration md, TimerUsageAnalysis analysis, boolean isTimeoutMethod) {
            final boolean inTimeoutMethod = isTimeoutMethod;

            // P1.5 Review 5: Check method parameters for TimerHandle type BEFORE early return.
            // TimerHandle parameters in non-@Timeout methods are escapes because we can't transform them.
            // This must be done even for methods with null body (e.g., abstract methods).
            if (md.getParameters() != null) {
                for (Statement param : md.getParameters()) {
                    if (param instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) param;
                        if (isTimerHandleParameter(vd)) {
                            // Mark as uses TimerHandle
                            analysis.usesTimerHandle = true;
                            // TimerHandle param in non-@Timeout method is an escape
                            if (!inTimeoutMethod) {
                                analysis.timerHandleEscapes = true;
                            } else {
                                // P1.5 Review 8: Track TimerHandle params in @Timeout
                                // (not derived from timerParam.getHandle(), so escape)
                                analysis.usesTimerHandleParamInTimeout = true;
                                analysis.timerHandleEscapes = true;
                            }
                        }
                    }
                }
            }

            if (md.getBody() == null) return;

            // Use tracked field names for matching
            final Set<String> timerServiceFieldNames = analysis.timerServiceFieldNames;
            final Set<String> timerParamNames = analysis.timerParamNames;

            // P1.5: Track local handles per-method. Only @Timeout method handles are transformable.
            // Create a method-local set for tracking, only merge to class-level for @Timeout.
            final Set<String> methodLocalHandleVars = new HashSet<>();

            // P1.5 Review 5: Add TimerHandle parameters to method-local tracking set
            if (md.getParameters() != null) {
                for (Statement param : md.getParameters()) {
                    if (param instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) param;
                        if (isTimerHandleParameter(vd)) {
                            for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                                methodLocalHandleVars.add(var.getSimpleName());
                            }
                        }
                    }
                }
            }

            new JavaIsoVisitor<TimerUsageAnalysis>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, TimerUsageAnalysis a) {
                    String name = mi.getSimpleName();

                    if (isTimerServiceInvocation(mi, timerServiceFieldNames)) {
                        if ("createTimer".equals(name) || "createSingleActionTimer".equals(name)) {
                            a.hasSingleTimer = true;
                            a.hasLiteralTimerCreation = hasLiteralArgs(mi);
                            // Detect Date/Duration overloads
                            detectFirstArgType(mi, a);
                            // Detect TimerConfig usage for persistence tracking
                            detectTimerConfigUsage(mi, a);
                        } else if ("createIntervalTimer".equals(name)) {
                            a.hasIntervalTimer = true;
                            a.hasLiteralTimerCreation = hasLiteralArgs(mi);
                            // Detect Date/Duration overloads for interval timer too
                            detectFirstArgType(mi, a);
                            // Detect TimerConfig usage for persistence tracking
                            detectTimerConfigUsage(mi, a);
                        } else if ("createCalendarTimer".equals(name)) {
                            a.hasCalendarTimer = true;
                            // P1.4: Analyze ScheduleExpression to determine if safe for migration
                            ScheduleExpressionAnalysis seAnalysis = analyzeScheduleExpression(mi);
                            if (seAnalysis != null && seAnalysis.isSafeForMigration) {
                                a.calendarTimerIsSafe = true;
                                a.calendarTimerAnalysis = seAnalysis;
                            }
                            // Detect TimerConfig usage for persistence tracking
                            detectTimerConfigUsage(mi, a);
                        } else if ("getTimers".equals(name)) {
                            a.dynamicTimerCreation = true;
                        }
                    }

                    // Check Timer API usage - these prevent auto-transform if not supported
                    if (isTimerInvocation(mi, timerParamNames)) {
                        if ("getInfo".equals(name)) {
                            a.usesTimerInfo = true;
                        } else if ("cancel".equals(name)) {
                            // P1.5: Distinguish between direct timer.cancel() and handle.getTimer().cancel()
                            // handle.getTimer().cancel() can be transformed, direct cancel() cannot
                            // P1.5 Review 5: Only recognize cancel-via-handle in @Timeout methods
                            // P1.5 Review 7: Use a.localTimerHandleVariables which only contains
                            // handles from timerParam.getHandle() (not from other sources)
                            if (inTimeoutMethod && isCancelViaTimerHandle(mi, a.localTimerHandleVariables)) {
                                a.usesTimerCancelViaHandle = true;
                            } else {
                                a.usesTimerCancel = true;
                            }
                        } else if ("getHandle".equals(name)) {
                            // P1.5: Track getHandle() separately for MigratedTimerHandle support
                            a.usesTimerHandle = true;
                            // P1.5: getHandle() outside @Timeout is considered an escape
                            // because we can only transform Timer param in @Timeout methods
                            if (!inTimeoutMethod) {
                                a.timerHandleEscapes = true;
                            } else {
                                // P1.5: Even in @Timeout, getHandle() must be on the timer parameter
                                // to be transformable. Other timers (e.g., timerService.createTimer())
                                // cannot be transformed.
                                Expression select = mi.getSelect();
                                if (select instanceof J.Identifier) {
                                    String selectName = ((J.Identifier) select).getSimpleName();
                                    if (!timerParamNames.contains(selectName)) {
                                        a.timerHandleEscapes = true;  // Not timer param -> escape
                                    }
                                } else {
                                    a.timerHandleEscapes = true;  // Not a simple identifier -> escape
                                }
                            }
                        } else if ("getSchedule".equals(name)) {
                            // P1.6: timer.getSchedule() can be transformed if calendar timer is safe
                            // We track this separately and decide at class level if transformation is possible
                            a.usesTimerGetSchedule = true;
                            // P1.6 Review 1: getSchedule() outside @Timeout is considered an escape
                            // because we can only transform Timer param in @Timeout methods
                            if (!inTimeoutMethod) {
                                a.timerGetScheduleEscapes = true;
                            } else {
                                // P1.6 Review 1: Even in @Timeout, getSchedule() must be on the timer parameter
                                // to be transformable. Other timers (e.g., local Timer variables)
                                // cannot be transformed.
                                Expression select = mi.getSelect();
                                if (select instanceof J.Identifier) {
                                    String selectName = ((J.Identifier) select).getSimpleName();
                                    if (!timerParamNames.contains(selectName)) {
                                        a.timerGetScheduleEscapes = true;  // Not timer param -> escape
                                    }
                                } else {
                                    a.timerGetScheduleEscapes = true;  // Not a simple identifier -> escape
                                }
                            }
                        } else if ("getTimeRemaining".equals(name) ||
                                   "getNextTimeout".equals(name) ||
                                   "isPersistent".equals(name) ||
                                   "isCalendarTimer".equals(name)) {
                            // These Timer APIs have no direct Quartz equivalent
                            a.usesUnsupportedTimerApis = true;
                        }
                    }

                    // P1.6 Review 3: Detect unsupported ScheduleExpression getters
                    // This is OUTSIDE the isTimerInvocation block because for timer.getSchedule().getYear()
                    // the receiver of getYear() is ScheduleExpression, not Timer.
                    // MigratedScheduleInfo only supports: getSecond, getMinute, getHour,
                    // getDayOfMonth, getMonth, getDayOfWeek, getCronExpression
                    // If getYear(), getTimezone(), getStart(), getEnd() are called,
                    // the transformed code won't compile
                    if (isUnsupportedScheduleGetter(name)) {
                        if (mi.getSelect() instanceof J.MethodInvocation) {
                            J.MethodInvocation selectMi = (J.MethodInvocation) mi.getSelect();
                            if ("getSchedule".equals(selectMi.getSimpleName())) {
                                a.usesTimerGetSchedule = true;
                                a.timerGetScheduleEscapes = true;  // Unsupported getter -> escape
                            }
                        }
                    }

                    // P1.5: Check if TimerHandle is passed as method argument (escape analysis)
                    // someMethod(handle) or list.add(handle) -> timerHandleEscapes = true
                    if (a.usesTimerHandle) {
                        for (Expression arg : mi.getArguments()) {
                            if (arg instanceof J.Empty) continue;
                            // Use method-local set for accurate escape analysis
                            if (isTimerHandleExpression(arg) || isLocalTimerHandleVar(arg, methodLocalHandleVars)) {
                                a.timerHandleEscapes = true;
                                break;
                            }
                        }
                    }

                    // P1.6 Review 3: Check if timer.getSchedule() is passed as method argument
                    // foo(timer.getSchedule()) -> timerGetScheduleEscapes = true
                    // We can't transform this because the target method expects ScheduleExpression
                    for (Expression arg : mi.getArguments()) {
                        if (arg instanceof J.Empty) continue;
                        if (isTimerGetScheduleExpression(arg)) {
                            a.usesTimerGetSchedule = true;
                            a.timerGetScheduleEscapes = true;
                            break;
                        }
                    }

                    return super.visitMethodInvocation(mi, a);
                }

                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, TimerUsageAnalysis a) {
                    vd = super.visitVariableDeclarations(vd, a);

                    // P1.5: Track local Timer variable declarations for escape analysis
                    // Timer t = timerService.createTimer(...) -> track 't' as local timer variable
                    if (isTimerType(vd.getType())) {
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            a.localTimerVariables.add(var.getSimpleName());
                        }
                    }

                    // P1.5: Track TimerHandle variable declarations (method-scoped)
                    // TimerHandle h = timer.getHandle() -> track 'h' as local handle variable
                    // P1.5 Review 6: Also set usesTimerHandle and timerHandleEscapes
                    // P1.5 Review 7: Only treat as transformable if initializer is timerParam.getHandle()
                    if (isTimerHandleType(vd.getType())) {
                        // P1.5 Review 6: Mark that this class uses TimerHandle
                        a.usesTimerHandle = true;

                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            // Track in method-local set (for all methods)
                            methodLocalHandleVars.add(var.getSimpleName());

                            // P1.5 Review 7: Check if the initializer is timerParam.getHandle()
                            boolean isFromTimerParamGetHandle = false;
                            if (inTimeoutMethod && var.getInitializer() != null) {
                                isFromTimerParamGetHandle = isTimerParamGetHandle(var.getInitializer(), timerParamNames);
                            }

                            if (!inTimeoutMethod) {
                                // P1.5 Review 6: Non-@Timeout methods with TimerHandle locals are escapes
                                a.timerHandleEscapes = true;
                            } else if (!isFromTimerParamGetHandle) {
                                // P1.5 Review 7: In @Timeout, but not from timerParam.getHandle() -> escape
                                // e.g., TimerHandle h = loadHandleFromDatabase();
                                a.timerHandleEscapes = true;
                            } else {
                                // Only add to class-level set if from timerParam.getHandle() (for transformation)
                                a.localTimerHandleVariables.add(var.getSimpleName());
                            }
                        }
                    }

                    return vd;
                }

                @Override
                public J.Assignment visitAssignment(J.Assignment assignment, TimerUsageAnalysis a) {
                    assignment = super.visitAssignment(assignment, a);

                    // P1.5: Check if TimerHandle is assigned to a field (escape analysis)
                    // this.savedHandle = timer.getHandle() -> timerHandleStoredInField = true
                    if (a.usesTimerHandle) {
                        Expression target = assignment.getVariable();
                        Expression value = assignment.getAssignment();

                        // Check if assigning to a field (this.field or field without this.)
                        if (isFieldAccess(target)) {
                            // Use method-local set for accurate escape analysis
                            if (isTimerHandleExpression(value) || isLocalTimerHandleVar(value, methodLocalHandleVars)) {
                                a.timerHandleStoredInField = true;
                                a.timerHandleEscapes = true;
                            }
                        }
                    }

                    return assignment;
                }

                @Override
                public J.Return visitReturn(J.Return ret, TimerUsageAnalysis a) {
                    ret = super.visitReturn(ret, a);

                    // P1.5: Check if TimerHandle is returned (escape analysis)
                    if (a.usesTimerHandle && ret.getExpression() != null) {
                        // Use method-local set for accurate escape analysis
                        if (isTimerHandleExpression(ret.getExpression()) ||
                            isLocalTimerHandleVar(ret.getExpression(), methodLocalHandleVars)) {
                            a.timerHandleEscapes = true;
                        }
                    }

                    // P1.6 Review 3: Check if timer.getSchedule() is returned (escape analysis)
                    // return timer.getSchedule() -> timerGetScheduleEscapes = true
                    if (ret.getExpression() != null && isTimerGetScheduleExpression(ret.getExpression())) {
                        a.usesTimerGetSchedule = true;
                        a.timerGetScheduleEscapes = true;
                    }

                    return ret;
                }

                /**
                 * P1.5: Checks if an expression is a local TimerHandle variable (method-scoped).
                 */
                private boolean isLocalTimerHandleVar(Expression expr, Set<String> localVars) {
                    if (expr instanceof J.Identifier) {
                        String name = ((J.Identifier) expr).getSimpleName();
                        return localVars.contains(name);
                    }
                    return false;
                }

                /**
                 * P1.5: Checks if an expression is a timer.getHandle() call.
                 */
                private boolean isTimerHandleExpression(Expression expr) {
                    if (expr instanceof J.MethodInvocation) {
                        J.MethodInvocation mi = (J.MethodInvocation) expr;
                        return "getHandle".equals(mi.getSimpleName());
                    }
                    return false;
                }

                /**
                 * P1.6 Review 3: Checks if a method name is an unsupported ScheduleExpression getter.
                 * MigratedScheduleInfo only supports: getSecond, getMinute, getHour,
                 * getDayOfMonth, getMonth, getDayOfWeek, getCronExpression
                 * These getters exist in EJB ScheduleExpression but not in MigratedScheduleInfo.
                 */
                private boolean isUnsupportedScheduleGetter(String methodName) {
                    return "getYear".equals(methodName) ||
                           "getTimezone".equals(methodName) ||
                           "getStart".equals(methodName) ||
                           "getEnd".equals(methodName);
                }

                /**
                 * P1.6 Review 3: Checks if an expression is a timer.getSchedule() call.
                 */
                private boolean isTimerGetScheduleExpression(Expression expr) {
                    if (expr instanceof J.MethodInvocation) {
                        J.MethodInvocation mi = (J.MethodInvocation) expr;
                        return "getSchedule".equals(mi.getSimpleName());
                    }
                    return false;
                }

                /**
                 * P1.5 Review 7: Checks if an expression is timerParam.getHandle().
                 * Only TimerHandle values from the @Timeout timer parameter are transformable.
                 * @param expr The initializer expression to check
                 * @param timerParamNames Names of the timer parameters in @Timeout methods
                 * @return true if expr is timerParam.getHandle() where timerParam is one of the timer parameter names
                 */
                private boolean isTimerParamGetHandle(Expression expr, Set<String> timerParamNames) {
                    if (!(expr instanceof J.MethodInvocation)) {
                        return false;
                    }
                    J.MethodInvocation mi = (J.MethodInvocation) expr;
                    if (!"getHandle".equals(mi.getSimpleName())) {
                        return false;
                    }
                    // Check if the select (receiver) is one of the timer parameter names
                    Expression select = mi.getSelect();
                    if (select instanceof J.Identifier) {
                        String selectName = ((J.Identifier) select).getSimpleName();
                        return timerParamNames.contains(selectName);
                    }
                    return false;
                }

                /**
                 * P1.5: Checks if an expression is a local TimerHandle variable.
                 */
                private boolean isLocalTimerHandleVariable(Expression expr, TimerUsageAnalysis a) {
                    if (expr instanceof J.Identifier) {
                        String name = ((J.Identifier) expr).getSimpleName();
                        return a.localTimerHandleVariables.contains(name);
                    }
                    return false;
                }

                /**
                 * P1.5: Checks if a cancel() call is via handle.getTimer().cancel() pattern.
                 * Pattern: handle.getTimer().cancel() where handle is a local TimerHandle variable.
                 * The mi parameter is the cancel() call, and we need to check if its select
                 * is a getTimer() call on a TimerHandle variable.
                 *
                 * P1.5 Review 5: Now uses method-local handle variables for accurate detection.
                 * This method should only be called when inTimeoutMethod is true.
                 */
                private boolean isCancelViaTimerHandle(J.MethodInvocation cancelCall, Set<String> methodLocalHandleVars) {
                    // cancel() is called on something - check what
                    Expression select = cancelCall.getSelect();
                    if (select instanceof J.MethodInvocation) {
                        J.MethodInvocation getTimerCall = (J.MethodInvocation) select;
                        // Check if it's getTimer() call
                        if ("getTimer".equals(getTimerCall.getSimpleName())) {
                            // Check if getTimer() is called on a local handle variable
                            Expression handleSelect = getTimerCall.getSelect();
                            if (handleSelect instanceof J.Identifier) {
                                String varName = ((J.Identifier) handleSelect).getSimpleName();
                                return methodLocalHandleVars.contains(varName);
                            }
                        }
                    }
                    return false;
                }

                /**
                 * P1.5: Checks if an expression is a field access (this.field or instance field).
                 */
                private boolean isFieldAccess(Expression expr) {
                    if (expr instanceof J.FieldAccess) {
                        return true;
                    }
                    // Simple identifier that's not a local variable could be a field
                    // This is a heuristic - ideally would check against method locals
                    if (expr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) expr;
                        // Check if it looks like a field (hasFieldType set)
                        return ident.getFieldType() != null &&
                               ident.getFieldType().getOwner() != null;
                    }
                    return false;
                }

                /**
                 * P1.5: Checks if a type is jakarta.ejb.Timer or javax.ejb.Timer.
                 */
                private boolean isTimerType(JavaType type) {
                    if (type == null) return false;
                    return TypeUtils.isOfClassType(type, JAKARTA_TIMER) ||
                           TypeUtils.isOfClassType(type, JAVAX_TIMER);
                }

                /**
                 * P1.5: Checks if a type is jakarta.ejb.TimerHandle or javax.ejb.TimerHandle.
                 */
                private boolean isTimerHandleType(JavaType type) {
                    if (type == null) return false;
                    return TypeUtils.isOfClassType(type, JAKARTA_TIMER_HANDLE) ||
                           TypeUtils.isOfClassType(type, JAVAX_TIMER_HANDLE);
                }

                private boolean hasLiteralArgs(J.MethodInvocation mi) {
                    for (Expression arg : mi.getArguments()) {
                        if (arg instanceof J.Literal) continue;
                        if (arg instanceof J.Identifier) {
                            // Variable reference - not literal
                            return false;
                        }
                    }
                    return true;
                }

                private void detectFirstArgType(J.MethodInvocation mi, TimerUsageAnalysis a) {
                    List<Expression> args = mi.getArguments();
                    if (args.isEmpty() || args.get(0) instanceof J.Empty) {
                        return;
                    }
                    Expression firstArg = args.get(0);
                    JavaType argType = firstArg.getType();
                    if (argType != null) {
                        if (TypeUtils.isOfClassType(argType, "java.util.Date")) {
                            a.hasDateBasedTimer = true;
                        } else if (TypeUtils.isOfClassType(argType, "java.time.Duration")) {
                            a.hasDurationBasedTimer = true;
                        }
                    }
                }

                private void detectTimerConfigUsage(J.MethodInvocation mi, TimerUsageAnalysis a) {
                    // Check if any argument is a TimerConfig (implies isPersistent() may be used)
                    List<Expression> args = mi.getArguments();
                    for (Expression arg : args) {
                        if (arg instanceof J.Empty) continue;
                        // Check type attribution (most reliable)
                        JavaType argType = arg.getType();
                        if (argType != null) {
                            if (TypeUtils.isOfClassType(argType, JAKARTA_TIMER_CONFIG) ||
                                TypeUtils.isOfClassType(argType, JAVAX_TIMER_CONFIG)) {
                                a.usesTimerConfig = true;
                                // Type attribution doesn't tell us the isPersistent value
                                a.mayUsePersistentTimers = true;
                                return;
                            }
                        }
                        // Check for new TimerConfig(...) expression
                        if (arg instanceof J.NewClass) {
                            J.NewClass newClass = (J.NewClass) arg;
                            // Handle fully qualified new jakarta.ejb.TimerConfig(...)
                            if (newClass.getClazz() instanceof J.FieldAccess) {
                                String fqn = extractFullyQualifiedName((J.FieldAccess) newClass.getClazz());
                                if (JAKARTA_TIMER_CONFIG.equals(fqn) || JAVAX_TIMER_CONFIG.equals(fqn)) {
                                    a.usesTimerConfig = true;
                                    if (!isExplicitlyNonPersistent(newClass)) {
                                        a.mayUsePersistentTimers = true;
                                    }
                                    return;
                                }
                            }
                            // Handle simple new TimerConfig(...) with import verification
                            if (newClass.getClazz() instanceof J.Identifier) {
                                String className = ((J.Identifier) newClass.getClazz()).getSimpleName();
                                if ("TimerConfig".equals(className)) {
                                    // Verify EJB import to avoid false positives with non-EJB TimerConfig
                                    if (hasEjbTimerConfigImportSimple()) {
                                        a.usesTimerConfig = true;
                                        // Check if isPersistent is explicitly false
                                        // new TimerConfig(info, false) -> no persistence needed
                                        // new TimerConfig(info, true) or new TimerConfig(info, var) -> persistence needed
                                        if (!isExplicitlyNonPersistent(newClass)) {
                                            a.mayUsePersistentTimers = true;
                                        }
                                    }
                                    return;
                                }
                            }
                        }
                        // Check for identifier references to TimerConfig variables
                        // Only set flags if the type can be resolved via declaration lookup
                        // Do NOT use name-based heuristics as they produce false positives
                        if (arg instanceof J.Identifier) {
                            String varName = ((J.Identifier) arg).getSimpleName();
                            if (isVariableDeclaredAsTimerConfig(varName)) {
                                // Type resolved via parameter/field/local variable declaration
                                a.usesTimerConfig = true;
                                // Unknown persistence value from resolved variable, assume may be persistent
                                a.mayUsePersistentTimers = true;
                                return;
                            }
                            // If type cannot be resolved, do NOT assume it's TimerConfig
                            // This avoids false positives from generic variable names like "config"
                        }
                    }
                }

                /**
                 * Checks if a new TimerConfig(...) expression is explicitly non-persistent.
                 * new TimerConfig(info, false) is explicitly non-persistent.
                 * new TimerConfig(info, true) or new TimerConfig(info, var) may be persistent.
                 * new TimerConfig(info) uses default (non-persistent) so also explicitly non-persistent.
                 */
                private boolean isExplicitlyNonPersistent(J.NewClass newClass) {
                    List<Expression> args = newClass.getArguments();
                    // new TimerConfig(info) - single arg, default is non-persistent
                    if (args.size() == 1) {
                        return true;
                    }
                    // new TimerConfig(info, persistent)
                    if (args.size() >= 2) {
                        Expression persistentArg = args.get(1);
                        if (persistentArg instanceof J.Literal) {
                            J.Literal lit = (J.Literal) persistentArg;
                            if (lit.getValue() instanceof Boolean) {
                                return !((Boolean) lit.getValue()); // true if isPersistent=false
                            }
                        }
                    }
                    // Unknown - assume might be persistent
                    return false;
                }

                /**
                 * Checks if a variable is declared as TimerConfig via parameters, local vars, or fields.
                 * Uses the closure-captured `md` for method context.
                 */
                private boolean isVariableDeclaredAsTimerConfig(String varName) {
                    // 1. Check method parameters
                    for (Statement param : md.getParameters()) {
                        if (param instanceof J.VariableDeclarations) {
                            if (checkVarDeclForTimerConfig((J.VariableDeclarations) param, varName)) {
                                return true;
                            }
                        }
                    }

                    // 2. Check local variable declarations in method body
                    if (md.getBody() != null) {
                        for (Statement stmt : md.getBody().getStatements()) {
                            if (stmt instanceof J.VariableDeclarations) {
                                if (checkVarDeclForTimerConfig((J.VariableDeclarations) stmt, varName)) {
                                    return true;
                                }
                            }
                        }
                    }

                    // 3. Check class fields via cursor
                    J.ClassDeclaration classDecl = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    if (classDecl != null && classDecl.getBody() != null) {
                        for (Statement stmt : classDecl.getBody().getStatements()) {
                            if (stmt instanceof J.VariableDeclarations) {
                                if (checkVarDeclForTimerConfig((J.VariableDeclarations) stmt, varName)) {
                                    return true;
                                }
                            }
                        }
                    }

                    return false;
                }

                private boolean checkVarDeclForTimerConfig(J.VariableDeclarations decl, String varName) {
                    for (J.VariableDeclarations.NamedVariable var : decl.getVariables()) {
                        if (varName.equals(var.getSimpleName())) {
                            // First, check type attribution (most reliable)
                            JavaType declType = decl.getType();
                            if (declType != null) {
                                if (TypeUtils.isOfClassType(declType, JAKARTA_TIMER_CONFIG) ||
                                    TypeUtils.isOfClassType(declType, JAVAX_TIMER_CONFIG)) {
                                    return true;
                                }
                            }
                            // Fallback: check type expression without cursor-based print
                            TypeTree typeTree = decl.getTypeExpression();
                            if (typeTree != null) {
                                String typeName = extractSimpleTypeName(typeTree);
                                if ("TimerConfig".equals(typeName)) {
                                    // Simple name - verify EJB import to avoid false positives
                                    return hasEjbTimerConfigImportSimple();
                                }
                                // Check fully qualified name in FieldAccess
                                if (typeTree instanceof J.FieldAccess) {
                                    J.FieldAccess fa = (J.FieldAccess) typeTree;
                                    String target = extractFullyQualifiedName(fa);
                                    if (JAKARTA_TIMER_CONFIG.equals(target) || JAVAX_TIMER_CONFIG.equals(target)) {
                                        return true;
                                    }
                                }
                            }
                            return false;
                        }
                    }
                    return false;
                }

                // Note: extractSimpleTypeName, extractFullyQualifiedName, extractFqnFromQualid
                // are now shared static methods at class level

                /**
                 * Checks if there's an import for EJB TimerConfig without using cursor-based print.
                 */
                private boolean hasEjbTimerConfigImportSimple() {
                    J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                    if (cu == null || cu.getImports() == null) return false;

                    for (J.Import imp : cu.getImports()) {
                        // Check using type if available
                        if (imp.getQualid().getType() != null) {
                            String typeFqn = imp.getQualid().getType().toString();
                            if (typeFqn.equals(JAKARTA_TIMER_CONFIG) || typeFqn.equals(JAVAX_TIMER_CONFIG)) {
                                return true;
                            }
                        }
                        // Fallback: extract FQN from qualid structure (uses shared static method)
                        String importFqn = extractFqnFromQualid(imp.getQualid());
                        if (JAKARTA_TIMER_CONFIG.equals(importFqn) || JAVAX_TIMER_CONFIG.equals(importFqn)) {
                            return true;
                        }
                        if ("jakarta.ejb.*".equals(importFqn) || "javax.ejb.*".equals(importFqn)) {
                            return true;
                        }
                    }
                    return false;
                }

                private boolean isTimerServiceInvocation(J.MethodInvocation mi, Set<String> fieldNames) {
                    if (mi.getSelect() == null) return false;
                    Expression select = mi.getSelect();
                    // First check type attribution (most reliable)
                    if (select.getType() != null) {
                        return TypeUtils.isOfClassType(select.getType(), JAKARTA_TIMER_SERVICE) ||
                               TypeUtils.isOfClassType(select.getType(), JAVAX_TIMER_SERVICE);
                    }
                    // Fallback: check if identifier matches any tracked TimerService field name
                    if (select instanceof J.Identifier) {
                        String selectName = ((J.Identifier) select).getSimpleName();
                        return fieldNames.contains(selectName);
                    }
                    return false;
                }

                private boolean isTimerInvocation(J.MethodInvocation mi, Set<String> paramNames) {
                    if (mi.getSelect() == null) return false;
                    Expression select = mi.getSelect();
                    // First check type attribution
                    if (select.getType() != null) {
                        return TypeUtils.isOfClassType(select.getType(), JAKARTA_TIMER) ||
                               TypeUtils.isOfClassType(select.getType(), JAVAX_TIMER);
                    }
                    // Fallback: check if identifier matches any tracked Timer param name
                    if (select instanceof J.Identifier) {
                        String selectName = ((J.Identifier) select).getSimpleName();
                        return paramNames.contains(selectName);
                    }
                    return false;
                }

                /**
                 * P1.4: Analyzes a createCalendarTimer invocation to extract ScheduleExpression details.
                 *
                 * Supports setter-chain pattern:
                 *   new ScheduleExpression().hour("2").minute("0").dayOfWeek("Mon-Fri")
                 *
                 * Returns null if the expression cannot be analyzed (not a NewClass with method chain).
                 * Returns ScheduleExpressionAnalysis with isSafeForMigration=false if:
                 * - Any setter uses a non-literal argument
                 * - Unsupported attributes are used (year, timezone, start, end)
                 * - Special values like "Last", "1st", etc. are detected
                 */
                private ScheduleExpressionAnalysis analyzeScheduleExpression(J.MethodInvocation createCalendarTimerCall) {
                    List<Expression> args = createCalendarTimerCall.getArguments();
                    if (args.isEmpty() || args.get(0) instanceof J.Empty) {
                        return null;
                    }

                    Expression scheduleExpr = args.get(0);
                    ScheduleExpressionAnalysis analysis = new ScheduleExpressionAnalysis();

                    // Must be a method chain starting with new ScheduleExpression()
                    // Parse the chain bottom-up: the expression could be:
                    // - new ScheduleExpression().hour("2")  (single setter)
                    // - new ScheduleExpression().hour("2").minute("0")  (multiple setters)
                    // - variable reference (not safe)
                    // - other expression (not safe)

                    if (!parseScheduleExpressionChain(scheduleExpr, analysis)) {
                        return null; // Could not parse, not a recognized pattern
                    }

                    // P1.4 Fix: Check for dayOfMonth/dayOfWeek conflict (Quartz limitation)
                    if (analysis.hasDayOfMonthAndDayOfWeekConflict()) {
                        analysis.markUnsafe("Both dayOfMonth and dayOfWeek are explicitly set - Quartz only supports one");
                    }

                    return analysis;
                }

                /**
                 * Recursively parses a ScheduleExpression setter chain.
                 * Returns true if successfully parsed, false if pattern not recognized.
                 */
                private boolean parseScheduleExpressionChain(Expression expr, ScheduleExpressionAnalysis analysis) {
                    // Base case: new ScheduleExpression()
                    if (expr instanceof J.NewClass) {
                        J.NewClass newClass = (J.NewClass) expr;
                        TypeTree clazz = newClass.getClazz();
                        String className = null;

                        if (clazz instanceof J.Identifier) {
                            className = ((J.Identifier) clazz).getSimpleName();
                        } else if (clazz instanceof J.FieldAccess) {
                            className = ((J.FieldAccess) clazz).getSimpleName();
                        }

                        if ("ScheduleExpression".equals(className)) {
                            // Found the base constructor - this is valid
                            return true;
                        }
                        // Not a ScheduleExpression constructor
                        analysis.markUnsafe("Not a ScheduleExpression constructor");
                        return false;
                    }

                    // Recursive case: method invocation on a chain
                    if (expr instanceof J.MethodInvocation) {
                        J.MethodInvocation mi = (J.MethodInvocation) expr;
                        String methodName = mi.getSimpleName();

                        // First, recurse to parse the receiver (the chain before this method)
                        Expression select = mi.getSelect();
                        if (select == null) {
                            analysis.markUnsafe("Method invocation without receiver");
                            return false;
                        }

                        if (!parseScheduleExpressionChain(select, analysis)) {
                            return false; // Chain parsing failed
                        }

                        // Now process this method call
                        return processScheduleExpressionSetter(mi, methodName, analysis);
                    }

                    // Variable reference or other expression - not safe for static analysis
                    analysis.markUnsafe("ScheduleExpression is not a literal chain");
                    analysis.allLiterals = false;
                    return false;
                }

                /**
                 * Processes a single ScheduleExpression setter method.
                 * Returns true if valid, false if invalid or unsupported.
                 */
                private boolean processScheduleExpressionSetter(J.MethodInvocation mi, String methodName, ScheduleExpressionAnalysis analysis) {
                    List<Expression> args = mi.getArguments();

                    // P1.4 Review 3 Fix: All setters should have exactly one argument
                    // Mark as unsafe if no argument is provided
                    if (args.isEmpty() || args.get(0) instanceof J.Empty) {
                        analysis.markUnsafe("ScheduleExpression setter " + methodName + "() called without argument");
                        return true; // Continue parsing, but marked as unsafe
                    }

                    Expression arg = args.get(0);

                    // Extract literal string value (if applicable)
                    String literalValue = null;
                    if (arg instanceof J.Literal) {
                        J.Literal lit = (J.Literal) arg;
                        if (lit.getValue() instanceof String) {
                            literalValue = (String) lit.getValue();
                        }
                    }

                    switch (methodName) {
                        case "second":
                            if (literalValue == null) {
                                analysis.markUnsafe("second() uses non-literal argument");
                                analysis.allLiterals = false;
                                return true; // Continue parsing, but mark unsafe
                            }
                            if (ScheduleExpressionAnalysis.hasUnsupportedCronValue(literalValue)) {
                                analysis.markUnsafe("second() uses unsupported value: " + literalValue);
                                return true;
                            }
                            analysis.second = literalValue;
                            break;

                        case "minute":
                            if (literalValue == null) {
                                analysis.markUnsafe("minute() uses non-literal argument");
                                analysis.allLiterals = false;
                                return true;
                            }
                            if (ScheduleExpressionAnalysis.hasUnsupportedCronValue(literalValue)) {
                                analysis.markUnsafe("minute() uses unsupported value: " + literalValue);
                                return true;
                            }
                            analysis.minute = literalValue;
                            break;

                        case "hour":
                            if (literalValue == null) {
                                analysis.markUnsafe("hour() uses non-literal argument");
                                analysis.allLiterals = false;
                                return true;
                            }
                            if (ScheduleExpressionAnalysis.hasUnsupportedCronValue(literalValue)) {
                                analysis.markUnsafe("hour() uses unsupported value: " + literalValue);
                                return true;
                            }
                            analysis.hour = literalValue;
                            break;

                        case "dayOfMonth":
                            analysis.dayOfMonthWasSet = true; // P1.4 Review 2: Track explicit setter
                            if (literalValue == null) {
                                analysis.markUnsafe("dayOfMonth() uses non-literal argument");
                                analysis.allLiterals = false;
                                return true;
                            }
                            if (ScheduleExpressionAnalysis.hasUnsupportedCronValue(literalValue)) {
                                analysis.markUnsafe("dayOfMonth() uses unsupported value: " + literalValue);
                                return true;
                            }
                            analysis.dayOfMonth = literalValue;
                            break;

                        case "month":
                            if (literalValue == null) {
                                analysis.markUnsafe("month() uses non-literal argument");
                                analysis.allLiterals = false;
                                return true;
                            }
                            if (ScheduleExpressionAnalysis.hasUnsupportedCronValue(literalValue)) {
                                analysis.markUnsafe("month() uses unsupported value: " + literalValue);
                                return true;
                            }
                            analysis.month = literalValue;
                            break;

                        case "dayOfWeek":
                            analysis.dayOfWeekWasSet = true; // P1.4 Review 2: Track explicit setter
                            if (literalValue == null) {
                                analysis.markUnsafe("dayOfWeek() uses non-literal argument");
                                analysis.allLiterals = false;
                                return true;
                            }
                            if (ScheduleExpressionAnalysis.hasUnsupportedCronValue(literalValue)) {
                                analysis.markUnsafe("dayOfWeek() uses unsupported value: " + literalValue);
                                return true;
                            }
                            analysis.dayOfWeek = literalValue;
                            break;

                        // Unsupported attributes - mark as unsafe
                        case "year":
                            analysis.markUnsafe("year() attribute not supported in Spring Cron");
                            if (literalValue != null) {
                                analysis.year = literalValue;
                            }
                            break;

                        case "timezone":
                            analysis.markUnsafe("timezone() attribute requires separate handling");
                            if (literalValue != null) {
                                analysis.timezone = literalValue;
                            }
                            break;

                        case "start":
                            analysis.markUnsafe("start() attribute not supported in Spring Cron");
                            analysis.hasStart = true;
                            break;

                        case "end":
                            analysis.markUnsafe("end() attribute not supported in Spring Cron");
                            analysis.hasEnd = true;
                            break;

                        default:
                            // Unknown method - mark as unsafe to avoid silent semantic loss
                            analysis.markUnsafe("Unknown ScheduleExpression setter: " + methodName + "()");
                            break;
                    }

                    return true;
                }
            }.visit(md.getBody(), analysis);
        }

        private boolean isTimerServiceField(J.VariableDeclarations vd) {
            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr == null) return false;

            // 1. Check type attribution first (most reliable)
            if (typeExpr.getType() != null) {
                if (TypeUtils.isOfClassType(typeExpr.getType(), JAKARTA_TIMER_SERVICE) ||
                    TypeUtils.isOfClassType(typeExpr.getType(), JAVAX_TIMER_SERVICE)) {
                    return true;
                }
            }

            // 2. Check for fully qualified name in FieldAccess
            if (typeExpr instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) typeExpr;
                if ("TimerService".equals(fa.getSimpleName())) {
                    String fullName = fa.print(getCursor()).trim();
                    return fullName.endsWith(".TimerService") &&
                           (fullName.contains("javax.ejb") || fullName.contains("jakarta.ejb"));
                }
            }

            // 3. Check simple name "TimerService" with import verification
            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                if ("TimerService".equals(ident.getSimpleName())) {
                    return hasEjbTimerServiceImport();
                }
            }

            return false;
        }

        private boolean hasEjbTimerServiceImport() {
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            if (cu == null || cu.getImports() == null) return false;

            for (J.Import imp : cu.getImports()) {
                String importStr = imp.getQualid().print(getCursor()).trim();
                // Check for direct TimerService import or wildcard ejb import
                if (JAKARTA_TIMER_SERVICE.equals(importStr) || JAVAX_TIMER_SERVICE.equals(importStr)) {
                    return true;
                }
                if ("jakarta.ejb.*".equals(importStr) || "javax.ejb.*".equals(importStr)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * WFQ-017: Check if a field is a TimerHandle field.
         * TimerHandle fields should be removed in fallback mode.
         */
        private boolean isTimerHandleField(J.VariableDeclarations vd) {
            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr == null) return false;

            // 1. Check type attribution first (most reliable)
            if (typeExpr.getType() != null) {
                if (TypeUtils.isOfClassType(typeExpr.getType(), JAKARTA_TIMER_HANDLE) ||
                    TypeUtils.isOfClassType(typeExpr.getType(), JAVAX_TIMER_HANDLE)) {
                    return true;
                }
            }

            // 2. Check for fully qualified name in FieldAccess
            if (typeExpr instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) typeExpr;
                if ("TimerHandle".equals(fa.getSimpleName())) {
                    String fullName = fa.print(getCursor()).trim();
                    return fullName.endsWith(".TimerHandle") &&
                           (fullName.contains("javax.ejb") || fullName.contains("jakarta.ejb"));
                }
            }

            // 3. Check simple name "TimerHandle" with import verification
            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                if ("TimerHandle".equals(ident.getSimpleName())) {
                    return hasEjbTimerHandleImport();
                }
            }

            return false;
        }

        private boolean hasEjbTimerHandleImport() {
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            if (cu == null || cu.getImports() == null) return false;

            for (J.Import imp : cu.getImports()) {
                String importStr = imp.getQualid().print(getCursor()).trim();
                // Check for direct TimerHandle import or wildcard ejb import
                if (JAKARTA_TIMER_HANDLE.equals(importStr) || JAVAX_TIMER_HANDLE.equals(importStr)) {
                    return true;
                }
                if ("jakarta.ejb.*".equals(importStr) || "javax.ejb.*".equals(importStr)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isTimerParameter(J.VariableDeclarations vd) {
            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr == null) return false;

            // 1. Check type attribution first (most reliable)
            if (typeExpr.getType() != null) {
                if (TypeUtils.isOfClassType(typeExpr.getType(), JAKARTA_TIMER) ||
                    TypeUtils.isOfClassType(typeExpr.getType(), JAVAX_TIMER)) {
                    return true;
                }
            }

            // 2. Check for fully qualified name in FieldAccess
            if (typeExpr instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) typeExpr;
                if ("Timer".equals(fa.getSimpleName())) {
                    // Could be javax.ejb.Timer or jakarta.ejb.Timer
                    String fullName = fa.print(getCursor()).trim();
                    return fullName.endsWith(".Timer") &&
                           (fullName.contains("javax.ejb") || fullName.contains("jakarta.ejb"));
                }
            }

            // 3. Check simple name "Timer" with import verification
            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                if ("Timer".equals(ident.getSimpleName())) {
                    // Verify via imports that this is an EJB Timer
                    return hasEjbTimerImport();
                }
            }

            return false;
        }

        /**
         * P1.5 Review 5: Class-level method to check if a variable declaration is a TimerHandle.
         * Used for checking method parameters before entering the visitor.
         *
         * Note: This method uses a simple name check ("TimerHandle") because:
         * 1. Type attribution may not be reliable in test environments with TypeValidation.none()
         * 2. Import verification via getCursor() may not work outside visitor visit methods
         * 3. In practice, a type named "TimerHandle" is almost certainly jakarta.ejb.TimerHandle
         */
        private boolean isTimerHandleParameter(J.VariableDeclarations vd) {
            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr == null) return false;

            // 1. Check type attribution first (most reliable when available)
            if (typeExpr.getType() != null) {
                if (TypeUtils.isOfClassType(typeExpr.getType(), JAKARTA_TIMER_HANDLE) ||
                    TypeUtils.isOfClassType(typeExpr.getType(), JAVAX_TIMER_HANDLE)) {
                    return true;
                }
            }

            // 2. Check for fully qualified name in FieldAccess
            if (typeExpr instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) typeExpr;
                if ("TimerHandle".equals(fa.getSimpleName())) {
                    // Could be javax.ejb.TimerHandle or jakarta.ejb.TimerHandle
                    return true;
                }
            }

            // 3. Check simple name "TimerHandle"
            // In the context of EJB code, TimerHandle almost always refers to the EJB interface.
            // This is a pragmatic check that works even when type attribution is incomplete.
            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                if ("TimerHandle".equals(ident.getSimpleName())) {
                    return true;
                }
            }

            return false;
        }

        private boolean hasEjbTimerImport() {
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            if (cu == null || cu.getImports() == null) return false;

            for (J.Import imp : cu.getImports()) {
                String importStr = imp.getQualid().print(getCursor()).trim();
                // Check for direct Timer import or wildcard ejb import
                if (JAKARTA_TIMER.equals(importStr) || JAVAX_TIMER.equals(importStr)) {
                    return true;
                }
                if ("jakarta.ejb.*".equals(importStr) || "javax.ejb.*".equals(importStr)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasTimerParameter(J.MethodDeclaration md) {
            if (md.getParameters() == null) return false;
            for (Statement param : md.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    if (isTimerParameter((J.VariableDeclarations) param)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * WFQ-017: Check if a method has a TimerHandle parameter.
         */
        private boolean hasTimerHandleParameter(J.MethodDeclaration md) {
            if (md.getParameters() == null) return false;
            for (Statement param : md.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    if (isTimerHandleParameter((J.VariableDeclarations) param)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private String getTimerParameterName(J.MethodDeclaration md) {
            if (md.getParameters() == null) return "timer";
            for (Statement param : md.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    if (isTimerParameter(vd) && !vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
            }
            return "timer";
        }

        private boolean hasTimeoutAnnotation(J.MethodDeclaration md) {
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (isTimeoutAnnotation(ann)) return true;
            }
            return false;
        }

        private boolean isTimeoutAnnotation(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), JAKARTA_TIMEOUT) ||
                   TypeUtils.isOfClassType(ann.getType(), JAVAX_TIMEOUT) ||
                   "Timeout".equals(ann.getSimpleName());
        }

        private boolean isResourceAnnotation(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), JAKARTA_RESOURCE) ||
                   TypeUtils.isOfClassType(ann.getType(), JAVAX_RESOURCE) ||
                   "Resource".equals(ann.getSimpleName());
        }

        private J.Annotation createAnnotation(String simpleName, String fqn, Space prefix) {
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
    }

    /**
     * Analysis result for timer usage in a class.
     */
    private static class TimerUsageAnalysis {
        boolean hasTimerService = false;
        // Track all TimerService field names (may have multiple or custom names)
        final Set<String> timerServiceFieldNames = new HashSet<>();
        // Track all Timer parameter names from @Timeout methods
        final Set<String> timerParamNames = new HashSet<>();
        int timeoutMethodCount = 0;
        String timeoutMethodName;
        boolean timeoutHasTimerParam = false;
        String timerParamName = "timer";
        boolean hasSingleTimer = false;
        boolean hasIntervalTimer = false;
        boolean hasCalendarTimer = false;
        // P1.4: Track if calendar timer can be safely migrated (literal setters, supported attrs)
        boolean calendarTimerIsSafe = false;
        ScheduleExpressionAnalysis calendarTimerAnalysis = null;
        boolean usesTimerInfo = false;
        boolean usesTimerCancel = false;
        // Other Timer APIs that prevent auto-transform (getTimeRemaining, getNextTimeout, etc.)
        boolean usesUnsupportedTimerApis = false;
        boolean dynamicTimerCreation = false;
        boolean hasLiteralTimerCreation = true;
        // Track Date/Duration overloads for generating correct helper methods
        boolean hasDateBasedTimer = false;
        boolean hasDurationBasedTimer = false;
        // Track if TimerConfig is used (implies isPersistent() may be called)
        boolean usesTimerConfig = false;
        // Track if any TimerConfig explicitly uses isPersistent=true or unknown
        // (not just isPersistent=false which doesn't need JDBC JobStore)
        boolean mayUsePersistentTimers = false;

        // P1.5: TimerHandle tracking
        // Whether timer.getHandle() is called anywhere
        boolean usesTimerHandle = false;
        // Whether TimerHandle is stored in a field (escapes local scope)
        boolean timerHandleStoredInField = false;
        // Whether TimerHandle is passed to another method or returned (escapes local scope)
        boolean timerHandleEscapes = false;
        // Track local variable names that hold Timer references (for escape analysis)
        final Set<String> localTimerVariables = new HashSet<>();
        // Track local variable names that hold TimerHandle references
        final Set<String> localTimerHandleVariables = new HashSet<>();
        // P1.5: Whether cancel() is called via handle.getTimer().cancel() (transformable)
        boolean usesTimerCancelViaHandle = false;
        // P1.5 Review 8: Track if @Timeout method has TimerHandle parameter (not derived from timerParam.getHandle())
        boolean usesTimerHandleParamInTimeout = false;

        // P1.6: Timer.getSchedule() tracking
        // Whether timer.getSchedule() is called
        boolean usesTimerGetSchedule = false;
        // P1.6 Review 1: Whether getSchedule() escapes (called outside @Timeout or on non-timer param)
        boolean timerGetScheduleEscapes = false;
        // Whether all getSchedule() calls can be transformed (only if calendarTimerIsSafe and no escapes)
        boolean canTransformGetSchedule = false;

        boolean hasTimerUsage() {
            // P1.5 Review 5: Include usesTimerHandle to catch classes with TimerHandle
            // parameters in non-@Timeout methods (these still need marker annotation)
            return hasTimerService || timeoutMethodCount > 0 || usesTimerHandle;
        }

        // Get the first/primary TimerService field name (for backward compatibility)
        String getTimerServiceFieldName() {
            return timerServiceFieldNames.isEmpty() ? "timerService" : timerServiceFieldNames.iterator().next();
        }

        String getTimerPattern() {
            List<String> patterns = new ArrayList<>();
            if (hasSingleTimer) patterns.add("single");
            if (hasIntervalTimer) patterns.add("interval");
            if (hasCalendarTimer) patterns.add("calendar");

            if (patterns.isEmpty()) return "";
            if (patterns.size() == 1) return patterns.get(0);
            return "mixed";
        }
    }

    /**
     * P1.4: Analysis result for ScheduleExpression in createCalendarTimer.
     * Determines if a ScheduleExpression can be safely migrated to Spring Cron.
     *
     * Safe migration requires:
     * - All setters use String literals (no variables, constants, or method calls)
     * - Only supported attributes (second, minute, hour, dayOfMonth, month, dayOfWeek)
     * - No unsupported attributes (year, timezone, start, end)
     * - No unsupported special values (Last, 1st, 2nd, negative offsets)
     */
    static class ScheduleExpressionAnalysis {
        boolean isSafeForMigration = true;
        String unsafeReason = null;

        // Extracted values with EJB 3.2 defaults
        String second = "0";
        String minute = "0";
        String hour = "0";
        String dayOfMonth = "*";
        String month = "*";
        String dayOfWeek = "*";

        // P1.4 Review 2 Fix: Track explicitly set fields (vs. default values)
        // This distinguishes between "not set" (uses EJB default) and "explicitly set to '*'"
        boolean dayOfMonthWasSet = false;
        boolean dayOfWeekWasSet = false;

        // Unsupported attributes (if set, migration is not safe)
        String year = null;
        String timezone = null;
        boolean hasStart = false;
        boolean hasEnd = false;

        // All values must be string literals
        boolean allLiterals = true;

        /**
         * Builds Quartz Cron expression from extracted values.
         * Format: second minute hour dayOfMonth month dayOfWeek
         *
         * Quartz Cron requires that either dayOfMonth or dayOfWeek be '?' (not both '*').
         * - If dayOfMonth is set (not '*'), dayOfWeek becomes '?'
         * - If dayOfWeek is set (not '*'), dayOfMonth becomes '?'
         * - If both are '*', dayOfWeek becomes '?' (Quartz convention)
         *
         * Note: If both dayOfMonth AND dayOfWeek are set to non-'*' values,
         * this is a conflict that should have been marked unsafe during parsing.
         */
        String buildQuartzCronExpression() {
            String effectiveDayOfMonth = dayOfMonth;
            String effectiveDayOfWeek = dayOfWeek;

            // Quartz requires '?' for one of dayOfMonth/dayOfWeek
            boolean domSet = !"*".equals(dayOfMonth);
            boolean dowSet = !"*".equals(dayOfWeek);

            if (domSet && dowSet) {
                // Both are set - this is a conflict, should have been marked unsafe
                // Return as-is, but callers should check hasConflict() first
                effectiveDayOfWeek = "?";
            } else if (domSet) {
                // dayOfMonth is set, use '?' for dayOfWeek
                effectiveDayOfWeek = "?";
            } else {
                // dayOfWeek is set (or both are '*'), use '?' for dayOfMonth
                effectiveDayOfMonth = "?";
            }

            return String.format("%s %s %s %s %s %s",
                second, minute, hour, effectiveDayOfMonth, month, effectiveDayOfWeek);
        }

        /**
         * Checks if both dayOfMonth and dayOfWeek are explicitly set.
         * This is a conflict in Quartz Cron - only one can be specified.
         *
         * P1.4 Review 2 Fix: Check if both fields were explicitly set by the user,
         * regardless of their values. This handles the case where user explicitly sets
         * dayOfMonth="*" AND dayOfWeek="Mon-Fri" (or vice versa), which is semantically
         * different from not setting one of them.
         */
        boolean hasDayOfMonthAndDayOfWeekConflict() {
            // Case 1: Both explicitly set to non-'*' values -> definite conflict
            if (!"*".equals(dayOfMonth) && !"*".equals(dayOfWeek)) {
                return true;
            }
            // Case 2: Both were explicitly set, even if one is '*' -> ambiguous, treat as conflict
            // This is conservative: if user explicitly set both, we cannot safely pick one
            if (dayOfMonthWasSet && dayOfWeekWasSet) {
                return true;
            }
            return false;
        }

        /**
         * Marks the analysis as unsafe with a reason.
         */
        void markUnsafe(String reason) {
            isSafeForMigration = false;
            if (unsafeReason == null) {
                unsafeReason = reason;
            }
        }

        /**
         * Checks if a cron value contains unsupported special values.
         * Returns true if the value is NOT safe for Spring Cron.
         */
        static boolean hasUnsupportedCronValue(String value) {
            if (value == null || value.isEmpty()) return false;
            String lower = value.toLowerCase();

            // "Last" special value (EJB supports, Spring doesn't directly)
            if (lower.contains("last")) return true;

            // "1st", "2nd", "3rd", "4th", "5th" (n-th weekday in month)
            if (lower.matches(".*\\d+(st|nd|rd|th).*")) return true;

            // Negative offsets like "-1" (last day minus 1)
            // Note: ranges like "9-17" are OK, so we check for trailing negative number
            if (value.matches(".*-\\d+$") && !value.matches("^\\d+-\\d+$")) return true;

            return false;
        }
    }
}
