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

    /**
     * Raw expression from original @Schedule/@Schedules annotation.
     * Used when the annotation contains non-literal values or multiple schedules
     * that cannot be safely transformed. Manual migration is required.
     */
    String rawExpression() default "";

}
