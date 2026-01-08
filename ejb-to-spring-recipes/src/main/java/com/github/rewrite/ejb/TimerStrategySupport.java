package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import com.github.rewrite.ejb.marker.TimerStrategyMarker;
import org.openrewrite.java.tree.J;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

public final class TimerStrategySupport {

    private TimerStrategySupport() {
    }

    public static boolean isStrategy(J.CompilationUnit cu, ProjectConfiguration.TimerStrategy strategy) {
        return resolveStrategy(cu) == strategy;
    }

    public static boolean isQuartz(J.CompilationUnit cu) {
        return resolveStrategy(cu) == ProjectConfiguration.TimerStrategy.QUARTZ;
    }

    public static ProjectConfiguration.TimerStrategy resolveStrategy(J.CompilationUnit cu) {
        if (cu == null) {
            return ProjectConfiguration.mavenDefaults().getTimerStrategy();
        }
        Optional<TimerStrategyMarker> marker = cu.getMarkers().findFirst(TimerStrategyMarker.class);
        if (marker.isPresent()) {
            return marker.get().getStrategy();
        }
        Path sourcePath = cu.getSourcePath();
        if (sourcePath != null) {
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(sourcePath));
            return config.getTimerStrategy();
        }
        return ProjectConfiguration.mavenDefaults().getTimerStrategy();
    }

    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = sourcePath.toAbsolutePath().getParent();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("project.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get(System.getProperty("user.dir"));
    }
}
