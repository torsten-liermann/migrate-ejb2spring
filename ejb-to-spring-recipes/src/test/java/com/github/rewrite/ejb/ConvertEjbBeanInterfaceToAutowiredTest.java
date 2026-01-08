package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class ConvertEjbBeanInterfaceToAutowiredTest implements RewriteTest {

    private static final String EJB_STUB = """
        package jakarta.ejb;

        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.FIELD, ElementType.METHOD, ElementType.TYPE})
        public @interface EJB {
            Class<?> beanInterface() default Object.class;
            String beanName() default "";
        }
        """;

    private static final String AUTOWIRED_STUB = """
        package org.springframework.beans.factory.annotation;

        import java.lang.annotation.ElementType;
        import java.lang.annotation.Retention;
        import java.lang.annotation.RetentionPolicy;
        import java.lang.annotation.Target;

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.FIELD, ElementType.METHOD, ElementType.CONSTRUCTOR})
        public @interface Autowired {
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ConvertEjbBeanInterfaceToAutowired())
            .parser(JavaParser.fromJavaVersion());
    }

    @Test
    void convertsBeanInterfaceToFieldType() {
        rewriteRun(
            java(EJB_STUB),
            java(AUTOWIRED_STUB),
            java(
                """
                import jakarta.ejb.EJB;

                public class Example {
                    @EJB(beanInterface = MyService.class)
                    private Object service;
                }
                """,
                """
                import org.springframework.beans.factory.annotation.Autowired;

                public class Example {
                    @Autowired
                    private MyService service;
                }
                """
            ),
            java(
                """
                public interface MyService {
                }
                """
            )
        );
    }

    @Test
    void usesFullyQualifiedInterfaceWhenProvided() {
        rewriteRun(
            java(EJB_STUB),
            java(AUTOWIRED_STUB),
            java(
                """
                import jakarta.ejb.EJB;

                public class Example {
                    @EJB(beanInterface = com.example.api.RemoteService.class)
                    private Object service;
                }
                """,
                """
                import org.springframework.beans.factory.annotation.Autowired;

                public class Example {
                    @Autowired
                    private com.example.api.RemoteService service;
                }
                """
            )
        );
    }

    @Test
    void skipsBeanNameCases() {
        rewriteRun(
            java(EJB_STUB),
            java(AUTOWIRED_STUB),
            java(
                """
                import jakarta.ejb.EJB;

                public class Example {
                    @EJB(beanInterface = MyService.class, beanName = "special")
                    private Object service;
                }
                """
            ),
            java(
                """
                public interface MyService {
                }
                """
            )
        );
    }
}
