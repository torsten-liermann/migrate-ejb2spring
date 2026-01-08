package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

class MarkArquillianTestsForMigrationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkArquillianTestsForMigration())
            .typeValidationOptions(TypeValidation.none())
            .cycles(1)
            .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void markArquillianExtensionTest() {
        rewriteRun(
            java(
                """
                package com.example;

                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.junit.jupiter.api.extension.ExtendWith;

                @ExtendWith(ArquillianExtension.class)
                public class MyArquillianTest {
                    // Test methods
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    @Test
    void markShrinkWrapWebArchiveTest() {
        rewriteRun(
            java(
                """
                package com.example;

                import org.jboss.arquillian.container.test.api.Deployment;
                import org.jboss.shrinkwrap.api.ShrinkWrap;
                import org.jboss.shrinkwrap.api.spec.WebArchive;

                public class DeploymentTest {

                    @Deployment
                    public static WebArchive createDeployment() {
                        return null; // ShrinkWrap.create(WebArchive.class, "test.war");
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    @Test
    void skipClassAlreadyMarked() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import org.jboss.arquillian.junit5.ArquillianExtension;
                import org.junit.jupiter.api.extension.ExtendWith;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @NeedsReview(reason = "Already marked", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "test", suggestedAction = "test")
                @ExtendWith(ArquillianExtension.class)
                public class AlreadyMarkedTest {
                }
                """
            )
        );
    }

    @Test
    void noOpForPlainJUnitTest() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import org.junit.jupiter.api.Test;

                public class PlainJUnitTest {

                    @Test
                    void testSomething() {
                        // Regular JUnit test without Arquillian
                    }
                }
                """
            )
        );
    }
}
