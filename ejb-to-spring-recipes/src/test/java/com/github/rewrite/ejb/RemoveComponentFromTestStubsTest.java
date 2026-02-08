package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for the simple RemoveComponentFromTestStubs recipe.
 *
 * Expected behavior:
 * - Removes @Component/@Service/@Repository from test stubs
 * - Adds @NeedsStubReview annotation for manual review
 * - Does NOT generate TestConfiguration
 * - Does NOT modify @Import annotations
 */
class RemoveComponentFromTestStubsTest implements RewriteTest {

    private static final String NEEDS_STUB_REVIEW_STUB =
        """
        package com.github.migration;

        import java.lang.annotation.*;

        @Target(ElementType.TYPE)
        @Retention(RetentionPolicy.SOURCE)
        @Documented
        public @interface NeedsStubReview {
            String value() default "";
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveComponentFromTestStubs())
            .parser(JavaParser.fromJavaVersion()
                .classpath("spring-context")
                .dependsOn(NEEDS_STUB_REVIEW_STUB));
    }

    @Test
    @DocumentExample
    void removesComponentAndAddsNeedsStubReview() {
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class ApplicationEventsStub {
                    public void onEvent(String event) {
                    }
                }
                """,
                """
                package org.example.test;

                import com.github.migration.NeedsStubReview;



                @NeedsStubReview("@Component removed - manual review required")
                public class ApplicationEventsStub {
                    public void onEvent(String event) {
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/ApplicationEventsStub.java")
            )
        );
    }

    @Test
    void removesServiceAndAddsNeedsStubReview() {
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Service;

                @Service
                public class UserServiceMock {
                    public String getUser(int id) {
                        return "Mock User";
                    }
                }
                """,
                """
                package org.example.test;

                import com.github.migration.NeedsStubReview;

                @NeedsStubReview("@Service removed - manual review required")
                public class UserServiceMock {
                    public String getUser(int id) {
                        return "Mock User";
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/UserServiceMock.java")
            )
        );
    }

    @Test
    void removesRepositoryAndAddsNeedsStubReview() {
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Repository;

                @Repository
                public class DataRepositoryFake {
                    public Object find(int id) {
                        return "fake";
                    }
                }
                """,
                """
                package org.example.test;

                import com.github.migration.NeedsStubReview;

                @NeedsStubReview("@Repository removed - manual review required")
                public class DataRepositoryFake {
                    public Object find(int id) {
                        return "fake";
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/DataRepositoryFake.java")
            )
        );
    }

    @Test
    void doesNotModifyMainSourceClasses() {
        rewriteRun(
            java(
                """
                package org.example.app;

                import org.springframework.stereotype.Component;

                @Component
                public class ApplicationEventsStub {
                    public void onEvent(String event) {
                    }
                }
                """,
                spec -> spec.path("src/main/java/org/example/app/ApplicationEventsStub.java")
            )
        );
    }

    @Test
    void doesNotModifyTestClassWithoutStubPattern() {
        rewriteRun(
            java(
                """
                package org.example.test;

                import org.springframework.stereotype.Component;

                @Component
                public class TestHelper {
                    public void help() {
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/TestHelper.java")
            )
        );
    }

    @Test
    void doesNotAddDuplicateNeedsStubReview() {
        rewriteRun(
            java(
                """
                package org.example.test;

                import com.github.migration.NeedsStubReview;
                import org.springframework.stereotype.Component;

                @Component
                @NeedsStubReview("Already reviewed")
                public class OldServiceStub {
                    public void doSomething() {
                    }
                }
                """,
                """
                package org.example.test;

                import com.github.migration.NeedsStubReview;


                @NeedsStubReview("Already reviewed")
                public class OldServiceStub {
                    public void doSomething() {
                    }
                }
                """,
                spec -> spec.path("src/test/java/org/example/test/OldServiceStub.java")
            )
        );
    }
}
