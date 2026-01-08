package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateCdiProducerToSpringBeanTest implements RewriteTest {

    private static final String SPRING_INJECTION_POINT_STUB =
        "package org.springframework.beans.factory;" +
        "import java.lang.reflect.Member;" +
        "import java.lang.reflect.Type;" +
        "import java.lang.reflect.AnnotatedElement;" +
        "import org.springframework.core.MethodParameter;" +
        "public interface InjectionPoint {" +
        "  Member getMember();" +
        "  MethodParameter getMethodParameter();" +
        "  java.lang.reflect.Field getField();" +
        "  AnnotatedElement getAnnotatedElement();" +
        "  Type getType();" +
        "}";

    private static final String NEEDS_REVIEW_STUB =
        "package com.github.rewrite.ejb.annotations;" +
        "import java.lang.annotation.*;" +
        "@Documented @Retention(RetentionPolicy.SOURCE) " +
        "@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER}) " +
        "public @interface NeedsReview {" +
        "  String reason();" +
        "  Category category();" +
        "  String originalCode() default \"\";" +
        "  String suggestedAction() default \"\";" +
        "  enum Category { REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING, CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, OTHER }" +
        "}";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateCdiProducerToSpringBean())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.enterprise.cdi-api", "spring-context", "spring-beans")
                .dependsOn(SPRING_INJECTION_POINT_STUB, NEEDS_REVIEW_STUB));
    }

    @Test
    void migrateSimpleProducer() {
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;

                public class ProducerConfig {
                    @Produces
                    public String name() {
                        return "x";
                    }
                }
                """,
                """
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class ProducerConfig {
                    @Bean
                    public String name() {
                        return "x";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateInjectionPointProducer() {
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class LoggerProducer {
                    @Produces
                    public Logger logger(InjectionPoint ip) {
                        return Logger.getLogger(ip.getMember().getDeclaringClass().getName());
                    }
                }
                """,
                """
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                import java.util.logging.Logger;

                @Configuration
                public class LoggerProducer {
                    @Bean
                    public Logger logger(org.springframework.beans.factory.InjectionPoint ip) {
                        return Logger.getLogger(ip.getMember().getDeclaringClass().getName());
                    }
                }
                """
            )
        );
    }

    @Test
    void marksNeedsReviewForUnsupportedInjectionPointUsage() {
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;

                public class TypeProducer {
                    @Produces
                    public String type(InjectionPoint ip) {
                        return ip.getType().getTypeName();
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class TypeProducer {
                    @NeedsReview(reason = "CDI InjectionPoint uses API not fully compatible with Spring. Verify usage of InjectionPoint methods.",  category = NeedsReview.Category.CDI_FEATURE,  originalCode = "@Produces",  suggestedAction = "Review InjectionPoint API differences and adjust as needed")
                    @Bean
                    public String type(org.springframework.beans.factory.InjectionPoint ip) {
                        return ip.getType().getTypeName();
                    }
                }
                """
            )
        );
    }
}
