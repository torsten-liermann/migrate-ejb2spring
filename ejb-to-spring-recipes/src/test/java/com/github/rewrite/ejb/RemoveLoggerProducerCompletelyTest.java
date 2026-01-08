package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * Tests for RemoveLoggerProducerCompletely recipe.
 * <p>
 * This recipe should work with ANY class name that matches the logger-producer pattern
 * (not just "LoggerProducer"). Tests verify this with various class names.
 */
class RemoveLoggerProducerCompletelyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RemoveLoggerProducerCompletely())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api"));
    }

    @DocumentExample
    @Test
    void removesLoggerProducerClassAndReferences() {
        // Standard case: LoggerProducer class + reference in another class
        rewriteRun(
            // The logger producer class - should be deleted
            java(
                """
                package org.example.logging;

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
                (String) null // Deleted
            ),
            // A class referencing the logger producer - should have import removed
            java(
                """
                package org.example.test;

                import org.example.logging.LoggerProducer;

                public class Deployments {
                    public void setup() {
                        // Reference to LoggerProducer exists via import
                    }
                }
                """,
                """
                package org.example.test;

                public class Deployments {
                    public void setup() {
                        // Reference to LoggerProducer exists via import
                    }
                }
                """
            )
        );
    }

    @Test
    void removesTraceProducerClass() {
        // Different class name: TraceProducer
        rewriteRun(
            java(
                """
                package com.company.util;

                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class TraceProducer {
                    @Produces
                    public Logger createTraceLogger(InjectionPoint ip) {
                        return Logger.getLogger(ip.getMember().getDeclaringClass().getName());
                    }
                }
                """,
                (String) null // Deleted
            ),
            java(
                """
                package com.company.test;

                import com.company.util.TraceProducer;

                public class TestSetup {
                    // Reference to TraceProducer should be removed
                }
                """,
                """
                package com.company.test;

                public class TestSetup {
                    // Reference to TraceProducer should be removed
                }
                """
            )
        );
    }

    @Test
    void removesMyLoggerFactoryClass() {
        // Different class name: MyLoggerFactory
        rewriteRun(
            java(
                """
                package com.github.infrastructure;

                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class MyLoggerFactory {
                    private static final String PREFIX = "APP";

                    @Produces
                    public Logger getLogger(InjectionPoint injectionPoint) {
                        return Logger.getLogger(PREFIX + "." +
                            injectionPoint.getMember().getDeclaringClass().getName());
                    }
                }
                """,
                (String) null // Deleted - static final constants are allowed
            )
        );
    }

    @Test
    void removesAddClassInMethodChain() {
        // Test method chain: archive.addPackage(...).addClass(Deleted.class) -> archive.addPackage(...)
        // Uses a dummy Archive class to avoid ShrinkWrap classpath issues
        rewriteRun(
            // Logger producer class - will be deleted
            java(
                """
                package org.example.logging;

                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class LogProducer {
                    @Produces
                    public Logger produce(InjectionPoint ip) {
                        return Logger.getLogger(ip.getMember().getDeclaringClass().getName());
                    }
                }
                """,
                (String) null
            ),
            // Dummy Archive class for testing
            java(
                """
                package org.example.archive;

                public class Archive {
                    public Archive addPackage(String pkg) { return this; }
                    public Archive addClass(Class<?> clazz) { return this; }
                }
                """
            ),
            // Deployment class with method chain .addClass() at the end
            java(
                """
                package org.example.test;

                import org.example.logging.LogProducer;
                import org.example.archive.Archive;

                public class DeploymentHelper {
                    public Archive createArchive() {
                        return new Archive().addPackage("x").addClass(LogProducer.class);
                    }
                }
                """,
                // Note: Import removal merges package/import blank line (acceptable formatting quirk)
                """
                package org.example.test;
                import org.example.archive.Archive;

                public class DeploymentHelper {
                    public Archive createArchive() {
                        return new Archive().addPackage("x");
                    }
                }
                """
            )
        );
    }

    @Test
    void removesStandaloneAddClassStatement() {
        // Test standalone statement: archive.addClass(Deleted.class); -> statement removed
        rewriteRun(
            // Logger producer class - will be deleted
            java(
                """
                package org.example.logging;

                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class LogProducer {
                    @Produces
                    public Logger produce(InjectionPoint ip) {
                        return Logger.getLogger(ip.getMember().getDeclaringClass().getName());
                    }
                }
                """,
                (String) null
            ),
            // Dummy Archive class for testing
            java(
                """
                package org.example.archive;

                public class Archive {
                    public Archive addPackage(String pkg) { return this; }
                    public Archive addClass(Class<?> clazz) { return this; }
                }
                """
            ),
            // Deployment class with standalone addClass statement
            java(
                """
                package org.example.test;

                import org.example.logging.LogProducer;
                import org.example.archive.Archive;

                public class DeploymentSetup {
                    public void configure(Archive archive) {
                        archive.addPackage("other");
                        archive.addClass(LogProducer.class);
                        archive.addPackage("more");
                    }
                }
                """,
                // Note: Import removal merges package/import blank line (acceptable formatting quirk)
                """
                package org.example.test;
                import org.example.archive.Archive;

                public class DeploymentSetup {
                    public void configure(Archive archive) {
                        archive.addPackage("other");
                        archive.addPackage("more");
                    }
                }
                """
            )
        );
    }

    @Test
    void removesProducerWithPrivateHelperMethod() {
        // CargoTracker pattern: Logger producer with private helper method
        // Private helpers should NOT prevent deletion (they're internal implementation detail)
        rewriteRun(
            java(
                """
                package org.eclipse.cargotracker.infrastructure.logging;

                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class LoggerProducer {
                    private static final long serialVersionUID = 1L;

                    @Produces
                    public Logger produceLogger(InjectionPoint injectionPoint) {
                        String loggerName = extractLoggerName(injectionPoint);
                        return Logger.getLogger(loggerName);
                    }

                    private String extractLoggerName(InjectionPoint injectionPoint) {
                        if (injectionPoint.getBean() == null) {
                            return injectionPoint.getMember().getDeclaringClass().getName();
                        }
                        if (injectionPoint.getBean().getName() == null) {
                            return injectionPoint.getBean().getBeanClass().getName();
                        }
                        return injectionPoint.getBean().getName();
                    }
                }
                """,
                (String) null // Should be deleted - private helper is allowed
            )
        );
    }

    @Test
    void keepsClassWithPublicOtherMethods() {
        // A class that has PUBLIC other methods besides logger producer should NOT be deleted
        // (other code might depend on those methods)
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
                        // This PUBLIC method exists, so class should NOT be deleted
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsClassWithProtectedOtherMethods() {
        // A class that has PROTECTED other methods should NOT be deleted
        // (subclasses might depend on those methods)
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class ExtensibleProducer {
                    @Produces
                    public Logger produceLogger(InjectionPoint injectionPoint) {
                        return Logger.getLogger(getPrefix() + injectionPoint.getMember().getDeclaringClass().getName());
                    }

                    protected String getPrefix() {
                        // This PROTECTED method exists, so class should NOT be deleted
                        return "APP.";
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsClassWithPackagePrivateOtherMethods() {
        // A class that has package-private other methods should NOT be deleted
        // (same-package code might depend on those methods)
        rewriteRun(
            java(
                """
                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class PackageProducer {
                    @Produces
                    public Logger produceLogger(InjectionPoint injectionPoint) {
                        return Logger.getLogger(injectionPoint.getMember().getDeclaringClass().getName());
                    }

                    String getConfig() {
                        // This package-private method exists, so class should NOT be deleted
                        return "config";
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
    void handlesMultipleLoggerProducerClasses() {
        // Multiple logger producer classes should all be deleted
        rewriteRun(
            java(
                """
                package org.example.logging;

                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class LoggerProducer {
                    @Produces
                    public Logger produce(InjectionPoint ip) {
                        return Logger.getLogger(ip.getMember().getDeclaringClass().getName());
                    }
                }
                """,
                (String) null
            ),
            java(
                """
                package org.example.audit;

                import jakarta.enterprise.inject.Produces;
                import jakarta.enterprise.inject.spi.InjectionPoint;
                import java.util.logging.Logger;

                public class AuditLoggerProducer {
                    @Produces
                    public Logger produceAuditLogger(InjectionPoint ip) {
                        return Logger.getLogger("AUDIT." + ip.getMember().getDeclaringClass().getName());
                    }
                }
                """,
                (String) null
            ),
            // References to both should be removed
            java(
                """
                package org.example.test;

                import org.example.logging.LoggerProducer;
                import org.example.audit.AuditLoggerProducer;

                public class TestHelper {
                }
                """,
                """
                package org.example.test;

                public class TestHelper {
                }
                """
            )
        );
    }
}
