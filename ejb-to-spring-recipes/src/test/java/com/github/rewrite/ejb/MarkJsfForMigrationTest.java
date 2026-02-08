package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

class MarkJsfForMigrationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkJsfForMigration())
            .typeValidationOptions(TypeValidation.none())
            .cycles(1)
            .expectedCyclesThatMakeChanges(1)
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.faces-api", "jakarta.enterprise.cdi-api"));
    }

    @DocumentExample
    @Test
    void markClassWithViewScoped() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.faces.view.ViewScoped;
                import java.io.Serializable;

                @ViewScoped
                public class MyBackingBean implements Serializable {
                    private String name;

                    public String getName() { return name; }
                    public void setName(String name) { this.name = name; }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("CONFIGURATION")
                        .contains("@ViewScoped")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    /**
     * P0.1: @ConversationScoped without Conversation injection should be marked
     * with SEMANTIC_CHANGE category (not CONFIGURATION).
     */
    @Test
    void markClassWithConversationScopedSemanticChange() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.enterprise.context.ConversationScoped;
                import java.io.Serializable;

                @ConversationScoped
                public class WizardBean implements Serializable {
                    private int step;
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("SEMANTIC_CHANGE")
                        .contains("@ConversationScoped")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview")
                        .contains("ConversationScoped is mapped to session scope");
                    return after;
                })
            )
        );
    }

    /**
     * P0.1: @ConversationScoped with Conversation.begin() and end() calls
     * should include these in originalCode.
     */
    @Test
    void markClassWithConversationScopedAndExplicitLifecycle() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.enterprise.context.ConversationScoped;
                import jakarta.enterprise.context.Conversation;
                import jakarta.inject.Inject;
                import java.io.Serializable;

                @ConversationScoped
                public class WizardBean implements Serializable {
                    @Inject
                    private Conversation conversation;
                    private int step;

                    public void startWizard() {
                        conversation.begin();
                    }

                    public void finishWizard() {
                        conversation.end();
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("SEMANTIC_CHANGE")
                        .contains("explicit begin()/end() calls detected")
                        .contains("Conversation.begin()")
                        .contains("Conversation.end()")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    /**
     * P0.1: @ConversationScoped already marked with @NeedsReview should not be modified.
     */
    @Test
    void skipConversationScopedAlreadyMarked() {
        rewriteRun(
            spec -> spec.cycles(1).expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.enterprise.context.ConversationScoped;
                import java.io.Serializable;

                @NeedsReview(reason = "Already reviewed", category = NeedsReview.Category.SEMANTIC_CHANGE, originalCode = "@ConversationScoped", suggestedAction = "Done")
                @ConversationScoped
                public class AlreadyMarkedWizardBean implements Serializable {
                }
                """
            )
        );
    }

    /**
     * P0.1: @ConversationScoped with only Conversation injection (no begin/end calls)
     * should mention injection but no lifecycle calls.
     */
    @Test
    void markClassWithConversationScopedAndInjectionOnly() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.enterprise.context.ConversationScoped;
                import jakarta.enterprise.context.Conversation;
                import jakarta.inject.Inject;
                import java.io.Serializable;

                @ConversationScoped
                public class SimpleConversationBean implements Serializable {
                    @Inject
                    private Conversation conversation;

                    public String getConversationId() {
                        return conversation.getId();
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("SEMANTIC_CHANGE")
                        .contains("Conversation injection")
                        .doesNotContain("begin()/end() vorhanden")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    @Test
    void markClassUsingFacesContextInMethod() {
        // Test for FacesContext.getCurrentInstance() pattern (method-only usage)
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.faces.context.FacesContext;
                import jakarta.faces.application.FacesMessage;

                public class MessageHelper {
                    public void addMessage(String summary) {
                        FacesContext context = FacesContext.getCurrentInstance();
                        context.addMessage(null, new FacesMessage(summary));
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("CONFIGURATION")
                        .contains("FacesContext")
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

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.faces.view.ViewScoped;
                import java.io.Serializable;

                @NeedsReview(reason = "Already marked", category = NeedsReview.Category.CONFIGURATION, originalCode = "test", suggestedAction = "test")
                @ViewScoped
                public class AlreadyMarkedBean implements Serializable {
                }
                """
            )
        );
    }
}
