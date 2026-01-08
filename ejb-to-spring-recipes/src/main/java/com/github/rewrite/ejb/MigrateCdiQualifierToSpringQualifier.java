/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for MKR-008 - Custom CDI Qualifiers to Spring @Qualifier conversion
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
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

/**
 * Migrates custom CDI Qualifiers to Spring @Qualifier.
 * <p>
 * CDI allows custom qualifier annotations (meta-annotated with @jakarta.inject.Qualifier).
 * Spring uses @Qualifier("beanName") for the same purpose.
 * <p>
 * This recipe handles:
 * <ul>
 *   <li><b>Injection points</b>: Fields and constructor/method parameters with CDI qualifiers</li>
 *   <li><b>Bean definitions</b>: Classes annotated with CDI qualifiers (also need Spring @Qualifier)</li>
 * </ul>
 * <p>
 * Limitations (marked with @NeedsReview):
 * <ul>
 *   <li>Qualifiers with attributes (e.g., @Region("EU")) - values cannot be preserved</li>
 *   <li>Multiple CDI qualifiers on same injection point - Spring cannot represent qualifier intersection</li>
 *   <li>Event-related types (Event&lt;T&gt;, ApplicationEventPublisher) - require wrapper events</li>
 * </ul>
 * <p>
 * Bean definition handling:
 * <ul>
 *   <li>Class-level CDI qualifiers are converted to Spring @Qualifier</li>
 *   <li>Producer methods/fields with CDI qualifiers are also converted</li>
 * </ul>
 * <p>
 * Example (injection point):
 * <pre>
 * // Before: Custom CDI qualifier
 * @Inject @Premium
 * private PaymentService paymentService;
 *
 * // After: Spring @Qualifier
 * @Autowired @Qualifier("premium")
 * private PaymentService paymentService;
 * </pre>
 * <p>
 * Example (bean definition):
 * <pre>
 * // Before: Bean with CDI qualifier
 * @Named @Premium
 * public class PremiumPaymentService implements PaymentService {}
 *
 * // After: Bean with Spring @Qualifier
 * @Named @Qualifier("premium")
 * public class PremiumPaymentService implements PaymentService {}
 * </pre>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateCdiQualifierToSpringQualifier extends Recipe {

    private static final String SPRING_QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier";
    private static final String CDI_QUALIFIER_JAKARTA = "jakarta.inject.Qualifier";
    private static final String CDI_QUALIFIER_JAVAX = "javax.inject.Qualifier";
    private static final String CDI_NAMED_JAKARTA = "jakarta.inject.Named";
    private static final String CDI_NAMED_JAVAX = "javax.inject.Named";
    private static final String APP_EVENT_PUBLISHER = "org.springframework.context.ApplicationEventPublisher";
    private static final String CDI_EVENT_JAKARTA = "jakarta.enterprise.event.Event";
    private static final String CDI_EVENT_JAVAX = "javax.enterprise.event.Event";
    private static final String CDI_OBSERVES_JAKARTA = "jakarta.enterprise.event.Observes";
    private static final String CDI_OBSERVES_JAVAX = "javax.enterprise.event.Observes";
    private static final String CDI_PRODUCES_JAKARTA = "jakarta.enterprise.inject.Produces";
    private static final String CDI_PRODUCES_JAVAX = "javax.enterprise.inject.Produces";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    // Standard annotations that are NOT CDI qualifiers
    private static final Set<String> KNOWN_NON_QUALIFIERS = Set.of(
        // Spring
        "Autowired", "Value", "Qualifier", "Primary", "Lazy", "Scope", "Profile",
        "Bean", "Component", "Service", "Repository", "Controller", "RestController", "Configuration",
        // Jakarta/Java
        "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface",
        "PostConstruct", "PreDestroy", "Resource",
        // JPA/Transactions
        "Transactional", "PersistenceContext", "PersistenceUnit", "Entity", "Table",
        // Validation
        "NotNull", "NotEmpty", "NotBlank", "Valid", "Size", "Min", "Max",
        "Pattern", "Email", "Digits", "Future", "Past", "Positive", "Negative",
        // CDI/JSR-330 (handled separately)
        "Named", "Inject", "Produces",
        // Our annotations
        "NeedsReview",
        // Event related
        "EventListener", "TransactionalEventListener", "Async"
    );

    @Override
    public String getDisplayName() {
        return "Migrate custom CDI Qualifiers to Spring @Qualifier";
    }

    @Override
    public String getDescription() {
        return "Converts custom CDI qualifier annotations (meta-annotated with @jakarta.inject.Qualifier) " +
               "on injection points to Spring @Qualifier. The qualifier's simple name is used as the " +
               "qualifier value (e.g., @Premium becomes @Qualifier(\"premium\")). " +
               "Does not apply to ApplicationEventPublisher fields (events require wrapper classes).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new CdiQualifierVisitor();
    }

    private class CdiQualifierVisitor extends JavaIsoVisitor<ExecutionContext> {

        // Track which custom qualifier imports to remove
        private final Set<String> qualifiersToRemove = new HashSet<>();

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            qualifiersToRemove.clear();
            J.CompilationUnit result = super.visitCompilationUnit(cu, ctx);

            // Remove imports of migrated qualifiers (only if no remaining usage)
            for (String fqn : qualifiersToRemove) {
                result = (J.CompilationUnit) new RemoveImport<ExecutionContext>(fqn, false).visit(result, ctx);
            }

            return result;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Find custom CDI qualifier annotations on the class (bean definition)
            List<QualifierInfo> qualifiers = findAllCustomCdiQualifiers(cd.getLeadingAnnotations());
            if (qualifiers.isEmpty()) {
                return cd;
            }

            // Skip if already has Spring @Qualifier
            if (hasSpringQualifier(cd.getLeadingAnnotations())) {
                return cd;
            }

            // Multiple qualifiers need @NeedsReview (but still not convertible)
            if (qualifiers.size() > 1) {
                if (!hasNeedsReviewAnnotation(cd.getLeadingAnnotations())) {
                    return markClassForReview(cd, qualifiers,
                        "Multiple CDI qualifiers on bean definition. Spring @Qualifier cannot represent " +
                        "qualifier intersection. Consider using explicit bean names or custom Spring qualifiers.");
                }
                return cd; // Already marked, cannot convert multiple qualifiers
            }

            QualifierInfo qualifierInfo = qualifiers.get(0);

            // Qualifier with attributes needs @NeedsReview (but still not convertible)
            if (qualifierInfo.hasAttributes) {
                if (!hasNeedsReviewAnnotation(cd.getLeadingAnnotations())) {
                    return markClassForReview(cd, qualifiers,
                        "CDI Qualifier @" + qualifierInfo.simpleName + " has attributes that cannot be " +
                        "preserved in Spring @Qualifier. Original: " + qualifierInfo.originalAnnotationText + ". " +
                        "Consider custom Spring qualifier annotation or explicit bean naming.");
                }
                return cd; // Already marked, cannot convert attributed qualifier
            }

            // Convert CDI qualifier to Spring @Qualifier
            return convertClassToSpringQualifier(cd, qualifierInfo);
        }

        private J.ClassDeclaration markClassForReview(J.ClassDeclaration cd, List<QualifierInfo> qualifiers, String reason) {
            maybeAddImport(NEEDS_REVIEW_FQN);

            // Build @NeedsReview annotation
            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                cd.getLeadingAnnotations().isEmpty() ? cd.getPrefix() : cd.getLeadingAnnotations().get(0).getPrefix(),
                reason
            );

            // Add @NeedsReview at the beginning
            List<J.Annotation> newAnnotations = new ArrayList<>();
            newAnnotations.add(needsReviewAnn);

            String annotationPrefix = calculateAnnotationPrefix(cd);
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                newAnnotations.add(ann.withPrefix(Space.format(annotationPrefix)));
            }

            return cd.withLeadingAnnotations(newAnnotations);
        }

        private J.ClassDeclaration convertClassToSpringQualifier(J.ClassDeclaration cd, QualifierInfo qualifierInfo) {
            maybeAddImport(SPRING_QUALIFIER);

            // Track the qualifier for import removal
            if (qualifierInfo.fqn != null) {
                qualifiersToRemove.add(qualifierInfo.fqn);
            }

            // Build new annotation list
            List<J.Annotation> newAnnotations = new ArrayList<>();

            for (int i = 0; i < cd.getLeadingAnnotations().size(); i++) {
                J.Annotation ann = cd.getLeadingAnnotations().get(i);

                if (i == qualifierInfo.annotationIndex) {
                    // Replace CDI qualifier with Spring @Qualifier
                    String qualifierValue = toQualifierValue(qualifierInfo.simpleName);
                    J.Annotation springQualifier = createSpringQualifierAnnotation(qualifierValue, ann.getPrefix());
                    newAnnotations.add(springQualifier);
                } else {
                    newAnnotations.add(ann);
                }
            }

            return cd.withLeadingAnnotations(newAnnotations);
        }

        private String calculateAnnotationPrefix(J.ClassDeclaration cd) {
            if (!cd.getLeadingAnnotations().isEmpty()) {
                String prefix = cd.getLeadingAnnotations().get(0).getPrefix().getWhitespace();
                if (prefix.contains("\n")) {
                    return prefix;
                }
            }
            String classPrefix = cd.getPrefix().getWhitespace();
            if (classPrefix.contains("\n")) {
                int lastNewline = classPrefix.lastIndexOf('\n');
                return classPrefix.substring(lastNewline);
            }
            return "\n";
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecl, ExecutionContext ctx) {
            J.VariableDeclarations vd = super.visitVariableDeclarations(varDecl, ctx);

            // Skip event-related types (handled by MarkCdiQualifiersForReview)
            // - ApplicationEventPublisher (Spring)
            // - Event<T> (CDI) - will be converted to ApplicationEventPublisher by MigrateCdiEventsToSpring
            if (isEventType(vd)) {
                return vd;
            }

            // Skip @Observes parameters (CDI event observers - handled by MarkCdiQualifiersForReview)
            if (hasObservesAnnotation(vd.getLeadingAnnotations())) {
                return vd;
            }

            // Find all custom CDI qualifier annotations
            List<QualifierInfo> qualifiers = findAllCustomCdiQualifiers(vd.getLeadingAnnotations());
            if (qualifiers.isEmpty()) {
                return vd;
            }

            // Skip if already has Spring @Qualifier
            if (hasSpringQualifier(vd.getLeadingAnnotations())) {
                return vd;
            }

            // Multiple qualifiers need @NeedsReview (but cannot be converted)
            if (qualifiers.size() > 1) {
                if (!hasNeedsReviewAnnotation(vd.getLeadingAnnotations())) {
                    return markFieldForReview(vd, qualifiers,
                        "Multiple CDI qualifiers on injection point. Spring @Qualifier cannot represent " +
                        "qualifier intersection. Consider explicit bean naming or custom Spring qualifier.");
                }
                return vd; // Already marked, cannot convert multiple qualifiers
            }

            QualifierInfo qualifierInfo = qualifiers.get(0);

            // Qualifier with attributes needs @NeedsReview (but cannot be converted)
            if (qualifierInfo.hasAttributes) {
                if (!hasNeedsReviewAnnotation(vd.getLeadingAnnotations())) {
                    return markFieldForReview(vd, qualifiers,
                        "CDI Qualifier @" + qualifierInfo.simpleName + " has attributes that cannot be " +
                        "preserved in Spring @Qualifier. Original: " + qualifierInfo.originalAnnotationText + ". " +
                        "Consider custom Spring qualifier annotation or explicit bean naming.");
                }
                return vd; // Already marked, cannot convert attributed qualifier
            }

            // Convert CDI qualifier to Spring @Qualifier
            return convertToSpringQualifier(vd, qualifierInfo);
        }

        private J.VariableDeclarations markFieldForReview(J.VariableDeclarations vd, List<QualifierInfo> qualifiers, String reason) {
            maybeAddImport(NEEDS_REVIEW_FQN);

            // Build @NeedsReview annotation
            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                vd.getLeadingAnnotations().isEmpty() ? vd.getPrefix() : vd.getLeadingAnnotations().get(0).getPrefix(),
                reason
            );

            // Add @NeedsReview at the beginning
            List<J.Annotation> newAnnotations = new ArrayList<>();
            newAnnotations.add(needsReviewAnn);

            String annotationPrefix = calculateAnnotationPrefix(vd);
            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                newAnnotations.add(ann.withPrefix(Space.format(annotationPrefix)));
            }

            return vd.withLeadingAnnotations(newAnnotations);
        }

        private String calculateAnnotationPrefix(J.VariableDeclarations vd) {
            if (!vd.getLeadingAnnotations().isEmpty()) {
                String prefix = vd.getLeadingAnnotations().get(0).getPrefix().getWhitespace();
                if (prefix.contains("\n")) {
                    return prefix;
                }
            }
            String fieldPrefix = vd.getPrefix().getWhitespace();
            if (fieldPrefix.contains("\n")) {
                int lastNewline = fieldPrefix.lastIndexOf('\n');
                return fieldPrefix.substring(lastNewline);
            }
            return "\n    ";
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            // Handle producer methods (@Produces) - they define bean side of qualifier contract
            if (hasProducesAnnotation(md.getLeadingAnnotations())) {
                md = handleProducerMethodQualifiers(md);
            }

            // Process parameters
            if (md.getParameters() != null) {
                List<Statement> newParams = new ArrayList<>();
                boolean changed = false;
                boolean needsMethodReview = false;
                String reviewReason = null;

                for (Statement param : md.getParameters()) {
                    if (param instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) param;

                        // Skip event types and @Observes parameters (handled by MarkCdiQualifiersForReview)
                        if (!isEventType(vd) && !hasObservesAnnotation(vd.getLeadingAnnotations())) {
                            List<QualifierInfo> qualifiers = findAllCustomCdiQualifiers(vd.getLeadingAnnotations());
                            if (!qualifiers.isEmpty() && !hasSpringQualifier(vd.getLeadingAnnotations())) {
                                // Multiple qualifiers need @NeedsReview on method
                                if (qualifiers.size() > 1) {
                                    needsMethodReview = true;
                                    reviewReason = "Multiple CDI qualifiers on parameter. Spring @Qualifier cannot " +
                                        "represent qualifier intersection. Consider explicit bean naming.";
                                } else {
                                    QualifierInfo qualifierInfo = qualifiers.get(0);
                                    // Qualifier with attributes needs @NeedsReview on method
                                    if (qualifierInfo.hasAttributes) {
                                        needsMethodReview = true;
                                        reviewReason = "CDI Qualifier @" + qualifierInfo.simpleName + " has attributes " +
                                            "that cannot be preserved in Spring @Qualifier. Original: " +
                                            qualifierInfo.originalAnnotationText + ".";
                                    } else {
                                        vd = convertToSpringQualifier(vd, qualifierInfo);
                                        changed = true;
                                    }
                                }
                            }
                        }
                        newParams.add(vd);
                    } else {
                        newParams.add(param);
                    }
                }

                if (changed) {
                    md = md.withParameters(newParams);
                }

                // Add @NeedsReview to method if parameters have issues
                if (needsMethodReview && !hasNeedsReviewAnnotation(md.getLeadingAnnotations())) {
                    md = markMethodForReview(md, reviewReason);
                }
            }

            return md;
        }

        private J.MethodDeclaration markMethodForReview(J.MethodDeclaration md, String reason) {
            maybeAddImport(NEEDS_REVIEW_FQN);

            // Build @NeedsReview annotation
            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                md.getLeadingAnnotations().isEmpty() ? md.getPrefix() : md.getLeadingAnnotations().get(0).getPrefix(),
                reason
            );

            // Add @NeedsReview at the beginning
            List<J.Annotation> newAnnotations = new ArrayList<>();
            newAnnotations.add(needsReviewAnn);

            String annotationPrefix = calculateMethodAnnotationPrefix(md);
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                newAnnotations.add(ann.withPrefix(Space.format(annotationPrefix)));
            }

            return md.withLeadingAnnotations(newAnnotations);
        }

        private String calculateMethodAnnotationPrefix(J.MethodDeclaration md) {
            if (!md.getLeadingAnnotations().isEmpty()) {
                String prefix = md.getLeadingAnnotations().get(0).getPrefix().getWhitespace();
                if (prefix.contains("\n")) {
                    return prefix;
                }
            }
            String methodPrefix = md.getPrefix().getWhitespace();
            if (methodPrefix.contains("\n")) {
                int lastNewline = methodPrefix.lastIndexOf('\n');
                return methodPrefix.substring(lastNewline);
            }
            return "\n    ";
        }

        private boolean hasNeedsReviewAnnotation(List<J.Annotation> annotations) {
            for (J.Annotation ann : annotations) {
                if ("NeedsReview".equals(ann.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Checks if method has @Produces annotation (CDI producer method).
         */
        private boolean hasProducesAnnotation(List<J.Annotation> annotations) {
            for (J.Annotation ann : annotations) {
                if ("Produces".equals(ann.getSimpleName())) {
                    return true;
                }
                if (TypeUtils.isOfClassType(ann.getType(), CDI_PRODUCES_JAKARTA) ||
                    TypeUtils.isOfClassType(ann.getType(), CDI_PRODUCES_JAVAX)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Handles CDI qualifiers on producer methods.
         * Producer methods define the bean side of the qualifier contract.
         */
        private J.MethodDeclaration handleProducerMethodQualifiers(J.MethodDeclaration md) {
            // Find CDI qualifiers on method annotations
            List<QualifierInfo> qualifiers = findAllCustomCdiQualifiers(md.getLeadingAnnotations());
            if (qualifiers.isEmpty()) {
                return md;
            }

            // Skip if already has Spring @Qualifier
            if (hasSpringQualifier(md.getLeadingAnnotations())) {
                return md;
            }

            // Multiple qualifiers need @NeedsReview (but cannot be converted)
            if (qualifiers.size() > 1) {
                if (!hasNeedsReviewAnnotation(md.getLeadingAnnotations())) {
                    return markMethodForReview(md,
                        "Multiple CDI qualifiers on producer method. Spring @Qualifier cannot represent " +
                        "qualifier intersection. Consider explicit bean names or custom Spring qualifiers.");
                }
                return md;
            }

            QualifierInfo qualifierInfo = qualifiers.get(0);

            // Qualifier with attributes needs @NeedsReview (but cannot be converted)
            if (qualifierInfo.hasAttributes) {
                if (!hasNeedsReviewAnnotation(md.getLeadingAnnotations())) {
                    return markMethodForReview(md,
                        "CDI Qualifier @" + qualifierInfo.simpleName + " has attributes that cannot be " +
                        "preserved in Spring @Qualifier. Original: " + qualifierInfo.originalAnnotationText + ". " +
                        "Consider custom Spring qualifier annotation or explicit bean naming.");
                }
                return md;
            }

            // Convert CDI qualifier to Spring @Qualifier on producer method
            return convertMethodToSpringQualifier(md, qualifierInfo);
        }

        /**
         * Converts a CDI qualifier on a method to Spring @Qualifier.
         */
        private J.MethodDeclaration convertMethodToSpringQualifier(J.MethodDeclaration md, QualifierInfo qualifierInfo) {
            maybeAddImport(SPRING_QUALIFIER);

            // Track the qualifier for import removal
            if (qualifierInfo.fqn != null) {
                qualifiersToRemove.add(qualifierInfo.fqn);
            }

            // Build new annotation list
            List<J.Annotation> newAnnotations = new ArrayList<>();

            for (int i = 0; i < md.getLeadingAnnotations().size(); i++) {
                J.Annotation ann = md.getLeadingAnnotations().get(i);

                if (i == qualifierInfo.annotationIndex) {
                    // Replace CDI qualifier with Spring @Qualifier
                    String qualifierValue = toQualifierValue(qualifierInfo.simpleName);
                    J.Annotation springQualifier = createSpringQualifierAnnotation(qualifierValue, ann.getPrefix());
                    newAnnotations.add(springQualifier);
                } else {
                    newAnnotations.add(ann);
                }
            }

            return md.withLeadingAnnotations(newAnnotations);
        }

        private J.VariableDeclarations convertToSpringQualifier(J.VariableDeclarations vd, QualifierInfo qualifierInfo) {
            maybeAddImport(SPRING_QUALIFIER);

            // Track the qualifier for import removal
            if (qualifierInfo.fqn != null) {
                qualifiersToRemove.add(qualifierInfo.fqn);
            }

            // Build new annotation list
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean qualifierAdded = false;

            for (int i = 0; i < vd.getLeadingAnnotations().size(); i++) {
                J.Annotation ann = vd.getLeadingAnnotations().get(i);

                if (i == qualifierInfo.annotationIndex) {
                    // Replace CDI qualifier with Spring @Qualifier
                    String qualifierValue = toQualifierValue(qualifierInfo.simpleName);
                    J.Annotation springQualifier = createSpringQualifierAnnotation(qualifierValue, ann.getPrefix());
                    newAnnotations.add(springQualifier);
                    qualifierAdded = true;
                } else {
                    newAnnotations.add(ann);
                }
            }

            return vd.withLeadingAnnotations(newAnnotations);
        }

        /**
         * Converts an annotation simple name to a qualifier value.
         * Uses lowercase first letter (MyQualifier -> "myQualifier").
         */
        private String toQualifierValue(String simpleName) {
            if (simpleName == null || simpleName.isEmpty()) {
                return "";
            }
            return Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
        }

        private J.Annotation createSpringQualifierAnnotation(String value, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(SPRING_QUALIFIER);

            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Qualifier",
                type,
                null
            );

            J.Literal literal = new J.Literal(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                value,
                "\"" + value + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            List<JRightPadded<Expression>> argList = new ArrayList<>();
            argList.add(new JRightPadded<>(literal, Space.EMPTY, Markers.EMPTY));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                argList,
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

        private J.Annotation createNeedsReviewAnnotation(Space prefix, String reason) {
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

            // category = NeedsReview.Category.MANUAL_MIGRATION
            arguments.add(createCategoryArg("MANUAL_MIGRATION"));

            // reason = "..."
            arguments.add(createStringAssignmentArg("reason", reason, true));

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

        private JRightPadded<Expression> createStringAssignmentArg(String key, String value, boolean leadingSpace) {
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
                "\"" + value.replace("\"", "\\\"") + "\"",
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
                Space.EMPTY,
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

        /**
         * Checks if a variable declaration is an event-related type that should be skipped.
         * Includes:
         * - Spring ApplicationEventPublisher
         * - CDI Event<T> (jakarta and javax namespaces)
         */
        private boolean isEventType(J.VariableDeclarations varDecl) {
            TypeTree typeExpr = varDecl.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }

            // Check by simple name for quick path
            String simpleName = null;
            if (typeExpr instanceof J.Identifier) {
                simpleName = ((J.Identifier) typeExpr).getSimpleName();
            } else if (typeExpr instanceof J.ParameterizedType) {
                // For Event<T>, get the base type name
                J.ParameterizedType pt = (J.ParameterizedType) typeExpr;
                if (pt.getClazz() instanceof J.Identifier) {
                    simpleName = ((J.Identifier) pt.getClazz()).getSimpleName();
                }
            }

            if (simpleName != null) {
                if ("ApplicationEventPublisher".equals(simpleName) || "Event".equals(simpleName)) {
                    return true;
                }
            }

            // Check by type for more precise matching
            JavaType type = typeExpr.getType();
            if (type != null) {
                if (TypeUtils.isAssignableTo(APP_EVENT_PUBLISHER, type)) {
                    return true;
                }
                if (TypeUtils.isAssignableTo(CDI_EVENT_JAKARTA, type) ||
                    TypeUtils.isAssignableTo(CDI_EVENT_JAVAX, type)) {
                    return true;
                }
            }

            return false;
        }

        private boolean hasSpringQualifier(List<J.Annotation> annotations) {
            for (J.Annotation ann : annotations) {
                if ("Qualifier".equals(ann.getSimpleName())) {
                    // Verify it's Spring's Qualifier
                    if (TypeUtils.isOfClassType(ann.getType(), SPRING_QUALIFIER)) {
                        return true;
                    }
                    // Fallback for incomplete type info
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(ann.getType());
                    if (fq != null && fq.getFullyQualifiedName().startsWith("org.springframework")) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Checks if annotations include @Observes (CDI event observer pattern).
         * Parameters with @Observes are event observers, not bean injection points.
         */
        private boolean hasObservesAnnotation(List<J.Annotation> annotations) {
            for (J.Annotation ann : annotations) {
                if ("Observes".equals(ann.getSimpleName())) {
                    return true;
                }
                if (TypeUtils.isOfClassType(ann.getType(), CDI_OBSERVES_JAKARTA) ||
                    TypeUtils.isOfClassType(ann.getType(), CDI_OBSERVES_JAVAX)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Finds all custom CDI qualifier annotations.
         * A custom CDI qualifier is meta-annotated with @jakarta.inject.Qualifier (or javax).
         */
        private List<QualifierInfo> findAllCustomCdiQualifiers(List<J.Annotation> annotations) {
            List<QualifierInfo> qualifiers = new ArrayList<>();

            for (int i = 0; i < annotations.size(); i++) {
                J.Annotation ann = annotations.get(i);
                String simpleName = ann.getSimpleName();

                // Skip known non-qualifiers
                if (KNOWN_NON_QUALIFIERS.contains(simpleName)) {
                    continue;
                }

                JavaType.FullyQualified annType = TypeUtils.asFullyQualified(ann.getType());
                if (annType != null) {
                    // Skip @Named (handled by MigrateNamedToComponent)
                    String fqn = annType.getFullyQualifiedName();
                    if (CDI_NAMED_JAKARTA.equals(fqn) || CDI_NAMED_JAVAX.equals(fqn)) {
                        continue;
                    }

                    // Check if meta-annotated with @Qualifier
                    if (isMetaAnnotatedWithQualifier(annType)) {
                        QualifierInfo info = new QualifierInfo();
                        info.annotation = ann;
                        info.annotationIndex = i;
                        info.simpleName = simpleName;
                        info.fqn = fqn;
                        info.hasAttributes = hasAnnotationArguments(ann);
                        info.originalAnnotationText = getAnnotationText(ann);
                        qualifiers.add(info);
                    }
                }
            }
            return qualifiers;
        }

        /**
         * Checks if an annotation has arguments (attributes).
         */
        private boolean hasAnnotationArguments(J.Annotation ann) {
            if (ann.getArguments() == null || ann.getArguments().isEmpty()) {
                return false;
            }
            // Check if there are actual arguments (not just empty parens)
            for (Expression arg : ann.getArguments()) {
                if (arg instanceof J.Empty) {
                    continue;
                }
                return true;
            }
            return false;
        }

        /**
         * Gets a string representation of the annotation for @NeedsReview messages.
         */
        private String getAnnotationText(J.Annotation ann) {
            StringBuilder sb = new StringBuilder("@");
            sb.append(ann.getSimpleName());
            if (ann.getArguments() != null && !ann.getArguments().isEmpty()) {
                sb.append("(");
                boolean first = true;
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Empty) {
                        continue;
                    }
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(arg.print(getCursor()));
                }
                sb.append(")");
            }
            return sb.toString();
        }

        /**
         * Checks if an annotation type is meta-annotated with @Qualifier.
         */
        private boolean isMetaAnnotatedWithQualifier(JavaType.FullyQualified annType) {
            List<JavaType.FullyQualified> metaAnnotations = annType.getAnnotations();
            if (metaAnnotations != null) {
                for (JavaType.FullyQualified metaAnn : metaAnnotations) {
                    String metaFqn = metaAnn.getFullyQualifiedName();
                    if (CDI_QUALIFIER_JAKARTA.equals(metaFqn) || CDI_QUALIFIER_JAVAX.equals(metaFqn)) {
                        return true;
                    }
                }
            }
            return false;
        }
    }

    private static class QualifierInfo {
        J.Annotation annotation;
        int annotationIndex;
        String simpleName;
        String fqn;
        boolean hasAttributes;
        String originalAnnotationText;
    }
}
