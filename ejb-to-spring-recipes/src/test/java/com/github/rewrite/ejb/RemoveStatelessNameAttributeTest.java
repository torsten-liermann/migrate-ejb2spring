package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link RemoveStatelessNameAttribute}.
 * <p>
 * JUS-004: Remove name attribute from @Stateless/@Singleton/@Stateful annotations.
 */
class RemoveStatelessNameAttributeTest implements RewriteTest {

    // Stub for Jakarta EJB annotations
    private static final String JAKARTA_EJB_STUB = """
        package jakarta.ejb;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Stateless {
            String name() default "";
            String mappedName() default "";
            String description() default "";
        }
        """;

    private static final String JAKARTA_SINGLETON_STUB = """
        package jakarta.ejb;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Singleton {
            String name() default "";
            String mappedName() default "";
            String description() default "";
        }
        """;

    private static final String JAKARTA_STATEFUL_STUB = """
        package jakarta.ejb;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Stateful {
            String name() default "";
            String mappedName() default "";
            String description() default "";
        }
        """;

    private static final String JAVAX_EJB_STUB = """
        package javax.ejb;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target(ElementType.TYPE)
        public @interface Stateless {
            String name() default "";
            String mappedName() default "";
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveStatelessNameAttribute());
    }

    @DocumentExample
    @Test
    void removeExplicitNameAttribute() {
        rewriteRun(
            java(JAKARTA_EJB_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(name = "MyBean")
                public class MyBean { }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class MyBean { }
                """
            )
        );
    }

    @Test
    void removeNameWithFieldReference() {
        rewriteRun(
            java(JAKARTA_EJB_STUB),
            java(
                """
                package com.example;

                public interface IMyBean {
                    String JNDI_NAME = "java:global/MyBean";
                }
                """
            ),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(name = IMyBean.JNDI_NAME)
                public class MyBean implements IMyBean { }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class MyBean implements IMyBean { }
                """
            )
        );
    }

    @Test
    void removeImplicitNameAttribute() {
        // @Stateless("Foo") is equivalent to @Stateless(name = "Foo") per EJB spec
        rewriteRun(
            java(JAKARTA_EJB_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless("ImplicitName")
                public class MyBean { }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class MyBean { }
                """
            )
        );
    }

    @Test
    void keepOtherAttributes() {
        rewriteRun(
            java(JAKARTA_EJB_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(name = "MyBean", mappedName = "ejb/MyBean")
                public class MyBean { }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(mappedName = "ejb/MyBean")
                public class MyBean { }
                """
            )
        );
    }

    @Test
    void keepMultipleOtherAttributes() {
        rewriteRun(
            java(JAKARTA_EJB_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(name = "MyBean", mappedName = "ejb/MyBean", description = "My service")
                public class MyBean { }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(mappedName = "ejb/MyBean", description = "My service")
                public class MyBean { }
                """
            )
        );
    }

    @Test
    void handleSingleton() {
        rewriteRun(
            java(JAKARTA_SINGLETON_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Singleton;

                @Singleton(name = "MySingleton")
                public class MySingleton { }
                """,
                """
                package com.example;

                import jakarta.ejb.Singleton;

                @Singleton
                public class MySingleton { }
                """
            )
        );
    }

    @Test
    void handleStateful() {
        // HIGH fix: @Stateful also has name attribute that should be removed
        rewriteRun(
            java(JAKARTA_STATEFUL_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateful;

                @Stateful(name = "MyStatefulBean")
                public class MyStatefulBean { }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateful;

                @Stateful
                public class MyStatefulBean { }
                """
            )
        );
    }

    @Test
    void noOpWhenNoNameAttribute() {
        rewriteRun(
            java(JAKARTA_EJB_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class MyBean { }
                """
            )
        );
    }

    @Test
    void noOpWhenOnlyOtherAttributes() {
        rewriteRun(
            java(JAKARTA_EJB_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(mappedName = "ejb/MyBean")
                public class MyBean { }
                """
            )
        );
    }

    @Test
    void handleJavaxNamespace() {
        rewriteRun(
            java(JAVAX_EJB_STUB),
            java(
                """
                package com.example;

                import javax.ejb.Stateless;

                @Stateless(name = "MyBean")
                public class MyBean { }
                """,
                """
                package com.example;

                import javax.ejb.Stateless;

                @Stateless
                public class MyBean { }
                """
            )
        );
    }

    @Test
    void handleNameAttributeInMiddle() {
        // Name attribute is not first in the list
        rewriteRun(
            java(JAKARTA_EJB_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(mappedName = "ejb/MyBean", name = "MyBean", description = "Service")
                public class MyBean { }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(mappedName = "ejb/MyBean", description = "Service")
                public class MyBean { }
                """
            )
        );
    }

    @Test
    void handleNameAttributeAtEnd() {
        rewriteRun(
            java(JAKARTA_EJB_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(mappedName = "ejb/MyBean", name = "MyBean")
                public class MyBean { }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless(mappedName = "ejb/MyBean")
                public class MyBean { }
                """
            )
        );
    }

    @Test
    void doNotRemoveOtherAnnotations() {
        // Other annotations on the class should remain untouched
        rewriteRun(
            java(JAKARTA_EJB_STUB),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Deprecated
                @Stateless(name = "MyBean")
                public class MyBean { }
                """,
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Deprecated
                @Stateless
                public class MyBean { }
                """
            )
        );
    }
}
