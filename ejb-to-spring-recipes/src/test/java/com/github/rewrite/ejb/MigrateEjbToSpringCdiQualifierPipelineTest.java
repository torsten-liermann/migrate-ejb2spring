package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.config.Environment;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateEjbToSpringCdiQualifierPipelineTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(Environment.builder()
                .scanRuntimeClasspath("com.github.rewrite.ejb")
                .build()
                .activateRecipes("com.github.rewrite.ejb.MigrateEjbToSpring"))
            .cycles(1)
            .expectedCyclesThatMakeChanges(1)
            .afterRecipe(recipeRun -> { });
    }

    @Test
    void defaultPipelineMarksCdiQualifierOnEvents() {
        String needsReview = """
            package com.github.rewrite.ejb.annotations;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
            public @interface NeedsReview {
                Category category();
                String reason() default "";
                String stableKey() default "";
                enum Category { CONFIGURATION, MANUAL_MIGRATION }
            }
            """;
        String cdiEvent = """
            package jakarta.enterprise.event;

            public interface Event<T> {
                void fire(T event);
            }
            """;
        String observes = """
            package jakarta.enterprise.event;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.PARAMETER)
            public @interface Observes {}
            """;
        String qualifier = """
            package jakarta.inject;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.ANNOTATION_TYPE)
            public @interface Qualifier {}
            """;
        String applicationEventPublisher = """
            package org.springframework.context;

            public interface ApplicationEventPublisher {
                void publishEvent(Object event);
            }
            """;
        String eventListener = """
            package org.springframework.context.event;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.METHOD)
            public @interface EventListener {}
            """;
        String cargoInspected = """
            package org.example.events.cdi;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import jakarta.inject.Qualifier;

            @Qualifier
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface CargoInspected {}
            """;
        String cargoArrived = """
            package org.example.events.cdi;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;
            import jakarta.inject.Qualifier;

            @Qualifier
            @Retention(RetentionPolicy.RUNTIME)
            @Target({ElementType.FIELD, ElementType.PARAMETER})
            public @interface CargoArrived {}
            """;
        String cargoEvent = """
            package org.example.events;

            public class CargoEvent {}
            """;
        String springBootApplication = """
            package org.springframework.boot.autoconfigure;

            import java.lang.annotation.ElementType;
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.lang.annotation.Target;

            @Retention(RetentionPolicy.RUNTIME)
            @Target(ElementType.TYPE)
            public @interface SpringBootApplication {}
            """;

        rewriteRun(
            // NeedsReview stub to avoid generating it during the run
            java(needsReview, spec -> spec.path("src/main/java/com/github/rewrite/ejb/annotations/NeedsReview.java")),
            // CDI Event/Observes stubs
            java(cdiEvent),
            java(observes),
            java(qualifier),
            // Spring stubs
            java(applicationEventPublisher),
            java(eventListener),
            java(springBootApplication),
            // Custom qualifiers + event
            java(cargoInspected),
            java(cargoArrived),
            java(cargoEvent),
            // Existing Spring Boot application to prevent AddSpringBootApplication output
            java(
                """
                package com.example;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class ExistingApplication {}
                """,
                spec -> {}
            ),
            // Publisher: Event<T> with qualifier
            java(
                """
                package org.example.publisher;

                import jakarta.enterprise.event.Event;
                import org.example.events.CargoEvent;
                import org.example.events.cdi.CargoInspected;

                public class CargoPublisher {
                    @CargoInspected
                    private Event<CargoEvent> events;

                    public void fire(CargoEvent event) {
                        events.fire(event);
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("ApplicationEventPublisher")
                        .contains("@NeedsReview")
                        .contains("CDI Qualifier @CargoInspected")
                        .contains("@CargoInspected")
                        .contains("publishEvent");
                    return actual;
                })
            ),
            // Listener: @Observes + qualifier
            java(
                """
                package org.example.listener;

                import jakarta.enterprise.event.Observes;
                import org.example.events.CargoEvent;
                import org.example.events.cdi.CargoArrived;

                public class CargoListener {
                    public void onCargo(@Observes @CargoArrived CargoEvent event) {
                    }
                }
                """,
                spec -> spec.after(actual -> {
                    org.assertj.core.api.Assertions.assertThat(actual)
                        .contains("@EventListener")
                        .contains("@NeedsReview")
                        .contains("CDI Qualifier @CargoArrived")
                        .contains("@CargoArrived")
                        .doesNotContain("@Observes");
                    return actual;
                })
            )
        );
    }
}
