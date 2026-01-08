package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.marker.TimerStrategyMarker;
import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.Parser;
import org.openrewrite.RecipeRun;
import org.openrewrite.Result;
import org.openrewrite.SourceFile;
import org.openrewrite.internal.InMemoryLargeSourceSet;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ResolveTimerStrategyTest {

    private J.CompilationUnit runRecipe(String code, String path) {
        InMemoryExecutionContext ctx = new InMemoryExecutionContext(Throwable::printStackTrace);
        JavaParser parser = JavaParser.fromJavaVersion()
            .classpath("jakarta.jakartaee-api")
            .build();

        Parser.Input input = Parser.Input.fromString(Paths.get(path), code);
        List<SourceFile> sources = parser.parseInputs(List.of(input), Path.of("."), ctx).toList();

        RecipeRun run = new ResolveTimerStrategy().run(new InMemoryLargeSourceSet(sources), ctx);
        List<Result> results = run.getChangeset().getAllResults();

        SourceFile after = results.isEmpty() ? sources.get(0) : results.get(0).getAfter();
        assertThat(after).isInstanceOf(J.CompilationUnit.class);
        return (J.CompilationUnit) after;
    }

    @Test
    void schedulePersistentDefaultRequiresQuartz() {
        J.CompilationUnit cu = runRecipe(
            """
            import jakarta.ejb.Schedule;

            public class AuditJob {
                @Schedule(minute = \"*/5\")
                public void run() {
                }
            }
            """,
            "src/main/java/com/example/AuditJob.java"
        );

        TimerStrategyMarker marker = cu.getMarkers().findFirst(TimerStrategyMarker.class).orElse(null);
        assertThat(marker).isNotNull();
        assertThat(marker.getStrategy()).isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
    }

    @Test
    void scheduleWithTimerParameterRequiresTaskScheduler() {
        J.CompilationUnit cu = runRecipe(
            """
            import jakarta.ejb.Schedule;
            import jakarta.ejb.Timer;

            public class CleanupJob {
                @Schedule(minute = \"0\", hour = \"1\", persistent = false)
                public void cleanup(Timer timer) {
                }
            }
            """,
            "src/main/java/com/example/CleanupJob.java"
        );

        TimerStrategyMarker marker = cu.getMarkers().findFirst(TimerStrategyMarker.class).orElse(null);
        assertThat(marker).isNotNull();
        assertThat(marker.getStrategy()).isEqualTo(ProjectConfiguration.TimerStrategy.TASKSCHEDULER);
    }

    @Test
    void createTimerWithTimerConfigPersistentFalseRequiresTaskScheduler() {
        J.CompilationUnit cu = runRecipe(
            """
            import jakarta.ejb.TimerConfig;
            import jakarta.ejb.TimerService;
            import jakarta.annotation.Resource;

            public class ProgrammaticTimers {
                @Resource
                private TimerService timerService;

                public void init() {
                    timerService.createTimer(1000L, new TimerConfig("info", false));
                }
            }
            """,
            "src/main/java/com/example/ProgrammaticTimers.java"
        );

        TimerStrategyMarker marker = cu.getMarkers().findFirst(TimerStrategyMarker.class).orElse(null);
        assertThat(marker).isNotNull();
        assertThat(marker.getStrategy()).isEqualTo(ProjectConfiguration.TimerStrategy.TASKSCHEDULER);
    }

    @Test
    void timerConfigSetPersistentFalseRequiresTaskScheduler() {
        J.CompilationUnit cu = runRecipe(
            """
            import jakarta.ejb.TimerConfig;
            import jakarta.ejb.TimerService;
            import jakarta.annotation.Resource;

            public class ProgrammaticTimers {
                @Resource
                private TimerService timerService;

                public void init() {
                    TimerConfig cfg = new TimerConfig();
                    cfg.setPersistent(false);
                    timerService.createTimer(1000L, cfg);
                }
            }
            """,
            "src/main/java/com/example/ProgrammaticTimers.java"
        );

        TimerStrategyMarker marker = cu.getMarkers().findFirst(TimerStrategyMarker.class).orElse(null);
        assertThat(marker).isNotNull();
        assertThat(marker.getStrategy()).isEqualTo(ProjectConfiguration.TimerStrategy.TASKSCHEDULER);
    }

    @Test
    void timerServiceGetTimersRequiresQuartz() {
        J.CompilationUnit cu = runRecipe(
            """
            import jakarta.ejb.TimerService;
            import jakarta.annotation.Resource;

            public class ProgrammaticTimers {
                @Resource
                private TimerService timerService;

                public void check() {
                    timerService.getTimers();
                }
            }
            """,
            "src/main/java/com/example/ProgrammaticTimers.java"
        );

        TimerStrategyMarker marker = cu.getMarkers().findFirst(TimerStrategyMarker.class).orElse(null);
        assertThat(marker).isNotNull();
        assertThat(marker.getStrategy()).isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
    }
}
