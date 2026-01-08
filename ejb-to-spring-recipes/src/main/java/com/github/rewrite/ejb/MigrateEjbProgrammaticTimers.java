package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.RemoveImport;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Migrates simple EJB programmatic timers to Spring TaskScheduler.
 * <p>
 * Conservative rules:
 * - Exactly one @Timeout method with no parameters.
 * - TimerService usage where return value is ignored (expression statement).
 * - Supported calls: createTimer(...), createSingleActionTimer(...), createIntervalTimer(...)
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEjbProgrammaticTimers extends Recipe {

    private static final String JAKARTA_TIMER_SERVICE = "jakarta.ejb.TimerService";
    private static final String JAVAX_TIMER_SERVICE = "javax.ejb.TimerService";
    private static final String JAKARTA_TIMER_CONFIG = "jakarta.ejb.TimerConfig";
    private static final String JAVAX_TIMER_CONFIG = "javax.ejb.TimerConfig";
    private static final String JAKARTA_TIMEOUT = "jakarta.ejb.Timeout";
    private static final String JAVAX_TIMEOUT = "javax.ejb.Timeout";
    private static final String JAKARTA_RESOURCE = "jakarta.annotation.Resource";
    private static final String JAVAX_RESOURCE = "javax.annotation.Resource";
    private static final String JAKARTA_INJECT = "jakarta.inject.Inject";
    private static final String JAVAX_INJECT = "javax.inject.Inject";
    private static final String SPRING_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final String SPRING_TASK_SCHEDULER = "org.springframework.scheduling.TaskScheduler";
    private static final String INSTANT_STUB =
        "package java.time;\n" +
        "public class Instant {\n" +
        "    public static Instant now() { return null; }\n" +
        "    public Instant plusMillis(long millis) { return null; }\n" +
        "}\n";
    private static final String DURATION_STUB =
        "package java.time;\n" +
        "public class Duration {\n" +
        "    public static Duration ofMillis(long millis) { return null; }\n" +
        "}\n";
    private static final JavaParser.Builder JAVA_TIME_PARSER =
        JavaParser.fromJavaVersion()
            .classpath("spring-context")
            .dependsOn(INSTANT_STUB, DURATION_STUB);

    @Override
    public String getDisplayName() {
        return "Migrate programmatic EJB timers to TaskScheduler";
    }

    @Override
    public String getDescription() {
        return "Converts simple TimerService.createTimer/createIntervalTimer calls to Spring TaskScheduler " +
               "when a single no-arg @Timeout method exists.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate = Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_TIMER_SERVICE, false),
                new UsesType<>(JAVAX_TIMER_SERVICE, false),
                new UsesType<>(JAKARTA_TIMEOUT, false),
                new UsesType<>(JAVAX_TIMEOUT, false)
            ),
            new ProgrammaticTimerVisitor()
        );
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (TimerStrategySupport.isQuartz(cu)) {
                    return cu;
                }
                return (J.CompilationUnit) delegate.visit(cu, ctx);
            }
        };
    }

    private static class ProgrammaticTimerVisitor extends JavaIsoVisitor<ExecutionContext> {

        private static class ClassState {
            final Set<String> timerServiceFieldNames;
            final J.MethodDeclaration timeoutMethod;
            final boolean canAutoMigrate;
            final boolean converted;

            private ClassState(Set<String> timerServiceFieldNames,
                               J.MethodDeclaration timeoutMethod,
                               boolean canAutoMigrate,
                               boolean converted) {
                this.timerServiceFieldNames = timerServiceFieldNames;
                this.timeoutMethod = timeoutMethod;
                this.canAutoMigrate = canAutoMigrate;
                this.converted = converted;
            }
        }

        private final Deque<ClassState> stateStack = new ArrayDeque<>();
        private Set<String> timerServiceFieldNames = new HashSet<>();
        private J.MethodDeclaration timeoutMethod;
        private boolean canAutoMigrate;
        private boolean converted;

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            stateStack.push(new ClassState(timerServiceFieldNames, timeoutMethod, canAutoMigrate, converted));

            analyzeClass(classDecl);
            converted = false;

            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            if (canAutoMigrate && converted && timeoutMethod != null && cd.getBody() != null) {
                cd = removeTimeoutAnnotation(cd, timeoutMethod.getId(), ctx, getCursor());
                cd = autoFormat(cd, ctx);
                if (!containsTimerServiceIdentifier(cd, ctx)) {
                    doAfterVisit(new RemoveImport<>(JAKARTA_TIMER_SERVICE, true));
                    doAfterVisit(new RemoveImport<>(JAVAX_TIMER_SERVICE, true));
                }
            }

            ClassState previous = stateStack.pop();
            timerServiceFieldNames = previous.timerServiceFieldNames;
            timeoutMethod = previous.timeoutMethod;
            canAutoMigrate = previous.canAutoMigrate;
            converted = previous.converted;

            return cd;
        }

        private void analyzeClass(J.ClassDeclaration classDecl) {
            timerServiceFieldNames = new HashSet<>();
            timeoutMethod = null;
            canAutoMigrate = false;

            if (classDecl.getBody() == null) {
                return;
            }

            List<J.MethodDeclaration> timeoutMethods = new ArrayList<>();
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (isTimerServiceField(vd)) {
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            timerServiceFieldNames.add(var.getSimpleName());
                        }
                    }
                } else if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    if (hasTimeoutAnnotation(md)) {
                        timeoutMethods.add(md);
                    }
                }
            }

            if (timerServiceFieldNames.isEmpty() || timeoutMethods.size() != 1) {
                return;
            }

            J.MethodDeclaration candidate = timeoutMethods.get(0);
            if (hasNoParameters(candidate)) {
                timeoutMethod = candidate;
                // P0.1 Fix: Only allow auto-migration if there are migratable timer calls
                canAutoMigrate = hasMigratableTimerCalls(classDecl);
            }
        }

        /**
         * Checks if the class contains any timer calls that can be migrated.
         * This pre-analysis ensures we only convert the TimerService field
         * if there's at least one migratable createTimer/createIntervalTimer call.
         *
         * P0.1 Fix: Also checks for non-migratable calls - if ANY non-migratable call
         * exists, the entire class is NOT auto-migratable (to avoid compile errors
         * from mixed converted/unconverted calls).
         *
         * P0.2 Fix: Also checks for setPersistent() calls anywhere in the class.
         * If any TimerConfig is modified via setPersistent(), the class is NOT auto-migratable.
         */
        private boolean hasMigratableTimerCalls(J.ClassDeclaration classDecl) {
            // P0.2: First check if any setPersistent() calls exist - if so, not migratable
            if (hasAnySetPersistentCall(classDecl)) {
                return false;
            }

            MigratableTimerCallFinder finder = new MigratableTimerCallFinder();
            finder.visit(classDecl, null);
            // P0.1 Fix: Only migratable if we have migratable calls AND NO non-migratable calls
            return finder.hasMigratableCalls && !finder.hasNonMigratableCalls;
        }

        /**
         * P0.2: Checks if there are ANY setPersistent() calls on any TimerConfig in the class.
         * If setPersistent() is called, the persistent value is determined dynamically
         * and we cannot safely migrate to TaskScheduler.
         */
        private boolean hasAnySetPersistentCall(J.ClassDeclaration classDecl) {
            AnySetPersistentCallFinder finder = new AnySetPersistentCallFinder();
            finder.visit(classDecl, null);
            return finder.hasSetPersistentCall;
        }

        /**
         * P0.2: Visitor to find any setPersistent() calls on TimerConfig objects.
         * Only considers calls on variables that are confirmed to be TimerConfig types.
         */
        private static class AnySetPersistentCallFinder extends JavaIsoVisitor<Void> {
            boolean hasSetPersistentCall = false;
            private final Set<String> timerConfigVariables = new HashSet<>();

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, Void unused) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, unused);

                // Track variables that are of TimerConfig type
                TypeTree typeExpr = vd.getTypeExpression();
                if (typeExpr != null) {
                    boolean isTimerConfig = false;
                    if (typeExpr.getType() != null && (
                        TypeUtils.isOfClassType(typeExpr.getType(), JAKARTA_TIMER_CONFIG) ||
                        TypeUtils.isOfClassType(typeExpr.getType(), JAVAX_TIMER_CONFIG))) {
                        isTimerConfig = true;
                    } else if (typeExpr instanceof J.Identifier &&
                               "TimerConfig".equals(((J.Identifier) typeExpr).getSimpleName())) {
                        isTimerConfig = true;
                    }

                    if (isTimerConfig) {
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            timerConfigVariables.add(var.getSimpleName());
                        }
                    }
                }

                return vd;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Void unused) {
                J.MethodInvocation mi = super.visitMethodInvocation(method, unused);

                if ("setPersistent".equals(mi.getSimpleName())) {
                    Expression select = mi.getSelect();
                    if (select != null) {
                        JavaType selectType = select.getType();

                        // If type info is available, check if it's TimerConfig
                        if (selectType != null) {
                            if (TypeUtils.isOfClassType(selectType, JAKARTA_TIMER_CONFIG) ||
                                TypeUtils.isOfClassType(selectType, JAVAX_TIMER_CONFIG)) {
                                hasSetPersistentCall = true;
                            }
                            // If type is known but NOT TimerConfig, do NOT fallback to name check
                            // This prevents false positives from other types that happen to have
                            // a variable with the same name as a TimerConfig variable
                        } else if (select instanceof J.Identifier) {
                            // Only fallback to name check when type info is unavailable
                            String varName = ((J.Identifier) select).getSimpleName();
                            if (timerConfigVariables.contains(varName)) {
                                hasSetPersistentCall = true;
                            }
                        }
                    }
                }

                return mi;
            }
        }

        private class MigratableTimerCallFinder extends JavaIsoVisitor<Void> {
            boolean hasMigratableCalls = false;
            boolean hasNonMigratableCalls = false;

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, Void unused) {
                J.MethodInvocation mi = super.visitMethodInvocation(method, unused);

                if (mi.getSelect() == null) {
                    return mi;
                }

                if (!isTimerServiceInvocationForFinder(mi)) {
                    return mi;
                }

                List<Expression> args = mi.getArguments();
                if (args == null || args.isEmpty()) {
                    return mi;
                }

                String methodName = mi.getSimpleName();
                if ("createTimer".equals(methodName) || "createSingleActionTimer".equals(methodName)) {
                    Expression delayExpr = args.get(0);
                    if (!isNumericExpression(delayExpr)) {
                        // Non-numeric delay - not migratable
                        hasNonMigratableCalls = true;
                        return mi;
                    }

                    // P0.1 Fix: Only count as migratable if it has TimerConfig with persistent=false
                    // EJB TimerService.createTimer always has at least 2 arguments (delay, info/config)
                    if (args.size() >= 2) {
                        Expression secondArg = args.get(1);
                        if (isTimerConfigWithPersistentFalse(secondArg)) {
                            hasMigratableCalls = true;
                        } else {
                            // Second argument is not TimerConfig(x, false) - not migratable
                            hasNonMigratableCalls = true;
                        }
                    }
                } else if ("createIntervalTimer".equals(methodName) && args.size() >= 3) {
                    // P0.1 Fix: createIntervalTimer(initial, interval, timerConfig)
                    // Third argument is always TimerConfig - must check persistent=false
                    Expression initialExpr = args.get(0);
                    Expression intervalExpr = args.get(1);
                    Expression configExpr = args.get(2);
                    if (!isNumericExpression(initialExpr) || !isNumericExpression(intervalExpr)) {
                        hasNonMigratableCalls = true;
                    } else if (isTimerConfigWithPersistentFalse(configExpr)) {
                        hasMigratableCalls = true;
                    } else {
                        // TimerConfig not with persistent=false, or null - not migratable
                        // EJB default is persistent=true
                        hasNonMigratableCalls = true;
                    }
                } else {
                    // Other timer service methods (getTimers, etc.) - mark as non-migratable
                    // to prevent field conversion when these methods are used
                    if (methodName.startsWith("create") || methodName.startsWith("get")) {
                        hasNonMigratableCalls = true;
                    }
                }

                return mi;
            }

            private boolean isTimerServiceInvocationForFinder(J.MethodInvocation mi) {
                Expression select = mi.getSelect();
                if (select == null) {
                    return false;
                }

                if (select.getType() != null) {
                    if (TypeUtils.isOfClassType(select.getType(), JAKARTA_TIMER_SERVICE) ||
                        TypeUtils.isOfClassType(select.getType(), JAVAX_TIMER_SERVICE)) {
                        return true;
                    }
                }

                if (select instanceof J.Identifier) {
                    return timerServiceFieldNames.contains(((J.Identifier) select).getSimpleName());
                }

                return false;
            }
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
            J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);

            if (!canAutoMigrate || !isTimerServiceField(vd) || !isFieldDeclaration()) {
                return vd;
            }

            maybeAddImport(SPRING_TASK_SCHEDULER);
            maybeAddImport(SPRING_AUTOWIRED);
            maybeRemoveImport(JAKARTA_TIMER_SERVICE);
            maybeRemoveImport(JAVAX_TIMER_SERVICE);
            maybeRemoveImport(JAKARTA_RESOURCE);
            maybeRemoveImport(JAVAX_RESOURCE);
            maybeRemoveImport(JAKARTA_INJECT);
            maybeRemoveImport(JAVAX_INJECT);

            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                JavaType.ShallowClass type = JavaType.ShallowClass.build(SPRING_TASK_SCHEDULER);
                ident = ident.withSimpleName("TaskScheduler").withType(type);
                vd = vd.withTypeExpression(ident);
                vd = updateVariableTypes(vd, type);
            }

            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean hasAutowired = vd.getLeadingAnnotations().stream()
                .anyMatch(a -> "Autowired".equals(a.getSimpleName()) ||
                               TypeUtils.isOfClassType(a.getType(), SPRING_AUTOWIRED));

            if (!hasAutowired) {
                Space autowiredPrefix = vd.getLeadingAnnotations().isEmpty()
                    ? vd.getPrefix()
                    : vd.getLeadingAnnotations().get(0).getPrefix();
                newAnnotations.add(createSimpleAnnotation("Autowired", SPRING_AUTOWIRED, autowiredPrefix));
            }

            for (int i = 0; i < vd.getLeadingAnnotations().size(); i++) {
                J.Annotation ann = vd.getLeadingAnnotations().get(i);
                if (isInjectionAnnotation(ann)) {
                    continue;
                }
                if (!newAnnotations.isEmpty() && newAnnotations.get(0).equals(ann)) {
                    continue;
                }
                if (!newAnnotations.isEmpty() && i == 0) {
                    ann = ann.withPrefix(Space.format(" "));
                }
                newAnnotations.add(ann);
            }

            if (!newAnnotations.isEmpty()) {
                vd = vd.withLeadingAnnotations(newAnnotations);
            }

            return vd;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);

            if (!canAutoMigrate || timeoutMethod == null || mi.getSelect() == null) {
                return mi;
            }

            if (!isTimerServiceInvocation(mi)) {
                return mi;
            }

            Object parent = getCursor().getParentTreeCursor().getValue();
            if (!(parent instanceof J.Block)) {
                return mi;
            }

            List<Expression> args = mi.getArguments();
            if (args == null || args.isEmpty()) {
                return mi;
            }

            String methodName = mi.getSimpleName();
            if ("createTimer".equals(methodName) || "createSingleActionTimer".equals(methodName)) {
                Expression delayExpr = args.get(0);
                if (!isNumericExpression(delayExpr)) {
                    return mi;
                }
                // P0.1 Fix: Only migrate if second argument is TimerConfig with persistent=false
                // createTimer(delay, info) without TimerConfig should NOT be migrated
                // EJB TimerService.createTimer always has at least 2 arguments (delay, info/config)
                if (args.size() >= 2) {
                    Expression secondArg = args.get(1);
                    if (!isTimerConfigWithPersistentFalse(secondArg)) {
                        // Not a TimerConfig with persistent=false, do not migrate
                        // MarkEjbTimerServiceForReview will handle this case
                        return mi;
                    }
                }
                converted = true;
                doAfterVisit(new AddImport<>("java.time.Instant", null, false));
                return scheduleOnce(mi, updateSelectType(mi.getSelect()), delayExpr, timeoutMethod.getSimpleName());
            }

            if ("createIntervalTimer".equals(methodName) && args.size() >= 3) {
                // P0.1 Fix: createIntervalTimer(initial, interval, timerConfig)
                // Third argument is always TimerConfig - must check persistent=false
                Expression initialExpr = args.get(0);
                Expression intervalExpr = args.get(1);
                Expression configExpr = args.get(2);
                if (!isNumericExpression(initialExpr) || !isNumericExpression(intervalExpr)) {
                    return mi;
                }
                // P0.1 Fix: Only migrate if TimerConfig has persistent=false
                if (!isTimerConfigWithPersistentFalse(configExpr)) {
                    // Not a TimerConfig with persistent=false, do not migrate
                    // MarkEjbTimerServiceForReview will handle this case
                    return mi;
                }
                converted = true;
                doAfterVisit(new AddImport<>("java.time.Instant", null, false));
                doAfterVisit(new AddImport<>("java.time.Duration", null, false));
                return scheduleAtFixedRate(mi, updateSelectType(mi.getSelect()), initialExpr, intervalExpr, timeoutMethod.getSimpleName());
            }

            return mi;
        }

        private J.ClassDeclaration removeTimeoutAnnotation(J.ClassDeclaration cd, UUID timeoutId, ExecutionContext ctx, Cursor cursor) {
            List<Statement> statements = cd.getBody().getStatements();
            List<Statement> updated = new ArrayList<>(statements.size());
            boolean removed = false;
            String indent = determineIndent(cd);

            for (Statement stmt : statements) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    if (md.getId().equals(timeoutId)) {
                        List<J.Annotation> newAnnotations = new ArrayList<>();
                        for (J.Annotation ann : md.getLeadingAnnotations()) {
                            if (isTimeoutAnnotation(ann)) {
                                removed = true;
                                continue;
                            }
                            newAnnotations.add(ann);
                        }
                        if (removed) {
                            md = md.withLeadingAnnotations(newAnnotations);
                            if (newAnnotations.isEmpty()) {
                                md = md.withPrefix(normalizeMethodPrefix(md.getPrefix(), indent));
                            }
                        }
                    }
                    updated.add(md);
                } else {
                    updated.add(stmt);
                }
            }

            if (removed) {
                maybeRemoveImport(JAKARTA_TIMEOUT);
                maybeRemoveImport(JAVAX_TIMEOUT);
                cd = cd.withBody(cd.getBody().withStatements(updated));
            }

            return cd;
        }

        private boolean isTimerServiceInvocation(J.MethodInvocation mi) {
            Expression select = mi.getSelect();
            if (select == null) {
                return false;
            }

            if (select.getType() != null) {
                if (TypeUtils.isOfClassType(select.getType(), JAKARTA_TIMER_SERVICE) ||
                    TypeUtils.isOfClassType(select.getType(), JAVAX_TIMER_SERVICE)) {
                    return true;
                }
            }

            if (select instanceof J.Identifier) {
                return timerServiceFieldNames.contains(((J.Identifier) select).getSimpleName());
            }

            return false;
        }

        private boolean isFieldDeclaration() {
            Cursor parent = getCursor().getParentTreeCursor();
            if (parent == null || !(parent.getValue() instanceof J.Block)) {
                return false;
            }
            Cursor grandParent = parent.getParentTreeCursor();
            return grandParent != null && grandParent.getValue() instanceof J.ClassDeclaration;
        }

        private boolean isTimerServiceField(J.VariableDeclarations varDecls) {
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr == null) return false;

            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                return TypeUtils.isOfClassType(ident.getType(), JAKARTA_TIMER_SERVICE) ||
                       TypeUtils.isOfClassType(ident.getType(), JAVAX_TIMER_SERVICE) ||
                       "TimerService".equals(ident.getSimpleName());
            }
            return TypeUtils.isOfClassType(typeExpr.getType(), JAKARTA_TIMER_SERVICE) ||
                   TypeUtils.isOfClassType(typeExpr.getType(), JAVAX_TIMER_SERVICE);
        }

        private boolean isTimeoutAnnotation(J.Annotation ann) {
            if (TypeUtils.isOfClassType(ann.getType(), JAKARTA_TIMEOUT) ||
                TypeUtils.isOfClassType(ann.getType(), JAVAX_TIMEOUT)) {
                return true;
            }
            return "Timeout".equals(ann.getSimpleName());
        }

        private boolean hasNoParameters(J.MethodDeclaration method) {
            List<Statement> params = method.getParameters();
            if (params.isEmpty()) {
                return true;
            }
            for (Statement param : params) {
                if (!(param instanceof J.Empty)) {
                    return false;
                }
            }
            return true;
        }

        private J.VariableDeclarations updateVariableTypes(J.VariableDeclarations vd, JavaType.ShallowClass type) {
            List<J.VariableDeclarations.NamedVariable> updated = new ArrayList<>();
            for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                JavaType.Variable variableType = var.getVariableType();
                if (variableType != null) {
                    var = var.withVariableType(variableType.withType(type));
                } else {
                    var = var.withVariableType(new JavaType.Variable(null, 0, var.getSimpleName(), null, type, null));
                }
                updated.add(var);
            }
            return vd.withVariables(updated);
        }

        private boolean containsTimerServiceIdentifier(J.ClassDeclaration cd, ExecutionContext ctx) {
            TimerServiceIdentifierVisitor visitor = new TimerServiceIdentifierVisitor();
            visitor.visit(cd, ctx);
            return visitor.found;
        }

        private Expression updateSelectType(Expression select) {
            if (select instanceof J.Identifier) {
                JavaType.ShallowClass type = JavaType.ShallowClass.build(SPRING_TASK_SCHEDULER);
                J.Identifier ident = (J.Identifier) select;
                return ident.withType(type);
            }
            return select;
        }

        private static class TimerServiceIdentifierVisitor extends JavaIsoVisitor<ExecutionContext> {
            boolean found;

            @Override
            public J.Identifier visitIdentifier(J.Identifier ident, ExecutionContext ctx) {
                if ("TimerService".equals(ident.getSimpleName())) {
                    found = true;
                }
                return ident;
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fieldAccess, ExecutionContext ctx) {
                if ("TimerService".equals(fieldAccess.getSimpleName())) {
                    found = true;
                }
                return fieldAccess;
            }
        }

        private Space normalizeMethodPrefix(Space prefix, String indent) {
            return prefix.withWhitespace("\n" + indent);
        }

        private String extractIndent(Space prefix) {
            String ws = prefix.getWhitespace();
            int lastNewline = ws.lastIndexOf('\n');
            if (lastNewline >= 0) {
                return ws.substring(lastNewline + 1);
            }
            return "";
        }

        private String determineIndent(J.ClassDeclaration cd) {
            if (cd.getBody() == null) {
                return "";
            }
            for (Statement stmt : cd.getBody().getStatements()) {
                String indent = extractIndent(stmt.getPrefix());
                if (!indent.isEmpty()) {
                    return indent;
                }
            }
            return "";
        }

        private boolean hasTimeoutAnnotation(J.MethodDeclaration md) {
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (isTimeoutAnnotation(ann)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isInjectionAnnotation(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), SPRING_AUTOWIRED) ||
                   TypeUtils.isOfClassType(ann.getType(), JAKARTA_RESOURCE) ||
                   TypeUtils.isOfClassType(ann.getType(), JAVAX_RESOURCE) ||
                   TypeUtils.isOfClassType(ann.getType(), JAKARTA_INJECT) ||
                   TypeUtils.isOfClassType(ann.getType(), JAVAX_INJECT) ||
                   "Autowired".equals(ann.getSimpleName()) ||
                   "Resource".equals(ann.getSimpleName()) ||
                   "Inject".equals(ann.getSimpleName());
        }

        private boolean isNumericExpression(Expression expr) {
            if (expr instanceof J.Literal) {
                Object value = ((J.Literal) expr).getValue();
                return value instanceof Number;
            }

            JavaType type = expr.getType();
            if (type instanceof JavaType.Primitive) {
                JavaType.Primitive prim = (JavaType.Primitive) type;
                return prim != JavaType.Primitive.Boolean && prim != JavaType.Primitive.Void;
            }

            if (type != null && TypeUtils.isAssignableTo("java.lang.Number", type)) {
                return true;
            }

            return false;
        }

        /**
         * Checks if the expression is a TimerConfig with persistent=false (literal).
         * Only TimerConfig with persistent=false can be safely migrated to TaskScheduler.
         *
         * Note: setPersistent() calls are checked separately at class level via
         * hasAnySetPersistentCall() before this method is invoked.
         *
         * @param expr the expression to check (second argument to createTimer)
         * @return true if expr is a TimerConfig with persistent=false literal
         */
        private boolean isTimerConfigWithPersistentFalse(Expression expr) {
            // Check if expression type is TimerConfig
            JavaType type = expr.getType();
            boolean isTimerConfig = type != null && (
                TypeUtils.isOfClassType(type, JAKARTA_TIMER_CONFIG) ||
                TypeUtils.isOfClassType(type, JAVAX_TIMER_CONFIG)
            );

            if (!isTimerConfig) {
                // Also check by simple name for cases where type info is incomplete
                if (expr instanceof J.Identifier) {
                    // Variable reference - we need to find its initializer
                    // For now, we don't migrate variables - too complex to trace
                    return false;
                }
                if (!(expr instanceof J.NewClass)) {
                    return false;
                }
                J.NewClass newClass = (J.NewClass) expr;
                if (newClass.getClazz() == null) {
                    return false;
                }
                String className = null;
                if (newClass.getClazz() instanceof J.Identifier) {
                    className = ((J.Identifier) newClass.getClazz()).getSimpleName();
                }
                if (!"TimerConfig".equals(className)) {
                    return false;
                }
            }

            // If it's a new TimerConfig(...) expression, check the persistent argument
            if (expr instanceof J.NewClass) {
                J.NewClass newClass = (J.NewClass) expr;
                List<Expression> constructorArgs = newClass.getArguments();

                // TimerConfig(Serializable info, boolean persistent) - 2 args constructor
                if (constructorArgs != null && constructorArgs.size() == 2) {
                    Expression persistentArg = constructorArgs.get(1);
                    if (persistentArg instanceof J.Literal) {
                        Object value = ((J.Literal) persistentArg).getValue();
                        // Only migrate if persistent is explicitly false
                        return Boolean.FALSE.equals(value);
                    }
                }
                // Default constructor or other constructors - persistent defaults to true
                return false;
            }

            // For variable references or other expressions, we cannot determine
            // the persistent value statically - do not migrate
            return false;
        }

        private J.MethodInvocation scheduleOnce(J.MethodInvocation original, Expression select,
                                                Expression delayExpr, String timeoutMethodName) {
            JavaTemplate template = JavaTemplate.builder(
                    "#{any()}.schedule(() -> " + timeoutMethodName + "(), Instant.now().plusMillis(#{any()}))"
                )
                .javaParser(JAVA_TIME_PARSER)
                .imports("java.time.Instant")
                .contextSensitive()
                .build();

            return template.apply(
                getCursor(),
                original.getCoordinates().replace(),
                select,
                delayExpr
            );
        }

        private J.MethodInvocation scheduleAtFixedRate(J.MethodInvocation original, Expression select,
                                                       Expression initialExpr, Expression intervalExpr,
                                                       String timeoutMethodName) {
            JavaTemplate template = JavaTemplate.builder(
                    "#{any()}.scheduleAtFixedRate(() -> " + timeoutMethodName + "(), " +
                    "Instant.now().plusMillis(#{any()}), Duration.ofMillis(#{any()}))"
                )
                .javaParser(JAVA_TIME_PARSER)
                .imports("java.time.Instant", "java.time.Duration")
                .contextSensitive()
                .build();

            return template.apply(
                getCursor(),
                original.getCoordinates().replace(),
                select,
                initialExpr,
                intervalExpr
            );
        }

        private J.Annotation createSimpleAnnotation(String simpleName, String fqn, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(fqn);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                java.util.Collections.emptyList(),
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
}
