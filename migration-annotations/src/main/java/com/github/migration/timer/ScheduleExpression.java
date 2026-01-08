package com.github.migration.timer;

import java.io.Serializable;
import java.util.Date;

/**
 * Stub class representing EJB ScheduleExpression for migration compatibility.
 * <p>
 * This is a compile-time stub that allows migrated code to compile while
 * being marked for manual review.
 * <p>
 * Original: jakarta.ejb.ScheduleExpression / javax.ejb.ScheduleExpression
 * <p>
 * Migration: Convert to Spring cron expression or Quartz CronScheduleBuilder
 * <p>
 * EJB cron format: second minute hour dayOfMonth month dayOfWeek year
 * Spring cron format: second minute hour dayOfMonth month dayOfWeek
 * <p>
 * Example conversions:
 * <pre>
 * // EJB: every 5 seconds
 * new ScheduleExpression().hour("*").minute("*").second("0/5")
 * // Spring cron: "0/5 * * * * *"
 *
 * // EJB: every Monday at 8:00 AM
 * new ScheduleExpression().dayOfWeek("Mon").hour("8").minute("0").second("0")
 * // Spring cron: "0 0 8 * * MON"
 * </pre>
 *
 * @see com.github.migration.timer.TimerService
 */
public class ScheduleExpression implements Serializable {

    private static final long serialVersionUID = 1L;

    private String second = "0";
    private String minute = "0";
    private String hour = "0";
    private String dayOfMonth = "*";
    private String month = "*";
    private String dayOfWeek = "*";
    private String year = "*";
    private String timezone;
    private Date start;
    private Date end;

    public ScheduleExpression() {
    }

    /**
     * Sets the second attribute.
     * Values: 0-59, lists, ranges, increments, or "*"
     */
    public ScheduleExpression second(String s) {
        this.second = s;
        return this;
    }

    /**
     * Sets the second attribute with int.
     */
    public ScheduleExpression second(int s) {
        return second(String.valueOf(s));
    }

    /**
     * Sets the minute attribute.
     * Values: 0-59, lists, ranges, increments, or "*"
     */
    public ScheduleExpression minute(String m) {
        this.minute = m;
        return this;
    }

    /**
     * Sets the minute attribute with int.
     */
    public ScheduleExpression minute(int m) {
        return minute(String.valueOf(m));
    }

    /**
     * Sets the hour attribute.
     * Values: 0-23, lists, ranges, increments, or "*"
     */
    public ScheduleExpression hour(String h) {
        this.hour = h;
        return this;
    }

    /**
     * Sets the hour attribute with int.
     */
    public ScheduleExpression hour(int h) {
        return hour(String.valueOf(h));
    }

    /**
     * Sets the day-of-month attribute.
     * Values: 1-31, "Last", -7 to -1, lists, ranges, or "*"
     */
    public ScheduleExpression dayOfMonth(String d) {
        this.dayOfMonth = d;
        return this;
    }

    /**
     * Sets the day-of-month attribute with int.
     */
    public ScheduleExpression dayOfMonth(int d) {
        return dayOfMonth(String.valueOf(d));
    }

    /**
     * Sets the month attribute.
     * Values: 1-12, Jan-Dec, lists, ranges, or "*"
     */
    public ScheduleExpression month(String m) {
        this.month = m;
        return this;
    }

    /**
     * Sets the month attribute with int.
     */
    public ScheduleExpression month(int m) {
        return month(String.valueOf(m));
    }

    /**
     * Sets the day-of-week attribute.
     * Values: 0-7 (0 and 7 are Sunday), Sun-Sat, lists, ranges, or "*"
     */
    public ScheduleExpression dayOfWeek(String d) {
        this.dayOfWeek = d;
        return this;
    }

    /**
     * Sets the day-of-week attribute with int.
     */
    public ScheduleExpression dayOfWeek(int d) {
        return dayOfWeek(String.valueOf(d));
    }

    /**
     * Sets the year attribute.
     * Values: 4-digit year, lists, ranges, or "*"
     */
    public ScheduleExpression year(String y) {
        this.year = y;
        return this;
    }

    /**
     * Sets the year attribute with int.
     */
    public ScheduleExpression year(int y) {
        return year(String.valueOf(y));
    }

    /**
     * Sets the timezone.
     */
    public ScheduleExpression timezone(String tz) {
        this.timezone = tz;
        return this;
    }

    /**
     * Sets the start date.
     */
    public ScheduleExpression start(Date s) {
        this.start = s;
        return this;
    }

    /**
     * Sets the end date.
     */
    public ScheduleExpression end(Date e) {
        this.end = e;
        return this;
    }

    // Getters

    public String getSecond() {
        return second;
    }

    public String getMinute() {
        return minute;
    }

    public String getHour() {
        return hour;
    }

    public String getDayOfMonth() {
        return dayOfMonth;
    }

    public String getMonth() {
        return month;
    }

    public String getDayOfWeek() {
        return dayOfWeek;
    }

    public String getYear() {
        return year;
    }

    public String getTimezone() {
        return timezone;
    }

    public Date getStart() {
        return start;
    }

    public Date getEnd() {
        return end;
    }

    /**
     * Converts this ScheduleExpression to a Spring cron expression.
     * <p>
     * Note: Spring cron does not support year field. If year is specified
     * and not "*", a warning comment should be added during migration.
     *
     * @return Spring-compatible cron expression
     */
    public String toSpringCron() {
        return String.format("%s %s %s %s %s %s",
            second, minute, hour, dayOfMonth, month, dayOfWeek);
    }
}
