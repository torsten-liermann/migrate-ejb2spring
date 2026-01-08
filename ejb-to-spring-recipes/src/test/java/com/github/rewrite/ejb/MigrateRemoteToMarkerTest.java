package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

/**
 * Tests for MigrateRemoteToMarker recipe.
 * <p>
 * Covers:
 * <ul>
 *   <li>@NeedsReview annotation added to @Remote interfaces</li>
 *   <li>@NeedsReview annotation added to @Remote classes</li>
 *   <li>No change if already has @NeedsReview</li>
 *   <li>No change for non-@Remote types</li>
 * </ul>
 */
class MigrateRemoteToMarkerTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateRemoteToMarker())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api"))
            .typeValidationOptions(TypeValidation.none());
    }

    @DocumentExample
    @Test
    void addsNeedsReviewToRemoteInterface() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface CustomerService {
                    Customer find(Long id);
                    void update(Customer customer);
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("NeedsReview.Category.REMOTE_ACCESS")
                        .contains("Remote EJB interface requires manual migration to REST API")
                        // @Remote is removed (only originalCode="@Remote" remains in @NeedsReview)
                        .doesNotContain("import jakarta.ejb.Remote") // import removed
                        .doesNotContain("\n@Remote\n") // annotation itself removed
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    @Test
    void addsNeedsReviewToRemoteClass() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public class LegacyRemoteBean {
                    public String getData() {
                        return "data";
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("NeedsReview.Category.REMOTE_ACCESS")
                        .contains("Remote EJB class requires manual migration to REST API")
                        // @Remote is removed (only originalCode="@Remote" remains in @NeedsReview)
                        .doesNotContain("import jakarta.ejb.Remote")
                        .doesNotContain("\n@Remote\n")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    @Test
    void javaxRemoteAlsoWorks() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(1)
                .parser(JavaParser.fromJavaVersion()
                    .classpath("javax.ejb-api")),
            java(
                """
                package com.example;

                import javax.ejb.Remote;

                @Remote
                public interface LegacyService {
                    void doLegacy();
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("NeedsReview.Category.REMOTE_ACCESS")
                        .contains("Remote EJB interface requires manual migration to REST API")
                        // @Remote is removed (only originalCode="@Remote" remains in @NeedsReview)
                        .doesNotContain("import javax.ejb.Remote")
                        .doesNotContain("\n@Remote\n")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    @Test
    void noChangeIfAlreadyHasNeedsReview() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.ejb.Remote;

                @NeedsReview(reason = "Already marked", category = NeedsReview.Category.REMOTE_ACCESS, originalCode = "@Remote", suggestedAction = "Do something")
                @Remote
                public interface AlreadyMarkedService {
                    void doSomething();
                }
                """
                // No changes - already has @NeedsReview
            )
        );
    }

    @Test
    void noChangeForNonRemoteInterface() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                public interface RegularService {
                    void doSomething();
                }
                """
                // No changes - not a @Remote interface
            )
        );
    }

    @Test
    void noChangeForStatelessWithoutRemote() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import jakarta.ejb.Stateless;

                @Stateless
                public class StatelessBean {
                    public void doSomething() {
                    }
                }
                """
                // No changes - @Stateless without @Remote
            )
        );
    }
}
