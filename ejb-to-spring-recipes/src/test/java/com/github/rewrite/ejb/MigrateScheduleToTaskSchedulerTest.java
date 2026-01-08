package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.marker.TimerStrategyMarker;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.util.UUID;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for MigrateScheduleToTaskScheduler recipe.
 * <p>
 * This recipe performs AUTOMATIC transformation for @Schedule methods with Timer parameter:
 * - Generates TaskScheduler field with @Autowired
 * - Generates ScheduledFuture field for timer control
 * - Generates @PostConstruct init method with taskScheduler.schedule()
 * - Replaces Timer API calls (getInfo, cancel, getTimeRemaining)
 * - Removes Timer parameter
 * - Removes @Schedule annotation
 * <p>
 * Falls back to @EjbSchedule marker only for unresolvable cases:
 * - Non-literal @Schedule values
 * - Multiple @Schedules on same method
 */
class MigrateScheduleToTaskSchedulerTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // ResolveTimerStrategy must run first to set the TimerStrategyMarker
        // based on code analysis (@Schedule with persistent=false and Timer parameter → TASKSCHEDULER)
        // expectedCyclesThatMakeChanges(1): cycle 1 = ResolveTimerStrategy adds markers, cycle 2 = transformation
        spec.recipes(new ResolveTimerStrategy(), new MigrateScheduleToTaskScheduler())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context", "spring-beans"))
            // Disable type validation since manually constructed AST nodes don't have type info
            .typeValidationOptions(TypeValidation.none())
            .expectedCyclesThatMakeChanges(1);
    }

    @Test
    void skipsMethodWithoutTimerParameter() {
        // Methods without Timer parameter are not handled by this recipe
        // ResolveTimerStrategy adds marker (1 cycle) but no transformation occurs
        String input = """
                import jakarta.ejb.Schedule;

                public class SimpleScheduler {
                    @Schedule(minute = "0", hour = "8", persistent = false)
                    public void simpleTask() {
                        // No timer parameter
                    }
                }
                """;
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            java(input, input)  // Same input as output = no visible change expected
        );
    }

    @Test
    void skipsMethodWithoutScheduleAnnotation() {
        // Methods without @Schedule are not handled
        // ResolveTimerStrategy still adds marker (1 cycle) but no transformation occurs
        String input = """
                import jakarta.ejb.Timer;

                public class NoSchedule {
                    public void someMethod(Timer timer) {
                        timer.cancel();
                    }
                }
                """;
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            java(input, input)  // Same input as output = no visible change expected
        );
    }

    @DocumentExample
    @Test
    void automaticallyTransformsScheduleWithTimerParameter() {
        // Basic case: @Schedule method with Timer parameter gets automatic transformation
        // timer.getInfo() -> null (no info attribute defined)
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Timer;

                public class TimerScheduler {
                    @Schedule(minute = "*/5", hour = "*", persistent = false)
                    public void scheduledTask(Timer timer) {
                        String info = (String) timer.getInfo();
                    }
                }
                """,
                """
                import jakarta.annotation.PostConstruct;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.scheduling.TaskScheduler;
                import org.springframework.scheduling.support.CronTrigger;

                import java.util.concurrent.ScheduledFuture;

                public class TimerScheduler {
                    @Autowired
                    private TaskScheduler taskScheduler;
                    private ScheduledFuture<?> scheduledFuture;
                    private static final String CRON_EXPRESSION = "0 */5 * * * *";
                   \s
                    public void scheduledTask() {
                        String info = (String) null;
                    }

                    @PostConstruct
                    public void initScheduler() {
                        this.scheduledFuture = taskScheduler.schedule(this::scheduledTask, new CronTrigger(CRON_EXPRESSION));
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsTimerCancelCall() {
        // timer.cancel() -> scheduledFuture.cancel(false)
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(2),
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Timer;

                public class CancelScheduler {
                    @Schedule(minute = "*/10", hour = "*", persistent = false)
                    public void cancelableTask(Timer timer) {
                        timer.cancel();
                    }
                }
                """,
                """
                import jakarta.annotation.PostConstruct;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.scheduling.TaskScheduler;
                import org.springframework.scheduling.support.CronTrigger;

                import java.util.concurrent.ScheduledFuture;

                public class CancelScheduler {
                    @Autowired
                    private TaskScheduler taskScheduler;
                    private ScheduledFuture<?> scheduledFuture;
                    private static final String CRON_EXPRESSION = "0 */10 * * * *";
                   \s
                    public void cancelableTask() {
                        this.scheduledFuture.cancel(false);
                    }

                    @PostConstruct
                    public void initScheduler() {
                        this.scheduledFuture = taskScheduler.schedule(this::cancelableTask, new CronTrigger(CRON_EXPRESSION));
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsTimerGetTimeRemaining() {
        // timer.getTimeRemaining() -> scheduledFuture.getDelay(TimeUnit.MILLISECONDS)
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(2),
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Timer;

                public class TimeRemainingScheduler {
                    @Schedule(minute = "30", hour = "12", persistent = false)
                    public void checkTime(Timer timer) {
                        long remaining = timer.getTimeRemaining();
                    }
                }
                """,
                """
                import jakarta.annotation.PostConstruct;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.scheduling.TaskScheduler;
                import org.springframework.scheduling.support.CronTrigger;

                import java.util.concurrent.ScheduledFuture;
                import java.util.concurrent.TimeUnit;

                public class TimeRemainingScheduler {
                    @Autowired
                    private TaskScheduler taskScheduler;
                    private ScheduledFuture<?> scheduledFuture;
                    private static final String CRON_EXPRESSION = "0 30 12 * * *";
                   \s
                    public void checkTime() {
                        long remaining = this.scheduledFuture.getDelay(TimeUnit.MILLISECONDS);
                    }

                    @PostConstruct
                    public void initScheduler() {
                        this.scheduledFuture = taskScheduler.schedule(this::checkTime, new CronTrigger(CRON_EXPRESSION));
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsWithTimerInfo() {
        // @Schedule with info attribute creates timerInfo field
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Timer;

                public class InfoScheduler {
                    @Schedule(hour = "12", info = "Daily noon task", persistent = false)
                    public void noonTask(Timer timer) {
                        System.out.println(timer.getInfo());
                    }
                }
                """,
                """
                import jakarta.annotation.PostConstruct;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.scheduling.TaskScheduler;
                import org.springframework.scheduling.support.CronTrigger;

                import java.util.concurrent.ScheduledFuture;

                public class InfoScheduler {
                    @Autowired
                    private TaskScheduler taskScheduler;
                    private ScheduledFuture<?> scheduledFuture;
                    private static final String CRON_EXPRESSION = "0 0 12 * * *";
                    private final Object timerInfo = "Daily noon task";
                   \s
                    public void noonTask() {
                        System.out.println(this.timerInfo);
                    }

                    @PostConstruct
                    public void initScheduler() {
                        this.scheduledFuture = taskScheduler.schedule(this::noonTask, new CronTrigger(CRON_EXPRESSION));
                    }
                }
                """
            )
        );
    }

    @Test
    void skipsNonLiteralValues() {
        // Non-literal values (constants/field references) require QUARTZ strategy
        // and are handled by MigrateScheduleToQuartz, not TaskScheduler.
        // ResolveTimerStrategy resolves to QUARTZ for non-literals, so this recipe skips.
        // 1 cycle: ResolveTimerStrategy adds marker but no transformation by this recipe
        String constantsInput = """
                public class ScheduleConstants {
                    public static final String EVERY_HOUR = "*";
                }
                """;
        String schedulerInput = """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Timer;

                public class ConstantScheduler {
                    @Schedule(minute = "0", hour = ScheduleConstants.EVERY_HOUR)
                    public void runWithConstant(Timer timer) {
                        timer.cancel();
                    }
                }
                """;
        rewriteRun(
            spec -> spec.recipe(new MigrateScheduleToTaskScheduler())
                .expectedCyclesThatMakeChanges(0)
                .allSources(source -> source.markers(new TimerStrategyMarker(
                    UUID.randomUUID(),
                    ProjectConfiguration.TimerStrategy.QUARTZ,
                    "test"
                ))),
            java(constantsInput),  // No visible change
            java(schedulerInput)   // No visible change - QUARTZ recipe would handle this
        );
    }

    @Test
    void fallsBackToMarkerForMultipleSchedules() {
        // @Schedules with multiple @Schedule entries uses fallback marker
        // (multiple schedules require Quartz for proper handling)
        // EjbSchedule annotation must be provided with same input/output (no change expected)
        String ejbScheduleAnnotation = """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.METHOD})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbSchedule {
                    String second() default "0";
                    String minute() default "0";
                    String hour() default "0";
                    String dayOfMonth() default "*";
                    String month() default "*";
                    String dayOfWeek() default "*";
                    String year() default "*";
                    String timezone() default "";
                    String info() default "";
                    boolean persistent() default true;
                    String rawExpression() default "";
                }
                """;
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(2),
            java(ejbScheduleAnnotation),  // No change to annotation definition
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Schedules;
                import jakarta.ejb.Timer;

                public class MultiScheduler {
                    @Schedules({
                        @Schedule(hour = "8", minute = "0", persistent = false),
                        @Schedule(hour = "12", minute = "0", persistent = false),
                        @Schedule(hour = "18", minute = "0", persistent = false)
                    })
                    public void runThreeTimesDaily(Timer timer) {
                        System.out.println(timer.getInfo());
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbSchedule;
                import jakarta.ejb.Timer;

                public class MultiScheduler {
                    @EjbSchedule(rawExpression = "@Schedules({\\n        @Schedule(hour = \\"8\\", minute = \\"0\\", persistent = false),\\n        @Schedule(hour = \\"12\\", minute = \\"0\\", persistent = false),\\n        @Schedule(hour = \\"18\\", minute = \\"0\\", persistent = false)\\n    })")
                    public void runThreeTimesDaily(Timer timer) {
                        System.out.println(timer.getInfo());
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsSchedulesWithSingleEntry() {
        // @Schedules with single @Schedule entry can be auto-transformed
        // Using getInfo() in expression context (no info attribute -> null)
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Schedules;
                import jakarta.ejb.Timer;

                public class SingleInContainer {
                    @Schedules({
                        @Schedule(hour = "6", minute = "30", persistent = false)
                    })
                    public void morningTask(Timer timer) {
                        Object info = timer.getInfo();
                    }
                }
                """,
                """
                import jakarta.annotation.PostConstruct;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.scheduling.TaskScheduler;
                import org.springframework.scheduling.support.CronTrigger;

                import java.util.concurrent.ScheduledFuture;

                public class SingleInContainer {
                    @Autowired
                    private TaskScheduler taskScheduler;
                    private ScheduledFuture<?> scheduledFuture;
                    private static final String CRON_EXPRESSION = "0 30 6 * * *";
                   \s
                    public void morningTask() {
                        Object info = null;
                    }

                    @PostConstruct
                    public void initScheduler() {
                        this.scheduledFuture = taskScheduler.schedule(this::morningTask, new CronTrigger(CRON_EXPRESSION));
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesOtherAnnotations() {
        // Other annotations on the method should be preserved
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(2),
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Timer;

                public class AnnotatedScheduler {
                    @Deprecated
                    @Schedule(minute = "0", hour = "0", persistent = false)
                    public void deprecatedTask(Timer timer) {
                        timer.cancel();
                    }
                }
                """,
                """
                import jakarta.annotation.PostConstruct;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.scheduling.TaskScheduler;
                import org.springframework.scheduling.support.CronTrigger;

                import java.util.concurrent.ScheduledFuture;

                public class AnnotatedScheduler {
                    @Autowired
                    private TaskScheduler taskScheduler;
                    private ScheduledFuture<?> scheduledFuture;
                    private static final String CRON_EXPRESSION = "0 0 0 * * *";
                    @Deprecated
                    public void deprecatedTask() {
                        this.scheduledFuture.cancel(false);
                    }

                    @PostConstruct
                    public void initScheduler() {
                        this.scheduledFuture = taskScheduler.schedule(this::deprecatedTask, new CronTrigger(CRON_EXPRESSION));
                    }
                }
                """
            )
        );
    }
}
