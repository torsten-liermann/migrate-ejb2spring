package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ClusterMode;
import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import com.github.rewrite.ejb.marker.TimerStrategyMarker;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static com.github.rewrite.ejb.TimerScheduleUtils.*;

/**
 * Resolves the effective timer strategy per module based on code semantics and project.yaml.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ResolveTimerStrategy extends ScanningRecipe<ResolveTimerStrategy.Accumulator> {

    private static final String JAKARTA_TIMER_SERVICE = "jakarta.ejb.TimerService";
    private static final String JAVAX_TIMER_SERVICE = "javax.ejb.TimerService";
    private static final String JAKARTA_TIMER = "jakarta.ejb.Timer";
    private static final String JAVAX_TIMER = "javax.ejb.Timer";
    private static final String JAKARTA_TIMER_CONFIG = "jakarta.ejb.TimerConfig";
    private static final String JAVAX_TIMER_CONFIG = "javax.ejb.TimerConfig";
    private static final String JAKARTA_TIMER_HANDLE = "jakarta.ejb.TimerHandle";
    private static final String JAVAX_TIMER_HANDLE = "javax.ejb.TimerHandle";
    private static final String JAKARTA_TIMEOUT = "jakarta.ejb.Timeout";
    private static final String JAVAX_TIMEOUT = "javax.ejb.Timeout";

    static class Accumulator {
        Map<Path, ModuleRequirements> requirementsByModule = new HashMap<>();
    }

    static class ModuleRequirements {
        boolean hasTimerUsage;
        boolean requiresQuartz;
        boolean requiresTaskScheduler;
        final Set<String> quartzReasons = new LinkedHashSet<>();
        final Set<String> taskSchedulerReasons = new LinkedHashSet<>();

        void requireQuartz(String reason) {
            requiresQuartz = true;
            if (reason != null && !reason.isBlank()) {
                quartzReasons.add(reason);
            }
        }

        void requireTaskScheduler(String reason) {
            requiresTaskScheduler = true;
            if (reason != null && !reason.isBlank()) {
                taskSchedulerReasons.add(reason);
            }
        }

        String buildReason(ProjectConfiguration.TimerStrategy policy, ProjectConfiguration.TimerStrategy resolved) {
            if (resolved == ProjectConfiguration.TimerStrategy.QUARTZ && !quartzReasons.isEmpty()) {
                return "code requires quartz: " + String.join(", ", quartzReasons) +
                       (policy != resolved ? "; policy=" + policy.name().toLowerCase() : "");
            }
            if (resolved == ProjectConfiguration.TimerStrategy.TASKSCHEDULER && !taskSchedulerReasons.isEmpty()) {
                return "code requires taskscheduler: " + String.join(", ", taskSchedulerReasons) +
                       (policy != resolved ? "; policy=" + policy.name().toLowerCase() : "");
            }
            if (policy != resolved) {
                return "policy=" + policy.name().toLowerCase() + "; resolved=" + resolved.name().toLowerCase();
            }
            return "";
        }
    }

    @Override
    public String getDisplayName() {
        return "Resolve timer strategy per module";
    }

    @Override
    public String getDescription() {
        return "Determines effective timer migration strategy based on code semantics and project.yaml and " +
               "adds a marker for downstream recipes.";
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
                Path sourcePath = cu.getSourcePath();
                Path moduleRoot = extractProjectRoot(sourcePath);
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(moduleRoot);
                if (sourcePath != null && config.isTestSource(sourcePath.toString().replace('\\', '/'))) {
                    return cu;
                }
                ModuleRequirements requirements = acc.requirementsByModule
                        .computeIfAbsent(moduleRoot, ignored -> new ModuleRequirements());
                new TimerUsageVisitor(requirements).visit(cu, ctx);
                return cu;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                Path sourcePath = cu.getSourcePath();
                Path moduleRoot = extractProjectRoot(sourcePath);
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(moduleRoot);
                if (sourcePath != null && config.isTestSource(sourcePath.toString().replace('\\', '/'))) {
                    return cu;
                }
                ModuleRequirements requirements = acc.requirementsByModule
                        .getOrDefault(moduleRoot, new ModuleRequirements());

                if (!requirements.hasTimerUsage) {
                    return cu;
                }

                ModuleRequirements perCuRequirements = new ModuleRequirements();
                new TimerUsageVisitor(perCuRequirements).visit(cu, ctx);
                if (!perCuRequirements.hasTimerUsage) {
                    return cu;
                }

                ResolvedStrategy resolved = resolveStrategy(requirements, config, moduleRoot);
                String reason = requirements.buildReason(resolved.policy, resolved.strategy);
                TimerStrategyMarker marker = new TimerStrategyMarker(
                        UUID.randomUUID(),
                        resolved.strategy,
                        reason
                );

                return cu.getMarkers().findFirst(TimerStrategyMarker.class)
                        .map(existing -> {
                            if (existing.getStrategy() == resolved.strategy &&
                                Objects.equals(existing.getReason(), reason)) {
                                return cu;
                            }
                            TimerStrategyMarker updated = new TimerStrategyMarker(
                                    existing.getId(),
                                    resolved.strategy,
                                    reason
                            );
                            return cu.withMarkers(cu.getMarkers().setByType(updated));
                        })
                        .orElseGet(() -> cu.withMarkers(cu.getMarkers().add(marker)));
            }
        };
    }

    private static class ResolvedStrategy {
        final ProjectConfiguration.TimerStrategy policy;
        final ProjectConfiguration.TimerStrategy strategy;

        private ResolvedStrategy(ProjectConfiguration.TimerStrategy policy,
                                 ProjectConfiguration.TimerStrategy strategy) {
            this.policy = policy;
            this.strategy = strategy;
        }
    }

    private ResolvedStrategy resolveStrategy(ModuleRequirements requirements,
                                             ProjectConfiguration config,
                                             Path moduleRoot) {
        ProjectConfiguration.TimerStrategy policy = config.getTimerStrategy();
        ProjectConfiguration.TimerStrategy required = ProjectConfiguration.TimerStrategy.SCHEDULED;
        if (requirements.requiresQuartz) {
            required = ProjectConfiguration.TimerStrategy.QUARTZ;
        } else if (requirements.requiresTaskScheduler) {
            required = ProjectConfiguration.TimerStrategy.TASKSCHEDULER;
        }

        ProjectConfiguration.TimerStrategy resolved = maxStrategy(policy, required);
        if (config.getClusterMode() == ClusterMode.QUARTZ_JDBC) {
            resolved = ProjectConfiguration.TimerStrategy.QUARTZ;
            requirements.requireQuartz("cluster: quartz-jdbc");
        }

        if (config.getClusterMode() == ClusterMode.SHEDLOCK &&
            resolved == ProjectConfiguration.TimerStrategy.QUARTZ) {
            System.err.println("Warning: module " + moduleRoot +
                    " resolved to quartz but cluster mode is shedlock. Adjust project.yaml.");
        }

        if (requirements.hasTimerUsage && resolved != policy) {
            System.err.println("Info: module " + moduleRoot +
                    " timer strategy upgraded from " + policy.name().toLowerCase() +
                    " to " + resolved.name().toLowerCase() + " based on code semantics.");
        }

        return new ResolvedStrategy(policy, resolved);
    }

    private ProjectConfiguration.TimerStrategy maxStrategy(ProjectConfiguration.TimerStrategy a,
                                                           ProjectConfiguration.TimerStrategy b) {
        if (rank(b) > rank(a)) {
            return b;
        }
        return a;
    }

    private int rank(ProjectConfiguration.TimerStrategy strategy) {
        if (strategy == ProjectConfiguration.TimerStrategy.TASKSCHEDULER) {
            return 1;
        }
        if (strategy == ProjectConfiguration.TimerStrategy.QUARTZ) {
            return 2;
        }
        return 0;
    }

    private static class TimerUsageVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final ModuleRequirements requirements;
        private final Deque<Set<String>> timerConfigVariables = new ArrayDeque<>();
        private final Deque<Set<String>> timerServiceVariables = new ArrayDeque<>();
        private final Deque<Set<String>> timerConfigPersistentFalse = new ArrayDeque<>();

        private TimerUsageVisitor(ModuleRequirements requirements) {
            this.requirements = requirements;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            timerConfigVariables.push(new HashSet<>());
            timerServiceVariables.push(new HashSet<>());
            timerConfigPersistentFalse.push(new HashSet<>());
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
            timerConfigVariables.pop();
            timerServiceVariables.pop();
            timerConfigPersistentFalse.pop();
            return cd;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            if (method.getReturnTypeExpression() != null) {
                JavaType type = method.getReturnTypeExpression().getType();
                if (isTimerHandleType(type)) {
                    requirements.hasTimerUsage = true;
                    requirements.requireQuartz("TimerHandle return type");
                }
            }

            if (hasTimeoutAnnotation(method)) {
                requirements.hasTimerUsage = true;
            }

            if (hasScheduleAnnotation(method)) {
                requirements.hasTimerUsage = true;
                analyzeScheduleMethod(method);
            }

            return md;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multiVariable,
                                                                 ExecutionContext ctx) {
            J.VariableDeclarations vd = super.visitVariableDeclarations(multiVariable, ctx);
            JavaType type = vd.getType();

            if (isTimerServiceType(type)) {
                requirements.hasTimerUsage = true;
                for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                    timerServiceVariables.peek().add(var.getSimpleName());
                }
            }

            if (isTimerConfigType(type)) {
                for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                    timerConfigVariables.peek().add(var.getSimpleName());
                    if (isTimerConfigWithPersistentFalse(var.getInitializer())) {
                        timerConfigPersistentFalse.peek().add(var.getSimpleName());
                    }
                }
            }

            if (isTimerHandleType(type)) {
                requirements.hasTimerUsage = true;
                requirements.requireQuartz("TimerHandle usage");
            }

            return vd;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method,
                                                         ExecutionContext ctx) {
            J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);
            String name = mi.getSimpleName();

            if ("setPersistent".equals(name) && isTimerConfigSelect(mi.getSelect())) {
                requirements.hasTimerUsage = true;
                Boolean value = extractBooleanArgument(mi);
                String targetName = getTargetName(mi.getSelect());
                if (Boolean.FALSE.equals(value)) {
                    requirements.requireTaskScheduler("TimerConfig.setPersistent(false)");
                    if (targetName != null) {
                        timerConfigPersistentFalse.peek().add(targetName);
                    }
                } else {
                    requirements.requireQuartz("TimerConfig.setPersistent");
                    if (targetName != null) {
                        timerConfigPersistentFalse.peek().remove(targetName);
                    }
                }
                return mi;
            }

            if (isTimerServiceInvocation(mi)) {
                requirements.hasTimerUsage = true;

                if ("getTimers".equals(name)) {
                    requirements.requireQuartz("TimerService.getTimers");
                    return mi;
                }

                if ("createCalendarTimer".equals(name)) {
                    requirements.requireQuartz("TimerService.createCalendarTimer");
                    return mi;
                }

                if ("createTimer".equals(name) || "createSingleActionTimer".equals(name)) {
                    List<Expression> args = mi.getArguments();
                    if (args != null && args.size() >= 2) {
                        Expression configArg = args.get(1);
                        if (isTimerConfigWithPersistentFalse(configArg) ||
                            isTimerConfigVariablePersistentFalse(configArg)) {
                            requirements.requireTaskScheduler("createTimer persistent=false");
                        } else {
                            requirements.requireQuartz("createTimer persistent!=false");
                        }
                    }
                    return mi;
                }

                if ("createIntervalTimer".equals(name)) {
                    List<Expression> args = mi.getArguments();
                    if (args != null && args.size() >= 3) {
                        Expression configArg = args.get(2);
                        if (isTimerConfigWithPersistentFalse(configArg) ||
                            isTimerConfigVariablePersistentFalse(configArg)) {
                            requirements.requireTaskScheduler("createIntervalTimer persistent=false");
                        } else {
                            requirements.requireQuartz("createIntervalTimer persistent!=false");
                        }
                    }
                    return mi;
                }
            }

            if (isTimerInvocation(mi)) {
                requirements.hasTimerUsage = true;
                if ("getSchedule".equals(name)) {
                    requirements.requireQuartz("Timer.getSchedule");
                }
                if ("getHandle".equals(name)) {
                    requirements.requireQuartz("Timer.getHandle");
                }
            }

            return mi;
        }

        private void analyzeScheduleMethod(J.MethodDeclaration method) {
            List<ScheduleConfig> configs = extractAllScheduleConfigs(method);
            if (configs.isEmpty()) {
                return;
            }

            boolean timerParam = analyzeTimerParameter(method).hasTimerParameter();
            boolean taskSchedulerSafe = true;

            for (ScheduleConfig config : configs) {
                if (config.isEffectivelyPersistent()) {
                    requirements.requireQuartz("@Schedule persistent=true");
                }
                if (config.hasNonLiterals()) {
                    requirements.requireQuartz("@Schedule non-literals");
                }
                if (config.hasYearRestriction()) {
                    requirements.requireQuartz("@Schedule year");
                }
                if (config.getTimezone() != null && !config.getTimezone().isBlank()) {
                    requirements.requireQuartz("@Schedule timezone");
                }

                if (!isSafeForTaskScheduler(config)) {
                    taskSchedulerSafe = false;
                }
            }

            if (timerParam) {
                if (!requirements.requiresQuartz && taskSchedulerSafe) {
                    requirements.requireTaskScheduler("@Schedule Timer parameter");
                } else if (!taskSchedulerSafe) {
                    requirements.requireQuartz("@Schedule Timer parameter not safe");
                }
            }
        }

        private boolean isSafeForTaskScheduler(ScheduleConfig config) {
            return Boolean.FALSE.equals(config.getPersistent()) &&
                   !config.hasNonLiterals() &&
                   !config.hasYearRestriction() &&
                   (config.getTimezone() == null || config.getTimezone().isBlank());
        }

        private boolean hasScheduleAnnotation(J.MethodDeclaration method) {
            for (J.Annotation ann : method.getLeadingAnnotations()) {
                if (isScheduleAnnotation(ann) || isSchedulesAnnotation(ann)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasTimeoutAnnotation(J.MethodDeclaration method) {
            for (J.Annotation ann : method.getLeadingAnnotations()) {
                if (ann.getType() != null && (TypeUtils.isOfClassType(ann.getType(), JAKARTA_TIMEOUT) ||
                    TypeUtils.isOfClassType(ann.getType(), JAVAX_TIMEOUT))) {
                    return true;
                }
                if ("Timeout".equals(ann.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }

        private boolean isTimerServiceInvocation(J.MethodInvocation method) {
            JavaType.Method type = method.getMethodType();
            if (type != null && isTimerServiceType(type.getDeclaringType())) {
                return true;
            }
            Expression select = method.getSelect();
            if (select instanceof J.Identifier) {
                String name = ((J.Identifier) select).getSimpleName();
                return timerServiceVariables.peek().contains(name);
            }
            return false;
        }

        private boolean isTimerInvocation(J.MethodInvocation method) {
            JavaType.Method type = method.getMethodType();
            if (type != null && isTimerType(type.getDeclaringType())) {
                return true;
            }
            return false;
        }

        private boolean isTimerConfigSelect(Expression select) {
            if (select == null) {
                return false;
            }
            JavaType type = select.getType();
            if (isTimerConfigType(type)) {
                return true;
            }
            if (select instanceof J.Identifier) {
                String name = ((J.Identifier) select).getSimpleName();
                return timerConfigVariables.peek().contains(name);
            }
            return false;
        }

        private String getTargetName(Expression select) {
            if (select instanceof J.Identifier) {
                return ((J.Identifier) select).getSimpleName();
            }
            if (select instanceof J.FieldAccess) {
                return ((J.FieldAccess) select).getSimpleName();
            }
            return null;
        }

        private Boolean extractBooleanArgument(J.MethodInvocation mi) {
            List<Expression> args = mi.getArguments();
            if (args == null || args.isEmpty()) {
                return null;
            }
            Expression arg = args.get(0);
            if (arg instanceof J.Literal) {
                Object value = ((J.Literal) arg).getValue();
                if (value instanceof Boolean) {
                    return (Boolean) value;
                }
            }
            return null;
        }

        private boolean isTimerServiceType(JavaType type) {
            return TypeUtils.isOfClassType(type, JAKARTA_TIMER_SERVICE) ||
                   TypeUtils.isOfClassType(type, JAVAX_TIMER_SERVICE);
        }

        private boolean isTimerType(JavaType type) {
            return TypeUtils.isOfClassType(type, JAKARTA_TIMER) ||
                   TypeUtils.isOfClassType(type, JAVAX_TIMER);
        }

        private boolean isTimerConfigType(JavaType type) {
            return TypeUtils.isOfClassType(type, JAKARTA_TIMER_CONFIG) ||
                   TypeUtils.isOfClassType(type, JAVAX_TIMER_CONFIG);
        }

        private boolean isTimerHandleType(JavaType type) {
            return TypeUtils.isOfClassType(type, JAKARTA_TIMER_HANDLE) ||
                   TypeUtils.isOfClassType(type, JAVAX_TIMER_HANDLE);
        }

        private boolean isTimerConfigWithPersistentFalse(Expression expr) {
            if (expr == null) {
                return false;
            }
            JavaType type = expr.getType();
            boolean isTimerConfig = type != null && (
                TypeUtils.isOfClassType(type, JAKARTA_TIMER_CONFIG) ||
                TypeUtils.isOfClassType(type, JAVAX_TIMER_CONFIG)
            );

            if (!isTimerConfig) {
                if (!(expr instanceof J.NewClass)) {
                    return false;
                }
                J.NewClass newClass = (J.NewClass) expr;
                if (newClass.getClazz() == null) {
                    return false;
                }
                String className = newClass.getClazz() instanceof J.Identifier
                        ? ((J.Identifier) newClass.getClazz()).getSimpleName()
                        : newClass.getClazz().toString();
                if (!"TimerConfig".equals(className)) {
                    return false;
                }
            }

            if (expr instanceof J.NewClass) {
                J.NewClass newClass = (J.NewClass) expr;
                List<Expression> args = newClass.getArguments();
                if (args != null && args.size() == 2) {
                    Expression persistentArg = args.get(1);
                    if (persistentArg instanceof J.Literal) {
                        Object value = ((J.Literal) persistentArg).getValue();
                        return Boolean.FALSE.equals(value);
                    }
                }
            }

            return false;
        }

        private boolean isTimerConfigVariablePersistentFalse(Expression expr) {
            if (!(expr instanceof J.Identifier)) {
                return false;
            }
            String name = ((J.Identifier) expr).getSimpleName();
            return timerConfigPersistentFalse.peek().contains(name);
        }
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
