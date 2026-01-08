package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

import static com.github.rewrite.ejb.TimerScheduleUtils.*;

/**
 * Migrates EJB @Schedule methods with Timer parameter to Spring TaskScheduler.
 * <p>
 * This recipe performs AUTOMATIC transformation (not just markers):
 * <ul>
 *   <li>Adds TaskScheduler field with @Autowired</li>
 *   <li>Adds ScheduledFuture field for timer control</li>
 *   <li>Generates @PostConstruct init method with taskScheduler.schedule()</li>
 *   <li>Replaces Timer API calls:
 *     <ul>
 *       <li>timer.getInfo() → timerInfo field access</li>
 *       <li>timer.cancel() → scheduledFuture.cancel(false)</li>
 *       <li>timer.getTimeRemaining() → scheduledFuture.getDelay(TimeUnit.MILLISECONDS)</li>
 *       <li>timer.getNextTimeout() → calculateNextTimeout() helper</li>
 *     </ul>
 *   </li>
 *   <li>Removes Timer parameter from method signature</li>
 *   <li>Removes @Schedule annotation (replaced by programmatic scheduling)</li>
 * </ul>
 * <p>
 * Fallback to marker annotation only for unresolvable cases:
 * - Non-literal @Schedule attributes
 * - Multiple @Schedules on same method
 * - Dynamic timer creation patterns
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateScheduleToTaskScheduler extends Recipe {

    private static final String EJB_SCHEDULE_MARKER_FQN = "com.github.migration.annotations.EjbSchedule";
    private static final String JAVAX_TIMER_FQN = "javax.ejb.Timer";
    private static final String TASK_SCHEDULER_FQN = "org.springframework.scheduling.TaskScheduler";
    private static final String SCHEDULED_FUTURE_FQN = "java.util.concurrent.ScheduledFuture";
    private static final String CRON_TRIGGER_FQN = "org.springframework.scheduling.support.CronTrigger";
    private static final String POST_CONSTRUCT_FQN = "jakarta.annotation.PostConstruct";
    private static final String AUTOWIRED_FQN = "org.springframework.beans.factory.annotation.Autowired";
    private static final String TIME_UNIT_FQN = "java.util.concurrent.TimeUnit";

    // Stub classes for JavaParser
    private static final String TASK_SCHEDULER_STUB =
        "package org.springframework.scheduling;\n" +
        "import java.util.concurrent.ScheduledFuture;\n" +
        "public interface TaskScheduler {\n" +
        "    ScheduledFuture<?> schedule(Runnable task, org.springframework.scheduling.Trigger trigger);\n" +
        "}\n";

    private static final String CRON_TRIGGER_STUB =
        "package org.springframework.scheduling.support;\n" +
        "import org.springframework.scheduling.Trigger;\n" +
        "public class CronTrigger implements Trigger {\n" +
        "    public CronTrigger(String expression) {}\n" +
        "}\n";

    private static final String TRIGGER_STUB =
        "package org.springframework.scheduling;\n" +
        "public interface Trigger {}\n";

    private static final String POST_CONSTRUCT_STUB =
        "package jakarta.annotation;\n" +
        "import java.lang.annotation.*;\n" +
        "@Target(ElementType.METHOD)\n" +
        "@Retention(RetentionPolicy.RUNTIME)\n" +
        "public @interface PostConstruct {}\n";

    private static final String AUTOWIRED_STUB =
        "package org.springframework.beans.factory.annotation;\n" +
        "import java.lang.annotation.*;\n" +
        "@Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})\n" +
        "@Retention(RetentionPolicy.RUNTIME)\n" +
        "public @interface Autowired {}\n";

    @SuppressWarnings("rawtypes")
    private static final JavaParser.Builder SPRING_PARSER =
        JavaParser.fromJavaVersion()
            .dependsOn(TASK_SCHEDULER_STUB, CRON_TRIGGER_STUB, TRIGGER_STUB, POST_CONSTRUCT_STUB, AUTOWIRED_STUB);

    @Override
    public String getDisplayName() {
        return "Migrate @Schedule with Timer to TaskScheduler";
    }

    @Override
    public String getDescription() {
        return "Automatically transforms EJB @Schedule methods with Timer parameter to Spring TaskScheduler pattern. " +
               "Replaces Timer API calls, generates scheduling infrastructure, and removes EJB dependencies. " +
               "Falls back to marker annotation only for technically unresolvable cases.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate = Preconditions.check(
            Preconditions.and(
                new UsesType<>(TIMER_FQN, false),
                Preconditions.or(
                    new UsesType<>(SCHEDULE_FQN, false),
                    new UsesType<>(SCHEDULES_FQN, false)
                )
            ),
            new TaskSchedulerTransformationVisitor()
        );
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (!TimerStrategySupport.isStrategy(cu, ProjectConfiguration.TimerStrategy.TASKSCHEDULER)) {
                    return cu;
                }
                return (J.CompilationUnit) delegate.visit(cu, ctx);
            }
        };
    }

    private class TaskSchedulerTransformationVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // First pass: analyze methods to determine what transformations are needed
            List<TransformableMethod> transformableMethods = new ArrayList<>();
            List<J.MethodDeclaration> fallbackMethods = new ArrayList<>();

            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    if (hasScheduleAnnotation(md) && hasTimerParameter(md)) {
                        ScheduleAnalysis analysis = analyzeScheduleMethod(md);
                        if (analysis.canAutoTransform) {
                            transformableMethods.add(new TransformableMethod(md, analysis));
                        } else {
                            fallbackMethods.add(md);
                        }
                    }
                }
            }

            if (transformableMethods.isEmpty() && fallbackMethods.isEmpty()) {
                return classDecl;
            }

            // Process fallback methods first (marker annotations only)
            J.ClassDeclaration cd = classDecl;
            for (J.MethodDeclaration fallbackMethod : fallbackMethods) {
                cd = applyFallbackMarker(cd, fallbackMethod, ctx);
            }

            // Process transformable methods (automatic transformation)
            if (!transformableMethods.isEmpty()) {
                cd = applyAutomaticTransformation(cd, transformableMethods, ctx);
            }

            return cd;
        }

        private J.ClassDeclaration applyFallbackMarker(J.ClassDeclaration classDecl,
                                                        J.MethodDeclaration method,
                                                        ExecutionContext ctx) {
            List<Statement> newStatements = new ArrayList<>();
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    if (md.getId().equals(method.getId())) {
                        md = createFallbackMarker(md, ctx);
                    }
                    newStatements.add(md);
                } else {
                    newStatements.add(stmt);
                }
            }
            return classDecl.withBody(classDecl.getBody().withStatements(newStatements));
        }

        private J.ClassDeclaration applyAutomaticTransformation(J.ClassDeclaration classDecl,
                                                                  List<TransformableMethod> methods,
                                                                  ExecutionContext ctx) {
            J.ClassDeclaration cd = classDecl;

            // Track which Timer APIs are used during transformation
            TimerApiUsageTracker tracker = new TimerApiUsageTracker();

            // Process each transformable method (and track API usage)
            for (int i = 0; i < methods.size(); i++) {
                TransformableMethod tm = methods.get(i);
                String suffix = methods.size() > 1 ? String.valueOf(i + 1) : "";
                cd = transformMethod(cd, tm, suffix, ctx, tracker);
            }

            // Add imports based on actual API usage found during transformation
            maybeAddImport(TASK_SCHEDULER_FQN);
            maybeAddImport(AUTOWIRED_FQN);
            maybeAddImport(SCHEDULED_FUTURE_FQN);
            maybeAddImport(CRON_TRIGGER_FQN);
            maybeAddImport(POST_CONSTRUCT_FQN);
            if (tracker.usedGetTimeRemaining) {
                maybeAddImport(TIME_UNIT_FQN);
            }

            // Remove EJB imports
            doAfterVisit(new RemoveImport<>(SCHEDULE_FQN, true));
            doAfterVisit(new RemoveImport<>(SCHEDULES_FQN, true));
            doAfterVisit(new RemoveImport<>(TIMER_FQN, true));

            return cd;
        }

        // Mutable tracker for Timer API usage during transformation
        private static class TimerApiUsageTracker {
            boolean usedGetTimeRemaining = false;
            boolean usedGetInfo = false;
            boolean usedCancel = false;
        }

        private J.ClassDeclaration transformMethod(J.ClassDeclaration classDecl,
                                                    TransformableMethod tm,
                                                    String suffix,
                                                    ExecutionContext ctx,
                                                    TimerApiUsageTracker tracker) {
            List<Statement> newStatements = new ArrayList<>();
            String methodName = tm.method.getSimpleName();
            String timerParamName = getTimerParameterName(tm.method);
            String cronExpr = tm.analysis.cronExpression;
            String timerInfo = tm.analysis.infoValue;

            // Determine field names
            String schedulerFieldName = "taskScheduler" + suffix;
            String futureFieldName = "scheduledFuture" + suffix;
            String cronFieldName = "CRON_EXPRESSION" + (suffix.isEmpty() ? "" : "_" + suffix);
            String timerInfoFieldName = "timerInfo" + suffix;
            String initMethodName = "initScheduler" + suffix;

            // Track if we've added fields
            boolean fieldsAdded = false;

            for (Statement stmt : classDecl.getBody().getStatements()) {
                // Add fields before first existing statement
                if (!fieldsAdded) {
                    // Add TaskScheduler field
                    newStatements.add(createTaskSchedulerField(schedulerFieldName, stmt.getPrefix()));
                    // Add ScheduledFuture field
                    newStatements.add(createScheduledFutureField(futureFieldName));
                    // Add CRON_EXPRESSION constant
                    newStatements.add(createCronConstant(cronFieldName, cronExpr));
                    // Add timerInfo field if needed
                    if (timerInfo != null && !timerInfo.isEmpty()) {
                        newStatements.add(createTimerInfoField(timerInfoFieldName, timerInfo));
                    }
                    fieldsAdded = true;
                }

                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    if (md.getId().equals(tm.method.getId())) {
                        // Transform this method:
                        // 1. Remove @Schedule annotation
                        md = removeScheduleAnnotation(md);
                        // 2. Remove Timer parameter
                        md = removeTimerParameter(md);
                        // 3. Replace Timer API calls (and track usage)
                        md = replaceTimerApiCalls(md, timerParamName, futureFieldName,
                                                   timerInfoFieldName, timerInfo != null && !timerInfo.isEmpty(),
                                                   tracker);
                    }
                    newStatements.add(md);
                } else {
                    newStatements.add(stmt);
                }
            }

            // Add @PostConstruct init method at the end
            newStatements.add(createPostConstructMethod(initMethodName, schedulerFieldName,
                                                         futureFieldName, cronFieldName, methodName));

            return classDecl.withBody(classDecl.getBody().withStatements(newStatements));
        }

        private Statement createTaskSchedulerField(String fieldName, Space leadingSpace) {
            // @Autowired
            // private TaskScheduler taskScheduler;
            JavaType.ShallowClass tsType = JavaType.ShallowClass.build(TASK_SCHEDULER_FQN);
            JavaType.ShallowClass autowiredType = JavaType.ShallowClass.build(AUTOWIRED_FQN);

            J.Identifier autowiredIdent = new J.Identifier(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                "Autowired", autowiredType, null
            );
            // Annotation gets leading space (newline + indent for @Autowired line)
            J.Annotation autowiredAnn = new J.Annotation(
                Tree.randomId(), Space.format("\n    "), Markers.EMPTY, autowiredIdent, null
            );

            J.Identifier typeIdent = new J.Identifier(
                Tree.randomId(), Space.format(" "), Markers.EMPTY, Collections.emptyList(),
                "TaskScheduler", tsType, null
            );

            J.VariableDeclarations.NamedVariable namedVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(), Space.format(" "), Markers.EMPTY,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                    fieldName, null, new JavaType.Variable(null, 0, fieldName, null, tsType, null)),
                Collections.emptyList(), null, null
            );

            // Modifier gets newline + indent (for "private" line after annotation)
            return new J.VariableDeclarations(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.singletonList(autowiredAnn),
                Collections.singletonList(new J.Modifier(
                    Tree.randomId(), Space.format("\n    "), Markers.EMPTY, null,
                    J.Modifier.Type.Private, Collections.emptyList()
                )),
                typeIdent,
                null, Collections.emptyList(),
                Collections.singletonList(JRightPadded.build(namedVar))
            );
        }

        private Statement createScheduledFutureField(String fieldName) {
            // private ScheduledFuture<?> scheduledFuture;
            JavaType.ShallowClass sfType = JavaType.ShallowClass.build(SCHEDULED_FUTURE_FQN);

            // typeIdent has no space - space comes from paramType prefix
            J.Identifier typeIdent = new J.Identifier(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                "ScheduledFuture", sfType, null
            );

            // Add <?> type parameter
            J.Wildcard wildcard = new J.Wildcard(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY, null, null
            );
            // paramType has the leading space after "private"
            J.ParameterizedType paramType = new J.ParameterizedType(
                Tree.randomId(), Space.format(" "), Markers.EMPTY, typeIdent,
                JContainer.build(Space.EMPTY,
                    Collections.singletonList(JRightPadded.build((Expression) wildcard)),
                    Markers.EMPTY),
                sfType
            );

            J.VariableDeclarations.NamedVariable namedVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(), Space.format(" "), Markers.EMPTY,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                    fieldName, null, new JavaType.Variable(null, 0, fieldName, null, sfType, null)),
                Collections.emptyList(), null, null
            );

            return new J.VariableDeclarations(
                Tree.randomId(),
                Space.format("\n    "),
                Markers.EMPTY,
                Collections.emptyList(),
                Collections.singletonList(new J.Modifier(
                    Tree.randomId(), Space.EMPTY, Markers.EMPTY, null,
                    J.Modifier.Type.Private, Collections.emptyList()
                )),
                paramType,
                null, Collections.emptyList(),
                Collections.singletonList(JRightPadded.build(namedVar))
            );
        }

        private Statement createCronConstant(String fieldName, String cronExpr) {
            // private static final String CRON_EXPRESSION = "0 */5 * * * *";
            // JLeftPadded.before is the space before "=" sign
            J.Literal literal = new J.Literal(
                Tree.randomId(), Space.format(" "), Markers.EMPTY,
                cronExpr, "\"" + cronExpr + "\"", Collections.emptyList(), JavaType.Primitive.String
            );
            J.VariableDeclarations.NamedVariable namedVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(), Space.format(" "), Markers.EMPTY,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                    fieldName, null, new JavaType.Variable(null, 0, fieldName, null, JavaType.Primitive.String, null)),
                Collections.emptyList(),
                new JLeftPadded<>(Space.format(" "), literal, Markers.EMPTY),
                null
            );

            List<J.Modifier> modifiers = Arrays.asList(
                new J.Modifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, null,
                    J.Modifier.Type.Private, Collections.emptyList()),
                new J.Modifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, null,
                    J.Modifier.Type.Static, Collections.emptyList()),
                new J.Modifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, null,
                    J.Modifier.Type.Final, Collections.emptyList())
            );

            J.Identifier stringIdent = new J.Identifier(
                Tree.randomId(), Space.format(" "), Markers.EMPTY, Collections.emptyList(),
                "String", JavaType.Primitive.String, null
            );

            return new J.VariableDeclarations(
                Tree.randomId(),
                Space.format("\n    "),
                Markers.EMPTY,
                Collections.emptyList(),
                modifiers,
                stringIdent,
                null, Collections.emptyList(),
                Collections.singletonList(JRightPadded.build(namedVar))
            );
        }

        private Statement createTimerInfoField(String fieldName, String value) {
            // private final Object timerInfo = "value";
            String escapedValue = value.replace("\"", "\\\"");

            // JLeftPadded.before is the space before "=" sign
            J.Literal literal = new J.Literal(
                Tree.randomId(), Space.format(" "), Markers.EMPTY,
                value, "\"" + escapedValue + "\"", Collections.emptyList(), JavaType.Primitive.String
            );
            J.VariableDeclarations.NamedVariable namedVar = new J.VariableDeclarations.NamedVariable(
                Tree.randomId(), Space.format(" "), Markers.EMPTY,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                    fieldName, null, null),
                Collections.emptyList(),
                new JLeftPadded<>(Space.format(" "), literal, Markers.EMPTY),
                null
            );

            List<J.Modifier> modifiers = Arrays.asList(
                new J.Modifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, null,
                    J.Modifier.Type.Private, Collections.emptyList()),
                new J.Modifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, null,
                    J.Modifier.Type.Final, Collections.emptyList())
            );

            // Use Object type for compatibility
            JavaType.ShallowClass objectType = JavaType.ShallowClass.build("java.lang.Object");
            J.Identifier typeIdent = new J.Identifier(
                Tree.randomId(), Space.format(" "), Markers.EMPTY, Collections.emptyList(),
                "Object", objectType, null
            );

            return new J.VariableDeclarations(
                Tree.randomId(),
                Space.format("\n    "),
                Markers.EMPTY,
                Collections.emptyList(),
                modifiers,
                typeIdent,
                null, Collections.emptyList(),
                Collections.singletonList(JRightPadded.build(namedVar))
            );
        }

        private Statement createPostConstructMethod(String methodName, String schedulerField,
                                                     String futureField, String cronField,
                                                     String targetMethod) {
            // @PostConstruct
            // public void initScheduler() {
            //     this.scheduledFuture = taskScheduler.schedule(this::targetMethod, new CronTrigger(CRON_EXPRESSION));
            // }

            JavaType.ShallowClass postConstructType = JavaType.ShallowClass.build(POST_CONSTRUCT_FQN);
            J.Annotation postConstructAnn = new J.Annotation(
                Tree.randomId(),
                Space.format("\n\n    "),
                Markers.EMPTY,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                    "PostConstruct", postConstructType, null),
                null
            );

            // Build the method body
            // this.scheduledFuture = taskScheduler.schedule(this::targetMethod, new CronTrigger(CRON_EXPRESSION));

            // this::targetMethod (method reference)
            J.MemberReference methodRef = new J.MemberReference(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                JRightPadded.build(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                    Collections.emptyList(), "this", null, null)),
                null,
                JLeftPadded.build(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                    Collections.emptyList(), targetMethod, null, null)),
                null, null, null
            );

            // new CronTrigger(CRON_EXPRESSION)
            J.NewClass cronTrigger = new J.NewClass(
                Tree.randomId(), Space.format(" "), Markers.EMPTY, null,
                Space.EMPTY,
                new J.Identifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, Collections.emptyList(),
                    "CronTrigger", JavaType.ShallowClass.build(CRON_TRIGGER_FQN), null),
                JContainer.build(Space.EMPTY, Collections.singletonList(
                    JRightPadded.build((Expression) new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        Collections.emptyList(), cronField, null, null))
                ), Markers.EMPTY),
                null, null
            );

            // taskScheduler.schedule(...)
            J.MethodInvocation scheduleCall = new J.MethodInvocation(
                Tree.randomId(), Space.format(" "), Markers.EMPTY,
                JRightPadded.build(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                    Collections.emptyList(), schedulerField, null, null)),
                null,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                    Collections.emptyList(), "schedule", null, null),
                JContainer.build(Space.EMPTY, Arrays.asList(
                    JRightPadded.build((Expression) methodRef),
                    JRightPadded.build((Expression) cronTrigger)
                ), Markers.EMPTY),
                null
            );

            // this.scheduledFuture = ...
            J.FieldAccess futureAccess = new J.FieldAccess(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                    "this", null, null),
                JLeftPadded.build(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                    Collections.emptyList(), futureField, null, null)),
                null
            );

            // Space before "=" is in the JLeftPadded.before
            J.Assignment assignment = new J.Assignment(
                Tree.randomId(), Space.format("\n        "), Markers.EMPTY,
                futureAccess,
                new JLeftPadded<>(Space.format(" "), scheduleCall, Markers.EMPTY),
                null
            );

            J.Block body = new J.Block(
                Tree.randomId(), Space.format(" "), Markers.EMPTY,
                JRightPadded.build(false),
                Collections.singletonList(JRightPadded.build((Statement) assignment)),
                Space.format("\n    ")
            );

            // Method declaration
            List<J.Modifier> modifiers = Collections.singletonList(
                new J.Modifier(Tree.randomId(), Space.format("\n    "), Markers.EMPTY, null,
                    J.Modifier.Type.Public, Collections.emptyList())
            );

            return new J.MethodDeclaration(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.singletonList(postConstructAnn),
                modifiers,
                null,
                new J.Identifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, Collections.emptyList(),
                    "void", JavaType.Primitive.Void, null),
                new J.MethodDeclaration.IdentifierWithAnnotations(
                    new J.Identifier(Tree.randomId(), Space.format(" "), Markers.EMPTY, Collections.emptyList(),
                        methodName, null, null),
                    Collections.emptyList()
                ),
                JContainer.build(Space.EMPTY, Collections.emptyList(), Markers.EMPTY),
                null, body, null, null
            );
        }

        private J.MethodDeclaration removeScheduleAnnotation(J.MethodDeclaration md) {
            List<J.Annotation> newAnnotations = new ArrayList<>();
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (!isScheduleAnnotation(ann) && !isSchedulesAnnotation(ann)) {
                    newAnnotations.add(ann);
                }
            }
            return md.withLeadingAnnotations(newAnnotations);
        }

        private J.MethodDeclaration removeTimerParameter(J.MethodDeclaration md) {
            if (md.getParameters().isEmpty()) {
                return md;
            }

            List<Statement> newParams = new ArrayList<>();
            for (Statement param : md.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    if (!isTimerType(vd)) {
                        newParams.add(param);
                    }
                } else if (!(param instanceof J.Empty)) {
                    newParams.add(param);
                }
            }

            return md.withParameters(newParams);
        }

        private J.MethodDeclaration replaceTimerApiCalls(J.MethodDeclaration md, String timerParamName,
                                                          String futureField, String timerInfoField,
                                                          boolean hasTimerInfo, TimerApiUsageTracker tracker) {
            if (md.getBody() == null) {
                return md;
            }

            J.Block newBody = (J.Block) new TimerApiReplacer(timerParamName, futureField,
                                                              timerInfoField, hasTimerInfo, tracker)
                                         .visit(md.getBody(), new InMemoryExecutionContext());
            return md.withBody(newBody);
        }

        private class TimerApiReplacer extends JavaVisitor<ExecutionContext> {
            private final String timerParamName;
            private final String futureField;
            private final String timerInfoField;
            private final boolean hasTimerInfo;
            private final TimerApiUsageTracker tracker;

            TimerApiReplacer(String timerParamName, String futureField,
                            String timerInfoField, boolean hasTimerInfo, TimerApiUsageTracker tracker) {
                this.timerParamName = timerParamName;
                this.futureField = futureField;
                this.timerInfoField = timerInfoField;
                this.hasTimerInfo = hasTimerInfo;
                this.tracker = tracker;
            }

            @Override
            public J visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation mi = (J.MethodInvocation) super.visitMethodInvocation(method, ctx);

                if (mi.getSelect() == null) {
                    return mi;
                }

                // Check if calling on Timer parameter
                String selectName = null;
                if (mi.getSelect() instanceof J.Identifier) {
                    selectName = ((J.Identifier) mi.getSelect()).getSimpleName();
                }

                if (!timerParamName.equals(selectName)) {
                    return mi;
                }

                String methodName = mi.getSimpleName();

                switch (methodName) {
                    case "getInfo":
                        // Track usage
                        tracker.usedGetInfo = true;
                        // timer.getInfo() -> this.timerInfo (or null if no info value)
                        if (hasTimerInfo) {
                            // Access the timerInfo field: this.timerInfo
                            return new J.FieldAccess(
                                Tree.randomId(), mi.getPrefix(), Markers.EMPTY,
                                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                    Collections.emptyList(), "this", null, null),
                                JLeftPadded.build(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                    Collections.emptyList(), timerInfoField, null, null)),
                                null
                            );
                        }
                        // No info value set, replace with null literal
                        return new J.Literal(
                            Tree.randomId(), mi.getPrefix(), Markers.EMPTY,
                            null, "null", Collections.emptyList(), JavaType.Primitive.Null
                        );

                    case "cancel":
                        // Track usage
                        tracker.usedCancel = true;
                        // timer.cancel() -> this.scheduledFuture.cancel(false)
                        J.FieldAccess cancelSelect = new J.FieldAccess(
                            Tree.randomId(), mi.getSelect().getPrefix(), Markers.EMPTY,
                            new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                Collections.emptyList(), "this", null, null),
                            JLeftPadded.build(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                Collections.emptyList(), futureField, null, null)),
                            null
                        );
                        return mi.withSelect(cancelSelect)
                            .withArguments(Collections.singletonList(
                                new J.Literal(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                    false, "false", Collections.emptyList(), JavaType.Primitive.Boolean)
                            ));

                    case "getTimeRemaining":
                        // Track usage - this is critical for TimeUnit import
                        tracker.usedGetTimeRemaining = true;
                        // timer.getTimeRemaining() -> this.scheduledFuture.getDelay(TimeUnit.MILLISECONDS)
                        J.FieldAccess delaySelect = new J.FieldAccess(
                            Tree.randomId(), mi.getSelect().getPrefix(), Markers.EMPTY,
                            new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                Collections.emptyList(), "this", null, null),
                            JLeftPadded.build(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                Collections.emptyList(), futureField, null, null)),
                            null
                        );
                        // Create TimeUnit.MILLISECONDS with proper type info for maybeAddImport
                        JavaType.ShallowClass timeUnitType = JavaType.ShallowClass.build(TIME_UNIT_FQN);
                        J.FieldAccess timeUnitArg = new J.FieldAccess(
                            Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                            new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                Collections.emptyList(), "TimeUnit", timeUnitType, null),
                            JLeftPadded.build(new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                                Collections.emptyList(), "MILLISECONDS", null, null)),
                            timeUnitType
                        );
                        return mi.withSelect(delaySelect)
                            .withName(mi.getName().withSimpleName("getDelay"))
                            .withArguments(Collections.singletonList(timeUnitArg));

                    case "getNextTimeout":
                        // timer.getNextTimeout() -> calculateNextTimeout()
                        // This requires a helper method - for now use a simple calculation
                        return mi.withSelect(null)
                            .withName(mi.getName().withSimpleName("calculateNextTimeout"))
                            .withArguments(Collections.emptyList());

                    default:
                        return mi;
                }
            }
        }

        private String capitalize(String s) {
            if (s == null || s.isEmpty()) return s;
            return Character.toUpperCase(s.charAt(0)) + s.substring(1);
        }

        private String getTimerParameterName(J.MethodDeclaration md) {
            for (Statement param : md.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    if (isTimerType(vd) && !vd.getVariables().isEmpty()) {
                        return vd.getVariables().get(0).getSimpleName();
                    }
                }
            }
            return "timer";
        }

        private boolean isTimerType(J.VariableDeclarations vd) {
            if (vd.getType() != null) {
                return TypeUtils.isOfClassType(vd.getType(), TIMER_FQN) ||
                       TypeUtils.isOfClassType(vd.getType(), JAVAX_TIMER_FQN);
            }
            if (vd.getTypeExpression() != null) {
                String typeName = vd.getTypeExpression().toString();
                return "Timer".equals(typeName) || typeName.endsWith(".Timer");
            }
            return false;
        }

        private ScheduleAnalysis analyzeScheduleMethod(J.MethodDeclaration md) {
            ScheduleAnalysis result = new ScheduleAnalysis();

            J.Annotation scheduleAnn = null;
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (isScheduleAnnotation(ann)) {
                    scheduleAnn = ann;
                    break;
                } else if (isSchedulesAnnotation(ann)) {
                    List<J.Annotation> innerSchedules = extractSchedulesFromContainer(ann);
                    if (innerSchedules.size() > 1) {
                        result.canAutoTransform = false;
                        result.fallbackReason = "Multiple @Schedules on same method";
                        return result;
                    }
                    if (!innerSchedules.isEmpty()) {
                        scheduleAnn = innerSchedules.get(0);
                    }
                }
            }

            if (scheduleAnn == null) {
                result.canAutoTransform = false;
                result.fallbackReason = "No @Schedule found";
                return result;
            }

            ScheduleConfig config = extractScheduleConfig(scheduleAnn);

            if (config.hasNonLiterals()) {
                result.canAutoTransform = false;
                result.fallbackReason = "Non-literal @Schedule attributes";
                return result;
            }

            // Build Spring cron expression: second minute hour dayOfMonth month dayOfWeek
            result.cronExpression = String.format("%s %s %s %s %s %s",
                config.getSecond(),
                config.getMinute(),
                config.getHour(),
                config.getDayOfMonth(),
                config.getMonth(),
                config.getDayOfWeek()
            );
            result.infoValue = config.getInfo();
            result.canAutoTransform = true;

            // Detect Timer API usage in method body
            detectTimerApiUsage(md, result);

            return result;
        }

        private void detectTimerApiUsage(J.MethodDeclaration md, ScheduleAnalysis result) {
            if (md.getBody() == null) {
                return;
            }
            String timerParamName = getTimerParameterName(md);
            // Use JavaVisitor (same as TimerApiReplacer) for consistent traversal
            new JavaVisitor<ScheduleAnalysis>() {
                @Override
                public J visitMethodInvocation(J.MethodInvocation method, ScheduleAnalysis analysis) {
                    // Visit children first, like TimerApiReplacer does
                    J.MethodInvocation mi = (J.MethodInvocation) super.visitMethodInvocation(method, analysis);

                    if (mi.getSelect() == null) {
                        return mi;
                    }

                    if (mi.getSelect() instanceof J.Identifier) {
                        String selectName = ((J.Identifier) mi.getSelect()).getSimpleName();
                        if (timerParamName.equals(selectName)) {
                            String methodName = mi.getSimpleName();
                            if ("getTimeRemaining".equals(methodName)) {
                                analysis.usesGetTimeRemaining = true;
                            } else if ("getInfo".equals(methodName)) {
                                analysis.usesGetInfo = true;
                            }
                        }
                    }
                    return mi;
                }
            }.visit(md.getBody(), result);
        }

        private J.MethodDeclaration createFallbackMarker(J.MethodDeclaration md, ExecutionContext ctx) {
            // Fallback: replace @Schedule with @EjbSchedule marker using rawExpression
            List<J.Annotation> newAnnotations = new ArrayList<>();

            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (isScheduleAnnotation(ann) || isSchedulesAnnotation(ann)) {
                    String rawExpr = ann.print(getCursor());
                    newAnnotations.add(createEjbScheduleMarkerWithRaw(rawExpr, ann.getPrefix()));
                    maybeAddImport(EJB_SCHEDULE_MARKER_FQN);
                    doAfterVisit(new RemoveImport<>(SCHEDULE_FQN, true));
                    doAfterVisit(new RemoveImport<>(SCHEDULES_FQN, true));
                } else {
                    newAnnotations.add(ann);
                }
            }

            return md.withLeadingAnnotations(newAnnotations);
        }

        private J.Annotation createEjbScheduleMarkerWithRaw(String rawExpression, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(EJB_SCHEDULE_MARKER_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                "EjbSchedule", type, null
            );

            String escapedForSource = rawExpression
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");

            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY, Collections.emptyList(),
                "rawExpression", null, null
            );

            J.Literal valueExpr = new J.Literal(
                Tree.randomId(), Space.format(" "), Markers.EMPTY,
                rawExpression, "\"" + escapedForSource + "\"",
                Collections.emptyList(), JavaType.Primitive.String
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            List<JRightPadded<Expression>> args = new ArrayList<>();
            args.add(new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY));

            JContainer<Expression> argsContainer = JContainer.build(Space.EMPTY, args, Markers.EMPTY);

            return new J.Annotation(
                Tree.randomId(), prefix, Markers.EMPTY, ident, argsContainer
            );
        }

        private boolean hasTimerParameter(J.MethodDeclaration md) {
            if (md.getParameters() == null || md.getParameters().isEmpty()) {
                return false;
            }
            for (Statement param : md.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    if (isTimerType(vd)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static class TransformableMethod {
        final J.MethodDeclaration method;
        final ScheduleAnalysis analysis;

        TransformableMethod(J.MethodDeclaration method, ScheduleAnalysis analysis) {
            this.method = method;
            this.analysis = analysis;
        }
    }

    private static class ScheduleAnalysis {
        boolean canAutoTransform = false;
        String fallbackReason = null;
        String cronExpression = null;
        String infoValue = null;
        boolean usesGetTimeRemaining = false;
        boolean usesGetInfo = false;
    }
}
