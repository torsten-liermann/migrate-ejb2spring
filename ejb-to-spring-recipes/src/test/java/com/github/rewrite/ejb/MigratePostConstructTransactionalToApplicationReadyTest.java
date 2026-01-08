package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigratePostConstructTransactionalToApplicationReadyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigratePostConstructTransactionalToApplicationReady())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.annotation-api", "spring-context", "spring-tx", "spring-boot"));
    }

    @Test
    void replacesPostConstructWhenTransactionalPresent() {
        rewriteRun(
            java(
                """
                import jakarta.annotation.PostConstruct;
                import org.springframework.stereotype.Component;
                import org.springframework.transaction.annotation.Transactional;

                @Component
                public class BookingServiceTestDataGenerator {
                    @PostConstruct
                    @Transactional
                    public void loadSampleData() {
                    }
                }
                """,
                """
                import org.springframework.boot.context.event.ApplicationReadyEvent;
                import org.springframework.context.event.EventListener;
                import org.springframework.stereotype.Component;
                import org.springframework.transaction.annotation.Transactional;

                @Component
                public class BookingServiceTestDataGenerator {
                    @EventListener(ApplicationReadyEvent.class)
                    @Transactional
                    public void loadSampleData() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotChangeWithoutTransactional() {
        rewriteRun(
            java(
                """
                import jakarta.annotation.PostConstruct;
                import org.springframework.stereotype.Component;

                @Component
                public class SampleInitializer {
                    @PostConstruct
                    public void init() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotChangeWhenEventListenerAlreadyPresent() {
        rewriteRun(
            java(
                """
                import jakarta.annotation.PostConstruct;
                import org.springframework.boot.context.event.ApplicationReadyEvent;
                import org.springframework.context.event.EventListener;
                import org.springframework.stereotype.Component;
                import org.springframework.transaction.annotation.Transactional;

                @Component
                public class SampleInitializer {
                    @PostConstruct
                    @EventListener(ApplicationReadyEvent.class)
                    @Transactional
                    public void init() {
                    }
                }
                """
            )
        );
    }
}
