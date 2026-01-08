package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * JUS-005: Removes {@code @Interceptors} annotations and marks affected classes/methods
 * with {@code @NeedsReview} for manual Spring AOP migration.
 * <p>
 * Pattern:
 * <pre>
 * // Input
 * &#64;Interceptors({EntryPointProtokollInterceptor.class, LaufzeitProtokollInterceptor.class})
 * &#64;Stateless
 * public class MyFassade { }
 *
 * // Output
 * &#64;NeedsReview(reason = "EJB Interceptors removed: EntryPointProtokollInterceptor, LaufzeitProtokollInterceptor - migrate to Spring AOP",
 *              category = NeedsReview.Category.OTHER)
 * &#64;Stateless
 * public class MyFassade { }
 * </pre>
 * <p>
 * This recipe:
 * <ul>
 *   <li>Removes {@code @jakarta.interceptor.Interceptors} and {@code @javax.interceptor.Interceptors}</li>
 *   <li>Handles both class-level and method-level annotations</li>
 *   <li>Adds {@code @NeedsReview} to the same element where {@code @Interceptors} was removed</li>
 *   <li>Includes interceptor names (FQN when available) in the review reason</li>
 *   <li>Is idempotent: won't add duplicate {@code @NeedsReview} annotations</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveInterceptorsWithAopMarker extends ScanningRecipe<RemoveInterceptorsWithAopMarker.InterceptorTracker> {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final String JAKARTA_INTERCEPTORS_FQN = "jakarta.interceptor.Interceptors";
    private static final String JAVAX_INTERCEPTORS_FQN = "javax.interceptor.Interceptors";

    @Override
    public String getDisplayName() {
        return "Remove EJB @Interceptors and add AOP migration marker";
    }

    @Override
    public String getDescription() {
        return "Removes @Interceptors annotations from classes and methods, adding @NeedsReview " +
               "marker annotations to guide manual migration to Spring AOP aspects. " +
               "The interceptor names are included in the review reason for reference.";
    }

    /**
     * Tracks which classes/methods had @Interceptors and which interceptors were used.
     */
    static class InterceptorTracker {
        // Key: FQN of class or "FQN#methodName" for methods
        // Value: Set of interceptor names (FQN when available, simple name otherwise)
        final Map<String, Set<String>> interceptorsByLocation = new ConcurrentHashMap<>();
    }

    @Override
    public InterceptorTracker getInitialValue(ExecutionContext ctx) {
        return new InterceptorTracker();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(InterceptorTracker acc) {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                if (isInterceptorsAnnotation(annotation, getCursor())) {
                    String locationKey = getLocationKey();
                    if (locationKey != null) {
                        Set<String> interceptorNames = extractInterceptorNames(annotation);
                        acc.interceptorsByLocation.computeIfAbsent(locationKey, k -> new LinkedHashSet<>())
                            .addAll(interceptorNames);
                    }
                }
                return super.visitAnnotation(annotation, ctx);
            }

            private String getLocationKey() {
                // Check if we're on a method
                J.MethodDeclaration method = getCursor().firstEnclosing(J.MethodDeclaration.class);
                J.ClassDeclaration classDecl = getCursor().firstEnclosing(J.ClassDeclaration.class);

                if (classDecl == null) {
                    return null;
                }

                JavaType.FullyQualified classType = TypeUtils.asFullyQualified(classDecl.getType());
                String className = classType != null ? classType.getFullyQualifiedName() : classDecl.getSimpleName();

                if (method != null) {
                    // Method-level annotation - include signature to handle overloads
                    return className + "#" + buildMethodSignature(method);
                }
                // Class-level annotation
                return className;
            }

            private String buildMethodSignature(J.MethodDeclaration method) {
                String params;
                if (method.getMethodType() != null && method.getMethodType().getParameterTypes() != null) {
                    // Preferred: use fully qualified type names from type attribution
                    params = method.getMethodType().getParameterTypes().stream()
                        .map(t -> {
                            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
                            return fq != null ? fq.getFullyQualifiedName() : t.toString();
                        })
                        .collect(Collectors.joining(","));
                } else {
                    // Fallback: use parameter type expressions from source syntax (MEDIUM fix)
                    params = method.getParameters().stream()
                        .map(p -> {
                            if (p instanceof J.VariableDeclarations) {
                                TypeTree typeExpr = ((J.VariableDeclarations) p).getTypeExpression();
                                return typeExpr != null ? typeExpr.toString() : "?";
                            }
                            return "?";
                        })
                        .collect(Collectors.joining(","));
                }
                return method.getSimpleName() + "(" + params + ")";
            }

            private Set<String> extractInterceptorNames(J.Annotation annotation) {
                Set<String> names = new LinkedHashSet<>();
                if (annotation.getArguments() == null) {
                    return names;
                }

                for (Expression arg : annotation.getArguments()) {
                    extractNamesFromExpression(arg, names);
                }
                return names;
            }

            private void extractNamesFromExpression(Expression expr, Set<String> names) {
                if (expr instanceof J.NewArray) {
                    // @Interceptors({A.class, B.class})
                    J.NewArray newArray = (J.NewArray) expr;
                    if (newArray.getInitializer() != null) {
                        for (Expression elem : newArray.getInitializer()) {
                            extractNamesFromExpression(elem, names);
                        }
                    }
                } else if (expr instanceof J.FieldAccess) {
                    // FooInterceptor.class
                    J.FieldAccess fa = (J.FieldAccess) expr;
                    if ("class".equals(fa.getSimpleName())) {
                        Expression target = fa.getTarget();
                        String name = extractTypeName(target);
                        if (name != null) {
                            names.add(name);
                        }
                    }
                } else if (expr instanceof J.Assignment) {
                    // value = {A.class, B.class}
                    J.Assignment assign = (J.Assignment) expr;
                    extractNamesFromExpression(assign.getAssignment(), names);
                }
            }

            private String extractTypeName(Expression expr) {
                // Try to get FQN via type attribution
                if (expr.getType() != null) {
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(expr.getType());
                    if (fq != null) {
                        return fq.getFullyQualifiedName();
                    }
                }
                // Fallback to source text
                if (expr instanceof J.Identifier) {
                    return ((J.Identifier) expr).getSimpleName() + " (unresolved)";
                } else if (expr instanceof J.FieldAccess) {
                    // Could be package.ClassName
                    return expr.toString().replace(".class", "");
                }
                return expr.toString();
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(InterceptorTracker tracker) {
        return new InterceptorsVisitor(tracker);
    }

    private class InterceptorsVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final InterceptorTracker tracker;

        InterceptorsVisitor(InterceptorTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);

            if (isInterceptorsAnnotation(a, getCursor())) {
                maybeRemoveImport(JAKARTA_INTERCEPTORS_FQN);
                maybeRemoveImport(JAVAX_INTERCEPTORS_FQN);
                //noinspection DataFlowIssue
                return null; // Remove the annotation
            }

            return a;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // First, check BEFORE super call if this class has @Interceptors
            JavaType.FullyQualified classType = TypeUtils.asFullyQualified(classDecl.getType());
            String className = classType != null ? classType.getFullyQualifiedName() : classDecl.getSimpleName();
            Set<String> interceptors = tracker.interceptorsByLocation.get(className);

            // Get prefix from original first annotation BEFORE removal
            Space originalFirstAnnotationPrefix = classDecl.getLeadingAnnotations().isEmpty()
                ? classDecl.getPrefix()
                : classDecl.getLeadingAnnotations().get(0).getPrefix();

            // Now call super - this will remove @Interceptors via visitAnnotation
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // If no interceptors tracked for this class, nothing to do
            if (interceptors == null) {
                return cd;
            }

            // Check idempotency: don't add @NeedsReview if already present
            boolean alreadyHasReview = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> "NeedsReview".equals(a.getSimpleName()));

            if (!alreadyHasReview) {
                maybeAddImport(NEEDS_REVIEW_FQN);

                String reason = buildReason(interceptors);
                J.Annotation needsReview = createNeedsReviewAnnotation(reason, originalFirstAnnotationPrefix);

                List<J.Annotation> newAnnotations = new ArrayList<>();
                newAnnotations.add(needsReview);

                // Add remaining annotations with adjusted prefixes
                for (int i = 0; i < cd.getLeadingAnnotations().size(); i++) {
                    J.Annotation ann = cd.getLeadingAnnotations().get(i);
                    if (i == 0) {
                        // First remaining annotation needs a newline prefix
                        String ws = originalFirstAnnotationPrefix.getWhitespace();
                        ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n"));
                    }
                    newAnnotations.add(ann);
                }

                cd = cd.withLeadingAnnotations(newAnnotations);
            }

            return cd;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.ClassDeclaration classDecl = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if (classDecl == null) {
                return super.visitMethodDeclaration(method, ctx);
            }

            JavaType.FullyQualified classType = TypeUtils.asFullyQualified(classDecl.getType());
            String className = classType != null ? classType.getFullyQualifiedName() : classDecl.getSimpleName();
            // Use signature-based key to handle overloaded methods (HIGH fix)
            String methodKey = className + "#" + buildMethodSignature(method);

            // Check BEFORE super call if this method has @Interceptors
            Set<String> interceptors = tracker.interceptorsByLocation.get(methodKey);

            // Get prefix from original first annotation BEFORE removal
            Space originalFirstAnnotationPrefix = method.getLeadingAnnotations().isEmpty()
                ? method.getPrefix()
                : method.getLeadingAnnotations().get(0).getPrefix();

            // Now call super - this will remove @Interceptors via visitAnnotation
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            // If no interceptors tracked for this method, nothing to do
            if (interceptors == null) {
                return md;
            }

            // Check idempotency
            boolean alreadyHasReview = md.getLeadingAnnotations().stream()
                .anyMatch(a -> "NeedsReview".equals(a.getSimpleName()));

            if (!alreadyHasReview) {
                maybeAddImport(NEEDS_REVIEW_FQN);

                String reason = buildReason(interceptors);
                J.Annotation needsReview = createNeedsReviewAnnotation(reason, originalFirstAnnotationPrefix);

                List<J.Annotation> newAnnotations = new ArrayList<>();
                newAnnotations.add(needsReview);

                // Add remaining annotations with adjusted prefixes
                for (int i = 0; i < md.getLeadingAnnotations().size(); i++) {
                    J.Annotation ann = md.getLeadingAnnotations().get(i);
                    if (i == 0) {
                        String ws = originalFirstAnnotationPrefix.getWhitespace();
                        ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n"));
                    }
                    newAnnotations.add(ann);
                }

                md = md.withLeadingAnnotations(newAnnotations);
            }

            return md;
        }

        private String buildMethodSignature(J.MethodDeclaration method) {
            String params;
            if (method.getMethodType() != null && method.getMethodType().getParameterTypes() != null) {
                // Preferred: use fully qualified type names from type attribution
                params = method.getMethodType().getParameterTypes().stream()
                    .map(t -> {
                        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
                        return fq != null ? fq.getFullyQualifiedName() : t.toString();
                    })
                    .collect(Collectors.joining(","));
            } else {
                // Fallback: use parameter type expressions from source syntax (MEDIUM fix)
                params = method.getParameters().stream()
                    .map(p -> {
                        if (p instanceof J.VariableDeclarations) {
                            TypeTree typeExpr = ((J.VariableDeclarations) p).getTypeExpression();
                            return typeExpr != null ? typeExpr.toString() : "?";
                        }
                        return "?";
                    })
                    .collect(Collectors.joining(","));
            }
            return method.getSimpleName() + "(" + params + ")";
        }

        private String buildReason(Set<String> interceptors) {
            // LOW fix: handle empty interceptor list
            if (interceptors == null || interceptors.isEmpty()) {
                return "EJB Interceptors removed (unresolved) - migrate to Spring AOP. Configure @Order on generated @Aspect classes to preserve execution sequence.";
            }
            String interceptorList = interceptors.stream()
                .map(this::simplifyName)
                .collect(Collectors.joining(", "));
            // MKR-001: Add guidance about aspect ordering
            String orderGuidance = interceptors.size() > 1
                ? ". Configure @Order on generated @Aspect classes to preserve execution sequence."
                : "";
            return "EJB Interceptors removed: " + interceptorList + " - migrate to Spring AOP" + orderGuidance;
        }

        private String simplifyName(String fqn) {
            // For display, use simple name but keep (unresolved) marker
            if (fqn.endsWith("(unresolved)")) {
                return fqn;
            }
            int lastDot = fqn.lastIndexOf('.');
            return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
        }

        private J.Annotation createNeedsReviewAnnotation(String reason, Space prefix) {
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
            arguments.add(createCategoryArg("OTHER"));

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
                "\"" + escapeString(value) + "\"",
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

        private String escapeString(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }

    /**
     * Checks if the annotation is @Interceptors (jakarta or javax).
     * Uses type attribution when available, falls back to name + import matching.
     *
     * @param annotation the annotation to check
     * @param cursor the cursor for accessing compilation unit and imports
     * @return true if this is a jakarta/javax @Interceptors annotation
     */
    private boolean isInterceptorsAnnotation(J.Annotation annotation, Cursor cursor) {
        // Type-based check (preferred)
        if (TypeUtils.isOfClassType(annotation.getType(), JAKARTA_INTERCEPTORS_FQN) ||
            TypeUtils.isOfClassType(annotation.getType(), JAVAX_INTERCEPTORS_FQN)) {
            return true;
        }

        // Name-based fallback when type is null (HIGH fix: check imports)
        if (annotation.getType() == null && "Interceptors".equals(annotation.getSimpleName())) {
            NameTree annotationType = annotation.getAnnotationType();

            // If fully qualified in source (@jakarta.interceptor.Interceptors), check the path
            if (annotationType instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) annotationType;
                String source = fa.toString();
                return source.contains("jakarta.interceptor") || source.contains("javax.interceptor");
            }

            // If simple name (@Interceptors), check imports to avoid false positives
            if (annotationType instanceof J.Identifier) {
                return hasInterceptorsImport(cursor);
            }
        }

        return false;
    }

    /**
     * Checks if the compilation unit has an import for jakarta/javax @Interceptors.
     */
    private boolean hasInterceptorsImport(Cursor cursor) {
        J.CompilationUnit cu = cursor.firstEnclosing(J.CompilationUnit.class);
        if (cu == null || cu.getImports() == null) {
            return false;
        }

        for (J.Import imp : cu.getImports()) {
            String fqn = imp.getTypeName();
            if (JAKARTA_INTERCEPTORS_FQN.equals(fqn) || JAVAX_INTERCEPTORS_FQN.equals(fqn)) {
                return true;
            }
            // Also check wildcard imports
            if (imp.isStatic()) continue;
            String pkg = imp.getPackageName();
            if (("jakarta.interceptor".equals(pkg) || "javax.interceptor".equals(pkg)) && "*".equals(imp.getClassName())) {
                return true;
            }
        }
        return false;
    }
}
