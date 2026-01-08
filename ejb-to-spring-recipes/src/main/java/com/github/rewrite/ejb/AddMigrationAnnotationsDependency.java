package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.tree.J;
import org.openrewrite.maven.AddDependency;
import org.openrewrite.xml.tree.Xml;

import java.util.*;

/**
 * Adds the migration-annotations dependency to Maven POM files when EJB code is detected.
 * <p>
 * The migration-annotations artifact provides:
 * <ul>
 *   <li>{@code @NeedsReview} - Marks code requiring manual review</li>
 *   <li>{@code @EjbSchedule} - Preserves EJB schedule semantics</li>
 *   <li>{@code @EjbTimeout} - Preserves EJB timeout semantics</li>
 *   <li>{@code @EjbTransactionAttribute} - Preserves EJB transaction attribute semantics</li>
 *   <li>{@code @EjbApplicationException} - Preserves EJB application exception semantics</li>
 *   <li>Other EJB marker annotations for migration tracking</li>
 * </ul>
 * <p>
 * This recipe detects EJB/Jakarta EE annotations that will trigger marker annotations
 * to be added by other recipes, and proactively adds the dependency so the migrated
 * code can compile.
 * <p>
 * Detection is based on source EJB annotations (not marker imports) because this recipe
 * runs as part of a recipe chain where marker annotations are added by other recipes
 * in the same run. ScanningRecipes scan original sources before modifications.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddMigrationAnnotationsDependency extends ScanningRecipe<AddMigrationAnnotationsDependency.Accumulator> {

    private static final String MIGRATION_ANNOTATIONS_GROUP_ID = "com.github.migration";
    private static final String MIGRATION_ANNOTATIONS_ARTIFACT_ID = "migration-annotations";
    private static final String DEFAULT_VERSION = "1.0.0-SNAPSHOT";

    // EJB annotations that will trigger marker annotations being added
    // These are the SOURCE annotations we detect, not the marker annotations themselves

    // Remote interfaces → @NeedsReview will be added
    private static final Set<String> REMOTE_ANNOTATIONS = Set.of(
        "jakarta.ejb.Remote",
        "javax.ejb.Remote"
    );

    // Timer/Schedule annotations → @EjbSchedule/@EjbTimeout markers may be added
    private static final Set<String> TIMER_ANNOTATIONS = Set.of(
        "jakarta.ejb.Schedule",
        "jakarta.ejb.Schedules",
        "jakarta.ejb.Timeout",
        "javax.ejb.Schedule",
        "javax.ejb.Schedules",
        "javax.ejb.Timeout"
    );

    // Transaction annotations → @EjbTransactionAttribute markers may be added
    private static final Set<String> TRANSACTION_ANNOTATIONS = Set.of(
        "jakarta.ejb.TransactionAttribute",
        "jakarta.ejb.TransactionManagement",
        "javax.ejb.TransactionAttribute",
        "javax.ejb.TransactionManagement"
    );

    // Application exception → @EjbApplicationException markers may be added
    private static final Set<String> APP_EXCEPTION_ANNOTATIONS = Set.of(
        "jakarta.ejb.ApplicationException",
        "javax.ejb.ApplicationException"
    );

    // Stateful/Remove → @EjbStateful/@EjbRemove markers may be added
    private static final Set<String> STATEFUL_ANNOTATIONS = Set.of(
        "jakarta.ejb.Stateful",
        "jakarta.ejb.Remove",
        "javax.ejb.Stateful",
        "javax.ejb.Remove"
    );

    // Basic EJB annotations that may trigger @NeedsReview in complex cases
    private static final Set<String> EJB_ANNOTATIONS = Set.of(
        "jakarta.ejb.Singleton",
        "jakarta.ejb.Stateless",
        "jakarta.ejb.MessageDriven",
        "jakarta.ejb.EJB",
        "javax.ejb.Singleton",
        "javax.ejb.Stateless",
        "javax.ejb.MessageDriven",
        "javax.ejb.EJB"
    );

    // JSF annotations that trigger @NeedsReview
    private static final Set<String> JSF_ANNOTATIONS = Set.of(
        "jakarta.faces.bean.ManagedBean",
        "jakarta.faces.view.ViewScoped",
        "javax.faces.bean.ManagedBean",
        "javax.faces.view.ViewScoped"
    );

    // Jakarta Batch annotations that trigger @NeedsReview
    private static final Set<String> BATCH_ANNOTATIONS = Set.of(
        "jakarta.batch.api.Batchlet",
        "jakarta.batch.api.chunk.ItemReader",
        "jakarta.batch.api.chunk.ItemProcessor",
        "jakarta.batch.api.chunk.ItemWriter",
        "javax.batch.api.Batchlet",
        "javax.batch.api.chunk.ItemReader"
    );

    // WebSocket annotations that trigger @NeedsReview
    private static final Set<String> WEBSOCKET_ANNOTATIONS = Set.of(
        "jakarta.websocket.server.ServerEndpoint",
        "jakarta.websocket.OnMessage",
        "javax.websocket.server.ServerEndpoint",
        "javax.websocket.OnMessage"
    );

    @Override
    public String getDisplayName() {
        return "Add migration-annotations dependency";
    }

    @Override
    public String getDescription() {
        return "Adds the migration-annotations Maven dependency when EJB/Jakarta EE code is detected " +
               "that will require marker annotations (@NeedsReview, @EjbSchedule, @EjbTimeout, etc.). " +
               "This ensures the migrated project compiles with the required annotation definitions.";
    }

    static class Accumulator {
        boolean needsMigrationAnnotations = false;
        // Track which modules need the dependency
        Set<String> modulesNeedingDependency = new HashSet<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof J.CompilationUnit) {
                    J.CompilationUnit cu = (J.CompilationUnit) tree;
                    String modulePath = getModulePath(cu.getSourcePath().toString());

                    for (J.Import imp : cu.getImports()) {
                        String importPath = imp.getQualid().toString();

                        // Check all annotation categories that will trigger marker annotations
                        if (triggersMarkerAnnotation(importPath)) {
                            acc.needsMigrationAnnotations = true;
                            acc.modulesNeedingDependency.add(modulePath);
                            // Once we find one, we can stop checking this file
                            break;
                        }
                    }
                }
                return tree;
            }

            private boolean triggersMarkerAnnotation(String importPath) {
                return REMOTE_ANNOTATIONS.contains(importPath) ||
                       TIMER_ANNOTATIONS.contains(importPath) ||
                       TRANSACTION_ANNOTATIONS.contains(importPath) ||
                       APP_EXCEPTION_ANNOTATIONS.contains(importPath) ||
                       STATEFUL_ANNOTATIONS.contains(importPath) ||
                       EJB_ANNOTATIONS.contains(importPath) ||
                       JSF_ANNOTATIONS.contains(importPath) ||
                       BATCH_ANNOTATIONS.contains(importPath) ||
                       WEBSOCKET_ANNOTATIONS.contains(importPath);
            }

            private String getModulePath(String sourcePath) {
                int srcIndex = sourcePath.indexOf("/src/");
                if (srcIndex > 0) {
                    return sourcePath.substring(0, srcIndex);
                }
                return "";
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        return Collections.emptyList();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (!acc.needsMigrationAnnotations) {
            return TreeVisitor.noop();
        }

        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof Xml.Document)) {
                    return tree;
                }

                Xml.Document doc = (Xml.Document) tree;
                String sourcePath = doc.getSourcePath().toString();
                if (!sourcePath.endsWith("pom.xml")) {
                    return tree;
                }

                String modulePath = getModulePath(sourcePath);

                // Add dependency to modules that need it, or to root pom for inheritance
                boolean isRootPom = modulePath.isEmpty();
                boolean moduleNeedsDependency = acc.modulesNeedingDependency.contains(modulePath);

                // Strategy: Add to root pom if any module needs it (for inheritance)
                // or add to specific module pom if it needs it
                if (!isRootPom && !moduleNeedsDependency) {
                    return tree;
                }

                // Add migration-annotations dependency with provided scope
                // Provided scope since these annotations are only needed at compile time
                // AddDependency: groupId, artifactId, version, versionPattern, scope,
                // releasesOnly, onlyIfUsing, type, classifier, optional, familyPattern, acceptTransitive
                tree = new AddDependency(
                    MIGRATION_ANNOTATIONS_GROUP_ID,
                    MIGRATION_ANNOTATIONS_ARTIFACT_ID,
                    DEFAULT_VERSION,
                    null,        // versionPattern
                    "provided",  // scope - compile-time only
                    null,        // releasesOnly
                    null,        // onlyIfUsing
                    null,        // type
                    null,        // classifier
                    null,        // optional
                    null,        // familyPattern
                    null         // acceptTransitive
                ).getVisitor().visit(tree, ctx);

                return tree;
            }

            private String getModulePath(String sourcePath) {
                if (sourcePath.equals("pom.xml")) {
                    return "";
                }
                int pomIndex = sourcePath.indexOf("/pom.xml");
                if (pomIndex > 0) {
                    return sourcePath.substring(0, pomIndex);
                }
                return "";
            }
        };
    }
}
