package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateTimerApiToStubsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateTimerApiToStubs())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "javax.ejb-api"));
    }

    @DocumentExample
    @Test
    void migrateTimerServiceInjection() {
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.TimerService;

                public class SchedulerBean {
                    @Resource
                    private TimerService timerService;
                }
                """,
                """
                import com.github.migration.timer.TimerService;
                import jakarta.annotation.Resource;

                public class SchedulerBean {
                    @Resource
                    private TimerService timerService;
                }
                """
            )
        );
    }

    @Test
    void migrateTimerUsage() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Timer;

                public class TimeoutHandler {
                    public void handleTimeout(Timer timer) {
                        timer.cancel();
                    }
                }
                """,
                """
                import com.github.migration.timer.Timer;

                public class TimeoutHandler {
                    public void handleTimeout(Timer timer) {
                        timer.cancel();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateTimerConfigUsage() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerService;

                public class SchedulerBean {
                    private TimerService timerService;

                    public void scheduleTask() {
                        TimerConfig config = new TimerConfig("myTask", false);
                        timerService.createSingleActionTimer(5000, config);
                    }
                }
                """,
                """
                import com.github.migration.timer.TimerConfig;
                import com.github.migration.timer.TimerService;

                public class SchedulerBean {
                    private TimerService timerService;

                    public void scheduleTask() {
                        TimerConfig config = new TimerConfig("myTask", false);
                        timerService.createSingleActionTimer(5000, config);
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateScheduleExpressionUsage() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.TimerService;

                public class CronScheduler {
                    private TimerService timerService;

                    public void scheduleDaily() {
                        ScheduleExpression schedule = new ScheduleExpression()
                            .hour("0")
                            .minute("0");
                        timerService.createCalendarTimer(schedule);
                    }
                }
                """,
                """
                import com.github.migration.timer.ScheduleExpression;
                import com.github.migration.timer.TimerService;

                public class CronScheduler {
                    private TimerService timerService;

                    public void scheduleDaily() {
                        ScheduleExpression schedule = new ScheduleExpression()
                            .hour("0")
                            .minute("0");
                        timerService.createCalendarTimer(schedule);
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateTimerHandleUsage() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerHandle;
                import java.io.Serializable;

                public class TimerPersistence {
                    public Serializable saveTimerHandle(Timer timer) {
                        TimerHandle handle = timer.getHandle();
                        return handle;
                    }
                }
                """,
                """
                import com.github.migration.timer.Timer;
                import com.github.migration.timer.TimerHandle;

                import java.io.Serializable;

                public class TimerPersistence {
                    public Serializable saveTimerHandle(Timer timer) {
                        TimerHandle handle = timer.getHandle();
                        return handle;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateJavaxTimerService() {
        rewriteRun(
            java(
                """
                import javax.ejb.TimerService;

                public class LegacyScheduler {
                    private TimerService timerService;
                }
                """,
                """
                import com.github.migration.timer.TimerService;

                public class LegacyScheduler {
                    private TimerService timerService;
                }
                """
            )
        );
    }

    @Test
    void migrateJavaxTimerAndConfig() {
        rewriteRun(
            java(
                """
                import javax.ejb.Timer;
                import javax.ejb.TimerConfig;

                public class LegacyTimeoutBean {
                    public void timeout(Timer timer) {
                        // handle timeout
                    }

                    public TimerConfig createConfig() {
                        return new TimerConfig("data", true);
                    }
                }
                """,
                """
                import com.github.migration.timer.Timer;
                import com.github.migration.timer.TimerConfig;

                public class LegacyTimeoutBean {
                    public void timeout(Timer timer) {
                        // handle timeout
                    }

                    public TimerConfig createConfig() {
                        return new TimerConfig("data", true);
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeWhenNoTimerTypes() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Stateless;

                @Stateless
                public class SimpleService {
                    public void doWork() {
                        // no timer usage
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateAllTimerTypesInOneClass() {
        rewriteRun(
            java(
                """
                import jakarta.annotation.Resource;
                import jakarta.ejb.ScheduleExpression;
                import jakarta.ejb.Timer;
                import jakarta.ejb.TimerConfig;
                import jakarta.ejb.TimerHandle;
                import jakarta.ejb.TimerService;

                public class FullTimerBean {
                    @Resource
                    private TimerService timerService;

                    public Timer scheduleTask() {
                        TimerConfig config = new TimerConfig("task", false);
                        return timerService.createSingleActionTimer(1000, config);
                    }

                    public Timer scheduleCalendar() {
                        ScheduleExpression expr = new ScheduleExpression().hour("*");
                        return timerService.createCalendarTimer(expr);
                    }

                    public void handleTimeout(Timer timer) {
                        TimerHandle handle = timer.getHandle();
                        timer.cancel();
                    }
                }
                """,
                """
                import com.github.migration.timer.*;
                import jakarta.annotation.Resource;

                public class FullTimerBean {
                    @Resource
                    private TimerService timerService;

                    public Timer scheduleTask() {
                        TimerConfig config = new TimerConfig("task", false);
                        return timerService.createSingleActionTimer(1000, config);
                    }

                    public Timer scheduleCalendar() {
                        ScheduleExpression expr = new ScheduleExpression().hour("*");
                        return timerService.createCalendarTimer(expr);
                    }

                    public void handleTimeout(Timer timer) {
                        TimerHandle handle = timer.getHandle();
                        timer.cancel();
                    }
                }
                """
            )
        );
    }
}
