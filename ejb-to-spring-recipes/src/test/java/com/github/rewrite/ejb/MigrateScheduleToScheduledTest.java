package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class MigrateScheduleToScheduledTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateScheduleToScheduled())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api", "spring-context"));
    }

    @DocumentExample
    @Test
    void migrateSimpleSchedule() {
        // Only @Schedule with persistent=false explicit is migrated
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;

                public class CargoInspector {
                    @Schedule(minute = "*/2", hour = "*", persistent = false)
                    public void inspectCargo() {
                        // inspect cargo
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Scheduled;

                public class CargoInspector {
                    @Scheduled(cron = "0 */2 * * * *")
                    public void inspectCargo() {
                        // inspect cargo
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateWithSecond() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;

                public class HealthChecker {
                    @Schedule(second = "30", minute = "0", hour = "8", persistent = false)
                    public void checkHealth() {
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Scheduled;

                public class HealthChecker {
                    @Scheduled(cron = "30 0 8 * * *")
                    public void checkHealth() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateWithDayOfWeek() {
        // EJB 3.2 defaults: second="0", minute="0", hour="0"
        // When only hour and dayOfWeek are specified, minute defaults to "0"
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;

                public class WeeklyReport {
                    @Schedule(hour = "6", dayOfWeek = "Mon", persistent = false)
                    public void generateReport() {
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Scheduled;

                public class WeeklyReport {
                    @Scheduled(cron = "0 0 6 * * Mon")
                    public void generateReport() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateWithTimezone() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;

                public class TimezoneScheduler {
                    @Schedule(minute = "0", hour = "12", timezone = "Europe/Berlin", persistent = false)
                    public void scheduledTask() {
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Scheduled;

                public class TimezoneScheduler {
                    @Scheduled(cron = "0 0 12 * * *", zone = "Europe/Berlin")
                    public void scheduledTask() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateComplexSchedule() {
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;

                public class ComplexScheduler {
                    @Schedule(second = "0", minute = "30", hour = "9", dayOfMonth = "1", month = "*", dayOfWeek = "*", persistent = false)
                    public void monthlyTask() {
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Scheduled;

                public class ComplexScheduler {
                    @Scheduled(cron = "0 30 9 1 * *")
                    public void monthlyTask() {
                    }
                }
                """
            )
        );
    }

    @Test
    void skipsYearAttribute() {
        // year attribute is skipped - marker recipe handles it (preserves year value)
        // Spring cron has no year field
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;

                public class YearlyTask {
                    @Schedule(minute = "0", hour = "0", dayOfMonth = "1", month = "1", year = "2025", persistent = false)
                    public void runYearly() {
                    }
                }
                """
            )
        );
    }

    @Test
    void skipsNonLiteralValues() {
        // Non-literal values (constants/field references) are skipped - marker recipe handles them
        rewriteRun(
            // Provide the constants class
            java(
                """
                public class ScheduleConstants {
                    public static final String EVERY_HOUR = "*";
                }
                """
            ),
            java(
                """
                import jakarta.ejb.Schedule;

                public class ConstantScheduler {
                    @Schedule(minute = "0", hour = ScheduleConstants.EVERY_HOUR, persistent = false)
                    public void runWithConstant() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateSchedulesContainerWithMultipleSchedules() {
        // Test @Schedules container annotation with multiple @Schedule annotations
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Schedules;

                public class MultiScheduler {
                    @Schedules({
                        @Schedule(hour = "8", minute = "0", persistent = false),
                        @Schedule(hour = "12", minute = "0", persistent = false),
                        @Schedule(hour = "18", minute = "0", persistent = false)
                    })
                    public void runThreeTimesDaily() {
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Scheduled;

                public class MultiScheduler {
                    @Scheduled(cron = "0 0 8 * * *")
                    @Scheduled(cron = "0 0 12 * * *")
                    @Scheduled(cron = "0 0 18 * * *")
                    public void runThreeTimesDaily() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateSchedulesContainerWithSingleSchedule() {
        // Edge case: @Schedules with only one @Schedule
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Schedules;

                public class SingleInContainer {
                    @Schedules({
                        @Schedule(hour = "6", minute = "30", persistent = false)
                    })
                    public void morningTask() {
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Scheduled;

                public class SingleInContainer {
                    @Scheduled(cron = "0 30 6 * * *")
                    public void morningTask() {
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateSchedulesContainerWithValueAssignment() {
        // Edge case: @Schedules(value = {...}) form
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Schedules;

                public class ValueAssignedSchedules {
                    @Schedules(value = {
                        @Schedule(hour = "8", minute = "0", persistent = false),
                        @Schedule(hour = "12", minute = "0", persistent = false)
                    })
                    public void runTwiceDaily() {
                    }
                }
                """,
                """
                import org.springframework.scheduling.annotation.Scheduled;

                public class ValueAssignedSchedules {
                    @Scheduled(cron = "0 0 8 * * *")
                    @Scheduled(cron = "0 0 12 * * *")
                    public void runTwiceDaily() {
                    }
                }
                """
            )
        );
    }

    @Test
    void skipsTimerParameterMethods() {
        // Methods with Timer parameter are skipped - marker recipe handles them
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Timer;

                public class TimerParamScheduler {
                    @Schedule(minute = "*/5", hour = "*", persistent = false)
                    public void scheduledTask(Timer timer) {
                        System.out.println("Running scheduled task");
                    }
                }
                """
            )
        );
    }

    @Test
    void skipsTimerUsedInBody() {
        // Methods with Timer parameter (used or not) are skipped - marker recipe handles them
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;
                import jakarta.ejb.Timer;

                public class TimerUsedScheduler {
                    @Schedule(minute = "*/5", hour = "*", persistent = false)
                    public void scheduledTask(Timer timer) {
                        System.out.println("Timer info: " + timer.getInfo());
                    }
                }
                """
            )
        );
    }

    @Test
    void skipsPersistentTimerByDefault() {
        // EJB default for persistent is true - no explicit setting means persistent
        // These are skipped and handled by marker recipe
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;

                public class DefaultPersistentScheduler {
                    @Schedule(minute = "0", hour = "8")
                    public void persistentTask() {
                    }
                }
                """
            )
        );
    }

    @Test
    void skipsExplicitPersistentTrue() {
        // Explicit persistent=true is skipped
        rewriteRun(
            java(
                """
                import jakarta.ejb.Schedule;

                public class ExplicitPersistentScheduler {
                    @Schedule(minute = "0", hour = "8", persistent = true)
                    public void persistentTask() {
                    }
                }
                """
            )
        );
    }
}
