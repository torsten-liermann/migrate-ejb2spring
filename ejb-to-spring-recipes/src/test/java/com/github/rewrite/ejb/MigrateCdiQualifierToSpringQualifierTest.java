package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for MigrateCdiQualifierToSpringQualifier recipe.
 */
class MigrateCdiQualifierToSpringQualifierTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateCdiQualifierToSpringQualifier())
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-beans", "spring-context"));
    }

    @Test
    @DocumentExample
    void convertsCustomCdiQualifierToSpringQualifier() {
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom CDI qualifier
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Premium {}
                """
            ),
            // Spring @Qualifier stub
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
                public @interface Qualifier {
                    String value() default "";
                }
                """
            ),
            // Test class
            java(
                """
                package org.example.service;

                import org.example.qualifier.Premium;

                public class OrderService {
                    @Premium
                    private PaymentService paymentService;
                }

                interface PaymentService {}
                """,
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Qualifier;

                public class OrderService {
                    @Qualifier("premium")
                    private PaymentService paymentService;
                }

                interface PaymentService {}
                """
            )
        );
    }

    @Test
    void convertsQualifierOnConstructorParameter() {
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom CDI qualifier
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface HighPriority {}
                """
            ),
            // Spring @Qualifier stub
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
                public @interface Qualifier {
                    String value() default "";
                }
                """
            ),
            // Test class with constructor parameter
            java(
                """
                package org.example.service;

                import org.example.qualifier.HighPriority;

                public class NotificationService {
                    private final MessageSender sender;

                    public NotificationService(@HighPriority MessageSender sender) {
                        this.sender = sender;
                    }
                }

                interface MessageSender {}
                """,
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Qualifier;

                public class NotificationService {
                    private final MessageSender sender;

                    public NotificationService(@Qualifier("highPriority") MessageSender sender) {
                        this.sender = sender;
                    }
                }

                interface MessageSender {}
                """
            )
        );
    }

    @Test
    void skipsApplicationEventPublisherField() {
        // ApplicationEventPublisher fields with qualifiers are handled by MarkCdiQualifiersForReview
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom CDI qualifier
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface CargoInspected {}
                """
            ),
            // ApplicationEventPublisher stub
            java(
                """
                package org.springframework.context;
                public interface ApplicationEventPublisher {
                    void publishEvent(Object event);
                }
                """
            ),
            // Test class - ApplicationEventPublisher with qualifier should NOT change
            java(
                """
                package org.example.service;

                import org.example.qualifier.CargoInspected;
                import org.springframework.context.ApplicationEventPublisher;

                public class CargoService {
                    @CargoInspected
                    private ApplicationEventPublisher events;
                }
                """
            )
        );
    }

    @Test
    void skipsCdiEventField() {
        // CDI Event<T> fields with qualifiers are handled by MarkCdiQualifiersForReview
        // (after MigrateCdiEventsToSpring converts Event<T> to ApplicationEventPublisher)
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // CDI Event type
            java(
                """
                package jakarta.enterprise.event;
                public interface Event<T> {
                    void fire(T event);
                }
                """
            ),
            // Custom CDI qualifier
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface CargoInspected {}
                """
            ),
            // Event payload
            java(
                """
                package org.example.events;
                public class CargoEvent {}
                """
            ),
            // Test class - CDI Event<T> with qualifier should NOT change
            java(
                """
                package org.example.service;

                import jakarta.enterprise.event.Event;
                import org.example.events.CargoEvent;
                import org.example.qualifier.CargoInspected;

                public class CargoService {
                    @CargoInspected
                    private Event<CargoEvent> events;
                }
                """
            )
        );
    }

    @Test
    void skipsNamedAnnotation() {
        // @Named is handled by MigrateNamedToComponent
        rewriteRun(
            // jakarta.inject.Named stub
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
                public @interface Named {
                    String value() default "";
                }
                """
            ),
            // Test class - @Named should NOT be processed by this recipe
            java(
                """
                package org.example.service;

                import jakarta.inject.Named;

                public class UserService {
                    @Named("adminUser")
                    private UserRepository repository;
                }

                interface UserRepository {}
                """
            )
        );
    }

    @Test
    void skipsFieldWithExistingSpringQualifier() {
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom CDI qualifier
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Legacy {}
                """
            ),
            // Spring @Qualifier stub
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
                public @interface Qualifier {
                    String value() default "";
                }
                """
            ),
            // Test class - already has Spring @Qualifier, should not add another
            java(
                """
                package org.example.service;

                import org.example.qualifier.Legacy;
                import org.springframework.beans.factory.annotation.Qualifier;

                public class MixedService {
                    @Legacy
                    @Qualifier("existing")
                    private DataSource dataSource;
                }

                interface DataSource {}
                """
            )
        );
    }

    @Test
    void convertsMultipleQualifiersOnDifferentFields() {
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom CDI qualifiers
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Fast {}
                """
            ),
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Reliable {}
                """
            ),
            // Spring @Qualifier stub
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
                public @interface Qualifier {
                    String value() default "";
                }
                """
            ),
            // Test class with multiple qualified fields
            java(
                """
                package org.example.service;

                import org.example.qualifier.Fast;
                import org.example.qualifier.Reliable;

                public class ProcessorService {
                    @Fast
                    private Processor fastProcessor;

                    @Reliable
                    private Processor reliableProcessor;
                }

                interface Processor {}
                """,
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Qualifier;

                public class ProcessorService {
                    @Qualifier("fast")
                    private Processor fastProcessor;

                    @Qualifier("reliable")
                    private Processor reliableProcessor;
                }

                interface Processor {}
                """
            )
        );
    }

    @Test
    void handlesJavaxQualifierNamespace() {
        rewriteRun(
            // javax.inject.Qualifier meta-annotation (pre-Jakarta)
            java(
                """
                package javax.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom CDI qualifier using javax
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import javax.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Enterprise {}
                """
            ),
            // Spring @Qualifier stub
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
                public @interface Qualifier {
                    String value() default "";
                }
                """
            ),
            // Test class
            java(
                """
                package org.example.service;

                import org.example.qualifier.Enterprise;

                public class LegacyService {
                    @Enterprise
                    private Gateway gateway;
                }

                interface Gateway {}
                """,
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Qualifier;

                public class LegacyService {
                    @Qualifier("enterprise")
                    private Gateway gateway;
                }

                interface Gateway {}
                """
            )
        );
    }

    @Test
    void skipsObservesParameter() {
        // @Observes parameters are CDI event observers - qualifiers should NOT be converted
        // (handled by MarkCdiQualifiersForReview after MigrateCdiEventsToSpring)
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // CDI Observes annotation
            java(
                """
                package jakarta.enterprise.event;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.PARAMETER)
                public @interface Observes {}
                """
            ),
            // Custom CDI qualifier
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface CargoArrived {}
                """
            ),
            // Event payload
            java(
                """
                package org.example.events;
                public class CargoEvent {}
                """
            ),
            // Test class - @Observes parameter with qualifier should NOT change
            java(
                """
                package org.example.listener;

                import jakarta.enterprise.event.Observes;
                import org.example.events.CargoEvent;
                import org.example.qualifier.CargoArrived;

                public class CargoListener {
                    public void onCargo(@Observes @CargoArrived CargoEvent event) {
                    }
                }
                """
            )
        );
    }

    @Test
    void skipsNonQualifierAnnotations() {
        // Annotations without @Qualifier meta-annotation should not be processed
        rewriteRun(
            // Custom annotation WITHOUT @Qualifier meta-annotation
            java(
                """
                package org.example.annotation;

                import java.lang.annotation.*;

                // This is NOT a CDI qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Audited {}
                """
            ),
            // Test class - @Audited should NOT be converted (not a CDI qualifier)
            java(
                """
                package org.example.service;

                import org.example.annotation.Audited;

                public class AuditService {
                    @Audited
                    private DataStore dataStore;
                }

                interface DataStore {}
                """
            )
        );
    }

    @Test
    void convertsClassLevelQualifier() {
        // Class-level CDI qualifiers (bean definitions) should be converted
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom CDI qualifier
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER})
                public @interface Premium {}
                """
            ),
            // Spring @Qualifier stub
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
                public @interface Qualifier {
                    String value() default "";
                }
                """
            ),
            // Component annotation stub
            java(
                """
                package org.springframework.stereotype;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.TYPE)
                public @interface Service {}
                """
            ),
            // Bean class with CDI qualifier - should be converted
            java(
                """
                package org.example.service;

                import org.springframework.stereotype.Service;
                import org.example.qualifier.Premium;

                @Service
                @Premium
                public class PremiumPaymentService implements PaymentService {
                }

                interface PaymentService {}
                """,
                """
                package org.example.service;

                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.stereotype.Service;

                @Service
                @Qualifier("premium")
                public class PremiumPaymentService implements PaymentService {
                }

                interface PaymentService {}
                """
            )
        );
    }

    @Test
    void marksQualifierWithAttributesForReview() {
        // Qualifiers with attributes cannot preserve values - mark for review
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom CDI qualifier WITH attributes
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Region {
                    String value();
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
            // Test class - qualifier with attribute should get @NeedsReview
            java(
                """
                package org.example.service;

                import org.example.qualifier.Region;

                public class RegionalService {
                    @Region("EU")
                    private DataStore dataStore;
                }

                interface DataStore {}
                """,
                """
                package org.example.service;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.example.qualifier.Region;

                public class RegionalService {
                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "CDI Qualifier @Region has attributes that cannot be preserved in Spring @Qualifier. Original: @Region(\\"EU\\"). Consider custom Spring qualifier annotation or explicit bean naming.")
                    @Region("EU")
                    private DataStore dataStore;
                }

                interface DataStore {}
                """
            )
        );
    }

    @Test
    void convertsProducerMethodQualifier() {
        // Producer methods with CDI qualifiers (bean definition) should be converted
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // CDI Produces annotation
            java(
                """
                package jakarta.enterprise.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.METHOD, ElementType.FIELD})
                public @interface Produces {}
                """
            ),
            // Custom CDI qualifier
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
                public @interface Premium {}
                """
            ),
            // Spring @Qualifier stub
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
                public @interface Qualifier {
                    String value() default "";
                }
                """
            ),
            // Producer class with @Produces @Premium method
            java(
                """
                package org.example.config;

                import jakarta.enterprise.inject.Produces;
                import org.example.qualifier.Premium;

                public class PaymentConfig {
                    @Produces
                    @Premium
                    public PaymentService premiumPaymentService() {
                        return new PremiumPaymentServiceImpl();
                    }
                }

                interface PaymentService {}
                class PremiumPaymentServiceImpl implements PaymentService {}
                """,
                """
                package org.example.config;

                import jakarta.enterprise.inject.Produces;
                import org.springframework.beans.factory.annotation.Qualifier;

                public class PaymentConfig {
                    @Produces
                    @Qualifier("premium")
                    public PaymentService premiumPaymentService() {
                        return new PremiumPaymentServiceImpl();
                    }
                }

                interface PaymentService {}
                class PremiumPaymentServiceImpl implements PaymentService {}
                """
            )
        );
    }

    @Test
    void retainsImportWhenQualifierPartiallyConverted() {
        // When one field is converted and another retains CDI qualifier (multi-qualifier),
        // the import should remain
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom CDI qualifiers
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Premium {}
                """
            ),
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface VIP {}
                """
            ),
            // Spring @Qualifier stub
            java(
                """
                package org.springframework.beans.factory.annotation;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
                public @interface Qualifier {
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
            // Test class: one field is convertible (Premium only), another is not (Premium + VIP)
            // Premium import should REMAIN because it's still used on second field
            java(
                """
                package org.example.service;

                import org.example.qualifier.Premium;
                import org.example.qualifier.VIP;

                public class MixedService {
                    @Premium
                    private PaymentService premiumOnly;

                    @Premium @VIP
                    private PaymentService premiumAndVip;
                }

                interface PaymentService {}
                """,
                """
                package org.example.service;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.example.qualifier.Premium;
                import org.example.qualifier.VIP;
                import org.springframework.beans.factory.annotation.Qualifier;

                public class MixedService {
                    @Qualifier("premium")
                    private PaymentService premiumOnly;

                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "Multiple CDI qualifiers on injection point. Spring @Qualifier cannot represent qualifier intersection. Consider explicit bean naming or custom Spring qualifier.")
                    @Premium
                    @VIP
                    private PaymentService premiumAndVip;
                }

                interface PaymentService {}
                """
            )
        );
    }

    @Test
    void marksMultipleQualifiersForReview() {
        // Multiple CDI qualifiers on same injection point - Spring cannot represent intersection
        rewriteRun(
            // CDI Qualifier meta-annotation
            java(
                """
                package jakarta.inject;
                import java.lang.annotation.*;
                @Retention(RetentionPolicy.RUNTIME)
                @Target(ElementType.ANNOTATION_TYPE)
                public @interface Qualifier {}
                """
            ),
            // Custom CDI qualifiers
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Fast {}
                """
            ),
            java(
                """
                package org.example.qualifier;

                import java.lang.annotation.*;
                import jakarta.inject.Qualifier;

                @Qualifier
                @Retention(RetentionPolicy.RUNTIME)
                @Target({ElementType.FIELD, ElementType.PARAMETER})
                public @interface Reliable {}
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
            // Test class - two qualifiers on one field should get @NeedsReview
            java(
                """
                package org.example.service;

                import org.example.qualifier.Fast;
                import org.example.qualifier.Reliable;

                public class ProcessorService {
                    @Fast @Reliable
                    private Processor processor;
                }

                interface Processor {}
                """,
                """
                package org.example.service;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.example.qualifier.Fast;
                import org.example.qualifier.Reliable;

                public class ProcessorService {
                    @NeedsReview(category = NeedsReview.Category.MANUAL_MIGRATION, reason = "Multiple CDI qualifiers on injection point. Spring @Qualifier cannot represent qualifier intersection. Consider explicit bean naming or custom Spring qualifier.")
                    @Fast
                    @Reliable
                    private Processor processor;
                }

                interface Processor {}
                """
            )
        );
    }
}
