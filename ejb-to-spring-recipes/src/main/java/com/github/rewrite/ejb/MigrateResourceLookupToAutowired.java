package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Migrates @Resource(lookup="...") to @Autowired with @NeedsReview annotation.
 * <p>
 * Since JNDI lookups don't have a direct Spring equivalent, this recipe:
 * 1. Replaces @Resource(lookup="...") with @Autowired
 * 2. Adds @NeedsReview annotation with the original JNDI name
 * <p>
 * For known JNDI-backed types (DataSource, ConnectionFactory), use @JndiLookup
 * instead of @Autowired and skip @NeedsReview.
 * <p>
 * The @NeedsReview annotation helps developers identify resources that need
 * Spring configuration (e.g., @Bean definitions or application.properties).
 * <p>
 * Works with both jakarta.annotation.Resource and javax.annotation.Resource.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateResourceLookupToAutowired extends Recipe {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Migrate @Resource(lookup=...) to @Autowired";
    }

    @Override
    public String getDescription() {
        return "Replaces @Resource annotations with lookup attribute to @Autowired, adding @NeedsReview for manual configuration.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>("jakarta.annotation.Resource", false),
                new UsesType<>("javax.annotation.Resource", false)
            ),
            new ResourceLookupVisitor()
        );
    }

    private class ResourceLookupVisitor extends JavaIsoVisitor<ExecutionContext> {

        private static final String JAKARTA_RESOURCE = "jakarta.annotation.Resource";
        private static final String JAVAX_RESOURCE = "javax.annotation.Resource";
        private static final String SPRING_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
        private static final String SPRING_JNDI_LOOKUP = "org.springframework.jndi.JndiLookup";
        private static final String JAVAX_SQL_DATASOURCE = "javax.sql.DataSource";
        private static final String JAVAX_JMS_CONNECTION_FACTORY = "javax.jms.ConnectionFactory";
        private static final String JAKARTA_JMS_CONNECTION_FACTORY = "jakarta.jms.ConnectionFactory";

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations variableDeclarations, ExecutionContext ctx) {
            // Extract lookup values from annotations BEFORE calling super
            String lookupValue = null;
            for (J.Annotation ann : variableDeclarations.getLeadingAnnotations()) {
                if (isResourceAnnotation(ann)) {
                    lookupValue = extractLookupValue(ann);
                    if (lookupValue != null) {
                        break;
                    }
                }
            }

            boolean jndiLookupEligible = lookupValue != null && isJndiLookupEligible(variableDeclarations);

            // Call super (transforms the annotations)
            J.VariableDeclarations vd = super.visitVariableDeclarations(variableDeclarations, ctx);

            // If lookup value was found and not eligible for @JndiLookup, add @NeedsReview annotation
            if (lookupValue != null && !jndiLookupEligible) {
                boolean hasNeedsReview = vd.getLeadingAnnotations().stream()
                    .anyMatch(a -> a.getSimpleName().equals("NeedsReview"));

                if (!hasNeedsReview) {
                    String escapedLookup = lookupValue.replace("\"", "'");
                    maybeAddImport(NEEDS_REVIEW_FQN);

                    // Get prefix from first annotation for proper positioning
                    Space needsReviewPrefix = vd.getLeadingAnnotations().isEmpty()
                        ? vd.getPrefix()
                        : vd.getLeadingAnnotations().get(0).getPrefix();

                    // Create @NeedsReview annotation directly (for idempotency)
                    J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                        "JNDI lookup '" + escapedLookup + "' needs Spring configuration",
                        "CONFIGURATION",
                        "@Resource(lookup='" + escapedLookup + "')",
                        "Create @Bean definition or add to application.properties",
                        needsReviewPrefix
                    );

                    // Insert @NeedsReview at beginning
                    List<J.Annotation> newAnnotations = new ArrayList<>();
                    newAnnotations.add(needsReviewAnn);

                    for (int i = 0; i < vd.getLeadingAnnotations().size(); i++) {
                        J.Annotation ann = vd.getLeadingAnnotations().get(i);
                        if (i == 0) {
                            // Remove prefix from first original annotation
                            String ws = needsReviewPrefix.getWhitespace();
                            ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n    "));
                        }
                        newAnnotations.add(ann);
                    }

                    vd = vd.withLeadingAnnotations(newAnnotations);
                }
            }

            return vd;
        }

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation ann = super.visitAnnotation(annotation, ctx);

            // Check if this is @Resource annotation
            if (!isResourceAnnotation(ann)) {
                return ann;
            }

            // Only transform @Resource with lookup= attribute!
            // Plain @Resource without lookup stays unchanged
            String lookupValue = extractLookupValue(ann);
            if (lookupValue == null) {
                return ann;
            }

            boolean jndiLookupEligible = isJndiLookupEligible(getCursor().firstEnclosing(J.VariableDeclarations.class));
            if (jndiLookupEligible) {
                maybeAddImport(SPRING_JNDI_LOOKUP);
                maybeRemoveImport(JAKARTA_RESOURCE);
                maybeRemoveImport(JAVAX_RESOURCE);
                return createJndiLookupAnnotation(ann, lookupValue);
            }

            // Fallback: use @Autowired
            maybeAddImport(SPRING_AUTOWIRED);
            maybeRemoveImport(JAKARTA_RESOURCE);
            maybeRemoveImport(JAVAX_RESOURCE);

            // Create new @Autowired annotation identifier
            J.Identifier autowiredIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                java.util.Collections.emptyList(),
                "Autowired",
                JavaType.ShallowClass.build(SPRING_AUTOWIRED),
                null
            );

            // Create new annotation without arguments
            return ann
                .withAnnotationType(autowiredIdent)
                .withArguments(null);
        }

        private boolean isJndiLookupEligible(J.VariableDeclarations varDecls) {
            if (varDecls == null) {
                return false;
            }
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }
            JavaType type = typeExpr.getType();
            return TypeUtils.isAssignableTo(JAVAX_SQL_DATASOURCE, type) ||
                   TypeUtils.isAssignableTo(JAVAX_JMS_CONNECTION_FACTORY, type) ||
                   TypeUtils.isAssignableTo(JAKARTA_JMS_CONNECTION_FACTORY, type);
        }

        private boolean isResourceAnnotation(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), JAKARTA_RESOURCE) ||
                   TypeUtils.isOfClassType(ann.getType(), JAVAX_RESOURCE);
        }

        private String extractLookupValue(J.Annotation annotation) {
            List<Expression> args = annotation.getArguments();
            if (args == null || args.isEmpty()) {
                return null;
            }

            for (Expression arg : args) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        if ("lookup".equals(attrName)) {
                            Expression value = assignment.getAssignment();
                            if (value instanceof J.Literal) {
                                Object literalValue = ((J.Literal) value).getValue();
                                return literalValue != null ? literalValue.toString() : null;
                            }
                        }
                    }
                }
            }

            return null;
        }

        private J.Annotation createJndiLookupAnnotation(J.Annotation annotation, String lookupValue) {
            J.Identifier jndiLookupIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                java.util.Collections.emptyList(),
                "JndiLookup",
                JavaType.ShallowClass.build(SPRING_JNDI_LOOKUP),
                null
            );

            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                lookupValue,
                "\"" + lookupValue + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            return annotation
                .withAnnotationType(jndiLookupIdent)
                .withArguments(Collections.singletonList(valueExpr));
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
    }
}
