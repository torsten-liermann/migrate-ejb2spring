/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - @ManagedBean to @Named conversion
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

/**
 * Migrates JSF @ManagedBean to CDI @Named annotation.
 * <p>
 * @ManagedBean and @ManagedProperty are deprecated since JSF 2.3 and should be
 * replaced with CDI equivalents for JoinFaces compatibility.
 * <p>
 * <b>Safe transformations (automated):</b>
 * <ul>
 *   <li>@ManagedBean → @Named</li>
 *   <li>@ManagedBean(name="x") → @Named("x")</li>
 * </ul>
 * <p>
 * <b>Unsafe transformations (marker added):</b>
 * <ul>
 *   <li>@ManagedBean(eager=true) → @Named + @NeedsReview (no CDI equivalent)</li>
 *   <li>Class contains @ManagedProperty → No migration, class-level @NeedsReview</li>
 * </ul>
 * <p>
 * Note: When @ManagedProperty is present, the entire class is blocked from auto-migration
 * because @ManagedProperty requires manual conversion to @Inject or other mechanisms.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateManagedBeanToNamed extends Recipe {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    // JSF @ManagedBean annotations
    private static final String MANAGED_BEAN_JAKARTA = "jakarta.faces.bean.ManagedBean";
    private static final String MANAGED_BEAN_JAVAX = "javax.faces.bean.ManagedBean";

    // JSF @ManagedProperty annotations
    private static final String MANAGED_PROPERTY_JAKARTA = "jakarta.faces.bean.ManagedProperty";
    private static final String MANAGED_PROPERTY_JAVAX = "javax.faces.bean.ManagedProperty";

    // CDI @Named annotation
    private static final String NAMED_JAKARTA = "jakarta.inject.Named";

    @Override
    public String getDisplayName() {
        return "Migrate @ManagedBean to @Named";
    }

    @Override
    public String getDescription() {
        return "Converts JSF @ManagedBean annotations to CDI @Named. " +
               "Handles both jakarta.faces.bean and javax.faces.bean namespaces. " +
               "Classes with @ManagedProperty are not auto-migrated but marked for manual review.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        // Note: No Preconditions.check() with UsesType because it requires type resolution.
        // The visitor itself detects @ManagedBean patterns via annotations.
        return new ManagedBeanToNamedVisitor();
    }

    private class ManagedBeanToNamedVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Check if class already has @NeedsReview
            boolean hasNeedsReview = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> a.getSimpleName().equals("NeedsReview"));
            if (hasNeedsReview) {
                return cd;
            }

            // Find @ManagedBean annotation
            ManagedBeanInfo managedBeanInfo = findManagedBeanAnnotation(cd);
            if (managedBeanInfo == null) {
                return cd;
            }

            // Check for @ManagedProperty fields - if present, block auto-migration
            List<String> managedProperties = findManagedPropertyFields(cd);
            if (!managedProperties.isEmpty()) {
                return markClassWithManagedProperty(cd, managedProperties);
            }

            // Safe to migrate @ManagedBean to @Named
            return migrateManagedBeanToNamed(cd, managedBeanInfo);
        }

        /**
         * Information about @ManagedBean annotation.
         */
        private static class ManagedBeanInfo {
            J.Annotation annotation;
            int annotationIndex;
            String name;  // null if not specified
            boolean eager;  // true if eager=true specified
        }

        /**
         * Finds @ManagedBean annotation on the class and extracts its attributes.
         */
        private ManagedBeanInfo findManagedBeanAnnotation(J.ClassDeclaration classDecl) {
            List<J.Annotation> annotations = classDecl.getLeadingAnnotations();
            for (int i = 0; i < annotations.size(); i++) {
                J.Annotation ann = annotations.get(i);
                if (TypeUtils.isOfClassType(ann.getType(), MANAGED_BEAN_JAKARTA) ||
                    TypeUtils.isOfClassType(ann.getType(), MANAGED_BEAN_JAVAX) ||
                    "ManagedBean".equals(ann.getSimpleName())) {

                    ManagedBeanInfo info = new ManagedBeanInfo();
                    info.annotation = ann;
                    info.annotationIndex = i;
                    info.name = null;
                    info.eager = false;

                    // Extract annotation arguments
                    if (ann.getArguments() != null) {
                        for (Expression arg : ann.getArguments()) {
                            if (arg instanceof J.Assignment) {
                                J.Assignment assignment = (J.Assignment) arg;
                                String key = assignment.getVariable().toString();
                                Expression value = assignment.getAssignment();

                                if ("name".equals(key) && value instanceof J.Literal) {
                                    J.Literal literal = (J.Literal) value;
                                    if (literal.getValue() instanceof String) {
                                        info.name = (String) literal.getValue();
                                    }
                                } else if ("eager".equals(key) && value instanceof J.Literal) {
                                    J.Literal literal = (J.Literal) value;
                                    if (Boolean.TRUE.equals(literal.getValue())) {
                                        info.eager = true;
                                    }
                                }
                            } else if (arg instanceof J.Literal) {
                                // @ManagedBean("name") shorthand
                                J.Literal literal = (J.Literal) arg;
                                if (literal.getValue() instanceof String) {
                                    info.name = (String) literal.getValue();
                                }
                            }
                        }
                    }

                    return info;
                }
            }
            return null;
        }

        /**
         * Finds fields and methods with @ManagedProperty annotation.
         * Returns list of @ManagedProperty values for the marker.
         * <p>
         * Note: @ManagedProperty can be placed on fields OR setter methods in JSF.
         */
        private List<String> findManagedPropertyFields(J.ClassDeclaration classDecl) {
            List<String> managedProperties = new ArrayList<>();

            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    // Check fields for @ManagedProperty
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        for (J.Annotation ann : vd.getLeadingAnnotations()) {
                            if (isManagedPropertyAnnotation(ann)) {
                                managedProperties.add(formatManagedProperty(ann));
                            }
                        }
                    }
                    // Check methods (setters) for @ManagedProperty
                    else if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                        for (J.Annotation ann : md.getLeadingAnnotations()) {
                            if (isManagedPropertyAnnotation(ann)) {
                                managedProperties.add(formatManagedProperty(ann));
                            }
                        }
                    }
                }
            }

            return managedProperties;
        }

        /**
         * Checks if the annotation is @ManagedProperty.
         */
        private boolean isManagedPropertyAnnotation(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), MANAGED_PROPERTY_JAKARTA) ||
                   TypeUtils.isOfClassType(ann.getType(), MANAGED_PROPERTY_JAVAX) ||
                   "ManagedProperty".equals(ann.getSimpleName());
        }

        /**
         * Formats @ManagedProperty annotation for originalCode.
         */
        private String formatManagedProperty(J.Annotation ann) {
            String value = extractAnnotationStringValue(ann);
            if (value != null) {
                return "@ManagedProperty(" + value + ")";
            } else {
                return "@ManagedProperty";
            }
        }

        /**
         * Extracts string value from annotation (first string argument or value attribute).
         */
        private String extractAnnotationStringValue(J.Annotation ann) {
            if (ann.getArguments() != null) {
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Literal) {
                        J.Literal literal = (J.Literal) arg;
                        if (literal.getValue() instanceof String) {
                            return (String) literal.getValue();
                        }
                    } else if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        String key = assignment.getVariable().toString();
                        if ("value".equals(key) && assignment.getAssignment() instanceof J.Literal) {
                            J.Literal literal = (J.Literal) assignment.getAssignment();
                            if (literal.getValue() instanceof String) {
                                return (String) literal.getValue();
                            }
                        }
                    }
                }
            }
            return null;
        }

        /**
         * Marks class with @NeedsReview because it contains @ManagedProperty.
         * Does NOT migrate @ManagedBean.
         */
        private J.ClassDeclaration markClassWithManagedProperty(J.ClassDeclaration cd, List<String> managedProperties) {
            maybeAddImport(NEEDS_REVIEW_FQN);
            doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN + ".Category", "MANUAL_MIGRATION", false));

            String originalCode = String.join(", ", managedProperties);
            String suggestedAction = "Manuell migrieren: @ManagedProperty(#{beanName}) -> @Inject BeanType; " +
                                     "EL-Expressions wie #{param.x} manuell behandeln";

            Space prefix = cd.getLeadingAnnotations().isEmpty()
                ? cd.getPrefix()
                : cd.getLeadingAnnotations().get(0).getPrefix();

            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                "@ManagedProperty erfordert manuelle CDI-Migration",
                "MANUAL_MIGRATION",
                originalCode,
                suggestedAction,
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

        /**
         * Migrates @ManagedBean to @Named.
         * If eager=true, also adds @NeedsReview marker.
         */
        private J.ClassDeclaration migrateManagedBeanToNamed(J.ClassDeclaration cd, ManagedBeanInfo info) {
            // Add import for @Named
            maybeAddImport(NAMED_JAKARTA);

            // Remove import for @ManagedBean
            maybeRemoveImport(MANAGED_BEAN_JAKARTA);
            maybeRemoveImport(MANAGED_BEAN_JAVAX);

            // Build @Named annotation
            J.Annotation namedAnn = createNamedAnnotation(info.name, info.annotation.getPrefix());

            // Replace @ManagedBean with @Named
            List<J.Annotation> newAnnotations = new ArrayList<>(cd.getLeadingAnnotations());
            newAnnotations.set(info.annotationIndex, namedAnn);

            J.ClassDeclaration result = cd.withLeadingAnnotations(newAnnotations);

            // If eager=true, add @NeedsReview marker
            if (info.eager) {
                result = addEagerMarker(result);
            }

            return result;
        }

        /**
         * Creates @Named annotation with optional value.
         */
        private J.Annotation createNamedAnnotation(String name, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NAMED_JAKARTA);

            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Named",
                type,
                null
            );

            JContainer<Expression> args = null;
            if (name != null && !name.isEmpty()) {
                // @Named("value")
                J.Literal literal = new J.Literal(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    name,
                    "\"" + escapeJavaString(name) + "\"",
                    Collections.emptyList(),
                    JavaType.Primitive.String
                );

                List<JRightPadded<Expression>> argList = new ArrayList<>();
                argList.add(new JRightPadded<>(literal, Space.EMPTY, Markers.EMPTY));

                args = JContainer.build(
                    Space.EMPTY,
                    argList,
                    Markers.EMPTY
                );
            }

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }

        /**
         * Adds @NeedsReview marker for eager=true case.
         */
        private J.ClassDeclaration addEagerMarker(J.ClassDeclaration cd) {
            maybeAddImport(NEEDS_REVIEW_FQN);
            doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN + ".Category", "SEMANTIC_CHANGE", false));

            Space prefix = cd.getLeadingAnnotations().isEmpty()
                ? cd.getPrefix()
                : cd.getLeadingAnnotations().get(0).getPrefix();

            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                "ManagedBean(eager=true) hat keine direkte CDI-Entsprechung",
                "SEMANTIC_CHANGE",
                "@ManagedBean(eager=true)",
                "Pruefe: @PostConstruct + CommandLineRunner oder ApplicationListener fuer Startup-Logik",
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
