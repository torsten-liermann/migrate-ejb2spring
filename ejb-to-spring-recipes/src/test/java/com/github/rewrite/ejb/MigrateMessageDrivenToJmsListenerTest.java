package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateMessageDrivenToJmsListenerTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateMessageDrivenToJmsListener())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-jms", "spring-context"));
    }

    @DocumentExample
    @Test
    void migrateSimpleMessageDrivenBean() {
        rewriteRun(
            // Provide the NeedsReview annotation stub for broker config review
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ActivationConfigProperty;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                                              propertyValue = "java:app/jms/CargoHandledQueue")
                })
                public class CargoHandledConsumer implements MessageListener {
                    @Override
                    public void onMessage(Message message) {
                        // handle message
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)", category = NeedsReview.Category.MESSAGING, originalCode = "@MessageDriven", suggestedAction = "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection")
                @Component
                public class CargoHandledConsumer {
                    @JmsListener(destination = "CargoHandledQueue")
                    public void onMessage(Message message) {
                        // handle message
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateWithMessageSelector() {
        rewriteRun(
            // Provide the NeedsReview annotation stub
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ActivationConfigProperty;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                                              propertyValue = "java:app/jms/OrderQueue"),
                    @ActivationConfigProperty(propertyName = "messageSelector",
                                              propertyValue = "type = 'ORDER'")
                })
                public class OrderConsumer implements MessageListener {
                    @Override
                    public void onMessage(Message message) {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)", category = NeedsReview.Category.MESSAGING, originalCode = "@MessageDriven", suggestedAction = "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection")
                @Component
                public class OrderConsumer {
                    @JmsListener(destination = "OrderQueue", selector = "type = 'ORDER'")
                    public void onMessage(Message message) {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateWithConcurrency() {
        rewriteRun(
            // Provide the NeedsReview annotation stub
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ActivationConfigProperty;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                                              propertyValue = "java:app/jms/TaskQueue"),
                    @ActivationConfigProperty(propertyName = "maxSession",
                                              propertyValue = "5")
                })
                public class TaskConsumer implements MessageListener {
                    @Override
                    public void onMessage(Message message) {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)", category = NeedsReview.Category.MESSAGING, originalCode = "@MessageDriven", suggestedAction = "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection")
                @Component
                public class TaskConsumer {
                    @JmsListener(destination = "TaskQueue", concurrency = "5")
                    public void onMessage(Message message) {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateWithDestinationTypeTopic() {
        rewriteRun(
            // Provide the NeedsReview annotation stub
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ActivationConfigProperty;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                                              propertyValue = "java:app/jms/OrdersTopic"),
                    @ActivationConfigProperty(propertyName = "destinationType",
                                              propertyValue = "jakarta.jms.Topic")
                })
                public class OrderTopicConsumer implements MessageListener {
                    @Override
                    public void onMessage(Message message) {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.jms.ConnectionFactory;
                import jakarta.jms.Message;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)",  category = NeedsReview.Category.MESSAGING,  originalCode = "@MessageDriven",  suggestedAction = "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection")
                @Component
                public class OrderTopicConsumer {
                    @JmsListener(destination = "OrdersTopic", containerFactory = "orderTopicConsumerJmsListenerContainerFactory")
                    public void onMessage(Message message) {
                    }

                    @Configuration
                    static class OrderTopicConsumerJmsListenerConfiguration {

                        @Bean
                        public DefaultJmsListenerContainerFactory orderTopicConsumerJmsListenerContainerFactory(ConnectionFactory connectionFactory) {
                            DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
                            factory.setConnectionFactory(connectionFactory);
                            factory.setPubSubDomain(true);
                            return factory;
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void generatesNeedsReviewForUnknownDestinationType() {
        rewriteRun(
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ActivationConfigProperty;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                                              propertyValue = "java:app/jms/UnknownQueue"),
                    @ActivationConfigProperty(propertyName = "destinationType",
                                              propertyValue = "com.example.CustomDestination")
                })
                public class UnknownDestinationConsumer implements MessageListener {
                    @Override
                    public void onMessage(Message message) {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)", category = NeedsReview.Category.MESSAGING, originalCode = "@MessageDriven", suggestedAction = "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection")
                @Component
                public class UnknownDestinationConsumer {
                    @NeedsReview(reason = "MDB configuration needs review: destinationType=com.example.CustomDestination (cannot determine queue vs topic)", category = NeedsReview.Category.MESSAGING, originalCode = "@MessageDriven", suggestedAction = "Configure JMS container factory or connection settings")
                    @JmsListener(destination = "UnknownQueue")
                    public void onMessage(Message message) {
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesOtherAnnotations() {
        rewriteRun(
            // Provide the NeedsReview annotation stub
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            // Provide stub for SomeService to avoid missing type error
            java(
                """
                public interface SomeService {
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ActivationConfigProperty;
                import jakarta.ejb.MessageDriven;
                import jakarta.inject.Inject;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                                              propertyValue = "java:app/jms/TestQueue")
                })
                public class TestConsumer implements MessageListener {

                    @Inject
                    private SomeService service;

                    @Override
                    public void onMessage(Message message) {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.inject.Inject;
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)", category = NeedsReview.Category.MESSAGING, originalCode = "@MessageDriven", suggestedAction = "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection")
                @Component
                public class TestConsumer {

                    @Inject
                    private SomeService service;

                    @JmsListener(destination = "TestQueue")
                    public void onMessage(Message message) {
                    }
                }
                """
            )
        );
    }

    @Test
    void generatesNeedsReviewForUnmappedAcknowledgeMode() {
        // Test that acknowledgeMode generates @NeedsReview since it can't be mapped directly
        rewriteRun(
                        // Provide the NeedsReview annotation stub
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ActivationConfigProperty;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                                              propertyValue = "java:app/jms/AckQueue"),
                    @ActivationConfigProperty(propertyName = "acknowledgeMode",
                                              propertyValue = "Auto-acknowledge")
                })
                public class AckConsumer implements MessageListener {
                    @Override
                    public void onMessage(Message message) {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.jms.ConnectionFactory;
                import jakarta.jms.Message;
                import jakarta.jms.Session;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)",  category = NeedsReview.Category.MESSAGING,  originalCode = "@MessageDriven",  suggestedAction = "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection")
                @Component
                public class AckConsumer {
                    @JmsListener(destination = "AckQueue", containerFactory = "ackConsumerJmsListenerContainerFactory")
                    public void onMessage(Message message) {
                    }

                    @Configuration
                    static class AckConsumerJmsListenerConfiguration {

                        @Bean
                        public DefaultJmsListenerContainerFactory ackConsumerJmsListenerContainerFactory(ConnectionFactory connectionFactory) {
                            DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
                            factory.setConnectionFactory(connectionFactory);
                            factory.setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE);
                            return factory;
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void generatesNeedsReviewForConnectionFactory() {
        // Test that connectionFactoryLookup generates @NeedsReview
        rewriteRun(
                        // Provide the NeedsReview annotation stub
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ActivationConfigProperty;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                                              propertyValue = "java:app/jms/XaQueue"),
                    @ActivationConfigProperty(propertyName = "connectionFactoryLookup",
                                              propertyValue = "java:/JmsXA")
                })
                public class XaConsumer implements MessageListener {
                    @Override
                    public void onMessage(Message message) {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)", category = NeedsReview.Category.MESSAGING, originalCode = "@MessageDriven", suggestedAction = "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection")
                @Component
                public class XaConsumer {
                    @NeedsReview(reason = "MDB configuration needs review: connectionFactory=java:/JmsXA (configure via containerFactory parameter or JmsListenerContainerFactory bean)", category = NeedsReview.Category.MESSAGING, originalCode = "@MessageDriven", suggestedAction = "Configure JMS container factory or connection settings")
                    @JmsListener(destination = "XaQueue")
                    public void onMessage(Message message) {
                    }
                }
                """
            )
        );
    }

    @Test
    void generatesNeedsReviewForDurableSubscriptionWithoutName() {
        // Durable subscriptions require a stable subscription name in Spring
        rewriteRun(
                        // Provide the NeedsReview annotation stub
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ActivationConfigProperty;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                                              propertyValue = "java:app/jms/DurableQueue"),
                    @ActivationConfigProperty(propertyName = "subscriptionDurability",
                                              propertyValue = "Durable")
                })
                public class DurableConsumer implements MessageListener {
                    @Override
                    public void onMessage(Message message) {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.jms.Message;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)", category = NeedsReview.Category.MESSAGING, originalCode = "@MessageDriven", suggestedAction = "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection")
                @Component
                public class DurableConsumer {
                    @NeedsReview(reason = "MDB configuration needs review: subscriptionDurability=Durable requires subscription parameter (add subscriptionName to @JmsListener)", category = NeedsReview.Category.MESSAGING, originalCode = "@MessageDriven", suggestedAction = "Configure JMS container factory or connection settings")
                    @JmsListener(destination = "DurableQueue")
                    public void onMessage(Message message) {
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesJavadocWhenAddingNeedsReview() {
        // Test that existing Javadoc is preserved when @NeedsReview is added
        rewriteRun(
                        // Provide the NeedsReview annotation stub
            java(
                """
                package com.github.rewrite.ejb.annotations;
                import java.lang.annotation.*;
                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                public @interface NeedsReview {
                    String reason();
                    Category category();
                    String originalCode() default "";
                    String suggestedAction() default "";
                    enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ActivationConfigProperty;
                import jakarta.ejb.MessageDriven;
                import jakarta.jms.Message;
                import jakarta.jms.MessageListener;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                                              propertyValue = "java:app/jms/DocQueue"),
                    @ActivationConfigProperty(propertyName = "acknowledgeMode",
                                              propertyValue = "Auto-acknowledge")
                })
                public class DocumentedConsumer implements MessageListener {
                    /**
                     * Handles incoming messages from the queue.
                     * @param message the JMS message to process
                     */
                    @Override
                    public void onMessage(Message message) {
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.jms.ConnectionFactory;
                import jakarta.jms.Message;
                import jakarta.jms.Session;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.jms.annotation.JmsListener;
                import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
                import org.springframework.stereotype.Component;

                @NeedsReview(reason = "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)",  category = NeedsReview.Category.MESSAGING,  originalCode = "@MessageDriven",  suggestedAction = "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection")
                @Component
                public class DocumentedConsumer {
                    /**
                     * Handles incoming messages from the queue.
                     * @param message the JMS message to process
                     */
                    @JmsListener(destination = "DocQueue", containerFactory = "documentedConsumerJmsListenerContainerFactory")
                    public void onMessage(Message message) {
                    }

                    @Configuration
                    static class DocumentedConsumerJmsListenerConfiguration {

                        @Bean
                        public DefaultJmsListenerContainerFactory documentedConsumerJmsListenerContainerFactory(ConnectionFactory connectionFactory) {
                            DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();
                            factory.setConnectionFactory(connectionFactory);
                            factory.setSessionAcknowledgeMode(Session.AUTO_ACKNOWLEDGE);
                            return factory;
                        }
                    }
                }
                """
            )
        );
    }
}
