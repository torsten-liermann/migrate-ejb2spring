package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateAsyncResultToCompletableFutureTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateAsyncResultToCompletableFuture())
            .parser(JavaParser.fromJavaVersion()
                .classpath("javax.ejb-api", "jakarta.jakartaee-api"));
    }

    @DocumentExample
    @Test
    void migrateJavaxAsyncResult() {
        rewriteRun(
            java(
                """
                import javax.ejb.AsyncResult;
                import java.util.concurrent.Future;

                public class ComputeService {
                    public Future<Integer> compute(int value) {
                        int result = value * 2;
                        return new AsyncResult<>(result);
                    }
                }
                """,
                """
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Future;

                public class ComputeService {
                    public Future<Integer> compute(int value) {
                        int result = value * 2;
                        return CompletableFuture.completedFuture(result);
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateJakartaAsyncResult() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.AsyncResult;
                import java.util.concurrent.Future;

                public class DataService {
                    public Future<String> fetchData(String id) {
                        String data = "data-" + id;
                        return new AsyncResult<>(data);
                    }
                }
                """,
                """
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Future;

                public class DataService {
                    public Future<String> fetchData(String id) {
                        String data = "data-" + id;
                        return CompletableFuture.completedFuture(data);
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateAsyncResultWithStringLiteral() {
        rewriteRun(
            java(
                """
                import javax.ejb.AsyncResult;
                import java.util.concurrent.Future;

                public class MessageService {
                    public Future<String> getMessage() {
                        return new AsyncResult<>("Hello World");
                    }
                }
                """,
                """
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Future;

                public class MessageService {
                    public Future<String> getMessage() {
                        return CompletableFuture.completedFuture("Hello World");
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateAsyncResultWithNull() {
        rewriteRun(
            java(
                """
                import javax.ejb.AsyncResult;
                import java.util.concurrent.Future;

                public class VoidService {
                    public Future<Void> doWork() {
                        // work done
                        return new AsyncResult<>(null);
                    }
                }
                """,
                """
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Future;

                public class VoidService {
                    public Future<Void> doWork() {
                        // work done
                        return CompletableFuture.completedFuture(null);
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateAsyncResultWithMethodCall() {
        rewriteRun(
            java(
                """
                import javax.ejb.AsyncResult;
                import java.util.concurrent.Future;

                public class ProcessService {
                    public Future<String> process(String input) {
                        return new AsyncResult<>(processInternal(input));
                    }

                    private String processInternal(String input) {
                        return input.toUpperCase();
                    }
                }
                """,
                """
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Future;

                public class ProcessService {
                    public Future<String> process(String input) {
                        return CompletableFuture.completedFuture(processInternal(input));
                    }

                    private String processInternal(String input) {
                        return input.toUpperCase();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMultipleAsyncResults() {
        rewriteRun(
            java(
                """
                import javax.ejb.AsyncResult;
                import java.util.concurrent.Future;

                public class MultiService {
                    public Future<Integer> getNumber() {
                        return new AsyncResult<>(42);
                    }

                    public Future<String> getText() {
                        return new AsyncResult<>("text");
                    }

                    public Future<Boolean> getFlag() {
                        return new AsyncResult<>(true);
                    }
                }
                """,
                """
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Future;

                public class MultiService {
                    public Future<Integer> getNumber() {
                        return CompletableFuture.completedFuture(42);
                    }

                    public Future<String> getText() {
                        return CompletableFuture.completedFuture("text");
                    }

                    public Future<Boolean> getFlag() {
                        return CompletableFuture.completedFuture(true);
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeWhenNoAsyncResult() {
        rewriteRun(
            java(
                """
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Future;

                public class ModernService {
                    public Future<String> getData() {
                        return CompletableFuture.completedFuture("data");
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateAsyncResultWithNewObject() {
        rewriteRun(
            java(
                """
                import javax.ejb.AsyncResult;
                import java.util.concurrent.Future;
                import java.util.ArrayList;
                import java.util.List;

                public class ListService {
                    public Future<List<String>> getItems() {
                        List<String> items = new ArrayList<>();
                        items.add("item1");
                        return new AsyncResult<>(items);
                    }
                }
                """,
                """
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Future;
                import java.util.ArrayList;
                import java.util.List;

                public class ListService {
                    public Future<List<String>> getItems() {
                        List<String> items = new ArrayList<>();
                        items.add("item1");
                        return CompletableFuture.completedFuture(items);
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateAsyncResultInConditional() {
        rewriteRun(
            java(
                """
                import javax.ejb.AsyncResult;
                import java.util.concurrent.Future;

                public class ConditionalService {
                    public Future<String> process(boolean flag) {
                        if (flag) {
                            return new AsyncResult<>("yes");
                        }
                        return new AsyncResult<>("no");
                    }
                }
                """,
                """
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Future;

                public class ConditionalService {
                    public Future<String> process(boolean flag) {
                        if (flag) {
                            return CompletableFuture.completedFuture("yes");
                        }
                        return CompletableFuture.completedFuture("no");
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateAsyncResultTypeReference() {
        // Test that AsyncResult type references (not just new AsyncResult) are also migrated
        rewriteRun(
            java(
                """
                import javax.ejb.AsyncResult;
                import java.util.concurrent.Future;

                public class TypeRefService {
                    public AsyncResult<String> createResult(String value) {
                        AsyncResult<String> result = new AsyncResult<>(value);
                        return result;
                    }
                }
                """,
                """
                import java.util.concurrent.CompletableFuture;
                import java.util.concurrent.Future;

                public class TypeRefService {
                    public CompletableFuture<String> createResult(String value) {
                        CompletableFuture<String> result = CompletableFuture.completedFuture(value);
                        return result;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateJakartaAsyncResultTypeReference() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.AsyncResult;

                public class JakartaTypeRefService {
                    private AsyncResult<Integer> lastResult;

                    public void setResult(int value) {
                        lastResult = new AsyncResult<>(value);
                    }

                    public AsyncResult<Integer> getLastResult() {
                        return lastResult;
                    }
                }
                """,
                """
                import java.util.concurrent.CompletableFuture;

                public class JakartaTypeRefService {
                    private CompletableFuture<Integer> lastResult;

                    public void setResult(int value) {
                        lastResult = CompletableFuture.completedFuture(value);
                    }

                    public CompletableFuture<Integer> getLastResult() {
                        return lastResult;
                    }
                }
                """
            )
        );
    }
}
