package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class RemoveLoggerProducerClassesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveLoggerProducerClasses())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api"));
    }

    @DocumentExample
    @Test
    void removesLoggerOnlyProducerClass() {
        // A class that ONLY produces Logger via InjectionPoint should be deleted
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class LoggerProducer {
                    @Produces
                    public Logger produceLogger(InjectionPoint injectionPoint) {
                        return Logger.getLogger(injectionPoint.getMember().getDeclaringClass().getName());
                    }
                }
                """,
                // Expected: File is deleted (null)
                (String) null
            )
        );
    }

    @Test
    void removesClassWithMultipleLoggerProducers() {
        // A class with multiple logger producer methods (but no other methods) should be deleted
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class LoggerFactory {
                    @Produces
                    public Logger produceLogger(InjectionPoint ip) {
                        return Logger.getLogger(ip.getMember().getDeclaringClass().getName());
                    }

                    @Produces
                    public Logger produceNamedLogger(InjectionPoint ip) {
                        return Logger.getLogger(ip.getBean().getBeanClass().getSimpleName());
                    }
                }
                """,
                (String) null
            )
        );
    }

    @Test
    void keepsClassWithOtherMethods() {
        // A class that has other methods besides logger producer should NOT be deleted
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class MixedProducer {
                    @Produces
                    public Logger produceLogger(InjectionPoint injectionPoint) {
                        return Logger.getLogger(injectionPoint.getMember().getDeclaringClass().getName());
                    }

                    public void someOtherMethod() {
                        // This method exists, so class should NOT be deleted
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsClassWithInstanceFields() {
        // A class that has instance fields should NOT be deleted
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class StatefulProducer {
                    private String prefix;

                    @Produces
                    public Logger produceLogger(InjectionPoint injectionPoint) {
                        return Logger.getLogger(prefix + injectionPoint.getMember().getDeclaringClass().getName());
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsClassWithNonLoggerProducer() {
        // A class that produces something other than Logger should NOT be deleted
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;

                public class ConfigProducer {
                    @Produces
                    public String produceConfig(InjectionPoint injectionPoint) {
                        return "config-" + injectionPoint.getMember().getName();
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsClassWithProducerWithoutInjectionPoint() {
        // A class with @Produces Logger but without InjectionPoint is NOT a logger producer class
        // (it's a regular bean producer)
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import java.util.logging.Logger;

                public class SimpleLoggerProvider {
                    @Produces
                    public Logger produceDefaultLogger() {
                        return Logger.getLogger("default");
                    }
                }
                """
            )
        );
    }

    @Test
    void allowsConstantsInLoggerProducerClass() {
        // Static final fields (constants) are OK - class should still be deleted
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class LoggerProducerWithConstants {
                    private static final String PREFIX = "APP";

                    @Produces
                    public Logger produceLogger(InjectionPoint injectionPoint) {
                        return Logger.getLogger(PREFIX + "." + injectionPoint.getMember().getDeclaringClass().getName());
                    }
                }
                """,
                (String) null
            )
        );
    }
}
