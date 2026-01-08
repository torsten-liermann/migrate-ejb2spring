package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateJmsContextToJmsTemplateTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateJmsContextToJmsTemplate())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-jms"));
    }

    @DocumentExample
    @Test
    void replacesCreateProducerSendWithJmsTemplate() {
        rewriteRun(
            java(
                """
                package org.example;

                import jakarta.inject.Inject;
                import jakarta.jms.JMSContext;
                import jakarta.jms.Queue;

                public class MessageSender {
                    @Inject
                    JMSContext context;

                    public void send(Queue queue, String payload) {
                        context.createProducer().send(queue, payload);
                    }
                }
                """,
                """
                package org.example;

                import jakarta.inject.Inject;
                import jakarta.jms.Queue;
                import org.springframework.jms.core.JmsTemplate;

                public class MessageSender {
                    @Inject
                    JmsTemplate context;

                    public void send(Queue queue, String payload) {
                        context.convertAndSend(queue, payload);
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsJmsContextAndAddsNeedsReviewWhenUnsupportedUsageExists() {
        rewriteRun(
            java(
                """
                package org.example;

                import jakarta.inject.Inject;
                import jakarta.jms.JMSContext;
                import jakarta.jms.Queue;

                public class MessageSender {
                    @Inject
                    JMSContext context;

                    public void send(Queue queue, String payload) {
                        context.createProducer().send(queue, payload);
                        context.createConsumer(queue);
                    }
                }
                """,
                """
                package org.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.inject.Inject;
                import jakarta.jms.JMSContext;
                import jakarta.jms.Queue;

                public class MessageSender {
                    @NeedsReview(reason = "JMSContext usage cannot be safely migrated; consider JmsTemplate or ConnectionFactory.createContext()", category = NeedsReview.Category.MANUAL_MIGRATION)
                    @Inject
                    JMSContext context;

                    public void send(Queue queue, String payload) {
                        context.createProducer().send(queue, payload);
                        context.createConsumer(queue);
                    }
                }
                """
            )
        );
    }
}
