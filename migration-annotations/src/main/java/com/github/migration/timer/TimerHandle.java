package com.github.migration.timer;

import java.io.Serializable;

/**
 * Stub interface representing EJB TimerHandle for migration compatibility.
 * <p>
 * This is a compile-time stub that allows migrated code to compile while
 * being marked for manual review.
 * <p>
 * Original: jakarta.ejb.TimerHandle / javax.ejb.TimerHandle
 * <p>
 * Migration: There is no direct equivalent in Spring. Consider:
 * <ul>
 *   <li>Storing Quartz JobKey for persistent job references</li>
 *   <li>Using ScheduledFuture references for TaskScheduler</li>
 * </ul>
 *
 * @see com.github.migration.timer.Timer
 */
public interface TimerHandle extends Serializable {

    /**
     * Obtain a reference to the timer.
     * <p>
     * Migration: Use Scheduler.getJobDetail(jobKey) in Quartz
     */
    Timer getTimer();
}
