package com.github.migration.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.SOURCE)
public @interface EjbEJB {

    String name() default "";
    String description() default "";
    String beanName() default "";
    Class beanInterface() default Object.class;
    String mappedName() default "";
    String lookup() default "";

}
