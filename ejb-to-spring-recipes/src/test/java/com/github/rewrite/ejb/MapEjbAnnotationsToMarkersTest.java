package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.openrewrite.java.Assertions.java;

class MapEjbAnnotationsToMarkersTest implements RewriteTest {

    /**
     * Set of marker annotation files required by MapEjbAnnotationsToMarkers.
     * Derived from the recipe's EJB_ANNOTATIONS and EJB_ENUMS lists to ensure consistency.
     * Only these markers are loaded to avoid OpenRewrite parser issues with large dependency sets.
     */
    private static final Set<String> REQUIRED_MARKERS = Stream.concat(
            MapEjbAnnotationsToMarkers.EJB_ANNOTATIONS.stream(),
            MapEjbAnnotationsToMarkers.EJB_ENUMS.stream()
        )
        .map(name -> "Ejb" + name + ".java")
        .collect(Collectors.toUnmodifiableSet());

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MapEjbAnnotationsToMarkers())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.ejb-api", "javax.ejb-api")
                .dependsOn(markerSources()));
    }

    private static String[] markerSources() {
        Path root = Path.of("../migration-annotations/src/main/java/com/github/migration/annotations");
        if (!Files.isDirectory(root)) {
            throw new IllegalStateException("Marker sources not found at " + root.toAbsolutePath());
        }
        try (Stream<Path> paths = Files.list(root)) {
            return paths
                .filter(path -> path.toString().endsWith(".java"))
                .filter(path -> REQUIRED_MARKERS.contains(path.getFileName().toString()))
                .sorted()
                .map(MapEjbAnnotationsToMarkersTest::readFile)
                .toArray(String[]::new);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String readFile(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void mapsJakartaAnnotationsAndEnums() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Stateless;
                import jakarta.ejb.TransactionAttribute;
                import jakarta.ejb.TransactionAttributeType;

                @Stateless
                @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
                public class BookingService {
                }
                """,
                """
                import com.github.migration.annotations.EjbStateless;
                import com.github.migration.annotations.EjbTransactionAttribute;
                import com.github.migration.annotations.EjbTransactionAttributeType;

                @EjbStateless
                @EjbTransactionAttribute(EjbTransactionAttributeType.REQUIRES_NEW)
                public class BookingService {
                }
                """
            )
        );
    }

    @Test
    void mapsJavaxMessageDrivenActivationConfig() {
        rewriteRun(
            java(
                """
                import javax.ejb.ActivationConfigProperty;
                import javax.ejb.MessageDriven;

                @MessageDriven(activationConfig = {
                    @ActivationConfigProperty(propertyName = "destinationLookup",
                        propertyValue = "java:/jms/queue/Orders")
                })
                public class OrderListener {
                }
                """,
                """
                import com.github.migration.annotations.EjbActivationConfigProperty;
                import com.github.migration.annotations.EjbMessageDriven;

                @EjbMessageDriven(activationConfig = {
                    @EjbActivationConfigProperty(propertyName = "destinationLookup",
                        propertyValue = "java:/jms/queue/Orders")
                })
                public class OrderListener {
                }
                """
            )
        );
    }

    @Test
    void mapsJakartaConcurrencyAnnotations() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.AccessTimeout;
                import jakarta.ejb.ConcurrencyManagement;
                import jakarta.ejb.ConcurrencyManagementType;
                import jakarta.ejb.Lock;
                import jakarta.ejb.LockType;
                import jakarta.ejb.Singleton;
                import java.util.concurrent.TimeUnit;

                @Singleton
                @ConcurrencyManagement(ConcurrencyManagementType.BEAN)
                public class CacheCoordinator {
                    @Lock(LockType.READ)
                    @AccessTimeout(value = 5, unit = TimeUnit.SECONDS)
                    public void reload() {
                    }
                }
                """,
                """
                import com.github.migration.annotations.*;
                import java.util.concurrent.TimeUnit;

                @EjbSingleton
                @EjbConcurrencyManagement(EjbConcurrencyManagementType.BEAN)
                public class CacheCoordinator {
                    @EjbLock(EjbLockType.READ)
                    @EjbAccessTimeout(value = 5, unit = TimeUnit.SECONDS)
                    public void reload() {
                    }
                }
                """
            )
        );
    }

    @Test
    void mapsJavaxStatefulLifecycleAnnotations() {
        rewriteRun(
            java(
                """
                import javax.ejb.PostActivate;
                import javax.ejb.PrePassivate;
                import javax.ejb.Remove;
                import javax.ejb.Stateful;
                import javax.ejb.StatefulTimeout;
                import java.util.concurrent.TimeUnit;

                @Stateful
                @StatefulTimeout(value = 30, unit = TimeUnit.MINUTES)
                public class BookingSession {
                    @PrePassivate
                    public void beforePassivation() {
                    }

                    @PostActivate
                    public void afterActivation() {
                    }

                    @Remove
                    public void close() {
                    }
                }
                """,
                """
                import com.github.migration.annotations.*;

                import java.util.concurrent.TimeUnit;

                @EjbStateful
                @EjbStatefulTimeout(value = 30, unit = TimeUnit.MINUTES)
                public class BookingSession {
                    @EjbPrePassivate
                    public void beforePassivation() {
                    }

                    @EjbPostActivate
                    public void afterActivation() {
                    }

                    @EjbRemove
                    public void close() {
                    }
                }
                """
            )
        );
    }

    @Test
    void mapsInterfaceAndReferenceAnnotations() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.EJB;
                import jakarta.ejb.EJBs;
                import jakarta.ejb.Local;
                import jakarta.ejb.LocalBean;
                import jakarta.ejb.LocalHome;
                import jakarta.ejb.Remote;
                import jakarta.ejb.RemoteHome;

                @Local({PricingLocal.class})
                @Remote({PricingRemote.class})
                @LocalHome(PricingLocalHome.class)
                @RemoteHome(PricingRemoteHome.class)
                @LocalBean
                @EJBs({
                    @EJB(name = "auditRef", lookup = "java:global/AuditBean"),
                    @EJB(beanName = "pricingBean")
                })
                public class PricingBean {
                    @EJB
                    private PricingService pricingService;
                }

                interface PricingLocal {}
                interface PricingRemote {}
                interface PricingLocalHome {}
                interface PricingRemoteHome {}
                class PricingService {}
                """,
                """
                import com.github.migration.annotations.*;

                @EjbLocal({PricingLocal.class})
                @EjbRemote({PricingRemote.class})
                @EjbLocalHome(PricingLocalHome.class)
                @EjbRemoteHome(PricingRemoteHome.class)
                @EjbLocalBean
                @EjbEJBs({
                    @EjbEJB(name = "auditRef", lookup = "java:global/AuditBean"),
                    @EjbEJB(beanName = "pricingBean")
                })
                public class PricingBean {
                    @EjbEJB
                    private PricingService pricingService;
                }

                interface PricingLocal {}
                interface PricingRemote {}
                interface PricingLocalHome {}
                interface PricingRemoteHome {}
                class PricingService {}
                """
            )
        );
    }

    @Test
    void mapsStartupDependsOnAndTransactionManagementAnnotations() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.DependsOn;
                import jakarta.ejb.Singleton;
                import jakarta.ejb.Startup;
                import jakarta.ejb.TransactionManagement;
                import jakarta.ejb.TransactionManagementType;

                @Singleton
                @Startup
                @DependsOn({"cache", "audit"})
                @TransactionManagement(TransactionManagementType.BEAN)
                public class BootstrapCoordinator {
                }
                """,
                """
                import com.github.migration.annotations.*;

                @EjbSingleton
                @EjbStartup
                @EjbDependsOn({"cache", "audit"})
                @EjbTransactionManagement(EjbTransactionManagementType.BEAN)
                public class BootstrapCoordinator {
                }
                """
            )
        );
    }

    @Test
    void mapsAsynchronousAndInitAnnotations() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Asynchronous;
                import jakarta.ejb.Init;

                public class LegacyInitializer {
                    @Init
                    public void initialize() {
                    }

                    @Asynchronous
                    public void refresh() {
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbAsynchronous;
                import com.github.migration.annotations.EjbInit;

                public class LegacyInitializer {
                    @EjbInit
                    public void initialize() {
                    }

                    @EjbAsynchronous
                    public void refresh() {
                    }
                }
                """
            )
        );
    }

    @Test
    void mapsTimerAnnotations() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Schedules;
                import jakarta.ejb.Timeout;

                public class TimerBean {
                    @Schedule(hour = "6", minute = "0", timezone = "UTC")
                    public void morning() {
                    }

                    @Schedules({
                        @Schedule(hour = "12", minute = "0"),
                        @Schedule(hour = "18", minute = "0")
                    })
                    public void recurring() {
                    }

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbSchedule;
                import com.github.migration.annotations.EjbSchedules;
                import com.github.migration.annotations.EjbTimeout;

                public class TimerBean {
                    @EjbSchedule(hour = "6", minute = "0", timezone = "UTC")
                    public void morning() {
                    }

                    @EjbSchedules({
                        @EjbSchedule(hour = "12", minute = "0"),
                        @EjbSchedule(hour = "18", minute = "0")
                    })
                    public void recurring() {
                    }

                    @EjbTimeout
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void mapsSessionSynchronizationAnnotations() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.AfterBegin;
                import jakarta.ejb.AfterCompletion;
                import jakarta.ejb.BeforeCompletion;

                public class SessionSyncBean {
                    @AfterBegin
                    public void afterBegin() {
                    }

                    @BeforeCompletion
                    public void beforeCompletion() {
                    }

                    @AfterCompletion
                    public void afterCompletion(boolean committed) {
                    }
                }
                """,
                """
                import com.github.migration.annotations.EjbAfterBegin;
                import com.github.migration.annotations.EjbAfterCompletion;
                import com.github.migration.annotations.EjbBeforeCompletion;

                public class SessionSyncBean {
                    @EjbAfterBegin
                    public void afterBegin() {
                    }

                    @EjbBeforeCompletion
                    public void beforeCompletion() {
                    }

                    @EjbAfterCompletion
                    public void afterCompletion(boolean committed) {
                    }
                }
                """
            )
        );
    }

    @Test
    void mapsApplicationExceptionAnnotation() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.ApplicationException;

                @ApplicationException(rollback = true, inherited = false)
                public class BookingException extends RuntimeException {
                }
                """,
                """
                import com.github.migration.annotations.EjbApplicationException;

                @EjbApplicationException(rollback = true, inherited = false)
                public class BookingException extends RuntimeException {
                }
                """
            )
        );
    }
}
