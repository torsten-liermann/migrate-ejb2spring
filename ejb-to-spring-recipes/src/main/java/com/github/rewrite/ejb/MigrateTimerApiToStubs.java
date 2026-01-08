package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;

/**
 * Migrates EJB Timer API types to compile-time stub types in migration-annotations.
 * <p>
 * This recipe changes imports from jakarta.ejb/javax.ejb Timer types to
 * com.github.migration.timer stub types. The stub types allow the code to
 * compile while being marked for manual review.
 * <p>
 * Type mappings:
 * <ul>
 *   <li>jakarta.ejb.Timer → com.github.migration.timer.Timer</li>
 *   <li>jakarta.ejb.TimerService → com.github.migration.timer.TimerService</li>
 *   <li>jakarta.ejb.TimerConfig → com.github.migration.timer.TimerConfig</li>
 *   <li>jakarta.ejb.ScheduleExpression → com.github.migration.timer.ScheduleExpression</li>
 *   <li>jakarta.ejb.TimerHandle → com.github.migration.timer.TimerHandle</li>
 *   <li>(same for javax.ejb.* namespace)</li>
 * </ul>
 * <p>
 * Note: This recipe should run AFTER MarkEjbTimerServiceForReview so that
 * @NeedsReview and @Profile("manual-migration") annotations are already added.
 *
 * @see com.github.rewrite.ejb.MarkEjbTimerServiceForReview
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateTimerApiToStubs extends Recipe {

    // jakarta.ejb types
    private static final String JAKARTA_TIMER = "jakarta.ejb.Timer";
    private static final String JAKARTA_TIMER_SERVICE = "jakarta.ejb.TimerService";
    private static final String JAKARTA_TIMER_CONFIG = "jakarta.ejb.TimerConfig";
    private static final String JAKARTA_SCHEDULE_EXPRESSION = "jakarta.ejb.ScheduleExpression";
    private static final String JAKARTA_TIMER_HANDLE = "jakarta.ejb.TimerHandle";

    // javax.ejb types (legacy)
    private static final String JAVAX_TIMER = "javax.ejb.Timer";
    private static final String JAVAX_TIMER_SERVICE = "javax.ejb.TimerService";
    private static final String JAVAX_TIMER_CONFIG = "javax.ejb.TimerConfig";
    private static final String JAVAX_SCHEDULE_EXPRESSION = "javax.ejb.ScheduleExpression";
    private static final String JAVAX_TIMER_HANDLE = "javax.ejb.TimerHandle";

    // Stub types in migration-annotations
    private static final String STUB_TIMER = "com.github.migration.timer.Timer";
    private static final String STUB_TIMER_SERVICE = "com.github.migration.timer.TimerService";
    private static final String STUB_TIMER_CONFIG = "com.github.migration.timer.TimerConfig";
    private static final String STUB_SCHEDULE_EXPRESSION = "com.github.migration.timer.ScheduleExpression";
    private static final String STUB_TIMER_HANDLE = "com.github.migration.timer.TimerHandle";

    @Override
    public String getDisplayName() {
        return "Migrate EJB Timer API to stub types";
    }

    @Override
    public String getDescription() {
        return "Changes imports from jakarta.ejb/javax.ejb Timer types to com.github.migration.timer " +
               "stub types. The stub types allow code to compile while marked for manual review.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate = Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_TIMER, false),
                new UsesType<>(JAKARTA_TIMER_SERVICE, false),
                new UsesType<>(JAKARTA_TIMER_CONFIG, false),
                new UsesType<>(JAKARTA_SCHEDULE_EXPRESSION, false),
                new UsesType<>(JAKARTA_TIMER_HANDLE, false),
                new UsesType<>(JAVAX_TIMER, false),
                new UsesType<>(JAVAX_TIMER_SERVICE, false),
                new UsesType<>(JAVAX_TIMER_CONFIG, false),
                new UsesType<>(JAVAX_SCHEDULE_EXPRESSION, false),
                new UsesType<>(JAVAX_TIMER_HANDLE, false)
            ),
            new TimerApiStubVisitor()
        );
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (TimerStrategySupport.isStrategy(cu, ProjectConfiguration.TimerStrategy.QUARTZ)) {
                    return cu;
                }
                return (J.CompilationUnit) delegate.visit(cu, ctx);
            }
        };
    }

    private static class TimerApiStubVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);

            // Apply ChangeType for all Timer API types - jakarta namespace
            c = (J.CompilationUnit) new ChangeType(JAKARTA_TIMER, STUB_TIMER, true)
                    .getVisitor().visit(c, ctx);
            c = (J.CompilationUnit) new ChangeType(JAKARTA_TIMER_SERVICE, STUB_TIMER_SERVICE, true)
                    .getVisitor().visit(c, ctx);
            c = (J.CompilationUnit) new ChangeType(JAKARTA_TIMER_CONFIG, STUB_TIMER_CONFIG, true)
                    .getVisitor().visit(c, ctx);
            c = (J.CompilationUnit) new ChangeType(JAKARTA_SCHEDULE_EXPRESSION, STUB_SCHEDULE_EXPRESSION, true)
                    .getVisitor().visit(c, ctx);
            c = (J.CompilationUnit) new ChangeType(JAKARTA_TIMER_HANDLE, STUB_TIMER_HANDLE, true)
                    .getVisitor().visit(c, ctx);

            // Apply ChangeType for all Timer API types - javax namespace (legacy)
            c = (J.CompilationUnit) new ChangeType(JAVAX_TIMER, STUB_TIMER, true)
                    .getVisitor().visit(c, ctx);
            c = (J.CompilationUnit) new ChangeType(JAVAX_TIMER_SERVICE, STUB_TIMER_SERVICE, true)
                    .getVisitor().visit(c, ctx);
            c = (J.CompilationUnit) new ChangeType(JAVAX_TIMER_CONFIG, STUB_TIMER_CONFIG, true)
                    .getVisitor().visit(c, ctx);
            c = (J.CompilationUnit) new ChangeType(JAVAX_SCHEDULE_EXPRESSION, STUB_SCHEDULE_EXPRESSION, true)
                    .getVisitor().visit(c, ctx);
            c = (J.CompilationUnit) new ChangeType(JAVAX_TIMER_HANDLE, STUB_TIMER_HANDLE, true)
                    .getVisitor().visit(c, ctx);

            return c;
        }
    }
}
