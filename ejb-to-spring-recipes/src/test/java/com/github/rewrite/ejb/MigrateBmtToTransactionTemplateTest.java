package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link MigrateBmtToTransactionTemplate}.
 *
 * Tests BMT pattern classification and guidance generation.
 */
class MigrateBmtToTransactionTemplateTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateBmtToTransactionTemplate())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-tx", "spring-context"));
    }

    @Test
    @DocumentExample
    void classifiesLinearTryCatchPattern() {
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
                            System.out.println("Processing order");
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

                @NeedsReview(reason = "BMT with 1 linear pattern(s) - can be converted to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "LINEAR PATTERNS: Replace try { utx.begin(); ... utx.commit(); } catch { utx.rollback(); } with transactionTemplate.executeWithoutResult(status -> { ... }); Inject TransactionTemplate instead of UserTransaction. ")
                public class OrderService {

                    @Resource
                    private UserTransaction utx;

                    public void processOrder() {
                        try {
                            utx.begin();
                            System.out.println("Processing order");
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
    void classifiesDirectBeginCommitAsComplexWithoutRollback() {
        // Direct begin/commit without rollback handling is COMPLEX - requires manual review
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.transaction.UserTransaction;

                public class SimpleService {

                    private UserTransaction tx;

                    public void doWork() throws Exception {
                        tx.begin();
                        System.out.println("Working");
                        tx.commit();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "BMT with 1 complex pattern(s) - requires manual analysis", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "COMPLEX PATTERNS: Analyze control flow carefully. doWork: No explicit rollback handling; ")
                public class SimpleService {

                    private UserTransaction tx;

                    public void doWork() throws Exception {
                        tx.begin();
                        System.out.println("Working");
                        tx.commit();
                    }
                }
                """
            )
        );
    }

    @Test
    void classifiesComplexLoopPattern() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.transaction.UserTransaction;
                import java.util.List;

                public class LoopService {

                    private UserTransaction utx;

                    public void processBatch(List<String> items) throws Exception {
                        for (String item : items) {
                            utx.begin();
                            System.out.println(item);
                            utx.commit();
                        }
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.transaction.UserTransaction;
                import java.util.List;

                @NeedsReview(reason = "BMT with 1 complex pattern(s) - requires manual analysis", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "COMPLEX PATTERNS: Analyze control flow carefully. processBatch: UTX calls inside loop; ")
                public class LoopService {

                    private UserTransaction utx;

                    public void processBatch(List<String> items) throws Exception {
                        for (String item : items) {
                            utx.begin();
                            System.out.println(item);
                            utx.commit();
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void classifiesMultipleTransactionPattern() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.transaction.UserTransaction;

                public class MultiTxService {

                    private UserTransaction utx;

                    public void multiStep() throws Exception {
                        utx.begin();
                        System.out.println("Step 1");
                        utx.commit();

                        utx.begin();
                        System.out.println("Step 2");
                        utx.commit();
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "BMT with 1 complex pattern(s) - requires manual analysis", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "COMPLEX PATTERNS: Analyze control flow carefully. multiStep: Multiple transaction blocks; ")
                public class MultiTxService {

                    private UserTransaction utx;

                    public void multiStep() throws Exception {
                        utx.begin();
                        System.out.println("Step 1");
                        utx.commit();

                        utx.begin();
                        System.out.println("Step 2");
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
    void noChangeWhenAlreadyHasNeedsReview() {
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-tx", "spring-context")
                .dependsOn(
                    """
                    package com.github.rewrite.ejb.annotations;
                    import java.lang.annotation.*;
                    @Retention(RetentionPolicy.RUNTIME)
                    @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
                    public @interface NeedsReview {
                        String reason() default "";
                        Category category();
                        String originalCode() default "";
                        String suggestedAction() default "";
                        enum Category { MANUAL_MIGRATION }
                    }
                    """
                )),
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "Already marked", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "", suggestedAction = "")
                public class AlreadyMarkedService {

                    private UserTransaction utx;

                    public void doWork() throws Exception {
                        utx.begin();
                        utx.commit();
                    }
                }
                """
            )
        );
    }

    @Test
    void detectsThisFieldAccessPattern() {
        // Codex review: Handle this.utx.begin() field access pattern
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.transaction.UserTransaction;

                public class FieldAccessService {

                    private UserTransaction utx;

                    public void processWithThis() {
                        try {
                            this.utx.begin();
                            System.out.println("Processing");
                            this.utx.commit();
                        } catch (Exception e) {
                            try {
                                this.utx.rollback();
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
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "BMT with 1 linear pattern(s) - can be converted to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "LINEAR PATTERNS: Replace try { utx.begin(); ... utx.commit(); } catch { utx.rollback(); } with transactionTemplate.executeWithoutResult(status -> { ... }); Inject TransactionTemplate instead of UserTransaction. ")
                public class FieldAccessService {

                    private UserTransaction utx;

                    public void processWithThis() {
                        try {
                            this.utx.begin();
                            System.out.println("Processing");
                            this.utx.commit();
                        } catch (Exception e) {
                            try {
                                this.utx.rollback();
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
    void detectsMultipleUtxFields() {
        // Codex review: Collect all UserTransaction field names
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.transaction.UserTransaction;

                public class MultiFieldService {

                    private UserTransaction tx1;
                    private UserTransaction tx2;

                    public void useFirstTx() {
                        try {
                            tx1.begin();
                            System.out.println("Using tx1");
                            tx1.commit();
                        } catch (Exception e) {
                            try {
                                tx1.rollback();
                            } catch (Exception ex) {
                                // ignore
                            }
                        }
                    }

                    public void useSecondTx() {
                        try {
                            tx2.begin();
                            System.out.println("Using tx2");
                            tx2.commit();
                        } catch (Exception e) {
                            try {
                                tx2.rollback();
                            } catch (Exception ex) {
                                // ignore
                            }
                        }
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "BMT with 2 linear pattern(s) - can be converted to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "LINEAR PATTERNS: Replace try { utx.begin(); ... utx.commit(); } catch { utx.rollback(); } with transactionTemplate.executeWithoutResult(status -> { ... }); Inject TransactionTemplate instead of UserTransaction. ")
                public class MultiFieldService {

                    private UserTransaction tx1;
                    private UserTransaction tx2;

                    public void useFirstTx() {
                        try {
                            tx1.begin();
                            System.out.println("Using tx1");
                            tx1.commit();
                        } catch (Exception e) {
                            try {
                                tx1.rollback();
                            } catch (Exception ex) {
                                // ignore
                            }
                        }
                    }

                    public void useSecondTx() {
                        try {
                            tx2.begin();
                            System.out.println("Using tx2");
                            tx2.commit();
                        } catch (Exception e) {
                            try {
                                tx2.rollback();
                            } catch (Exception ex) {
                                // ignore
                            }
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void detectsSessionContextGetUserTransaction() {
        // Codex review: Handle ctx.getUserTransaction().begin() pattern
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.ejb.SessionContext;

                public class ContextService {

                    @Resource
                    private SessionContext ctx;

                    public void processWithContext() {
                        try {
                            ctx.getUserTransaction().begin();
                            System.out.println("Processing");
                            ctx.getUserTransaction().commit();
                        } catch (Exception e) {
                            try {
                                ctx.getUserTransaction().rollback();
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
                import jakarta.ejb.SessionContext;

                @NeedsReview(reason = "BMT with 1 linear pattern(s) - can be converted to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "LINEAR PATTERNS: Replace try { utx.begin(); ... utx.commit(); } catch { utx.rollback(); } with transactionTemplate.executeWithoutResult(status -> { ... }); Inject TransactionTemplate instead of UserTransaction. ")
                public class ContextService {

                    @Resource
                    private SessionContext ctx;

                    public void processWithContext() {
                        try {
                            ctx.getUserTransaction().begin();
                            System.out.println("Processing");
                            ctx.getUserTransaction().commit();
                        } catch (Exception e) {
                            try {
                                ctx.getUserTransaction().rollback();
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
    void classifiesMixedUtxSourcesAsComplex() {
        // Codex review: tx1.begin/commit with tx2.rollback should be COMPLEX
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.transaction.UserTransaction;

                public class MixedUtxService {

                    private UserTransaction tx1;
                    private UserTransaction tx2;

                    public void mixedTransaction() {
                        try {
                            tx1.begin();
                            System.out.println("Processing");
                            tx1.commit();
                        } catch (Exception e) {
                            try {
                                tx2.rollback();
                            } catch (Exception ex) {
                                // ignore
                            }
                        }
                    }
                }
                """,
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "BMT with 1 complex pattern(s) - requires manual analysis", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "COMPLEX PATTERNS: Analyze control flow carefully. mixedTransaction: Multiple UTX sources used; ")
                public class MixedUtxService {

                    private UserTransaction tx1;
                    private UserTransaction tx2;

                    public void mixedTransaction() {
                        try {
                            tx1.begin();
                            System.out.println("Processing");
                            tx1.commit();
                        } catch (Exception e) {
                            try {
                                tx2.rollback();
                            } catch (Exception ex) {
                                // ignore
                            }
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void detectsLocalVariableFromGetUserTransaction() {
        // Codex review: Track local variables assigned from getUserTransaction()
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.ejb.SessionContext;
                import jakarta.transaction.UserTransaction;

                public class LocalVarService {

                    @Resource
                    private SessionContext ctx;

                    public void processWithLocalVar() {
                        try {
                            UserTransaction utx = ctx.getUserTransaction();
                            utx.begin();
                            System.out.println("Processing");
                            utx.commit();
                        } catch (Exception e) {
                            try {
                                UserTransaction utx = ctx.getUserTransaction();
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
                import jakarta.ejb.SessionContext;
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "BMT with 1 linear pattern(s) - can be converted to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "LINEAR PATTERNS: Replace try { utx.begin(); ... utx.commit(); } catch { utx.rollback(); } with transactionTemplate.executeWithoutResult(status -> { ... }); Inject TransactionTemplate instead of UserTransaction. ")
                public class LocalVarService {

                    @Resource
                    private SessionContext ctx;

                    public void processWithLocalVar() {
                        try {
                            UserTransaction utx = ctx.getUserTransaction();
                            utx.begin();
                            System.out.println("Processing");
                            utx.commit();
                        } catch (Exception e) {
                            try {
                                UserTransaction utx = ctx.getUserTransaction();
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
    void normalizesThisAndBareFieldAccess() {
        // Codex review: Normalize this.utx and utx to same source
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.transaction.UserTransaction;

                public class MixedAccessService {

                    private UserTransaction utx;

                    public void processMixedAccess() {
                        try {
                            this.utx.begin();
                            System.out.println("Processing");
                            utx.commit();
                        } catch (Exception e) {
                            try {
                                this.utx.rollback();
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
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "BMT with 1 linear pattern(s) - can be converted to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "LINEAR PATTERNS: Replace try { utx.begin(); ... utx.commit(); } catch { utx.rollback(); } with transactionTemplate.executeWithoutResult(status -> { ... }); Inject TransactionTemplate instead of UserTransaction. ")
                public class MixedAccessService {

                    private UserTransaction utx;

                    public void processMixedAccess() {
                        try {
                            this.utx.begin();
                            System.out.println("Processing");
                            utx.commit();
                        } catch (Exception e) {
                            try {
                                this.utx.rollback();
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
    void detectsTwoStepLocalVariableAssignment() {
        // Codex review: Handle separate declaration and assignment: UserTransaction utx; utx = ctx.getUserTransaction();
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.annotation.Resource;
                import jakarta.ejb.SessionContext;
                import jakarta.transaction.UserTransaction;

                public class TwoStepAssignmentService {

                    @Resource
                    private SessionContext ctx;

                    public void processWithTwoStepAssignment() {
                        try {
                            UserTransaction utx;
                            utx = ctx.getUserTransaction();
                            utx.begin();
                            System.out.println("Processing");
                            utx.commit();
                        } catch (Exception e) {
                            try {
                                UserTransaction utx;
                                utx = ctx.getUserTransaction();
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
                import jakarta.ejb.SessionContext;
                import jakarta.transaction.UserTransaction;

                @NeedsReview(reason = "BMT with 1 linear pattern(s) - can be converted to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "LINEAR PATTERNS: Replace try { utx.begin(); ... utx.commit(); } catch { utx.rollback(); } with transactionTemplate.executeWithoutResult(status -> { ... }); Inject TransactionTemplate instead of UserTransaction. ")
                public class TwoStepAssignmentService {

                    @Resource
                    private SessionContext ctx;

                    public void processWithTwoStepAssignment() {
                        try {
                            UserTransaction utx;
                            utx = ctx.getUserTransaction();
                            utx.begin();
                            System.out.println("Processing");
                            utx.commit();
                        } catch (Exception e) {
                            try {
                                UserTransaction utx;
                                utx = ctx.getUserTransaction();
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
    void detectsContextAsMethodParameter() {
        // Codex review: Handle SessionContext passed as method parameter
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.SessionContext;

                public class ParameterContextService {

                    public void processWithContextParam(SessionContext ctx) {
                        try {
                            ctx.getUserTransaction().begin();
                            System.out.println("Processing");
                            ctx.getUserTransaction().commit();
                        } catch (Exception e) {
                            try {
                                ctx.getUserTransaction().rollback();
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
                import jakarta.ejb.SessionContext;

                @NeedsReview(reason = "BMT with 1 linear pattern(s) - can be converted to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "LINEAR PATTERNS: Replace try { utx.begin(); ... utx.commit(); } catch { utx.rollback(); } with transactionTemplate.executeWithoutResult(status -> { ... }); Inject TransactionTemplate instead of UserTransaction. ")
                public class ParameterContextService {

                    public void processWithContextParam(SessionContext ctx) {
                        try {
                            ctx.getUserTransaction().begin();
                            System.out.println("Processing");
                            ctx.getUserTransaction().commit();
                        } catch (Exception e) {
                            try {
                                ctx.getUserTransaction().rollback();
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
    void detectsLocalContextVariable() {
        // Codex review: Handle SessionContext as local variable
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.SessionContext;
                import javax.naming.InitialContext;

                public class LocalContextService {

                    public void processWithLocalContext() throws Exception {
                        InitialContext ic = new InitialContext();
                        SessionContext ctx = (SessionContext) ic.lookup("java:comp/EJBContext");
                        try {
                            ctx.getUserTransaction().begin();
                            System.out.println("Processing");
                            ctx.getUserTransaction().commit();
                        } catch (Exception e) {
                            try {
                                ctx.getUserTransaction().rollback();
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
                import jakarta.ejb.SessionContext;
                import javax.naming.InitialContext;

                @NeedsReview(reason = "BMT with 1 linear pattern(s) - can be converted to TransactionTemplate", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "UserTransaction.begin/commit/rollback", suggestedAction = "LINEAR PATTERNS: Replace try { utx.begin(); ... utx.commit(); } catch { utx.rollback(); } with transactionTemplate.executeWithoutResult(status -> { ... }); Inject TransactionTemplate instead of UserTransaction. ")
                public class LocalContextService {

                    public void processWithLocalContext() throws Exception {
                        InitialContext ic = new InitialContext();
                        SessionContext ctx = (SessionContext) ic.lookup("java:comp/EJBContext");
                        try {
                            ctx.getUserTransaction().begin();
                            System.out.println("Processing");
                            ctx.getUserTransaction().commit();
                        } catch (Exception e) {
                            try {
                                ctx.getUserTransaction().rollback();
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
}
