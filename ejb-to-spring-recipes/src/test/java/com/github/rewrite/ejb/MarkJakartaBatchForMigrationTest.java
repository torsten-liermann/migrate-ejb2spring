package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;

class MarkJakartaBatchForMigrationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MarkJakartaBatchForMigration())
            .typeValidationOptions(TypeValidation.none())
            .cycles(1)
            .expectedCyclesThatMakeChanges(1)
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.batch-api"));
    }

    @DocumentExample
    @Test
    void markClassExtendingAbstractItemReader() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.batch.api.chunk.AbstractItemReader;

                public class MyItemReader extends AbstractItemReader {
                    @Override
                    public Object readItem() throws Exception {
                        return null;
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("extends AbstractItemReader")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    @Test
    void markClassImplementingJobListener() {
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.batch.api.listener.JobListener;

                public class MyJobListener implements JobListener {
                    @Override
                    public void beforeJob() throws Exception {}

                    @Override
                    public void afterJob() throws Exception {}
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("implements JobListener")
                        .contains("import com.github.rewrite.ejb.annotations.NeedsReview");
                    return after;
                })
            )
        );
    }

    @Test
    void markClassUsingBatchRuntimeInMethod() {
        // Test for BatchRuntime.getJobOperator() pattern (like UploadDirectoryScanner)
        rewriteRun(
            java(
                """
                package com.example;

                import jakarta.batch.runtime.BatchRuntime;
                import jakarta.batch.operations.JobOperator;
                import java.util.Properties;

                public class BatchScheduler {
                    public void runBatchJob() {
                        JobOperator jobOperator = BatchRuntime.getJobOperator();
                        jobOperator.start("myJob", new Properties());
                    }
                }
                """,
                source -> source.after(after -> {
                    assertThat(after)
                        .contains("@NeedsReview")
                        .contains("MANUAL_MIGRATION")
                        .contains("uses BatchRuntime")
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
                import jakarta.batch.api.chunk.AbstractItemReader;

                @NeedsReview(reason = "Already marked", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "test", suggestedAction = "test")
                public class AlreadyMarkedReader extends AbstractItemReader {
                    @Override
                    public Object readItem() throws Exception {
                        return null;
                    }
                }
                """
            )
        );
    }
}
