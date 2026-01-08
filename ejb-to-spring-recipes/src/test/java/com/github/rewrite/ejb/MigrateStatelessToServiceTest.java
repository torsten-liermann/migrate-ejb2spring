package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateStatelessToServiceTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateStatelessToService())
            .parser(JavaParser.fromJavaVersion()
                .classpath("javax.ejb-api", "spring-context"));
    }

    @DocumentExample
    @Test
    void migrateSimpleStateless() {
        rewriteRun(
            java(
                """
                import javax.ejb.Stateless;

                @Stateless
                public class CalculatorService {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """,
                """
                import org.springframework.stereotype.Service;

                @Service
                public class CalculatorService {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateStatelessWithName() {
        // Note: @Stateless(name="x") becomes @Service(name="x") which Spring also accepts
        rewriteRun(
            java(
                """
                import javax.ejb.Stateless;

                @Stateless(name = "myCalculator")
                public class CalculatorService {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """,
                """
                import org.springframework.stereotype.Service;

                @Service(name = "myCalculator")
                public class CalculatorService {
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """
            )
        );
    }

    @Test
    void removeLocalBean() {
        rewriteRun(
            java(
                """
                import javax.ejb.LocalBean;
                import javax.ejb.Stateless;

                @Stateless
                @LocalBean
                public class LocalService {
                    public String process() {
                        return "processed";
                    }
                }
                """,
                """
                import org.springframework.stereotype.Service;

                @Service
                public class LocalService {
                    public String process() {
                        return "processed";
                    }
                }
                """
            )
        );
    }
}
