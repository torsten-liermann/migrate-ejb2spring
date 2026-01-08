package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.test.SourceSpecs.text;

/**
 * Tests for MigrateJmsDestination recipe.
 * <p>
 * IMPORTANT: This recipe keeps @NeedsReview annotations on JMS Queue/Topic fields
 * unless a JMS provider is configured in project.yaml. With a provider configured,
 * it generates provider-specific Queue beans and removes @NeedsReview.
 */
class MigrateJmsDestinationTest implements RewriteTest {

    private static final String NEEDS_REVIEW_STUB = """
        package com.github.rewrite.ejb.annotations;
        import java.lang.annotation.*;
        @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
        @Retention(RetentionPolicy.SOURCE)
        public @interface NeedsReview {
            String reason() default "";
            String originalCode() default "";
            String action() default "";
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateJmsDestination())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jms-api", "spring-beans", "spring-context")
                .dependsOn(NEEDS_REVIEW_STUB));
    }

    @Test
    void noChangesWithoutNeedsReview() {
        // Queue fields without @NeedsReview should not trigger config generation
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.jms.Queue;
                import org.springframework.beans.factory.annotation.Autowired;

                public class Service {
                    @Autowired
                    private Queue queue;
                }
                """,
                spec -> spec.path("src/main/java/com/example/Service.java")
            )
        );
    }

    @DocumentExample
    @Test
    void keepNeedsReviewOnQueueField() {
        // Verify @NeedsReview is NOT removed from Queue fields
        // The recipe intentionally keeps @NeedsReview because lambda Queue beans
        // may not work with all JMS providers
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.jms.Queue;
                import org.springframework.beans.factory.annotation.Autowired;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class MessageService {
                    @NeedsReview(
                        reason = "JNDI lookup 'java:app/jms/MyQueue' needs Spring configuration",
                        originalCode = "@Resource(lookup='java:app/jms/MyQueue')"
                    )
                    @Autowired
                    private Queue myQueue;

                    public void send(String msg) {
                        // use myQueue
                    }
                }
                """,
                // The Java file should remain UNCHANGED (no changes to annotations)
                spec -> spec.path("src/main/java/com/example/MessageService.java")
            ),
            text(
                null,
                """
                package com.example;

                import jakarta.jms.Queue;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                /**
                 * JMS Queue bean definitions.
                 * <p>
                 * Auto-generated during EJB-to-Spring migration.
                 * <p>
                 * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                 * IMPORTANT: This is a TEMPLATE configuration that uses lambda-based Queue.
                 * The lambda Queue implementation MAY NOT WORK with all JMS providers!
                 * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                 * <p>
                 * TODO: Adjust Queue implementations for your JMS provider:
                 * - Apache Artemis: Use org.apache.activemq.artemis.jms.client.ActiveMQQueue("queueName")
                 * - ActiveMQ Classic: Use org.apache.activemq.command.ActiveMQQueue("queueName")
                 * - IBM MQ: Use com.ibm.mq.jms.MQQueue("queueName")
                 * <p>
                 * After verifying JMS configuration works, remove @NeedsReview from the
                 * Destination fields in the classes that use these destinations.
                 */
                @Configuration
                public class JmsConfiguration {

                    /**
                     * Queue bean for 'MyQueue'.
                     * <p>
                     * TODO: Replace lambda with provider-specific Queue implementation if needed.
                     * Example for Artemis: return new org.apache.activemq.artemis.jms.client.ActiveMQQueue("MyQueue");
                     */
                    @Bean
                    public Queue myQueue() {
                        // Lambda Queue - works with some JMS providers but may need adjustment
                        return () -> "MyQueue";
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/JmsConfiguration.java")
            )
        );
    }

    @Test
    void removesNeedsReviewWhenProviderConfigured(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws java.io.IOException {
        ProjectConfigurationLoader.clearCache();
        java.nio.file.Path projectDir = tempDir.resolve("jms-provider-project");
        java.nio.file.Files.createDirectories(projectDir.resolve("src/main/java/com/example"));
        java.nio.file.Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        java.nio.file.Files.writeString(projectDir.resolve("project.yaml"), """
                jms:
                  provider: artemis
                """);

        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.jms.Queue;
                import org.springframework.beans.factory.annotation.Autowired;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class MessageService {
                    @NeedsReview(
                        reason = "JNDI lookup 'java:app/jms/MyQueue' needs Spring configuration",
                        originalCode = "@Resource(lookup='java:app/jms/MyQueue')"
                    )
                    @Autowired
                    private Queue myQueue;
                }
                """,
                """
                package com.example;

                import jakarta.jms.Queue;
                import org.springframework.beans.factory.annotation.Autowired;

                public class MessageService {
                    @Autowired
                    private Queue myQueue;
                }
                """,
                spec -> spec.path(projectDir.resolve("src/main/java/com/example/MessageService.java").toString())
            ),
            text(
                null,
                """
                package com.example;

                import jakarta.jms.Queue;
                import org.apache.activemq.artemis.jms.client.ActiveMQQueue;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                /**
                 * JMS Queue bean definitions.
                 * <p>
                 * Auto-generated during EJB-to-Spring migration.
                 * <p>
                 * Provider-specific JMS implementation: artemis.
                 */
                @Configuration
                public class JmsConfiguration {

                    /**
                     * Queue bean for 'MyQueue'.
                     */
                    @Bean
                    public Queue myQueue() {
                        return new ActiveMQQueue("MyQueue");
                    }

                }
                """,
                spec -> spec.path(projectDir.resolve("src/main/java/com/example/JmsConfiguration.java").toString())
            )
        );
    }

    @Test
    void generatesTopicBeansForTopicFields() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.jms.Topic;
                import org.springframework.beans.factory.annotation.Autowired;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class TopicService {
                    @NeedsReview(
                        reason = "JNDI lookup 'java:app/jms/EventsTopic' needs Spring configuration",
                        originalCode = "@Resource(lookup='java:app/jms/EventsTopic')"
                    )
                    @Autowired
                    private Topic eventsTopic;
                }
                """,
                spec -> spec.path("src/main/java/com/example/TopicService.java")
            ),
            text(
                null,
                """
                package com.example;

                import jakarta.jms.Topic;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                /**
                 * JMS Topic bean definitions.
                 * <p>
                 * Auto-generated during EJB-to-Spring migration.
                 * <p>
                 * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                 * IMPORTANT: This is a TEMPLATE configuration that uses lambda-based Topic.
                 * The lambda Topic implementation MAY NOT WORK with all JMS providers!
                 * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                 * <p>
                 * TODO: Adjust Topic implementations for your JMS provider:
                 * - Apache Artemis: Use org.apache.activemq.artemis.jms.client.ActiveMQTopic("topicName")
                 * - ActiveMQ Classic: Use org.apache.activemq.command.ActiveMQTopic("topicName")
                 * <p>
                 * After verifying JMS configuration works, remove @NeedsReview from the
                 * Destination fields in the classes that use these destinations.
                 */
                @Configuration
                public class JmsConfiguration {

                    /**
                     * Topic bean for 'EventsTopic'.
                     * <p>
                     * TODO: Replace lambda with provider-specific Topic implementation if needed.
                     * Example for Artemis: return new org.apache.activemq.artemis.jms.client.ActiveMQTopic("EventsTopic");
                     */
                    @Bean
                    public Topic eventsTopic() {
                        // Lambda Topic - works with some JMS providers but may need adjustment
                        return () -> "EventsTopic";
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/JmsConfiguration.java")
            )
        );
    }

    @Test
    void warnsOnJndiFallbackExtraction() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.jms.Queue;
                import org.springframework.beans.factory.annotation.Autowired;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class MessageService {
                    @NeedsReview(
                        reason = "JNDI lookup 'java:jboss/exported/queue/OrderQueue' needs Spring configuration",
                        originalCode = "@Resource(lookup='java:jboss/exported/queue/OrderQueue')"
                    )
                    @Autowired
                    private Queue orderQueue;
                }
                """,
                spec -> spec.path("src/main/java/com/example/MessageService.java")
            ),
            text(
                null,
                """
                package com.example;

                import jakarta.jms.Queue;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                /**
                 * JMS Queue bean definitions.
                 * <p>
                 * Auto-generated during EJB-to-Spring migration.
                 * <p>
                 * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                 * IMPORTANT: This is a TEMPLATE configuration that uses lambda-based Queue.
                 * The lambda Queue implementation MAY NOT WORK with all JMS providers!
                 * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                 * <p>
                 * TODO: Adjust Queue implementations for your JMS provider:
                 * - Apache Artemis: Use org.apache.activemq.artemis.jms.client.ActiveMQQueue("queueName")
                 * - ActiveMQ Classic: Use org.apache.activemq.command.ActiveMQQueue("queueName")
                 * - IBM MQ: Use com.ibm.mq.jms.MQQueue("queueName")
                 * <p>
                 * After verifying JMS configuration works, remove @NeedsReview from the
                 * Destination fields in the classes that use these destinations.
                 */
                @Configuration
                public class JmsConfiguration {

                    /**
                     * Queue bean for 'OrderQueue'.
                     * <p>
                     * TODO: Destination name derived from JNDI fallback (original: java:jboss/exported/queue/OrderQueue).
                     * Verify the physical destination name.
                     * <p>
                     * TODO: Replace lambda with provider-specific Queue implementation if needed.
                     * Example for Artemis: return new org.apache.activemq.artemis.jms.client.ActiveMQQueue("OrderQueue");
                     */
                    @Bean
                    public Queue orderQueue() {
                        // Lambda Queue - works with some JMS providers but may need adjustment
                        return () -> "OrderQueue";
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/JmsConfiguration.java")
            )
        );
    }

    @Test
    void appendsReasonForDestinationType() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.jms.Destination;
                import org.springframework.beans.factory.annotation.Autowired;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class DestinationService {
                    @NeedsReview(
                        reason = "JNDI lookup 'java:app/jms/LegacyDestination' needs Spring configuration",
                        originalCode = "@Resource(lookup='java:app/jms/LegacyDestination')"
                    )
                    @Autowired
                    private Destination legacyDestination;
                }
                """,
                """
                package com.example;

                import jakarta.jms.Destination;
                import org.springframework.beans.factory.annotation.Autowired;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class DestinationService {
                    @NeedsReview(
                        reason = "JNDI lookup 'java:app/jms/LegacyDestination' needs Spring configuration (Destination type: define Queue/Topic bean manually)",
                        originalCode = "@Resource(lookup='java:app/jms/LegacyDestination')"
                    )
                    @Autowired
                    private Destination legacyDestination;
                }
                """,
                spec -> spec.path("src/main/java/com/example/DestinationService.java")
            ),
            text(
                null,
                """
                package com.example;

                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                /**
                 * JMS Destination bean definitions.
                 * <p>
                 * Auto-generated during EJB-to-Spring migration.
                 * <p>
                 * TODO: Some Destination fields could not be classified as Queue/Topic.
                 * Verify these JNDI names and create matching beans manually.
                 */
                @Configuration
                public class JmsConfiguration {

                }
                """,
                spec -> spec.path("src/main/java/com/example/JmsConfiguration.java")
            )
        );
    }
}
