/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Tests for Arquillian to Spring Boot Test migration
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * Tests for {@link MigrateArquillianToSpringBootTest}.
 */
class MigrateArquillianToSpringBootTestTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateArquillianToSpringBootTest())
            .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void migrateSimpleArquillianTest() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit.Arquillian;
                import org.junit.Test;
                import org.junit.runner.RunWith;

                @RunWith(Arquillian.class)
                public class MyTest {
                    @Test
                    public void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class MyTest {
                    @Test
                    public void testSomething() {
                    }
                }
                """
            )
        );
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
    void removesDeploymentMethodAndAddsNeedsReview() {
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
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@SpringBootTest")
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("@Deployment method removed")
                        // The @Deployment method should be removed
                        .doesNotContain("createDeployment")
                        .doesNotContain("public static WebArchive")
                        // Imports should be removed
                        .doesNotContain("import org.jboss.arquillian.container.test.api.Deployment")
                        .doesNotContain("import org.jboss.shrinkwrap");
                    // Note: @Deployment appears in the originalCode attribute of @NeedsReview
                    // which is expected and correct
                    return after;
                })
            )
        );
    }

    @Test
    void migratesArquillianResourceToAutowired() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit.Arquillian;
                import org.jboss.arquillian.test.api.ArquillianResource;
                import org.junit.Test;
                import org.junit.runner.RunWith;
                import java.net.URL;

                @RunWith(Arquillian.class)
                public class ResourceTest {

                    @ArquillianResource
                    private URL deploymentUrl;

                    @Test
                    public void testSomething() {
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@SpringBootTest")
                        .contains("@Autowired")
                        .contains("@NeedsReview")
                        .contains("@ArquillianResource replaced with @Autowired")
                        // The annotation on the field should be replaced
                        .doesNotContain("import org.jboss.arquillian.test.api.ArquillianResource");
                    // Note: @ArquillianResource appears in the originalCode attribute of @NeedsReview
                    // which is expected and correct
                    return after;
                })
            )
        );
    }

    @Test
    void migratesJUnit4ToJUnit5() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit.Arquillian;
                import org.junit.After;
                import org.junit.Before;
                import org.junit.Test;
                import org.junit.runner.RunWith;

                @RunWith(Arquillian.class)
                public class JUnit4Test {

                    @Before
                    public void setUp() {
                    }

                    @After
                    public void tearDown() {
                    }

                    @Test
                    public void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.AfterEach;
                import org.junit.jupiter.api.BeforeEach;
                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class JUnit4Test {

                    @BeforeEach
                    public void setUp() {
                    }

                    @AfterEach
                    public void tearDown() {
                    }

                    @Test
                    public void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesJUnit4BeforeClassAndAfterClass() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit.Arquillian;
                import org.junit.AfterClass;
                import org.junit.BeforeClass;
                import org.junit.Test;
                import org.junit.runner.RunWith;

                @RunWith(Arquillian.class)
                public class StaticSetupTest {

                    @BeforeClass
                    public static void setUpClass() {
                    }

                    @AfterClass
                    public static void tearDownClass() {
                    }

                    @Test
                    public void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.AfterAll;
                import org.junit.jupiter.api.BeforeAll;
                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class StaticSetupTest {

                    @BeforeAll
                    public static void setUpClass() {
                    }

                    @AfterAll
                    public static void tearDownClass() {
                    }

                    @Test
                    public void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesIgnoreToDisabled() {
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit.Arquillian;
                import org.junit.Ignore;
                import org.junit.Test;
                import org.junit.runner.RunWith;

                @RunWith(Arquillian.class)
                public class IgnoredTest {

                    @Ignore("Not implemented yet")
                    @Test
                    public void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Disabled;
                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class IgnoredTest {

                    @Disabled("Not implemented yet")
                    @Test
                    public void testSomething() {
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
                public class TaggedTest {
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
                public class TaggedTest {
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
    void handlesCombinedScenario() {
        // Test combining @Deployment removal, @ArquillianResource to @Autowired, and JUnit 4 to 5
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.container.test.api.Deployment;
                import org.jboss.arquillian.junit.Arquillian;
                import org.jboss.arquillian.test.api.ArquillianResource;
                import org.jboss.shrinkwrap.api.ShrinkWrap;
                import org.jboss.shrinkwrap.api.spec.WebArchive;
                import org.junit.Before;
                import org.junit.Test;
                import org.junit.runner.RunWith;
                import java.net.URL;

                @RunWith(Arquillian.class)
                public class FullMigrationTest {

                    @ArquillianResource
                    private URL deploymentUrl;

                    @Deployment
                    public static WebArchive createDeployment() {
                        return ShrinkWrap.create(WebArchive.class);
                    }

                    @Before
                    public void setUp() {
                    }

                    @Test
                    public void testSomething() {
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@SpringBootTest")
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("@Autowired")
                        .contains("@BeforeEach")
                        .contains("import org.junit.jupiter.api.Test")
                        .contains("import org.junit.jupiter.api.BeforeEach")
                        .doesNotContain("@RunWith")
                        .doesNotContain("createDeployment") // @Deployment method removed
                        .doesNotContain("import org.jboss.arquillian.test.api.ArquillianResource")
                        .doesNotContain("import org.jboss.shrinkwrap")
                        .doesNotContain("import org.junit.Test")
                        .doesNotContain("import org.junit.Before");
                    // Note: @ArquillianResource and @Deployment appear in the originalCode attribute
                    // of @NeedsReview which is expected and correct
                    return after;
                })
            )
        );
    }

    @Test
    void handlesNestedClassesCorrectly() {
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
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@SpringBootTest")
                        .contains("@NeedsReview")
                        .contains("public static class InnerHelper")
                        // The @Deployment method should be removed
                        .doesNotContain("createDeployment")
                        .doesNotContain("public static WebArchive");
                    // Note: @Deployment appears in the originalCode attribute of @NeedsReview
                    // which is expected and correct
                    return after;
                })
            )
        );
    }

    @Test
    void isIdempotent() {
        // Running the recipe twice should produce the same result
        rewriteRun(
            spec -> spec.cycles(2).expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit.Arquillian;
                import org.junit.Test;
                import org.junit.runner.RunWith;

                @RunWith(Arquillian.class)
                public class IdempotentTest {
                    @Test
                    public void testSomething() {
                    }
                }
                """,
                """
                package com.example.test;

                import org.junit.jupiter.api.Test;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                public class IdempotentTest {
                    @Test
                    public void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotAddDuplicateSpringBootTest() {
        // If @SpringBootTest already exists, don't add another one
        rewriteRun(
            java(
                """
                package com.example.test;

                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;
                import org.springframework.boot.test.context.SpringBootTest;

                @SpringBootTest
                @ExtendWith(ArquillianExtension.class)
                public class AlreadySpringBootTest {
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
                public class AlreadySpringBootTest {
                    @Test
                    void testSomething() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotAddDuplicateNeedsReview() {
        // If @NeedsReview already exists, don't add another one
        rewriteRun(
            java(
                """
                package com.example.test;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.jboss.arquillian.container.test.api.Deployment;
                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.jboss.shrinkwrap.api.ShrinkWrap;
                import org.jboss.shrinkwrap.api.spec.WebArchive;
                import org.junit.jupiter.api.Test;
                import org.junit.jupiter.api.extension.ExtendWith;

                import static com.github.rewrite.ejb.annotations.NeedsReview.Category.MANUAL_MIGRATION;

                @NeedsReview(reason = "Already reviewed", category = MANUAL_MIGRATION, originalCode = "", suggestedAction = "")
                @ExtendWith(ArquillianExtension.class)
                public class AlreadyMarkedTest {

                    @Deployment
                    public static WebArchive createDeployment() {
                        return ShrinkWrap.create(WebArchive.class);
                    }

                    @Test
                    void testSomething() {
                    }
                }
                """,
                source -> source.after(after -> {
                    // Should have exactly one @NeedsReview (the original one)
                    int count = 0;
                    int idx = 0;
                    while ((idx = after.indexOf("@NeedsReview", idx)) >= 0) {
                        count++;
                        idx++;
                    }
                    assertThat(count).isEqualTo(1);
                    assertThat(after)
                        .contains("@SpringBootTest")
                        .contains("Already reviewed")
                        // The @Deployment method should be removed
                        .doesNotContain("createDeployment")
                        .doesNotContain("public static WebArchive");
                    return after;
                })
            )
        );
    }
}
