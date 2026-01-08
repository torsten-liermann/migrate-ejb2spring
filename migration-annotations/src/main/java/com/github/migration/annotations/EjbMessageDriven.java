package com.github.migration.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.TYPE})
@Retention(RetentionPolicy.SOURCE)
public @interface EjbMessageDriven {

    String name() default "";
    Class messageListenerInterface() default Object.class;
    EjbActivationConfigProperty[] activationConfig() default {};
    String mappedName() default "";
    String description() default "";

}
