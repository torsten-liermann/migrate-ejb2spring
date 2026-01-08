/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Added P0.3 Timer-API guarantee tests
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
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

/**
 * P0.3: Timer-API-Verwendungen vollstaendig ersetzen - "Harte Garantie"
 * <p>
 * Diese Tests verifizieren dass nach der Migration KEINE Timer-API-Aufrufe
 * (timer.getInfo(), timer.cancel(), timer.getTimeRemaining(), timer.getNextTimeout())
 * verbleiben - entweder weil sie automatisch ersetzt wurden, oder weil die Klasse
 * mit @NeedsReview markiert wurde.
 * <p>
 * Timer-Referenzierungsarten die abgedeckt werden:
 * <ul>
 *   <li>Parameter: void timeout(Timer timer)</li>
 *   <li>Lokale Variable: Timer t = timerService.createTimer(...)</li>
 *   <li>Field: private Timer myTimer</li>
 *   <li>Return von getTimers(): for (Timer t : timerService.getTimers())</li>
 *   <li>TimerHandle.getTimer(): handle.getTimer().cancel()</li>
 * </ul>
 * <p>
 * NOTE: Tests run with MarkEjbTimerServiceForReview only, as MigrateEjbProgrammaticTimers
 * does not modify cases with Timer API usage (it requires no-param @Timeout).
 * The guarantee is: if MigrateEjbProgrammaticTimers does NOT migrate (leaves unchanged),
 * then MarkEjbTimerServiceForReview MUST catch it.
 */
class TimerApiGuaranteeTest implements RewriteTest {

    private static final String NEEDS_REVIEW_STUB =
        """
        package com.github.rewrite.ejb.annotations;

        import java.lang.annotation.*;

        @Retention(RetentionPolicy.RUNTIME)
        @Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})
        public @interface NeedsReview {
            Category category();
            String reason() default "";
            String originalCode() default "";
            String suggestedAction() default "";
            enum Category { CONFIGURATION, MANUAL_MIGRATION }
        }
        """;

    @Override
    public void defaults(RecipeSpec spec) {
        // Use MarkEjbTimerServiceForReview to verify it catches all non-migrated cases.
        // MigrateEjbProgrammaticTimers only handles simple cases (no-param @Timeout + inline TimerConfig(x, false))
        // so all Timer API usage cases are caught by the marker.
        spec.recipe(new MarkEjbTimerServiceForReview())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context", "spring-boot"));
    }

    // ========== Timer as Field - MUST trigger marker ==========

