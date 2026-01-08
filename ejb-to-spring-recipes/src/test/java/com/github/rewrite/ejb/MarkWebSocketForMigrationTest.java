package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

class MarkWebSocketForMigrationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkWebSocketForMigration())
            .typeValidationOptions(TypeValidation.none())
            .cycles(1)
            .expectedCyclesThatMakeChanges(1)
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.websocket-api", "jakarta.websocket-client-api"));
    }

    @DocumentExample
    @Test
    void markClassWithServerEndpoint() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.websocket.OnMessage;
                import jakarta.websocket.OnOpen;
                import jakarta.websocket.Session;
                import jakarta.websocket.server.ServerEndpoint;

                @ServerEndpoint("/chat")
                public class ChatEndpoint {
                    @OnOpen
                    public void onOpen(Session session) {}

                    @OnMessage
                    public void onMessage(String message, Session session) {}
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("@ServerEndpoint")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    @Test
    void markClassWithClientEndpoint() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.websocket.ClientEndpoint;
                import jakarta.websocket.OnMessage;

                @ClientEndpoint
                public class WebSocketClient {
                    @OnMessage
                    public void onMessage(String message) {}
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("@ClientEndpoint")
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
                import jakarta.websocket.server.ServerEndpoint;

                @NeedsReview(reason = "Already marked", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "test", suggestedAction = "test")
                @ServerEndpoint("/test")
                public class AlreadyMarkedEndpoint {
                }
                """
            )
        );
    }
}
