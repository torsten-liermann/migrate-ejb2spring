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
import static org.openrewrite.test.SourceSpecs.text;

/**
 * Tests for MigrateTimerServiceToQuartz recipe.
 * <p>
 * This recipe performs full automatic transformation of EJB TimerService/@Timeout
 * to Quartz Job/Trigger pattern, including:
 * - createTimer/createIntervalTimer → scheduleJob with helper methods
 * - Timer.getInfo() → JobDataMap access
 * - @Timeout → regular method called by generated Job class
 * <p>
 * Falls back to @EjbQuartzTimerService marker for complex cases like:
 * - Multiple @Timeout methods
 * - timer.cancel() or other unsupported Timer APIs
 * - createCalendarTimer (complex cron expressions)
 */
class MigrateTimerServiceToQuartzTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        // Force QUARTZ strategy for unit tests so the recipe always runs.
        spec.recipe(new MigrateTimerServiceToQuartz())
            .allSources(source -> source.markers(new TimerStrategyMarker(
                UUID.randomUUID(),
                ProjectConfiguration.TimerStrategy.QUARTZ,
                "test"
            )))
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context", "spring-beans"))
            .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void transformsTimerServiceToQuartzScheduler() {
        // Full transformation case: class with TimerService and @Timeout method
        // - TimerService field → Scheduler field with constructor injection
        // - @Timeout annotation removed
        // - createTimer() → scheduleQuartzJob() helper method call
        // - Helper method generated for Quartz scheduling
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class TimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                        System.out.println("Timeout!");
                    }

                    public void start() {
                        timerService.createTimer(1000L, "info");
                    }
                }
                """,
                """
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class TimerBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void onTimeout() {
                        System.out.println("Timeout!");
                    }

                    public void start() {
                        scheduleQuartzJob(1000L, "info", false, TimerBeanJob.class);
                    }

                    public TimerBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzJob(long delayMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .startAt(Date.from(Instant.now().plusMillis(delayMs)))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule job", e);
                        }
                    }

                    private void scheduleQuartzJob(Date expirationDate, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        long delayMs = java.time.Duration.between(Instant.now(), expirationDate.toInstant()).toMillis();
                        if (delayMs < 0) delayMs = 0;
                        scheduleQuartzJob(delayMs, info, persistent, jobClass);
                    }

                    private void scheduleQuartzJob(java.time.Duration duration, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        scheduleQuartzJob(duration.toMillis(), info, persistent, jobClass);
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsIntervalTimerToQuartz() {
        // Full transformation for interval timer
        // - createIntervalTimer() → scheduleQuartzIntervalJob() with SimpleScheduleBuilder
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class IntervalTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void tick() {
                    }

                    public void start() {
                        timerService.createIntervalTimer(0L, 5000L, null);
                    }
                }
                """,
                """
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class IntervalTimerBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void tick() {
                    }

                    public void start() {
                        scheduleQuartzIntervalJob(0L, 5000L, null, false, IntervalTimerBeanJob.class);
                    }

                    public IntervalTimerBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzIntervalJob(long initialDelayMs, long intervalMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .startAt(Date.from(Instant.now().plusMillis(initialDelayMs)))
                                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                                            .withIntervalInMilliseconds(intervalMs)
                                            .repeatForever())
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule interval job", e);
                        }
                    }

                    private void scheduleQuartzIntervalJob(Date startDate, long intervalMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        long delayMs = java.time.Duration.between(Instant.now(), startDate.toInstant()).toMillis();
                        if (delayMs < 0) delayMs = 0;
                        scheduleQuartzIntervalJob(delayMs, intervalMs, info, persistent, jobClass);
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsTimerGetInfoToJobDataMap() {
        // Full transformation: Timer.getInfo() → context.getMergedJobDataMap().get("info")
        // - Timer parameter type changed to JobExecutionContext
        // - timer.getInfo() → timer.getMergedJobDataMap().get("info")
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;

                public class TimerWithInfoBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        String info = (String) timer.getInfo();
                        System.out.println(info);
                    }

                    public void start() {
                        timerService.createTimer(1000L, "my-task");
                    }
                }
                """,
                """
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class TimerWithInfoBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void onTimeout(JobExecutionContext timer) {
                        String info = (String) timer.getMergedJobDataMap().get("info");
                        System.out.println(info);
                    }

                    public void start() {
                        scheduleQuartzJob(1000L, "my-task", false, TimerWithInfoBeanJob.class);
                    }

                    public TimerWithInfoBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzJob(long delayMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .startAt(Date.from(Instant.now().plusMillis(delayMs)))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule job", e);
                        }
                    }

                    private void scheduleQuartzJob(Date expirationDate, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        long delayMs = java.time.Duration.between(Instant.now(), expirationDate.toInstant()).toMillis();
                        if (delayMs < 0) delayMs = 0;
                        scheduleQuartzJob(delayMs, info, persistent, jobClass);
                    }

                    private void scheduleQuartzJob(java.time.Duration duration, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        scheduleQuartzJob(duration.toMillis(), info, persistent, jobClass);
                    }
                }
                """
            )
        );
    }

    @Test
    void skipsClassWithoutTimerUsage() {
        // Class without TimerService or @Timeout is not modified
        String input = """
                import org.springframework.stereotype.Component;

                @Component
                public class RegularBean {
                    public void doSomething() {
                        System.out.println("Hello");
                    }
                }
                """;
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(input)
        );
    }

    @Test
    void skipsAlreadyMarkedClass() {
        // Class already has @EjbQuartzTimerService marker
        String annotationInput = """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    String migrationNotes() default "";
                }
                """;
        String beanInput = """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                @EjbQuartzTimerService(timerPattern = "interval")
                public class AlreadyMarkedBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """;
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(annotationInput),
            java(beanInput)
        );
    }

    @Test
    void handlesTimeoutMethodOnly() {
        // Class with only @Timeout method (no explicit TimerService field, no createTimer calls)
        // This is the simple auto-transform case:
        // - @Timeout annotation is removed (Job class will call the method)
        // - Method body is preserved
        // - A separate Job class file is generated (TimeoutOnlyBeanJob.java)
        rewriteRun(
            java(
                """
                import jakarta.ejb.Timeout;

                public class TimeoutOnlyBean {
                    @Timeout
                    public void onTimeout() {
                        System.out.println("Timeout!");
                    }
                }
                """,
                // Note: The blank line preserves indentation from where @Timeout was removed
                "public class TimeoutOnlyBean {\n" +
                "    \n" +
                "    public void onTimeout() {\n" +
                "        System.out.println(\"Timeout!\");\n" +
                "    }\n" +
                "}\n"
            )
        );
    }

    @Test
    void autoTransformsTimeoutWithTimerParameter() {
        // Auto-transform case: @Timeout with Timer parameter (no createTimer calls)
        // - @Timeout annotation is removed
        // - Timer parameter type changed to JobExecutionContext (name preserved!)
        // - Timer import is removed (no longer used)
        // - JobExecutionContext import is added
        rewriteRun(
            java(
                """
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;

                public class TimerParamBean {
                    @Timeout
                    public void onTimeout(Timer timer) {
                        System.out.println("Timeout!");
                    }
                }
                """,
                // Timer param type changed to JobExecutionContext, name "timer" preserved
                // This ensures any other uses of the timer variable remain valid
                // Timer import removed, JobExecutionContext import added
                "import org.quartz.JobExecutionContext;\n" +
                "\n" +
                "public class TimerParamBean {\n" +
                "    \n" +
                "    public void onTimeout(JobExecutionContext timer) {\n" +
                "        System.out.println(\"Timeout!\");\n" +
                "    }\n" +
                "}\n"
            )
        );
    }

    @Test
    void handlesMultipleTimeoutMethods() {
        // Class with multiple @Timeout methods
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class MultiTimeoutBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void firstTimeout() {
                    }

                    @Timeout
                    public void secondTimeout() {
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timeoutMethodCount = 2)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: multiple @Timeout methods", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class MultiTimeoutBean {

                   \s
                    public void firstTimeout() {
                    }

                   \s
                    public void secondTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void fallsBackToMarkerForTimerCancel() {
        // Timer.cancel() is unsupported - must fall back to marker annotation
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;

                public class TimerCancelBean {
                    @Timeout
                    public void onTimeout(Timer timer) {
                        timer.cancel();  // Unsupported API
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timeoutMethodCount = 1)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: timer.cancel() usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimerCancelBean {
                   \s
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void fallsBackToMarkerForTimerGetNextTimeout() {
        // Timer.getNextTimeout() is unsupported - must fall back to marker annotation
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import java.util.Date;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;

                public class TimerGetNextTimeoutBean {
                    @Timeout
                    public void onTimeout(Timer timer) {
                        Date next = timer.getNextTimeout();  // Unsupported API
                        System.out.println(next);
                    }
                }
                """,
                """



                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timeoutMethodCount = 1)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: unsupported Timer APIs (getTimeRemaining/getNextTimeout)", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimerGetNextTimeoutBean {
                   \s
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void injectsSchedulerIntoExistingConstructor() {
        // Class with existing constructor should get Scheduler parameter injected
        // rather than a new constructor added (which would leave final field uninitialized)
        // The constructor stays in its original position, with Scheduler added as last parameter
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class BeanWithConstructor {
                    private final String name;

                    @Resource
                    private TimerService timerService;

                    public BeanWithConstructor(String name) {
                        this.name = name;
                    }

                    @Timeout
                    public void onTimeout() {
                        System.out.println(name);
                    }

                    public void start() {
                        timerService.createTimer(1000L, "info");
                    }
                }
                """,
                """
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class BeanWithConstructor {
                    private final String name;

                   \s
                    private final Scheduler scheduler;

                    public BeanWithConstructor(String name, Scheduler scheduler) {
                        this.scheduler = scheduler;
                        this.name = name;
                    }

                   \s
                    public void onTimeout() {
                        System.out.println(name);
                    }

                    public void start() {
                        scheduleQuartzJob(1000L, "info", false, BeanWithConstructorJob.class);
                    }

                    private void scheduleQuartzJob(long delayMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .startAt(Date.from(Instant.now().plusMillis(delayMs)))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule job", e);
                        }
                    }

                    private void scheduleQuartzJob(Date expirationDate, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        long delayMs = java.time.Duration.between(Instant.now(), expirationDate.toInstant()).toMillis();
                        if (delayMs < 0) delayMs = 0;
                        scheduleQuartzJob(delayMs, info, persistent, jobClass);
                    }

                    private void scheduleQuartzJob(java.time.Duration duration, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        scheduleQuartzJob(duration.toMillis(), info, persistent, jobClass);
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsDateBasedTimerCreation() {
        // Test for Date-based createTimer overload
        // With full type resolution, the Date argument type is detected and
        // a Date overload helper method is generated that converts to millis delay.
        rewriteRun(
            java(
                """
                import java.util.Date;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class DateTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void scheduleAt(Date expirationDate) {
                        timerService.createTimer(expirationDate, "scheduled-task");
                    }
                }
                """,
                """
                import java.time.Instant;
                import java.util.Date;
                import org.quartz.*;

                public class DateTimerBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void onTimeout() {
                    }

                    public void scheduleAt(Date expirationDate) {
                        scheduleQuartzJob(expirationDate, "scheduled-task", false, DateTimerBeanJob.class);
                    }

                    public DateTimerBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzJob(long delayMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .startAt(Date.from(Instant.now().plusMillis(delayMs)))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule job", e);
                        }
                    }

                    private void scheduleQuartzJob(java.util.Date expirationDate, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        long delayMs = java.time.Duration.between(Instant.now(), expirationDate.toInstant()).toMillis();
                        if (delayMs < 0) delayMs = 0;
                        scheduleQuartzJob(delayMs, info, persistent, jobClass);
                    }

                    private void scheduleQuartzJob(java.time.Duration duration, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        scheduleQuartzJob(duration.toMillis(), info, persistent, jobClass);
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsTimerConfigToGetInfo() {
        // Test that TimerConfig argument is wrapped with .getInfo() to extract the actual info object
        // createSingleActionTimer(1000L, config) -> scheduleQuartzJob(1000L, config.getInfo(), Job.class)
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class TimerConfigBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void scheduleWithConfig() {
                        TimerConfig config = new TimerConfig("my-info", false);
                        timerService.createSingleActionTimer(1000L, config);
                    }
                }
                """,
                """
                import jakarta.ejb.TimerConfig;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class TimerConfigBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void onTimeout() {
                    }

                    public void scheduleWithConfig() {
                        TimerConfig config = new TimerConfig("my-info", false);
                        scheduleQuartzJob(1000L, config.getInfo(), config.isPersistent(), TimerConfigBeanJob.class);
                    }

                    public TimerConfigBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzJob(long delayMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .startAt(Date.from(Instant.now().plusMillis(delayMs)))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule job", e);
                        }
                    }

                    private void scheduleQuartzJob(Date expirationDate, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        long delayMs = java.time.Duration.between(Instant.now(), expirationDate.toInstant()).toMillis();
                        if (delayMs < 0) delayMs = 0;
                        scheduleQuartzJob(delayMs, info, persistent, jobClass);
                    }

                    private void scheduleQuartzJob(java.time.Duration duration, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        scheduleQuartzJob(duration.toMillis(), info, persistent, jobClass);
                    }
                }
                """
            )
        );
    }

    @Test
    void handlesConstructorChainingWithThis() {
        // Test that constructor chaining with this() is handled correctly:
        // - Scheduler is added to delegated constructor call: this(42) -> this(42, scheduler)
        // - Assignment is NOT added to delegating constructor (target handles it)
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class ChainedConstructorBean {
                    private final int value;

                    @Resource
                    private TimerService timerService;

                    public ChainedConstructorBean() {
                        this(42);
                    }

                    public ChainedConstructorBean(int value) {
                        this.value = value;
                    }

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.createTimer(1000L, "info");
                    }
                }
                """,
                """
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class ChainedConstructorBean {
                    private final int value;

                   \s
                    private final Scheduler scheduler;

                    public ChainedConstructorBean( Scheduler scheduler) {
                        this(42, scheduler);
                    }

                    public ChainedConstructorBean(int value, Scheduler scheduler) {
                        this.scheduler = scheduler;
                        this.value = value;
                    }

                   \s
                    public void onTimeout() {
                    }

                    public void start() {
                        scheduleQuartzJob(1000L, "info", false, ChainedConstructorBeanJob.class);
                    }

                    private void scheduleQuartzJob(long delayMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .startAt(Date.from(Instant.now().plusMillis(delayMs)))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule job", e);
                        }
                    }

                    private void scheduleQuartzJob(Date expirationDate, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        long delayMs = java.time.Duration.between(Instant.now(), expirationDate.toInstant()).toMillis();
                        if (delayMs < 0) delayMs = 0;
                        scheduleQuartzJob(delayMs, info, persistent, jobClass);
                    }

                    private void scheduleQuartzJob(java.time.Duration duration, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        scheduleQuartzJob(duration.toMillis(), info, persistent, jobClass);
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotTreatNonEjbTimerConfigAsTimerConfig() {
        // Test that a non-EJB TimerConfig type (e.g., com.example.TimerConfig) is NOT treated as EJB TimerConfig
        // This ensures the import-aware detection avoids false positives
        // Also verifies that application.properties is NOT modified (no persistence config generated)
        rewriteRun(
            // Existing application.properties should remain unchanged (no Quartz persistence config added)
            text(
                """
                server.port=8080
                spring.datasource.url=jdbc:h2:mem:testdb
                """,
                spec -> spec.path("src/main/resources/application.properties")
            ),
            java(
                """
                package com.example;

                public class TimerConfig {
                    private String name;
                    private boolean persistent;
                    public TimerConfig(String name, boolean persistent) { this.name = name; this.persistent = persistent; }
                    public String getName() { return name; }
                    public boolean isPersistent() { return persistent; }
                }
                """
            ),
            java(
                """
                import com.example.TimerConfig;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class NonEjbTimerConfigBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void scheduleWithNonEjbConfig() {
                        // This is NOT jakarta.ejb.TimerConfig - should NOT be wrapped with getInfo()
                        // Uses persistent=true which would trigger persistence config for EJB TimerConfig
                        TimerConfig myConfig = new TimerConfig("test", true);
                        // createTimer with non-EJB TimerConfig - should just pass the object as-is
                        timerService.createTimer(1000L, myConfig);
                    }
                }
                """,
                """
                import com.example.TimerConfig;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class NonEjbTimerConfigBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void onTimeout() {
                    }

                    public void scheduleWithNonEjbConfig() {
                        // This is NOT jakarta.ejb.TimerConfig - should NOT be wrapped with getInfo()
                        // Uses persistent=true which would trigger persistence config for EJB TimerConfig
                        TimerConfig myConfig = new TimerConfig("test", true);
                        // createTimer with non-EJB TimerConfig - should just pass the object as-is
                        scheduleQuartzJob(1000L, myConfig, false, NonEjbTimerConfigBeanJob.class);
                    }

                    public NonEjbTimerConfigBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzJob(long delayMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .startAt(Date.from(Instant.now().plusMillis(delayMs)))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule job", e);
                        }
                    }

                    private void scheduleQuartzJob(Date expirationDate, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        long delayMs = java.time.Duration.between(Instant.now(), expirationDate.toInstant()).toMillis();
                        if (delayMs < 0) delayMs = 0;
                        scheduleQuartzJob(delayMs, info, persistent, jobClass);
                    }

                    private void scheduleQuartzJob(java.time.Duration duration, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        scheduleQuartzJob(duration.toMillis(), info, persistent, jobClass);
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotTreatNonEjbTimerConfigAsTimerConfigMultiModule() {
        // Multi-module test: verifies that non-EJB TimerConfig doesn't trigger persistence config
        // in any module, covering the case where recipe might generate properties per source root
        rewriteRun(
            // Module A: has its own application.properties that should remain unchanged
            text(
                """
                server.port=8081
                module.name=module-a
                """,
                spec -> spec.path("module-a/src/main/resources/application.properties")
            ),
            // Module B: has its own application.properties that should remain unchanged
            text(
                """
                server.port=8082
                module.name=module-b
                """,
                spec -> spec.path("module-b/src/main/resources/application.properties")
            ),
            // Non-EJB TimerConfig class in module-a
            java(
                """
                package com.example;

                public class TimerConfig {
                    private String name;
                    private boolean persistent;
                    public TimerConfig(String name, boolean persistent) { this.name = name; this.persistent = persistent; }
                    public String getName() { return name; }
                    public boolean isPersistent() { return persistent; }
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/TimerConfig.java")
            ),
            // Bean using non-EJB TimerConfig in module-a with persistent=true
            java(
                """
                import com.example.TimerConfig;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class ModuleABean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void schedule() {
                        // Uses persistent=true which would trigger persistence config for real EJB TimerConfig
                        TimerConfig config = new TimerConfig("moduleA", true);
                        timerService.createTimer(1000L, config);
                    }
                }
                """,
                """
                import com.example.TimerConfig;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class ModuleABean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void onTimeout() {
                    }

                    public void schedule() {
                        // Uses persistent=true which would trigger persistence config for real EJB TimerConfig
                        TimerConfig config = new TimerConfig("moduleA", true);
                        scheduleQuartzJob(1000L, config, false, ModuleABeanJob.class);
                    }

                    public ModuleABean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzJob(long delayMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .startAt(Date.from(Instant.now().plusMillis(delayMs)))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule job", e);
                        }
                    }

                    private void scheduleQuartzJob(Date expirationDate, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        long delayMs = java.time.Duration.between(Instant.now(), expirationDate.toInstant()).toMillis();
                        if (delayMs < 0) delayMs = 0;
                        scheduleQuartzJob(delayMs, info, persistent, jobClass);
                    }

                    private void scheduleQuartzJob(java.time.Duration duration, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        scheduleQuartzJob(duration.toMillis(), info, persistent, jobClass);
                    }
                }
                """,
                spec -> spec.path("module-a/src/main/java/ModuleABean.java")
            ),
            // Bean using non-EJB TimerConfig in module-b
            java(
                """
                import com.example.TimerConfig;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class ModuleBBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void schedule() {
                        TimerConfig config = new TimerConfig("moduleB", true);
                        timerService.createTimer(2000L, config);
                    }
                }
                """,
                """
                import com.example.TimerConfig;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class ModuleBBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void onTimeout() {
                    }

                    public void schedule() {
                        TimerConfig config = new TimerConfig("moduleB", true);
                        scheduleQuartzJob(2000L, config, false, ModuleBBeanJob.class);
                    }

                    public ModuleBBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzJob(long delayMs, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .startAt(Date.from(Instant.now().plusMillis(delayMs)))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule job", e);
                        }
                    }

                    private void scheduleQuartzJob(Date expirationDate, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        long delayMs = java.time.Duration.between(Instant.now(), expirationDate.toInstant()).toMillis();
                        if (delayMs < 0) delayMs = 0;
                        scheduleQuartzJob(delayMs, info, persistent, jobClass);
                    }

                    private void scheduleQuartzJob(java.time.Duration duration, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        scheduleQuartzJob(duration.toMillis(), info, persistent, jobClass);
                    }
                }
                """,
                spec -> spec.path("module-b/src/main/java/ModuleBBean.java")
            )
        );
    }

    // ========== P1.4: createCalendarTimer Tests ==========

    @Test
    void migratesCreateCalendarTimerWithLiteralSetterChain() {
        // P1.4: Safe calendar timer with literal setter chain should be migrated
        // Note: ScheduleExpression and TimerConfig imports remain because TimerConfig is still referenced in transformed code
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class CalendarTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                        System.out.println("Calendar timeout!");
                    }

                    public void start() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression().hour("2").minute("0").dayOfWeek("Mon-Fri"),
                            new TimerConfig("myInfo", false));
                    }
                }
                """,
                """
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.TimerConfig;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class CalendarTimerBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void onTimeout() {
                        System.out.println("Calendar timeout!");
                    }

                    public void start() {
                        scheduleQuartzCronJob("0 0 2 ? * Mon-Fri", new TimerConfig("myInfo", false).getInfo(), new TimerConfig("myInfo", false).isPersistent(), CalendarTimerBeanJob.class);
                    }

                    public CalendarTimerBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzCronJob(String cronExpression, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule cron job", e);
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesCreateCalendarTimerWithOnlyHour() {
        // P1.4: Simple calendar timer with only hour set
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class SimpleCalendarBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void execute() {
                        // runs at 9 AM
                    }

                    public void init() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression().hour("9"),
                            new TimerConfig(null, false));
                    }
                }
                """,
                """
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.TimerConfig;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class SimpleCalendarBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void execute() {
                        // runs at 9 AM
                    }

                    public void init() {
                        scheduleQuartzCronJob("0 0 9 ? * *", new TimerConfig(null, false).getInfo(), new TimerConfig(null, false).isPersistent(), SimpleCalendarBeanJob.class);
                    }

                    public SimpleCalendarBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzCronJob(String cronExpression, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule cron job", e);
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesCreateCalendarTimerWithMultipleValues() {
        // P1.4: Calendar timer with multiple values (comma-separated and ranges)
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class MultiValueCalendarBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void process() {
                        // runs every 2 hours at minute 0 and 30
                    }

                    public void setup() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression().minute("0,30").hour("*/2"),
                            new TimerConfig(null, false));
                    }
                }
                """,
                """
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.TimerConfig;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class MultiValueCalendarBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void process() {
                        // runs every 2 hours at minute 0 and 30
                    }

                    public void setup() {
                        scheduleQuartzCronJob("0 0,30 */2 ? * *", new TimerConfig(null, false).getInfo(), new TimerConfig(null, false).isPersistent(), MultiValueCalendarBeanJob.class);
                    }

                    public MultiValueCalendarBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzCronJob(String cronExpression, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule cron job", e);
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateCalendarTimerWithYear() {
        // P1.4: Calendar timer with year attribute should NOT be migrated (marker fallback)
        // WFQ-017: EJB Timer API is removed even in fallback - no jakarta.ejb.Timer* imports
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class YearCalendarBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression().hour("2").year("2025"),
                            new TimerConfig(null, false));
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class YearCalendarBean {

                   \s
                    public void onTimeout() {
                    }

                    public void start() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateCalendarTimerWithTimezone() {
        // P1.4: Calendar timer with timezone attribute should NOT be migrated
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class TimezoneCalendarBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression().hour("2").timezone("Europe/Berlin"),
                            new TimerConfig(null, false));
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimezoneCalendarBean {

                   \s
                    public void onTimeout() {
                    }

                    public void start() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateCalendarTimerWithVariable() {
        // P1.4: Calendar timer with variable (non-literal) argument should NOT be migrated
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class VariableCalendarBean {
                    @Resource
                    private TimerService timerService;

                    private String hourValue = "2";

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression().hour(hourValue),
                            new TimerConfig(null, false));
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class VariableCalendarBean {

                    private String hourValue = "2";

                   \s
                    public void onTimeout() {
                    }

                    public void start() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateCalendarTimerWithLastSpecialValue() {
        // P1.4: Calendar timer with "Last" special value should NOT be migrated
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class LastDayCalendarBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        // Last day of month at 2 AM
                        timerService.createCalendarTimer(
                            new ScheduleExpression().hour("2").dayOfMonth("Last"),
                            new TimerConfig(null, false));
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class LastDayCalendarBean {

                   \s
                    public void onTimeout() {
                    }

                    public void start() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateCalendarTimerWithStart() {
        // P1.4: Calendar timer with start() attribute should NOT be migrated
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;
                import java.util.Date;

                public class StartDateCalendarBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression().hour("2").start(new Date()),
                            new TimerConfig(null, false));
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class StartDateCalendarBean {

                   \s
                    public void onTimeout() {
                    }

                    public void start() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateCalendarTimerWithDayOfMonthAndDayOfWeekConflict() {
        // P1.4 Fix: Quartz doesn't support both dayOfMonth and dayOfWeek being set
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class ConflictCalendarBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        // Both dayOfMonth and dayOfWeek set - Quartz conflict
                        timerService.createCalendarTimer(
                            new ScheduleExpression().hour("9").dayOfMonth("15").dayOfWeek("Mon"),
                            new TimerConfig(null, false));
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class ConflictCalendarBean {

                   \s
                    public void onTimeout() {
                    }

                    public void start() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesCreateCalendarTimerWithNullTimerConfig() {
        // P1.4 Fix: null TimerConfig uses EJB default: persistent=true, info=null
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class NullConfigCalendarBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression().hour("3"),
                            null);
                    }
                }
                """,
                """
                import jakarta.ejb.ScheduleExpression;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class NullConfigCalendarBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void onTimeout() {
                    }

                    public void start() {
                        scheduleQuartzCronJob("0 0 3 ? * *", null, true, NullConfigCalendarBeanJob.class);
                    }

                    public NullConfigCalendarBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzCronJob(String cronExpression, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule cron job", e);
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateCalendarTimerWithExplicitDayOfWeekStarAndDayOfMonth() {
        // P1.4 Review 2: Both dayOfMonth and dayOfWeek explicitly set (even if one is '*')
        // This is ambiguous in Quartz - user intent unclear, fall back to marker
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class ExplicitBothFieldsBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        // User explicitly sets both fields - ambiguous for Quartz
                        timerService.createCalendarTimer(
                            new ScheduleExpression().dayOfMonth("15").dayOfWeek("*"),
                            new TimerConfig(null, false));
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class ExplicitBothFieldsBean {

                   \s
                    public void onTimeout() {
                    }

                    public void start() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateCalendarTimerWithExplicitDayOfMonthStarAndDayOfWeek() {
        // P1.4 Review 2: Both dayOfMonth and dayOfWeek explicitly set (reverse case)
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class ExplicitBothFieldsBean2 {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        // User explicitly sets dayOfMonth='*' and dayOfWeek='Mon-Fri'
                        timerService.createCalendarTimer(
                            new ScheduleExpression().dayOfMonth("*").dayOfWeek("Mon-Fri"),
                            new TimerConfig(null, false));
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class ExplicitBothFieldsBean2 {

                   \s
                    public void onTimeout() {
                    }

                    public void start() {
                    }
                }
                """
            )
        );
    }

    // ========== P1.5: TimerHandle/cancel with MigratedTimerHandle Tests ==========

    @Test
    void fallsBackToMarkerForTimerGetHandleStoredInField() {
        // P1.5: TimerHandle stored in field escapes local scope - needs marker
        // The escape analysis detects that the handle is stored in a field,
        // which means it cannot be auto-transformed.
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    boolean usesTimerHandle() default false;
                    boolean timerHandleEscapes() default false;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleFieldBean {
                    @Resource
                    private TimerService timerService;

                    private TimerHandle savedHandle;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        savedHandle = timer.getHandle();
                    }

                    public void cancelLater() {
                        if (savedHandle != null) {
                            savedHandle.getTimer().cancel();
                        }
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timeoutMethodCount = 1, usesTimerHandle = true, timerHandleEscapes = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: timer.cancel() usage, TimerHandle usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimerHandleFieldBean {

                   \s
                    public void onTimeout() {
                    }

                    public void cancelLater() {
                    }
                }
                """
            )
        );
    }

    @Test
    void fallsBackToMarkerForTimerGetHandleReturned() {
        // P1.5: TimerHandle returned from method escapes local scope - needs marker
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    boolean usesTimerHandle() default false;
                    boolean timerHandleEscapes() default false;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleReturnBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                    }

                    public TimerHandle startAndGetHandle() {
                        Timer t = timerService.createTimer(1000L, "info");
                        return t.getHandle();
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.TimerHandle;

                @EjbQuartzTimerService(timerPattern = "single", timeoutMethodCount = 1, usesTimerHandle = true, timerHandleEscapes = true, hasSingleTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: TimerHandle usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/single", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimerHandleReturnBean {

                   \s
                    public void onTimeout() {
                    }

                    public TimerHandle startAndGetHandle() {
                    }
                }
                """
            )
        );
    }

    @Test
    void fallsBackToMarkerForTimerGetHandleWithLocalVariableStoredInField() {
        // P1.5: TimerHandle stored via local variable in field - needs marker
        // Even if the handle is first stored in a local variable, assigning to a field
        // means it escapes.
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    boolean usesTimerHandle() default false;
                    boolean timerHandleEscapes() default false;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleLocalToFieldBean {
                    @Resource
                    private TimerService timerService;

                    private TimerHandle savedHandle;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        TimerHandle h = timer.getHandle();
                        this.savedHandle = h;  // Escapes via field assignment
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timeoutMethodCount = 1, usesTimerHandle = true, timerHandleEscapes = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: TimerHandle usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimerHandleLocalToFieldBean {

                   \s
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void fallsBackToMarkerForTimerGetHandlePassedAsArgument() {
        // P1.5: TimerHandle passed as method argument escapes local scope - needs marker
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    boolean usesTimerHandle() default false;
                    boolean timerHandleEscapes() default false;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleArgBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        TimerHandle h = timer.getHandle();
                        saveHandle(h);  // Escapes via method argument
                    }

                    private void saveHandle(TimerHandle handle) {
                        System.out.println("Handle: " + handle);
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timeoutMethodCount = 1, usesTimerHandle = true, timerHandleEscapes = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: TimerHandle usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimerHandleArgBean {

                   \s
                    public void onTimeout() {
                    }

                    private void saveHandle() {
                    }
                }
                """
            )
        );
    }

    @Test
    void autoTransformsLocalTimerGetHandle() {
        // P1.5: Local-only TimerHandle is auto-transformed to MigratedTimerHandle
        // The timer.getHandle() is replaced with new MigratedTimerHandle(...)
        // Note: TimerHandle import may remain but will be unused (RemoveUnusedImports cleans it later)
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleLocalBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        TimerHandle h = timer.getHandle();  // Local only - no escape
                        System.out.println("Handle: " + h);
                    }
                }
                """,
                """
                import com.github.migration.timer.MigratedTimerHandle;
                import jakarta.ejb.TimerHandle;
                import org.quartz.JobExecutionContext;
                import org.quartz.JobKey;

                public class TimerHandleLocalBean {

                   \s
                    public void onTimeout(JobExecutionContext timer) {
                        MigratedTimerHandle h = new MigratedTimerHandle(timer.getJobDetail().getKey().getName(), timer.getJobDetail().getKey().getGroup());  // Local only - no escape
                        System.out.println("Handle: " + h);
                    }
                }
                """
            )
        );
    }

    @Test
    void autoTransformsLocalTimerHandleWithCancel() {
        // P1.5: Local TimerHandle with cancel() is auto-transformed
        // handle.getTimer().cancel() becomes scheduler.deleteJob(new JobKey(...))
        // Scheduler field is injected via constructor since TimerService was present
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleCancelBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        TimerHandle h = timer.getHandle();
                        h.getTimer().cancel();  // Cancel via handle
                    }
                }
                """,
                """
                import com.github.migration.timer.MigratedTimerHandle;
                import jakarta.ejb.TimerHandle;
                import org.quartz.JobExecutionContext;
                import org.quartz.JobKey;
                import org.quartz.Scheduler;

                public class TimerHandleCancelBean {
                   \s
                    private final Scheduler scheduler;

                   \s
                    public void onTimeout(JobExecutionContext timer) {
                        MigratedTimerHandle h = new MigratedTimerHandle(timer.getJobDetail().getKey().getName(), timer.getJobDetail().getKey().getGroup());
                        scheduler.deleteJob(new JobKey(h.getJobName(), h.getJobGroup()));  // Cancel via handle
                    }

                    public TimerHandleCancelBean(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }
                }
                """
            )
        );
    }

    @Test
    void fallsBackToMarkerForTimerHandleLocalInTimeoutNotFromTimerParam() {
        // P1.5 Review 7: TimerHandle local in @Timeout method but NOT from timerParam.getHandle()
        // should be treated as escape (not transformable)
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    boolean usesTimerHandle() default false;
                    boolean timerHandleEscapes() default false;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleNotFromTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // TimerHandle from another source, NOT from timer.getHandle()
                        TimerHandle h = loadHandleFromDatabase();
                        h.getTimer().cancel();
                    }

                    private TimerHandle loadHandleFromDatabase() {
                        return null;  // Simulated
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.TimerHandle;

                @EjbQuartzTimerService(timeoutMethodCount = 1, usesTimerHandle = true, timerHandleEscapes = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: timer.cancel() usage, TimerHandle usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimerHandleNotFromTimerBean {

                   \s
                    public void onTimeout() {
                    }

                    private TimerHandle loadHandleFromDatabase() {
                        return null;  // Simulated
                    }
                }
                """
            )
        );
    }

    @Test
    void fallsBackToMarkerForTimerHandleLocalInNonTimeoutMethod() {
        // P1.5 Review 6: TimerHandle local variable in a non-@Timeout method is an escape
        // even if the handle doesn't come from timer.getHandle() (e.g., from another API).
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    boolean usesTimerHandle() default false;
                    boolean timerHandleEscapes() default false;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleLocalBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // Normal timeout processing
                    }

                    // Non-@Timeout method with TimerHandle local variable from another source
                    public void processStoredHandle() {
                        TimerHandle h = loadHandleFromDatabase();  // Not from timer.getHandle()
                        h.getTimer().cancel();
                    }

                    private TimerHandle loadHandleFromDatabase() {
                        return null;  // Simulated
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.TimerHandle;

                @EjbQuartzTimerService(timeoutMethodCount = 1, usesTimerHandle = true, timerHandleEscapes = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: timer.cancel() usage, TimerHandle usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimerHandleLocalBean {

                   \s
                    public void onTimeout() {
                    }

                    // Non-@Timeout method with TimerHandle local variable from another source
                    public void processStoredHandle() {
                    }

                    private TimerHandle loadHandleFromDatabase() {
                        return null;  // Simulated
                    }
                }
                """
            )
        );
    }

    @Test
    void fallsBackToMarkerForTimerHandleParameterInNonTimeoutMethod() {
        // P1.5 Review 5: TimerHandle parameter in a non-@Timeout method is an escape
        // because we cannot transform it. The handle.getTimer().cancel() should NOT be
        // treated as "cancel via handle" since it's not in a @Timeout method.
        // The class also has a @Timeout method to ensure hasTimerUsage() returns true.
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    boolean usesTimerHandle() default false;
                    boolean timerHandleEscapes() default false;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleParamBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // Normal timeout processing
                    }

                    // Non-@Timeout method with TimerHandle parameter - this is an escape
                    public void stopTimer(TimerHandle handle) {
                        handle.getTimer().cancel();  // Cancel via handle in non-@Timeout
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timeoutMethodCount = 1, usesTimerHandle = true, timerHandleEscapes = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: timer.cancel() usage, TimerHandle usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimerHandleParamBean {

                   \s
                    public void onTimeout() {
                    }

                    // Non-@Timeout method with TimerHandle parameter - this is an escape
                    public void stopTimer() {
                    }
                }
                """
            )
        );
    }

    @Test
    void fallsBackToMarkerForTimerHandleParamInTimeoutMethod() {
        // P1.5 Review 8: TimerHandle parameter in @Timeout method is an escape
        // because it's injected (not derived from timerParam.getHandle()).
        // The marker should include usesTimerHandleParamInTimeout = true.
        rewriteRun(
            java(
                """
                package com.github.migration.annotations;

                import java.lang.annotation.ElementType;
                import java.lang.annotation.Retention;
                import java.lang.annotation.RetentionPolicy;
                import java.lang.annotation.Target;

                @Target({ElementType.TYPE})
                @Retention(RetentionPolicy.SOURCE)
                public @interface EjbQuartzTimerService {
                    String timerPattern() default "";
                    boolean usesTimerInfo() default false;
                    boolean dynamicTimerCreation() default false;
                    int timeoutMethodCount() default 0;
                    boolean usesTimerHandle() default false;
                    boolean timerHandleEscapes() default false;
                    boolean usesTimerHandleParamInTimeout() default false;
                    String migrationNotes() default "";
                }
                """
            ),
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleParamBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer, TimerHandle handle) {
                        // TimerHandle injected as parameter, not from timer.getHandle()
                        handle.getTimer().cancel();
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timeoutMethodCount = 1, usesTimerHandle = true, timerHandleEscapes = true, usesTimerHandleParamInTimeout = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: timer.cancel() usage, TimerHandle usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class TimerHandleParamBean {

                   \s
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    // ========== P1.6: timer.getSchedule() Tests ==========

    @Test
    void transformsTimerGetScheduleWithCalendarTimer() {
        // P1.6: timer.getSchedule() can be transformed when a safe calendar timer exists
        // The schedule info is preserved in MigratedScheduleInfo for runtime access
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class CalendarTimerWithGetSchedule {
                    @Resource
                    private TimerService timerService;

                    public void scheduleDaily() {
                        // Inline ScheduleExpression chain allows automatic transformation
                        timerService.createCalendarTimer(
                            new ScheduleExpression()
                                .hour("2")
                                .minute("0")
                                .second("0")
                                .dayOfWeek("Mon-Fri"),
                            new TimerConfig("daily-job", true));
                    }

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // P1.6: Access schedule info at runtime
                        String hour = timer.getSchedule().getHour();
                        System.out.println("Running at hour: " + hour);
                    }
                }
                """,
                """
                import com.github.migration.timer.MigratedScheduleInfo;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.TimerConfig;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class CalendarTimerWithGetSchedule {
                   \s
                    private final Scheduler scheduler;

                    public void scheduleDaily() {
                        // Inline ScheduleExpression chain allows automatic transformation
                        scheduleQuartzCronJob("0 0 2 ? * Mon-Fri", new TimerConfig("daily-job", true).getInfo(), new TimerConfig("daily-job", true).isPersistent(), CalendarTimerWithGetScheduleJob.class, "0", "0", "2", "*", "*", "Mon-Fri");
                    }

                   \s
                    public void onTimeout(JobExecutionContext timer) {
                        // P1.6: Access schedule info at runtime
                        String hour = ((MigratedScheduleInfo)timer.getMergedJobDataMap().get("scheduleInfo")).getHour();
                        System.out.println("Running at hour: " + hour);
                    }

                    public CalendarTimerWithGetSchedule(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzCronJob(String cronExpression, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass,
                                                   String second, String minute, String hour, String dayOfMonth, String month, String dayOfWeek) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            // P1.6: Store schedule info for timer.getSchedule() compatibility
                            MigratedScheduleInfo scheduleInfo = new MigratedScheduleInfo(
                                    second, minute, hour, dayOfMonth, month, dayOfWeek, cronExpression);
                            jobDataMap.put("scheduleInfo", scheduleInfo);
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule cron job", e);
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsTimerGetScheduleChainedCalls() {
        // P1.6: Chained calls like timer.getSchedule().getMinute() should work
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class GetScheduleChained {
                    @Resource
                    private TimerService timerService;

                    public void scheduleHourly() {
                        // Inline ScheduleExpression chain allows automatic transformation
                        timerService.createCalendarTimer(
                            new ScheduleExpression()
                                .hour("*")
                                .minute("30")
                                .second("0"),
                            new TimerConfig(null, false));
                    }

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // Multiple getters on schedule
                        String min = timer.getSchedule().getMinute();
                        String sec = timer.getSchedule().getSecond();
                        String dow = timer.getSchedule().getDayOfWeek();
                        System.out.println("Schedule: " + sec + " " + min + " " + dow);
                    }
                }
                """,
                """
                import com.github.migration.timer.MigratedScheduleInfo;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.TimerConfig;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class GetScheduleChained {
                   \s
                    private final Scheduler scheduler;

                    public void scheduleHourly() {
                        // Inline ScheduleExpression chain allows automatic transformation
                        scheduleQuartzCronJob("0 30 * ? * *", new TimerConfig(null, false).getInfo(), new TimerConfig(null, false).isPersistent(), GetScheduleChainedJob.class, "0", "30", "*", "*", "*", "*");
                    }

                   \s
                    public void onTimeout(JobExecutionContext timer) {
                        // Multiple getters on schedule
                        String min = ((MigratedScheduleInfo)timer.getMergedJobDataMap().get("scheduleInfo")).getMinute();
                        String sec = ((MigratedScheduleInfo)timer.getMergedJobDataMap().get("scheduleInfo")).getSecond();
                        String dow = ((MigratedScheduleInfo)timer.getMergedJobDataMap().get("scheduleInfo")).getDayOfWeek();
                        System.out.println("Schedule: " + sec + " " + min + " " + dow);
                    }

                    public GetScheduleChained(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzCronJob(String cronExpression, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass,
                                                   String second, String minute, String hour, String dayOfMonth, String month, String dayOfWeek) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            // P1.6: Store schedule info for timer.getSchedule() compatibility
                            MigratedScheduleInfo scheduleInfo = new MigratedScheduleInfo(
                                    second, minute, hour, dayOfMonth, month, dayOfWeek, cronExpression);
                            jobDataMap.put("scheduleInfo", scheduleInfo);
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule cron job", e);
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void marksGetScheduleWithoutCalendarTimerAsUnsupported() {
        // P1.6: When timer.getSchedule() is used but there's no calendar timer,
        // we cannot determine the schedule at migration time -> marker annotation
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;

                public class GetScheduleNoCalendarTimer {
                    @Resource
                    private TimerService timerService;

                    public void scheduleSimple() {
                        timerService.createTimer(1000L, "simple");
                    }

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // getSchedule() on a non-calendar timer - cannot transform
                        String hour = timer.getSchedule().getHour();
                        System.out.println("Hour: " + hour);
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "single", timeoutMethodCount = 1, usesTimerGetSchedule = true, hasSingleTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: Timer.getSchedule() usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/single", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class GetScheduleNoCalendarTimer {

                    public void scheduleSimple() {
                    }

                   \s
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void marksGetScheduleWithDynamicCalendarTimerAsUnsupported() {
        // P1.6: When timer.getSchedule() is used with a dynamically built schedule expression,
        // we cannot determine the schedule at migration time -> marker annotation
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class GetScheduleDynamicSchedule {
                    @Resource
                    private TimerService timerService;

                    private String configuredHour = "3";

                    public void scheduleDynamic() {
                        ScheduleExpression expr = new ScheduleExpression()
                            .hour(configuredHour)  // Dynamic - uses variable
                            .minute("0");
                        timerService.createCalendarTimer(expr, new TimerConfig(null, false));
                    }

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // Cannot transform because schedule is dynamic
                        String hour = timer.getSchedule().getHour();
                        System.out.println("Hour: " + hour);
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, usesTimerGetSchedule = true, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions, Timer.getSchedule() usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class GetScheduleDynamicSchedule {

                    private String configuredHour = "3";

                    public void scheduleDynamic() {
                    }

                   \s
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void transformsDirectScheduleExpressionAssignment() {
        // P1.6 Review 1: Direct assignment ScheduleExpression s = timer.getSchedule() should be transformed
        // to MigratedScheduleInfo s = (MigratedScheduleInfo) timer.getMergedJobDataMap().get("scheduleInfo")
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class DirectAssignment {
                    @Resource
                    private TimerService timerService;

                    public void scheduleHourly() {
                        // Inline ScheduleExpression chain allows automatic transformation
                        timerService.createCalendarTimer(
                            new ScheduleExpression()
                                .hour("*")
                                .minute("30")
                                .second("0"),
                            new TimerConfig(null, false));
                    }

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // P1.6 Review 1: Direct assignment - should transform to MigratedScheduleInfo
                        ScheduleExpression schedule = timer.getSchedule();
                        String min = schedule.getMinute();
                        System.out.println("Minute: " + min);
                    }
                }
                """,
                """
                import com.github.migration.timer.MigratedScheduleInfo;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.TimerConfig;
                import org.quartz.*;

                import java.time.Instant;
                import java.util.Date;

                public class DirectAssignment {
                   \s
                    private final Scheduler scheduler;

                    public void scheduleHourly() {
                        // Inline ScheduleExpression chain allows automatic transformation
                        scheduleQuartzCronJob("0 30 * ? * *", new TimerConfig(null, false).getInfo(), new TimerConfig(null, false).isPersistent(), DirectAssignmentJob.class, "0", "30", "*", "*", "*", "*");
                    }

                   \s
                    public void onTimeout(JobExecutionContext timer) {
                        // P1.6 Review 1: Direct assignment - should transform to MigratedScheduleInfo
                        MigratedScheduleInfo schedule =(MigratedScheduleInfo)timer.getMergedJobDataMap().get("scheduleInfo");
                        String min = schedule.getMinute();
                        System.out.println("Minute: " + min);
                    }

                    public DirectAssignment(Scheduler scheduler) {
                        this.scheduler = scheduler;
                    }

                    private void scheduleQuartzCronJob(String cronExpression, Object info, boolean persistent, Class<? extends org.quartz.Job> jobClass,
                                                   String second, String minute, String hour, String dayOfMonth, String month, String dayOfWeek) {
                        try {
                            JobDataMap jobDataMap = new JobDataMap();
                            if (info != null) {
                                jobDataMap.put("info", info);
                            }
                            // P1.6: Store schedule info for timer.getSchedule() compatibility
                            MigratedScheduleInfo scheduleInfo = new MigratedScheduleInfo(
                                    second, minute, hour, dayOfMonth, month, dayOfWeek, cronExpression);
                            jobDataMap.put("scheduleInfo", scheduleInfo);
                            JobBuilder jobBuilder = JobBuilder.newJob(jobClass)
                                    .usingJobData(jobDataMap);
                            if (persistent) {
                                jobBuilder = jobBuilder.storeDurably(true).requestRecovery(true);
                            }
                            JobDetail job = jobBuilder.build();
                            Trigger trigger = TriggerBuilder.newTrigger()
                                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                                    .build();
                            scheduler.scheduleJob(job, trigger);
                        } catch (SchedulerException e) {
                            throw new RuntimeException("Failed to schedule cron job", e);
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void marksGetScheduleOutsideTimeoutAsEscape() {
        // P1.6 Review 1: timer.getSchedule() outside @Timeout cannot be transformed -> marker annotation
        // This tests the timerGetScheduleEscapes flag
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class GetScheduleOutsideTimeout {
                    @Resource
                    private TimerService timerService;

                    public void probe(Timer timer) {
                        // P1.6 Review 1: getSchedule() outside @Timeout - should trigger escape
                        String hour = timer.getSchedule().getHour();
                        System.out.println(hour);
                    }

                    @Timeout
                    public void onTimeout(Timer timer) {
                        System.out.println("Timer fired");
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timeoutMethodCount = 1, usesTimerGetSchedule = true, timerGetScheduleEscapes = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: Timer.getSchedule() usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class GetScheduleOutsideTimeout {

                    public void probe() {
                    }

                   \s
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void marksGetScheduleAsArgumentAsEscape() {
        // P1.6 Review 3: foo(timer.getSchedule()) cannot be transformed -> marker annotation
        // The target method expects ScheduleExpression, not MigratedScheduleInfo
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class GetScheduleAsArgument {
                    @Resource
                    private TimerService timerService;

                    public void scheduleTask() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression()
                                .hour("3")
                                .minute("15"),
                            new TimerConfig(null, false));
                    }

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // P1.6 Review 3: getSchedule() as argument - should trigger escape
                        logSchedule(timer.getSchedule());
                    }

                    private void logSchedule(ScheduleExpression expr) {
                        System.out.println(expr.getHour());
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.ScheduleExpression;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, usesTimerGetSchedule = true, timerGetScheduleEscapes = true, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions, Timer.getSchedule() usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class GetScheduleAsArgument {

                    public void scheduleTask() {
                    }

                   \s
                    public void onTimeout() {
                    }

                    private void logSchedule(ScheduleExpression expr) {
                        System.out.println(expr.getHour());
                    }
                }
                """
            )
        );
    }

    @Test
    void marksGetScheduleReturnAsEscape() {
        // P1.6 Review 3: return timer.getSchedule() cannot be transformed -> marker annotation
        // The method return type would be ScheduleExpression, not MigratedScheduleInfo
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class GetScheduleReturn {
                    @Resource
                    private TimerService timerService;

                    public void scheduleTask() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression()
                                .hour("3")
                                .minute("15"),
                            new TimerConfig(null, false));
                    }

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // P1.6 Review 3: return getSchedule() - should trigger escape
                        ScheduleExpression schedule = getScheduleFrom(timer);
                        System.out.println(schedule.getHour());
                    }

                    private ScheduleExpression getScheduleFrom(Timer t) {
                        return t.getSchedule();
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.ScheduleExpression;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, usesTimerGetSchedule = true, timerGetScheduleEscapes = true, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions, Timer.getSchedule() usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class GetScheduleReturn {

                    public void scheduleTask() {
                    }

                   \s
                    public void onTimeout() {
                    }

                    private ScheduleExpression getScheduleFrom() {
                    }
                }
                """
            )
        );
    }

    @Test
    void marksUnsupportedScheduleGetterAsEscape() {
        // P1.6 Review 3: timer.getSchedule().getYear() cannot be transformed -> marker annotation
        // MigratedScheduleInfo doesn't have getYear(), getTimezone(), getStart(), getEnd()
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    String reason() default "";
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, TIMER, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION, SEMANTIC_CHANGE, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class UnsupportedScheduleGetter {
                    @Resource
                    private TimerService timerService;

                    public void scheduleTask() {
                        timerService.createCalendarTimer(
                            new ScheduleExpression()
                                .hour("3")
                                .minute("15"),
                            new TimerConfig(null, false));
                    }

                    @Timeout
                    public void onTimeout(Timer timer) {
                        // P1.6 Review 3: getYear() is not supported in MigratedScheduleInfo
                        String year = timer.getSchedule().getYear();
                        System.out.println("Year: " + year);
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbQuartzTimerService;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @EjbQuartzTimerService(timerPattern = "calendar", timeoutMethodCount = 1, usesTimerGetSchedule = true, timerGetScheduleEscapes = true, hasCalendarTimer = true)
                @NeedsReview(reason = "EJB Timer requires manual Quartz migration: calendar-based timer expressions, Timer.getSchedule() usage", category = NeedsReview.Category.TIMER, originalCode = "TimerService/calendar", suggestedAction = "Manually convert EJB TimerService calls to Quartz Scheduler pattern. See @EjbQuartzTimerService annotation for specific timer usage patterns.")
                public class UnsupportedScheduleGetter {

                    public void scheduleTask() {
                    }

                   \s
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }
}
