package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Migrates JAX-RS parameter annotations to Spring MVC.
 * <p>
 * Handles both javax.ws.rs and jakarta.ws.rs namespaces.
 * <p>
 * Transformations:
 * <ul>
 *   <li>@PathParam("id") -> @PathVariable("id")</li>
 *   <li>@QueryParam("name") -> @RequestParam("name")</li>
 *   <li>@HeaderParam("header") -> @RequestHeader("header")</li>
 *   <li>@FormParam("field") -> @RequestParam("field")</li>
 *   <li>@CookieParam("cookie") -> @CookieValue("cookie")</li>
 *   <li>@DefaultValue("0") + @QueryParam -> @RequestParam(defaultValue = "0")</li>
 *   <li>@DefaultValue("0") + @PathVariable -> @PathVariable with note about defaultValue</li>
 *   <li>@BeanParam -> @ModelAttribute (marked for review)</li>
 *   <li>@MatrixParam -> @MatrixVariable (requires matrix variables enabled in Spring MVC)</li>
 *   <li>@Context -> @NeedsReview (depends on injected type)</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateJaxRsParameterAnnotations extends ScanningRecipe<MigrateJaxRsParameterAnnotations.Accumulator> {

    private static final String JAVAX_WS_RS = "javax.ws.rs";
    private static final String JAKARTA_WS_RS = "jakarta.ws.rs";
    private static final String SPRING_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final String SPRING_HTTP_HEADERS = "org.springframework.http.HttpHeaders";
    private static final String SPRING_REQUEST_HEADER = "org.springframework.web.bind.annotation.RequestHeader";
    private static final String SPRING_URI_COMPONENTS_BUILDER = "org.springframework.web.util.UriComponentsBuilder";
    private static final String JAVAX_HTTP_SERVLET_REQUEST = "javax.servlet.http.HttpServletRequest";
    private static final String JAKARTA_HTTP_SERVLET_REQUEST = "jakarta.servlet.http.HttpServletRequest";
    private static final String MATRIX_CONFIG_METHOD = "setRemoveSemicolonContent";
    private static final String PATTERN_PARSER_METHOD = "setPatternParser";
    private static final String URL_PATH_HELPER_FQN = "org.springframework.web.util.UrlPathHelper";
    private static final String PATH_MATCH_CONFIGURER_FQN = "org.springframework.web.servlet.config.annotation.PathMatchConfigurer";
    private static final String TEST_SOURCE_KEY = "matrixConfigTestSource";
    private static final Set<String> URI_INFO_SUPPORTED_METHODS = Set.of(
        "getPath",
        "getRequestUri"
    );
    private static final Set<String> REQUEST_UNSUPPORTED_METHODS = Set.of(
        "evaluatePreconditions",
        "selectVariant"
    );

    @Option(displayName = "JAX-RS strategy override",
            description = "Override project.yaml JAX-RS server strategy: keep-jaxrs or migrate-to-spring-mvc. " +
                          "If not set, project.yaml (or defaults) are used.",
            example = "migrate-to-spring-mvc",
            required = false)
    @Nullable
    String strategy;
    private static final Set<String> CONTEXT_FQNS = Set.of(
        "javax.servlet.http.HttpServletRequest",
        "jakarta.servlet.http.HttpServletRequest",
        "javax.servlet.http.HttpServletResponse",
        "jakarta.servlet.http.HttpServletResponse",
        "javax.servlet.http.HttpSession",
        "jakarta.servlet.http.HttpSession",
        "javax.servlet.ServletRequest",
        "jakarta.servlet.ServletRequest",
        "javax.servlet.ServletResponse",
        "jakarta.servlet.ServletResponse",
        "javax.servlet.ServletContext",
        "jakarta.servlet.ServletContext",
        "javax.servlet.ServletConfig",
        "jakarta.servlet.ServletConfig"
    );
    private static final Set<String> CONTEXT_HTTP_HEADERS_FQNS = Set.of(
        "javax.ws.rs.core.HttpHeaders",
        "jakarta.ws.rs.core.HttpHeaders"
    );
    private static final Set<String> CONTEXT_URI_INFO_FQNS = Set.of(
        "javax.ws.rs.core.UriInfo",
        "jakarta.ws.rs.core.UriInfo"
    );
    private static final Set<String> CONTEXT_REQUEST_FQNS = Set.of(
        "javax.ws.rs.core.Request",
        "jakarta.ws.rs.core.Request"
    );
    private static final Set<String> CONTEXT_SECURITY_CONTEXT_FQNS = Set.of(
        "javax.ws.rs.core.SecurityContext",
        "jakarta.ws.rs.core.SecurityContext"
    );
    private static final Set<String> BEAN_PARAM_RELEVANT_ANNOTATIONS = Set.of(
        "PathParam",
        "QueryParam",
        "HeaderParam",
        "FormParam",
        "CookieParam",
        "MatrixParam",
        "DefaultValue",
        "BeanParam"
    );

    @Override
    public String getDisplayName() {
        return "Migrate JAX-RS parameter annotations to Spring MVC";
    }

    @Override
    public String getDescription() {
        return "Converts JAX-RS parameter annotations (@PathParam, @QueryParam, @HeaderParam, etc.) " +
               "to Spring MVC equivalents (@PathVariable, @RequestParam, @RequestHeader, etc.). " +
               "Supports both javax.ws.rs and jakarta.ws.rs namespaces.";
    }

    static class Accumulator {
        boolean hasMatrixConfig = false;
        Map<Path, Map<String, String>> constantsByModule = new HashMap<>();
        Map<String, BeanParamInfo> beanParamInfoByFqn = new HashMap<>();
        Set<String> beanParamTypeFqns = new HashSet<>();
    }

    static class BeanParamInfo {
        private final boolean safeForModelAttribute;

        BeanParamInfo(boolean safeForModelAttribute) {
            this.safeForModelAttribute = safeForModelAttribute;
        }

        boolean isSafeForModelAttribute() {
            return safeForModelAttribute;
        }
    }

    public MigrateJaxRsParameterAnnotations() {
        this.strategy = null;
    }

    public MigrateJaxRsParameterAnnotations(@Nullable String strategy) {
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
                boolean isTestSource = false;
                Path sourcePath = cu.getSourcePath();
                if (!shouldMigrate(sourcePath)) {
                    return cu;
                }
                if (sourcePath != null) {
                    ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(extractProjectRoot(sourcePath));
                    isTestSource = config.isTestSource(sourcePath.toString().replace('\\', '/'));
                }
                getCursor().putMessage(TEST_SOURCE_KEY, isTestSource);
                if (!isTestSource) {
                    Path moduleRoot = extractProjectRoot(sourcePath);
                    collectConstants(cu, acc, moduleRoot);
                }
                return super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                if (!Boolean.TRUE.equals(getCursor().getNearestMessage(TEST_SOURCE_KEY, false))) {
                    J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                    if (cu != null) {
                        String className = getFullyQualifiedClassName(cu, classDecl);
                        if (className != null) {
                            acc.beanParamInfoByFqn.putIfAbsent(
                                className,
                                analyzeBeanParamClass(classDecl)
                            );
                        }
                    }
                }
                return super.visitClassDeclaration(classDecl, ctx);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                if (Boolean.TRUE.equals(getCursor().getNearestMessage(TEST_SOURCE_KEY, false))) {
                    return m;
                }
                if (MATRIX_CONFIG_METHOD.equals(m.getSimpleName())) {
                    if (isMethodOnType(m, getCursor(), URL_PATH_HELPER_FQN)) {
                        List<Expression> args = m.getArguments();
                        if (args.size() == 1 && args.get(0) instanceof J.Literal) {
                            Object value = ((J.Literal) args.get(0)).getValue();
                            if (Boolean.FALSE.equals(value)) {
                                acc.hasMatrixConfig = true;
                            }
                        }
                    }
                } else if (PATTERN_PARSER_METHOD.equals(m.getSimpleName())) {
                    if (isMethodOnType(m, getCursor(), PATH_MATCH_CONFIGURER_FQN)) {
                        acc.hasMatrixConfig = true;
                    }
                }
                return m;
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);
                if (Boolean.TRUE.equals(getCursor().getNearestMessage(TEST_SOURCE_KEY, false))) {
                    return vd;
                }
                if (hasBeanParamAnnotation(vd.getLeadingAnnotations())) {
                    JavaType type = vd.getType();
                    JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
                    if (fq == null && type instanceof JavaType.Parameterized) {
                        fq = ((JavaType.Parameterized) type).getType();
                    }
                    if (fq != null) {
                        acc.beanParamTypeFqns.add(fq.getFullyQualifiedName());
                    }
                }
                return vd;
            }

            private boolean hasBeanParamAnnotation(List<J.Annotation> annotations) {
                for (J.Annotation ann : annotations) {
                    String simpleName = getAnnotationSimpleName(ann);
                    if ("BeanParam".equals(simpleName) && isJaxRsAnnotation(ann, "BeanParam")) {
                        return true;
                    }
                }
                return false;
            }

            private String getFullyQualifiedClassName(J.CompilationUnit cu, J.ClassDeclaration cd) {
                String packageName = cu.getPackageDeclaration() != null
                    ? cu.getPackageDeclaration().getExpression().toString()
                    : "";

                List<String> classNames = new ArrayList<>();
                classNames.add(cd.getSimpleName());

                Cursor cursor = getCursor();
                while (cursor != null) {
                    Object value = cursor.getValue();
                    if (value instanceof J.ClassDeclaration && value != cd) {
                        classNames.add(0, ((J.ClassDeclaration) value).getSimpleName());
                    }
                    cursor = cursor.getParent();
                }

                String className = String.join("$", classNames);
                return packageName.isEmpty() ? className : packageName + "." + className;
            }

            private BeanParamInfo analyzeBeanParamClass(J.ClassDeclaration classDecl) {
                if (classDecl.getBody() == null) {
                    return new BeanParamInfo(false);
                }
                boolean safeForModelAttribute = true;
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations fields = (J.VariableDeclarations) stmt;
                        if (hasUnsupportedBeanParamAnnotations(fields)) {
                            safeForModelAttribute = false;
                            break;
                        }
                    } else if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                        if (hasBeanParamRelevantAnnotation(method.getLeadingAnnotations())) {
                            safeForModelAttribute = false;
                            break;
                        }
                        for (Statement param : method.getParameters()) {
                            if (param instanceof J.VariableDeclarations) {
                                if (hasBeanParamRelevantAnnotation(((J.VariableDeclarations) param).getLeadingAnnotations())) {
                                    safeForModelAttribute = false;
                                    break;
                                }
                            }
                        }
                    }
                    if (!safeForModelAttribute) {
                        break;
                    }
                }
                return new BeanParamInfo(safeForModelAttribute);
            }

            private boolean hasUnsupportedBeanParamAnnotations(J.VariableDeclarations fields) {
                for (J.Annotation ann : fields.getLeadingAnnotations()) {
                    String simpleName = getAnnotationSimpleName(ann);
                    if (simpleName == null) {
                        continue;
                    }
                    if ("Context".equals(simpleName) && isJaxRsAnnotation(ann, "core.Context")) {
                        return true;
                    }
                if (!BEAN_PARAM_RELEVANT_ANNOTATIONS.contains(simpleName) || !isJaxRsAnnotation(ann, simpleName)) {
                    continue;
                }
                if ("QueryParam".equals(simpleName) || "FormParam".equals(simpleName)) {
                    if (ann.getArguments() != null && !ann.getArguments().isEmpty()) {
                        String value = extractAnnotationLiteralValue(ann);
                        if (value == null) {
                            return true;
                        }
                        for (J.VariableDeclarations.NamedVariable var : fields.getVariables()) {
                            if (!value.equals(var.getSimpleName())) {
                                return true;
                            }
                        }
                    }
                } else {
                    return true;
                }
            }
            return false;
        }

            @Nullable
            private String extractAnnotationLiteralValue(J.Annotation ann) {
                if (ann.getArguments() == null || ann.getArguments().isEmpty()) {
                    return null;
                }
                Expression arg = ann.getArguments().get(0);
                if (arg instanceof J.Literal) {
                    Object value = ((J.Literal) arg).getValue();
                    return value != null ? value.toString() : null;
                }
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    Expression value = assignment.getAssignment();
                    if (value instanceof J.Literal) {
                        Object literal = ((J.Literal) value).getValue();
                        return literal != null ? literal.toString() : null;
                    }
                }
                return null;
            }

            private boolean hasBeanParamRelevantAnnotation(List<J.Annotation> annotations) {
                for (J.Annotation ann : annotations) {
                    String simpleName = getAnnotationSimpleName(ann);
                    if (simpleName == null) {
                        continue;
                    }
                    if ("Context".equals(simpleName) && isJaxRsAnnotation(ann, "core.Context")) {
                        return true;
                    }
                    if (BEAN_PARAM_RELEVANT_ANNOTATIONS.contains(simpleName) && isJaxRsAnnotation(ann, simpleName)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean isJaxRsAnnotation(J.Annotation annotation, String simpleName) {
                String fqn = getAnnotationFqn(annotation);
                if (fqn == null) {
                    String annSimpleName = getAnnotationSimpleName(annotation);
                    return simpleName.equals(annSimpleName);
                }
                if (simpleName.contains(".")) {
                    return fqn.equals(JAVAX_WS_RS + "." + simpleName) ||
                           fqn.equals(JAKARTA_WS_RS + "." + simpleName);
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
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        TreeVisitor<?, ExecutionContext> delegate = Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAVAX_WS_RS + ".*", false),
                new UsesType<>(JAKARTA_WS_RS + ".*", false),
                new UsesType<>(JAVAX_WS_RS + ".core.Context", false),
                new UsesType<>(JAKARTA_WS_RS + ".core.Context", false)
            ),
            new ParameterAnnotationVisitor(acc.hasMatrixConfig, acc.constantsByModule, acc.beanParamInfoByFqn, acc.beanParamTypeFqns)
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

    private static class ParameterAnnotationVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final Deque<Map<String, String>> constantStack = new ArrayDeque<>();
        private final Deque<String> classNameStack = new ArrayDeque<>();
        private final Deque<Set<String>> securityContextNamesStack = new ArrayDeque<>();
        private final Deque<Set<String>> securityContextFieldNamesStack = new ArrayDeque<>();
        private final Deque<Set<String>> httpHeadersNamesStack = new ArrayDeque<>();
        private final Deque<Set<String>> httpHeadersFieldNamesStack = new ArrayDeque<>();
        private final Deque<Set<String>> requestParamNeedsReviewStack = new ArrayDeque<>();
        private final Deque<Set<String>> requestFieldNeedsReviewStack = new ArrayDeque<>();
        private final Deque<Set<String>> uriInfoParamNeedsReviewStack = new ArrayDeque<>();
        private final Deque<Set<String>> uriInfoParamAutoNamesStack = new ArrayDeque<>();
        private final Deque<Map<String, String>> pathParamDefaultStack = new ArrayDeque<>();
        private final Deque<Boolean> beanParamClassStack = new ArrayDeque<>();
        private final Deque<Boolean> beanParamSafeStack = new ArrayDeque<>();
        private final boolean hasMatrixConfig;
        private final Map<Path, Map<String, String>> constantsByModule;
        private final Map<String, BeanParamInfo> beanParamInfoByFqn;
        private final Set<String> beanParamTypeFqns;

        private ParameterAnnotationVisitor(boolean hasMatrixConfig,
                                           Map<Path, Map<String, String>> constantsByModule,
                                           Map<String, BeanParamInfo> beanParamInfoByFqn,
                                           Set<String> beanParamTypeFqns) {
            this.hasMatrixConfig = hasMatrixConfig;
            this.constantsByModule = constantsByModule != null ? constantsByModule : Collections.emptyMap();
            this.beanParamInfoByFqn = beanParamInfoByFqn != null ? beanParamInfoByFqn : Collections.emptyMap();
            this.beanParamTypeFqns = beanParamTypeFqns != null ? beanParamTypeFqns : Collections.emptySet();
        }

        // Mapping from JAX-RS annotation to Spring annotation
        private static final Map<String, String> ANNOTATION_MAPPING = Map.of(
            "PathParam", "PathVariable",
            "QueryParam", "RequestParam",
            "HeaderParam", "RequestHeader",
            "FormParam", "RequestParam",
            "CookieParam", "CookieValue"
        );

        private static final Map<String, String> SPRING_IMPORTS = Map.of(
            "PathVariable", "org.springframework.web.bind.annotation.PathVariable",
            "RequestParam", "org.springframework.web.bind.annotation.RequestParam",
            "RequestHeader", "org.springframework.web.bind.annotation.RequestHeader",
            "CookieValue", "org.springframework.web.bind.annotation.CookieValue",
            "ModelAttribute", "org.springframework.web.bind.annotation.ModelAttribute",
            "MatrixVariable", "org.springframework.web.bind.annotation.MatrixVariable"
        );
        private static final Set<String> JAXRS_PARAM_ANNOTATIONS = Set.of(
            "PathParam",
            "QueryParam",
            "HeaderParam",
            "FormParam",
            "CookieParam",
            "MatrixParam",
            "DefaultValue",
            "BeanParam"
        );
        private static final Map<String, String> HTTP_HEADERS_METHOD_MAPPING = Map.of(
            "getRequestHeader", "get",
            "getHeaderString", "getFirst",
            "getAcceptableMediaTypes", "getAccept",
            "getAcceptableLanguages", "getAcceptLanguageAsLocales",
            "getMediaType", "getContentType"
        );

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            constantStack.push(extractStringConstantsFromClass(classDecl));
            classNameStack.push(classDecl.getSimpleName());
            securityContextFieldNamesStack.push(extractSecurityContextFieldNames(classDecl));
            httpHeadersFieldNamesStack.push(extractHttpHeadersFieldNames(classDecl));
            requestFieldNeedsReviewStack.push(extractRequestFieldNeedsReview(classDecl, classDecl.getSimpleName(), ctx));
            boolean isBeanParamClass = isBeanParamClass(classDecl);
            beanParamClassStack.push(isBeanParamClass);
            beanParamSafeStack.push(isBeanParamClass && isSafeBeanParamClass(classDecl));
            try {
                return super.visitClassDeclaration(classDecl, ctx);
            } finally {
                constantStack.pop();
                classNameStack.pop();
                securityContextFieldNamesStack.pop();
                httpHeadersFieldNamesStack.pop();
                requestFieldNeedsReviewStack.pop();
                beanParamClassStack.pop();
                beanParamSafeStack.pop();
            }
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            Set<String> securityContextNames = new HashSet<>();
            Set<String> fieldNames = securityContextFieldNamesStack.peek();
            if (fieldNames != null) {
                securityContextNames.addAll(fieldNames);
            }
            Set<String> httpHeadersNames = new HashSet<>();
            Set<String> httpHeadersFieldNames = httpHeadersFieldNamesStack.peek();
            if (httpHeadersFieldNames != null) {
                httpHeadersNames.addAll(httpHeadersFieldNames);
            }
            for (Statement param : method.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations varDecls = (J.VariableDeclarations) param;
                    if (hasContextAnnotation(varDecls.getLeadingAnnotations()) &&
                        isJaxRsSecurityContextType(varDecls)) {
                        for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                            securityContextNames.add(var.getSimpleName());
                        }
                    }
                    if (hasContextAnnotation(varDecls.getLeadingAnnotations()) &&
                        isJaxRsHttpHeadersType(varDecls)) {
                        for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                            httpHeadersNames.add(var.getSimpleName());
                        }
                    }
                }
            }
            securityContextNamesStack.push(securityContextNames);
            httpHeadersNamesStack.push(httpHeadersNames);
            requestParamNeedsReviewStack.push(extractRequestParamNeedsReview(method, ctx));
            Set<String> uriInfoParamNames = extractUriInfoParamNames(method);
            Set<String> uriInfoParamNeedsReview = findUriInfoNamesWithUnsupportedUsage(method, uriInfoParamNames, ctx);
            Set<String> uriInfoParamAutoNames = new HashSet<>(uriInfoParamNames);
            uriInfoParamAutoNames.removeAll(uriInfoParamNeedsReview);
            uriInfoParamNeedsReviewStack.push(uriInfoParamNeedsReview);
            uriInfoParamAutoNamesStack.push(uriInfoParamAutoNames);
            Map<String, String> pathParamDefaults = collectPathParamDefaults(method, ctx);
            pathParamDefaultStack.push(pathParamDefaults);
            J.MethodDeclaration updated;
            try {
                updated = super.visitMethodDeclaration(method, ctx);
                updated = applyPathParamDefaults(updated, pathParamDefaults);
                return updated;
            } finally {
                securityContextNamesStack.pop();
                httpHeadersNamesStack.pop();
                requestParamNeedsReviewStack.pop();
                uriInfoParamNeedsReviewStack.pop();
                uriInfoParamAutoNamesStack.pop();
                pathParamDefaultStack.pop();
            }
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);
            if ("getLanguage".equals(mi.getSimpleName()) && isOnTrackedHttpHeaders(mi)) {
                Expression select = mi.getSelect();
                if (select != null) {
                    JavaTemplate template = JavaTemplate.builder(
                            "#{any()}.getAcceptLanguageAsLocales().stream().findFirst().orElse(null)")
                        .contextSensitive()
                        .build();
                    return template.apply(getCursor(), mi.getCoordinates().replace(), select);
                }
            }
            String httpHeadersReplacement = HTTP_HEADERS_METHOD_MAPPING.get(mi.getSimpleName());
            if (httpHeadersReplacement != null && isOnTrackedHttpHeaders(mi)) {
                return mi.withName(mi.getName().withSimpleName(httpHeadersReplacement));
            }
            if (isSupportedUriInfoInvocation(mi) && isOnTrackedUriInfo(mi)) {
                Expression select = mi.getSelect();
                if (select != null) {
                    JavaTemplate template = JavaTemplate.builder(uriInfoTemplateFor(mi.getSimpleName()))
                        .contextSensitive()
                        .build();
                    return template.apply(getCursor(), mi.getCoordinates().replace(), select);
                }
            }
            if (!"getAuthenticationScheme".equals(mi.getSimpleName())) {
                return mi;
            }
            Set<String> securityContextNames = securityContextNamesStack.peek();
            if (securityContextNames == null || securityContextNames.isEmpty()) {
                return mi;
            }
            Expression select = mi.getSelect();
            if (select instanceof J.Identifier) {
                String name = ((J.Identifier) select).getSimpleName();
                if (securityContextNames.contains(name)) {
                    return mi.withName(mi.getName().withSimpleName("getAuthType"));
                }
            } else if (select instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) select;
                String name = fa.getSimpleName();
                if (securityContextNames.contains(name) && isThisOrClassQualifier(fa.getTarget())) {
                    return mi.withName(mi.getName().withSimpleName("getAuthType"));
                }
            }
            return mi;
        }

        private Map<String, String> collectPathParamDefaults(J.MethodDeclaration method, ExecutionContext ctx) {
            Map<String, String> defaults = new LinkedHashMap<>();
            if (method.getBody() == null) {
                return defaults;
            }
            for (Statement param : method.getParameters()) {
                if (!(param instanceof J.VariableDeclarations)) {
                    continue;
                }
                J.VariableDeclarations varDecls = (J.VariableDeclarations) param;
                if (varDecls.getVariables().isEmpty()) {
                    continue;
                }
                if (!isStringType(varDecls) || hasModifier(varDecls, J.Modifier.Type.Final)) {
                    continue;
                }
                J.Annotation pathParam = null;
                J.Annotation defaultValueAnn = null;
                for (J.Annotation ann : varDecls.getLeadingAnnotations()) {
                    String simpleName = getAnnotationSimpleName(ann);
                    if ("PathParam".equals(simpleName) && isJaxRsAnnotation(ann, "PathParam")) {
                        pathParam = ann;
                    } else if ("DefaultValue".equals(simpleName) && isJaxRsAnnotation(ann, "DefaultValue")) {
                        defaultValueAnn = ann;
                    }
                }
                if (pathParam == null || defaultValueAnn == null) {
                    continue;
                }
                ResolvedValue resolvedPath = resolveAnnotationStringValue(pathParam);
                ResolvedValue resolvedDefault = resolveAnnotationStringValue(defaultValueAnn);
                if (!resolvedPath.resolved || !resolvedDefault.resolved || resolvedDefault.value == null) {
                    continue;
                }
                String paramName = varDecls.getVariables().get(0).getSimpleName();
                if (paramName == null || paramName.isEmpty()) {
                    continue;
                }
                if (isCapturedInNestedScope(method.getBody(), paramName, ctx)) {
                    continue;
                }
                defaults.put(paramName, resolvedDefault.value);
            }
            return defaults;
        }

        private J.MethodDeclaration applyPathParamDefaults(J.MethodDeclaration method,
                                                           Map<String, String> pathParamDefaults) {
            if (method.getBody() == null || pathParamDefaults.isEmpty()) {
                return method;
            }
            J.MethodDeclaration updated = method;
            int inserted = 0;
            for (Map.Entry<String, String> entry : pathParamDefaults.entrySet()) {
                String paramName = entry.getKey();
                String defaultValue = entry.getValue();
                if (hasExistingDefaultFallback(updated.getBody(), paramName, defaultValue)) {
                    continue;
                }
                String escapedValue = escapeJavaString(defaultValue);
                JavaTemplate template = JavaTemplate.builder(
                        "if (" + paramName + " == null || " + paramName + ".isEmpty()) { " +
                        paramName + " = \"" + escapedValue + "\"; }")
                    .contextSensitive()
                    .build();
                updated = template.apply(
                    new Cursor(getCursor(), updated),
                    updated.getBody().getCoordinates().lastStatement()
                );
                inserted++;
            }
            if (inserted == 0) {
                return updated;
            }
            List<Statement> statements = new ArrayList<>(updated.getBody().getStatements());
            int size = statements.size();
            List<Statement> insertedStatements = new ArrayList<>(statements.subList(size - inserted, size));
            statements.subList(size - inserted, size).clear();
            statements.addAll(0, insertedStatements);
            return updated.withBody(updated.getBody().withStatements(statements));
        }

        private boolean hasExistingDefaultFallback(J.Block body, String paramName, String defaultValue) {
            if (body == null) {
                return false;
            }
            String escapedValue = escapeJavaString(defaultValue);
            String assignmentSnippet = paramName + " = \"" + escapedValue + "\"";
            String nullCheckSnippet = paramName + " == null";
            String emptyCheckSnippet = paramName + ".isEmpty()";
            for (Statement stmt : body.getStatements()) {
                if (!(stmt instanceof J.If)) {
                    continue;
                }
                String printed = stmt.print(new Cursor(null, stmt));
                if (printed.contains(nullCheckSnippet) &&
                    printed.contains(emptyCheckSnippet) &&
                    printed.contains(assignmentSnippet)) {
                    return true;
                }
            }
            return false;
        }

        private boolean isCapturedInNestedScope(J.Block body, String paramName, ExecutionContext ctx) {
            if (body == null) {
                return false;
            }
            final boolean[] captured = {false};
            new JavaIsoVisitor<ExecutionContext>() {
                int nestedDepth = 0;

                @Override
                public J.Lambda visitLambda(J.Lambda lambda, ExecutionContext ctx) {
                    if (captured[0]) {
                        return lambda;
                    }
                    nestedDepth++;
                    J.Lambda l = super.visitLambda(lambda, ctx);
                    nestedDepth--;
                    return l;
                }

                @Override
                public J.NewClass visitNewClass(J.NewClass newClass, ExecutionContext ctx) {
                    if (captured[0]) {
                        return newClass;
                    }
                    boolean isAnonymous = newClass.getBody() != null;
                    if (isAnonymous) {
                        nestedDepth++;
                    }
                    J.NewClass nc = super.visitNewClass(newClass, ctx);
                    if (isAnonymous) {
                        nestedDepth--;
                    }
                    return nc;
                }

                @Override
                public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                    if (captured[0]) {
                        return classDecl;
                    }
                    nestedDepth++;
                    J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                    nestedDepth--;
                    return cd;
                }

                @Override
                public J.Identifier visitIdentifier(J.Identifier ident, ExecutionContext ctx) {
                    if (nestedDepth > 0 && paramName.equals(ident.getSimpleName())) {
                        captured[0] = true;
                        return ident;
                    }
                    return super.visitIdentifier(ident, ctx);
                }

                @Override
                public J.MemberReference visitMemberReference(J.MemberReference memberRef, ExecutionContext ctx) {
                    if (nestedDepth > 0 && paramName.equals(memberRef.getReference().getSimpleName())) {
                        captured[0] = true;
                        return memberRef;
                    }
                    return super.visitMemberReference(memberRef, ctx);
                }
            }.visit(body, ctx);
            return captured[0];
        }

        private boolean isStringType(J.VariableDeclarations varDecls) {
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

        private String escapeJavaString(String value) {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
            J.VariableDeclarations v = super.visitVariableDeclarations(varDecls, ctx);
            if (isBeanParamField(v)) {
                if (Boolean.TRUE.equals(beanParamSafeStack.peek())) {
                    return stripBeanParamFieldAnnotations(v, ctx);
                }
                return v;
            }

            // Process annotations on this parameter
            List<J.Annotation> annotations = new ArrayList<>(v.getLeadingAnnotations());
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean modified = false;

            // First pass: collect DefaultValue if present
            String defaultValue = null;
            J.Annotation defaultValueAnnotation = null;
            for (J.Annotation ann : annotations) {
                if (isJaxRsAnnotation(ann, "DefaultValue")) {
                    ResolvedValue resolvedDefault = resolveAnnotationStringValue(ann);
                    defaultValue = resolvedDefault.value != null ? resolvedDefault.value : resolvedDefault.expression;
                    defaultValueAnnotation = ann;
                }
            }

            // Track whether defaultValue was consumed (converted to Spring attribute or warned)
            boolean defaultValueConsumed = false;
            // Track whether @DefaultValue annotation was kept (not removed)
            boolean defaultValueKept = false;
            String variableName = v.getVariables().isEmpty() ? null : v.getVariables().get(0).getSimpleName();
            Map<String, String> pathParamDefaults = pathParamDefaultStack.isEmpty() ? Collections.emptyMap()
                : pathParamDefaultStack.peek();
            boolean hasPathParamDefault = variableName != null && pathParamDefaults.containsKey(variableName);

            // Second pass: transform annotations (except @DefaultValue)
            for (J.Annotation ann : annotations) {
                String simpleName = getAnnotationSimpleName(ann);
                if (simpleName == null) {
                    newAnnotations.add(ann);
                    continue;
                }

                // Skip @DefaultValue in this pass - handle it separately at the end
                if ("DefaultValue".equals(simpleName) && isJaxRsAnnotation(ann, "DefaultValue")) {
                    continue;
                }

                // Handle mappable annotations
                if (ANNOTATION_MAPPING.containsKey(simpleName) && isJaxRsAnnotation(ann, simpleName)) {
                    String springAnnotation = ANNOTATION_MAPPING.get(simpleName);
                    ResolvedValue resolvedValue = resolveAnnotationStringValue(ann);
                    String value = resolvedValue.value;
                    String valueExpression = resolvedValue.expression != null ? resolvedValue.expression : value;
                    boolean valueIsLiteral = resolvedValue.resolved;

                    // If value is non-literal (constant reference), keep original annotation and add @NeedsReview
                    if (!valueIsLiteral) {
                        newAnnotations.add(ann);  // Keep original JAX-RS annotation unchanged
                        // Also keep @DefaultValue if present (it's part of the JAX-RS annotation set)
                        if (defaultValueAnnotation != null) {
                            // Add space prefix if not first annotation (ensures proper spacing)
                            J.Annotation dvAnn = newAnnotations.isEmpty() ? defaultValueAnnotation
                                : defaultValueAnnotation.withPrefix(Space.format(" "));
                            newAnnotations.add(dvAnn);
                            defaultValueConsumed = true;  // Mark as consumed so we don't get the warning
                            defaultValueKept = true;  // Mark as kept so we don't remove the import
                        }
                        // Only add @NeedsReview if not already present with same reason (idempotency)
                        String reasonKey = "@" + simpleName + " has non-literal value";
                        if (!hasNeedsReviewForReason(annotations, reasonKey)) {
                            J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                "JAX-RS @" + simpleName + " has non-literal value (" + valueExpression + ") - manual migration required. " +
                                "Replace constant with literal value, then re-run migration.")
                                .withPrefix(Space.format(" "));
                            newAnnotations.add(reviewAnn);
                            maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                        }
                        modified = true;
                        // Don't remove JAX-RS import since we're keeping the annotation
                    } else {
                        // Literal value - convert to Spring annotation
                        J.Annotation newAnn = buildSpringAnnotation(springAnnotation, value, defaultValue);
                        newAnnotations.add(newAnn);
                        modified = true;

                        // Add import and remove JAX-RS import
                        maybeAddImport(SPRING_IMPORTS.get(springAnnotation));
                        removeJaxRsImport(simpleName);

                        // Handle defaultValue consumption
                        if (defaultValue != null) {
                            if ("PathVariable".equals(springAnnotation)) {
                                if (hasPathParamDefault) {
                                    // Default handled in method body (only for safe String cases)
                                    defaultValueConsumed = true;
                                } else {
                                    // PathVariable doesn't support defaultValue - add NeedsReview
                                    // Only add @NeedsReview if not already present with same reason (idempotency)
                                    if (!hasNeedsReviewForReason(annotations, "@DefaultValue with @PathParam")) {
                                        J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                            "@DefaultValue with @PathParam: Spring @PathVariable does not support default values. " +
                                            "Consider making the path segment optional or handling the default in code.")
                                            .withPrefix(Space.format(" "));
                                        newAnnotations.add(reviewAnn);
                                        maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                                    }
                                }
                                // Mark consumed so we don't add another warning
                                defaultValueConsumed = true;
                            } else {
                                // Other annotations (RequestParam, RequestHeader, CookieValue) support defaultValue
                                defaultValueConsumed = true;
                            }
                        }
                    }
                }
                // Handle @BeanParam -> @ModelAttribute (with review marker)
                else if ("BeanParam".equals(simpleName) && isJaxRsAnnotation(ann, "BeanParam")) {
                    boolean isMethodParam = getCursor().firstEnclosing(J.MethodDeclaration.class) != null;
                    if (!isMethodParam) {
                        if (!hasNeedsReviewForReason(annotations, "@BeanParam on field")) {
                            J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                "JAX-RS @BeanParam on field requires manual migration. " +
                                "Consider moving to a controller method parameter with @ModelAttribute.");
                            if (!newAnnotations.isEmpty()) {
                                reviewAnn = reviewAnn.withPrefix(Space.format(" "));
                            } else {
                                reviewAnn = reviewAnn.withPrefix(ann.getPrefix());
                            }
                            newAnnotations.add(reviewAnn);
                            maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                        }
                        modified = true;
                        removeJaxRsImport("BeanParam");
                    } else {
                        J.Annotation newAnn = buildSpringAnnotation("ModelAttribute", null, null);
                        newAnnotations.add(newAnn);
                        if (requiresBeanParamReview(v, annotations)) {
                            // Add @NeedsReview because @BeanParam may have complex nested annotations
                            // Only add @NeedsReview if not already present with same reason (idempotency)
                            if (!hasNeedsReviewForReason(annotations, "@BeanParam mapped to @ModelAttribute")) {
                                J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                    "@BeanParam mapped to @ModelAttribute: Review nested @PathParam/@QueryParam/@HeaderParam " +
                                    "annotations in the bean class - they need separate Spring binding configuration.")
                                    .withPrefix(Space.format(" "));
                                newAnnotations.add(reviewAnn);
                                maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                            }
                        }
                        modified = true;

                        maybeAddImport(SPRING_IMPORTS.get("ModelAttribute"));
                        removeJaxRsImport("BeanParam");
                    }
                }
                // Handle @MatrixParam -> @MatrixVariable (requires config to enable matrix vars)
                else if ("MatrixParam".equals(simpleName) && isJaxRsAnnotation(ann, "MatrixParam")) {
                    ResolvedValue resolvedValue = resolveAnnotationStringValue(ann);
                    String valueExpression = resolvedValue.expression != null ? resolvedValue.expression : resolvedValue.value;
                    if (resolvedValue.resolved) {
                        J.Annotation newAnn = buildSpringAnnotation("MatrixVariable", resolvedValue.value, null);
                        newAnnotations.add(newAnn);
                        maybeAddImport(SPRING_IMPORTS.get("MatrixVariable"));
                        removeJaxRsImport("MatrixParam");

                        if (!hasNeedsReviewForReason(annotations, "@MatrixParam")) {
                            String paramName = resolvedValue.value != null ? ("(\"" + resolvedValue.value + "\")") : "";
                            if (!hasMatrixConfig) {
                                J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                    "JAX-RS @MatrixParam" + paramName + " migrated to Spring @MatrixVariable. " +
                                    "Matrix variables are disabled by default in Spring MVC; enable them via WebMvcConfigurer.");
                                if (!newAnnotations.isEmpty()) {
                                    reviewAnn = reviewAnn.withPrefix(Space.format(" "));
                                }
                                newAnnotations.add(reviewAnn);
                                maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                            }
                        }
                    } else {
                        newAnnotations.add(ann);
                        if (!hasNeedsReviewForReason(annotations, "@MatrixParam")) {
                            J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                "JAX-RS @MatrixParam has non-literal value (" + valueExpression + ") - manual migration required. " +
                                "Replace constant with literal value, then re-run migration.");
                            if (!newAnnotations.isEmpty()) {
                                reviewAnn = reviewAnn.withPrefix(Space.format(" "));
                            }
                            newAnnotations.add(reviewAnn);
                            maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                        }
                    }
                    modified = true;
                }
                // Handle @Context -> @NeedsReview (depends on injected type)
                else if ("Context".equals(simpleName) && isJaxRsAnnotation(ann, "core.Context")) {
                    boolean isMethodParam = getCursor().firstEnclosing(J.MethodDeclaration.class) != null;
                    if (isSpringContextInjectableType(v)) {
                        // If this is a field, replace @Context with @Autowired.
                        if (!isMethodParam) {
                            J.Annotation autowired = createAnnotation("Autowired", SPRING_AUTOWIRED, Collections.emptyList())
                                .withPrefix(ann.getPrefix());
                            newAnnotations.add(autowired);
                            maybeAddImport(SPRING_AUTOWIRED);
                        }
                        modified = true;
                        maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                        maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                    } else if (isJaxRsHttpHeadersType(v)) {
                        if (isMethodParam) {
                            J.Annotation newAnn = buildSpringAnnotation("RequestHeader", null, null)
                                .withPrefix(ann.getPrefix());
                            newAnnotations.add(newAnn);
                            v = replaceTypeWithHttpHeaders(v);
                            doAfterVisit(new RemoveImport<>(JAVAX_WS_RS + ".core.HttpHeaders", true));
                            doAfterVisit(new RemoveImport<>(JAKARTA_WS_RS + ".core.HttpHeaders", true));
                            maybeAddImport(SPRING_HTTP_HEADERS);
                            maybeAddImport(SPRING_REQUEST_HEADER);
                            maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                            maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                        } else if (!hasNeedsReviewForReason(annotations, "@Context HttpHeaders on field")) {
                            J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                "JAX-RS @Context HttpHeaders on field requires manual migration. " +
                                "Consider using a controller method parameter with @RequestHeader HttpHeaders.");
                            if (!newAnnotations.isEmpty()) {
                                reviewAnn = reviewAnn.withPrefix(Space.format(" "));
                            } else {
                                reviewAnn = reviewAnn.withPrefix(ann.getPrefix());
                            }
                            newAnnotations.add(reviewAnn);
                            maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                        }
                        modified = true;
                    } else if (isJaxRsUriInfoType(v)) {
                        if (isMethodParam && isUriInfoNeedsReview(v, uriInfoParamNeedsReviewStack.peek())) {
                            if (!hasNeedsReviewForReason(annotations, "@Context UriInfo unsupported")) {
                                J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                    "JAX-RS @Context UriInfo uses unsupported methods; manual migration required. " +
                                    "Consider ServletUriComponentsBuilder or direct HttpServletRequest access.");
                                if (!newAnnotations.isEmpty()) {
                                    reviewAnn = reviewAnn.withPrefix(Space.format(" "));
                                } else {
                                    reviewAnn = reviewAnn.withPrefix(ann.getPrefix());
                                }
                                newAnnotations.add(reviewAnn);
                                maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                            }
                            modified = true;
                            maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                            maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                            continue;
                        }
                        if (isMethodParam) {
                            v = replaceTypeWithUriComponentsBuilder(v);
                            maybeAddImport(SPRING_URI_COMPONENTS_BUILDER);
                            doAfterVisit(new RemoveImport<>(JAVAX_WS_RS + ".core.UriInfo", true));
                            doAfterVisit(new RemoveImport<>(JAKARTA_WS_RS + ".core.UriInfo", true));
                            maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                            maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                        } else if (!hasNeedsReviewForReason(annotations, "@Context UriInfo on field")) {
                            J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                "JAX-RS @Context UriInfo on field requires manual migration. " +
                                "Consider using UriComponentsBuilder as a controller method parameter.");
                            if (!newAnnotations.isEmpty()) {
                                reviewAnn = reviewAnn.withPrefix(Space.format(" "));
                            } else {
                                reviewAnn = reviewAnn.withPrefix(ann.getPrefix());
                            }
                            newAnnotations.add(reviewAnn);
                            maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                            maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                            maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                        }
                        modified = true;
                    } else if (isJaxRsRequestType(v)) {
                        boolean needsReview = isMethodParam
                            ? hasAnyNamedVariable(v, requestParamNeedsReviewStack.peek())
                            : hasAnyNamedVariable(v, requestFieldNeedsReviewStack.peek());
                        if (needsReview) {
                            if (!hasNeedsReviewForReason(annotations, "@Context Request with evaluatePreconditions/selectVariant")) {
                                J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                    "JAX-RS @Context Request uses evaluatePreconditions/selectVariant; manual migration required. " +
                                    "Consider ServletWebRequest#checkNotModified or Spring content negotiation APIs.");
                                if (!newAnnotations.isEmpty()) {
                                    reviewAnn = reviewAnn.withPrefix(Space.format(" "));
                                } else {
                                    reviewAnn = reviewAnn.withPrefix(ann.getPrefix());
                                }
                                newAnnotations.add(reviewAnn);
                                maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                            }
                            modified = true;
                            maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                            maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                            continue;
                        }
                        String servletRequestFqn = resolveServletRequestFqn(v);
                        if (isMethodParam) {
                            v = replaceTypeWithHttpServletRequest(v, servletRequestFqn);
                            maybeAddImport(servletRequestFqn);
                            doAfterVisit(new RemoveImport<>(JAVAX_WS_RS + ".core.Request", true));
                            doAfterVisit(new RemoveImport<>(JAKARTA_WS_RS + ".core.Request", true));
                            maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                            maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                        } else {
                            J.Annotation autowired = createAnnotation("Autowired", SPRING_AUTOWIRED, Collections.emptyList())
                                .withPrefix(ann.getPrefix());
                            newAnnotations.add(autowired);
                            v = replaceTypeWithHttpServletRequest(v, servletRequestFqn);
                            maybeAddImport(SPRING_AUTOWIRED);
                            maybeAddImport(servletRequestFqn);
                            doAfterVisit(new RemoveImport<>(JAVAX_WS_RS + ".core.Request", true));
                            doAfterVisit(new RemoveImport<>(JAKARTA_WS_RS + ".core.Request", true));
                            maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                            maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                        }
                        modified = true;
                    } else if (isJaxRsSecurityContextType(v)) {
                        if (isMethodParam) {
                            String servletRequestFqn = resolveServletRequestFqn(v);
                            v = replaceTypeWithHttpServletRequest(v, servletRequestFqn);
                            maybeAddImport(servletRequestFqn);
                            doAfterVisit(new RemoveImport<>(JAVAX_WS_RS + ".core.SecurityContext", true));
                            doAfterVisit(new RemoveImport<>(JAKARTA_WS_RS + ".core.SecurityContext", true));
                            maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                            maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                        } else {
                            J.Annotation autowired = createAnnotation("Autowired", SPRING_AUTOWIRED, Collections.emptyList())
                                .withPrefix(ann.getPrefix());
                            newAnnotations.add(autowired);
                            String servletRequestFqn = resolveServletRequestFqn(v);
                            v = replaceTypeWithHttpServletRequest(v, servletRequestFqn);
                            maybeAddImport(SPRING_AUTOWIRED);
                            maybeAddImport(servletRequestFqn);
                            doAfterVisit(new RemoveImport<>(JAVAX_WS_RS + ".core.SecurityContext", true));
                            doAfterVisit(new RemoveImport<>(JAKARTA_WS_RS + ".core.SecurityContext", true));
                            maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                            maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                        }
                        modified = true;
                    } else {
                        // Remove @Context and replace with @NeedsReview (JAX-RS deps will be removed)
                        // Only add @NeedsReview if not already present with same reason (idempotency)
                        if (!hasNeedsReviewForReason(annotations, "@Context removed")) {
                            J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                                "JAX-RS @Context removed: Manual migration required. Common mappings: " +
                                "HttpServletRequest/HttpServletResponse/HttpSession/ServletRequest/ServletResponse/ServletContext/ServletConfig (inject directly), " +
                                "HttpHeaders -> @RequestHeader HttpHeaders, UriInfo -> UriComponentsBuilder, SecurityContext -> @AuthenticationPrincipal");
                            // Add space prefix if there are already annotations
                            if (!newAnnotations.isEmpty()) {
                                reviewAnn = reviewAnn.withPrefix(Space.format(" "));
                            }
                            newAnnotations.add(reviewAnn);
                            maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                        }
                        modified = true;

                        maybeRemoveImport(JAVAX_WS_RS + ".core.Context");
                        maybeRemoveImport(JAKARTA_WS_RS + ".core.Context");
                    }
                }
                // Keep non-JAX-RS annotations
                else {
                    newAnnotations.add(ann);
                }
            }

            // Handle @DefaultValue: only remove import if annotation was actually removed
            if (defaultValueAnnotation != null) {
                if (!defaultValueKept) {
                    // Only remove import if we didn't keep the @DefaultValue annotation
                    removeJaxRsImport("DefaultValue");
                }
                modified = true;
                if (!defaultValueConsumed) {
                    // DefaultValue not consumed, add warning
                    // Only add @NeedsReview if not already present with same reason (idempotency)
                    if (!hasNeedsReviewForReason(annotations, "@DefaultValue without")) {
                        J.Annotation reviewAnn = buildNeedsReviewAnnotation(
                            "@DefaultValue without corresponding parameter annotation")
                            .withPrefix(Space.format(" "));
                        newAnnotations.add(reviewAnn);
                        maybeAddImport("com.github.rewrite.ejb.annotations.NeedsReview");
                    }
                }
            }

            if (modified) {
                J.VariableDeclarations updated = v.withLeadingAnnotations(newAnnotations);
                if (newAnnotations.isEmpty() && getCursor().firstEnclosing(J.MethodDeclaration.class) != null) {
                    Space prefix = updated.getPrefix();
                    if (" ".equals(prefix.getWhitespace())) {
                        updated = updated.withPrefix(Space.EMPTY);
                    }
                    TypeTree typeExpr = updated.getTypeExpression();
                    if (typeExpr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) typeExpr;
                        if (" ".equals(ident.getPrefix().getWhitespace())) {
                            updated = updated.withTypeExpression(ident.withPrefix(Space.EMPTY));
                        }
                    } else if (typeExpr instanceof J.FieldAccess) {
                        J.FieldAccess fa = (J.FieldAccess) typeExpr;
                        if (" ".equals(fa.getPrefix().getWhitespace())) {
                            updated = updated.withTypeExpression(fa.withPrefix(Space.EMPTY));
                        }
                    }
                }
                return updated;
            }

            return v;
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

        private boolean isJaxRsAnnotation(J.Annotation annotation, String simpleName) {
            String fqn = getAnnotationFqn(annotation);
            if (fqn == null) {
                String annSimpleName = getAnnotationSimpleName(annotation);
                return simpleName.equals(annSimpleName);
            }
            // Handle special case for @Context which is in .core package
            if (simpleName.contains(".")) {
                return fqn.equals(JAVAX_WS_RS + "." + simpleName) ||
                       fqn.equals(JAKARTA_WS_RS + "." + simpleName);
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

        private boolean hasContextAnnotation(List<J.Annotation> annotations) {
            for (J.Annotation ann : annotations) {
                String simpleName = getAnnotationSimpleName(ann);
                if ("Context".equals(simpleName) && isJaxRsAnnotation(ann, "core.Context")) {
                    return true;
                }
            }
            return false;
        }

        private boolean isUriInfoNeedsReview(J.VariableDeclarations varDecls, Set<String> needsReviewNames) {
            return hasAnyNamedVariable(varDecls, needsReviewNames);
        }

        private boolean hasAnyNamedVariable(J.VariableDeclarations varDecls, Set<String> names) {
            if (names == null || names.isEmpty()) {
                return false;
            }
            for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                if (names.contains(var.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }

        private Set<String> extractUriInfoParamNames(J.MethodDeclaration method) {
            Set<String> paramNames = new HashSet<>();
            for (Statement param : method.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations varDecls = (J.VariableDeclarations) param;
                    if (hasContextAnnotation(varDecls.getLeadingAnnotations()) &&
                        isJaxRsUriInfoType(varDecls)) {
                        for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                            paramNames.add(var.getSimpleName());
                        }
                    }
                }
            }
            return paramNames;
        }

        private Set<String> findUriInfoNamesWithUnsupportedUsage(J.MethodDeclaration method,
                                                                 Set<String> uriInfoNames,
                                                                 ExecutionContext ctx) {
            if (method.getBody() == null || uriInfoNames.isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> needsReview = new HashSet<>();
            new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, ExecutionContext innerCtx) {
                    String name = resolveUriInfoSelectName(m.getSelect(), uriInfoNames);
                    if (name != null) {
                        if (isSupportedUriInfoInvocation(m)) {
                        } else {
                            needsReview.add(name);
                        }
                    }
                    List<Expression> args = m.getArguments();
                    if (args != null) {
                        for (Expression arg : args) {
                            String argName = resolveDirectUriInfoReference(arg, uriInfoNames);
                            if (argName != null) {
                                needsReview.add(argName);
                            }
                        }
                    }
                    return super.visitMethodInvocation(m, innerCtx);
                }

                @Override
                public J.Return visitReturn(J.Return r, ExecutionContext innerCtx) {
                    J.Return ret = super.visitReturn(r, innerCtx);
                    String name = resolveDirectUriInfoReference(ret.getExpression(), uriInfoNames);
                    if (name != null) {
                        needsReview.add(name);
                    }
                    return ret;
                }

                @Override
                public J.Assignment visitAssignment(J.Assignment assignment, ExecutionContext innerCtx) {
                    J.Assignment updated = super.visitAssignment(assignment, innerCtx);
                    String name = resolveDirectUriInfoReference(updated.getAssignment(), uriInfoNames);
                    if (name != null) {
                        needsReview.add(name);
                    }
                    return updated;
                }

                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations multi, ExecutionContext innerCtx) {
                    J.VariableDeclarations vars = super.visitVariableDeclarations(multi, innerCtx);
                    for (J.VariableDeclarations.NamedVariable var : vars.getVariables()) {
                        String name = resolveDirectUriInfoReference(var.getInitializer(), uriInfoNames);
                        if (name != null) {
                            needsReview.add(name);
                        }
                    }
                    return vars;
                }
            }.visit(method.getBody(), ctx);
            return needsReview;
        }

        private String resolveUriInfoSelectName(Expression select, Set<String> uriInfoNames) {
            if (select == null) {
                return null;
            }
            if (select instanceof J.Parentheses) {
                J tree = ((J.Parentheses<?>) select).getTree();
                if (tree instanceof Expression) {
                    return resolveUriInfoSelectName((Expression) tree, uriInfoNames);
                }
                return null;
            }
            String selectText = select.printTrimmed();
            for (String name : uriInfoNames) {
                if (selectText.equals(name) || selectText.endsWith("." + name)) {
                    return name;
                }
            }
            if (select instanceof J.Identifier) {
                String name = ((J.Identifier) select).getSimpleName();
                return uriInfoNames.contains(name) ? name : null;
            }
            if (select instanceof J.FieldAccess) {
                String name = ((J.FieldAccess) select).getSimpleName();
                return uriInfoNames.contains(name) ? name : null;
            }
            return null;
        }

        private String resolveDirectUriInfoReference(Expression expr, Set<String> uriInfoNames) {
            if (expr == null) {
                return null;
            }
            if (expr instanceof J.Parentheses) {
                J tree = ((J.Parentheses<?>) expr).getTree();
                if (tree instanceof Expression) {
                    return resolveDirectUriInfoReference((Expression) tree, uriInfoNames);
                }
                return null;
            }
            if (expr instanceof J.Identifier) {
                String name = ((J.Identifier) expr).getSimpleName();
                return uriInfoNames.contains(name) ? name : null;
            }
            if (expr instanceof J.FieldAccess) {
                String name = ((J.FieldAccess) expr).getSimpleName();
                return uriInfoNames.contains(name) ? name : null;
            }
            return null;
        }

        private Set<String> extractSecurityContextFieldNames(J.ClassDeclaration classDecl) {
            Set<String> fieldNames = new HashSet<>();
            if (classDecl.getBody() == null) {
                return fieldNames;
            }
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations varDecls = (J.VariableDeclarations) stmt;
                    if (hasContextAnnotation(varDecls.getLeadingAnnotations()) &&
                        isJaxRsSecurityContextType(varDecls)) {
                        for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                            fieldNames.add(var.getSimpleName());
                        }
                    }
                }
            }
            return fieldNames;
        }

        private Set<String> extractRequestFieldNeedsReview(J.ClassDeclaration classDecl, String className, ExecutionContext ctx) {
            Set<String> requestFieldNames = new HashSet<>();
            if (classDecl.getBody() == null) {
                return Collections.emptySet();
            }
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations varDecls = (J.VariableDeclarations) stmt;
                    if (hasContextAnnotation(varDecls.getLeadingAnnotations()) &&
                        isJaxRsRequestType(varDecls)) {
                        for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                            requestFieldNames.add(var.getSimpleName());
                        }
                    }
                }
            }
            if (requestFieldNames.isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> needsReview = new HashSet<>();
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    needsReview.addAll(findRequestNamesWithUnsupportedUsage((J.MethodDeclaration) stmt, requestFieldNames, className, ctx));
                }
            }
            return needsReview;
        }

        private Set<String> extractRequestParamNeedsReview(J.MethodDeclaration method, ExecutionContext ctx) {
            Set<String> requestParamNames = new HashSet<>();
            for (Statement param : method.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations varDecls = (J.VariableDeclarations) param;
                    if (hasContextAnnotation(varDecls.getLeadingAnnotations()) &&
                        isJaxRsRequestType(varDecls)) {
                        for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                            requestParamNames.add(var.getSimpleName());
                        }
                    }
                }
            }
            if (requestParamNames.isEmpty()) {
                return Collections.emptySet();
            }
            String className = classNameStack.peek();
            return findRequestNamesWithUnsupportedUsage(method, requestParamNames, className, ctx);
        }

        private Set<String> findRequestNamesWithUnsupportedUsage(J.MethodDeclaration method,
                                                                 Set<String> requestNames,
                                                                 String className,
                                                                 ExecutionContext ctx) {
            if (method.getBody() == null || requestNames.isEmpty()) {
                return Collections.emptySet();
            }
            Set<String> needsReview = new HashSet<>();
            new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, ExecutionContext innerCtx) {
                    J.MethodInvocation mi = super.visitMethodInvocation(m, innerCtx);
                    if (!REQUEST_UNSUPPORTED_METHODS.contains(mi.getSimpleName())) {
                        return mi;
                    }
                    String requestName = resolveRequestSelectName(mi.getSelect(), requestNames, className);
                    if (requestName != null) {
                        needsReview.add(requestName);
                    }
                    return mi;
                }
            }.visit(method.getBody(), ctx);
            return needsReview;
        }

        private String resolveRequestSelectName(Expression select, Set<String> requestNames, String className) {
            if (select instanceof J.Identifier) {
                String name = ((J.Identifier) select).getSimpleName();
                return requestNames.contains(name) ? name : null;
            }
            if (select instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) select;
                String name = fa.getSimpleName();
                if (requestNames.contains(name) && isThisOrClassQualifier(fa.getTarget(), className)) {
                    return name;
                }
            }
            return null;
        }

        private Set<String> extractHttpHeadersFieldNames(J.ClassDeclaration classDecl) {
            Set<String> fieldNames = new HashSet<>();
            if (classDecl.getBody() == null) {
                return fieldNames;
            }
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations varDecls = (J.VariableDeclarations) stmt;
                    if (hasContextAnnotation(varDecls.getLeadingAnnotations()) &&
                        isJaxRsHttpHeadersType(varDecls)) {
                        for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                            fieldNames.add(var.getSimpleName());
                        }
                    }
                }
            }
            return fieldNames;
        }

        private boolean isThisOrClassQualifier(Expression target) {
            if (target instanceof J.Identifier) {
                String name = ((J.Identifier) target).getSimpleName();
                return "this".equals(name) || "super".equals(name) || name.equals(classNameStack.peek());
            }
            if (target instanceof J.FieldAccess) {
                String name = ((J.FieldAccess) target).getSimpleName();
                return "this".equals(name) || "super".equals(name) || name.equals(classNameStack.peek());
            }
            return false;
        }

        private boolean isOnTrackedUriInfo(J.MethodInvocation mi) {
            Set<String> autoNames = uriInfoParamAutoNamesStack.peek();
            if (autoNames == null || autoNames.isEmpty()) {
                return false;
            }
            Expression select = mi.getSelect();
            if (select instanceof J.Identifier) {
                String name = ((J.Identifier) select).getSimpleName();
                return autoNames.contains(name);
            }
            if (select instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) select;
                String name = fa.getSimpleName();
                return autoNames.contains(name) && isThisOrClassQualifier(fa.getTarget());
            }
            return false;
        }

        private boolean isSupportedUriInfoInvocation(J.MethodInvocation mi) {
            if (!URI_INFO_SUPPORTED_METHODS.contains(mi.getSimpleName())) {
                return false;
            }
            List<Expression> args = mi.getArguments();
            if (args == null || args.isEmpty()) {
                return true;
            }
            for (Expression arg : args) {
                if (!(arg instanceof J.Empty)) {
                    return false;
                }
            }
            return true;
        }

        private String uriInfoTemplateFor(String methodName) {
            if ("getRequestUri".equals(methodName)) {
                return "#{any()}.build().toUri()";
            }
            return "#{any()}.build().getPath()";
        }

        private boolean isThisOrClassQualifier(Expression target, String className) {
            if (target instanceof J.Identifier) {
                String name = ((J.Identifier) target).getSimpleName();
                return "this".equals(name) || "super".equals(name) || name.equals(className);
            }
            if (target instanceof J.FieldAccess) {
                String name = ((J.FieldAccess) target).getSimpleName();
                return "this".equals(name) || "super".equals(name) || name.equals(className);
            }
            return false;
        }

        private boolean isOnTrackedHttpHeaders(J.MethodInvocation mi) {
            Set<String> httpHeadersNames = httpHeadersNamesStack.peek();
            if (httpHeadersNames == null || httpHeadersNames.isEmpty()) {
                return false;
            }
            Expression select = mi.getSelect();
            if (select instanceof J.Identifier) {
                String name = ((J.Identifier) select).getSimpleName();
                return httpHeadersNames.contains(name);
            }
            if (select instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) select;
                String name = fa.getSimpleName();
                return httpHeadersNames.contains(name) && isThisOrClassQualifier(fa.getTarget());
            }
            return false;
        }

        private String getAnnotationStringValue(J.Annotation annotation) {
            ResolvedValue resolved = resolveAnnotationStringValue(annotation);
            if (resolved.value != null) {
                return resolved.value;
            }
            return resolved.expression;
        }

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
                }
            }
            return annotation.getArguments().get(0).print(getCursor()).trim();
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

        private boolean isSpringContextInjectableType(J.VariableDeclarations varDecls) {
            JavaType type = varDecls.getType();
            for (String fqn : CONTEXT_FQNS) {
                if (TypeUtils.isOfClassType(type, fqn)) {
                    return true;
                }
            }
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }
            for (String fqn : CONTEXT_FQNS) {
                if (TypeUtils.isOfClassType(typeExpr.getType(), fqn)) {
                    return true;
                }
            }
            if (typeExpr instanceof J.Identifier) {
                String name = ((J.Identifier) typeExpr).getSimpleName();
                return "HttpServletRequest".equals(name) ||
                    "HttpServletResponse".equals(name) ||
                    "HttpSession".equals(name) ||
                    "ServletRequest".equals(name) ||
                    "ServletResponse".equals(name) ||
                    "ServletContext".equals(name) ||
                    "ServletConfig".equals(name);
            }
            if (typeExpr instanceof J.FieldAccess) {
                String name = ((J.FieldAccess) typeExpr).getSimpleName();
                return "HttpServletRequest".equals(name) ||
                    "HttpServletResponse".equals(name) ||
                    "HttpSession".equals(name) ||
                    "ServletRequest".equals(name) ||
                    "ServletResponse".equals(name) ||
                    "ServletContext".equals(name) ||
                    "ServletConfig".equals(name);
            }
            return false;
        }

        private boolean isJaxRsHttpHeadersType(J.VariableDeclarations varDecls) {
            JavaType type = varDecls.getType();
            for (String fqn : CONTEXT_HTTP_HEADERS_FQNS) {
                if (TypeUtils.isOfClassType(type, fqn)) {
                    return true;
                }
            }
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }
            for (String fqn : CONTEXT_HTTP_HEADERS_FQNS) {
                if (TypeUtils.isOfClassType(typeExpr.getType(), fqn)) {
                    return true;
                }
            }
            if (typeExpr instanceof J.Identifier) {
                return "HttpHeaders".equals(((J.Identifier) typeExpr).getSimpleName());
            }
            if (typeExpr instanceof J.FieldAccess) {
                return "HttpHeaders".equals(((J.FieldAccess) typeExpr).getSimpleName());
            }
            return false;
        }

        private boolean isJaxRsUriInfoType(J.VariableDeclarations varDecls) {
            JavaType type = varDecls.getType();
            for (String fqn : CONTEXT_URI_INFO_FQNS) {
                if (TypeUtils.isOfClassType(type, fqn)) {
                    return true;
                }
            }
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }
            for (String fqn : CONTEXT_URI_INFO_FQNS) {
                if (TypeUtils.isOfClassType(typeExpr.getType(), fqn)) {
                    return true;
                }
            }
            if (typeExpr instanceof J.Identifier) {
                return "UriInfo".equals(((J.Identifier) typeExpr).getSimpleName());
            }
            if (typeExpr instanceof J.FieldAccess) {
                return "UriInfo".equals(((J.FieldAccess) typeExpr).getSimpleName());
            }
            return false;
        }

        private boolean isJaxRsRequestType(J.VariableDeclarations varDecls) {
            JavaType type = varDecls.getType();
            for (String fqn : CONTEXT_REQUEST_FQNS) {
                if (TypeUtils.isOfClassType(type, fqn)) {
                    return true;
                }
            }
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }
            for (String fqn : CONTEXT_REQUEST_FQNS) {
                if (TypeUtils.isOfClassType(typeExpr.getType(), fqn)) {
                    return true;
                }
            }
            if (typeExpr instanceof J.Identifier) {
                return "Request".equals(((J.Identifier) typeExpr).getSimpleName());
            }
            if (typeExpr instanceof J.FieldAccess) {
                return "Request".equals(((J.FieldAccess) typeExpr).getSimpleName());
            }
            return false;
        }

        private boolean isJaxRsSecurityContextType(J.VariableDeclarations varDecls) {
            JavaType type = varDecls.getType();
            for (String fqn : CONTEXT_SECURITY_CONTEXT_FQNS) {
                if (TypeUtils.isOfClassType(type, fqn)) {
                    return true;
                }
            }
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }
            for (String fqn : CONTEXT_SECURITY_CONTEXT_FQNS) {
                if (TypeUtils.isOfClassType(typeExpr.getType(), fqn)) {
                    return true;
                }
            }
            if (typeExpr instanceof J.Identifier) {
                return "SecurityContext".equals(((J.Identifier) typeExpr).getSimpleName());
            }
            if (typeExpr instanceof J.FieldAccess) {
                return "SecurityContext".equals(((J.FieldAccess) typeExpr).getSimpleName());
            }
            return false;
        }

        private String resolveServletRequestFqn(J.VariableDeclarations varDecls) {
            if (isJakartaSecurityContextType(varDecls) || isJakartaRequestType(varDecls)) {
                return JAKARTA_HTTP_SERVLET_REQUEST;
            }
            return JAVAX_HTTP_SERVLET_REQUEST;
        }

        private boolean isJakartaSecurityContextType(J.VariableDeclarations varDecls) {
            JavaType type = varDecls.getType();
            if (TypeUtils.isOfClassType(type, JAKARTA_WS_RS + ".core.SecurityContext")) {
                return true;
            }
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr != null && TypeUtils.isOfClassType(typeExpr.getType(), JAKARTA_WS_RS + ".core.SecurityContext")) {
                return true;
            }
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            if (cu != null) {
                for (J.Import imp : cu.getImports()) {
                    if ((JAKARTA_WS_RS + ".core.SecurityContext").equals(imp.getTypeName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean isJakartaRequestType(J.VariableDeclarations varDecls) {
            JavaType type = varDecls.getType();
            if (TypeUtils.isOfClassType(type, JAKARTA_WS_RS + ".core.Request")) {
                return true;
            }
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr != null && TypeUtils.isOfClassType(typeExpr.getType(), JAKARTA_WS_RS + ".core.Request")) {
                return true;
            }
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            if (cu != null) {
                for (J.Import imp : cu.getImports()) {
                    if ((JAKARTA_WS_RS + ".core.Request").equals(imp.getTypeName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        private J.VariableDeclarations replaceTypeWithHttpHeaders(J.VariableDeclarations varDecls) {
            TypeTree typeExpr = varDecls.getTypeExpression();
            JavaType.ShallowClass httpHeadersType = JavaType.ShallowClass.build(SPRING_HTTP_HEADERS);
            TypeTree updatedTypeExpr = typeExpr;
            if (typeExpr instanceof J.Identifier) {
                updatedTypeExpr = ((J.Identifier) typeExpr).withSimpleName("HttpHeaders").withType(httpHeadersType);
            } else if (typeExpr instanceof J.FieldAccess) {
                updatedTypeExpr = new J.Identifier(
                    Tree.randomId(),
                    typeExpr.getPrefix(),
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "HttpHeaders",
                    httpHeadersType,
                    null
                );
            }
            List<J.VariableDeclarations.NamedVariable> updatedVars = new ArrayList<>();
            for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                JavaType.Variable varType = var.getVariableType();
                if (varType != null) {
                    varType = varType.withType(httpHeadersType);
                }
                updatedVars.add(var.withType(httpHeadersType).withVariableType(varType));
            }
            return varDecls.withType(httpHeadersType)
                .withTypeExpression(updatedTypeExpr)
                .withVariables(updatedVars);
        }

        private boolean isBeanParamField(J.VariableDeclarations varDecls) {
            if (!Boolean.TRUE.equals(beanParamClassStack.peek())) {
                return false;
            }
            return getCursor().firstEnclosing(J.MethodDeclaration.class) == null;
        }

        private J.VariableDeclarations stripBeanParamFieldAnnotations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean modified = false;
            for (J.Annotation ann : varDecls.getLeadingAnnotations()) {
                String simpleName = getAnnotationSimpleName(ann);
                if (simpleName == null) {
                    newAnnotations.add(ann);
                    continue;
                }
                if (isJaxRsAnnotation(ann, simpleName) &&
                    ("QueryParam".equals(simpleName) || "FormParam".equals(simpleName) || "DefaultValue".equals(simpleName))) {
                    removeJaxRsImport(simpleName);
                    modified = true;
                    continue;
                }
                newAnnotations.add(ann);
            }
            if (modified) {
                J.VariableDeclarations updated = varDecls.withLeadingAnnotations(newAnnotations);
                Space prefix = varDecls.getPrefix();
                String whitespace = prefix.getWhitespace();
                String cleaned = whitespace.replaceAll("\\n\\s*\\n", "\n");
                if (!cleaned.equals(whitespace)) {
                    updated = updated.withPrefix(prefix.withWhitespace(cleaned));
                } else if (newAnnotations.isEmpty()) {
                    String indent = prefix.getIndent();
                    updated = updated.withPrefix(prefix.withWhitespace("\n" + indent));
                }
                updated = autoFormat(updated, ctx);
                return normalizeBeanParamFieldWhitespace(updated);
            }
            return varDecls;
        }

        private J.VariableDeclarations normalizeBeanParamFieldWhitespace(J.VariableDeclarations varDecls) {
            J.VariableDeclarations updated = varDecls;
            TypeTree typeExpr = updated.getTypeExpression();
            if (typeExpr instanceof J) {
                Space typePrefix = ((J) typeExpr).getPrefix();
                String whitespace = typePrefix.getWhitespace();
                String normalized = whitespace.replaceFirst("^(\\s*\\n)+", "");
                if (!normalized.equals(whitespace)) {
                    updated = updated.withTypeExpression((TypeTree) ((J) typeExpr).withPrefix(typePrefix.withWhitespace(normalized)));
                }
            }

            List<J.VariableDeclarations.NamedVariable> variables = updated.getVariables();
            if (!variables.isEmpty()) {
                boolean changed = false;
                List<J.VariableDeclarations.NamedVariable> normalizedVars = new ArrayList<>(variables.size());
                for (J.VariableDeclarations.NamedVariable var : variables) {
                    Space varPrefix = var.getPrefix();
                    String whitespace = varPrefix.getWhitespace();
                    String normalized = whitespace.replaceFirst("^(\\s*\\n)+", "");
                    if (!normalized.equals(whitespace)) {
                        normalizedVars.add(var.withPrefix(varPrefix.withWhitespace(normalized)));
                        changed = true;
                    } else {
                        normalizedVars.add(var);
                    }
                }
                if (changed) {
                    updated = updated.withVariables(normalizedVars);
                }
            }
            return updated;
        }

        private J.VariableDeclarations replaceTypeWithUriComponentsBuilder(J.VariableDeclarations varDecls) {
            TypeTree typeExpr = varDecls.getTypeExpression();
            JavaType.ShallowClass uriBuilderType = JavaType.ShallowClass.build(SPRING_URI_COMPONENTS_BUILDER);
            TypeTree updatedTypeExpr = typeExpr;
            if (typeExpr instanceof J.Identifier) {
                updatedTypeExpr = ((J.Identifier) typeExpr).withSimpleName("UriComponentsBuilder").withType(uriBuilderType);
            } else if (typeExpr instanceof J.FieldAccess) {
                updatedTypeExpr = new J.Identifier(
                    Tree.randomId(),
                    typeExpr.getPrefix(),
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "UriComponentsBuilder",
                    uriBuilderType,
                    null
                );
            }
            List<J.VariableDeclarations.NamedVariable> updatedVars = new ArrayList<>();
            for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                JavaType.Variable varType = var.getVariableType();
                if (varType != null) {
                    varType = varType.withType(uriBuilderType);
                }
                updatedVars.add(var.withType(uriBuilderType).withVariableType(varType));
            }
            return varDecls.withType(uriBuilderType)
                .withTypeExpression(updatedTypeExpr)
                .withVariables(updatedVars);
        }

        private J.VariableDeclarations replaceTypeWithHttpServletRequest(J.VariableDeclarations varDecls, String servletRequestFqn) {
            TypeTree typeExpr = varDecls.getTypeExpression();
            JavaType.ShallowClass servletRequestType = JavaType.ShallowClass.build(servletRequestFqn);
            TypeTree updatedTypeExpr = typeExpr;
            if (typeExpr instanceof J.Identifier) {
                updatedTypeExpr = ((J.Identifier) typeExpr).withSimpleName("HttpServletRequest").withType(servletRequestType);
            } else if (typeExpr instanceof J.FieldAccess) {
                updatedTypeExpr = new J.Identifier(
                    Tree.randomId(),
                    typeExpr.getPrefix(),
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "HttpServletRequest",
                    servletRequestType,
                    null
                );
            }
            List<J.VariableDeclarations.NamedVariable> updatedVars = new ArrayList<>();
            for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                JavaType.Variable varType = var.getVariableType();
                if (varType != null) {
                    varType = varType.withType(servletRequestType);
                }
                updatedVars.add(var.withType(servletRequestType).withVariableType(varType));
            }
            return varDecls.withType(servletRequestType)
                .withTypeExpression(updatedTypeExpr)
                .withVariables(updatedVars);
        }

        private boolean requiresBeanParamReview(J.VariableDeclarations varDecls, List<J.Annotation> annotations) {
            if (hasNeedsReviewForReason(annotations, "@BeanParam mapped to @ModelAttribute")) {
                return false;
            }
            BeanParamInfo info = findBeanParamInfo(varDecls);
            if (info != null) {
                return !info.isSafeForModelAttribute();
            }
            String typeName = getSimpleTypeName(varDecls.getTypeExpression());
            if (typeName == null) {
                return true;
            }
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            if (cu == null) {
                return true;
            }
            J.ClassDeclaration beanClass = findClassBySimpleName(cu, typeName);
            if (beanClass == null) {
                return true;
            }
            return classRequiresBeanParamReview(beanClass);
        }

        @Nullable
        private BeanParamInfo findBeanParamInfo(J.VariableDeclarations varDecls) {
            JavaType type = varDecls.getType();
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
            if (fq == null && type instanceof JavaType.Parameterized) {
                fq = ((JavaType.Parameterized) type).getType();
            }
            if (fq != null) {
                return beanParamInfoByFqn.get(fq.getFullyQualifiedName());
            }
            return null;
        }

        private String getSimpleTypeName(TypeTree typeExpr) {
            if (typeExpr instanceof J.Identifier) {
                return ((J.Identifier) typeExpr).getSimpleName();
            }
            if (typeExpr instanceof J.FieldAccess) {
                return ((J.FieldAccess) typeExpr).getSimpleName();
            }
            return null;
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

        private boolean hasBeanParamRelevantAnnotation(List<J.Annotation> annotations) {
            for (J.Annotation ann : annotations) {
                String simpleName = getAnnotationSimpleName(ann);
                if (simpleName == null) {
                    continue;
                }
                if ("Context".equals(simpleName) && isJaxRsAnnotation(ann, "core.Context")) {
                    return true;
                }
                if (BEAN_PARAM_RELEVANT_ANNOTATIONS.contains(simpleName) && isJaxRsAnnotation(ann, simpleName)) {
                    return true;
                }
            }
            return false;
        }

        private boolean classRequiresBeanParamReview(J.ClassDeclaration classDecl) {
            return !analyzeBeanParamClass(classDecl).isSafeForModelAttribute();
        }

        private BeanParamInfo analyzeBeanParamClass(J.ClassDeclaration classDecl) {
            if (classDecl.getBody() == null) {
                return new BeanParamInfo(false);
            }
            boolean safeForModelAttribute = true;
            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations fields = (J.VariableDeclarations) stmt;
                    if (hasUnsupportedBeanParamAnnotations(fields)) {
                        safeForModelAttribute = false;
                        break;
                    }
                } else if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                    if (hasBeanParamRelevantAnnotation(method.getLeadingAnnotations())) {
                        safeForModelAttribute = false;
                        break;
                    }
                    for (Statement param : method.getParameters()) {
                        if (param instanceof J.VariableDeclarations) {
                            if (hasBeanParamRelevantAnnotation(((J.VariableDeclarations) param).getLeadingAnnotations())) {
                                safeForModelAttribute = false;
                                break;
                            }
                        }
                    }
                }
                if (!safeForModelAttribute) {
                    break;
                }
            }
            return new BeanParamInfo(safeForModelAttribute);
        }

        private boolean hasUnsupportedBeanParamAnnotations(J.VariableDeclarations fields) {
            for (J.Annotation ann : fields.getLeadingAnnotations()) {
                String simpleName = getAnnotationSimpleName(ann);
                if (simpleName == null) {
                    continue;
                }
                if ("Context".equals(simpleName) && isJaxRsAnnotation(ann, "core.Context")) {
                    return true;
                }
                if (!BEAN_PARAM_RELEVANT_ANNOTATIONS.contains(simpleName) || !isJaxRsAnnotation(ann, simpleName)) {
                    continue;
                }
                if ("QueryParam".equals(simpleName) || "FormParam".equals(simpleName)) {
                    if (ann.getArguments() != null && !ann.getArguments().isEmpty()) {
                        String value = extractAnnotationLiteralValue(ann);
                        if (value == null) {
                            return true;
                        }
                        for (J.VariableDeclarations.NamedVariable var : fields.getVariables()) {
                            if (!value.equals(var.getSimpleName())) {
                                return true;
                            }
                        }
                    }
                } else {
                    return true;
                }
            }
            return false;
        }

        @Nullable
        private String extractAnnotationLiteralValue(J.Annotation ann) {
            if (ann.getArguments() == null || ann.getArguments().isEmpty()) {
                return null;
            }
            Expression arg = ann.getArguments().get(0);
            if (arg instanceof J.Literal) {
                Object value = ((J.Literal) arg).getValue();
                return value != null ? value.toString() : null;
            }
            if (arg instanceof J.Assignment) {
                J.Assignment assignment = (J.Assignment) arg;
                Expression value = assignment.getAssignment();
                if (value instanceof J.Literal) {
                    Object literal = ((J.Literal) value).getValue();
                    return literal != null ? literal.toString() : null;
                }
            }
            return null;
        }

        private boolean isBeanParamClass(J.ClassDeclaration classDecl) {
            String fqn = getFullyQualifiedClassName(classDecl);
            return fqn != null && beanParamTypeFqns.contains(fqn);
        }

        private boolean isSafeBeanParamClass(J.ClassDeclaration classDecl) {
            String fqn = getFullyQualifiedClassName(classDecl);
            if (fqn == null || !beanParamTypeFqns.contains(fqn)) {
                return false;
            }
            BeanParamInfo info = beanParamInfoByFqn.get(fqn);
            if (info != null) {
                return info.isSafeForModelAttribute();
            }
            return analyzeBeanParamClass(classDecl).isSafeForModelAttribute();
        }

        @Nullable
        private String getFullyQualifiedClassName(J.ClassDeclaration classDecl) {
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            if (cu == null) {
                return null;
            }
            String packageName = cu.getPackageDeclaration() != null
                ? cu.getPackageDeclaration().getExpression().toString()
                : "";
            List<String> classNames = new ArrayList<>();
            classNames.add(classDecl.getSimpleName());
            Cursor cursor = getCursor();
            while (cursor != null) {
                Object value = cursor.getValue();
                if (value instanceof J.ClassDeclaration && value != classDecl) {
                    classNames.add(0, ((J.ClassDeclaration) value).getSimpleName());
                }
                cursor = cursor.getParent();
            }
            String className = String.join("$", classNames);
            return packageName.isEmpty() ? className : packageName + "." + className;
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

        private J.Annotation buildSpringAnnotation(String annotationName, String value, String defaultValue) {
            String fqn = SPRING_IMPORTS.get(annotationName);
            if (fqn == null) {
                fqn = "org.springframework.web.bind.annotation." + annotationName;
            }

            List<Expression> args = new ArrayList<>();

            if (value != null) {
                // For RequestParam with defaultValue, use name= instead of value=
                if (defaultValue != null && ("RequestParam".equals(annotationName) || "RequestHeader".equals(annotationName) || "CookieValue".equals(annotationName))) {
                    args.add(createAssignment("name", createStringLiteral(value)));
                    args.add(createAssignment("defaultValue", createStringLiteral(defaultValue)).withPrefix(Space.format(" ")));
                } else {
                    args.add(createStringLiteral(value));
                }
            } else if (defaultValue != null && !"PathVariable".equals(annotationName)) {
                // Only defaultValue, no name
                args.add(createAssignment("defaultValue", createStringLiteral(defaultValue)));
            }

            return createAnnotation(annotationName, fqn, args);
        }

        private J.Annotation buildNeedsReviewAnnotation(String reason) {
            List<Expression> args = new ArrayList<>();
            args.add(createAssignment("reason", createStringLiteral(reason.replace("\"", "\\\""))));
            args.add(createAssignment("category", createCategoryExpression("MANUAL_MIGRATION")).withPrefix(Space.format(" ")));
            return createAnnotation("NeedsReview", "com.github.rewrite.ejb.annotations.NeedsReview", args);
        }

        private J.Annotation createAnnotation(String simpleName, String fqn, List<Expression> args) {
            JContainer<Expression> argsContainer = null;
            if (!args.isEmpty()) {
                List<JRightPadded<Expression>> paddedArgs = new ArrayList<>();
                for (int i = 0; i < args.size(); i++) {
                    Expression arg = args.get(i);
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
                    simpleName,
                    JavaType.ShallowClass.build(fqn),
                    null
                ),
                argsContainer
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
                new JLeftPadded<>(Space.format(" "), value.withPrefix(Space.format(" ")), Markers.EMPTY),
                null
            );
        }

        private void removeJaxRsImport(String simpleName) {
            maybeRemoveImport(JAVAX_WS_RS + "." + simpleName);
            maybeRemoveImport(JAKARTA_WS_RS + "." + simpleName);
        }

        private J.FieldAccess createCategoryExpression(String categoryName) {
            String needsReviewFqn = "com.github.rewrite.ejb.annotations.NeedsReview";
            JavaType.ShallowClass needsReviewType = JavaType.ShallowClass.build(needsReviewFqn);
            JavaType.ShallowClass categoryType = JavaType.ShallowClass.build(needsReviewFqn + ".Category");

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
    }

    private static boolean isMethodOnType(J.MethodInvocation method, Cursor cursor, String fqn) {
        JavaType.Method methodType = method.getMethodType();
        if (methodType != null && TypeUtils.isOfClassType(methodType.getDeclaringType(), fqn)) {
            return true;
        }
        Expression select = method.getSelect();
        if (select != null && TypeUtils.isOfClassType(select.getType(), fqn)) {
            return true;
        }
        if (!(select instanceof J.Identifier)) {
            return false;
        }

        String selectName = ((J.Identifier) select).getSimpleName();
        String simpleName = simpleNameFromFqn(fqn);

        J.MethodDeclaration methodDecl = cursor.firstEnclosing(J.MethodDeclaration.class);
        if (methodDecl != null) {
            if (matchesVariableDeclarations(methodDecl.getParameters(), selectName, simpleName, fqn)) {
                return true;
            }
            if (methodDecl.getBody() != null &&
                matchesVariableDeclarations(methodDecl.getBody().getStatements(), selectName, simpleName, fqn)) {
                return true;
            }
        }

        J.ClassDeclaration classDecl = cursor.firstEnclosing(J.ClassDeclaration.class);
        if (classDecl != null && classDecl.getBody() != null &&
            matchesVariableDeclarations(classDecl.getBody().getStatements(), selectName, simpleName, fqn)) {
            return true;
        }

        return false;
    }

    private static boolean matchesVariableDeclarations(List<Statement> statements,
                                                       String selectName,
                                                       String simpleName,
                                                       String fqn) {
        for (Statement statement : statements) {
            if (statement instanceof J.VariableDeclarations) {
                if (matchesVariableDeclaration((J.VariableDeclarations) statement, selectName, simpleName, fqn)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean matchesVariableDeclaration(J.VariableDeclarations declaration,
                                                      String selectName,
                                                      String simpleName,
                                                      String fqn) {
        for (J.VariableDeclarations.NamedVariable variable : declaration.getVariables()) {
            if (!variable.getSimpleName().equals(selectName)) {
                continue;
            }
            String declaredType = typeNameFromTypeExpression(declaration.getTypeExpression());
            return simpleName.equals(declaredType) || fqn.equals(declaredType);
        }
        return false;
    }

    private static String typeNameFromTypeExpression(TypeTree typeExpression) {
        if (typeExpression == null) {
            return null;
        }
        if (typeExpression instanceof J.Identifier) {
            return ((J.Identifier) typeExpression).getSimpleName();
        }
        if (typeExpression instanceof J.FieldAccess) {
            return ((J.FieldAccess) typeExpression).getName().getSimpleName();
        }
        if (typeExpression instanceof J.ParameterizedType) {
            return typeNameFromNameTree(((J.ParameterizedType) typeExpression).getClazz());
        }
        return null;
    }

    private static String typeNameFromNameTree(NameTree nameTree) {
        if (nameTree instanceof J.Identifier) {
            return ((J.Identifier) nameTree).getSimpleName();
        }
        if (nameTree instanceof J.FieldAccess) {
            return ((J.FieldAccess) nameTree).getName().getSimpleName();
        }
        return null;
    }

    private static String simpleNameFromFqn(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
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
