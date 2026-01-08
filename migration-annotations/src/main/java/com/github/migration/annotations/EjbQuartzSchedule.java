package com.github.migration.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marker annotation for EJB @Schedule methods that should be migrated to Quartz.
 * <p>
 * This annotation is used for methods that require persistent/clustered scheduling:
 * - Methods with Timer parameter that use Timer API
 * - When timer.strategy = quartz is configured
 * <p>
 * The annotation preserves the original EJB schedule configuration for manual
 * migration to Quartz Job/Trigger pattern:
 * - Create a Quartz Job class implementing org.quartz.Job
 * - Configure JobDetail and Trigger beans in Spring configuration
 * - Use JobDataMap for Timer.getInfo() replacement
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.SOURCE)
public @interface EjbQuartzSchedule {

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

    /**
     * Raw expression from original @Schedule/@Schedules annotation.
     * Used when the annotation contains non-literal values or multiple schedules
     * that cannot be safely transformed. Manual migration is required.
     */
    String rawExpression() default "";

}
