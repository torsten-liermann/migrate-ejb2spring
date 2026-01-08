package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveClassLevelEjbAnnotationTest implements RewriteTest {

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
            String name() default "";
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveClassLevelEjbAnnotation())
            .parser(JavaParser.fromJavaVersion());
    }

    @Test
    void removesClassLevelAnnotationButKeepsFieldLevel() {
        rewriteRun(
            java(EJB_STUB),
            java(
                """
                import jakarta.ejb.EJB;

                @EJB(name = "ejb/MyService", beanInterface = MyService.class)
                public class MyBean {
                    @EJB
                    private MyService service;
                }
                """,
                """
                import jakarta.ejb.EJB;

                public class MyBean {
                    @EJB
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
    void removesImportWhenOnlyClassLevelExists() {
        rewriteRun(
            java(EJB_STUB),
            java(
                """
                import jakarta.ejb.EJB;

                @EJB(name = "ejb/MyService", beanInterface = MyService.class)
                public class MyBean {
                }
                """,
                """
                public class MyBean {
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
