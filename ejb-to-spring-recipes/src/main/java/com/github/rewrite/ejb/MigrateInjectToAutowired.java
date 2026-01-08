package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Migrates CDI @Inject to Spring @Autowired.
 * <p>
 * Handles both javax.inject and jakarta.inject namespaces.
 * Note: Spring also supports @Inject (JSR-330) natively, so this migration is optional.
 * This recipe is provided for projects that want to use pure Spring annotations.
 * <p>
 * Migration behavior is controlled by project.yaml:
 * <pre>
 * migration:
 *   inject:
 *     strategy: keep-jsr330 | migrate-to-spring
 * </pre>
 * Default is keep-jsr330, which preserves @Inject annotations.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateInjectToAutowired extends Recipe {

    private static final String JAVAX_INJECT = "javax.inject.Inject";
    private static final String JAKARTA_INJECT = "jakarta.inject.Inject";
    private static final String SPRING_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";

    @Option(displayName = "Inject strategy override",
            description = "Override project.yaml inject strategy: keep-jsr330 or migrate-to-spring. " +
                          "If not set, project.yaml (or defaults) are used. Default strategy is keep-jsr330.",
            example = "migrate-to-spring",
            required = false)
    @Nullable
    String strategy;

    public MigrateInjectToAutowired() {
        this.strategy = null;
    }

    public MigrateInjectToAutowired(@Nullable String strategy) {
        this.strategy = strategy;
    }

    @Override
    public String getDisplayName() {
        return "Migrate @Inject to @Autowired";
    }

    @Override
    public String getDescription() {
        return "Converts CDI/JSR-330 @Inject annotations to Spring @Autowired. " +
               "Supports both javax.inject and jakarta.inject namespaces. " +
               "Migration only occurs when inject strategy is 'migrate-to-spring' (default is 'keep-jsr330').";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate = Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAVAX_INJECT, false),
                new UsesType<>(JAKARTA_INJECT, false)
            ),
            new InjectToAutowiredVisitor()
        );

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                // Check if migration is enabled by configuration
                if (!shouldMigrate(cu.getSourcePath())) {
                    return cu;
                }
                return (J.CompilationUnit) delegate.visit(cu, ctx);
            }
        };
    }

    /**
     * Checks if migration should occur based on project.yaml or strategy override.
     * Returns true only if strategy is MIGRATE_TO_SPRING (default is KEEP_JSR330).
     */
    private boolean shouldMigrate(@Nullable Path sourcePath) {
        ProjectConfiguration.InjectStrategy override = ProjectConfiguration.InjectStrategy.fromString(strategy);
        if (strategy != null && override == null) {
            System.err.println("Warning: Unknown inject strategy override '" + strategy +
                    "', using project.yaml/defaults.");
        }
        if (override != null) {
            return override == ProjectConfiguration.InjectStrategy.MIGRATE_TO_SPRING;
        }
        if (sourcePath == null) {
            return ProjectConfiguration.mavenDefaults().getInjectStrategy()
                    == ProjectConfiguration.InjectStrategy.MIGRATE_TO_SPRING;
        }
        ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(sourcePath));
        return config.getInjectStrategy() == ProjectConfiguration.InjectStrategy.MIGRATE_TO_SPRING;
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

    private static class InjectToAutowiredVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);

            if (TypeUtils.isOfClassType(a.getType(), JAVAX_INJECT)) {
                maybeAddImport(SPRING_AUTOWIRED);
                maybeRemoveImport(JAVAX_INJECT);
                doAfterVisit(new ChangeType(JAVAX_INJECT, SPRING_AUTOWIRED, true).getVisitor());
                return a;
            }
            if (TypeUtils.isOfClassType(a.getType(), JAKARTA_INJECT)) {
                maybeAddImport(SPRING_AUTOWIRED);
                maybeRemoveImport(JAKARTA_INJECT);
                doAfterVisit(new ChangeType(JAKARTA_INJECT, SPRING_AUTOWIRED, true).getVisitor());
                return a;
            }

            return a;
        }
    }
}
