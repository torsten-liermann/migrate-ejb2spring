package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link MigrateBeanManagedTransactions}.
 *
 * The recipe takes a conservative approach: it marks classes using UserTransaction
 * with @NeedsReview but does NOT transform the field type. This preserves compile
 * safety while flagging code that needs manual migration.
 */
class MigrateBeanManagedTransactionsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateBeanManagedTransactions())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-beans", "spring-tx", "spring-context"));
    }

    @Test
    @DocumentExample
    void marksClassWithUserTransactionForReview() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.transaction.UserTransaction;

                public class OrderService {

                    @Resource
                    private UserTransaction utx;

                    public void processOrder() {
                        try {
                            utx.begin();
                            // business logic
                            utx.commit();
                        } catch (Exception e) {
                            try {
                                utx.rollback();
                            } catch (Exception ex) {
                                // ignore
                            }
                            throw new RuntimeException(e);
                        }
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "Bean-Managed Transactions (UserTransaction) require manual migration to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback/setRollbackOnly", suggestedAction = "Replace with transactionTemplate.execute(status -> { ... }); use status.setRollbackOnly() for rollback; return value from lambda for results")
                public class OrderService {

                    @Resource
                    private UserTransaction utx;

                    public void processOrder() {
                        try {
                            utx.begin();
                            // business logic
                            utx.commit();
                        } catch (Exception e) {
                            try {
                                utx.rollback();
                            } catch (Exception ex) {
                                // ignore
                            }
                            throw new RuntimeException(e);
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void marksJavaxUserTransactionForReview() {
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-beans", "spring-tx", "spring-context")
                .dependsOn(
                    """
                    package javax.annotation;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                    public @interface Resource {
                        String name() default "";
                    }
                    """,
                    """
                    package javax.transaction;
                    public interface UserTransaction {
                        void begin() throws Exception;
                        void commit() throws Exception;
                        void rollback() throws Exception;
                    }
                    """
                )),
            java(
                """
                package com.example;

                import javax.annotation.Resource;
                import javax.transaction.UserTransaction;

                public class PaymentService {

                    @Resource
                    private UserTransaction userTransaction;

                    public void pay() throws Exception {
                        userTransaction.begin();
                        userTransaction.commit();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;

                import javax.annotation.Resource;
                import javax.transaction.UserTransaction;

                @NeedsReview(reason = "Bean-Managed Transactions (UserTransaction) require manual migration to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback/setRollbackOnly", suggestedAction = "Replace with transactionTemplate.execute(status -> { ... }); use status.setRollbackOnly() for rollback; return value from lambda for results")
                public class PaymentService {

                    @Resource
                    private UserTransaction userTransaction;

                    public void pay() throws Exception {
                        userTransaction.begin();
                        userTransaction.commit();
                    }
                }
                """
            )
        );
    }

    @Test
    void marksFieldWithoutInjectionAnnotationForReview() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.transaction.UserTransaction;

                public class SimpleService {

                    private UserTransaction tx;

                    public void doWork() throws Exception {
                        tx.begin();
                        tx.commit();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "Bean-Managed Transactions (UserTransaction) require manual migration to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback/setRollbackOnly", suggestedAction = "Replace with transactionTemplate.execute(status -> { ... }); use status.setRollbackOnly() for rollback; return value from lambda for results")
                public class SimpleService {

                    private UserTransaction tx;

                    public void doWork() throws Exception {
                        tx.begin();
                        tx.commit();
                    }
                }
                """
            )
        );
    }

    @Test
    void marksFieldWithAutowiredForReview() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.transaction.UserTransaction;
                import org.springframework.beans.factory.annotation.Autowired;

                public class MixedService {

                    @Autowired
                    private UserTransaction utx;

                    public void execute() throws Exception {
                        utx.begin();
                        utx.commit();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.transaction.UserTransaction;
                import org.springframework.beans.factory.annotation.Autowired;

                @NeedsReview(reason = "Bean-Managed Transactions (UserTransaction) require manual migration to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback/setRollbackOnly", suggestedAction = "Replace with transactionTemplate.execute(status -> { ... }); use status.setRollbackOnly() for rollback; return value from lambda for results")
                public class MixedService {

                    @Autowired
                    private UserTransaction utx;

                    public void execute() throws Exception {
                        utx.begin();
                        utx.commit();
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeWithoutUserTransaction() {
        rewriteRun(
            java(
                """
                package com.example;

                import org.springframework.stereotype.Service;

                @Service
                public class RegularService {

                    public void doSomething() {
                        System.out.println("Hello");
                    }
                }
                """
            )
        );
    }

    @Test
    void marksFieldWithoutUsageCalls() {
        // Field with UserTransaction but no calls - still marked for review
        // (field might be passed to another class)
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.transaction.UserTransaction;

                public class DelegatingService {

                    @Resource
                    private UserTransaction utx;

                    public UserTransaction getTransaction() {
                        return utx;
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "Bean-Managed Transactions (UserTransaction) require manual migration to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback/setRollbackOnly", suggestedAction = "Replace with transactionTemplate.execute(status -> { ... }); use status.setRollbackOnly() for rollback; return value from lambda for results")
                public class DelegatingService {

                    @Resource
                    private UserTransaction utx;

                    public UserTransaction getTransaction() {
                        return utx;
                    }
                }
                """
            )
        );
    }
}
