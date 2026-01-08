/**
 * Migration stub types for EJB Timer API.
 * <p>
 * These are compile-time stubs that allow migrated code to compile while
 * being marked for manual review. The classes in this package mirror the
 * EJB Timer API (jakarta.ejb.Timer, TimerService, etc.) but do not provide
 * runtime functionality.
 * <p>
 * Classes using these types should be annotated with
 * {@link com.github.rewrite.ejb.annotations.NeedsReview} and will require
 * manual migration to either:
 * <ul>
 *   <li>Spring TaskScheduler - for simple, non-persistent scheduling</li>
 *   <li>Quartz Scheduler - for persistent/clustered scheduling</li>
 * </ul>
 * <p>
 * Migration recipes will:
 * <ol>
 *   <li>Change imports from jakarta.ejb/javax.ejb to com.github.migration.timer</li>
 *   <li>Add @NeedsReview annotation with migration guidance</li>
 *   <li>Add @Profile("manual-migration") to disable the bean at runtime</li>
 * </ol>
 *
 * @see com.github.migration.timer.Timer
 * @see com.github.migration.timer.TimerService
 * @see com.github.migration.timer.ScheduleExpression
 * @see com.github.migration.timer.TimerConfig
 */
package com.github.migration.timer;
