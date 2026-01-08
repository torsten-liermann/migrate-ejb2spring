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
 * Adds @NeedsReview marker to @Remote EJB interfaces for manual migration.
 * <p>
 * This recipe is used when {@code migration.remote.strategy: manual} is configured.
 * Instead of generating REST controllers and clients, it marks the @Remote interfaces
 * for manual review and migration.
 *
 * @see com.github.rewrite.ejb.config.ProjectConfiguration.RemoteStrategy#MANUAL
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateRemoteToMarker extends Recipe {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final AnnotationMatcher REMOTE_JAVAX = new AnnotationMatcher("@javax.ejb.Remote");
    private static final AnnotationMatcher REMOTE_JAKARTA = new AnnotationMatcher("@jakarta.ejb.Remote");

    @Override
    public String getDisplayName() {
        return "Mark @Remote interfaces for manual migration";
    }

    @Override
    public String getDescription() {
        return "Adds @NeedsReview marker to @Remote EJB interfaces. Use this recipe when " +
               "automatic REST migration is not desired (migration.remote.strategy: manual).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MigrateRemoteToMarkerVisitor();
    }

    private class MigrateRemoteToMarkerVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Check if this class has @Remote annotation
            boolean hasRemote = false;
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if (REMOTE_JAVAX.matches(ann) || REMOTE_JAKARTA.matches(ann)) {
                    hasRemote = true;
                    break;
                }
            }

            if (!hasRemote) {
                return cd;
            }

            // Check if already has @NeedsReview (by simple name to handle missing type resolution)
            boolean hasNeedsReview = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> a.getSimpleName().equals("NeedsReview"));
            if (hasNeedsReview) {
                return cd; // Already marked
            }

            // Add import
            maybeAddImport(NEEDS_REVIEW_FQN);

            // Determine interface or class for message
            String typeDesc = cd.getKind() == J.ClassDeclaration.Kind.Type.Interface
                    ? "interface"
                    : "class";

            // Build @NeedsReview annotation
            String reason = "Remote EJB " + typeDesc + " requires manual migration to REST API";
            String suggestedAction = "Design REST endpoints and implement controller/client for " +
                                     (cd.getType() != null ? cd.getType().getFullyQualifiedName() : cd.getSimpleName());

            Space needsReviewPrefix = cd.getLeadingAnnotations().isEmpty()
                ? cd.getPrefix()
                : cd.getLeadingAnnotations().get(0).getPrefix();

            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                reason,
                "REMOTE_ACCESS",
                "@Remote",
                suggestedAction,
                needsReviewPrefix
            );

            // Insert @NeedsReview at beginning and remove @Remote
            List<J.Annotation> newAnnotations = new ArrayList<>();
            newAnnotations.add(needsReviewAnn);

            boolean firstNonRemote = true;
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                // Skip @Remote annotation - it should be removed
                if (REMOTE_JAVAX.matches(ann) || REMOTE_JAKARTA.matches(ann)) {
                    // Also remove the javax.ejb.Remote or jakarta.ejb.Remote import
                    maybeRemoveImport("javax.ejb.Remote");
                    maybeRemoveImport("jakarta.ejb.Remote");
                    continue;
                }
                if (firstNonRemote) {
                    // Adjust prefix of first remaining annotation
                    String ws = needsReviewPrefix.getWhitespace();
                    ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n"));
                    firstNonRemote = false;
                }
                newAnnotations.add(ann);
            }

            return cd.withLeadingAnnotations(newAnnotations);
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
                new JLeftPadded<>(
                    Space.EMPTY,
                    new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "Category",
                        categoryType,
                        null
                    ),
                    Markers.EMPTY
                ),
                categoryType
            );

            J.Identifier categoryValueIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                categoryName,
                categoryType,
                null
            );

            J.FieldAccess fullCategoryAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                categoryAccess,
                new JLeftPadded<>(
                    Space.EMPTY,
                    categoryValueIdent,
                    Markers.EMPTY
                ),
                categoryType
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(fullCategoryAccess).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }
    }
}
