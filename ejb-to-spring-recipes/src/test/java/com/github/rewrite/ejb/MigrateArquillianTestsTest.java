package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link MigrateArquillianTests}.
 */
class MigrateArquillianTestsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateArquillianTests())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void migratesExtendWithArquillianExtension() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;

                @ExtendWith(ArquillianExtension.class)
                public class MyArquillianTest {
                    @Test
                    void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class MyArquillianTest {
                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void removesDeploymentMethod() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.container.test.api.Deployment;
                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.jboss.shrinkwrap.api.ShrinkWrap;
                import org.jboss.shrinkwrap.api.spec.WebArchive;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;

                @ExtendWith(ArquillianExtension.class)
                public class MyArquillianTest {

                    @Deployment
                    public static WebArchive createDeployment() {
                        return ShrinkWrap.create(WebArchive.class)
                            .addClass(MyArquillianTest.class);
                    }

                    @Test
                    void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class MyArquillianTest {

                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void preservesOtherAnnotations() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.junit.jupiter.api.Tag;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;

                @Tag("integration")
                @ExtendWith(ArquillianExtension.class)
                public class MyArquillianTest {
                    @Test
                    void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Tag;
                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @Tag("integration")
                @SpringBootTest
                public class MyArquillianTest {
                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotModifyCustomArquillianExtension() {
        // Protection test: MyArquillianExtension should NOT be treated as ArquillianExtension
        // This catches substring matching regressions
        rewriteRun(
            java(
                """
                package com.example.test;

                import com.example.MyArquillianExtension;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;

                @ExtendWith(MyArquillianExtension.class)
                public class CustomExtensionTest {
                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotModifyNonArquillianTests() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.junit.jupiter.api.Test;

                public class SimpleTest {
                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesRunWithArquillian() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit.Arquillian;
                import org.junit.Test;
                import org.junit.runner.RunWith;

                @RunWith(Arquillian.class)
                public class MyJUnit4ArquillianTest {
                    @Test
                    public void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class MyJUnit4ArquillianTest {
                    @Test
                    public void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void handlesNestedClassesWithDeploymentCorrectly() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.container.test.api.Deployment;
                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.jboss.shrinkwrap.api.ShrinkWrap;
                import org.jboss.shrinkwrap.api.spec.WebArchive;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;

                @ExtendWith(ArquillianExtension.class)
                public class OuterArquillianTest {

                    public static class InnerHelper {
                        public void help() {
                        }
                    }

                    @Deployment
                    public static WebArchive createDeployment() {
                        return ShrinkWrap.create(WebArchive.class);
                    }

                    @Test
                    void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class OuterArquillianTest {

                    public static class InnerHelper {
                        public void help() {
                        }
                    }

                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void removesArquillianFromMultipleExtensions() {
        // ArquillianExtension should be removed from array, leaving only MockitoExtension
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.junit.jupiter.MockitoExtension;

                @ExtendWith({ArquillianExtension.class, MockitoExtension.class})
                public class MyMultiExtensionTest {
                    @Test
                    void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                @ExtendWith(MockitoExtension.class)
                public class MyMultiExtensionTest {
                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void removesArquillianFromAssignmentFormArray() {
        // Test for value = {...} assignment form with ArquillianExtension not as first element
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.junit.jupiter.MockitoExtension;

                @ExtendWith(value = {MockitoExtension.class, ArquillianExtension.class})
                public class MyAssignmentFormTest {
                    @Test
                    void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                @ExtendWith(MockitoExtension.class)
                public class MyAssignmentFormTest {
                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void handlesRepeatableExtendWith() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.junit.jupiter.MockitoExtension;

                @ExtendWith(ArquillianExtension.class)
                @ExtendWith(MockitoExtension.class)
                public class MyRepeatableExtensionTest {
                    @Test
                    void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                @ExtendWith(MockitoExtension.class)
                public class MyRepeatableExtensionTest {
                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void removesDeploymentAndArquillianWhenSpringBootTestExists() {
        // Edge case: class already has @SpringBootTest but still has @Deployment method and ArquillianExtension
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.container.test.api.Deployment;
                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.jboss.shrinkwrap.api.ShrinkWrap;
                import org.jboss.shrinkwrap.api.spec.WebArchive;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                @ExtendWith({ArquillianExtension.class, MockitoExtension.class})
                public class PartiallyMigratedTest {

                    @Deployment
                    public static WebArchive createDeployment() {
                        return ShrinkWrap.create(WebArchive.class);
                    }

                    @Test
                    void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.mockito.junit.jupiter.MockitoExtension;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                @ExtendWith(MockitoExtension.class)
                public class PartiallyMigratedTest {

                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }
}
