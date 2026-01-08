package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Marks Jakarta Batch classes for manual migration to Spring Batch.
 * <p>
 * Jakarta Batch (JSR-352) has no automatic Spring equivalent and requires
 * manual migration to Spring Batch. This recipe adds @NeedsReview annotations
 * to classes that use Jakarta Batch APIs:
 * <ul>
 *   <li>Classes extending AbstractItemReader/AbstractItemWriter</li>
 *   <li>Classes implementing batch listener interfaces</li>
 *   <li>Classes using JobContext/StepContext</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MarkJakartaBatchForMigration extends Recipe {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    // Jakarta Batch types to detect - comprehensive list
    // Core APIs
    private static final String ABSTRACT_ITEM_READER = "jakarta.batch.api.chunk.AbstractItemReader";
    private static final String ABSTRACT_ITEM_WRITER = "jakarta.batch.api.chunk.AbstractItemWriter";
    private static final String ITEM_READER = "jakarta.batch.api.chunk.ItemReader";
    private static final String ITEM_WRITER = "jakarta.batch.api.chunk.ItemWriter";
    private static final String ITEM_PROCESSOR = "jakarta.batch.api.chunk.ItemProcessor";
    private static final String BATCHLET = "jakarta.batch.api.Batchlet";
    private static final String ABSTRACT_BATCHLET = "jakarta.batch.api.AbstractBatchlet";

    // Runtime APIs (important for UploadDirectoryScanner pattern!)
    private static final String BATCH_RUNTIME = "jakarta.batch.runtime.BatchRuntime";
    private static final String JOB_OPERATOR = "jakarta.batch.operations.JobOperator";
    private static final String JOB_CONTEXT = "jakarta.batch.runtime.context.JobContext";
    private static final String STEP_CONTEXT = "jakarta.batch.runtime.context.StepContext";

    // Listeners
    private static final String JOB_LISTENER = "jakarta.batch.api.listener.JobListener";
    private static final String STEP_LISTENER = "jakarta.batch.api.listener.StepListener";
    private static final String CHUNK_LISTENER = "jakarta.batch.api.chunk.listener.ChunkListener";
    private static final String ITEM_READ_LISTENER = "jakarta.batch.api.chunk.listener.ItemReadListener";
    private static final String ITEM_WRITE_LISTENER = "jakarta.batch.api.chunk.listener.ItemWriteListener";
    private static final String ITEM_PROCESS_LISTENER = "jakarta.batch.api.chunk.listener.ItemProcessListener";
    private static final String SKIP_LISTENER = "jakarta.batch.api.chunk.listener.SkipListener";
    private static final String SKIP_READ_LISTENER = "jakarta.batch.api.chunk.listener.SkipReadListener";
    private static final String SKIP_WRITE_LISTENER = "jakarta.batch.api.chunk.listener.SkipWriteListener";
    private static final String SKIP_PROCESS_LISTENER = "jakarta.batch.api.chunk.listener.SkipProcessListener";
    private static final String RETRY_READ_LISTENER = "jakarta.batch.api.chunk.listener.RetryReadListener";
    private static final String RETRY_WRITE_LISTENER = "jakarta.batch.api.chunk.listener.RetryWriteListener";
    private static final String RETRY_PROCESS_LISTENER = "jakarta.batch.api.chunk.listener.RetryProcessListener";

    // Partition APIs
    private static final String PARTITION_MAPPER = "jakarta.batch.api.partition.PartitionMapper";
    private static final String PARTITION_REDUCER = "jakarta.batch.api.partition.PartitionReducer";
    private static final String PARTITION_COLLECTOR = "jakarta.batch.api.partition.PartitionCollector";
    private static final String PARTITION_ANALYZER = "jakarta.batch.api.partition.PartitionAnalyzer";

    @Override
    public String getDisplayName() {
        return "Mark Jakarta Batch classes for manual migration";
    }

    @Override
    public String getDescription() {
        return "Adds @NeedsReview annotation to classes using Jakarta Batch APIs, indicating they need manual migration to Spring Batch.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        // Note: No Preconditions.check() with UsesType because it requires type resolution.
        // The visitor itself detects Batch patterns via class names, annotations, and extends clauses.
        return new JakartaBatchVisitor();
    }

    private class JakartaBatchVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Check if class already has @NeedsReview
            boolean hasNeedsReview = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> a.getSimpleName().equals("NeedsReview"));

            if (hasNeedsReview) {
                return cd;
            }

            // Determine the Jakarta Batch type being used
            String batchType = detectBatchType(cd);
            if (batchType == null) {
                return cd;
            }

            // Add @NeedsReview annotation
            maybeAddImport(NEEDS_REVIEW_FQN);
            // Static import for enum values
            doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN + ".Category", "MANUAL_MIGRATION", false));

            Space prefix = cd.getLeadingAnnotations().isEmpty()
                ? cd.getPrefix()
                : cd.getLeadingAnnotations().get(0).getPrefix();

            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                "Jakarta Batch class needs migration to Spring Batch",
                "MANUAL_MIGRATION",
                batchType,
                "See docs/migration-guide/batch-migration.md for Spring Batch equivalents",
                prefix
            );

            List<J.Annotation> newAnnotations = new ArrayList<>();
            newAnnotations.add(needsReviewAnn);

            for (int i = 0; i < cd.getLeadingAnnotations().size(); i++) {
                J.Annotation ann = cd.getLeadingAnnotations().get(i);
                if (i == 0) {
                    String ws = prefix.getWhitespace();
                    ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n"));
                }
                newAnnotations.add(ann);
            }

            return cd.withLeadingAnnotations(newAnnotations);
        }

        private String detectBatchType(J.ClassDeclaration classDecl) {
            List<String> detectedFeatures = new ArrayList<>();

            // First: Check imports for jakarta.batch.* or javax.batch.* (most robust detection)
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            if (cu != null) {
                for (J.Import imp : cu.getImports()) {
                    String importPath = imp.getQualid().toString();
                    if (importPath.startsWith("jakarta.batch.") || importPath.startsWith("javax.batch.")) {
                        // Detect specific APIs from import
                        if (importPath.contains("BatchRuntime")) {
                            detectedFeatures.add("uses BatchRuntime");
                        } else if (importPath.contains("JobOperator")) {
                            detectedFeatures.add("uses JobOperator");
                        } else if (importPath.contains("JobContext")) {
                            detectedFeatures.add("uses JobContext");
                        } else if (importPath.contains("StepContext")) {
                            detectedFeatures.add("uses StepContext");
                        } else if (importPath.contains("AbstractItemReader")) {
                            detectedFeatures.add("extends AbstractItemReader");
                        } else if (importPath.contains("AbstractItemWriter")) {
                            detectedFeatures.add("extends AbstractItemWriter");
                        } else if (importPath.contains("ItemReader")) {
                            detectedFeatures.add("implements ItemReader");
                        } else if (importPath.contains("ItemWriter")) {
                            detectedFeatures.add("implements ItemWriter");
                        } else if (importPath.contains("ItemProcessor")) {
                            detectedFeatures.add("implements ItemProcessor");
                        } else if (importPath.contains("AbstractBatchlet")) {
                            detectedFeatures.add("extends AbstractBatchlet");
                        } else if (importPath.contains("Batchlet")) {
                            detectedFeatures.add("implements Batchlet");
                        } else if (importPath.contains("Listener")) {
                            detectedFeatures.add("uses Batch Listener");
                        } else if (importPath.contains("Partition")) {
                            detectedFeatures.add("uses Partition API");
                        } else if (!detectedFeatures.contains("uses Jakarta Batch API")) {
                            // Generic fallback for any jakarta.batch import
                            detectedFeatures.add("uses Jakarta Batch API");
                        }
                    }
                }
            }

            // If imports detected something, return early (most reliable detection)
            if (!detectedFeatures.isEmpty()) {
                return String.join(", ", detectedFeatures);
            }

            // Check extends clause
            if (classDecl.getExtends() != null) {
                JavaType.FullyQualified extendsType = TypeUtils.asFullyQualified(classDecl.getExtends().getType());
                if (extendsType != null) {
                    String fqn = extendsType.getFullyQualifiedName();
                    if (ABSTRACT_ITEM_READER.equals(fqn)) {
                        detectedFeatures.add("extends AbstractItemReader");
                    } else if (ABSTRACT_ITEM_WRITER.equals(fqn)) {
                        detectedFeatures.add("extends AbstractItemWriter");
                    } else if (ABSTRACT_BATCHLET.equals(fqn)) {
                        detectedFeatures.add("extends AbstractBatchlet");
                    }
                }
            }

            // Check implements clause
            if (classDecl.getImplements() != null) {
                for (TypeTree impl : classDecl.getImplements()) {
                    JavaType.FullyQualified implType = TypeUtils.asFullyQualified(impl.getType());
                    if (implType != null) {
                        String fqn = implType.getFullyQualifiedName();
                        // Core interfaces
                        if (ITEM_READER.equals(fqn)) {
                            detectedFeatures.add("implements ItemReader");
                        } else if (ITEM_WRITER.equals(fqn)) {
                            detectedFeatures.add("implements ItemWriter");
                        } else if (ITEM_PROCESSOR.equals(fqn)) {
                            detectedFeatures.add("implements ItemProcessor");
                        } else if (BATCHLET.equals(fqn)) {
                            detectedFeatures.add("implements Batchlet");
                        }
                        // Listeners
                        else if (JOB_LISTENER.equals(fqn)) {
                            detectedFeatures.add("implements JobListener");
                        } else if (STEP_LISTENER.equals(fqn)) {
                            detectedFeatures.add("implements StepListener");
                        } else if (CHUNK_LISTENER.equals(fqn)) {
                            detectedFeatures.add("implements ChunkListener");
                        } else if (ITEM_READ_LISTENER.equals(fqn)) {
                            detectedFeatures.add("implements ItemReadListener");
                        } else if (ITEM_WRITE_LISTENER.equals(fqn)) {
                            detectedFeatures.add("implements ItemWriteListener");
                        } else if (ITEM_PROCESS_LISTENER.equals(fqn)) {
                            detectedFeatures.add("implements ItemProcessListener");
                        } else if (SKIP_LISTENER.equals(fqn) || SKIP_READ_LISTENER.equals(fqn) ||
                                   SKIP_WRITE_LISTENER.equals(fqn) || SKIP_PROCESS_LISTENER.equals(fqn)) {
                            detectedFeatures.add("implements SkipListener");
                        } else if (RETRY_READ_LISTENER.equals(fqn) || RETRY_WRITE_LISTENER.equals(fqn) ||
                                   RETRY_PROCESS_LISTENER.equals(fqn)) {
                            detectedFeatures.add("implements RetryListener");
                        }
                        // Partition interfaces
                        else if (PARTITION_MAPPER.equals(fqn)) {
                            detectedFeatures.add("implements PartitionMapper");
                        } else if (PARTITION_REDUCER.equals(fqn)) {
                            detectedFeatures.add("implements PartitionReducer");
                        } else if (PARTITION_COLLECTOR.equals(fqn)) {
                            detectedFeatures.add("implements PartitionCollector");
                        } else if (PARTITION_ANALYZER.equals(fqn)) {
                            detectedFeatures.add("implements PartitionAnalyzer");
                        }
                    }
                }
            }

            // Check for batch-related field usage (JobContext, StepContext, JobOperator, BatchRuntime)
            if (classDecl.getType() != null && classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        JavaType varType = vd.getType();
                        if (varType instanceof JavaType.FullyQualified) {
                            String fqn = ((JavaType.FullyQualified) varType).getFullyQualifiedName();
                            if (JOB_CONTEXT.equals(fqn)) {
                                detectedFeatures.add("uses JobContext");
                            } else if (STEP_CONTEXT.equals(fqn)) {
                                detectedFeatures.add("uses StepContext");
                            } else if (JOB_OPERATOR.equals(fqn)) {
                                detectedFeatures.add("uses JobOperator");
                            }
                        }
                    }
                }
            }

            // String-based fallback detection (when types aren't resolved)
            if (detectedFeatures.isEmpty()) {
                // Check extends clause by simple name
                if (classDecl.getExtends() != null) {
                    String extendsName = classDecl.getExtends().toString();
                    if (extendsName.contains("AbstractItemReader")) {
                        detectedFeatures.add("extends AbstractItemReader");
                    } else if (extendsName.contains("AbstractItemWriter")) {
                        detectedFeatures.add("extends AbstractItemWriter");
                    } else if (extendsName.contains("AbstractBatchlet")) {
                        detectedFeatures.add("extends AbstractBatchlet");
                    }
                }

                // Check implements clause by simple name
                if (classDecl.getImplements() != null) {
                    for (TypeTree impl : classDecl.getImplements()) {
                        String implName = impl.toString();
                        if (implName.contains("ItemReader") && !implName.contains("Abstract")) {
                            detectedFeatures.add("implements ItemReader");
                        } else if (implName.contains("ItemWriter") && !implName.contains("Abstract")) {
                            detectedFeatures.add("implements ItemWriter");
                        } else if (implName.contains("ItemProcessor")) {
                            detectedFeatures.add("implements ItemProcessor");
                        } else if (implName.contains("Batchlet") && !implName.contains("Abstract")) {
                            detectedFeatures.add("implements Batchlet");
                        } else if (implName.contains("Listener")) {
                            detectedFeatures.add("implements Batch Listener");
                        } else if (implName.contains("Partition")) {
                            detectedFeatures.add("implements Partition API");
                        }
                    }
                }
            }

            // No batch features detected - return null to skip marking
            if (detectedFeatures.isEmpty()) {
                return null;
            }

            return String.join(", ", detectedFeatures);
        }

        private J.Annotation createNeedsReviewAnnotation(String reason, String category,
                                                          String originalCode, String suggestedAction,
                                                          Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                type,
                null
            );

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg(category));
            arguments.add(createAssignmentArg("originalCode", originalCode, true));
            arguments.add(createAssignmentArg("suggestedAction", suggestedAction, true));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

        private String escapeJavaString(String value) {
            if (value == null) return "";
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }

        private JRightPadded<Expression> createAssignmentArg(String key, String value, boolean leadingSpace) {
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                leadingSpace ? Space.format(" ") : Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                key,
                null,
                null
            );

            String escapedValue = escapeJavaString(value);
            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                value,
                "\"" + escapedValue + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }

        private JRightPadded<Expression> createCategoryArg(String categoryName) {
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "category",
                null,
                null
            );

            JavaType.ShallowClass categoryType = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN + ".Category");

            // Create MANUAL_MIGRATION identifier (works with static import of NeedsReview.Category.MANUAL_MIGRATION)
            J.Identifier valueExpr = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                categoryName,
                categoryType,
                null
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }
    }
}
