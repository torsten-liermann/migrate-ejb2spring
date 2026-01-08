package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openrewrite.DocumentExample;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.openrewrite.java.Assertions.java;

class MigrateJaxRsParameterAnnotationsTest implements RewriteTest {

    @TempDir
    Path tempDir;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateJaxRsParameterAnnotations("migrate-to-spring-mvc"))
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web"));
    }

    @DocumentExample
    @Test
    void migratePathParam() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.PathParam;

                public class UserResource {
                    public String getUser(@PathParam("id") Long id) {
                        return "user " + id;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.PathVariable;

                public class UserResource {
                    public String getUser(@PathVariable("id") Long id) {
                        return "user " + id;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateQueryParam() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.QueryParam;

                public class SearchResource {
                    public String search(@QueryParam("query") String query) {
                        return "results for " + query;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestParam;

                public class SearchResource {
                    public String search(@RequestParam("query") String query) {
                        return "results for " + query;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateHeaderParam() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.HeaderParam;

                public class ApiResource {
                    public String process(@HeaderParam("Authorization") String auth) {
                        return "auth: " + auth;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestHeader;

                public class ApiResource {
                    public String process(@RequestHeader("Authorization") String auth) {
                        return "auth: " + auth;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateFormParam() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.FormParam;

                public class FormResource {
                    public String submit(@FormParam("username") String username) {
                        return "user: " + username;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestParam;

                public class FormResource {
                    public String submit(@RequestParam("username") String username) {
                        return "user: " + username;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateCookieParam() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.CookieParam;

                public class SessionResource {
                    public String session(@CookieParam("sessionId") String sessionId) {
                        return "session: " + sessionId;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.CookieValue;

                public class SessionResource {
                    public String session(@CookieValue("sessionId") String sessionId) {
                        return "session: " + sessionId;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextHttpServletRequestParameter() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.servlet.http.HttpServletRequest;

                public class ContextResource {
                    public String read(@Context HttpServletRequest request) {
                        return request.getMethod();
                    }
                }
                """,
                """
                import javax.servlet.http.HttpServletRequest;

                public class ContextResource {
                    public String read(HttpServletRequest request) {
                        return request.getMethod();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextServletContextParameter() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.servlet.ServletContext;

                public class ContextResource {
                    public String read(@Context ServletContext context) {
                        return context.getContextPath();
                    }
                }
                """,
                """
                import javax.servlet.ServletContext;

                public class ContextResource {
                    public String read(ServletContext context) {
                        return context.getContextPath();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextServletRequestParameter() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.servlet.ServletRequest;

                public class ContextResource {
                    public String read(@Context ServletRequest request) {
                        return request.getLocalAddr();
                    }
                }
                """,
                """
                import javax.servlet.ServletRequest;

                public class ContextResource {
                    public String read(ServletRequest request) {
                        return request.getLocalAddr();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextHttpSessionParameter() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.servlet.http.HttpSession;

                public class ContextResource {
                    public String read(@Context HttpSession session) {
                        return session.getId();
                    }
                }
                """,
                """
                import javax.servlet.http.HttpSession;

                public class ContextResource {
                    public String read(HttpSession session) {
                        return session.getId();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextHttpSessionFieldToAutowired() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.servlet.http.HttpSession;

                public class ContextResource {
                    @Context
                    private HttpSession session;
                }
                """,
                """
                import javax.servlet.http.HttpSession;

                import org.springframework.beans.factory.annotation.Autowired;

                public class ContextResource {
                    @Autowired
                    private HttpSession session;
                }
                """
            )
        );
    }

    @Test
    void migrateContextHttpServletResponseFieldToAutowired() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.servlet.http.HttpServletResponse;

                public class ContextResource {
                    @Context
                    private HttpServletResponse response;
                }
                """,
                """
                import javax.servlet.http.HttpServletResponse;

                import org.springframework.beans.factory.annotation.Autowired;

                public class ContextResource {
                    @Autowired
                    private HttpServletResponse response;
                }
                """
            )
        );
    }

    @Test
    void migrateContextHttpHeadersParameterToRequestHeader() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.HttpHeaders;

                public class ContextResource {
                    public String read(@Context HttpHeaders headers) {
                        return headers.getRequestHeader("X-Test").toString();
                    }
                }
                """,
                """
                import org.springframework.http.HttpHeaders;
                import org.springframework.web.bind.annotation.RequestHeader;

                public class ContextResource {
                    public String read(@RequestHeader HttpHeaders headers) {
                        return headers.get("X-Test").toString();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextHttpHeadersHeaderStringToGetFirst() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.HttpHeaders;

                public class ContextResource {
                    public String read(@Context HttpHeaders headers) {
                        return headers.getHeaderString("X-Test");
                    }
                }
                """,
                """
                import org.springframework.http.HttpHeaders;
                import org.springframework.web.bind.annotation.RequestHeader;

                public class ContextResource {
                    public String read(@RequestHeader HttpHeaders headers) {
                        return headers.getFirst("X-Test");
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextHttpHeadersLanguageToAcceptLanguage() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.HttpHeaders;

                import java.util.Locale;

                public class ContextResource {
                    public Locale read(@Context HttpHeaders headers) {
                        return headers.getLanguage();
                    }
                }
                """,
                """
                import org.springframework.http.HttpHeaders;
                import org.springframework.web.bind.annotation.RequestHeader;

                import java.util.Locale;

                public class ContextResource {
                    public Locale read(@RequestHeader HttpHeaders headers) {
                        return headers.getAcceptLanguageAsLocales().stream().findFirst().orElse(null);
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextSecurityContextToHttpServletRequest() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.SecurityContext;

                public class ContextResource {
                    public String read(@Context SecurityContext securityContext) {
                        return securityContext.getAuthenticationScheme();
                    }
                }
                """,
                """
                import javax.servlet.http.HttpServletRequest;

                public class ContextResource {
                    public String read(HttpServletRequest securityContext) {
                        return securityContext.getAuthType();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextSecurityContextFieldToAutowiredHttpServletRequest() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.SecurityContext;

                public class ContextResource {
                    @Context
                    private SecurityContext securityContext;

                    public String read() {
                        return securityContext.getAuthenticationScheme();
                    }
                }
                """,
                """
                import org.springframework.beans.factory.annotation.Autowired;

                import javax.servlet.http.HttpServletRequest;

                public class ContextResource {
                    @Autowired
                    private HttpServletRequest securityContext;

                    public String read() {
                        return securityContext.getAuthType();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextUriInfoParameterToUriComponentsBuilder() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    public String read(@Context UriInfo uriInfo) {
                        return uriInfo.getPath();
                    }
                }
                """,
                """
                import org.springframework.web.util.UriComponentsBuilder;

                public class ContextResource {
                    public String read(UriComponentsBuilder uriInfo) {
                        return uriInfo.build().getPath();
                    }
                }
                """
            )
        );
    }

    @Test
    void keepContextUriInfoParameterWithNeedsReviewWhenQueryParametersUsed() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.MultivaluedMap;
                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    public MultivaluedMap<String, String> read(@Context UriInfo uriInfo) {
                        return uriInfo.getQueryParameters();
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                import javax.ws.rs.core.MultivaluedMap;
                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    public MultivaluedMap<String, String> read(@NeedsReview(reason = "JAX-RS @Context UriInfo uses unsupported methods; manual migration required. Consider ServletUriComponentsBuilder or direct HttpServletRequest access.", category = NeedsReview.Category.MANUAL_MIGRATION) UriInfo uriInfo) {
                        return uriInfo.getQueryParameters();
                    }
                }
                """
            )
        );
    }

    @Test
    void keepContextUriInfoParameterWithNeedsReviewWhenBuilderMethodsUsed() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.UriBuilder;
                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    public UriBuilder read(@Context UriInfo uriInfo) {
                        UriBuilder first = uriInfo.getRequestUriBuilder();
                        UriBuilder second = uriInfo.getBaseUriBuilder();
                        return uriInfo.getAbsolutePathBuilder();
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                import javax.ws.rs.core.UriBuilder;
                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    public UriBuilder read(@NeedsReview(reason = "JAX-RS @Context UriInfo uses unsupported methods; manual migration required. Consider ServletUriComponentsBuilder or direct HttpServletRequest access.", category = NeedsReview.Category.MANUAL_MIGRATION) UriInfo uriInfo) {
                        UriBuilder first = uriInfo.getRequestUriBuilder();
                        UriBuilder second = uriInfo.getBaseUriBuilder();
                        return uriInfo.getAbsolutePathBuilder();
                    }
                }
                """
            )
        );
    }

    @Test
    void keepContextUriInfoParameterWithNeedsReviewWhenGetPathWithDecodeUsed() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    public String read(@Context UriInfo uriInfo) {
                        return uriInfo.getPath(true);
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    public String read(@NeedsReview(reason = "JAX-RS @Context UriInfo uses unsupported methods; manual migration required. Consider ServletUriComponentsBuilder or direct HttpServletRequest access.", category = NeedsReview.Category.MANUAL_MIGRATION) UriInfo uriInfo) {
                        return uriInfo.getPath(true);
                    }
                }
                """
            )
        );
    }

    @Test
    void keepContextUriInfoParameterWithNeedsReviewWhenPassedToHelper() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    public String read(@Context UriInfo uriInfo) {
                        return helper(uriInfo);
                    }

                    private String helper(UriInfo uriInfo) {
                        return uriInfo.getPath();
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    public String read(@NeedsReview(reason = "JAX-RS @Context UriInfo uses unsupported methods; manual migration required. Consider ServletUriComponentsBuilder or direct HttpServletRequest access.", category = NeedsReview.Category.MANUAL_MIGRATION) UriInfo uriInfo) {
                        return helper(uriInfo);
                    }

                    private String helper(UriInfo uriInfo) {
                        return uriInfo.getPath();
                    }
                }
                """
            )
        );
    }

    @Test
    void keepContextUriInfoFieldWithNeedsReview() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    @Context
                    private UriInfo uriInfo;

                    public String read() {
                        return uriInfo.getPath();
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                import javax.ws.rs.core.UriInfo;

                public class ContextResource {
                    @NeedsReview(reason = "JAX-RS @Context UriInfo on field requires manual migration. Consider using UriComponentsBuilder as a controller method parameter.", category = NeedsReview.Category.MANUAL_MIGRATION)
                    private UriInfo uriInfo;

                    public String read() {
                        return uriInfo.getPath();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextRequestParameterToHttpServletRequest() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.Request;

                public class ContextResource {
                    public String read(@Context Request request) {
                        return request.getMethod();
                    }
                }
                """,
                """
                import javax.servlet.http.HttpServletRequest;

                public class ContextResource {
                    public String read(HttpServletRequest request) {
                        return request.getMethod();
                    }
                }
                """
            )
        );
    }

    @Test
    void keepContextRequestParameterWithNeedsReviewWhenEvaluatePreconditionsUsed() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.EntityTag;
                import javax.ws.rs.core.Request;
                import javax.ws.rs.core.Response.ResponseBuilder;

                public class ContextResource {
                    public ResponseBuilder read(@Context Request request, EntityTag tag) {
                        return request.evaluatePreconditions(tag);
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                import javax.ws.rs.core.EntityTag;
                import javax.ws.rs.core.Request;
                import javax.ws.rs.core.Response.ResponseBuilder;

                public class ContextResource {
                    public ResponseBuilder read(@NeedsReview(reason = "JAX-RS @Context Request uses evaluatePreconditions/selectVariant; manual migration required. Consider ServletWebRequest#checkNotModified or Spring content negotiation APIs.", category = NeedsReview.Category.MANUAL_MIGRATION) Request request, EntityTag tag) {
                        return request.evaluatePreconditions(tag);
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateContextRequestFieldToAutowiredHttpServletRequest() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.Request;

                public class ContextResource {
                    @Context
                    private Request request;

                    public String read() {
                        return request.getMethod();
                    }
                }
                """,
                """
                import org.springframework.beans.factory.annotation.Autowired;

                import javax.servlet.http.HttpServletRequest;

                public class ContextResource {
                    @Autowired
                    private HttpServletRequest request;

                    public String read() {
                        return request.getMethod();
                    }
                }
                """
            )
        );
    }

    @Test
    void keepContextRequestFieldWithNeedsReviewWhenSelectVariantUsed() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Context;
                import javax.ws.rs.core.Request;
                import javax.ws.rs.core.Variant;

                import java.util.List;

                public class ContextResource {
                    @Context
                    private Request request;

                    public Variant read(List<Variant> variants) {
                        return request.selectVariant(variants);
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                import javax.ws.rs.core.Request;
                import javax.ws.rs.core.Variant;

                import java.util.List;

                public class ContextResource {
                    @NeedsReview(reason = "JAX-RS @Context Request uses evaluatePreconditions/selectVariant; manual migration required. Consider ServletWebRequest#checkNotModified or Spring content negotiation APIs.", category = NeedsReview.Category.MANUAL_MIGRATION)
                    private Request request;

                    public Variant read(List<Variant> variants) {
                        return request.selectVariant(variants);
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMatrixParamToMatrixVariable() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.MatrixParam;

                public class MatrixResource {
                    public String find(@MatrixParam("color") String color) {
                        return color;
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.web.bind.annotation.MatrixVariable;

                public class MatrixResource {
                    public String find(@MatrixVariable("color") @NeedsReview(reason = "JAX-RS @MatrixParam(\\\"color\\\") migrated to Spring @MatrixVariable. Matrix variables are disabled by default in Spring MVC; enable them via WebMvcConfigurer.", category = NeedsReview.Category.MANUAL_MIGRATION) String color) {
                        return color;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMatrixParamWithoutNeedsReviewWhenConfigPresent() {
        rewriteRun(
            java(
                """
                package com.example;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
                import org.springframework.web.util.UrlPathHelper;

                @Configuration
                public class WebConfig implements WebMvcConfigurer {
                    @Override
                    public void configurePathMatch(PathMatchConfigurer configurer) {
                        UrlPathHelper urlPathHelper = new UrlPathHelper();
                        urlPathHelper.setRemoveSemicolonContent(false);
                        configurer.setUrlPathHelper(urlPathHelper);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/WebConfig.java")
            ),
            java(
                """
                package com.example;

                import javax.ws.rs.MatrixParam;

                public class MatrixResource {
                    public String read(@MatrixParam("lang") String lang) {
                        return lang;
                    }
                }
                """,
                """
                package com.example;

                import org.springframework.web.bind.annotation.MatrixVariable;

                public class MatrixResource {
                    public String read(@MatrixVariable("lang") String lang) {
                        return lang;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/MatrixResource.java")
            )
        );
    }

    @Test
    void migrateQueryParamWithDefaultValue() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.QueryParam;
                import javax.ws.rs.DefaultValue;

                public class SearchResource {
                    public String search(@DefaultValue("*") @QueryParam("query") String query) {
                        return "results for " + query;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestParam;

                public class SearchResource {
                    public String search(@RequestParam(name = "query", defaultValue = "*") String query) {
                        return "results for " + query;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateDefaultValueWithLocalConstant() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.QueryParam;
                import javax.ws.rs.DefaultValue;

                public class SearchResource {
                    private static final String DEFAULT_QUERY = "all";

                    public String search(@DefaultValue(DEFAULT_QUERY) @QueryParam("query") String query) {
                        return "results for " + query;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestParam;

                public class SearchResource {
                    private static final String DEFAULT_QUERY = "all";

                    public String search(@RequestParam(name = "query", defaultValue = "all") String query) {
                        return "results for " + query;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMultipleParams() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.PathParam;
                import javax.ws.rs.QueryParam;
                import javax.ws.rs.HeaderParam;

                public class ComplexResource {
                    public String complex(
                            @PathParam("id") Long id,
                            @QueryParam("filter") String filter,
                            @HeaderParam("X-Custom") String custom) {
                        return "id=" + id + ", filter=" + filter + ", custom=" + custom;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RequestHeader;
                import org.springframework.web.bind.annotation.RequestParam;

                public class ComplexResource {
                    public String complex(
                            @PathVariable("id") Long id,
                            @RequestParam("filter") String filter,
                            @RequestHeader("X-Custom") String custom) {
                        return "id=" + id + ", filter=" + filter + ", custom=" + custom;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateBeanParam() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.BeanParam;

                public class ResourceWithBeanParam {
                    public String process(@BeanParam SearchCriteria criteria) {
                        return "processed";
                    }
                }

                class SearchCriteria {
                    private String query;
                    private int page;
                }
                """,
                """
                import org.springframework.web.bind.annotation.ModelAttribute;

                public class ResourceWithBeanParam {
                    public String process(@ModelAttribute SearchCriteria criteria) {
                        return "processed";
                    }
                }

                class SearchCriteria {
                    private String query;
                    private int page;
                }
                """
            )
        );
    }

    @Test
    void migrateBeanParamAcrossFilesWithoutAnnotations() {
        rewriteRun(
            java(
                """
                package com.example;

                import javax.ws.rs.BeanParam;

                public class ResourceWithBeanParam {
                    public String process(@BeanParam SearchCriteria criteria) {
                        return "processed";
                    }
                }
                """,
                """
                package com.example;

                import org.springframework.web.bind.annotation.ModelAttribute;

                public class ResourceWithBeanParam {
                    public String process(@ModelAttribute SearchCriteria criteria) {
                        return "processed";
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/ResourceWithBeanParam.java")
            ),
            java(
                """
                package com.example;

                public class SearchCriteria {
                    private String query;
                    private int page;
                }
                """,
                spec -> spec.path("src/main/java/com/example/SearchCriteria.java")
            )
        );
    }

    @Test
    void migrateBeanParamFieldKeepsNeedsReview() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.BeanParam;

                public class ResourceWithBeanParam {
                    @BeanParam
                    private SearchCriteria criteria;
                }

                class SearchCriteria {
                    private String query;
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;

                public class ResourceWithBeanParam {
                    @NeedsReview(reason = "JAX-RS @BeanParam on field requires manual migration. Consider moving to a controller method parameter with @ModelAttribute.", category = NeedsReview.Category.MANUAL_MIGRATION)
                    private SearchCriteria criteria;
                }

                class SearchCriteria {
                    private String query;
                }
                """
            )
        );
    }

    @Test
    void migrateBeanParamWithInnerClass() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.BeanParam;

                public class ResourceWithBeanParam {
                    public String process(@BeanParam SearchCriteria criteria) {
                        return "processed";
                    }

                    static class SearchCriteria {
                        private String query;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.ModelAttribute;

                public class ResourceWithBeanParam {
                    public String process(@ModelAttribute SearchCriteria criteria) {
                        return "processed";
                    }

                    static class SearchCriteria {
                        private String query;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateBeanParamWithAnnotatedFieldsKeepsNeedsReview() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.BeanParam;
                import javax.ws.rs.QueryParam;

                public class ResourceWithBeanParam {
                    public String process(@BeanParam SearchCriteria criteria) {
                        return "processed";
                    }
                }

                class SearchCriteria {
                    @QueryParam("query")
                    private String query;
                }
                """,
                """
                import org.springframework.web.bind.annotation.ModelAttribute;

                public class ResourceWithBeanParam {
                    public String process(@ModelAttribute SearchCriteria criteria) {
                        return "processed";
                    }
                }

                class SearchCriteria {

                    private String query;
                }
                """
            )
        );
    }

    @Test
    void migrateJakartaNamespace() {
        rewriteRun(
            java(
                """
                import jakarta.ws.rs.PathParam;
                import jakarta.ws.rs.QueryParam;

                public class JakartaResource {
                    public String get(@PathParam("id") Long id, @QueryParam("filter") String filter) {
                        return "id=" + id;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.PathVariable;
                import org.springframework.web.bind.annotation.RequestParam;

                public class JakartaResource {
                    public String get(@PathVariable("id") Long id, @RequestParam("filter") String filter) {
                        return "id=" + id;
                    }
                }
                """
            )
        );
    }

    @Test
    void migratePathParamWithLocalConstant() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.PathParam;

                public class UserResource {
                    private static final String USER_ID = "userId";

                    public String getUser(@PathParam(USER_ID) Long id) {
                        return "user " + id;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.PathVariable;

                public class UserResource {
                    private static final String USER_ID = "userId";

                    public String getUser(@PathVariable("userId") Long id) {
                        return "user " + id;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateQueryParamWithLocalConstantAndDefaultValue() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.QueryParam;
                import javax.ws.rs.DefaultValue;

                public class SearchResource {
                    private static final String QUERY = "query";

                    public String search(@DefaultValue("*") @QueryParam(QUERY) String query) {
                        return "results for " + query;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestParam;

                public class SearchResource {
                    private static final String QUERY = "query";

                    public String search(@RequestParam(name = "query", defaultValue = "*") String query) {
                        return "results for " + query;
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateQueryParamWithConstantInSameFile() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.QueryParam;

                public class SearchResource {
                    public String search(@QueryParam(ParamNames.QUERY) String query) {
                        return "results for " + query;
                    }
                }

                class ParamNames {
                    static final String QUERY = "query";
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestParam;

                public class SearchResource {
                    public String search(@RequestParam("query") String query) {
                        return "results for " + query;
                    }
                }

                class ParamNames {
                    static final String QUERY = "query";
                }
                """
            )
        );
    }

    @Test
    void migrateQueryParamWithInterfaceConstantInSameFile() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.QueryParam;

                public class SearchResource {
                    public String search(@QueryParam(ParamNames.QUERY) String query) {
                        return "results for " + query;
                    }
                }

                interface ParamNames {
                    String QUERY = "query";
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestParam;

                public class SearchResource {
                    public String search(@RequestParam("query") String query) {
                        return "results for " + query;
                    }
                }

                interface ParamNames {
                    String QUERY = "query";
                }
                """
            )
        );
    }

    @Test
    void migratePathParamWithConcatenatedConstant() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.PathParam;

                public class UserResource {
                    private static final String PREFIX = "user";

                    public String getUser(@PathParam(PREFIX + "Id") Long id) {
                        return "user " + id;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.PathVariable;

                public class UserResource {
                    private static final String PREFIX = "user";

                    public String getUser(@PathVariable("userId") Long id) {
                        return "user " + id;
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeIfNoJaxRsAnnotations() {
        rewriteRun(
            java(
                """
                public class PlainService {
                    public String process(String input) {
                        return "processed: " + input;
                    }
                }
                """
            )
        );
    }

    @Test
    void migratePathParamWithDefaultValueAddsNeedsReview() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.PathParam;
                import javax.ws.rs.DefaultValue;

                public class UserResource {
                    public String getUser(@DefaultValue("0") @PathParam("id") Long id) {
                        return "user " + id;
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.web.bind.annotation.PathVariable;

                public class UserResource {
                    public String getUser(@PathVariable("id") @NeedsReview(reason = "@DefaultValue with @PathParam: Spring @PathVariable does not support default values. Consider making the path segment optional or handling the default in code.", category = NeedsReview.Category.MANUAL_MIGRATION) Long id) {
                        return "user " + id;
                    }
                }
                """
            )
        );
    }

    @Test
    void migratePathParamWithDefaultValueAddsFallbackForString() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.PathParam;
                import javax.ws.rs.DefaultValue;

                public class UserResource {
                    public String getUser(@DefaultValue("guest") @PathParam("name") String name) {
                        return name;
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.PathVariable;

                public class UserResource {
                    public String getUser(@PathVariable("name") String name) {
                        if (name == null || name.isEmpty()) {
                            name = "guest";
                        }
                        return name;
                    }
                }
                """
            )
        );
    }

    @Test
    void migratePathParamWithDefaultValueKeepsNeedsReviewWhenCapturedInLambda() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.PathParam;
                import javax.ws.rs.DefaultValue;

                public class UserResource {
                    public String getUser(@DefaultValue("guest") @PathParam("name") String name) {
                        Runnable r = () -> System.out.println(name);
                        r.run();
                        return name;
                    }
                }
                """,
                """
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.web.bind.annotation.PathVariable;

                public class UserResource {
                    public String getUser(@PathVariable("name") @NeedsReview(reason = "@DefaultValue with @PathParam: Spring @PathVariable does not support default values. Consider making the path segment optional or handling the default in code.", category = NeedsReview.Category.MANUAL_MIGRATION) String name) {
                        Runnable r = () -> System.out.println(name);
                        r.run();
                        return name;
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsJaxRsParametersWhenNoProjectYaml() {
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsParameterAnnotations())
                .expectedCyclesThatMakeChanges(0),
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.PathParam;

                @Path("/users")
                public class UserResource {
                    @GET
                    @Path("/{id}")
                    public String get(@PathParam("id") String id) {
                        return id;
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesWhenProjectYamlRequestsSpringMvc() throws IOException {
        Path projectDir = tempDir.resolve("jaxrs-params-project");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        Files.writeString(projectDir.resolve("project.yaml"), """
                jaxrs:
                  strategy: migrate-to-spring-mvc
                """);

        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsParameterAnnotations()),
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.PathParam;

                @Path("/users")
                public class UserResource {
                    @GET
                    @Path("/{id}")
                    public String get(@PathParam("id") String id) {
                        return id;
                    }
                }
                """,
                """
                import javax.ws.rs.Path;

                import javax.ws.rs.GET;

                import org.springframework.web.bind.annotation.PathVariable;

                @Path("/users")
                public class UserResource {
                    @GET
                    @Path("/{id}")
                    public String get(@PathVariable("id") String id) {
                        return id;
                    }
                }
                """,
                spec -> spec.path(projectDir.resolve("src/main/java/com/example/UserResource.java").toString())
            )
        );
    }

    // ========== Edge Case Tests ==========

    @Test
    void resolveNonLiteralPathParamFromImportedConstant() {
        // When @PathParam references a constant from another class, resolve to a literal value
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web"))
                .expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example;
                public class Params {
                    private static final String BASE = "user";
                    public static final String USER_ID = BASE + "Id";
                }
                """,
                spec -> spec.path("src/main/java/com/example/Params.java")
            ),
            java(
                """
                import javax.ws.rs.PathParam;
                import com.example.Params;

                public class UserResource {
                    public String getUser(@PathParam(Params.USER_ID) Long id) {
                        return "user " + id;
                    }
                }
                """,
                """
                import com.example.Params;
                import org.springframework.web.bind.annotation.PathVariable;

                public class UserResource {
                    public String getUser(@PathVariable("userId") Long id) {
                        return "user " + id;
                    }
                }
                """
            )
        );
    }

    @Test
    void resolveNonLiteralQueryParamWithDefaultValue() {
        // When @QueryParam references a constant from another class, resolve to a literal value
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web"))
                .expectedCyclesThatMakeChanges(1),
            java(
                """
                package com.example;
                public class Params {
                    private static final String BASE = "que";
                    public static final String QUERY_PARAM = BASE + "ry";
                }
                """,
                spec -> spec.path("src/main/java/com/example/Params.java")
            ),
            java(
                """
                import javax.ws.rs.QueryParam;
                import javax.ws.rs.DefaultValue;
                import com.example.Params;

                public class SearchResource {
                    public String search(@DefaultValue("*") @QueryParam(Params.QUERY_PARAM) String query) {
                        return "results for " + query;
                    }
                }
                """,
                """
                import com.example.Params;
                import org.springframework.web.bind.annotation.RequestParam;

                public class SearchResource {
                    public String search(@RequestParam(name = "query", defaultValue = "*") String query) {
                        return "results for " + query;
                    }
                }
                """
            )
        );
    }
}
