package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

/**
 * Marks CDI Qualifier usage on Spring event-related types with @NeedsReview.
 * <p>
 * CDI Qualifiers on Event injection and @Observes parameters don't translate to Spring's
 * event system. Spring's ApplicationEventPublisher and @EventListener receive ALL events
 * of a matching type, regardless of any qualifier annotation.
 * <p>
 * This recipe identifies fields/parameters with custom annotations (potential qualifiers)
 * on ApplicationEventPublisher types and marks them for manual review.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MarkCdiQualifiersForReview extends Recipe {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Mark CDI Qualifier usage for manual review";
    }

    @Override
    public String getDescription() {
        return "CDI Qualifiers don't work with Spring events. Marks ApplicationEventPublisher " +
               "fields with custom annotations using @NeedsReview.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecl, ExecutionContext ctx) {
                J.VariableDeclarations v = super.visitVariableDeclarations(varDecl, ctx);

                // Only process if this is an ApplicationEventPublisher field
                if (!isApplicationEventPublisher(v)) {
                    return v;
                }

                // Check for custom qualifier annotation (non-standard annotations)
                Optional<J.Annotation> qualifierOpt = findCustomQualifierAnnotation(v.getLeadingAnnotations());
                if (qualifierOpt.isEmpty()) {
                    return v;
                }

                // Already has @NeedsReview?
                if (hasNeedsReviewAnnotation(v.getLeadingAnnotations())) {
                    return v;
                }

                String qualifierName = qualifierOpt.get().getSimpleName();

                // Add import via doAfterVisit (more reliable than template.imports)
                doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN, null, false));

                // Create @NeedsReview annotation programmatically with enhanced guidance
                String reason = "CDI Qualifier @" + qualifierName + " is ignored by Spring ApplicationEventPublisher. " +
                    "Recommended: Create wrapper event class (e.g., " + qualifierName + "Event extends BaseEvent) " +
                    "and use publishEvent(new " + qualifierName + "Event(payload)). " +
                    "Alternative: Add type field to event and filter with @EventListener(condition=\"#event.type == '" +
                    qualifierName.toLowerCase() + "'\").";
                J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                    v.getLeadingAnnotations().isEmpty() ? v.getPrefix() : v.getLeadingAnnotations().get(0).getPrefix(),
                    reason
                );

                // Build new annotation list with proper line formatting
                // Calculate proper prefix: newline + indentation
                String annotationPrefix = calculateAnnotationPrefix(v);

                List<J.Annotation> newAnnotations = new ArrayList<>();
                newAnnotations.add(needsReviewAnn);

                for (int i = 0; i < v.getLeadingAnnotations().size(); i++) {
                    J.Annotation ann = v.getLeadingAnnotations().get(i);
                    // Each annotation on its own line
                    ann = ann.withPrefix(Space.format(annotationPrefix));
                    newAnnotations.add(ann);
                }

                v = v.withLeadingAnnotations(newAnnotations);

                // Also add newline before modifiers (so the field declaration starts on new line)
                // This ensures "private ApplicationEventPublisher" is on its own line after annotations
                if (!v.getModifiers().isEmpty()) {
                    List<J.Modifier> newModifiers = new ArrayList<>();
                    for (int i = 0; i < v.getModifiers().size(); i++) {
                        J.Modifier mod = v.getModifiers().get(i);
                        if (i == 0) {
                            // First modifier gets newline + indentation
                            mod = mod.withPrefix(Space.format(annotationPrefix));
                        }
                        newModifiers.add(mod);
                    }
                    v = v.withModifiers(newModifiers);
                } else if (v.getTypeExpression() != null) {
                    // No modifiers, add newline before type expression
                    v = v.withTypeExpression(v.getTypeExpression().withPrefix(Space.format(annotationPrefix)));
                }

                return v;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);

                // Check if this is an @EventListener method
                if (!hasAnnotation(m.getLeadingAnnotations(), "EventListener")) {
                    return m;
                }

                // Check if any parameter has a custom qualifier
                for (Statement param : m.getParameters()) {
                    if (param instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) param;
                        Optional<J.Annotation> qualifierOpt = findCustomQualifierAnnotation(vd.getLeadingAnnotations());
                        if (qualifierOpt.isPresent()) {
                            // Already has @NeedsReview on method?
                            if (hasNeedsReviewAnnotation(m.getLeadingAnnotations())) {
                                return m;
                            }

                            String qualifierName = qualifierOpt.get().getSimpleName();

                            // Add import via doAfterVisit
                            doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN, null, false));

                            // Create @NeedsReview annotation programmatically with enhanced guidance
                            String reason = "CDI Qualifier @" + qualifierName + " on @EventListener is ignored. Spring receives ALL events of this type. " +
                                "Recommended: Create wrapper event class (e.g., " + qualifierName + "Event) and change parameter type. " +
                                "Alternative: Add @EventListener(condition=\"#event.type == '" + qualifierName.toLowerCase() + "'\") if event has type field.";
                            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                                m.getLeadingAnnotations().isEmpty() ? m.getPrefix() : m.getLeadingAnnotations().get(0).getPrefix(),
                                reason
                            );

                            // Build new annotation list with proper line formatting
                            String annotationPrefix = calculateAnnotationPrefix(m);

                            List<J.Annotation> newAnnotations = new ArrayList<>();
                            newAnnotations.add(needsReviewAnn);

                            for (int i = 0; i < m.getLeadingAnnotations().size(); i++) {
                                J.Annotation ann = m.getLeadingAnnotations().get(i);
                                ann = ann.withPrefix(Space.format(annotationPrefix));
                                newAnnotations.add(ann);
                            }

                            m = m.withLeadingAnnotations(newAnnotations);

                            // Also add newline before modifiers (so the method signature starts on new line)
                            if (!m.getModifiers().isEmpty()) {
                                List<J.Modifier> newModifiers = new ArrayList<>();
                                for (int i = 0; i < m.getModifiers().size(); i++) {
                                    J.Modifier mod = m.getModifiers().get(i);
                                    if (i == 0) {
                                        mod = mod.withPrefix(Space.format(annotationPrefix));
                                    }
                                    newModifiers.add(mod);
                                }
                                m = m.withModifiers(newModifiers);
                            }

                            return m;
                        }
                    }
                }

                return m;
            }

            /**
             * Calculate annotation prefix (newline + indentation) from variable declaration.
             * Ensures each annotation starts on a new line with proper indentation.
             */
            private String calculateAnnotationPrefix(J.VariableDeclarations v) {
                // First, try to find existing newline + indentation
                String firstAnnotationPrefix = "";
                if (!v.getLeadingAnnotations().isEmpty()) {
                    firstAnnotationPrefix = v.getLeadingAnnotations().get(0).getPrefix().getWhitespace();
                    if (firstAnnotationPrefix.contains("\n")) {
                        return firstAnnotationPrefix;
                    }
                }

                // Check field's prefix for newline + indentation
                String fieldPrefix = v.getPrefix().getWhitespace();
                if (fieldPrefix.contains("\n")) {
                    int lastNewline = fieldPrefix.lastIndexOf('\n');
                    return fieldPrefix.substring(lastNewline);
                }

                // If first annotation has indentation but no newline, add newline
                if (!firstAnnotationPrefix.isEmpty() && firstAnnotationPrefix.startsWith(" ")) {
                    return "\n" + firstAnnotationPrefix;
                }

                // Default: newline + 4 spaces (typical field indentation)
                return "\n    ";
            }

            /**
             * Calculate annotation prefix (newline + indentation) from method declaration.
             */
            private String calculateAnnotationPrefix(J.MethodDeclaration m) {
                // Check first annotation's prefix
                String firstAnnotationPrefix = "";
                if (!m.getLeadingAnnotations().isEmpty()) {
                    firstAnnotationPrefix = m.getLeadingAnnotations().get(0).getPrefix().getWhitespace();
                    if (firstAnnotationPrefix.contains("\n")) {
                        return firstAnnotationPrefix;
                    }
                }

                // Check method's own prefix
                String methodPrefix = m.getPrefix().getWhitespace();
                if (methodPrefix.contains("\n")) {
                    int lastNewline = methodPrefix.lastIndexOf('\n');
                    return methodPrefix.substring(lastNewline);
                }

                // If first annotation has indentation but no newline, add newline
                if (!firstAnnotationPrefix.isEmpty() && firstAnnotationPrefix.startsWith(" ")) {
                    return "\n" + firstAnnotationPrefix;
                }

                // Default: newline + 4 spaces
                return "\n    ";
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

            private boolean isApplicationEventPublisher(J.VariableDeclarations varDecl) {
                TypeTree typeExpr = varDecl.getTypeExpression();
                if (typeExpr == null) {
                    return false;
                }

                // Check by simple name
                if (typeExpr instanceof J.Identifier) {
                    String name = ((J.Identifier) typeExpr).getSimpleName();
                    return "ApplicationEventPublisher".equals(name);
                }

                // Check by type
                JavaType type = typeExpr.getType();
                if (type != null) {
                    String typeName = type.toString();
                    return typeName.contains("ApplicationEventPublisher");
                }

                return false;
            }

            /**
             * Finds annotations that are CDI qualifiers using meta-annotation detection.
             * <p>
             * A CDI qualifier is an annotation that is itself annotated with
             * {@code @jakarta.inject.Qualifier} or {@code @javax.inject.Qualifier}.
             * This is the robust detection method recommended by the CDI spec.
             * <p>
             * Fallback: For cases where type information is incomplete, also checks
             * if the annotation is in a package containing "cdi", "event", or "qualifier".
             */
            private Optional<J.Annotation> findCustomQualifierAnnotation(List<J.Annotation> annotations) {
                // Standard annotations that we know are NOT qualifiers (even if they might
                // be in a package that matches our fallback heuristics)
                Set<String> knownNonQualifiers = Set.of(
                    // Spring injection
                    "Autowired", "Value",
                    // Jakarta/Java
                    "Override", "Deprecated", "SuppressWarnings", "FunctionalInterface",
                    "PostConstruct", "PreDestroy",
                    // JPA/Transactions
                    "Transactional", "PersistenceContext", "Entity", "Table",
                    // Our annotations
                    "NeedsReview",
                    // Event related (Spring)
                    "EventListener", "TransactionalEventListener", "Async",
                    // Bean Validation (common false positives)
                    "NotNull", "NotEmpty", "NotBlank", "Valid", "Size", "Min", "Max",
                    "Pattern", "Email", "Digits", "Future", "Past", "Positive", "Negative",
                    // Hibernate/JPA
                    "Audited", "Column", "Id", "GeneratedValue", "OneToMany", "ManyToOne",
                    // Spring stereotypes
                    "Bean", "Component", "Service", "Repository", "Controller", "RestController",
                    "Configuration", "Primary", "Lazy", "Scope", "Profile"
                );

                for (J.Annotation ann : annotations) {
                    String simpleName = ann.getSimpleName();

                    // Skip known non-qualifiers
                    if (knownNonQualifiers.contains(simpleName)) {
                        continue;
                    }

                    JavaType.FullyQualified annType = TypeUtils.asFullyQualified(ann.getType());
                    if (annType != null) {
                        // Primary check: Look for @Qualifier meta-annotation
                        if (isMetaAnnotatedWithQualifier(annType)) {
                            return Optional.of(ann);
                        }

                        // Secondary check: @Named is a standard CDI qualifier
                        // (it IS meta-annotated with @Qualifier, but including explicitly)
                        String fqn = annType.getFullyQualifiedName();
                        if ("jakarta.inject.Named".equals(fqn) || "javax.inject.Named".equals(fqn)) {
                            return Optional.of(ann);
                        }

                        // Fallback: Package-based heuristic for incomplete type info
                        // Only use if in clearly CDI-related package
                        String pkg = annType.getPackageName();
                        if (pkg.contains(".cdi.") || pkg.endsWith(".cdi") ||
                            pkg.contains(".qualifier.") || pkg.endsWith(".qualifier")) {
                            return Optional.of(ann);
                        }
                    }
                }
                return Optional.empty();
            }

            /**
             * Checks if an annotation type is meta-annotated with @jakarta.inject.Qualifier
             * or @javax.inject.Qualifier.
             */
            private boolean isMetaAnnotatedWithQualifier(JavaType.FullyQualified annType) {
                // Check the annotations on the annotation type itself
                List<JavaType.FullyQualified> metaAnnotations = annType.getAnnotations();
                if (metaAnnotations != null) {
                    for (JavaType.FullyQualified metaAnn : metaAnnotations) {
                        String metaFqn = metaAnn.getFullyQualifiedName();
                        if ("jakarta.inject.Qualifier".equals(metaFqn) ||
                            "javax.inject.Qualifier".equals(metaFqn)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            private boolean hasAnnotation(List<J.Annotation> annotations, String name) {
                return annotations.stream().anyMatch(a -> name.equals(a.getSimpleName()));
            }

            private boolean hasNeedsReviewAnnotation(List<J.Annotation> annotations) {
                return hasAnnotation(annotations, "NeedsReview");
            }
        };
    }
}
