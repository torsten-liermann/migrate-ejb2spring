package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.xml.Assertions.xml;

class GenerateSpringBatchConfigFromJslTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new GenerateSpringBatchConfigFromJsl())
            .parser(JavaParser.fromJavaVersion())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void generatesBatchConfigFromJobXml() {
        rewriteRun(
            java(
                """
                package com.example.batch;

                public class Marker {
                }
                """,
                spec -> spec.path("src/main/java/com/example/batch/Marker.java")
            ),
            xml(
                """
                <?xml version=\"1.0\" encoding=\"UTF-8\"?>
                <job id=\"importJob\" xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\">
                    <step id=\"importStep\">
                        <chunk item-count=\"10\">
                            <reader ref=\"csvReader\"/>
                            <processor ref=\"customerProcessor\"/>
                            <writer ref=\"jpaWriter\"/>
                        </chunk>
                    </step>
                </job>
                """,
                spec -> spec.path("src/main/resources/META-INF/batch-jobs/importJob.xml")
            ),
            java(
                null,
                """
                package com.example.batch;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.batch.core.Job;
                import org.springframework.batch.core.Step;
                import org.springframework.batch.core.job.builder.JobBuilder;
                import org.springframework.batch.core.repository.JobRepository;
                import org.springframework.batch.core.step.builder.StepBuilder;
                import org.springframework.batch.core.step.tasklet.Tasklet;
                import org.springframework.batch.item.ItemProcessor;
                import org.springframework.batch.item.ItemReader;
                import org.springframework.batch.item.ItemWriter;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.transaction.PlatformTransactionManager;

                @NeedsReview(reason = \"Generated from JSL batch job XML; verify Spring Batch mapping\", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = \"src/main/resources/META-INF/batch-jobs/importJob.xml\", suggestedAction = \"Review job flow, chunk settings, listeners, and transaction semantics.\")
                @Configuration
                public class BatchJobsConfiguration {

                    @Bean
                    public Job importJob(JobRepository jobRepository, Step importJob_importStep) {
                        return new JobBuilder(\"importJob\", jobRepository)
                            .start(importJob_importStep)
                            .build();
                    }

                    @Bean
                    public Step importJob_importStep(JobRepository jobRepository, PlatformTransactionManager transactionManager, @Qualifier(\"csvReader\") ItemReader<Object> csvReader, @Qualifier(\"customerProcessor\") ItemProcessor<Object, Object> customerProcessor, @Qualifier(\"jpaWriter\") ItemWriter<Object> jpaWriter) {
                        return new StepBuilder(\"importStep\", jobRepository)
                            .<Object, Object>chunk(10, transactionManager)
                            .reader(csvReader)
                            .processor(customerProcessor)
                            .writer(jpaWriter)
                            .build();
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/batch/BatchJobsConfiguration.java")
            )
        );
    }

    @Test
    void marksTransitionFlowsAsComplex() {
        rewriteRun(
            java(
                """
                package com.example.batch;

                public class Marker {
                }
                """,
                spec -> spec.path("src/main/java/com/example/batch/Marker.java")
            ),
            xml(
                """
                <?xml version=\"1.0\" encoding=\"UTF-8\"?>
                <job id=\"flowJob\" xmlns=\"http://xmlns.jcp.org/xml/ns/javaee\">
                    <step id=\"firstStep\" next=\"secondStep\">
                        <batchlet ref=\"firstBatchlet\"/>
                    </step>
                    <step id=\"secondStep\">
                        <batchlet ref=\"secondBatchlet\"/>
                    </step>
                </job>
                """,
                spec -> spec.path("src/main/resources/META-INF/batch-jobs/flowJob.xml")
            ),
            java(
                null,
                """
                package com.example.batch;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.batch.core.Job;
                import org.springframework.batch.core.Step;
                import org.springframework.batch.core.job.builder.JobBuilder;
                import org.springframework.batch.core.repository.JobRepository;
                import org.springframework.batch.core.step.builder.StepBuilder;
                import org.springframework.batch.core.step.tasklet.Tasklet;
                import org.springframework.batch.item.ItemProcessor;
                import org.springframework.batch.item.ItemReader;
                import org.springframework.batch.item.ItemWriter;
                import org.springframework.beans.factory.annotation.Qualifier;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;
                import org.springframework.transaction.PlatformTransactionManager;

                @NeedsReview(reason = \"Generated from JSL batch job XML; verify Spring Batch mapping\", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = \"src/main/resources/META-INF/batch-jobs/flowJob.xml\", suggestedAction = \"Review job flow, chunk settings, listeners, and transaction semantics.\")
                @Configuration
                public class BatchJobsConfiguration {

                    @Bean
                    public Job flowJob(JobRepository jobRepository, Step flowJob_firstStep) {
                        return new JobBuilder(\"flowJob\", jobRepository)
                            .start(flowJob_firstStep)
                            // TODO: Job uses transitions (next/stop/end/fail). Map flow explicitly.
                            .build();
                    }

                    @Bean
                    public Step flowJob_firstStep(JobRepository jobRepository, PlatformTransactionManager transactionManager, @Qualifier(\"firstBatchlet\") Tasklet firstBatchlet) {
                        return new StepBuilder(\"firstStep\", jobRepository)
                            .tasklet(firstBatchlet, transactionManager)
                            .build();
                    }

                    // TODO: Step contains transitions or partition/listener/properties elements; verify mapping.

                    @Bean
                    public Step flowJob_secondStep(JobRepository jobRepository, PlatformTransactionManager transactionManager, @Qualifier(\"secondBatchlet\") Tasklet secondBatchlet) {
                        return new StepBuilder(\"secondStep\", jobRepository)
                            .tasklet(secondBatchlet, transactionManager)
                            .build();
                    }

                    // TODO: Job flow contains transitions or split/flow/decision elements; verify mapping.

                }
                """,
                spec -> spec.path("src/main/java/com/example/batch/BatchJobsConfiguration.java")
            )
        );
    }
}
