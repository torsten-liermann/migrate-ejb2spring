package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateApplicationExceptionRollbackTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateApplicationExceptionRollback())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.ejb-api", "spring-tx"));
    }

    @DocumentExample
    @Test
    void migratesCheckedExceptionWithRollbackTrue() {
        rewriteRun(
            // Exception class with @ApplicationException(rollback=true)
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = true)
                public class BookingException extends Exception {
                }
                """,
                """
                public class BookingException extends Exception {
                }
                """
            ),
            // Method that throws the exception gets @Transactional(rollbackFor=...)
            java(
                """
                public class BookingService {
                    public void makeBooking() throws BookingException {
                        // business logic
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                public class BookingService {
                    @Transactional(rollbackFor = BookingException.class)
                    public void makeBooking() throws BookingException {
                        // business logic
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesUncheckedExceptionWithRollbackFalse() {
        rewriteRun(
            // RuntimeException with @ApplicationException (rollback=false is default)
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException
                public class ValidationException extends RuntimeException {
                }
                """,
                """
                public class ValidationException extends RuntimeException {
                }
                """
            ),
            // Method that throws the exception gets @Transactional(noRollbackFor=...)
            java(
                """
                public class ValidationService {
                    public void validate() throws ValidationException {
                        // validation logic
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                public class ValidationService {
                    @Transactional(noRollbackFor = ValidationException.class)
                    public void validate() throws ValidationException {
                        // validation logic
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeForCheckedExceptionWithRollbackFalse() {
        // Checked exceptions don't rollback by default in Spring, so no change needed
        rewriteRun(
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = false)
                public class BusinessException extends Exception {
                }
                """,
                """
                public class BusinessException extends Exception {
                }
                """
            ),
            java(
                """
                public class BusinessService {
                    public void doWork() throws BusinessException {
                        // business logic
                    }
                }
                """
                // No @Transactional added - Spring default matches EJB behavior
            )
        );
    }

    @Test
    void noChangeForUncheckedExceptionWithRollbackTrue() {
        // RuntimeExceptions rollback by default in Spring, so no change needed
        rewriteRun(
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = true)
                public class SystemFailureException extends RuntimeException {
                }
                """,
                """
                public class SystemFailureException extends RuntimeException {
                }
                """
            ),
            java(
                """
                public class SystemService {
                    public void doWork() throws SystemFailureException {
                        // system logic
                    }
                }
                """
                // No @Transactional added - Spring default matches EJB behavior
            )
        );
    }

    @Test
    void mergesWithExistingTransactional() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = true)
                public class InventoryException extends Exception {
                }
                """,
                """
                public class InventoryException extends Exception {
                }
                """
            ),
            java(
                """
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.transaction.annotation.Propagation;

                public class InventoryService {
                    @Transactional(propagation = Propagation.REQUIRES_NEW)
                    public void updateStock() throws InventoryException {
                        // inventory logic
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Transactional;
                import org.springframework.transaction.annotation.Propagation;

                public class InventoryService {
                    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = InventoryException.class)
                    public void updateStock() throws InventoryException {
                        // inventory logic
                    }
                }
                """
            )
        );
    }

    @Test
    void handlesMultipleExceptionsInThrowsClause() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = true)
                public class PaymentException extends Exception {
                }
                """,
                """
                public class PaymentException extends Exception {
                }
                """
            ),
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = true)
                public class AccountException extends Exception {
                }
                """,
                """
                public class AccountException extends Exception {
                }
                """
            ),
            java(
                """
                public class PaymentService {
                    public void processPayment() throws PaymentException, AccountException {
                        // payment logic
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                public class PaymentService {
                    @Transactional(rollbackFor = {PaymentException.class, AccountException.class})
                    public void processPayment() throws PaymentException, AccountException {
                        // payment logic
                    }
                }
                """
            )
        );
    }

    @Test
    void handlesInheritedAttribute() {
        // The inherited attribute doesn't affect Spring behavior - just remove annotation
        rewriteRun(
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = true, inherited = true)
                public class BaseException extends Exception {
                }
                """,
                """
                public class BaseException extends Exception {
                }
                """
            ),
            java(
                """
                public class BaseService {
                    public void doWork() throws BaseException {
                        // logic
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                public class BaseService {
                    @Transactional(rollbackFor = BaseException.class)
                    public void doWork() throws BaseException {
                        // logic
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeWithoutApplicationException() {
        rewriteRun(
            java(
                """
                public class RegularException extends Exception {
                }
                """
            ),
            java(
                """
                public class RegularService {
                    public void doWork() throws RegularException {
                        // logic
                    }
                }
                """
            )
        );
    }

    @Test
    void handlesSubclassOfInheritedApplicationException() {
        // When a subclass is thrown and parent has @ApplicationException(inherited=true),
        // the subclass should also get the rollback semantics
        rewriteRun(
            // Base exception with inherited=true (default)
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = true)
                public class ParentException extends Exception {
                }
                """,
                """
                public class ParentException extends Exception {
                }
                """
            ),
            // Subclass without annotation
            java(
                """
                public class ChildException extends ParentException {
                }
                """
            ),
            // Method throwing subclass should get rollbackFor mapping
            java(
                """
                public class ChildService {
                    public void doWork() throws ChildException {
                        // logic
                    }
                }
                """,
                """
                import org.springframework.transaction.annotation.Transactional;

                public class ChildService {
                    @Transactional(rollbackFor = ChildException.class)
                    public void doWork() throws ChildException {
                        // logic
                    }
                }
                """
            )
        );
    }

    @Test
    void inheritedFalseDoesNotApplyToSubclass() {
        // When inherited=false, subclasses don't inherit rollback semantics
        rewriteRun(
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = true, inherited = false)
                public class NonInheritedParent extends Exception {
                }
                """,
                """
                public class NonInheritedParent extends Exception {
                }
                """
            ),
            java(
                """
                public class NonInheritedChild extends NonInheritedParent {
                }
                """
            ),
            // Method throwing subclass should NOT get rollbackFor because inherited=false
            java(
                """
                public class NonInheritedService {
                    public void doWork() throws NonInheritedChild {
                        // logic
                    }
                }
                """
                // No change - child doesn't inherit the mapping
            )
        );
    }

    @Test
    void noDuplicateWhenExistingHasFullyQualifiedClassLiteral() {
        // When existing @Transactional already has the exception (FQN form),
        // no duplicate should be added
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = true)
                public class DuplicateTestException extends Exception {
                }
                """,
                """
                package com.example;


                public class DuplicateTestException extends Exception {
                }
                """
            ),
            java(
                """
                package com.example;

                import org.springframework.transaction.annotation.Transactional;

                public class DuplicateTestService {
                    @Transactional(rollbackFor = com.example.DuplicateTestException.class)
                    public void doWork() throws DuplicateTestException {
                        // logic
                    }
                }
                """
                // No change - exception already in rollbackFor (just FQN form)
            )
        );
    }
}
