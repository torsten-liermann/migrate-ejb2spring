/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: WFQ-004 - Tests for conditional EJB-API removal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.maven.Assertions.pomXml;

/**
 * WFQ-004: Complete timer migration or keep EJB API dependency
 * <p>
 * Tests for {@link ConditionalRemoveEjbApiDependency} which ensures that:
 * <ul>
 *   <li>EJB-API is removed when no Timer types remain</li>
 *   <li>EJB-API is kept when Timer types are still present</li>
 *   <li>Migration stubs (com.github.migration.timer.*) do NOT block removal</li>
 * </ul>
 */
class ConditionalRemoveEjbApiDependencyTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ConditionalRemoveEjbApiDependency())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "javax.ejb-api"));
    }

    @DocumentExample
    @Test
    void removesEjbApiWhenNoTimerTypesPresent() {
        // When no Timer types are present, EJB-API should be removed
        rewriteRun(
            java(
                """
                import jakarta.ejb.Stateless;

                @Stateless
                public class SimpleService {
                    public void doWork() {
                        // No timer usage
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            )
        );
    }

    @Test
    void keepsEjbApiWhenTimerServicePresent() {
        // When TimerService is still present, EJB-API must be kept
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.TimerService;

                public class TimerBean {
                    @Resource
                    private TimerService timerService;

                    public void scheduleTask() {
                        timerService.createTimer(1000L, "info");
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - dependency should remain
            )
        );
    }

    @Test
    void keepsEjbApiWhenTimerPresent() {
        // When Timer type is still present, EJB-API must be kept
        rewriteRun(
            java(
                """
                import jakarta.ejb.Timer;
                import jakarta.ejb.Timeout;

                public class TimeoutHandler {
                    @Timeout
                    public void handleTimeout(Timer timer) {
                        timer.cancel();
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - dependency should remain
            )
        );
    }

    @Test
    void keepsEjbApiWhenTimerConfigPresent() {
        // When TimerConfig is still present, EJB-API must be kept
        rewriteRun(
            java(
                """
                import jakarta.ejb.TimerConfig;

                public class ConfigFactory {
                    public TimerConfig createConfig() {
                        return new TimerConfig("data", false);
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - dependency should remain
            )
        );
    }

    @Test
    void keepsEjbApiWhenTimerHandlePresent() {
        // When TimerHandle is still present, EJB-API must be kept
        rewriteRun(
            java(
                """
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;

                public class TimerPersistence {
                    public TimerHandle saveHandle(Timer timer) {
                        return timer.getHandle();
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - dependency should remain
            )
        );
    }

    @Test
    void keepsEjbApiWhenScheduleExpressionPresent() {
        // When ScheduleExpression is still present, EJB-API must be kept
        rewriteRun(
            java(
                """
                import jakarta.ejb.ScheduleExpression;

                public class CronScheduler {
                    public ScheduleExpression createDaily() {
                        return new ScheduleExpression().hour("0").minute("0");
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - dependency should remain
            )
        );
    }

    @Test
    void keepsEjbApiWhenJavaxTimerServicePresent() {
        // Also check legacy javax namespace
        rewriteRun(
            java(
                """
                import javax.ejb.TimerService;

                public class LegacyTimerBean {
                    private TimerService timerService;
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>javax.ejb</groupId>
                            <artifactId>javax.ejb-api</artifactId>
                            <version>3.2</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - dependency should remain
            )
        );
    }

    private static final String TIMER_SERVICE_STUB =
        """
        package com.github.migration.timer;

        import java.io.Serializable;
        import java.util.Collection;
        import java.util.Date;

        public interface TimerService {
            Timer createTimer(long duration, Serializable info);
            Timer createTimer(Date expiration, Serializable info);
            Timer createTimer(long initialDuration, long intervalDuration, Serializable info);
            Timer createTimer(Date initialExpiration, long intervalDuration, Serializable info);
            Timer createSingleActionTimer(long duration, TimerConfig timerConfig);
            Timer createSingleActionTimer(Date expiration, TimerConfig timerConfig);
            Timer createIntervalTimer(long initialDuration, long intervalDuration, TimerConfig timerConfig);
            Timer createIntervalTimer(Date initialExpiration, long intervalDuration, TimerConfig timerConfig);
            Timer createCalendarTimer(ScheduleExpression schedule);
            Timer createCalendarTimer(ScheduleExpression schedule, TimerConfig timerConfig);
            Collection<Timer> getTimers();
            Collection<Timer> getAllTimers();
        }
        """;

    private static final String TIMER_STUB =
        """
        package com.github.migration.timer;

        import java.io.Serializable;
        import java.util.Date;

        public interface Timer {
            void cancel();
            long getTimeRemaining();
            Date getNextTimeout();
            ScheduleExpression getSchedule();
            boolean isCalendarTimer();
            boolean isPersistent();
            Serializable getInfo();
            TimerHandle getHandle();
        }
        """;

    private static final String TIMER_CONFIG_STUB =
        """
        package com.github.migration.timer;

        import java.io.Serializable;

        public class TimerConfig {
            public TimerConfig() {}
            public TimerConfig(Serializable info, boolean persistent) {}
            public Serializable getInfo() { return null; }
            public void setInfo(Serializable info) {}
            public boolean isPersistent() { return false; }
            public void setPersistent(boolean persistent) {}
        }
        """;

    private static final String TIMER_HANDLE_STUB =
        """
        package com.github.migration.timer;

        import java.io.Serializable;

        public interface TimerHandle extends Serializable {
            Timer getTimer();
        }
        """;

    private static final String SCHEDULE_EXPRESSION_STUB =
        """
        package com.github.migration.timer;

        import java.io.Serializable;

        public class ScheduleExpression implements Serializable {
            public ScheduleExpression second(String s) { return this; }
            public ScheduleExpression minute(String s) { return this; }
            public ScheduleExpression hour(String s) { return this; }
            public ScheduleExpression dayOfWeek(String s) { return this; }
            public ScheduleExpression dayOfMonth(String s) { return this; }
            public ScheduleExpression month(String s) { return this; }
            public ScheduleExpression year(String s) { return this; }
        }
        """;

    @Test
    void removesEjbApiWhenOnlyMigrationStubsUsed() {
        // When Timer types have been migrated to stubs (com.github.migration.timer.*),
        // the EJB-API can be removed because the stubs don't block removal.
        // This test uses the inline stubs to verify the logic without Maven resolution.
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "javax.ejb-api")),
            java(TIMER_SERVICE_STUB),
            java(TIMER_STUB),
            java(TIMER_CONFIG_STUB),
            java(TIMER_HANDLE_STUB),
            java(SCHEDULE_EXPRESSION_STUB),
            java(
                """
                import com.github.migration.timer.TimerService;

                public class MigratedTimerBean {
                    private TimerService timerService;
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            )
        );
    }

    @Test
    void keepsEjbApiWhenWildcardEjbImportUsed() {
        // Wildcard import from jakarta.ejb.* conservatively blocks removal
        rewriteRun(
            java(
                """
                import jakarta.ejb.*;

                @Stateless
                public class WildcardBean {
                    // May or may not use Timer types
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - wildcard import conservatively blocks removal
            )
        );
    }

    @Test
    void removesJavaxEjbApiWhenNoTimerTypesPresent() {
        // Also removes javax.ejb-api when no Timer types present
        rewriteRun(
            java(
                """
                import javax.ejb.Stateless;

                @Stateless
                public class LegacyService {
                    public void doWork() {
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>javax.ejb</groupId>
                            <artifactId>javax.ejb-api</artifactId>
                            <version>3.2</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                </project>
                """
            )
        );
    }

    // ============================================
    // HIGH Priority Fix: Timer Annotations Tests
    // ============================================

    @Test
    void keepsEjbApiWhenTimeoutAnnotationOnlyPresent() {
        // When @Timeout annotation is present (even without Timer type import), keep EJB-API
        rewriteRun(
            java(
                """
                import jakarta.ejb.Timeout;

                public class TimeoutOnlyBean {
                    @Timeout
                    public void handleTimeout() {
                        // Timer parameter removed, but annotation remains
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - @Timeout blocks removal
            )
        );
    }

    @Test
    void keepsEjbApiWhenScheduleAnnotationPresent() {
        // When @Schedule annotation is present, keep EJB-API
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;

                public class ScheduledBean {
                    @Schedule(hour = "0", minute = "0")
                    public void dailyTask() {
                        // Scheduled method
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - @Schedule blocks removal
            )
        );
    }

    @Test
    void keepsEjbApiWhenSchedulesAnnotationPresent() {
        // When @Schedules annotation is present, keep EJB-API
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Schedules;

                public class MultiScheduledBean {
                    @Schedules({
                        @Schedule(hour = "0"),
                        @Schedule(hour = "12")
                    })
                    public void twiceDaily() {
                        // Scheduled twice a day
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - @Schedules blocks removal
            )
        );
    }

    @Test
    void keepsEjbApiWhenJavaxTimeoutAnnotationPresent() {
        // Also check legacy javax namespace for @Timeout
        rewriteRun(
            java(
                """
                import javax.ejb.Timeout;

                public class LegacyTimeoutBean {
                    @Timeout
                    public void handleTimeout() {
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>javax.ejb</groupId>
                            <artifactId>javax.ejb-api</artifactId>
                            <version>3.2</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - javax @Timeout blocks removal
            )
        );
    }

    // ============================================
    // MEDIUM Priority Fix: FQN Usage Detection Tests
    // ============================================

    @Test
    void keepsEjbApiWhenTimerUsedAsFqn() {
        // When jakarta.ejb.Timer is used as FQN (no import), keep EJB-API
        rewriteRun(
            java(
                """
                public class FqnTimerUser {
                    public void handleTimeout(jakarta.ejb.Timer timer) {
                        timer.cancel();
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - FQN Timer usage blocks removal
            )
        );
    }

    @Test
    void keepsEjbApiWhenTimerServiceUsedAsFqn() {
        // When jakarta.ejb.TimerService is used as FQN (no import), keep EJB-API
        rewriteRun(
            java(
                """
                public class FqnTimerServiceUser {
                    private jakarta.ejb.TimerService timerService;

                    public void schedule() {
                        timerService.createTimer(1000L, null);
                    }
                }
                """
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>test-app</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """
                // No change expected - FQN TimerService usage blocks removal
            )
        );
    }

    // ============================================
    // MEDIUM Priority Fix: Multi-Module Tests
    // ============================================

    @Test
    void removesEjbApiInModuleWithoutTimerEvenWhenOtherModuleHasTimer() {
        // In a multi-module project:
        // - module-a has Timer usage -> keep EJB-API
        // - module-b has no Timer usage -> remove EJB-API
        rewriteRun(
            // Module A: has Timer usage
            java(
                """
                import jakarta.ejb.TimerService;

                public class TimerBeanA {
                    private TimerService timerService;
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/TimerBeanA.java")
            ),
            // Module A pom.xml - should NOT change (Timer present)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-a</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                // No change expected - module-a has Timer
                spec -> spec.path("module-a/pom.xml")
            ),
            // Module B: no Timer usage
            java(
                """
                import jakarta.ejb.Stateless;

                @Stateless
                public class SimpleServiceB {
                    public void doWork() {
                    }
                }
                """,
                spec -> spec.path("module-b/src/main/java/com/example/SimpleServiceB.java")
            ),
            // Module B pom.xml - should be updated (no Timer)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-b</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-b</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                spec -> spec.path("module-b/pom.xml")
            )
        );
    }

    @Test
    void keepsEjbApiInBothModulesWhenBothHaveTimerUsage() {
        // When both modules have Timer usage, keep EJB-API in both
        rewriteRun(
            // Module A: has Timer usage
            java(
                """
                import jakarta.ejb.Timer;

                public class TimerBeanA {
                    public void handle(Timer t) {}
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/TimerBeanA.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-a</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                spec -> spec.path("module-a/pom.xml")
            ),
            // Module B: also has Timer usage (@Schedule)
            java(
                """
                import jakarta.ejb.Schedule;

                public class ScheduledBeanB {
                    @Schedule(hour = "0")
                    public void daily() {}
                }
                """,
                spec -> spec.path("module-b/src/main/java/com/example/ScheduledBeanB.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-b</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                // No change - both modules have Timer usage
                spec -> spec.path("module-b/pom.xml")
            )
        );
    }

    // ============================================
    // MEDIUM Priority Fix: Custom Source Root Tests
    // ============================================

    @Test
    void detectsTimerInCustomSourceRoot() {
        // When Timer is used in a custom source root (not src/main/java),
        // the recipe should still detect it if configured via mainSourceRoots option.
        rewriteRun(
            spec -> spec.recipe(new ConditionalRemoveEjbApiDependency(
                "java,src/main/kotlin",  // Custom main source roots
                null                      // Default test roots
            )).parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "javax.ejb-api")),
            // Timer usage in custom source root
            java(
                """
                import jakarta.ejb.TimerService;

                public class KotlinStyleTimerBean {
                    private TimerService timerService;
                }
                """,
                spec -> spec.path("module-custom/java/com/example/KotlinStyleTimerBean.java")
            ),
            // pom.xml should NOT change because Timer is present
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-custom</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                // No change expected - Timer present in custom source root
                spec -> spec.path("module-custom/pom.xml")
            )
        );
    }

    @Test
    void removesEjbApiWhenNoTimerInCustomSourceRoot() {
        // When no Timer types are used in a custom source root,
        // the EJB-API should be removed.
        rewriteRun(
            spec -> spec.recipe(new ConditionalRemoveEjbApiDependency(
                "java,src/main/kotlin",  // Custom main source roots
                null                      // Default test roots
            )).parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "javax.ejb-api")),
            // No Timer usage
            java(
                """
                import jakarta.ejb.Stateless;

                @Stateless
                public class SimpleService {
                    public void doWork() {}
                }
                """,
                spec -> spec.path("module-custom/java/com/example/SimpleService.java")
            ),
            // pom.xml should be updated - no Timer
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-custom</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-custom</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                spec -> spec.path("module-custom/pom.xml")
            )
        );
    }

    @Test
    void multiModuleWithKotlinSourceRoot() {
        // Multi-module project with Kotlin source root:
        // - module-a has Timer usage in src/main/kotlin -> keep EJB-API
        // - module-b has no Timer usage -> remove EJB-API
        rewriteRun(
            spec -> spec.recipe(new ConditionalRemoveEjbApiDependency(
                "src/main/java,src/main/kotlin",  // Include both Java and Kotlin roots
                "src/test/java,src/test/kotlin"   // Test roots
            )).parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "javax.ejb-api")),
            // Module A: has Timer usage in Kotlin source root
            java(
                """
                import jakarta.ejb.TimerService;

                public class TimerBeanInKotlin {
                    private TimerService timerService;
                }
                """,
                spec -> spec.path("module-a/src/main/kotlin/com/example/TimerBeanInKotlin.java")
            ),
            // Module A pom.xml - should NOT change (Timer present)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-a</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                // No change expected - module-a has Timer
                spec -> spec.path("module-a/pom.xml")
            ),
            // Module B: no Timer usage
            java(
                """
                import jakarta.ejb.Stateless;

                @Stateless
                public class SimpleServiceB {
                    public void doWork() {}
                }
                """,
                spec -> spec.path("module-b/src/main/java/com/example/SimpleServiceB.java")
            ),
            // Module B pom.xml - should be updated (no Timer)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-b</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-b</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                spec -> spec.path("module-b/pom.xml")
            )
        );
    }

    // ============================================
    // Round 3: Nested Module Path Resolution Tests
    // ============================================

    @Test
    void nestedModulesWithDifferentTimerUsage() {
        // Test for Round 3 fix: Nested modules like apps/app1, apps/app2
        // Module path resolution must be consistent between Java scanning and pom.xml processing
        // - apps/app1 has Timer usage -> keep EJB-API
        // - apps/app2 has no Timer usage -> remove EJB-API
        rewriteRun(
            // apps/app1: has Timer usage
            java(
                """
                import jakarta.ejb.TimerService;

                public class TimerBeanApp1 {
                    private TimerService timerService;
                }
                """,
                spec -> spec.path("apps/app1/src/main/java/com/example/TimerBeanApp1.java")
            ),
            // apps/app1 pom.xml - should NOT change (Timer present)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app1</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                // No change expected - apps/app1 has Timer
                spec -> spec.path("apps/app1/pom.xml")
            ),
            // apps/app2: no Timer usage
            java(
                """
                import jakarta.ejb.Stateless;

                @Stateless
                public class SimpleServiceApp2 {
                    public void doWork() {}
                }
                """,
                spec -> spec.path("apps/app2/src/main/java/com/example/SimpleServiceApp2.java")
            ),
            // apps/app2 pom.xml - should be updated (no Timer)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app2</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>app2</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                spec -> spec.path("apps/app2/pom.xml")
            )
        );
    }

    @Test
    void deeplyNestedModulesWithCustomRoots() {
        // Test for Round 3 fix: Deeply nested modules with custom source roots
        // - services/core/api has Timer usage -> keep EJB-API
        // - services/core/impl has no Timer usage -> remove EJB-API
        // Uses custom source root "java" instead of "src/main/java"
        rewriteRun(
            spec -> spec.recipe(new ConditionalRemoveEjbApiDependency(
                "java,src/main/java",  // Custom main source roots
                "test,src/test/java"   // Custom test roots
            )).parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "javax.ejb-api")),
            // services/core/api: has Timer usage in custom source root
            java(
                """
                import jakarta.ejb.Schedule;

                public class ScheduledApiBean {
                    @Schedule(hour = "0")
                    public void dailyTask() {}
                }
                """,
                spec -> spec.path("services/core/api/java/com/example/ScheduledApiBean.java")
            ),
            // services/core/api pom.xml - should NOT change (Timer present)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>core-api</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                // No change expected - services/core/api has Timer
                spec -> spec.path("services/core/api/pom.xml")
            ),
            // services/core/impl: no Timer usage
            java(
                """
                import jakarta.ejb.Stateless;

                @Stateless
                public class SimpleServiceImpl {
                    public void doWork() {}
                }
                """,
                spec -> spec.path("services/core/impl/java/com/example/SimpleServiceImpl.java")
            ),
            // services/core/impl pom.xml - should be updated (no Timer)
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>core-impl</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>core-impl</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                spec -> spec.path("services/core/impl/pom.xml")
            )
        );
    }

    // ============================================
    // Round 4: Two-Pass Scanning and Kotlin/Scala Support Tests
    // ============================================

    @Test
    void detectsTimerInTestSourceRoot() {
        // When Timer is used in test source root (src/test/java),
        // the recipe should still detect it and keep EJB-API
        rewriteRun(
            spec -> spec.recipe(new ConditionalRemoveEjbApiDependency(
                "src/main/java",   // Main source roots
                "src/test/java"    // Test source roots
            )).parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "javax.ejb-api")),
            // Timer usage in test source root
            java(
                """
                import jakarta.ejb.TimerService;

                public class TimerTestBean {
                    private TimerService timerService;
                }
                """,
                spec -> spec.path("module-test/src/test/java/com/example/TimerTestBean.java")
            ),
            // pom.xml should NOT change because Timer is present in test
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-test</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                // No change expected - Timer present in test source root
                spec -> spec.path("module-test/pom.xml")
            )
        );
    }

    @Test
    void multiModuleWithMixedSourceRootsAndTestFiles() {
        // Multi-module project with Timer in main and test sources:
        // - module-a has Timer usage in src/main/java -> keep EJB-API
        // - module-b has Timer usage in src/test/java -> keep EJB-API
        // - module-c has no Timer usage -> remove EJB-API
        rewriteRun(
            spec -> spec.recipe(new ConditionalRemoveEjbApiDependency(
                "src/main/java,src/main/kotlin",  // Main source roots
                "src/test/java,src/test/kotlin"   // Test source roots
            )).parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "javax.ejb-api")),
            // Module A: Timer in main source
            java(
                """
                import jakarta.ejb.TimerService;

                public class TimerMainBean {
                    private TimerService timerService;
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/TimerMainBean.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-a</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                // No change - Timer in main
                spec -> spec.path("module-a/pom.xml")
            ),
            // Module B: Timer in test source
            java(
                """
                import jakarta.ejb.Stateless;

                @Stateless
                public class ProductionService {
                    public void doWork() {}
                }
                """,
                spec -> spec.path("module-b/src/main/java/com/example/ProductionService.java")
            ),
            java(
                """
                import jakarta.ejb.Schedule;

                public class ScheduledTestBean {
                    @Schedule(hour = "0")
                    public void testMethod() {}
                }
                """,
                spec -> spec.path("module-b/src/test/java/com/example/ScheduledTestBean.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-b</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                // No change - Timer in test
                spec -> spec.path("module-b/pom.xml")
            ),
            // Module C: no Timer usage anywhere
            java(
                """
                import jakarta.ejb.Stateless;

                @Stateless
                public class SimpleServiceC {
                    public void doWork() {}
                }
                """,
                spec -> spec.path("module-c/src/main/java/com/example/SimpleServiceC.java")
            ),
            java(
                """
                public class SimpleTestC {
                    public void testSomething() {}
                }
                """,
                spec -> spec.path("module-c/src/test/java/com/example/SimpleTestC.java")
            ),
            pomXml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-c</artifactId>
                    <version>1.0.0</version>
                    <dependencies>
                        <dependency>
                            <groupId>jakarta.ejb</groupId>
                            <artifactId>jakarta.ejb-api</artifactId>
                            <version>4.0.0</version>
                        </dependency>
                    </dependencies>
                </project>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>module-c</artifactId>
                    <version>1.0.0</version>
                </project>
                """,
                spec -> spec.path("module-c/pom.xml")
            )
        );
    }
}
