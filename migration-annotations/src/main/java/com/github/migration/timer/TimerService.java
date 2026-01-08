package com.github.migration.timer;

import java.io.Serializable;
import java.util.Collection;
import java.util.Date;

/**
 * Stub interface representing EJB TimerService for migration compatibility.
 * <p>
 * This is a compile-time stub that allows migrated code to compile while
 * being marked for manual review. Classes using this interface should be
 * annotated with @NeedsReview and will not function at runtime until
 * manually migrated.
 * <p>
 * Original: jakarta.ejb.TimerService / javax.ejb.TimerService
 * <p>
 * Migration options:
 * <ul>
 *   <li>Spring TaskScheduler - for simple, non-persistent scheduling</li>
 *   <li>Quartz Scheduler - for persistent/clustered scheduling</li>
 * </ul>
 * <p>
 * Example Spring TaskScheduler migration:
 * <pre>
 * // Before (EJB)
 * &#64;Resource
 * private TimerService timerService;
 *
 * public void scheduleTask() {
 *     timerService.createSingleActionTimer(5000, new TimerConfig("data", false));
 * }
 *
 * // After (Spring)
 * &#64;Autowired
 * private TaskScheduler taskScheduler;
 *
 * public void scheduleTask() {
 *     taskScheduler.schedule(() -&gt; executeTimeout("data"),
 *         Instant.now().plusMillis(5000));
 * }
 * </pre>
 *
 * @see com.github.migration.timer.Timer
 * @see com.github.rewrite.ejb.annotations.NeedsReview
 */
public interface TimerService {

    /**
     * Creates a single-action timer.
     * <p>
     * Migration: Use TaskScheduler.schedule(Runnable, Instant)
     */
    Timer createTimer(long duration, Serializable info);

    /**
     * Creates a single-action timer with Date.
     * <p>
     * Migration: Use TaskScheduler.schedule(Runnable, Instant)
     */
    Timer createTimer(Date expiration, Serializable info);

    /**
     * Creates an interval timer.
     * <p>
     * Migration: Use TaskScheduler.scheduleAtFixedRate(Runnable, Duration)
     */
    Timer createTimer(long initialDuration, long intervalDuration, Serializable info);

    /**
     * Creates an interval timer with Date.
     * <p>
     * Migration: Use TaskScheduler.scheduleAtFixedRate(Runnable, Instant, Duration)
     */
    Timer createTimer(Date initialExpiration, long intervalDuration, Serializable info);

    /**
     * Creates a single-action timer with configuration.
     * <p>
     * Migration: Use TaskScheduler.schedule(Runnable, Instant)
     */
    Timer createSingleActionTimer(long duration, TimerConfig timerConfig);

    /**
     * Creates a single-action timer with Date and configuration.
     * <p>
     * Migration: Use TaskScheduler.schedule(Runnable, Instant)
     */
    Timer createSingleActionTimer(Date expiration, TimerConfig timerConfig);

    /**
     * Creates an interval timer with configuration.
     * <p>
     * Migration: Use TaskScheduler.scheduleAtFixedRate(Runnable, Duration)
     */
    Timer createIntervalTimer(long initialDuration, long intervalDuration, TimerConfig timerConfig);

    /**
     * Creates an interval timer with Date and configuration.
     * <p>
     * Migration: Use TaskScheduler.scheduleAtFixedRate(Runnable, Instant, Duration)
     */
    Timer createIntervalTimer(Date initialExpiration, long intervalDuration, TimerConfig timerConfig);

    /**
     * Creates a calendar-based timer.
     * <p>
     * Migration: Use TaskScheduler with CronTrigger or Quartz
     */
    Timer createCalendarTimer(ScheduleExpression schedule);

    /**
     * Creates a calendar-based timer with configuration.
     * <p>
     * Migration: Use TaskScheduler with CronTrigger or Quartz
     */
    Timer createCalendarTimer(ScheduleExpression schedule, TimerConfig timerConfig);

    /**
     * Returns all active timers for this bean.
     * <p>
     * Migration: Maintain a collection of ScheduledFuture references
     */
    Collection<Timer> getTimers();

    /**
     * Returns all active timers for all EJBs in the module.
     * <p>
     * Migration: Not directly supported, use Quartz Scheduler.getCurrentlyExecutingJobs()
     */
    Collection<Timer> getAllTimers();
}
