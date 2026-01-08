package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateAsynchronousToAsyncTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAsynchronousToAsync())
            .parser(JavaParser.fromJavaVersion()
                .classpath("javax.ejb-api", "jakarta.jakartaee-api", "spring-context"));
    }

    @DocumentExample
    @Test
    void migrateJavaxAsynchronousToAsync() {
        rewriteRun(
            java(
                """
                import javax.ejb.Asynchronous;

                public class EmailService {
                    @Asynchronous
                    public void sendEmail(String to, String message) {
                        // send email
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Async;

                public class EmailService {
                    @Async
                    public void sendEmail(String to, String message) {
                        // send email
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateJakartaAsynchronousToAsync() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Asynchronous;

                public class ReportService {
                    @Asynchronous
                    public void generateReport(String reportId) {
                        // generate report
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Async;

                public class ReportService {
                    @Async
                    public void generateReport(String reportId) {
                        // generate report
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMultipleAsynchronousMethods() {
        rewriteRun(
            java(
                """
                import javax.ejb.Asynchronous;

                public class NotificationService {
                    @Asynchronous
                    public void sendSms(String phone, String message) {
                        // send SMS
                    }

                    @Asynchronous
                    public void sendPush(String deviceId, String message) {
                        // send push notification
                    }

                    public void sendImmediate(String message) {
                        // send immediately (not async)
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Async;

                public class NotificationService {
                    @Async
                    public void sendSms(String phone, String message) {
                        // send SMS
                    }

                    @Async
                    public void sendPush(String deviceId, String message) {
                        // send push notification
                    }

                    public void sendImmediate(String message) {
                        // send immediately (not async)
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateClassLevelAsynchronous() {
        rewriteRun(
            java(
                """
                import javax.ejb.Asynchronous;

                @Asynchronous
                public class AsyncBatchService {
                    public void processBatch(String batchId) {
                        // process batch
                    }

                    public void cleanupBatch(String batchId) {
                        // cleanup
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Async;

                @Async
                public class AsyncBatchService {
                    public void processBatch(String batchId) {
                        // process batch
                    }

                    public void cleanupBatch(String batchId) {
                        // cleanup
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeWhenNoAsynchronous() {
        rewriteRun(
            java(
                """
                public class SyncService {
                    public void doWork() {
                        // synchronous work
                    }
                }
                """
            )
        );
    }

    @Test
    void preserveOtherAnnotations() {
        rewriteRun(
            java(
                """
                import javax.ejb.Asynchronous;
                import javax.ejb.Stateless;

                @Stateless
                public class OrderService {
                    @Asynchronous
                    public void processOrder(String orderId) {
                        // process order
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Async;

                import javax.ejb.Stateless;

                @Stateless
                public class OrderService {
                    @Async
                    public void processOrder(String orderId) {
                        // process order
                    }
                }
                """
            )
        );
    }
}
