package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.ChangeMethodName;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.RemoveUnusedImports;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GAP-INT-001: Transforms EJB Interceptor implementation classes to Spring AOP Aspects.
 * <p>
 * This recipe:
 * <ul>
 *   <li>Finds classes with {@code @AroundInvoke} methods</li>
 *   <li>Adds {@code @Aspect} and {@code @Component} annotations</li>
 *   <li>Removes {@code @Interceptor} annotation if present</li>
 *   <li>Removes {@code @AroundInvoke} (no {@code @Around} added - pointcut binding via GAP-INT-002)</li>
 *   <li>Changes {@code InvocationContext} parameter to {@code ProceedingJoinPoint}</li>
 *   <li>Transforms {@code getParameters()} to {@code getArgs()}</li>
 *   <li>Adds {@code @NeedsReview} marker for manual pointcut definition</li>
 * </ul>
 * <p>
 * <b>Known limitations (require manual fix):</b>
 * <ul>
 *   <li>{@code getMethod()} - use {@code ((MethodSignature) pjp.getSignature()).getMethod()}</li>
 *   <li>{@code setParameters(args)} - use {@code pjp.proceed(args)} instead of {@code setParameters()} + {@code proceed()}</li>
 *   <li>{@code getContextData()} - no Spring AOP equivalent, use custom solution</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEjbInterceptorsToAop extends Recipe {

    // EJB Interceptor annotations
    private static final String JAKARTA_AROUND_INVOKE = "jakarta.interceptor.AroundInvoke";
    private static final String JAVAX_AROUND_INVOKE = "javax.interceptor.AroundInvoke";
    private static final String JAKARTA_INTERCEPTOR = "jakarta.interceptor.Interceptor";
    private static final String JAVAX_INTERCEPTOR = "javax.interceptor.Interceptor";
    private static final String JAKARTA_INVOCATION_CONTEXT = "jakarta.interceptor.InvocationContext";
    private static final String JAVAX_INVOCATION_CONTEXT = "javax.interceptor.InvocationContext";

    // Spring AOP
    private static final String ASPECT_FQN = "org.aspectj.lang.annotation.Aspect";
    private static final String COMPONENT_FQN = "org.springframework.stereotype.Component";
    private static final String PROCEEDING_JOIN_POINT_FQN = "org.aspectj.lang.ProceedingJoinPoint";

    // Migration marker
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Migrate EJB Interceptors to Spring AOP Aspects";
    }

    @Override
    public String getDescription() {
        return "Transforms EJB Interceptor implementation classes (with @AroundInvoke) to Spring AOP " +
               "@Aspect classes. Removes @AroundInvoke without adding @Around (pointcut binding handled " +
               "by GAP-INT-002 or manual configuration). Converts InvocationContext to ProceedingJoinPoint " +
               "and transforms getParameters() to getArgs(). Known limitations: getMethod(), setParameters(), " +
               "and getContextData() require manual migration (see class Javadoc for mappings).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        // Wrap the visitor to add post-processing for whitespace normalization
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_AROUND_INVOKE, false),
                new UsesType<>(JAVAX_AROUND_INVOKE, false)
            ),
            new InterceptorToAopVisitor()
        );
    }

    /**
     * Returns additional recipes for cleanup after the main visitor transformation.
     * Type transformation is now done in the visitor to scope it correctly to @AroundInvoke methods only.
     */
    @Override
    public java.util.List<Recipe> getRecipeList() {
        return java.util.Collections.singletonList(
            // Clean up unused imports after transformation
            new RemoveUnusedImports()
        );
    }

    private class InterceptorToAopVisitor extends JavaIsoVisitor<ExecutionContext> {

        // Marker to track if type transformation was already scheduled via doAfterVisit
        private boolean typeTransformationScheduled = false;
        // File-level flag: true if ANY @AroundInvoke method in the file uses unsupported APIs
        private boolean fileHasUnsupportedApis = false;

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            // Pre-scan the entire file for unsupported APIs in any @AroundInvoke method
            // This fixes the HIGH issue: if ANY class in the file uses unsupported APIs, skip ChangeType for the whole file
            fileHasUnsupportedApis = compilationUnitHasUnsupportedApis(cu);
            typeTransformationScheduled = false;
            return super.visitCompilationUnit(cu, ctx);
        }

        /**
         * Scans the entire compilation unit for unsupported InvocationContext API usage.
         * Returns true if ANY @AroundInvoke method uses getMethod(), setParameters(), or getContextData().
         */
        private boolean compilationUnitHasUnsupportedApis(J.CompilationUnit cu) {
            final boolean[] found = {false};
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, Integer p) {
                    classDecl.getBody().getStatements().stream()
                        .filter(s -> s instanceof J.MethodDeclaration)
                        .map(s -> (J.MethodDeclaration) s)
                        .filter(m -> hasAroundInvokeAnnotation(m))
                        .forEach(m -> {
                            if (usesUnsupportedApisConservative(m)) {
                                found[0] = true;
                            }
                        });
                    return super.visitClassDeclaration(classDecl, p);
                }
            }.visit(cu, 0);
            return found[0];
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // Check if this class has @AroundInvoke methods
            boolean hasAroundInvoke = classDecl.getBody().getStatements().stream()
                .filter(s -> s instanceof J.MethodDeclaration)
                .map(s -> (J.MethodDeclaration) s)
                .anyMatch(this::hasAroundInvokeAnnotation);

            if (!hasAroundInvoke) {
                return super.visitClassDeclaration(classDecl, ctx);
            }

            // Schedule type transformation via doAfterVisit (only once per source file)
            // This ensures ChangeType only runs on files with @AroundInvoke (fixes MEDIUM issue)
            // and only if NO unsupported APIs exist in the ENTIRE file (fixes HIGH issue)
            if (!typeTransformationScheduled && !fileHasUnsupportedApis) {
                typeTransformationScheduled = true;
                doAfterVisit(new ChangeType(JAKARTA_INVOCATION_CONTEXT, PROCEEDING_JOIN_POINT_FQN, true).getVisitor());
                doAfterVisit(new ChangeType(JAVAX_INVOCATION_CONTEXT, PROCEEDING_JOIN_POINT_FQN, true).getVisitor());
                doAfterVisit(new ChangeMethodName(PROCEEDING_JOIN_POINT_FQN + " getParameters()", "getArgs", true, false).getVisitor());
            }

            // Check if already has @Aspect (for idempotency of class annotations)
            boolean hasAspect = classDecl.getLeadingAnnotations().stream()
                .anyMatch(a -> "Aspect".equals(a.getSimpleName()));

            // For new annotations, we need a clean prefix: just double newline (blank line before class)
            // The originalPrefix may contain extra newlines from removed imports, so we normalize it
            Space cleanPrefix = Space.format("\n\n");

            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Remove @Interceptor annotation
            List<J.Annotation> newAnnotations = cd.getLeadingAnnotations().stream()
                .filter(a -> !isInterceptorAnnotation(a))
                .collect(Collectors.toList());

            // Check if already has a Spring stereotype annotation
            boolean hasStereotype = newAnnotations.stream()
                .anyMatch(a -> "Component".equals(a.getSimpleName()) ||
                               "Service".equals(a.getSimpleName()) ||
                               "Repository".equals(a.getSimpleName()) ||
                               "Controller".equals(a.getSimpleName()) ||
                               "Configuration".equals(a.getSimpleName()) ||
                               "Named".equals(a.getSimpleName()));

            List<J.Annotation> finalAnnotations = new ArrayList<>();

            // Only add @Aspect and @Component if not already present
            if (!hasAspect) {
                maybeAddImport(ASPECT_FQN);
                maybeAddImport(NEEDS_REVIEW_FQN);
                // First annotation uses clean prefix (normalized to avoid extra newlines from removed imports)
                finalAnnotations.add(createSimpleAnnotation("Aspect", ASPECT_FQN, cleanPrefix));

                if (!hasStereotype) {
                    maybeAddImport(COMPONENT_FQN);
                    finalAnnotations.add(createSimpleAnnotation("Component", COMPONENT_FQN, Space.format("\n")));
                }

                // Add @NeedsReview with guidance for manual migration
                finalAnnotations.add(createNeedsReviewAnnotation(
                    "AOP pointcut requires manual refinement",
                    "MANUAL_MIGRATION",
                    "Original EJB @Interceptors binding",
                    "Add @Around with target pointcut"
                ));

                // Add existing annotations (excluding @Interceptor which was filtered out)
                for (J.Annotation ann : newAnnotations) {
                    finalAnnotations.add(ann.withPrefix(Space.format("\n")));
                }

                cd = cd.withLeadingAnnotations(finalAnnotations);

                // Reset the class declaration prefix to empty since we're using annotation prefix for spacing
                // This avoids extra blank lines from removed imports being accumulated in the class prefix
                cd = cd.withPrefix(Space.EMPTY);

                // The class declaration (public/abstract/class) needs a newline prefix after the last annotation
                // This is achieved by setting the modifier's prefix if there are modifiers
                if (!cd.getModifiers().isEmpty()) {
                    J.Modifier firstMod = cd.getModifiers().get(0);
                    List<J.Modifier> newMods = new ArrayList<>(cd.getModifiers());
                    newMods.set(0, firstMod.withPrefix(Space.format("\n")));
                    cd = cd.withModifiers(newMods);
                }
            } else {
                // Class already has @Aspect, just remove @Interceptor if present
                // But still add @Component if no Spring stereotype exists
                // Also add @NeedsReview for pointcut refinement
                maybeAddImport(NEEDS_REVIEW_FQN);
                boolean hasComponentInExisting = newAnnotations.stream()
                    .anyMatch(a -> "Component".equals(a.getSimpleName()) ||
                                   "Service".equals(a.getSimpleName()) ||
                                   "Repository".equals(a.getSimpleName()) ||
                                   "Controller".equals(a.getSimpleName()) ||
                                   "Configuration".equals(a.getSimpleName()) ||
                                   "Named".equals(a.getSimpleName()));

                List<J.Annotation> updatedAnnotations = new ArrayList<>();
                for (J.Annotation ann : newAnnotations) {
                    updatedAnnotations.add(ann);
                    if ("Aspect".equals(ann.getSimpleName())) {
                        if (!hasComponentInExisting) {
                            maybeAddImport(COMPONENT_FQN);
                            updatedAnnotations.add(createSimpleAnnotation("Component", COMPONENT_FQN, Space.format("\n")));
                        }
                        // Add @NeedsReview after @Component (or after @Aspect if @Component already exists)
                        updatedAnnotations.add(createNeedsReviewAnnotation(
                            "AOP pointcut requires manual refinement",
                            "MANUAL_MIGRATION",
                            "Original EJB @Interceptors binding",
                            "Add @Around with target pointcut"
                        ));
                    }
                }
                cd = cd.withLeadingAnnotations(updatedAnnotations);
            }

            // Remove EJB interceptor imports (cleanup handled by RemoveUnusedImports in getRecipeList)
            maybeRemoveImport(JAKARTA_INTERCEPTOR);
            maybeRemoveImport(JAVAX_INTERCEPTOR);
            maybeRemoveImport(JAKARTA_AROUND_INVOKE);
            maybeRemoveImport(JAVAX_AROUND_INVOKE);

            return cd;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            // Check if this method has @AroundInvoke BEFORE super call
            boolean hasAroundInvoke = hasAroundInvokeAnnotation(method);

            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            if (!hasAroundInvoke) {
                return md;
            }

            // Strategy: Remove @AroundInvoke but do NOT add @Around with invalid pointcut.
            // The pointcut binding must be done manually or via GAP-INT-002 (ejb-jar.xml).
            // This ensures the code compiles and Spring context starts successfully.
            // Class-level @NeedsReview indicates manual @Around definition is needed.
            List<J.Annotation> newAnnotations = new ArrayList<>();
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (isAroundInvokeAnnotation(ann)) {
                    // Remove @AroundInvoke - do NOT add @Around with invalid pointcut
                    // The method becomes a regular method with @NeedsReview marker
                    // User must manually add @Around with correct pointcut
                    // (Skip adding @NeedsReview on method if class already has it)
                } else {
                    newAnnotations.add(ann);
                }
            }
            md = md.withLeadingAnnotations(newAnnotations);

            // Transform throws clause: Exception -> Throwable (for future @Around compatibility)
            if (md.getThrows() != null) {
                List<NameTree> newThrows = md.getThrows().stream()
                    .map(t -> {
                        String name = t.toString().trim();
                        if ("Exception".equals(name) || name.endsWith(".Exception")) {
                            return createThrowableType(t.getPrefix());
                        }
                        return t;
                    })
                    .collect(Collectors.toList());
                md = md.withThrows(newThrows);
            }

            return md;
        }

        private boolean hasAroundInvokeAnnotation(J.MethodDeclaration method) {
            return method.getLeadingAnnotations().stream()
                .anyMatch(this::isAroundInvokeAnnotation);
        }

        private boolean isAroundInvokeAnnotation(J.Annotation annotation) {
            return TypeUtils.isOfClassType(annotation.getType(), JAKARTA_AROUND_INVOKE) ||
                   TypeUtils.isOfClassType(annotation.getType(), JAVAX_AROUND_INVOKE) ||
                   "AroundInvoke".equals(annotation.getSimpleName());
        }

        private boolean isInterceptorAnnotation(J.Annotation annotation) {
            return TypeUtils.isOfClassType(annotation.getType(), JAKARTA_INTERCEPTOR) ||
                   TypeUtils.isOfClassType(annotation.getType(), JAVAX_INTERCEPTOR) ||
                   "Interceptor".equals(annotation.getSimpleName());
        }

        /**
         * Conservative check if a method uses unsupported InvocationContext APIs.
         * This version uses type attribution and is more conservative:
         * - Uses TypeUtils to check if the receiver is InvocationContext (handles aliases)
         * - If type info is missing but the method is called and an InvocationContext parameter exists,
         *   treat it as unsupported to be safe.
         */
        private boolean usesUnsupportedApisConservative(J.MethodDeclaration method) {
            // Check if method has an InvocationContext parameter
            boolean hasInvocationContextParam = false;
            if (method.getParameters() != null) {
                for (Statement param : method.getParameters()) {
                    if (param instanceof J.VariableDeclarations) {
                        J.VariableDeclarations varDecl = (J.VariableDeclarations) param;
                        if (varDecl.getType() != null) {
                            String typeFqn = varDecl.getType().toString();
                            if (typeFqn.contains("InvocationContext")) {
                                hasInvocationContextParam = true;
                                break;
                            }
                        }
                    }
                }
            }

            if (!hasInvocationContextParam) {
                return false;
            }

            // Scan method body for unsupported method calls
            // Conservative: if getMethod/setParameters/getContextData is called on ANY receiver that might be InvocationContext
            final boolean[] found = {false};
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation methodInvocation, Integer p) {
                    String methodName = methodInvocation.getSimpleName();
                    if ("getMethod".equals(methodName) || "setParameters".equals(methodName) || "getContextData".equals(methodName)) {
                        // Try type-based detection first
                        if (methodInvocation.getSelect() != null && methodInvocation.getSelect().getType() != null) {
                            JavaType selectType = methodInvocation.getSelect().getType();
                            if (TypeUtils.isOfClassType(selectType, JAKARTA_INVOCATION_CONTEXT) ||
                                TypeUtils.isOfClassType(selectType, JAVAX_INVOCATION_CONTEXT)) {
                                found[0] = true;
                            }
                        } else {
                            // Type info missing - be conservative: if method has InvocationContext param
                            // and this unsupported method is called, assume it's on InvocationContext
                            found[0] = true;
                        }
                    }
                    return super.visitMethodInvocation(methodInvocation, p);
                }
            }.visit(method.getBody(), 0);

            return found[0];
        }

        private J.Annotation createSimpleAnnotation(String name, String fqn, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(fqn);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                name,
                type,
                null
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                null
            );
        }

        private NameTree createThrowableType(Space prefix) {
            return new J.Identifier(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                Collections.emptyList(),
                "Throwable",
                JavaType.ShallowClass.build("java.lang.Throwable"),
                null
            );
        }

        private J.Annotation createNeedsReviewAnnotation(String reason, String category, String originalCode, String suggestedAction) {
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

            List<JRightPadded<Expression>> args = new ArrayList<>();

            // reason = "..."
            args.add(createAnnotationArgument("reason", reason, Space.EMPTY));
            // category = MANUAL_MIGRATION
            args.add(createAnnotationArgumentEnum("category", "NeedsReview.Category", category, Space.format("\n        ")));
            // originalCode = "..."
            args.add(createAnnotationArgument("originalCode", originalCode, Space.format("\n        ")));
            // suggestedAction = "..."
            args.add(createAnnotationArgument("suggestedAction", suggestedAction, Space.format("\n        ")));

            JContainer<Expression> argContainer = JContainer.build(
                Space.EMPTY,
                args,
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                Space.format("\n"),
                Markers.EMPTY,
                ident,
                argContainer
            );
        }

        private JRightPadded<Expression> createAnnotationArgument(String name, String value, Space prefix) {
            J.Identifier nameIdent = new J.Identifier(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                Collections.emptyList(),
                name,
                null,
                null
            );

            J.Literal valueLiteral = new J.Literal(
                Tree.randomId(),
                Space.EMPTY,
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
                nameIdent,
                new JLeftPadded<>(Space.format(" "), valueLiteral, Markers.EMPTY),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }

        private JRightPadded<Expression> createAnnotationArgumentEnum(String name, String enumClass, String enumValue, Space prefix) {
            J.Identifier nameIdent = new J.Identifier(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                Collections.emptyList(),
                name,
                null,
                null
            );

            // Create enum reference: NeedsReview.Category.MANUAL_MIGRATION
            J.FieldAccess enumAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
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
                    new JLeftPadded<>(Space.EMPTY, new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "Category",
                        null,
                        null
                    ), Markers.EMPTY),
                    null
                ),
                new JLeftPadded<>(Space.EMPTY, new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    enumValue,
                    null,
                    null
                ), Markers.EMPTY),
                null
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                nameIdent,
                new JLeftPadded<>(Space.format(" "), enumAccess, Markers.EMPTY),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }
    }
}
