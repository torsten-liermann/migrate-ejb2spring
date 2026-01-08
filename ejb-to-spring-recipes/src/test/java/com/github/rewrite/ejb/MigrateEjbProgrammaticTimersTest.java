package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateEjbProgrammaticTimersTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateEjbProgrammaticTimers())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context", "spring-boot"));
    }

    @Test
    void doesNotMigrateCreateTimerWithSerializableInfo() {
        // P0.1 Fix: createTimer(delay, info) WITHOUT TimerConfig should NOT be migrated
        // because we cannot determine if persistent=false
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @Component
                public class TimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.createTimer(1000L, "info");
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateTimerWithNullConfig() {
        // P0.1 Fix: createTimer(delay, null) should NOT be migrated
        // because null means no TimerConfig, EJB default is persistent=true
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @Component
                public class TimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.createTimer(1000L, null);
                    }
                }
                """
            )
        );
    }

    @Test
    void replacesCreateSingleActionTimerWithTimerConfigPersistentFalse() {
        // createSingleActionTimer with TimerConfig(x, false) should be migrated
        // Using createSingleActionTimer because it has better type resolution
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @Component
                public class TimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.createSingleActionTimer(1000L, new TimerConfig("info", false));
                    }
                }
                """,
                """
                import jakarta.ejb.TimerConfig;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.scheduling.TaskScheduler;
                import org.springframework.stereotype.Component;

                import java.time.Instant;

                @Component
                public class TimerBean {
                    @Autowired
                    private TaskScheduler timerService;

                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.schedule(() -> onTimeout(), Instant.now().plusMillis(1000L));
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateSingleActionTimerWithTimerConfigPersistentTrue() {
        // createSingleActionTimer with TimerConfig(x, true) should NOT be migrated (persistent timer)
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @Component
                public class TimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        timerService.createSingleActionTimer(1000L, new TimerConfig("info", true));
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateSingleActionTimerWithTimerConfigVariable() {
        // createSingleActionTimer with TimerConfig variable should NOT be migrated (cannot determine persistent value)
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @Component
                public class TimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        TimerConfig config = new TimerConfig("info", false);
                        timerService.createSingleActionTimer(1000L, config);
                    }
                }
                """
            )
        );
    }

    @Test
    void replacesCreateIntervalTimerWithTimerConfigPersistentFalse() {
        // P0.1 Fix: createIntervalTimer with TimerConfig(x, false) SHOULD be migrated
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @Component
                public class IntervalTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void tick() {
                    }

                    public void start() {
                        timerService.createIntervalTimer(0L, 5000L, new TimerConfig("info", false));
                    }
                }
                """,
                """
                import jakarta.ejb.TimerConfig;
                import org.springframework.beans.factory.annotation.Autowired;
                import org.springframework.scheduling.TaskScheduler;
                import org.springframework.stereotype.Component;

                import java.time.Duration;
                import java.time.Instant;

                @Component
                public class IntervalTimerBean {
                    @Autowired
                    private TaskScheduler timerService;

                    public void tick() {
                    }

                    public void start() {
                        timerService.scheduleAtFixedRate(() -> tick(), Instant.now().plusMillis(0L), Duration.ofMillis(5000L));
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateIntervalTimerWithNullConfig() {
        // P0.1 Fix: createIntervalTimer(initial, interval, null) should NOT be migrated
        // because null means no TimerConfig, EJB default is persistent=true
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @Component
                public class IntervalTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void tick() {
                    }

                    public void start() {
                        timerService.createIntervalTimer(0L, 5000L, null);
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateIntervalTimerWithTimerConfigPersistentTrue() {
        // P0.1 Fix: createIntervalTimer with TimerConfig(x, true) should NOT be migrated
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @Component
                public class IntervalTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void tick() {
                    }

                    public void start() {
                        timerService.createIntervalTimer(0L, 5000L, new TimerConfig("info", true));
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateIntervalTimerWithTimerConfigVariable() {
        // P0.1 Fix: createIntervalTimer with TimerConfig variable should NOT be migrated
        // because we cannot statically determine the persistent value
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @Component
                public class IntervalTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void tick() {
                    }

                    public void start() {
                        TimerConfig config = new TimerConfig("info", false);
                        timerService.createIntervalTimer(0L, 5000L, config);
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotChangeWhenTimeoutHasTimerParameter() {
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;
                import org.springframework.stereotype.Component;

                @Component
                public class TimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                    }

                    public void start() {
                        timerService.createTimer(1000L, "info");
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateCreateSingleActionTimerWithNullConfig() {
        // P0.1 Fix: createSingleActionTimer(delay, null) should NOT be migrated
        // because null means no TimerConfig, so we cannot determine persistent=false
        // (EJB default is persistent=true)
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Stateless;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                @Stateless
                public class MyBean {
                    @Resource
                    private TimerService timerService;

                    public void schedule() {
                        timerService.createSingleActionTimer(1000L, null);
                    }

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateWhenMixedCalls() {
        // P0.1 Fix: If a class has BOTH migratable and non-migratable timer calls,
        // the entire class should NOT be migrated to avoid compile errors
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Stateless;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                @Stateless
                public class MixedBean {
                    @Resource
                    private TimerService timerService;

                    public void schedule() {
                        timerService.createSingleActionTimer(1000L, new TimerConfig("a", false)); // migratable
                        timerService.createSingleActionTimer(2000L, new TimerConfig("b", true)); // NOT migratable (persistent=true)
                    }

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateWhenSetPersistentCalled() {
        // P0.2 Fix: If setPersistent() is called on a TimerConfig,
        // the persistent value is dynamic and we cannot safely migrate
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Stateless;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                @Stateless
                public class DynamicPersistentBean {
                    @Resource
                    private TimerService timerService;

                    public void schedule(boolean persistent) {
                        TimerConfig config = new TimerConfig("info", false);
                        config.setPersistent(persistent); // Dynamic - not migratable!
                        timerService.createSingleActionTimer(1000L, config);
                    }

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateWhenSetPersistentCalledWithLiteralFalse() {
        // P0.2 Fix: Even if setPersistent(false) is called with a literal,
        // we still don't migrate because the pattern cfg.setPersistent() indicates
        // dynamic configuration which should be reviewed
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Stateless;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                @Stateless
                public class SetPersistentFalseBean {
                    @Resource
                    private TimerService timerService;

                    public void schedule() {
                        TimerConfig config = new TimerConfig();
                        config.setPersistent(false); // Even literal false - pattern indicates review needed
                        timerService.createSingleActionTimer(1000L, config);
                    }

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateWhenSetPersistentCalledOnDefaultConstructor() {
        // P0.2 Fix: TimerConfig() with setPersistent(false) should NOT be migrated
        // because we only accept new TimerConfig(info, false) with literal false in constructor
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Stateless;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                @Stateless
                public class DefaultConstructorBean {
                    @Resource
                    private TimerService timerService;

                    public void schedule() {
                        TimerConfig config = new TimerConfig();
                        config.setInfo("info");
                        config.setPersistent(false);
                        timerService.createSingleActionTimer(1000L, config);
                    }

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }

    @Test
    void doesNotMigrateWhenSetInfoCalledMultipleTimes() {
        // P0.2 Fix: Multiple setInfo() calls indicate dynamic configuration
        // This is currently handled by the fact that we don't migrate variables anyway,
        // but we test it explicitly for documentation purposes
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Stateless;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                @Stateless
                public class MultipleSetInfoBean {
                    @Resource
                    private TimerService timerService;

                    public void schedule(boolean condition) {
                        TimerConfig config = new TimerConfig("initial", false);
                        if (condition) {
                            config.setInfo("info1");
                        } else {
                            config.setInfo("info2");
                        }
                        timerService.createSingleActionTimer(1000L, config);
                    }

                    @Timeout
                    public void onTimeout() {
                    }
                }
                """
            )
        );
    }
}
