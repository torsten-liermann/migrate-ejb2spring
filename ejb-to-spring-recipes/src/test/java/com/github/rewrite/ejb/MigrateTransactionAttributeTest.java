package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateTransactionAttributeTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateTransactionAttribute())
            .parser(JavaParser.fromJavaVersion()
                .classpath("javax.ejb-api", "spring-tx"));
    }

    @DocumentExample
    @Test
    void migrateRequiredTransaction() {
        rewriteRun(
            java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                public class PaymentService {
                    @TransactionAttribute(TransactionAttributeType.REQUIRED)
                    public void processPayment() {
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                public class PaymentService {
                    @Transactional(Propagation.REQUIRED)
                    public void processPayment() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateRequiresNewTransaction() {
        rewriteRun(
            java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                public class AuditService {
                    @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
                    public void logAudit() {
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                public class AuditService {
                    @Transactional(Propagation.REQUIRES_NEW)
                    public void logAudit() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMandatoryTransaction() {
        rewriteRun(
            java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                public class CriticalService {
                    @TransactionAttribute(TransactionAttributeType.MANDATORY)
                    public void criticalOperation() {
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                public class CriticalService {
                    @Transactional(Propagation.MANDATORY)
                    public void criticalOperation() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateNotSupportedTransaction() {
        rewriteRun(
            java(
                """
                import javax.ejb.TransactionAttribute;
                import javax.ejb.TransactionAttributeType;

                public class ReportService {
                    @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
                    public void generateReport() {
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Propagation;
                import org.springframework.transaction.annotation.Transactional;

                public class ReportService {
                    @Transactional(Propagation.NOT_SUPPORTED)
                    public void generateReport() {
                    }
                }
                """
            )
        );
    }

    @Test
    void removeTransactionManagement() {
        rewriteRun(
            java(
                """
                import javax.ejb.TransactionManagement;
                import javax.ejb.TransactionManagementType;

                @TransactionManagement(TransactionManagementType.CONTAINER)
                public class ManagedService {
                    public void doWork() {
                    }
                }
                """,
                """
                public class ManagedService {
                    public void doWork() {
                    }
                }
                """
            )
        );
    }
}
