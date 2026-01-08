package com.github.migration.timer;

import java.io.Serializable;

/**
 * Stub class representing EJB TimerConfig for migration compatibility.
 * <p>
 * This is a compile-time stub that allows migrated code to compile while
 * being marked for manual review.
 * <p>
 * Original: jakarta.ejb.TimerConfig / javax.ejb.TimerConfig
 * <p>
 * Migration:
 * <ul>
 *   <li>info: Pass as method parameter or store in JobDataMap (Quartz)</li>
 *   <li>persistent: Use Quartz for persistent timers, TaskScheduler for non-persistent</li>
 * </ul>
 *
 * @see com.github.migration.timer.TimerService
 */
public class TimerConfig implements Serializable {

    private static final long serialVersionUID = 1L;

    private Serializable info;
    private boolean persistent;

    /**
     * Creates a TimerConfig with default values.
     */
    public TimerConfig() {
        this.persistent = true;
    }

    /**
     * Creates a TimerConfig with the specified info and persistence flag.
     *
     * @param info application-provided data, passed to timeout callback
     * @param persistent true for persistent timers (survives restart), false for transient
     */
    public TimerConfig(Serializable info, boolean persistent) {
        this.info = info;
        this.persistent = persistent;
    }

    /**
     * Returns the application-provided info object.
     * <p>
     * Migration: Store in JobDataMap (Quartz) or pass as method parameter
     */
    public Serializable getInfo() {
        return info;
    }

    /**
     * Sets the application-provided info object.
     */
    public void setInfo(Serializable info) {
        this.info = info;
    }

    /**
     * Returns whether this timer should be persistent.
     * <p>
     * Migration: Use Quartz for persistent timers (survives restart)
     */
    public boolean isPersistent() {
        return persistent;
    }

    /**
     * Sets whether this timer should be persistent.
     */
    public void setPersistent(boolean persistent) {
        this.persistent = persistent;
    }
}
