package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for MarkCdiQualifiersForReview recipe.
 * Uses minimal stubs to avoid classpath issues.
 */
class MarkCdiQualifiersForReviewTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkCdiQualifiersForReview())
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api"));
    }

    @Test
    void keepsFieldWithoutCustomAnnotation() {
        // Standard @Autowired field without custom qualifier should not be marked
        rewriteRun(
            // Stub for Spring types
            java(
                """
                package org.springframework.context;
                public interface ApplicationEventPublisher {
                    void publishEvent(Object event);
                }
                """
            ),
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.PARAMETER})
                public @interface Autowired {}
                """
            ),
            // Test class - no custom qualifier, should not change
            java(
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.ApplicationEventPublisher;

                public class SimpleService {
                    @Autowired
                    private ApplicationEventPublisher events;
                }
                """
            )
        );
    }

    @Test
    void marksFieldWithCdiQualifier() {
        // Field with CDI qualifier annotation should be marked
        rewriteRun(
            // Stub for Spring types
            java(
                """
                package org.springframework.context;
                public interface ApplicationEventPublisher {
                    void publishEvent(Object event);
                }
                """
            ),
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.PARAMETER})
                public @interface Autowired {}
                """
            ),
            // Custom CDI qualifier in events.cdi package
            java(
                """
                package org.example.infrastructure.events.cdi;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface CargoInspected {}
                """
            ),
            // NeedsReview stub (must match actual annotation structure)
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    Category category();
                    String reason() default "";
                    enum Category { CONFIGURATION, MANUAL_MIGRATION }
                }
                """
            ),
            // Test class with CDI qualifier - should get @NeedsReview added
            java(
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.ApplicationEventPublisher;
                import org.example.infrastructure.events.cdi.CargoInspected;

                public class InspectionService {
                    @Autowired @CargoInspected
                    private ApplicationEventPublisher cargoInspected;
                }
                """,
                // Note: @NeedsReview is added first, each annotation on its own line with original indentation
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.ApplicationEventPublisher;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.example.infrastructure.events.cdi.CargoInspected;

                public class InspectionService {
                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "CDI Qualifier @CargoInspected is ignored by Spring ApplicationEventPublisher. Recommended: Create wrapper event class (e.g., CargoInspectedEvent extends BaseEvent) and use publishEvent(new CargoInspectedEvent(payload)). Alternative: Add type field to event and filter with @EventListener(condition=\\"#event.type == 'cargoinspected'\\").")
                    @Autowired
                    @CargoInspected
                    private ApplicationEventPublisher cargoInspected;
                }
                """
            )
        );
    }

    @Test
    void marksEventListenerMethodWithQualifiedParameter() {
        // @EventListener method with CDI qualifier on parameter should be marked
        rewriteRun(
            // Stub for Spring types
            java(
                """
                package org.springframework.context;
                public interface ApplicationEventPublisher {
                    void publishEvent(Object event);
                }
                """
            ),
            java(
                """
                package org.springframework.context.event;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.METHOD)
                public @interface EventListener {}
                """
            ),
            // Custom CDI qualifier with @Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            java(
                """
                package org.example.events.cdi;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface CargoArrived {}
                """
            ),
            // Event class
            java(
                """
                package org.example.events;
                public class CargoEvent {
                    private String cargoId;
                    public String getCargoId() { return cargoId; }
                }
                """
            ),
            // NeedsReview stub
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    Category category();
                    String reason() default "";
                    enum Category { CONFIGURATION, MANUAL_MIGRATION }
                }
                """
            ),
            // Test class - @EventListener with qualifier on parameter
            java(
                """
                package org.example.listener;

                import org.springframework.context.event.EventListener;
                import org.example.events.CargoEvent;
                import org.example.events.cdi.CargoArrived;

                public class CargoListener {
                    @EventListener
                    public void onCargoArrived(@CargoArrived CargoEvent event) {
                        System.out.println("Cargo arrived: " + event.getCargoId());
                    }
                }
                """,
                """
                package org.example.listener;

                import org.springframework.context.event.EventListener;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.example.events.CargoEvent;
                import org.example.events.cdi.CargoArrived;

                public class CargoListener {
                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "CDI Qualifier @CargoArrived on @EventListener is ignored. Spring receives ALL events of this type. Recommended: Create wrapper event class (e.g., CargoArrivedEvent) and change parameter type. Alternative: Add @EventListener(condition=\\"#event.type == 'cargoarrived'\\") if event has type field.")
                    @EventListener
                    public void onCargoArrived(@CargoArrived CargoEvent event) {
                        System.out.println("Cargo arrived: " + event.getCargoId());
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMarkFieldWithNonQualifierAnnotation() {
        // Field with validation annotation (not a @Qualifier) should NOT be marked
        rewriteRun(
            // Stub for Spring types
            java(
                """
                package org.springframework.context;
                public interface ApplicationEventPublisher {
                    void publishEvent(Object event);
                }
                """
            ),
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.PARAMETER})
                public @interface Autowired {}
                """
            ),
            // Bean Validation annotation - NOT a CDI qualifier (no @Qualifier meta-annotation)
            java(
                """
                package jakarta.validation.constraints;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface NotNull {
                    String message() default "must not be null";
                }
                """
            ),
            // Custom annotation WITHOUT @Qualifier meta-annotation
            java(
                """
                package org.example.util;

                import java.lang.annotation.*;

                // This is NOT a CDI qualifier - it has no @Qualifier meta-annotation
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Audited {}
                """
            ),
            // Test class - field with non-qualifier annotations should NOT change
            java(
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.ApplicationEventPublisher;
                import jakarta.validation.constraints.NotNull;
                import org.example.util.Audited;

                public class AuditService {
                    @Autowired
                    @NotNull
                    @Audited
                    private ApplicationEventPublisher events;
                }
                """
            )
        );
    }

    @Test
    void marksFieldWithNamedQualifier() {
        // @Named is a standard CDI qualifier - should be marked
        rewriteRun(
            // Stub for Spring types
            java(
                """
                package org.springframework.context;
                public interface ApplicationEventPublisher {
                    void publishEvent(Object event);
                }
                """
            ),
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.PARAMETER})
                public @interface Autowired {}
                """
            ),
            // jakarta.inject.Named - standard CDI qualifier
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD, ElementType.PARAMETER})
                public @interface Named {
                    String value() default "";
                }
                """
            ),
            // NeedsReview stub
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    Category category();
                    String reason() default "";
                    enum Category { CONFIGURATION, MANUAL_MIGRATION }
                }
                """
            ),
            // Test class - @Named on ApplicationEventPublisher should be marked
            java(
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.ApplicationEventPublisher;
                import jakarta.inject.Named;

                public class NamedEventService {
                    @Autowired
                    @Named("highPriority")
                    private ApplicationEventPublisher namedEvents;
                }
                """,
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.ApplicationEventPublisher;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.inject.Named;

                public class NamedEventService {
                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "CDI Qualifier @Named is ignored by Spring ApplicationEventPublisher. Recommended: Create wrapper event class (e.g., NamedEvent extends BaseEvent) and use publishEvent(new NamedEvent(payload)). Alternative: Add type field to event and filter with @EventListener(condition=\\"#event.type == 'named'\\").")
                    @Autowired
                    @Named("highPriority")
                    private ApplicationEventPublisher namedEvents;
                }
                """
            )
        );
    }

    @Test
    void marksFieldWithProperQualifierMetaAnnotation() {
        // Field with annotation that HAS @Qualifier meta-annotation should be marked
        rewriteRun(
            // Stub for Spring types
            java(
                """
                package org.springframework.context;
                public interface ApplicationEventPublisher {
                    void publishEvent(Object event);
                }
                """
            ),
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.CONSTRUCTOR, ElementType.METHOD, ElementType.PARAMETER})
                public @interface Autowired {}
                """
            ),
            // jakarta.inject.Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom qualifier WITH @Qualifier meta-annotation
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Priority {}
                """
            ),
            // NeedsReview stub
            java(
                """
                package com.github.rewrite.ejb.annotations;

                import java.lang.annotation.*;

                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                public @interface NeedsReview {
                    Category category();
                    String reason() default "";
                    enum Category { CONFIGURATION, MANUAL_MIGRATION }
                }
                """
            ),
            // Test class - field with proper @Qualifier annotation should be marked
            java(
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.ApplicationEventPublisher;
                import org.example.qualifier.Priority;

                public class PriorityService {
                    @Autowired
                    @Priority
                    private ApplicationEventPublisher priorityEvents;
                }
                """,
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.context.ApplicationEventPublisher;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.example.qualifier.Priority;

                public class PriorityService {
                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "CDI Qualifier @Priority is ignored by Spring ApplicationEventPublisher. Recommended: Create wrapper event class (e.g., PriorityEvent extends BaseEvent) and use publishEvent(new PriorityEvent(payload)). Alternative: Add type field to event and filter with @EventListener(condition=\\"#event.type == 'priority'\\").")
                    @Autowired
                    @Priority
                    private ApplicationEventPublisher priorityEvents;
                }
                """
            )
        );
    }
}
