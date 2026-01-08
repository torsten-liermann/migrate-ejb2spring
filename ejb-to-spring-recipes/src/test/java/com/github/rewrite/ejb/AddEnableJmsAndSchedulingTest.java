package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class AddEnableJmsAndSchedulingTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddEnableJmsAndScheduling())
            .parser(JavaParser.fromJavaVersion()
                .classpath("spring-jms", "spring-context", "jakarta.jms-api", "spring-boot-autoconfigure"));
    }

    @DocumentExample
    @Test
    void addEnableJmsToExistingConfiguration() {
        rewriteRun(
            // Configuration class
            java(
                """
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class AppConfig {
                }
                """,
                """
                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.EnableJms;

                @Configuration
                @EnableJms
                public class AppConfig {
                }
                """
            ),
            // Class using @JmsListener
            java(
                """
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class MessageConsumer {
                    @JmsListener(destination = "myQueue")
                    public void onMessage(Message message) {
                    }
                }
                """
            )
        );
    }

    @Test
    void addEnableSchedulingToExistingConfiguration() {
        rewriteRun(
            // Configuration class
            java(
                """
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class AppConfig {
                }
                """,
                """
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.annotation.EnableScheduling;

                @Configuration
                @EnableScheduling
                public class AppConfig {
                }
                """
            ),
            // Class using @Scheduled
            java(
                """
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class ScheduledTask {
                    @Scheduled(cron = "0 0 * * * *")
                    public void runHourly() {
                    }
                }
                """
            )
        );
    }

    @Test
    void addBothEnableJmsAndEnableScheduling() {
        rewriteRun(
            // Configuration class
            java(
                """
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class AppConfig {
                }
                """,
                """
                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.EnableJms;
                import org.springframework.scheduling.annotation.EnableScheduling;

                @Configuration
                @EnableJms
                @EnableScheduling
                public class AppConfig {
                }
                """
            ),
            // Class using @JmsListener
            java(
                """
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class MessageConsumer {
                    @JmsListener(destination = "myQueue")
                    public void onMessage(Message message) {
                    }
                }
                """
            ),
            // Class using @Scheduled
            java(
                """
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class ScheduledTask {
                    @Scheduled(cron = "0 0 * * * *")
                    public void runHourly() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotAddIfAlreadyPresent() {
        rewriteRun(
            // Configuration class already has @EnableJms
            java(
                """
                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.EnableJms;

                @Configuration
                @EnableJms
                public class AppConfig {
                }
                """
            ),
            // Class using @JmsListener
            java(
                """
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class MessageConsumer {
                    @JmsListener(destination = "myQueue")
                    public void onMessage(Message message) {
                    }
                }
                """
            )
        );
    }

    @Test
    void generatesConfigurationWhenNoConfigurationClassExists() {
        rewriteRun(
            // Only a @Component class using @JmsListener, no @Configuration
            java(
                """
                package com.example;

                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class MessageConsumer {
                    @JmsListener(destination = "myQueue")
                    public void onMessage(Message message) {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/MessageConsumer.java")
            ),
            java(
                null,
                """
                package com.example;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.EnableJms;

                @Configuration
                @EnableJms
                public class MigrationConfiguration {
                }
                """,
                spec -> spec.path("src/main/java/com/example/MigrationConfiguration.java")
            )
        );
    }

    @Test
    void addEnableAsyncToExistingConfiguration() {
        rewriteRun(
            // Configuration class
            java(
                """
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class AppConfig {
                }
                """,
                """
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.annotation.EnableAsync;

                @Configuration
                @EnableAsync
                public class AppConfig {
                }
                """
            ),
            // Class using @Async
            java(
                """
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;

                @Service
                public class EmailService {
                    @Async
                    public void sendEmail(String to, String message) {
                        // send email asynchronously
                    }
                }
                """
            )
        );
    }

    @Test
    void addAllThreeAnnotations() {
        rewriteRun(
            // Configuration class
            java(
                """
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class AppConfig {
                }
                """,
                """
                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.EnableJms;
                import org.springframework.scheduling.annotation.EnableAsync;
                import org.springframework.scheduling.annotation.EnableScheduling;

                @Configuration
                @EnableJms
                @EnableScheduling
                @EnableAsync
                public class AppConfig {
                }
                """
            ),
            // Class using @JmsListener
            java(
                """
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class MessageConsumer {
                    @JmsListener(destination = "myQueue")
                    public void onMessage(Message message) {
                    }
                }
                """
            ),
            // Class using @Scheduled
            java(
                """
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class ScheduledTask {
                    @Scheduled(cron = "0 0 * * * *")
                    public void runHourly() {
                    }
                }
                """
            ),
            // Class using @Async
            java(
                """
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;

                @Service
                public class AsyncProcessor {
                    @Async
                    public void processAsync(String data) {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotAddEnableAsyncIfAlreadyPresent() {
        rewriteRun(
            // Configuration class already has @EnableAsync
            java(
                """
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.annotation.EnableAsync;

                @Configuration
                @EnableAsync
                public class AppConfig {
                }
                """
            ),
            // Class using @Async
            java(
                """
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;

                @Service
                public class AsyncService {
                    @Async
                    public void doWork() {
                    }
                }
                """
            )
        );
    }

    @Test
    void generatesConfigurationWhenNoPackageDeclarationsExist() {
        rewriteRun(
            spec -> spec.typeValidationOptions(TypeValidation.none()),
            java(
                """
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;

                @Service
                public class AsyncService {
                    @Async
                    public void processAsync(String data) {
                    }
                }
                """,
                spec -> spec.path("src/main/java/AsyncService.java")
            ),
            java(
                null,
                """
                import org.springframework.context.annotation.Configuration;
                import org.springframework.scheduling.annotation.EnableAsync;

                @Configuration
                @EnableAsync
                public class MigrationConfiguration {
                }
                """,
                spec -> spec.path("src/main/java/MigrationConfiguration.java")
            )
        );
    }

    @Test
    void generatesConfigurationWithMultipleEnables() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class MessageConsumer {
                    @JmsListener(destination = "myQueue")
                    public void onMessage(Message message) {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/MessageConsumer.java")
            ),
            java(
                """
                package com.example;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class ScheduledTask {
                    @Scheduled(cron = "0 0 * * * *")
                    public void runHourly() {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/ScheduledTask.java")
            ),
            java(
                null,
                """
                package com.example;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.EnableJms;
                import org.springframework.scheduling.annotation.EnableScheduling;

                @Configuration
                @EnableJms
                @EnableScheduling
                public class MigrationConfiguration {
                }
                """,
                spec -> spec.path("src/main/java/com/example/MigrationConfiguration.java")
            )
        );
    }

    @Test
    void generatesConfigurationWithNameCollision() {
        rewriteRun(
            java(
                """
                package com.example;

                public class MigrationConfiguration {
                }
                """,
                spec -> spec.path("src/main/java/com/example/MigrationConfiguration.java")
            ),
            java(
                """
                package com.example;

                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class MessageConsumer {
                    @JmsListener(destination = "myQueue")
                    public void onMessage(Message message) {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/MessageConsumer.java")
            ),
            java(
                null,
                """
                package com.example;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.EnableJms;

                @Configuration
                @EnableJms
                public class MigrationConfiguration2 {
                }
                """,
                spec -> spec.path("src/main/java/com/example/MigrationConfiguration2.java")
            )
        );
    }

    @Test
    void generatesConfigurationUsingCommonPackagePrefix() {
        rewriteRun(
            java(
                """
                package com.acme.feature.api;

                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class ApiListener {
                    @JmsListener(destination = "myQueue")
                    public void onMessage(Message message) {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/acme/feature/api/ApiListener.java")
            ),
            java(
                """
                package com.acme.feature.impl;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class ImplTask {
                    @Scheduled(cron = "0 0 * * * *")
                    public void runHourly() {
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/acme/feature/impl/ImplTask.java")
            ),
            java(
                null,
                """
                package com.acme.feature;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.EnableJms;
                import org.springframework.scheduling.annotation.EnableScheduling;

                @Configuration
                @EnableJms
                @EnableScheduling
                public class MigrationConfiguration {
                }
                """,
                spec -> spec.path("src/main/java/com/acme/feature/MigrationConfiguration.java")
            )
        );
    }

    @Test
    void recognizesSpringBootApplicationAsConfigurationClass() {
        // @SpringBootApplication is a meta-annotation that includes @Configuration
        // The recipe should add @EnableJms/@EnableScheduling to it
        rewriteRun(
            // SpringBootApplication class
            java(
                """
                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class MyApplication {
                    public static void main(String[] args) {
                    }
                }
                """,
                """
                import org.springframework.boot.autoconfigure.SpringBootApplication;
                import org.springframework.jms.annotation.EnableJms;
                import org.springframework.scheduling.annotation.EnableScheduling;

                @SpringBootApplication
                @EnableJms
                @EnableScheduling
                public class MyApplication {
                    public static void main(String[] args) {
                    }
                }
                """
            ),
            // Class using @JmsListener
            java(
                """
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @Component
                public class MessageConsumer {
                    @JmsListener(destination = "myQueue")
                    public void onMessage(Message message) {
                    }
                }
                """
            ),
            // Class using @Scheduled
            java(
                """
                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                public class ScheduledTask {
                    @Scheduled(cron = "0 0 * * * *")
                    public void runHourly() {
                    }
                }
                """
            )
        );
    }
}
