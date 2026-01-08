package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Space;

import java.util.*;

/**
 * MKR-007: Migrate @Stateful beans to Spring with appropriate scoping.
 * <p>
 * Conservative approach:
 * <ul>
 *   <li>{@code @Stateful} → {@code @Service} + {@code @Scope("prototype")} + {@code @NeedsReview} with scope guidance</li>
 *   <li>{@code @Remove} → removed with cleanup guidance in {@code @NeedsReview} (requires manual disposal)</li>
 *   <li>{@code @StatefulTimeout} → removed with guidance in @NeedsReview</li>
 *   <li>{@code @PostActivate} → removed with guidance in @NeedsReview (passivation not supported)</li>
 *   <li>{@code @PrePassivate} → removed with guidance in @NeedsReview (passivation not supported)</li>
 * </ul>
 * <p>
 * Design rationale: Full scope inference (web-session vs prototype) requires understanding
 * caller context which is beyond safe automation. Use prototype as safe default;
 * developers can manually upgrade to @SessionScope where appropriate.
 * <p>
 * Important: @Remove is NOT converted to @PreDestroy because Spring does NOT automatically
 * invoke @PreDestroy callbacks for prototype-scoped beans. The caller must explicitly
 * destroy beans via ObjectProvider.destroy() or similar mechanisms.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateStatefulBean extends Recipe {

    private static final String JAKARTA_STATEFUL = "jakarta.ejb.Stateful";
    private static final String JAVAX_STATEFUL = "javax.ejb.Stateful";
    private static final String JAKARTA_REMOVE = "jakarta.ejb.Remove";
    private static final String JAVAX_REMOVE = "javax.ejb.Remove";
    private static final String JAKARTA_STATEFUL_TIMEOUT = "jakarta.ejb.StatefulTimeout";
    private static final String JAVAX_STATEFUL_TIMEOUT = "javax.ejb.StatefulTimeout";
    private static final String JAKARTA_POST_ACTIVATE = "jakarta.ejb.PostActivate";
    private static final String JAVAX_POST_ACTIVATE = "javax.ejb.PostActivate";
    private static final String JAKARTA_PRE_PASSIVATE = "jakarta.ejb.PrePassivate";
    private static final String JAVAX_PRE_PASSIVATE = "javax.ejb.PrePassivate";

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final String SERVICE_FQN = "org.springframework.stereotype.Service";
    private static final String SCOPE_FQN = "org.springframework.context.annotation.Scope";

    // Matchers for existing Spring annotations (to avoid duplicates)
    private static final AnnotationMatcher SERVICE_MATCHER = new AnnotationMatcher("@" + SERVICE_FQN);
    private static final AnnotationMatcher SCOPE_MATCHER = new AnnotationMatcher("@" + SCOPE_FQN);
    private static final AnnotationMatcher NEEDS_REVIEW_MATCHER = new AnnotationMatcher("@" + NEEDS_REVIEW_FQN);

    private static final AnnotationMatcher STATEFUL_MATCHER_JAKARTA = new AnnotationMatcher("@" + JAKARTA_STATEFUL);
    private static final AnnotationMatcher STATEFUL_MATCHER_JAVAX = new AnnotationMatcher("@" + JAVAX_STATEFUL);
    private static final AnnotationMatcher REMOVE_MATCHER_JAKARTA = new AnnotationMatcher("@" + JAKARTA_REMOVE);
    private static final AnnotationMatcher REMOVE_MATCHER_JAVAX = new AnnotationMatcher("@" + JAVAX_REMOVE);
    private static final AnnotationMatcher STATEFUL_TIMEOUT_MATCHER_JAKARTA = new AnnotationMatcher("@" + JAKARTA_STATEFUL_TIMEOUT);
    private static final AnnotationMatcher STATEFUL_TIMEOUT_MATCHER_JAVAX = new AnnotationMatcher("@" + JAVAX_STATEFUL_TIMEOUT);
    private static final AnnotationMatcher POST_ACTIVATE_MATCHER_JAKARTA = new AnnotationMatcher("@" + JAKARTA_POST_ACTIVATE);
    private static final AnnotationMatcher POST_ACTIVATE_MATCHER_JAVAX = new AnnotationMatcher("@" + JAVAX_POST_ACTIVATE);
    private static final AnnotationMatcher PRE_PASSIVATE_MATCHER_JAKARTA = new AnnotationMatcher("@" + JAKARTA_PRE_PASSIVATE);
    private static final AnnotationMatcher PRE_PASSIVATE_MATCHER_JAVAX = new AnnotationMatcher("@" + JAVAX_PRE_PASSIVATE);

    @Override
    public String getDisplayName() {
        return "Migrate @Stateful beans to Spring prototype scope";
    }

    @Override
    public String getDescription() {
        return "Migrates EJB @Stateful beans to Spring @Service with @Scope(\"prototype\"). " +
               "@Remove methods are removed (manual cleanup via ObjectProvider.destroy() required). " +
               "Passivation callbacks are removed. Adds @NeedsReview for scope refinement.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_STATEFUL, false),
                new UsesType<>(JAVAX_STATEFUL, false)
            ),
            new StatefulMigrationVisitor()
        );
    }

    private static class StatefulMigrationVisitor extends JavaIsoVisitor<ExecutionContext> {

        private static boolean isStatefulClass(J.ClassDeclaration classDecl) {
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if (STATEFUL_MATCHER_JAKARTA.matches(ann) || STATEFUL_MATCHER_JAVAX.matches(ann)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasStatefulTimeout(J.ClassDeclaration classDecl) {
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if (STATEFUL_TIMEOUT_MATCHER_JAKARTA.matches(ann) || STATEFUL_TIMEOUT_MATCHER_JAVAX.matches(ann)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            if (!isStatefulClass(classDecl)) {
                return classDecl;
            }

            // Scan for lifecycle annotations to build context for guidance message
            int removeMethodCount = 0;
            boolean hasPassivationCallbacks = false;
            boolean hasStatefulTimeout = hasStatefulTimeout(classDecl);

            if (classDecl.getBody() != null) {
                for (var stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                        for (J.Annotation ann : method.getLeadingAnnotations()) {
                            if (REMOVE_MATCHER_JAKARTA.matches(ann) || REMOVE_MATCHER_JAVAX.matches(ann)) {
                                removeMethodCount++;
                            }
                            if (POST_ACTIVATE_MATCHER_JAKARTA.matches(ann) || POST_ACTIVATE_MATCHER_JAVAX.matches(ann) ||
                                PRE_PASSIVATE_MATCHER_JAKARTA.matches(ann) || PRE_PASSIVATE_MATCHER_JAVAX.matches(ann)) {
                                hasPassivationCallbacks = true;
                            }
                        }
                    }
                }
            }

            // Visit children (methods) first
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Transform the class annotations
            cd = transformClassAnnotations(cd, removeMethodCount, hasStatefulTimeout, hasPassivationCallbacks);

            return cd;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            // Check if we're inside a @Stateful class
            J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if (enclosingClass == null || !isStatefulClass(enclosingClass)) {
                return method;
            }

            J.MethodDeclaration md = method;
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean modified = false;
            boolean hasRemoveAnnotation = false;
            Space firstRemovedAnnotationPrefix = null;

            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (REMOVE_MATCHER_JAKARTA.matches(ann) || REMOVE_MATCHER_JAVAX.matches(ann)) {
                    // Remove @Remove - Spring prototype doesn't auto-call cleanup like EJB @Remove
                    // Guidance is in class-level @NeedsReview for manual cleanup pattern
                    if (firstRemovedAnnotationPrefix == null) {
                        firstRemovedAnnotationPrefix = ann.getPrefix();
                    }
                    maybeRemoveImport(JAKARTA_REMOVE);
                    maybeRemoveImport(JAVAX_REMOVE);
                    modified = true;
                } else if (POST_ACTIVATE_MATCHER_JAKARTA.matches(ann) || POST_ACTIVATE_MATCHER_JAVAX.matches(ann)) {
                    // Remove @PostActivate (passivation not supported in Spring)
                    if (firstRemovedAnnotationPrefix == null) {
                        firstRemovedAnnotationPrefix = ann.getPrefix();
                    }
                    maybeRemoveImport(JAKARTA_POST_ACTIVATE);
                    maybeRemoveImport(JAVAX_POST_ACTIVATE);
                    modified = true;
                } else if (PRE_PASSIVATE_MATCHER_JAKARTA.matches(ann) || PRE_PASSIVATE_MATCHER_JAVAX.matches(ann)) {
                    // Remove @PrePassivate (passivation not supported in Spring)
                    if (firstRemovedAnnotationPrefix == null) {
                        firstRemovedAnnotationPrefix = ann.getPrefix();
                    }
                    maybeRemoveImport(JAKARTA_PRE_PASSIVATE);
                    maybeRemoveImport(JAVAX_PRE_PASSIVATE);
                    modified = true;
                } else {
                    newAnnotations.add(ann);
                }
            }

            if (modified) {
                md = md.withLeadingAnnotations(newAnnotations);
                // If we removed annotations and have no annotations left,
                // use the first removed annotation's prefix for proper spacing
                if (firstRemovedAnnotationPrefix != null && newAnnotations.isEmpty()) {
                    // The prefix from the removed annotation may have blank lines.
                    // We want to preserve the blank lines but ensure proper indentation
                    // for the method declaration (matching the original method's indent)
                    String removedPrefix = firstRemovedAnnotationPrefix.getWhitespace();
                    String methodPrefix = md.getPrefix().getWhitespace();

                    // Extract indent from method's current prefix (last line's whitespace)
                    int methodLastNewline = methodPrefix.lastIndexOf('\n');
                    String methodIndent = methodLastNewline >= 0
                        ? methodPrefix.substring(methodLastNewline + 1)
                        : methodPrefix;

                    // Extract blank lines from removed annotation's prefix
                    int removedLastNewline = removedPrefix.lastIndexOf('\n');
                    String blankLines = removedLastNewline > 0
                        ? removedPrefix.substring(0, removedLastNewline + 1)  // Keep all newlines including last
                        : "\n";

                    // Combine: blank lines + method indent
                    String normalized = blankLines + methodIndent;
                    md = md.withPrefix(md.getPrefix().withWhitespace(normalized));
                }
            }
            return md;
        }

        private J.ClassDeclaration transformClassAnnotations(J.ClassDeclaration cd, int removeMethodCount,
                                                              boolean hasStatefulTimeout, boolean hasPassivationCallbacks) {
            List<J.Annotation> newAnnotations = new ArrayList<>();
            Space originalPrefix = null;
            String originalIndent = "";
            boolean hasExistingScope = false;
            boolean hasExistingService = false;
            boolean hasExistingNeedsReview = false;

            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if (STATEFUL_MATCHER_JAKARTA.matches(ann) || STATEFUL_MATCHER_JAVAX.matches(ann)) {
                    // Capture original whitespace prefix from @Stateful for proper indentation
                    originalPrefix = ann.getPrefix();
                    // Extract the indent (spaces/tabs at the beginning of the line)
                    String whitespace = originalPrefix.getWhitespace();
                    int lastNewline = whitespace.lastIndexOf('\n');
                    originalIndent = lastNewline >= 0 ? whitespace.substring(lastNewline + 1) : "";
                    maybeRemoveImport(JAKARTA_STATEFUL);
                    maybeRemoveImport(JAVAX_STATEFUL);
                } else if (STATEFUL_TIMEOUT_MATCHER_JAKARTA.matches(ann) || STATEFUL_TIMEOUT_MATCHER_JAVAX.matches(ann)) {
                    // Remove @StatefulTimeout (no Spring equivalent)
                    maybeRemoveImport(JAKARTA_STATEFUL_TIMEOUT);
                    maybeRemoveImport(JAVAX_STATEFUL_TIMEOUT);
                } else {
                    // Check for existing Spring annotations to avoid duplicates
                    if (SCOPE_MATCHER.matches(ann)) {
                        hasExistingScope = true;
                    }
                    if (SERVICE_MATCHER.matches(ann)) {
                        hasExistingService = true;
                    }
                    if (NEEDS_REVIEW_MATCHER.matches(ann)) {
                        hasExistingNeedsReview = true;
                    }
                    newAnnotations.add(ann);
                }
            }

            // Build @NeedsReview guidance
            StringBuilder guidance = new StringBuilder();
            guidance.append("Stateful EJB migrated to prototype scope. ");

            if (hasPassivationCallbacks) {
                guidance.append("@PostActivate/@PrePassivate removed (passivation not supported in Spring). ");
            }
            if (hasStatefulTimeout) {
                guidance.append("@StatefulTimeout removed (no Spring equivalent; consider application-level timeout). ");
            }
            if (removeMethodCount > 0) {
                guidance.append("@Remove methods retained but require manual cleanup: ");
                guidance.append("Spring does NOT auto-call @PreDestroy for prototype-scoped beans. ");
                guidance.append("Use ObjectProvider<T>.destroy(bean) or implement explicit disposal. ");
            }

            guidance.append("Consider: For web sessions use @SessionScope; inject via ObjectProvider<T> for lazy/fresh instances.");

            // Compute proper prefix with indent for subsequent annotations
            Space firstPrefix = originalPrefix != null ? originalPrefix : Space.EMPTY;
            Space subsequentPrefix = Space.format("\n" + originalIndent);

            // Add new annotations only if not already present - order: @NeedsReview, @Scope, @Service
            if (!hasExistingNeedsReview) {
                maybeAddImport(NEEDS_REVIEW_FQN);
                newAnnotations.add(createNeedsReviewAnnotation(guidance.toString(), firstPrefix));
                // Subsequent annotations use indent-aware prefix
                firstPrefix = subsequentPrefix;
            }
            if (!hasExistingScope) {
                maybeAddImport(SCOPE_FQN);
                newAnnotations.add(createScopeAnnotation(firstPrefix));
                firstPrefix = subsequentPrefix;
            }
            if (!hasExistingService) {
                maybeAddImport(SERVICE_FQN);
                newAnnotations.add(createServiceAnnotation(firstPrefix));
            }

            return cd.withLeadingAnnotations(newAnnotations);
        }

        private J.Annotation createServiceAnnotation(Space prefix) {
            return new J.Annotation(
                Tree.randomId(),
                prefix,
                org.openrewrite.marker.Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    org.openrewrite.marker.Markers.EMPTY,
                    Collections.emptyList(),
                    "Service",
                    org.openrewrite.java.tree.JavaType.ShallowClass.build(SERVICE_FQN),
                    null
                ),
                null
            );
        }

        private J.Annotation createScopeAnnotation(Space prefix) {
            // @Scope("prototype")
            J.Literal prototypeValue = new J.Literal(
                Tree.randomId(),
                Space.EMPTY,
                org.openrewrite.marker.Markers.EMPTY,
                "prototype",
                "\"prototype\"",
                null,
                org.openrewrite.java.tree.JavaType.Primitive.String
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                org.openrewrite.marker.Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    org.openrewrite.marker.Markers.EMPTY,
                    Collections.emptyList(),
                    "Scope",
                    org.openrewrite.java.tree.JavaType.ShallowClass.build(SCOPE_FQN),
                    null
                ),
                org.openrewrite.java.tree.JContainer.build(
                    Space.EMPTY,
                    Collections.singletonList(
                        org.openrewrite.java.tree.JRightPadded.build(prototypeValue)
                    ),
                    org.openrewrite.marker.Markers.EMPTY
                )
            );
        }

        private J.Annotation createNeedsReviewAnnotation(String reason, Space prefix) {
            String escapedReason = reason.replace("\"", "\\\"");
            String suggestedAction = "Review scope: @SessionScope for web session affinity, " +
                "@Scope(\"prototype\") + ObjectProvider<T> for per-call instances. " +
                "For cleanup, call ObjectProvider.destroy(bean) or implement explicit disposal.";

            // Build arguments: reason, category, originalCode, suggestedAction
            List<Expression> args = new ArrayList<>();
            args.add(buildAssignment("reason", escapedReason));
            args.add(buildCategoryAssignment());
            args.add(buildAssignment("originalCode", "@Stateful"));
            args.add(buildAssignment("suggestedAction", suggestedAction));

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                org.openrewrite.marker.Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    org.openrewrite.marker.Markers.EMPTY,
                    Collections.emptyList(),
                    "NeedsReview",
                    org.openrewrite.java.tree.JavaType.ShallowClass.build(NEEDS_REVIEW_FQN),
                    null
                ),
                org.openrewrite.java.tree.JContainer.build(
                    Space.EMPTY,
                    args.stream()
                        .map(arg -> org.openrewrite.java.tree.JRightPadded.build(arg))
                        .collect(java.util.stream.Collectors.toList()),
                    org.openrewrite.marker.Markers.EMPTY
                )
            );
        }

        private J.Assignment buildAssignment(String name, String value) {
            Expression literal = new J.Literal(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                org.openrewrite.marker.Markers.EMPTY,
                value,
                "\"" + value.replace("\"", "\\\"") + "\"",
                Collections.emptyList(),
                org.openrewrite.java.tree.JavaType.Primitive.String
            );
            return new J.Assignment(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                org.openrewrite.marker.Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    org.openrewrite.marker.Markers.EMPTY,
                    Collections.emptyList(),
                    name,
                    null,
                    null
                ),
                org.openrewrite.java.tree.JLeftPadded.build(literal).withBefore(Space.SINGLE_SPACE),
                null
            );
        }

        private J.Assignment buildCategoryAssignment() {
            Expression fieldAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                org.openrewrite.marker.Markers.EMPTY,
                new J.FieldAccess(
                    Tree.randomId(),
                    Space.EMPTY,
                    org.openrewrite.marker.Markers.EMPTY,
                    new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        org.openrewrite.marker.Markers.EMPTY,
                        Collections.emptyList(),
                        "NeedsReview",
                        null,
                        null
                    ),
                    org.openrewrite.java.tree.JLeftPadded.build(
                        new J.Identifier(
                            Tree.randomId(),
                            Space.EMPTY,
                            org.openrewrite.marker.Markers.EMPTY,
                            Collections.emptyList(),
                            "Category",
                            null,
                            null
                        )
                    ),
                    null
                ),
                org.openrewrite.java.tree.JLeftPadded.build(
                    new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        org.openrewrite.marker.Markers.EMPTY,
                        Collections.emptyList(),
                        "STATEFUL_BEAN",
                        null,
                        null
                    )
                ),
                null
            );
            return new J.Assignment(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                org.openrewrite.marker.Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    org.openrewrite.marker.Markers.EMPTY,
                    Collections.emptyList(),
                    "category",
                    null,
                    null
                ),
                org.openrewrite.java.tree.JLeftPadded.build(fieldAccess).withBefore(Space.SINGLE_SPACE),
                null
            );
        }
    }
}
