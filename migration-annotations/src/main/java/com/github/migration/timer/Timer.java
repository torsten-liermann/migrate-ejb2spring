package com.github.migration.timer;

import java.io.Serializable;
import java.util.Date;

/**
 * Stub interface representing EJB Timer for migration compatibility.
 * <p>
 * This is a compile-time stub that allows migrated code to compile while
 * being marked for manual review. Classes using this interface should be
 * annotated with @NeedsReview and will not function at runtime until
 * manually migrated to Spring TaskScheduler or Quartz.
 * <p>
 * Original: jakarta.ejb.Timer / javax.ejb.Timer
 * <p>
 * Migration options:
 * <ul>
 *   <li>Spring TaskScheduler - for simple scheduling needs</li>
 *   <li>Quartz Scheduler - for persistent/clustered scheduling</li>
 * </ul>
 *
 * @see com.github.migration.timer.TimerService
 * @see com.github.rewrite.ejb.annotations.NeedsReview
 */
public interface Timer {

    /**
     * Cancels the timer.
     * <p>
     * Migration: Use ScheduledFuture.cancel() or Scheduler.deleteJob()
     */
    void cancel();

    /**
     * Returns the time remaining before the next scheduled expiration.
     * <p>
     * Migration: Calculate from ScheduledFuture.getDelay() or Trigger.getNextFireTime()
     */
    long getTimeRemaining();

    /**
     * Returns the date of the next scheduled timer expiration.
     * <p>
     * Migration: Use Trigger.getNextFireTime() in Quartz
     */
    Date getNextTimeout();

    /**
     * Returns the schedule expression for calendar-based timers.
     * <p>
     * Migration: Parse from cron expression or Trigger configuration
     */
    ScheduleExpression getSchedule();

    /**
     * Returns whether this timer is a calendar-based timer.
     */
    boolean isCalendarTimer();

    /**
     * Returns whether this timer is a persistent timer.
     */
    boolean isPersistent();

    /**
     * Returns the application-provided info object passed to createTimer.
     * <p>
     * Migration: Store in JobDataMap for Quartz, or use method parameters for TaskScheduler
     */
    Serializable getInfo();

    /**
     * Returns a serializable handle to the timer.
     * <p>
     * Migration: Not directly supported in Spring. Consider storing job identifiers.
     */
    TimerHandle getHandle();
}
