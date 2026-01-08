package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveLocalAnnotationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveLocalAnnotation())
            .parser(JavaParser.fromJavaVersion()
                .classpath("javax.ejb-api"));
    }

    @DocumentExample
    @Test
    void removeLocalFromInterface() {
        rewriteRun(
            java(
                """
                import javax.ejb.Local;

                @Local
                public interface CustomerService {
                    void notify();
                }
                """,
                """
                public interface CustomerService {
                    void notify();
                }
                """
            )
        );
    }

    @Test
    void removeLocalFromClass() {
        rewriteRun(
            java(
                """
                import javax.ejb.Local;
                import javax.ejb.Stateless;

                @Stateless
                @Local(CustomerService.class)
                public class CustomerServiceImpl implements CustomerService {
                    public void notify() {}
                }
                """,
                """
                import javax.ejb.Stateless;

                @Stateless
                public class CustomerServiceImpl implements CustomerService {
                    public void notify() {}
                }
                """
            ),
            java(
                """
                public interface CustomerService {
                    void notify();
                }
                """
            )
        );
    }
}
