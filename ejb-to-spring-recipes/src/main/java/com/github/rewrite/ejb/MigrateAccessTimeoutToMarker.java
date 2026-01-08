package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

/**
 * GAP-CONC-001: Marks @AccessTimeout for manual migration review.
 * <p>
 * Detects @AccessTimeout annotations and replaces them with @NeedsReview
 * containing specific guidance for Spring alternatives.
 * <p>
 * Important: Does NOT suggest @Transactional(timeout=...) as that is not
 * semantically equivalent to lock acquisition timeout.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateAccessTimeoutToMarker extends Recipe {

    private static final String JAKARTA_ACCESS_TIMEOUT = "jakarta.ejb.AccessTimeout";
    private static final String JAVAX_ACCESS_TIMEOUT = "javax.ejb.AccessTimeout";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Mark @AccessTimeout for Migration";
    }

    @Override
    public String getDescription() {
        return "Replaces EJB @AccessTimeout annotations with @NeedsReview markers. " +
               "Suggests ReentrantLock.tryLock() or Semaphore.tryAcquire() as Spring alternatives. " +
               "Does NOT suggest @Transactional(timeout=...) as it has different semantics.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_ACCESS_TIMEOUT, false),
                new UsesType<>(JAVAX_ACCESS_TIMEOUT, false)
            ),
            new AccessTimeoutVisitor()
        );
    }

    private class AccessTimeoutVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Find @AccessTimeout on class level
            Optional<J.Annotation> accessTimeout = findAccessTimeoutAnnotation(cd.getLeadingAnnotations());
            if (accessTimeout.isPresent()) {
                String originalCode = extractOriginalCode(accessTimeout.get());
                cd = removeAnnotation(cd, accessTimeout.get());
                cd = addNeedsReviewAnnotation(cd, originalCode, ctx);
            }

            return cd;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            // Find @AccessTimeout on method level
            Optional<J.Annotation> accessTimeout = findAccessTimeoutAnnotation(md.getLeadingAnnotations());
            if (accessTimeout.isPresent()) {
                String originalCode = extractOriginalCode(accessTimeout.get());

                // Build the @NeedsReview annotation
                J.Annotation needsReviewAnn = buildNeedsReviewAnnotation(originalCode, accessTimeout.get().getPrefix());

                // Remove @AccessTimeout and add @NeedsReview
                List<J.Annotation> newAnnotations = new ArrayList<>();
                for (J.Annotation ann : md.getLeadingAnnotations()) {
                    if (!isAccessTimeoutAnnotation(ann)) {
                        newAnnotations.add(ann);
                    }
                }
                // Add @NeedsReview at the position where @AccessTimeout was
                newAnnotations.add(needsReviewAnn);
                // Sort by name for consistency
                newAnnotations.sort(Comparator.comparing(J.Annotation::getSimpleName));

                doAfterVisit(new org.openrewrite.java.RemoveImport<>(JAKARTA_ACCESS_TIMEOUT, false));
                doAfterVisit(new org.openrewrite.java.RemoveImport<>(JAVAX_ACCESS_TIMEOUT, false));
                doAfterVisit(new org.openrewrite.java.AddImport<>(NEEDS_REVIEW_FQN, null, false));

                md = md.withLeadingAnnotations(newAnnotations);
            }

            return md;
        }

        private Optional<J.Annotation> findAccessTimeoutAnnotation(List<J.Annotation> annotations) {
            for (J.Annotation ann : annotations) {
                if (isAccessTimeoutAnnotation(ann)) {
                    return Optional.of(ann);
                }
            }
            return Optional.empty();
        }

        private boolean isAccessTimeoutAnnotation(J.Annotation ann) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(ann.getType());
            if (type != null) {
                String fqn = type.getFullyQualifiedName();
                return JAKARTA_ACCESS_TIMEOUT.equals(fqn) || JAVAX_ACCESS_TIMEOUT.equals(fqn);
            }
            // Fallback to simple name
            return "AccessTimeout".equals(ann.getSimpleName());
        }

        private String extractOriginalCode(J.Annotation ann) {
            StringBuilder sb = new StringBuilder("@AccessTimeout(");
            if (ann.getArguments() != null && !ann.getArguments().isEmpty()) {
                List<String> args = new ArrayList<>();
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        String name = assignment.getVariable().toString();
                        String value = assignment.getAssignment().toString();
                        args.add(name + " = " + value);
                    } else {
                        args.add(arg.toString());
                    }
                }
                sb.append(String.join(", ", args));
            }
            sb.append(")");
            return sb.toString();
        }

        private J.ClassDeclaration removeAnnotation(J.ClassDeclaration cd, J.Annotation toRemove) {
            List<J.Annotation> newAnnotations = new ArrayList<>();
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if (ann != toRemove) {
                    newAnnotations.add(ann);
                }
            }
            doAfterVisit(new org.openrewrite.java.RemoveImport<>(JAKARTA_ACCESS_TIMEOUT, false));
            doAfterVisit(new org.openrewrite.java.RemoveImport<>(JAVAX_ACCESS_TIMEOUT, false));
            return cd.withLeadingAnnotations(newAnnotations);
        }

        private J.ClassDeclaration addNeedsReviewAnnotation(J.ClassDeclaration cd, String originalCode, ExecutionContext ctx) {
            doAfterVisit(new org.openrewrite.java.AddImport<>(NEEDS_REVIEW_FQN, null, false));

            String escapedOriginalCode = originalCode.replace("\"", "\\\"");

            JavaTemplate template = JavaTemplate.builder(
                "@NeedsReview(reason = \"@AccessTimeout removed - no Spring equivalent for lock acquisition timeout\", " +
                "category = NeedsReview.Category.CONCURRENCY, " +
                "originalCode = \"" + escapedOriginalCode + "\", " +
                "suggestedAction = \"Consider: ReentrantLock.tryLock(timeout, unit) or Semaphore.tryAcquire(timeout, unit). " +
                "Note: @Transactional(timeout=...) is NOT equivalent - it controls transaction timeout, not lock wait time.\")"
            ).javaParser(JavaParser.fromJavaVersion()
                .dependsOn(
                    "package com.github.rewrite.ejb.annotations;\n" +
                    "import java.lang.annotation.*;\n" +
                    "@Retention(RetentionPolicy.RUNTIME)\n" +
                    "@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})\n" +
                    "public @interface NeedsReview {\n" +
                    "    String reason() default \"\";\n" +
                    "    Category category();\n" +
                    "    String originalCode() default \"\";\n" +
                    "    String suggestedAction() default \"\";\n" +
                    "    enum Category { CONCURRENCY, MANUAL_MIGRATION }\n" +
                    "}"
                ))
                .imports(NEEDS_REVIEW_FQN)
                .build();

            return template.apply(
                new Cursor(getCursor(), cd),
                cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
            );
        }

        private J.Annotation buildNeedsReviewAnnotation(String originalCode, Space prefix) {
            // Build @NeedsReview annotation arguments
            List<Expression> args = new ArrayList<>();

            // reason argument
            args.add(buildAssignment("reason",
                "@AccessTimeout removed - no Spring equivalent for lock acquisition timeout"));

            // category argument
            args.add(buildCategoryAssignment());

            // originalCode argument
            args.add(buildAssignment("originalCode", originalCode));

            // suggestedAction argument
            args.add(buildAssignment("suggestedAction",
                "Consider: ReentrantLock.tryLock(timeout, unit) or Semaphore.tryAcquire(timeout, unit). " +
                "Note: @Transactional(timeout=...) is NOT equivalent - it controls transaction timeout, not lock wait time."));

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "NeedsReview",
                    JavaType.ShallowClass.build(NEEDS_REVIEW_FQN),
                    null
                ),
                JContainer.build(
                    Space.EMPTY,
                    args.stream()
                        .map(arg -> JRightPadded.build(arg))
                        .collect(java.util.stream.Collectors.toList()),
                    Markers.EMPTY
                )
            );
        }

        private J.Assignment buildAssignment(String name, String value) {
            Expression literal = new J.Literal(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                value,
                "\"" + value.replace("\"", "\\\"") + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );
            return new J.Assignment(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    name,
                    null,
                    null
                ),
                JLeftPadded.build(literal).withBefore(Space.SINGLE_SPACE),
                null
            );
        }

        private J.Assignment buildCategoryAssignment() {
            Expression fieldAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                new J.FieldAccess(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "NeedsReview",
                        null,
                        null
                    ),
                    JLeftPadded.build(
                        new J.Identifier(
                            Tree.randomId(),
                            Space.EMPTY,
                            Markers.EMPTY,
                            Collections.emptyList(),
                            "Category",
                            null,
                            null
                        )
                    ),
                    null
                ),
                JLeftPadded.build(
                    new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "CONCURRENCY",
                        null,
                        null
                    )
                ),
                null
            );
            return new J.Assignment(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "category",
                    null,
                    null
                ),
                JLeftPadded.build(fieldAccess).withBefore(Space.SINGLE_SPACE),
                null
            );
        }
    }
}
