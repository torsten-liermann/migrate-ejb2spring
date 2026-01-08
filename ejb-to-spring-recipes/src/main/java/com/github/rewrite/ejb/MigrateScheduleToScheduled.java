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
 * Migrates EJB @Schedule and @Schedules to Spring @Scheduled.
 * <p>
 * Transformation:
 * - @Schedule(minute = "X", hour = "Y", ...) -> @Scheduled(cron = "0 X Y * * *")
 * - @Schedules({@Schedule(...), @Schedule(...)}) -> Multiple @Scheduled annotations
 * - @Schedule(timezone = "...") -> @Scheduled(cron = "...", zone = "...")
 * <p>
 * Cases NOT handled (delegated to marker recipe):
 * - @Schedule with Timer parameter in method signature
 * - @Schedule(persistent = true) or persistent not set (EJB default is true!)
 * - @Schedule(year = "...") with non-wildcard year (Spring cron has no year field)
 * - Non-literal values (constants) in schedule attributes
 * <p>
 * EJB Schedule format: second, minute, hour, dayOfMonth, month, dayOfWeek, year
 * Spring Cron format: second minute hour dayOfMonth month dayOfWeek
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateScheduleToScheduled extends Recipe {

    private static final String SCHEDULED_FQN = "org.springframework.scheduling.annotation.Scheduled";

    @Override
    public String getDisplayName() {
        return "Migrate @Schedule to Spring @Scheduled";
    }

    @Override
    public String getDescription() {
        return "Converts EJB @Schedule annotations to Spring @Scheduled with cron expressions. " +
               "Only handles safe cases (persistent=false explicitly set, no Timer parameter, " +
               "no non-literal values, no year restriction). Other cases are delegated to marker recipe.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate = Preconditions.check(
            Preconditions.or(
                new UsesType<>(SCHEDULE_FQN, false),
                new UsesType<>(SCHEDULES_FQN, false)
            ),
            new ScheduleVisitor()
        );
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (!TimerStrategySupport.isStrategy(cu, ProjectConfiguration.TimerStrategy.SCHEDULED)) {
                    return cu;
                }
                return (J.CompilationUnit) delegate.visit(cu, ctx);
            }
        };
    }

    private static class ScheduleVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            // Skip if method has Timer parameter - marker recipe handles this
            TimerAnalysis timerAnalysis = analyzeTimerParameter(md);
            if (timerAnalysis.hasTimerParameter()) {
                return md;
            }

            // Skip if any schedule is not safe for migration
            if (!allSchedulesSafeForMigration(md)) {
                return md;
            }

            // Process annotations
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean modified = false;

            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (isScheduleAnnotation(ann)) {
                    // Single @Schedule annotation
                    ScheduleConfig config = extractScheduleConfig(ann);
                    newAnnotations.add(createScheduledAnnotation(config, ann.getPrefix()));
                    modified = true;
                    maybeAddImport(SCHEDULED_FQN);
                    doAfterVisit(new RemoveImport<>(SCHEDULE_FQN, true));
                } else if (isSchedulesAnnotation(ann)) {
                    // @Schedules container with multiple @Schedule annotations
                    List<J.Annotation> innerSchedules = extractSchedulesFromContainer(ann);
                    String whitespace = determineWhitespace(ann, md);

                    boolean first = true;
                    for (J.Annotation innerAnn : innerSchedules) {
                        ScheduleConfig config = extractScheduleConfig(innerAnn);
                        Space prefix = first ? ann.getPrefix() : Space.format(whitespace);
                        newAnnotations.add(createScheduledAnnotation(config, prefix));
                        first = false;
                    }
                    modified = true;
                    maybeAddImport(SCHEDULED_FQN);
                    doAfterVisit(new RemoveImport<>(SCHEDULE_FQN, true));
                    doAfterVisit(new RemoveImport<>(SCHEDULES_FQN, true));
                } else {
                    newAnnotations.add(ann);
                }
            }

            if (modified) {
                md = md.withLeadingAnnotations(newAnnotations);
            }

            return md;
        }

        /**
         * Determines the whitespace to use for subsequent annotations when expanding @Schedules.
         */
        private String determineWhitespace(J.Annotation schedulesAnn, J.MethodDeclaration md) {
            String whitespace = schedulesAnn.getPrefix().getWhitespace();

            if (whitespace == null || whitespace.isEmpty() || !whitespace.contains("\n")) {
                String methodWhitespace = md.getPrefix().getWhitespace();
                int lastNewline = methodWhitespace.lastIndexOf('\n');
                if (lastNewline >= 0) {
                    whitespace = methodWhitespace.substring(lastNewline);
                } else if (!methodWhitespace.isEmpty()) {
                    whitespace = "\n" + methodWhitespace;
                } else {
                    whitespace = "\n    ";
                }
            }

            return whitespace;
        }

        /**
         * Creates a Spring @Scheduled annotation from the schedule configuration.
         */
        private J.Annotation createScheduledAnnotation(ScheduleConfig config, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(SCHEDULED_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Scheduled",
                type,
                null
            );

            List<JRightPadded<Expression>> arguments = new ArrayList<>();

            // Create cron = "expression" argument
            String cronExpression = config.buildSpringCronExpression();
            arguments.add(createAssignmentArg("cron", cronExpression, false));

            // Add zone parameter if timezone was specified
            if (config.getTimezone() != null) {
                arguments.add(createAssignmentArg("zone", config.getTimezone(), true));
            }

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
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

        private JRightPadded<Expression> createAssignmentArg(String key, String value, boolean leadingSpace) {
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                leadingSpace ? Space.format(" ") : Space.EMPTY,
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
                "\"" + value + "\"",
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
    }
}
