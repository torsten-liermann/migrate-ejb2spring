package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adds @Profile("manual-migration") to classes marked with @NeedsReview(category = MANUAL_MIGRATION).
 * <p>
 * This is a safety gate that prevents beans requiring manual migration from being loaded
 * by default. After manual migration is complete, the profile annotation can be removed
 * or the profile can be activated.
 * <p>
 * This recipe should run AFTER MarkManualMigrations (Phase 2.5) to ensure all
 * MANUAL_MIGRATION classes are already marked.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class AddProfileToManualMigrationClasses extends Recipe {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final String PROFILE_FQN = "org.springframework.context.annotation.Profile";
    private static final String PROFILE_NAME = "manual-migration";
    @Override
    public String getDisplayName() {
        return "Add @Profile to MANUAL_MIGRATION classes";
    }

    @Override
    public String getDescription() {
        return "Adds @Profile(\"" + PROFILE_NAME + "\") to classes with @NeedsReview(category = MANUAL_MIGRATION). " +
               "This prevents beans requiring manual migration from being loaded by default, " +
               "avoiding runtime errors until migration is complete.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Check if class or its members have @NeedsReview with category = MANUAL_MIGRATION
                if (!hasManualMigrationReview(cd, ctx)) {
                    return cd;
                }

                // Check if class already has @Profile
                boolean hasProfile = cd.getLeadingAnnotations().stream()
                    .anyMatch(a -> "Profile".equals(a.getSimpleName()) ||
                                   TypeUtils.isOfClassType(a.getType(), PROFILE_FQN));

                if (hasProfile) {
                    return cd;
                }

                // Add @Profile annotation
                doAfterVisit(new AddImport<>(PROFILE_FQN, null, false));
                List<J.Annotation> updatedAnnotations = new ArrayList<>(cd.getLeadingAnnotations());
                boolean hadAnnotations = !updatedAnnotations.isEmpty();
                Space classPrefix = cd.getPrefix();
                Space basePrefix = hadAnnotations
                    ? updatedAnnotations.get(0).getPrefix()
                    : classPrefix;
                String indent = extractIndent(basePrefix);

                int insertIndex = 0;
                for (int i = 0; i < updatedAnnotations.size(); i++) {
                    if (isNeedsReviewAnnotation(updatedAnnotations.get(i))) {
                        insertIndex = i + 1;
                    }
                }

                Space profilePrefix;
                if (updatedAnnotations.isEmpty()) {
                    profilePrefix = Space.EMPTY;
                } else if (insertIndex == 0) {
                    profilePrefix = basePrefix;
                    J.Annotation first = updatedAnnotations.get(0);
                    updatedAnnotations.set(0, first.withPrefix(Space.format("\n" + indent)));
                } else if (insertIndex < updatedAnnotations.size()) {
                    J.Annotation target = updatedAnnotations.get(insertIndex);
                    profilePrefix = target.getPrefix();
                    updatedAnnotations.set(insertIndex, target.withPrefix(Space.format("\n" + indent)));
                } else {
                    profilePrefix = Space.format("\n" + indent);
                }

                updatedAnnotations.add(insertIndex, createProfileAnnotation(profilePrefix));

                J.ClassDeclaration updatedClass = cd.withLeadingAnnotations(updatedAnnotations);
                if (!hadAnnotations) {
                    updatedClass = updatedClass.withPrefix(classPrefix);
                    updatedClass = updateFirstModifierPrefix(updatedClass, indent);
                }
                return updatedClass;
            }

            private boolean hasManualMigrationReview(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                ManualNeedsReviewFinder finder = new ManualNeedsReviewFinder();
                finder.visit(classDecl, ctx);
                return finder.found;
            }

            private boolean isManualNeedsReview(J.Annotation ann) {
                if (!"NeedsReview".equals(ann.getSimpleName()) &&
                    !TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN)) {
                    return false;
                }
                if (ann.getArguments() == null) {
                    return false;
                }
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            J.Identifier key = (J.Identifier) assignment.getVariable();
                            if ("category".equals(key.getSimpleName())) {
                                String valueStr = assignment.getAssignment().toString();
                                if (valueStr.contains("MANUAL_MIGRATION")) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                return false;
            }

            private class ManualNeedsReviewFinder extends JavaIsoVisitor<ExecutionContext> {
                boolean found;

                @Override
                public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                    J.Annotation ann = super.visitAnnotation(annotation, ctx);
                    if (!found && isManualNeedsReview(ann)) {
                        found = true;
                    }
                    return ann;
                }
            }

        };
    }

    private static boolean isNeedsReviewAnnotation(J.Annotation ann) {
        return "NeedsReview".equals(ann.getSimpleName()) ||
               TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN);
    }

    private static boolean isProfileAnnotation(J.Annotation ann) {
        return "Profile".equals(ann.getSimpleName()) ||
               TypeUtils.isOfClassType(ann.getType(), PROFILE_FQN);
    }

    private static J.Annotation createProfileAnnotation(Space prefix) {
        JavaType.ShallowClass type = JavaType.ShallowClass.build(PROFILE_FQN);
        J.Identifier ident = new J.Identifier(
            Tree.randomId(),
            Space.EMPTY,
            Markers.EMPTY,
            Collections.emptyList(),
            "Profile",
            type,
            null
        );

        J.Literal valueExpr = new J.Literal(
            Tree.randomId(),
            Space.EMPTY,
            Markers.EMPTY,
            PROFILE_NAME,
            "\"" + PROFILE_NAME + "\"",
            Collections.emptyList(),
            JavaType.Primitive.String
        );

        List<JRightPadded<Expression>> arguments = new ArrayList<>();
        arguments.add(new JRightPadded<>(valueExpr, Space.EMPTY, Markers.EMPTY));

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

    private static String extractIndent(Space prefix) {
        String ws = prefix.getWhitespace();
        int lastNewline = ws.lastIndexOf('\n');
        if (lastNewline >= 0) {
            return ws.substring(lastNewline + 1);
        }
        return "";
    }

    private static J.ClassDeclaration updateFirstModifierPrefix(J.ClassDeclaration classDecl, String indent) {
        List<J.Modifier> modifiers = classDecl.getModifiers();
        if (modifiers.isEmpty()) {
            return classDecl;
        }
        List<J.Modifier> updated = new ArrayList<>(modifiers);
        J.Modifier first = updated.get(0).withPrefix(Space.format("\n" + indent));
        updated.set(0, first);
        return classDecl.withModifiers(updated);
    }
}