    @Test
    void timerAsFieldTriggersMarker() {
        // P0.3: Timer as class field is a complex pattern that cannot be auto-migrated.
        // It requires storing Timer reference from createTimer() and using it later,
        // which needs manual refactoring to ScheduledFuture<?>.
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;

                public class TimerFieldBean {
                    @Resource
                    private TimerService timerService;

                    private Timer myTimer;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        myTimer = timerService.createTimer(1000L, "info");
                    }

                    public void stop() {
                        if (myTimer != null) {
                            myTimer.cancel();
                        }
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;
                import org.springframework.context.annotation.Profile;

                @NeedsReview(reason = "Programmatic EJB timers require refactoring to Spring TaskScheduler", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "@Timeout / TimerService", suggestedAction = "Replace TimerService with TaskScheduler and schedule via schedule()/scheduleAtFixedRate()")
                @Profile("manual-migration")
                public class TimerFieldBean {
                    @Resource
                    private TimerService timerService;

                    private Timer myTimer;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        myTimer = timerService.createTimer(1000L, "info");
                    }

                    public void stop() {
                        if (myTimer != null) {
                            myTimer.cancel();
                        }
                    }
                }
                """
            )
        );
    }

    // ========== getTimers() iteration - MUST trigger marker ==========

    @Test
    void getTimersIterationTriggersMarker() {
        // P0.3: timerService.getTimers() returns all active timers.
        // This pattern is used for timer management (e.g., cancel all timers).
        // Cannot be auto-migrated because Spring has no equivalent API.
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;
                import java.util.Collection;

                public class GetTimersBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void cancelAll() {
                        Collection<Timer> timers = timerService.getTimers();
                        for (Timer t : timers) {
                            t.cancel();
                        }
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;
                import org.springframework.context.annotation.Profile;

                import java.util.Collection;

                @NeedsReview(reason = "Programmatic EJB timers require refactoring to Spring TaskScheduler", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "@Timeout / TimerService", suggestedAction = "Replace TimerService with TaskScheduler and schedule via schedule()/scheduleAtFixedRate()")
                @Profile("manual-migration")
                public class GetTimersBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void cancelAll() {
                        Collection<Timer> timers = timerService.getTimers();
                        for (Timer t : timers) {
                            t.cancel();
                        }
                    }
                }
                """
            )
        );
    }

    // ========== TimerHandle usage - MUST trigger marker ==========

    @Test
    void timerHandleUsageTriggersMarker() {
        // P0.3: TimerHandle is used for persistent timer references.
        // It allows storing a serializable reference to a timer for later retrieval.
        // Cannot be auto-migrated because Spring has no equivalent concept.
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class TimerHandleBean {
                    @Resource
                    private TimerService timerService;

                    private TimerHandle savedHandle;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        savedHandle = timer.getHandle();
                    }

                    public void cancelLater() {
                        if (savedHandle != null) {
                            savedHandle.getTimer().cancel();
                        }
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;
                import org.springframework.context.annotation.Profile;

                @NeedsReview(reason = "Programmatic EJB timers require refactoring to Spring TaskScheduler", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "@Timeout / TimerService", suggestedAction = "Replace TimerService with TaskScheduler and schedule via schedule()/scheduleAtFixedRate()")
                @Profile("manual-migration")
                public class TimerHandleBean {
                    @Resource
                    private TimerService timerService;

                    private TimerHandle savedHandle;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        savedHandle = timer.getHandle();
                    }

                    public void cancelLater() {
                        if (savedHandle != null) {
                            savedHandle.getTimer().cancel();
                        }
                    }
                }
                """
            )
        );
    }

    // ========== Timer as local variable (from createTimer return) - triggers marker ==========

    @Test
    void timerAsLocalVariableTriggersMarker() {
        // P0.3: Timer returned from createTimer() as local variable.
        // When Timer-API methods (getInfo, cancel, etc.) are called on the result,
        // this indicates complex timer management that needs manual review.
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;

                public class LocalTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void scheduleAndLog() {
                        Timer t = timerService.createTimer(1000L, "info");
                        System.out.println("Created timer, next timeout: " + t.getNextTimeout());
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;
                import org.springframework.context.annotation.Profile;

                @NeedsReview(reason = "Programmatic EJB timers require refactoring to Spring TaskScheduler", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "@Timeout / TimerService", suggestedAction = "Replace TimerService with TaskScheduler and schedule via schedule()/scheduleAtFixedRate()")
                @Profile("manual-migration")
                public class LocalTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void scheduleAndLog() {
                        Timer t = timerService.createTimer(1000L, "info");
                        System.out.println("Created timer, next timeout: " + t.getNextTimeout());
                    }
                }
                """
            )
        );
    }

    // ========== Timer as parameter in @Timeout - specific handling ==========

    @Test
    void timerAsParameterInTimeoutTriggersMarker() {
        // P0.3: @Timeout with Timer parameter that uses timer.getInfo().
        // MigrateEjbProgrammaticTimers does not handle this (requires no-param @Timeout).
        // MarkEjbTimerServiceForReview catches it via @Timeout detection.
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;

                public class TimeoutWithTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        String info = (String) timer.getInfo();
                        System.out.println("Timeout with info: " + info);
                    }

                    public void start() {
                        timerService.createTimer(1000L, "task-info");
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;
                import org.springframework.context.annotation.Profile;

                @NeedsReview(reason = "Programmatic EJB timers require refactoring to Spring TaskScheduler", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "@Timeout / TimerService", suggestedAction = "Replace TimerService with TaskScheduler and schedule via schedule()/scheduleAtFixedRate()")
                @Profile("manual-migration")
                public class TimeoutWithTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        String info = (String) timer.getInfo();
                        System.out.println("Timeout with info: " + info);
                    }

                    public void start() {
                        timerService.createTimer(1000L, "task-info");
                    }
                }
                """
            )
        );
    }

    // ========== Verify marker catches persistent timer (default) ==========

    @Test
    void persistentTimerDefaultTriggersMarker() {
        // P0.1 + P0.3: createTimer without TimerConfig uses EJB default persistent=true.
        // MigrateEjbProgrammaticTimers does NOT migrate this (correctly).
        // MarkEjbTimerServiceForReview MUST catch it.
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;

                public class PersistentTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        // No TimerConfig = EJB default persistent=true
                        timerService.createTimer(1000L, "info");
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.TimerService;
                import org.springframework.context.annotation.Profile;

                @NeedsReview(reason = "Programmatic EJB timers require refactoring to Spring TaskScheduler", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "@Timeout / TimerService", suggestedAction = "Replace TimerService with TaskScheduler and schedule via schedule()/scheduleAtFixedRate()")
                @Profile("manual-migration")
                public class PersistentTimerBean {
                    @Resource
                    private TimerService timerService;

                    @Timeout
                    public void onTimeout() {
                    }

                    public void start() {
                        // No TimerConfig = EJB default persistent=true
                        timerService.createTimer(1000L, "info");
                    }
                }
                """
            )
        );
    }

    // ========== Complex: Multiple Timer APIs in one class ==========

    @Test
    void multipleTimerApiCallsTriggersMarker() {
        // P0.3: Class using multiple Timer-API methods.
        // All should be caught by the marker.
        rewriteRun(
            java(NEEDS_REVIEW_STUB),
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;

                public class ComplexTimerBean {
                    @Resource
                    private TimerService timerService;

                    private Timer activeTimer;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        String info = (String) timer.getInfo();
                        long remaining = timer.getTimeRemaining();
                        System.out.println("Info: " + info + ", remaining: " + remaining);

                        if (remaining < 1000) {
                            timer.cancel();
                        }
                    }

                    public void start() {
                        activeTimer = timerService.createTimer(5000L, "complex-task");
                    }

                    public void checkStatus() {
                        if (activeTimer != null) {
                            System.out.println("Next: " + activeTimer.getNextTimeout());
                        }
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import jakarta.annotation.Resource;
                import jakarta.ejb.Timeout;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerService;
                import org.springframework.context.annotation.Profile;

                @NeedsReview(reason = "Programmatic EJB timers require refactoring to Spring TaskScheduler", category = NeedsReview.Category.MANUAL_MIGRATION, originalCode = "@Timeout / TimerService", suggestedAction = "Replace TimerService with TaskScheduler and schedule via schedule()/scheduleAtFixedRate()")
                @Profile("manual-migration")
                public class ComplexTimerBean {
                    @Resource
                    private TimerService timerService;

                    private Timer activeTimer;

                    @Timeout
                    public void onTimeout(Timer timer) {
                        String info = (String) timer.getInfo();
                        long remaining = timer.getTimeRemaining();
                        System.out.println("Info: " + info + ", remaining: " + remaining);

                        if (remaining < 1000) {
                            timer.cancel();
                        }
                    }

                    public void start() {
                        activeTimer = timerService.createTimer(5000L, "complex-task");
                    }

                    public void checkStatus() {
                        if (activeTimer != null) {
                            System.out.println("Next: " + activeTimer.getNextTimeout());
                        }
                    }
                }
                """
            )
        );
    }

    // ========== AUTO-MIGRATED: Simple case with no Timer-API calls remaining ==========
    // NOTE: This test is in MigrateEjbProgrammaticTimersTest.replacesCreateSingleActionTimerWithTimerConfigPersistentFalse
    // The guarantee is verified by the fact that the migrated output has NO Timer-API calls.
    // Here we document that the marker does NOT trigger for classes that have already been migrated
    // (i.e., after migration, TimerService and @Timeout are removed, so marker has nothing to catch).
}
