package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

/**
 * Marks EJB TimerService/@Timeout usage for manual migration.
 * <p>
 * Programmatic timers require refactoring to Spring TaskScheduler.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MarkEjbTimerServiceForReview extends Recipe {

    private static final String JAKARTA_TIMER_SERVICE = "jakarta.ejb.TimerService";
    private static final String JAVAX_TIMER_SERVICE = "javax.ejb.TimerService";
    private static final String JAKARTA_TIMEOUT = "jakarta.ejb.Timeout";
    private static final String JAVAX_TIMEOUT = "javax.ejb.Timeout";
    private static final String PROFILE_FQN = "org.springframework.context.annotation.Profile";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    private static final String NEEDS_REVIEW_STUB =
        "package com.github.rewrite.ejb.annotations;\n" +
        "\n" +
        "import java.lang.annotation.*;\n" +
        "\n" +
        "@Retention(RetentionPolicy.RUNTIME)\n" +
        "@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})\n" +
        "public @interface NeedsReview {\n" +
        "    Category category();\n" +
        "    String reason() default \"\";\n" +
        "    String originalCode() default \"\";\n" +
        "    String suggestedAction() default \"\";\n" +
        "    enum Category { MANUAL_MIGRATION }\n" +
        "}\n";

    private static final Comparator<J.Annotation> NEEDS_REVIEW_FIRST = (a, b) -> {
        int aRank = isNeedsReviewAnnotation(a) ? 0 : 1;
        int bRank = isNeedsReviewAnnotation(b) ? 0 : 1;
        if (aRank != bRank) {
            return Integer.compare(aRank, bRank);
        }
        if (aRank == 0) {
            int aManual = isManualNeedsReview(a) ? 1 : 0;
            int bManual = isManualNeedsReview(b) ? 1 : 0;
            if (aManual != bManual) {
                return Integer.compare(aManual, bManual);
            }
        }
        return 0;
    };

    private static final String CLASS_NEEDS_REVIEW_TEMPLATE =
        "@NeedsReview(reason = \"Programmatic EJB timers require refactoring to Spring TaskScheduler\", " +
        "category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = \"@Timeout / TimerService\", " +
        "suggestedAction = \"Replace TimerService with TaskScheduler and schedule via schedule()/scheduleAtFixedRate()\")";


    @Override
    public String getDisplayName() {
        return "Mark EJB programmatic timers for manual migration";
    }

    @Override
    public String getDescription() {
        return "Adds @NeedsReview for EJB TimerService/@Timeout usages so they can be migrated " +
               "to Spring TaskScheduler or @Scheduled.";
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
            new TimerServiceVisitor()
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

    private static class TimerServiceVisitor extends JavaIsoVisitor<ExecutionContext> {

        private static class ClassState {
            final boolean hasTimerUsage;
            final boolean addClassReview;
            final boolean addProfile;

            private ClassState(boolean hasTimerUsage, boolean addClassReview,
                               boolean addProfile) {
                this.hasTimerUsage = hasTimerUsage;
                this.addClassReview = addClassReview;
                this.addProfile = addProfile;
            }
        }

        private final Deque<ClassState> stateStack = new ArrayDeque<>();
        private boolean hasTimerUsage;
        private boolean addClassReview;
        private boolean addProfile;
        private final JavaTemplate classNeedsReviewTemplate = JavaTemplate.builder(CLASS_NEEDS_REVIEW_TEMPLATE)
            .javaParser(JavaParser.fromJavaVersion().dependsOn(NEEDS_REVIEW_STUB))
            .imports(NEEDS_REVIEW_FQN)
            .build();

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            stateStack.push(new ClassState(hasTimerUsage, addClassReview, addProfile));

            analyzeClass(classDecl);
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            if (!hasTimerUsage) {
                restoreState();
                return cd;
            }

            if (addClassReview) {
                doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN, null, false));
                cd = classNeedsReviewTemplate.apply(
                    getCursor(),
                    cd.getCoordinates().addAnnotation(NEEDS_REVIEW_FIRST)
                );
            }

            if (addProfile) {
                doAfterVisit(new AddImport<>(PROFILE_FQN, null, false));
                cd = addProfileAnnotation(cd);
            }

            restoreState();
            return cd;
        }

        private void analyzeClass(J.ClassDeclaration classDecl) {
            hasTimerUsage = false;
            addClassReview = false;
            addProfile = false;

            boolean hasManualNeedsReview = hasManualNeedsReview(classDecl);
            boolean hasProfile = hasProfile(classDecl);

            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.VariableDeclarations) {
                        if (isTimerServiceField((J.VariableDeclarations) stmt)) {
                            hasTimerUsage = true;
                        }
                    } else if (stmt instanceof J.MethodDeclaration) {
                        if (hasTimeoutAnnotation((J.MethodDeclaration) stmt)) {
                            hasTimerUsage = true;
                        }
                    }
                }
            }

            if (!hasTimerUsage) {
                return;
            }

            // Always add class-level MANUAL_MIGRATION unless one already exists.
            // If another @NeedsReview exists (e.g., CONFIGURATION), the annotation is repeatable.
            addClassReview = !hasManualNeedsReview;
            addProfile = !hasProfile;
        }

        private void restoreState() {
            ClassState previous = stateStack.pop();
            hasTimerUsage = previous.hasTimerUsage;
            addClassReview = previous.addClassReview;
            addProfile = previous.addProfile;
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

        private boolean hasTimeoutAnnotation(J.MethodDeclaration md) {
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), JAKARTA_TIMEOUT) ||
                    TypeUtils.isOfClassType(ann.getType(), JAVAX_TIMEOUT) ||
                    "Timeout".equals(ann.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasClassNeedsReview(J.ClassDeclaration classDecl) {
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if ("NeedsReview".equals(ann.getSimpleName()) ||
                    TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN)) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasManualNeedsReview(J.ClassDeclaration classDecl) {
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if (!"NeedsReview".equals(ann.getSimpleName()) &&
                    !TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN)) {
                    continue;
                }
                if (ann.getArguments() == null) {
                    continue;
                }
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            J.Identifier key = (J.Identifier) assignment.getVariable();
                            if ("category".equals(key.getSimpleName())) {
                                if (assignment.getAssignment().toString().contains("MANUAL_MIGRATION")) {
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
            return false;
        }

        private boolean hasProfile(J.ClassDeclaration classDecl) {
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if ("Profile".equals(ann.getSimpleName()) ||
                    TypeUtils.isOfClassType(ann.getType(), PROFILE_FQN)) {
                    return true;
                }
            }
            return false;
        }

        private J.ClassDeclaration addProfileAnnotation(J.ClassDeclaration cd) {
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean inserted = false;
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                newAnnotations.add(ann);
                    if (!inserted && "NeedsReview".equals(ann.getSimpleName())) {
                        J.Annotation profileAnn = createProfileAnnotation(
                            Space.format("\n" + extractIndent(ann.getPrefix()))
                        );
                        newAnnotations.add(profileAnn);
                        inserted = true;
                    }
                }

            if (!inserted) {
                newAnnotations.add(createProfileAnnotation(cd.getPrefix()));
            }

            return cd.withLeadingAnnotations(newAnnotations);
        }

        private String extractIndent(Space prefix) {
            String ws = prefix.getWhitespace();
            int lastNewline = ws.lastIndexOf('\n');
            if (lastNewline >= 0) {
                return ws.substring(lastNewline + 1);
            }
            return "";
        }

        private J.Annotation createProfileAnnotation(Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(PROFILE_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Profile",
                type,
                null
            );

            J.Literal profileValue = new J.Literal(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                "manual-migration",
                "\"manual-migration\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                Collections.singletonList(new JRightPadded<>(profileValue, Space.EMPTY, Markers.EMPTY)),
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

    }

    private static boolean isNeedsReviewAnnotation(J.Annotation ann) {
        return "NeedsReview".equals(ann.getSimpleName()) ||
               TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN);
    }

    private static boolean isManualNeedsReview(J.Annotation ann) {
        if (!isNeedsReviewAnnotation(ann)) {
            return false;
        }
        if (ann.getArguments() == null) {
            return false;
        }
        for (Expression arg : ann.getArguments()) {
            if (arg instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) arg;
                if (assignment.getVariable() instanceof J.Identifier) {
                    J.Identifier key = (J.Identifier) assignment.getVariable();
                    if ("category".equals(key.getSimpleName())) {
                        String valueStr = assignment.getAssignment().toString();
                        if (valueStr.contains("MANUAL_MIGRATION")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
}
