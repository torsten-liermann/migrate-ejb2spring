package com.github.migration.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for classes using EJB TimerService that should be migrated to Quartz.
 * <p>
 * This annotation is placed on classes that:
 * - Use @Resource TimerService for programmatic timer creation
 * - Use @Timeout methods for timer callbacks
 * - Require persistent/clustered scheduling (Quartz use case)
 * <p>
 * The annotation preserves information for manual migration to Quartz pattern:
 * - Create a Quartz Job class implementing org.quartz.Job
 * - Configure JobDetail and Trigger beans in Spring configuration
 * - Use JobDataMap for Timer.getInfo() replacement
 * - Use Quartz Scheduler for programmatic timer management
 * <p>
 * Migration steps:
 * 1. Create Job class implementing org.quartz.Job
 * 2. Move @Timeout method logic to Job.execute()
 * 3. Replace TimerService.createTimer() with Scheduler.scheduleJob()
 * 4. Replace Timer.getInfo() with JobDataMap
 * 5. Replace Timer.cancel() with Scheduler.deleteJob()
 */
@Target({ElementType.TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface EjbQuartzTimerService {

    /**
     * Describes the timer usage pattern in this class.
     * Possible values: "interval", "single", "calendar", "mixed"
     */
    String timerPattern() default "";

    /**
     * Whether the class uses Timer.getInfo() for passing data.
     */
    boolean usesTimerInfo() default false;

    /**
     * Whether timers are created dynamically (not just on startup).
     */
    boolean dynamicTimerCreation() default false;

    /**
     * Number of @Timeout methods in the class.
     */
    int timeoutMethodCount() default 0;

    /**
     * P1.5: Whether the class uses Timer.getHandle() for persistent timer references.
     * When true, manual migration to MigratedTimerHandle is required.
     */
    boolean usesTimerHandle() default false;

    /**
     * P1.5: Whether TimerHandle escapes local scope (stored in field, returned, or passed to method).
     * When true, careful review is needed as the handle may need serialization support.
     */
    boolean timerHandleEscapes() default false;

    /**
     * P1.5 Review 8: Whether a TimerHandle is injected as a parameter into an @Timeout method.
     * This is distinct from timerHandleEscapes because the handle source is external injection,
     * not local getHandle() calls. Manual migration must determine where the handle originates.
     */
    boolean usesTimerHandleParamInTimeout() default false;

    /**
     * P1.6: Whether the class uses Timer.getSchedule() for accessing schedule information.
     * When true with safe calendar timer, automatic transformation is possible.
     * When true without safe calendar timer, manual migration is required.
     */
    boolean usesTimerGetSchedule() default false;

    /**
     * P1.6 Review 3: Whether getSchedule() escapes safe transformation scope.
     * This happens when getSchedule() is:
     * - Called outside @Timeout method
     * - Passed as argument to another method: foo(timer.getSchedule())
     * - Returned from a method: return timer.getSchedule()
     * - Used with unsupported getter: timer.getSchedule().getYear()
     * When true, manual migration is required as auto-transform cannot handle these patterns.
     */
    boolean timerGetScheduleEscapes() default false;

    /**
     * Whether the class creates single/single-action timers (createTimer/createSingleActionTimer).
     */
    boolean hasSingleTimer() default false;

    /**
     * Whether the class creates interval timers (createIntervalTimer).
     */
    boolean hasIntervalTimer() default false;

    /**
     * Whether the class creates calendar timers (createCalendarTimer).
     */
    boolean hasCalendarTimer() default false;

    /**
     * Additional notes for manual migration.
     */
    String migrationNotes() default "";
}
