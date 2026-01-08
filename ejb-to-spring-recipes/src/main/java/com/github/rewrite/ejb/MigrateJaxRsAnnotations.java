package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Migrates JAX-RS REST annotations to Spring MVC.
 * <p>
 * Handles both javax.ws.rs and jakarta.ws.rs namespaces.
 * <p>
 * Class-level transformations:
 * <ul>
 *   <li>@Path -> @RestController + @RequestMapping(value=...)</li>
 *   <li>@Produces -> @RequestMapping(produces=...)</li>
 *   <li>@Consumes -> @RequestMapping(consumes=...)</li>
 * </ul>
 * <p>
 * Method-level transformations:
 * <ul>
 *   <li>@GET -> @RequestMapping(method = RequestMethod.GET)</li>
 *   <li>@POST -> @RequestMapping(method = RequestMethod.POST)</li>
 *   <li>@PUT -> @RequestMapping(method = RequestMethod.PUT)</li>
 *   <li>@DELETE -> @RequestMapping(method = RequestMethod.DELETE)</li>
 *   <li>@HEAD -> @RequestMapping(method = RequestMethod.HEAD)</li>
 *   <li>@PATCH -> @RequestMapping(method = RequestMethod.PATCH)</li>
 *   <li>@Path -> value attribute in @RequestMapping</li>
 *   <li>@Produces -> produces attribute in @RequestMapping</li>
 *   <li>@Consumes -> consumes attribute in @RequestMapping</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateJaxRsAnnotations extends ScanningRecipe<MigrateJaxRsAnnotations.Accumulator> {

    // JAX-RS packages
    private static final String JAVAX_WS_RS = "javax.ws.rs";
    private static final String JAKARTA_WS_RS = "jakarta.ws.rs";

    // HTTP method annotations
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "DELETE", "HEAD", "PATCH", "OPTIONS");

    // Spring imports
    private static final String SPRING_REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
    private static final String SPRING_REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
    private static final String SPRING_REQUEST_METHOD = "org.springframework.web.bind.annotation.RequestMethod";
    private static final String SPRING_MEDIA_TYPE = "org.springframework.http.MediaType";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    // MediaType constant mappings (JAX-RS constants to actual values)
    private static final Map<String, String> MEDIA_TYPE_MAPPING = Map.ofEntries(
        Map.entry("MediaType.APPLICATION_JSON", "application/json"),
        Map.entry("MediaType.APPLICATION_XML", "application/xml"),
        Map.entry("MediaType.TEXT_PLAIN", "text/plain"),
        Map.entry("MediaType.TEXT_HTML", "text/html"),
        Map.entry("MediaType.TEXT_XML", "text/xml"),
        Map.entry("MediaType.APPLICATION_FORM_URLENCODED", "application/x-www-form-urlencoded"),
        Map.entry("MediaType.MULTIPART_FORM_DATA", "multipart/form-data"),
        Map.entry("MediaType.APPLICATION_OCTET_STREAM", "application/octet-stream"),
        Map.entry("APPLICATION_JSON", "application/json"),
        Map.entry("APPLICATION_XML", "application/xml"),
        Map.entry("TEXT_PLAIN", "text/plain"),
        Map.entry("TEXT_HTML", "text/html"),
        Map.entry("TEXT_XML", "text/xml"),
        Map.entry("APPLICATION_FORM_URLENCODED", "application/x-www-form-urlencoded"),
        Map.entry("MULTIPART_FORM_DATA", "multipart/form-data"),
        Map.entry("APPLICATION_OCTET_STREAM", "application/octet-stream")
    );

    private static final Map<String, String> SPRING_MEDIA_TYPE_VALUE_MAPPING = Map.ofEntries(
        Map.entry("application/json", "APPLICATION_JSON_VALUE"),
        Map.entry("application/xml", "APPLICATION_XML_VALUE"),
        Map.entry("text/plain", "TEXT_PLAIN_VALUE"),
        Map.entry("text/html", "TEXT_HTML_VALUE"),
        Map.entry("text/xml", "TEXT_XML_VALUE"),
        Map.entry("application/x-www-form-urlencoded", "APPLICATION_FORM_URLENCODED_VALUE"),
        Map.entry("multipart/form-data", "MULTIPART_FORM_DATA_VALUE"),
        Map.entry("application/octet-stream", "APPLICATION_OCTET_STREAM_VALUE")
    );

    @Option(displayName = "JAX-RS strategy override",
            description = "Override project.yaml JAX-RS server strategy: keep-jaxrs or migrate-to-spring-mvc. " +
                          "If not set, project.yaml (or defaults) are used.",
            example = "migrate-to-spring-mvc",
            required = false)
    @Nullable
    String strategy;

    @Override
    public String getDisplayName() {
        return "Migrate JAX-RS annotations to Spring MVC";
    }

    @Override
    public String getDescription() {
        return "Converts JAX-RS @Path, HTTP method annotations (@GET, @POST, etc.), " +
               "@Produces, and @Consumes to Spring MVC @RestController and @RequestMapping. " +
               "Supports both javax.ws.rs and jakarta.ws.rs namespaces.";
    }

    static class Accumulator {
        Map<Path, Map<String, String>> constantsByModule = new HashMap<>();
    }

    public MigrateJaxRsAnnotations() {
        this.strategy = null;
    }

    public MigrateJaxRsAnnotations(@Nullable String strategy) {
        this.strategy = strategy;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                Path sourcePath = cu.getSourcePath();
                if (!shouldMigrate(sourcePath)) {
                    return cu;
                }
                if (sourcePath != null) {
                    ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(sourcePath));
                    if (config.isTestSource(sourcePath.toString().replace('\\', '/'))) {
                        return cu;
                    }
                }
                Path moduleRoot = extractProjectRoot(sourcePath);
                collectConstants(cu, acc, moduleRoot);
                return super.visitCompilationUnit(cu, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // Check for either javax or jakarta JAX-RS usage
        TreeVisitor<?, ExecutionContext> delegate = Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAVAX_WS_RS + ".Path", false),
                new UsesType<>(JAKARTA_WS_RS + ".Path", false)
            ),
            new JaxRsToSpringMvcVisitor(acc != null ? acc.constantsByModule : null)
        );
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                if (!shouldMigrate(cu.getSourcePath())) {
                    return cu;
                }
                return (J.CompilationUnit) delegate.visit(cu, ctx);
            }
        };
    }

    private boolean shouldMigrate(@Nullable Path sourcePath) {
        ProjectConfiguration.JaxRsStrategy override = ProjectConfiguration.JaxRsStrategy.fromString(strategy);
        if (strategy != null && override == null) {
            System.err.println("Warning: Unknown jaxrs strategy override '" + strategy +
                    "', using project.yaml/defaults.");
        }
        if (override != null) {
            return override == ProjectConfiguration.JaxRsStrategy.MIGRATE_TO_SPRING_MVC;
        }
        if (sourcePath == null) {
            return ProjectConfiguration.mavenDefaults().getJaxRsStrategy()
                    == ProjectConfiguration.JaxRsStrategy.MIGRATE_TO_SPRING_MVC;
        }
        ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(sourcePath));
        return config.getJaxRsStrategy() == ProjectConfiguration.JaxRsStrategy.MIGRATE_TO_SPRING_MVC;
    }

    private static class JaxRsToSpringMvcVisitor extends JavaIsoVisitor<ExecutionContext> {

        private final Deque<Map<String, String>> constantStack = new ArrayDeque<>();
        private final Deque<String> classNameStack = new ArrayDeque<>();
        private final Map<Path, Map<String, String>> constantsByModule;

        private JaxRsToSpringMvcVisitor(Map<Path, Map<String, String>> constantsByModule) {
            this.constantsByModule = constantsByModule != null ? constantsByModule : Collections.emptyMap();
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            constantStack.push(extractStringConstantsFromClass(classDecl));
            classNameStack.push(classDecl.getSimpleName());
            try {
            // IMPORTANT: Check all blocking conditions BEFORE calling super
            // This prevents method conversion when class-level conversion will be skipped

            // Check if class has @Path annotation
            Optional<J.Annotation> pathAnnotationBefore = findAnnotation(classDecl.getLeadingAnnotations(), "Path");

            // If no @Path, let super handle method conversion and return
            if (pathAnnotationBefore.isEmpty()) {
                return super.visitClassDeclaration(classDecl, ctx);
            }

            // Check 1: If @Path has non-literal value, keep everything JAX-RS
            ResolvedValue classPathValue = resolveAnnotationStringValue(pathAnnotationBefore.get());
            if (!classPathValue.resolved) {
                // DON'T call super - skip all method conversions
                String pathExpr = classPathValue.expression;
                String reason = "JAX-RS @Path has non-literal value (" + pathExpr + ") - manual migration required. " +
                    "Replace constant with literal value, then re-run migration.";
                List<J.Annotation> newAnnotations = new ArrayList<>(classDecl.getLeadingAnnotations());
                // Only add @NeedsReview if not already present with same reason (idempotency)
                if (!hasNeedsReviewForReason(classDecl.getLeadingAnnotations(), "@Path has non-literal value")) {
                    newAnnotations.add(createNeedsReviewAnnotation(reason).withPrefix(Space.format("\n")));
                    maybeAddImport(NEEDS_REVIEW_FQN);
                }
                return classDecl.withLeadingAnnotations(newAnnotations);
            }

            // Check 2: Pre-check for unresolved MediaType constants BEFORE super
            List<String> producesPreCheck = new ArrayList<>();
            List<String> consumesPreCheck = new ArrayList<>();
            Optional<J.Annotation> producesAnnBefore = findAnnotation(classDecl.getLeadingAnnotations(), "Produces");
            Optional<J.Annotation> consumesAnnBefore = findAnnotation(classDecl.getLeadingAnnotations(), "Consumes");
            if (producesAnnBefore.isPresent()) {
                producesPreCheck.addAll(getAnnotationMediaTypeValues(producesAnnBefore.get()));
            }
            if (consumesAnnBefore.isPresent()) {
                consumesPreCheck.addAll(getAnnotationMediaTypeValues(consumesAnnBefore.get()));
            }
            List<String> unresolvedPreCheck = new ArrayList<>();
            unresolvedPreCheck.addAll(extractUnresolvedConstants(producesPreCheck));
            unresolvedPreCheck.addAll(extractUnresolvedConstants(consumesPreCheck));

            // If unresolved MediaTypes, keep everything JAX-RS
            if (!unresolvedPreCheck.isEmpty()) {
                // DON'T call super - skip all method conversions
                String reason = "JAX-RS @Produces/@Consumes have unresolved MediaType constants: " +
                    String.join(", ", unresolvedPreCheck) +
                    ". Replace with literal values and re-run migration.";
                List<J.Annotation> newAnnotations = new ArrayList<>(classDecl.getLeadingAnnotations());
                // Only add @NeedsReview if not already present with same reason (idempotency)
                if (!hasNeedsReviewForReason(classDecl.getLeadingAnnotations(), "@Produces/@Consumes have unresolved MediaType")) {
                    newAnnotations.add(createNeedsReviewAnnotation(reason).withPrefix(Space.format("\n")));
                    maybeAddImport(NEEDS_REVIEW_FQN);
                }
                return classDecl.withLeadingAnnotations(newAnnotations);
            }

            // Check 3: Pre-scan methods for non-literal @Path or unresolved MediaTypes
            // If ANY method requires manual handling, skip class conversion entirely
            // Otherwise, Spring class with JAX-RS method annotations won't work at runtime!
            String methodIssue = scanMethodsForBlockingIssues(classDecl);
            if (methodIssue != null) {
                // DON'T call super - skip all conversions
                // Update existing or add new @NeedsReview (refreshes reason if methods changed)
                List<J.Annotation> newAnnotations = updateOrAddNeedsReview(
                    classDecl.getLeadingAnnotations(),
                    JAXRS_METHOD_ISSUES_MARKER,
                    methodIssue
                );
                return classDecl.withLeadingAnnotations(newAnnotations);
            }

            // All checks passed - now call super to convert methods
            J.ClassDeclaration c = super.visitClassDeclaration(classDecl, ctx);

            // Path annotation is still present (use c now, not classDecl)
            Optional<J.Annotation> pathAnnotation = findAnnotation(c.getLeadingAnnotations(), "Path");
            if (pathAnnotation.isEmpty()) {
                return c;
            }

            // Path is literal (we already checked above), proceed with conversion

            // Collect class-level attributes (already pre-checked before super, so all are resolvable)
            String pathValue = resolveAnnotationStringValue(pathAnnotation.get()).value;
            List<String> produces = new ArrayList<>();
            List<String> consumes = new ArrayList<>();

            Optional<J.Annotation> producesAnnotation = findAnnotation(c.getLeadingAnnotations(), "Produces");
            if (producesAnnotation.isPresent()) {
                produces.addAll(getAnnotationMediaTypeValues(producesAnnotation.get()));
            }
            Optional<J.Annotation> consumesAnnotation = findAnnotation(c.getLeadingAnnotations(), "Consumes");
            if (consumesAnnotation.isPresent()) {
                consumes.addAll(getAnnotationMediaTypeValues(consumesAnnotation.get()));
            }

            // Defensive: filter out any UNRESOLVED values (pre-check should have caught these, but be safe)
            produces.removeIf(v -> v.startsWith(UNRESOLVED_MARKER));
            consumes.removeIf(v -> v.startsWith(UNRESOLVED_MARKER));

            // Build new annotation list with Spring annotations replacing JAX-RS
            List<J.Annotation> newAnnotations = new ArrayList<>();

            // Add @RestController annotation
            newAnnotations.add(createSimpleAnnotation("RestController", SPRING_REST_CONTROLLER));
            maybeAddImport(SPRING_REST_CONTROLLER);

            // Add @RequestMapping with collected attributes
            newAnnotations.add(createRequestMappingAnnotation(pathValue, produces, consumes, Collections.emptySet())
                .withPrefix(Space.format("\n")));
            maybeAddImport(SPRING_REQUEST_MAPPING);

            // Keep other non-JAX-RS annotations
            for (J.Annotation ann : c.getLeadingAnnotations()) {
                if (!isJaxRsAnnotation(ann, "Path") &&
                    !isJaxRsAnnotation(ann, "Produces") &&
                    !isJaxRsAnnotation(ann, "Consumes")) {
                    newAnnotations.add(ann);
                }
            }

            // Remove JAX-RS imports
            maybeRemoveImport(JAVAX_WS_RS + ".Path");
            maybeRemoveImport(JAKARTA_WS_RS + ".Path");
            maybeRemoveImport(JAVAX_WS_RS + ".Produces");
            maybeRemoveImport(JAKARTA_WS_RS + ".Produces");
            maybeRemoveImport(JAVAX_WS_RS + ".Consumes");
            maybeRemoveImport(JAKARTA_WS_RS + ".Consumes");
            // Also remove MediaType imports
            maybeRemoveImport(JAVAX_WS_RS + ".core.MediaType");
            maybeRemoveImport(JAKARTA_WS_RS + ".core.MediaType");

            return c.withLeadingAnnotations(newAnnotations);
            } finally {
                constantStack.pop();
                classNameStack.pop();
            }
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);

            // Check if method has any JAX-RS HTTP method annotation
            Set<String> httpMethods = new LinkedHashSet<>();
            String pathValue = null;
            List<String> produces = new ArrayList<>();
            List<String> consumes = new ArrayList<>();
            List<J.Annotation> annotationsToRemove = new ArrayList<>();
            boolean hasPath = false;
            boolean hasProduces = false;
            boolean hasConsumes = false;
            boolean pathIsNonLiteral = false;
            J.Annotation pathAnnotation = null;

            for (J.Annotation ann : m.getLeadingAnnotations()) {
                String simpleName = getAnnotationSimpleName(ann);
                if (simpleName == null) continue;

                if (HTTP_METHODS.contains(simpleName) && isJaxRsAnnotation(ann, simpleName)) {
                    httpMethods.add(simpleName);
                    annotationsToRemove.add(ann);
                } else if ("Path".equals(simpleName) && isJaxRsAnnotation(ann, "Path")) {
                    pathAnnotation = ann;
                    // Check if @Path has non-literal value
                    ResolvedValue resolvedPath = resolveAnnotationStringValue(ann);
                    if (!resolvedPath.resolved) {
                        pathIsNonLiteral = true;
                        pathValue = resolvedPath.expression;
                        // Don't add to annotationsToRemove - keep the original annotation
                    } else {
                        pathValue = resolvedPath.value;
                        annotationsToRemove.add(ann);
                    }
                    hasPath = true;
                } else if ("Produces".equals(simpleName) && isJaxRsAnnotation(ann, "Produces")) {
                    produces.addAll(getAnnotationMediaTypeValues(ann));
                    annotationsToRemove.add(ann);
                    hasProduces = true;
                } else if ("Consumes".equals(simpleName) && isJaxRsAnnotation(ann, "Consumes")) {
                    consumes.addAll(getAnnotationMediaTypeValues(ann));
                    annotationsToRemove.add(ann);
                    hasConsumes = true;
                }
            }

            // If no JAX-RS annotations found, return as-is
            if (annotationsToRemove.isEmpty() && !pathIsNonLiteral) {
                return m;
            }

            // Check for unresolved MediaType constants
            List<String> unresolvedConstants = new ArrayList<>();
            unresolvedConstants.addAll(extractUnresolvedConstants(produces));
            unresolvedConstants.addAll(extractUnresolvedConstants(consumes));

            // If there are unresolved MediaTypes, keep original JAX-RS annotations and add @NeedsReview
            if (!unresolvedConstants.isEmpty()) {
                String reason = "JAX-RS @Produces/@Consumes have unresolved MediaType constants: " +
                    String.join(", ", unresolvedConstants) +
                    ". Replace with literal values and re-run migration.";
                List<J.Annotation> newAnnotations = new ArrayList<>(m.getLeadingAnnotations());
                // Only add @NeedsReview if not already present with same reason (idempotency)
                if (!hasNeedsReviewForReason(m.getLeadingAnnotations(), "@Produces/@Consumes have unresolved MediaType")) {
                    newAnnotations.add(createNeedsReviewAnnotation(reason).withPrefix(Space.format(" ")));
                    maybeAddImport(NEEDS_REVIEW_FQN);
                }
                return m.withLeadingAnnotations(newAnnotations);
            }

            // Defensive: filter out any UNRESOLVED values (shouldn't exist at this point, but be safe)
            produces.removeIf(v -> v.startsWith(UNRESOLVED_MARKER));
            consumes.removeIf(v -> v.startsWith(UNRESOLVED_MARKER));

            // Build new annotation list
            List<J.Annotation> newAnnotations = new ArrayList<>();

            // If @Path is non-literal, skip conversion entirely - keep ALL original JAX-RS annotations
            if (pathIsNonLiteral) {
                // Keep ALL original annotations (don't filter by annotationsToRemove - we want to keep HTTP methods, etc.)
                String reason = "JAX-RS @Path has non-literal value (" + pathValue + ") - manual migration required. " +
                    "Replace constant with literal value, then re-run migration.";
                List<J.Annotation> allAnnotations = new ArrayList<>(m.getLeadingAnnotations());
                // Only add @NeedsReview if not already present with same reason (idempotency)
                if (!hasNeedsReviewForReason(m.getLeadingAnnotations(), "@Path has non-literal value")) {
                    J.Annotation reviewAnn = createNeedsReviewAnnotation(reason).withPrefix(Space.format(" "));
                    allAnnotations.add(reviewAnn);
                    maybeAddImport(NEEDS_REVIEW_FQN);
                }
                // Don't remove any JAX-RS imports since we're keeping all JAX-RS annotations
                return m.withLeadingAnnotations(allAnnotations);
            }

            // Add @RequestMapping with collected attributes and methods
            newAnnotations.add(createRequestMappingAnnotation(pathValue, produces, consumes, httpMethods));
            maybeAddImport(SPRING_REQUEST_MAPPING);
            if (!httpMethods.isEmpty()) {
                maybeAddImport(SPRING_REQUEST_METHOD);
            }

            // Keep annotations that are not being removed
            for (J.Annotation ann : m.getLeadingAnnotations()) {
                if (!annotationsToRemove.contains(ann)) {
                    newAnnotations.add(ann);
                }
            }

            // Remove JAX-RS imports for HTTP methods
            for (String httpMethod : httpMethods) {
                maybeRemoveImport(JAVAX_WS_RS + "." + httpMethod);
                maybeRemoveImport(JAKARTA_WS_RS + "." + httpMethod);
            }
            if (hasPath && !pathIsNonLiteral) {
                maybeRemoveImport(JAVAX_WS_RS + ".Path");
                maybeRemoveImport(JAKARTA_WS_RS + ".Path");
            }
            if (hasProduces) {
                maybeRemoveImport(JAVAX_WS_RS + ".Produces");
                maybeRemoveImport(JAKARTA_WS_RS + ".Produces");
                maybeRemoveImport(JAVAX_WS_RS + ".core.MediaType");
                maybeRemoveImport(JAKARTA_WS_RS + ".core.MediaType");
            }
            if (hasConsumes) {
                maybeRemoveImport(JAVAX_WS_RS + ".Consumes");
                maybeRemoveImport(JAKARTA_WS_RS + ".Consumes");
                maybeRemoveImport(JAVAX_WS_RS + ".core.MediaType");
                maybeRemoveImport(JAKARTA_WS_RS + ".core.MediaType");
            }

            return m.withLeadingAnnotations(newAnnotations);
        }

        private Optional<J.Annotation> findAnnotation(List<J.Annotation> annotations, String simpleName) {
            return annotations.stream()
                .filter(a -> isJaxRsAnnotation(a, simpleName))
                .findFirst();
        }

        private boolean isJaxRsAnnotation(J.Annotation annotation, String simpleName) {
            String fqn = getAnnotationFqn(annotation);
            if (fqn == null) {
                // Fallback to simple name check
                String annSimpleName = getAnnotationSimpleName(annotation);
                return simpleName.equals(annSimpleName);
            }
            return fqn.equals(JAVAX_WS_RS + "." + simpleName) ||
                   fqn.equals(JAKARTA_WS_RS + "." + simpleName);
        }

        private String getAnnotationFqn(J.Annotation annotation) {
            JavaType.FullyQualified type = TypeUtils.asFullyQualified(annotation.getType());
            return type != null ? type.getFullyQualifiedName() : null;
        }

        private String getAnnotationSimpleName(J.Annotation annotation) {
            if (annotation.getAnnotationType() instanceof J.Identifier) {
                return ((J.Identifier) annotation.getAnnotationType()).getSimpleName();
            } else if (annotation.getAnnotationType() instanceof J.FieldAccess) {
                return ((J.FieldAccess) annotation.getAnnotationType()).getSimpleName();
            }
            return null;
        }

        /**
         * Gets the expression string from an annotation argument (prints the actual code).
         * Use this for error messages when value might be a constant reference.
         */
        private String getAnnotationExpressionString(J.Annotation annotation) {
            if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                return null;
            }

            for (Expression arg : annotation.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        if ("value".equals(attrName)) {
                            return assignment.getAssignment().print(getCursor()).trim();
                        }
                    }
                } else {
                    // Direct argument (literal, identifier, or field access)
                    return arg.print(getCursor()).trim();
                }
            }
            return null;
        }

        private ResolvedValue resolveAnnotationStringValue(J.Annotation annotation) {
            if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                return new ResolvedValue(null, null, true);
            }

            for (Expression arg : annotation.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        if ("value".equals(attrName)) {
                            ResolvedValue resolved = resolveStringExpression(assignment.getAssignment());
                            if (resolved.resolved) {
                                return resolved;
                            }
                            return new ResolvedValue(null, resolved.expression, false);
                        }
                    }
                } else {
                    ResolvedValue resolved = resolveStringExpression(arg);
                    if (resolved.resolved) {
                        return resolved;
                    }
                    return new ResolvedValue(null, resolved.expression, false);
                }
            }
            return new ResolvedValue(null, getAnnotationExpressionString(annotation), false);
        }

        private ResolvedValue resolveStringExpression(Expression expr) {
            if (expr instanceof J.Literal) {
                Object value = ((J.Literal) expr).getValue();
                return new ResolvedValue(value != null ? value.toString() : null, null, true);
            }
            if (expr instanceof J.Parentheses) {
                J tree = ((J.Parentheses<?>) expr).getTree();
                if (tree instanceof Expression) {
                    return resolveStringExpression((Expression) tree);
                }
                return new ResolvedValue(null, expr.print(getCursor()).trim(), false);
            }
            if (expr instanceof J.Identifier || expr instanceof J.FieldAccess) {
                String resolved = resolveStringConstant(expr);
                if (resolved != null) {
                    return new ResolvedValue(resolved, null, true);
                }
                return new ResolvedValue(null, expr.print(getCursor()).trim(), false);
            }
            if (expr instanceof J.Binary) {
                J.Binary binary = (J.Binary) expr;
                if (binary.getOperator() == J.Binary.Type.Addition) {
                    ResolvedValue left = resolveStringExpression(binary.getLeft());
                    ResolvedValue right = resolveStringExpression(binary.getRight());
                    if (left.resolved && right.resolved) {
                        return new ResolvedValue(left.value + right.value, null, true);
                    }
                }
                return new ResolvedValue(null, expr.print(getCursor()).trim(), false);
            }
            return new ResolvedValue(null, expr.print(getCursor()).trim(), false);
        }

        /**
         * Gets MediaType values from @Produces or @Consumes, resolving constants to actual values.
         */
        private List<String> getAnnotationMediaTypeValues(J.Annotation annotation) {
            List<String> values = new ArrayList<>();
            if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                return values;
            }

            for (Expression arg : annotation.getArguments()) {
                if (arg instanceof J.Literal) {
                    // Direct string literal like "application/json"
                    Object value = ((J.Literal) arg).getValue();
                    if (value != null) {
                        values.add(value.toString());
                    }
                } else if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        if ("value".equals(attrName)) {
                            values.addAll(extractMediaTypeValues(assignment.getAssignment()));
                        }
                    }
                } else if (arg instanceof J.NewArray) {
                    // Array like {"application/json", "application/xml"}
                    values.addAll(extractMediaTypeArrayValues((J.NewArray) arg));
                } else if (arg instanceof J.Binary || arg instanceof J.Parentheses) {
                    ResolvedValue resolved = resolveStringExpression(arg);
                    if (resolved.resolved && resolved.value != null) {
                        values.add(resolved.value);
                    }
                } else if (arg instanceof J.FieldAccess || arg instanceof J.Identifier) {
                    // MediaType constant like MediaType.APPLICATION_JSON
                    String resolved = resolveMediaTypeConstant(arg);
                    if (resolved != null) {
                        values.add(resolved);
                    }
                }
            }
            return values;
        }

        private List<String> extractMediaTypeValues(Expression expr) {
            List<String> values = new ArrayList<>();
            if (expr instanceof J.Literal) {
                Object value = ((J.Literal) expr).getValue();
                if (value != null) {
                    values.add(value.toString());
                }
            } else if (expr instanceof J.NewArray) {
                values.addAll(extractMediaTypeArrayValues((J.NewArray) expr));
            } else if (expr instanceof J.Binary || expr instanceof J.Parentheses) {
                ResolvedValue resolved = resolveStringExpression(expr);
                if (resolved.resolved && resolved.value != null) {
                    values.add(resolved.value);
                }
            } else if (expr instanceof J.FieldAccess || expr instanceof J.Identifier) {
                String resolved = resolveMediaTypeConstant(expr);
                if (resolved != null) {
                    values.add(resolved);
                }
            }
            return values;
        }

        private List<String> extractMediaTypeArrayValues(J.NewArray newArray) {
            List<String> values = new ArrayList<>();
            if (newArray.getInitializer() == null || newArray.getInitializer().isEmpty()) {
                return values;
            }
            for (Expression elem : newArray.getInitializer()) {
                if (elem instanceof J.Literal) {
                    Object value = ((J.Literal) elem).getValue();
                    if (value != null) {
                        values.add(value.toString());
                    }
                } else if (elem instanceof J.Binary || elem instanceof J.Parentheses) {
                    ResolvedValue resolved = resolveStringExpression(elem);
                    if (resolved.resolved && resolved.value != null) {
                        values.add(resolved.value);
                    }
                } else if (elem instanceof J.FieldAccess || elem instanceof J.Identifier) {
                    String resolved = resolveMediaTypeConstant(elem);
                    if (resolved != null) {
                        values.add(resolved);
                    }
                }
            }
            return values;
        }

        // Prefix used to mark unresolved MediaType constants
        private static final String UNRESOLVED_MARKER = "UNRESOLVED:";

        // Marker for method-level issues deduplication (exact match in @NeedsReview reason)
        private static final String JAXRS_METHOD_ISSUES_MARKER = "[jaxrs-method-issues]";

        /**
         * Resolves MediaType constants like MediaType.APPLICATION_JSON to "application/json".
         * Returns prefixed string "UNRESOLVED:original" if constant cannot be resolved.
         */
        private String resolveMediaTypeConstant(Expression expr) {
            String resolvedConstant = resolveStringConstant(expr);
            if (resolvedConstant != null) {
                return resolvedConstant;
            }
            String printed = expr.print(getCursor()).trim();
            // Check if it's a known MediaType constant
            String resolved = MEDIA_TYPE_MAPPING.get(printed);
            if (resolved != null) {
                return resolved;
            }
            // If not found in mapping but looks like a constant, try to extract the simple name
            if (expr instanceof J.FieldAccess) {
                String simpleName = ((J.FieldAccess) expr).getSimpleName();
                resolved = MEDIA_TYPE_MAPPING.get(simpleName);
                if (resolved != null) {
                    return resolved;
                }
            } else if (expr instanceof J.Identifier) {
                String simpleName = ((J.Identifier) expr).getSimpleName();
                resolved = MEDIA_TYPE_MAPPING.get(simpleName);
                if (resolved != null) {
                    return resolved;
                }
            }
            // If we can't resolve, mark it as unresolved so caller can add @NeedsReview
            return UNRESOLVED_MARKER + printed;
        }

        /**
         * Checks if the annotation list already contains a @NeedsReview annotation with a reason
         * that contains the given key phrase. This enables multiple @NeedsReview annotations for
         * different issues while preventing duplicates of the same issue on repeated recipe runs.
         *
         * @param annotations the annotation list to check
         * @param reasonKey a key phrase that uniquely identifies this type of issue
         * @return true if a @NeedsReview with matching reason already exists
         */
        private boolean hasNeedsReviewForReason(List<J.Annotation> annotations, String reasonKey) {
            for (J.Annotation ann : annotations) {
                String simpleName = getAnnotationSimpleName(ann);
                if ("NeedsReview".equals(simpleName)) {
                    // Extract the reason value from the annotation
                    String reason = getAnnotationReasonValue(ann);
                    if (reason != null && reason.contains(reasonKey)) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Extracts the "reason" attribute value from a @NeedsReview annotation.
         */
        private String getAnnotationReasonValue(J.Annotation annotation) {
            if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                return null;
            }
            for (Expression arg : annotation.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        if ("reason".equals(attrName)) {
                            Expression valueExpr = assignment.getAssignment();
                            if (valueExpr instanceof J.Literal) {
                                Object value = ((J.Literal) valueExpr).getValue();
                                return value != null ? value.toString() : null;
                            }
                        }
                    }
                }
            }
            return null;
        }

        /**
         * Updates existing @NeedsReview (if marker matches but reason differs) or adds a new one.
         * This ensures that method-issue lists stay current when methods are fixed/changed.
         * Also deduplicates: if multiple @NeedsReview with the marker exist, only keeps one.
         *
         * @param annotations existing annotation list
         * @param marker the marker string to identify this type of @NeedsReview
         * @param newReason the new reason text (should contain the marker)
         * @return updated annotation list
         */
        private List<J.Annotation> updateOrAddNeedsReview(List<J.Annotation> annotations, String marker, String newReason) {
            List<J.Annotation> result = new ArrayList<>();
            boolean found = false;

            for (J.Annotation ann : annotations) {
                String simpleName = getAnnotationSimpleName(ann);
                if ("NeedsReview".equals(simpleName)) {
                    String existingReason = getAnnotationReasonValue(ann);
                    if (existingReason != null && existingReason.contains(marker)) {
                        if (!found) {
                            // First match - update or keep
                            found = true;
                            if (!existingReason.equals(newReason)) {
                                // Reason changed - replace with new annotation
                                result.add(createNeedsReviewAnnotation(newReason).withPrefix(ann.getPrefix()));
                                // Ensure import exists (in case original was fully-qualified)
                                maybeAddImport(NEEDS_REVIEW_FQN);
                            } else {
                                // Reason is same - keep existing
                                result.add(ann);
                            }
                        }
                        // Subsequent matches (duplicates) - skip them
                        continue;
                    }
                }
                result.add(ann);
            }

            // If not found, add new annotation
            if (!found) {
                result.add(createNeedsReviewAnnotation(newReason).withPrefix(Space.format("\n")));
                maybeAddImport(NEEDS_REVIEW_FQN);
            }

            return result;
        }

        /**
         * Extracts unresolved constant names from values (does NOT mutate input list).
         * Returns list of unresolved constant names (without UNRESOLVED_MARKER prefix).
         */
        private List<String> extractUnresolvedConstants(List<String> values) {
            List<String> unresolved = new ArrayList<>();
            for (String val : values) {
                if (val.startsWith(UNRESOLVED_MARKER)) {
                    String original = val.substring(UNRESOLVED_MARKER.length());
                    unresolved.add(original);
                }
            }
            return unresolved;
        }

        /**
         * Scans all methods in the class for blocking issues that prevent class-level conversion.
         * If any method has non-literal @Path or unresolved MediaTypes, class conversion must be skipped
         * because Spring class with JAX-RS method annotations won't work at runtime.
         *
         * Issues are grouped by method signature (name + parameter types) to handle overloaded methods.
         *
         * @return description of ALL blocking issues grouped by method, or null if all methods can be converted
         */
        private String scanMethodsForBlockingIssues(J.ClassDeclaration classDecl) {
            // Map method signature -> list of issues for that method (preserves order)
            // Using signature as key to correctly handle overloaded methods
            Map<String, List<String>> issuesByMethod = new LinkedHashMap<>();

            for (J.MethodDeclaration method : classDecl.getBody().getStatements().stream()
                    .filter(s -> s instanceof J.MethodDeclaration)
                    .map(s -> (J.MethodDeclaration) s)
                    .collect(Collectors.toList())) {

                String methodSignature = getMethodSignature(method);
                List<String> methodIssues = new ArrayList<>();

                for (J.Annotation ann : method.getLeadingAnnotations()) {
                    String simpleName = getAnnotationSimpleName(ann);
                    if (simpleName == null) continue;

                    // Check for non-literal @Path on method
                    if ("Path".equals(simpleName) && isJaxRsAnnotation(ann, "Path")) {
                        ResolvedValue resolvedPath = resolveAnnotationStringValue(ann);
                        if (!resolvedPath.resolved) {
                            String pathExpr = resolvedPath.expression;
                            methodIssues.add("@Path has non-literal value (" + pathExpr + ")");
                        }
                    }

                    // Check for unresolved MediaTypes on method
                    if ("Produces".equals(simpleName) && isJaxRsAnnotation(ann, "Produces")) {
                        List<String> values = getAnnotationMediaTypeValues(ann);
                        List<String> unresolved = extractUnresolvedConstants(values);
                        if (!unresolved.isEmpty()) {
                            methodIssues.add("@Produces has unresolved MediaType constants (" +
                                String.join(", ", unresolved) + ")");
                        }
                    }
                    if ("Consumes".equals(simpleName) && isJaxRsAnnotation(ann, "Consumes")) {
                        List<String> values = getAnnotationMediaTypeValues(ann);
                        List<String> unresolved = extractUnresolvedConstants(values);
                        if (!unresolved.isEmpty()) {
                            methodIssues.add("@Consumes has unresolved MediaType constants (" +
                                String.join(", ", unresolved) + ")");
                        }
                    }
                }

                if (!methodIssues.isEmpty()) {
                    issuesByMethod.put(methodSignature, methodIssues);
                }
            }

            if (issuesByMethod.isEmpty()) {
                return null; // No blocking issues found
            }

            // Format output with issues grouped by method signature
            List<String> methodDescriptions = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : issuesByMethod.entrySet()) {
                String methodSig = entry.getKey();
                List<String> issues = entry.getValue();
                methodDescriptions.add("Method '" + methodSig + "': " + String.join(", ", issues));
            }

            // Include marker for exact deduplication; count unique method signatures
            return JAXRS_METHOD_ISSUES_MARKER + " Manual migration required for " + issuesByMethod.size() + " method(s): " +
                String.join("; ", methodDescriptions) + ". Replace constants with literal values, then re-run migration.";
        }

        /**
         * Generates a method signature string including name and parameter types.
         * Format: methodName(Type1, Type2) for disambiguation of overloaded methods.
         * Uses JavaType when available to get fully qualified names that ensure uniqueness.
         */
        private String getMethodSignature(J.MethodDeclaration method) {
            StringBuilder sig = new StringBuilder(method.getSimpleName());
            sig.append("(");
            List<Statement> params = method.getParameters();
            for (int i = 0; i < params.size(); i++) {
                if (i > 0) {
                    sig.append(", ");
                }
                Statement param = params.get(i);
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations varDecl = (J.VariableDeclarations) param;
                    // Get qualified type name from JavaType (handles generics correctly with FQN)
                    String typeName = getQualifiedTypeName(varDecl);
                    sig.append(typeName);
                } else if (param instanceof J.Empty) {
                    // No parameters - skip
                } else {
                    sig.append("?");
                }
            }
            sig.append(")");
            return sig.toString();
        }

        /**
         * Gets a fully qualified type name from a VariableDeclarations, handling generics correctly.
         * Uses FQN to ensure uniqueness (e.g., com.foo.User vs com.bar.User are distinct).
         * For java.util.List<com.example.User> returns "java.util.List<com.example.User>".
         */
        private String getQualifiedTypeName(J.VariableDeclarations varDecl) {
            // First try to get the type from JavaType (most accurate)
            if (varDecl.getVariables() != null && !varDecl.getVariables().isEmpty()) {
                JavaType type = varDecl.getVariables().get(0).getType();
                if (type != null) {
                    return getQualifiedNameFromJavaType(type);
                }
            }
            // Fallback to type expression if no JavaType available
            TypeTree typeExpr = varDecl.getTypeExpression();
            if (typeExpr != null) {
                // Use full printed type string (don't try to strip packages - breaks generics)
                return typeExpr.print(getCursor()).trim();
            }
            return "?";
        }

        /**
         * Extracts a fully qualified type name from a JavaType, correctly handling generics.
         * Uses FQN to ensure uniqueness (e.g., com.foo.User vs com.bar.User are distinct).
         * For example: java.util.List<com.example.User> -> java.util.List<com.example.User>
         */
        private String getQualifiedNameFromJavaType(JavaType type) {
            if (type instanceof JavaType.Parameterized) {
                JavaType.Parameterized pType = (JavaType.Parameterized) type;
                // Use FQN for raw type to ensure uniqueness
                StringBuilder sb = new StringBuilder(pType.getFullyQualifiedName());
                List<JavaType> typeParams = pType.getTypeParameters();
                if (!typeParams.isEmpty()) {
                    sb.append("<");
                    for (int i = 0; i < typeParams.size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append(getQualifiedNameFromJavaType(typeParams.get(i)));
                    }
                    sb.append(">");
                }
                return sb.toString();
            } else if (type instanceof JavaType.FullyQualified) {
                // Use FQN to ensure uniqueness
                return ((JavaType.FullyQualified) type).getFullyQualifiedName();
            } else if (type instanceof JavaType.Array) {
                return getQualifiedNameFromJavaType(((JavaType.Array) type).getElemType()) + "[]";
            } else if (type instanceof JavaType.Primitive) {
                return ((JavaType.Primitive) type).getKeyword();
            } else if (type instanceof JavaType.GenericTypeVariable) {
                return ((JavaType.GenericTypeVariable) type).getName();
            }
            return type.toString();
        }

        private String resolveStringConstant(Expression expr) {
            Map<String, String> constants = constantStack.peek();
            if (expr instanceof J.Identifier) {
                String name = ((J.Identifier) expr).getSimpleName();
                if (constants != null && !constants.isEmpty()) {
                    String local = constants.get(name);
                    if (local != null) {
                        return local;
                    }
                }
                J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                if (cu != null) {
                    return resolveStaticImportConstant(cu, name);
                }
                return null;
            }
            if (expr instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) expr;
                String targetName = getTargetSimpleName(fa.getTarget());
                String currentClass = classNameStack.peek();
                if (currentClass != null && currentClass.equals(targetName)) {
                    if (constants == null || constants.isEmpty()) {
                        return null;
                    }
                    return constants.get(fa.getSimpleName());
                }
                if (targetName != null) {
                    J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                    if (cu != null) {
                        J.ClassDeclaration otherClass = findClassBySimpleName(cu, targetName);
                        if (otherClass != null) {
                            Map<String, String> otherConstants = extractStringConstantsFromClass(otherClass);
                            String resolved = otherConstants.get(fa.getSimpleName());
                            if (resolved != null) {
                                return resolved;
                            }
                        }
                        String imported = resolveImportedOrSamePackageConstant(cu, fa);
                        if (imported != null) {
                            return imported;
                        }
                    }
                }
            }
            return null;
        }

        private String getTargetSimpleName(Expression target) {
            if (target instanceof J.Identifier) {
                return ((J.Identifier) target).getSimpleName();
            }
            if (target instanceof J.FieldAccess) {
                return ((J.FieldAccess) target).getSimpleName();
            }
            return null;
        }

        private String resolveStaticImportConstant(J.CompilationUnit cu, String name) {
            Map<String, String> moduleConstants = moduleConstantsFor(cu);
            for (J.Import imp : cu.getImports()) {
                if (!imp.isStatic()) {
                    continue;
                }
                String typeName = imp.getTypeName();
                if (typeName == null) {
                    continue;
                }
                if (typeName.endsWith(".*")) {
                    String classFqn = typeName.substring(0, typeName.length() - 2);
                    String value = moduleConstants.get(classFqn + "." + name);
                    if (value != null) {
                        return value;
                    }
                } else {
                    int lastDot = typeName.lastIndexOf('.');
                    if (lastDot > 0 && typeName.substring(lastDot + 1).equals(name)) {
                        String value = moduleConstants.get(typeName);
                        if (value != null) {
                            return value;
                        }
                    }
                }
            }
            return null;
        }

        private String resolveImportedOrSamePackageConstant(J.CompilationUnit cu, J.FieldAccess fa) {
            String targetFqn = resolveTargetFqn(cu, fa.getTarget());
            if (targetFqn == null) {
                return null;
            }
            return moduleConstantsFor(cu).get(targetFqn + "." + fa.getSimpleName());
        }

        private String resolveTargetFqn(J.CompilationUnit cu, Expression target) {
            if (target == null) {
                return null;
            }
            String printedTarget = target.printTrimmed();
            if (printedTarget.contains(".")) {
                return printedTarget;
            }
            String targetName = getTargetSimpleName(target);
            if (targetName == null) {
                return null;
            }
            String imported = resolveImportedTypeFqn(cu, targetName);
            if (imported != null) {
                return imported;
            }
            String pkg = cu.getPackageDeclaration() != null
                ? cu.getPackageDeclaration().getPackageName()
                : "";
            return pkg.isEmpty() ? targetName : pkg + "." + targetName;
        }

        private String resolveImportedTypeFqn(J.CompilationUnit cu, String simpleName) {
            for (J.Import imp : cu.getImports()) {
                if (imp.isStatic()) {
                    continue;
                }
                String typeName = imp.getTypeName();
                if (typeName != null && typeName.endsWith("." + simpleName)) {
                    return typeName;
                }
            }
            return null;
        }

        private Map<String, String> moduleConstantsFor(J.CompilationUnit cu) {
            if (cu == null) {
                return Collections.emptyMap();
            }
            Path sourcePath = cu.getSourcePath();
            if (sourcePath == null) {
                return Collections.emptyMap();
            }
            Path moduleRoot = extractProjectRoot(sourcePath);
            Map<String, String> moduleConstants = constantsByModule.get(moduleRoot);
            return moduleConstants != null ? moduleConstants : Collections.emptyMap();
        }

        private J.ClassDeclaration findClassBySimpleName(J.CompilationUnit cu, String simpleName) {
            for (J.ClassDeclaration classDecl : cu.getClasses()) {
                J.ClassDeclaration found = findClassBySimpleName(classDecl, simpleName);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private J.ClassDeclaration findClassBySimpleName(J.ClassDeclaration classDecl, String simpleName) {
            if (simpleName.equals(classDecl.getSimpleName())) {
                return classDecl;
            }
            if (classDecl.getBody() == null) {
                return null;
            }
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.ClassDeclaration) {
                    J.ClassDeclaration found = findClassBySimpleName((J.ClassDeclaration) stmt, simpleName);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        private static class ResolvedValue {
            final String value;
            final String expression;
            final boolean resolved;

            ResolvedValue(String value, String expression, boolean resolved) {
                this.value = value;
                this.expression = expression;
                this.resolved = resolved;
            }
        }

        /**
         * Creates a simple annotation without arguments.
         */
        private J.Annotation createSimpleAnnotation(String simpleName, String fqn) {
            return new J.Annotation(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    simpleName,
                    JavaType.ShallowClass.build(fqn),
                    null
                ),
                null
            );
        }

        /**
         * Creates a @NeedsReview annotation with a reason.
         */
        private J.Annotation createNeedsReviewAnnotation(String reason) {
            List<JRightPadded<Expression>> args = new ArrayList<>();
            J.Assignment reasonAssignment = createAssignment("reason", createStringLiteral(reason.replace("\"", "\\\"")));
            J.Assignment categoryAssignment = createAssignment("category", createCategoryExpression("MANUAL_MIGRATION"))
                .withPrefix(Space.format(" "));
            args.add(new JRightPadded<>(reasonAssignment, Space.EMPTY, Markers.EMPTY));
            args.add(new JRightPadded<>(categoryAssignment, Space.EMPTY, Markers.EMPTY));

            return new J.Annotation(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "NeedsReview",
                    JavaType.ShallowClass.build(NEEDS_REVIEW_FQN),
                    null
                ),
                JContainer.build(Space.EMPTY, args, Markers.EMPTY)
            );
        }

        private J.FieldAccess createCategoryExpression(String categoryName) {
            JavaType.ShallowClass needsReviewType = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            JavaType.ShallowClass categoryType = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN + ".Category");

            J.Identifier needsReviewIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                needsReviewType,
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

            return new J.FieldAccess(
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
        }

        /**
         * Creates a @RequestMapping annotation with path, produces, consumes, and HTTP methods.
         */
        private J.Annotation createRequestMappingAnnotation(String pathValue, List<String> produces,
                                                            List<String> consumes, Set<String> httpMethods) {
            List<Expression> args = new ArrayList<>();

            // Add value attribute (path)
            if (pathValue != null) {
                args.add(createAssignment("value", createStringLiteral(pathValue)));
            }

            // Add produces attribute (single or array)
            if (!produces.isEmpty()) {
                if (produces.size() == 1) {
                    args.add(createAssignment("produces", createMediaTypeValueExpression(produces.get(0))));
                } else {
                    List<Expression> elements = produces.stream()
                        .map(this::createMediaTypeValueExpression)
                        .collect(Collectors.toList());
                    args.add(createAssignment("produces", createStringArray(elements)));
                }
            }

            // Add consumes attribute (single or array)
            if (!consumes.isEmpty()) {
                if (consumes.size() == 1) {
                    args.add(createAssignment("consumes", createMediaTypeValueExpression(consumes.get(0))));
                } else {
                    List<Expression> elements = consumes.stream()
                        .map(this::createMediaTypeValueExpression)
                        .collect(Collectors.toList());
                    args.add(createAssignment("consumes", createStringArray(elements)));
                }
            }

            // Add method = RequestMethod.XXX
            if (!httpMethods.isEmpty()) {
                if (httpMethods.size() == 1) {
                    String method = httpMethods.iterator().next();
                    args.add(createAssignment("method", createRequestMethodAccess(method)));
                } else {
                    // Multiple methods - create array
                    List<Expression> methodElements = httpMethods.stream()
                        .map(this::createRequestMethodAccess)
                        .collect(Collectors.toList());
                    args.add(createAssignment("method", createArray(methodElements)));
                }
            }

            JContainer<Expression> argsContainer = null;
            if (!args.isEmpty()) {
                List<JRightPadded<Expression>> paddedArgs = new ArrayList<>();
                for (int i = 0; i < args.size(); i++) {
                    Expression arg = args.get(i);
                    // First arg gets no prefix, subsequent args get space prefix (after comma)
                    if (i > 0) {
                        arg = arg.withPrefix(Space.format(" "));
                    }
                    paddedArgs.add(new JRightPadded<>(arg, Space.EMPTY, Markers.EMPTY));
                }
                argsContainer = JContainer.build(Space.EMPTY, paddedArgs, Markers.EMPTY);
            }

            return new J.Annotation(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "RequestMapping",
                    JavaType.ShallowClass.build(SPRING_REQUEST_MAPPING),
                    null
                ),
                argsContainer
            );
        }

        /**
         * Creates a string array initializer like {"value1", "value2"}.
         */
        private J.NewArray createStringArray(List<Expression> elements) {
            List<JRightPadded<Expression>> paddedElements = new ArrayList<>();
            for (int i = 0; i < elements.size(); i++) {
                Expression elem = elements.get(i);
                // Add space prefix to subsequent elements (space after comma)
                if (i > 0) {
                    elem = elem.withPrefix(Space.format(" "));
                }
                // Suffix is space before comma - keep it empty
                paddedElements.add(new JRightPadded<>(elem, Space.EMPTY, Markers.EMPTY));
            }

            return new J.NewArray(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                null,
                Collections.emptyList(),
                JContainer.build(Space.EMPTY, paddedElements, Markers.EMPTY),
                null
            );
        }

        private J.Literal createStringLiteral(String value) {
            return new J.Literal(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                value,
                "\"" + value + "\"",
                null,
                JavaType.Primitive.String
            );
        }

        private J.Assignment createAssignment(String name, Expression value) {
            return new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    name,
                    null,
                    null
                ),
                // Space before the = is added via the identifier suffix in JRightPadded
                // Space after = is the prefix of the value
                new JLeftPadded<>(Space.format(" "), value.withPrefix(Space.format(" ")), Markers.EMPTY),
                null
            );
        }

        private J.FieldAccess createRequestMethodAccess(String method) {
            return new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "RequestMethod",
                    JavaType.ShallowClass.build(SPRING_REQUEST_METHOD),
                    null
                ),
                new JLeftPadded<>(
                    Space.EMPTY,
                    new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        method,
                        null,
                        null
                    ),
                    Markers.EMPTY
                ),
                null
            );
        }

        private Expression createMediaTypeValueExpression(String value) {
            String constant = SPRING_MEDIA_TYPE_VALUE_MAPPING.get(value);
            if (constant != null) {
                maybeAddImport(SPRING_MEDIA_TYPE);
                return createSpringMediaTypeAccess(constant);
            }
            return createStringLiteral(value);
        }

        private J.FieldAccess createSpringMediaTypeAccess(String constantName) {
            return new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "MediaType",
                    JavaType.ShallowClass.build(SPRING_MEDIA_TYPE),
                    null
                ),
                new JLeftPadded<>(
                    Space.EMPTY,
                    new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        constantName,
                        null,
                        null
                    ),
                    Markers.EMPTY
                ),
                null
            );
        }

        private J.NewArray createArray(List<Expression> elements) {
            List<JRightPadded<Expression>> paddedElements = new ArrayList<>();
            for (int i = 0; i < elements.size(); i++) {
                Expression elem = elements.get(i);
                // Add space prefix to subsequent elements (space after comma)
                if (i > 0) {
                    elem = elem.withPrefix(Space.format(" "));
                }
                // Suffix is space before comma - keep it empty
                paddedElements.add(new JRightPadded<>(elem, Space.EMPTY, Markers.EMPTY));
            }

            return new J.NewArray(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                null,
                Collections.emptyList(),
                JContainer.build(Space.EMPTY, paddedElements, Markers.EMPTY),
                null
            );
        }
    }

    private static void collectConstants(J.CompilationUnit cu, Accumulator acc, Path moduleRoot) {
        if (cu == null || acc == null) {
            return;
        }
        if (moduleRoot == null) {
            return;
        }
        Map<String, String> moduleConstants = acc.constantsByModule.computeIfAbsent(moduleRoot, root -> new HashMap<>());
        String pkg = cu.getPackageDeclaration() != null
            ? cu.getPackageDeclaration().getPackageName()
            : "";
        for (J.ClassDeclaration classDecl : cu.getClasses()) {
            Map<String, String> constants = extractStringConstantsFromClass(classDecl);
            if (constants.isEmpty()) {
                continue;
            }
            String classFqn = pkg.isEmpty()
                ? classDecl.getSimpleName()
                : pkg + "." + classDecl.getSimpleName();
            for (Map.Entry<String, String> entry : constants.entrySet()) {
                String constFqn = classFqn + "." + entry.getKey();
                moduleConstants.putIfAbsent(constFqn, entry.getValue());
            }
        }
    }

    private static Map<String, String> extractStringConstantsFromClass(J.ClassDeclaration classDecl) {
        Map<String, String> resolved = new HashMap<>();
        Map<String, Expression> candidates = new LinkedHashMap<>();
        boolean inInterface = isInterface(classDecl);
        if (classDecl.getBody() == null) {
            return resolved;
        }
        for (Statement stmt : classDecl.getBody().getStatements()) {
            if (!(stmt instanceof J.VariableDeclarations)) {
                continue;
            }
            J.VariableDeclarations varDecls = (J.VariableDeclarations) stmt;
            if (!isStaticFinalString(varDecls, inInterface)) {
                continue;
            }
            for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                Expression init = var.getInitializer();
                if (init != null) {
                    candidates.put(var.getSimpleName(), init);
                }
            }
        }
        boolean progress;
        String className = classDecl.getSimpleName();
        do {
            progress = false;
            for (Map.Entry<String, Expression> entry : candidates.entrySet()) {
                if (resolved.containsKey(entry.getKey())) {
                    continue;
                }
                String value = resolveConstantExpression(entry.getValue(), resolved, className);
                if (value != null) {
                    resolved.put(entry.getKey(), value);
                    progress = true;
                }
            }
        } while (progress);
        return resolved;
    }

    private static boolean isStaticFinalString(J.VariableDeclarations varDecls, boolean inInterface) {
        if (!inInterface) {
            if (!hasModifier(varDecls, J.Modifier.Type.Static) ||
                !hasModifier(varDecls, J.Modifier.Type.Final)) {
                return false;
            }
        }
        TypeTree typeExpr = varDecls.getTypeExpression();
        if (typeExpr == null) {
            return false;
        }
        if (TypeUtils.isOfClassType(typeExpr.getType(), "java.lang.String")) {
            return true;
        }
        if (typeExpr instanceof J.Identifier) {
            return "String".equals(((J.Identifier) typeExpr).getSimpleName());
        }
        if (typeExpr instanceof J.FieldAccess) {
            return "String".equals(((J.FieldAccess) typeExpr).getSimpleName());
        }
        return false;
    }

    private static boolean isInterface(J.ClassDeclaration classDecl) {
        return classDecl.getKind() == J.ClassDeclaration.Kind.Type.Interface;
    }

    private static boolean hasModifier(J.VariableDeclarations varDecls, J.Modifier.Type type) {
        for (J.Modifier modifier : varDecls.getModifiers()) {
            if (modifier.getType() == type) {
                return true;
            }
        }
        return false;
    }

    private static String resolveConstantExpression(Expression expr, Map<String, String> resolved, String className) {
        if (expr instanceof J.Literal) {
            Object value = ((J.Literal) expr).getValue();
            return value != null ? value.toString() : null;
        }
        if (expr instanceof J.Parentheses) {
            J tree = ((J.Parentheses<?>) expr).getTree();
            if (tree instanceof Expression) {
                return resolveConstantExpression((Expression) tree, resolved, className);
            }
            return null;
        }
        if (expr instanceof J.Identifier) {
            return resolved.get(((J.Identifier) expr).getSimpleName());
        }
        if (expr instanceof J.FieldAccess) {
            J.FieldAccess fa = (J.FieldAccess) expr;
            String targetName = getTargetSimpleName(fa.getTarget());
            if (targetName == null || targetName.equals(className)) {
                return resolved.get(fa.getSimpleName());
            }
            return null;
        }
        if (expr instanceof J.Binary) {
            J.Binary binary = (J.Binary) expr;
            if (binary.getOperator() == J.Binary.Type.Addition) {
                String left = resolveConstantExpression(binary.getLeft(), resolved, className);
                String right = resolveConstantExpression(binary.getRight(), resolved, className);
                if (left != null && right != null) {
                    return left + right;
                }
            }
        }
        return null;
    }

    private static String getTargetSimpleName(Expression target) {
        if (target instanceof J.Identifier) {
            return ((J.Identifier) target).getSimpleName();
        }
        if (target instanceof J.FieldAccess) {
            return ((J.FieldAccess) target).getSimpleName();
        }
        return null;
    }

    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = sourcePath.toAbsolutePath().getParent();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("project.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get(System.getProperty("user.dir"));
    }
}
