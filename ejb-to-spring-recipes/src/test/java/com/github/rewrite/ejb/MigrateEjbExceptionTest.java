package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateEjbExceptionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEjbException())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.ejb-api", "javax.ejb-api"));
    }

    @DocumentExample
    @Test
    void migrateEjbExceptionThrow() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.EJBException;

                public class CustomerService {
                    public void validateName(String name) {
                        if (name == null || name.isEmpty()) {
                            throw new EJBException("Invalid name: customer names should not be empty");
                        }
                    }
                }
                """,
                """
                public class CustomerService {
                    public void validateName(String name) {
                        if (name == null || name.isEmpty()) {
                            throw new RuntimeException("Invalid name: customer names should not be empty");
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateEjbExceptionWithCause() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.EJBException;

                public class DataService {
                    public void processData() {
                        try {
                            // some operation
                        } catch (Exception e) {
                            throw new EJBException("Processing failed", e);
                        }
                    }
                }
                """,
                """
                public class DataService {
                    public void processData() {
                        try {
                            // some operation
                        } catch (Exception e) {
                            throw new RuntimeException("Processing failed", e);
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateGetCausedByExceptionToGetCause() {
        // getCausedByException() returns Exception, getCause() returns Throwable.
        // When assigned to Exception, a cast is inserted to maintain compilability.
        rewriteRun(
            java(
                """
                import jakarta.ejb.EJBException;

                public class ExceptionHandler {
                    public void handleException(EJBException ex) {
                        Exception cause = ex.getCausedByException();
                        if (cause != null) {
                            // handle the cause
                        }
                    }
                }
                """,
                """
                public class ExceptionHandler {
                    public void handleException(RuntimeException ex) {
                        Exception cause = (Exception) ex.getCause();
                        if (cause != null) {
                            // handle the cause
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateGetCausedByExceptionToThrowable() {
        // When assigned to Throwable, no cast is needed
        rewriteRun(
            java(
                """
                import jakarta.ejb.EJBException;

                public class ExceptionHandler {
                    public void handleException(EJBException ex) {
                        Throwable cause = ex.getCausedByException();
                        if (cause != null) {
                            // handle the cause
                        }
                    }
                }
                """,
                """
                public class ExceptionHandler {
                    public void handleException(RuntimeException ex) {
                        Throwable cause = ex.getCause();
                        if (cause != null) {
                            // handle the cause
                        }
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateJavaxEjbException() {
        rewriteRun(
            java(
                """
                import javax.ejb.EJBException;

                public class LegacyService {
                    public void validate() {
                        throw new EJBException("Validation failed");
                    }
                }
                """,
                """
                public class LegacyService {
                    public void validate() {
                        throw new RuntimeException("Validation failed");
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateCatchEjbException() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.EJBException;

                public class ServiceCaller {
                    public void callService() {
                        try {
                            // call EJB service
                        } catch (EJBException e) {
                            // handle EJB exception
                        }
                    }
                }
                """,
                """
                public class ServiceCaller {
                    public void callService() {
                        try {
                            // call EJB service
                        } catch (RuntimeException e) {
                            // handle EJB exception
                        }
                    }
                }
                """
            )
        );
    }
}
