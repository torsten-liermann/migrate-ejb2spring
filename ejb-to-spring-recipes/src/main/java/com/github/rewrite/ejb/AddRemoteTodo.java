package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Marks @Remote EJBs with @NeedsReview annotation indicating manual migration needed.
 * <p>
 * Remote EJBs cannot be automatically migrated to Spring because:
 * - They require network communication setup
 * - Options include REST API, gRPC, Spring Remoting, etc.
 * - The choice depends on requirements (performance, clients, etc.)
 * <p>
 * This recipe removes the @Remote annotation and adds @NeedsReview.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddRemoteTodo extends Recipe {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Mark @Remote EJBs for review";
    }

    @Override
    public String getDescription() {
        return "Removes @Remote annotations and adds @NeedsReview indicating that " +
               "remote EJBs require manual migration to REST, gRPC, or similar.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new AddRemoteTodoVisitor();
    }

    private class AddRemoteTodoVisitor extends JavaIsoVisitor<ExecutionContext> {

        private static final AnnotationMatcher REMOTE_JAVAX_MATCHER =
            new AnnotationMatcher("@javax.ejb.Remote");
        private static final AnnotationMatcher REMOTE_JAKARTA_MATCHER =
            new AnnotationMatcher("@jakarta.ejb.Remote");
        private static final String REMOTE_SIMPLE_NAME = "Remote";

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Check if this class has @Remote annotation (with simple-name fallback for unresolved types)
            boolean hasRemote = classDecl.getLeadingAnnotations().stream()
                .anyMatch(a -> REMOTE_JAVAX_MATCHER.matches(a) || REMOTE_JAKARTA_MATCHER.matches(a)
                              || isUnresolvedRemote(a));

            if (hasRemote) {
                // Check if already has @NeedsReview
                boolean hasNeedsReview = cd.getLeadingAnnotations().stream()
                    .anyMatch(a -> a.getSimpleName().equals("NeedsReview"));

                if (!hasNeedsReview) {
                    maybeAddImport(NEEDS_REVIEW_FQN);

                    // Get prefix from first annotation for proper positioning
                    Space needsReviewPrefix = cd.getLeadingAnnotations().isEmpty()
                        ? cd.getPrefix()
                        : cd.getLeadingAnnotations().get(0).getPrefix();

                    // Create @NeedsReview annotation directly (for idempotency)
                    J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                        "Remote EJB requires manual migration to REST API, gRPC, or Spring Remoting",
                        "REMOTE_ACCESS",
                        "@Remote",
                        "Convert to REST controller or use Spring Cloud for service communication",
                        needsReviewPrefix
                    );

                    // Insert @NeedsReview at beginning
                    List<J.Annotation> newAnnotations = new ArrayList<>();
                    newAnnotations.add(needsReviewAnn);

                    for (int i = 0; i < cd.getLeadingAnnotations().size(); i++) {
                        J.Annotation ann = cd.getLeadingAnnotations().get(i);
                        if (i == 0) {
                            // Remove prefix from first original annotation
                            String ws = needsReviewPrefix.getWhitespace();
                            ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n"));
                        }
                        newAnnotations.add(ann);
                    }

                    cd = cd.withLeadingAnnotations(newAnnotations);
                }
            }

            return cd;
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

            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                value,
                "\"" + value + "\"",
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

            J.Identifier needsReviewIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                JavaType.ShallowClass.build(NEEDS_REVIEW_FQN),
                null
            );

            J.FieldAccess categoryAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                needsReviewIdent,
                JLeftPadded.build(new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "Category",
                    categoryType,
                    null
                )),
                categoryType
            );

            J.FieldAccess valueExpr = new J.FieldAccess(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                categoryAccess,
                JLeftPadded.build(new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    categoryName,
                    categoryType,
                    null
                )),
                categoryType
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

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);

            if (REMOTE_JAVAX_MATCHER.matches(a)) {
                maybeRemoveImport("javax.ejb.Remote");
                //noinspection DataFlowIssue
                return null; // Remove the annotation
            }
            if (REMOTE_JAKARTA_MATCHER.matches(a)) {
                maybeRemoveImport("jakarta.ejb.Remote");
                //noinspection DataFlowIssue
                return null; // Remove the annotation
            }

            // Fallback: match by simple name when type is unresolved (e.g., EJB API provided-only)
            if (isUnresolvedRemote(a)) {
                maybeRemoveImport("javax.ejb.Remote");
                maybeRemoveImport("jakarta.ejb.Remote");
                //noinspection DataFlowIssue
                return null; // Remove the annotation
            }

            return a;
        }

        /**
         * Checks if the annotation is an unresolved @Remote.
         * This handles the case when EJB annotations are provided-only and not on the parser classpath.
         */
        private boolean isUnresolvedRemote(J.Annotation annotation) {
            // Check if type is unresolved (null type)
            if (annotation.getType() != null) {
                return false;
            }

            // Check simple name
            if (annotation.getAnnotationType() instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) annotation.getAnnotationType();
                return REMOTE_SIMPLE_NAME.equals(ident.getSimpleName());
            }
            // Handle fully qualified name in source (@javax.ejb.Remote or @jakarta.ejb.Remote)
            if (annotation.getAnnotationType() instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) annotation.getAnnotationType();
                return REMOTE_SIMPLE_NAME.equals(fa.getSimpleName()) &&
                       (fa.toString().contains("javax.ejb") || fa.toString().contains("jakarta.ejb"));
            }

            return false;
        }
    }
}
