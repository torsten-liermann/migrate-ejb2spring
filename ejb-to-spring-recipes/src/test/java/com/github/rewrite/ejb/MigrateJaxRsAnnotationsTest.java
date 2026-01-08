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

class MigrateJaxRsAnnotationsTest implements RewriteTest {

    @TempDir
    Path tempDir;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateJaxRsAnnotations("migrate-to-spring-mvc"))
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web"));
    }

    @DocumentExample
    @Test
    void migrateSimplePathToRestController() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;

                @Path("/users")
                public class UserResource {
                    @GET
                    public String getUsers() {
                        return "users";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/users")
                public class UserResource {
                    @RequestMapping(method = RequestMethod.GET)
                    public String getUsers() {
                        return "users";
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsJaxRsAnnotationsWhenNoProjectYaml() {
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsAnnotations())
                .expectedCyclesThatMakeChanges(0),
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;

                @Path("/users")
                public class UserResource {
                    @GET
                    public String getUsers() {
                        return "users";
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesWhenProjectYamlRequestsSpringMvc() throws IOException {
        Path projectDir = tempDir.resolve("jaxrs-server-project");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        Files.writeString(projectDir.resolve("project.yaml"), """
                jaxrs:
                  strategy: migrate-to-spring-mvc
                """);

        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsAnnotations()),
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;

                @Path("/users")
                public class UserResource {
                    @GET
                    public String getUsers() {
                        return "users";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/users")
                public class UserResource {
                    @RequestMapping(method = RequestMethod.GET)
                    public String getUsers() {
                        return "users";
                    }
                }
                """,
                spec -> spec.path(projectDir.resolve("src/main/java/com/example/UserResource.java").toString())
            )
        );
    }

    @Test
    void migratePathWithProducesAndConsumes() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.Produces;
                import javax.ws.rs.Consumes;

                @Path("/api")
                @Produces("application/json")
                @Consumes("application/json")
                public class ApiResource {
                    @GET
                    public String getData() {
                        return "{}";
                    }
                }
                """,
                """
                import org.springframework.http.MediaType;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api", produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
                public class ApiResource {
                    @RequestMapping(method = RequestMethod.GET)
                    public String getData() {
                        return "{}";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateAllHttpMethods() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.POST;
                import javax.ws.rs.PUT;
                import javax.ws.rs.DELETE;

                @Path("/items")
                public class ItemResource {

                    @GET
                    public String list() {
                        return "list";
                    }

                    @POST
                    public String create() {
                        return "create";
                    }

                    @PUT
                    public String update() {
                        return "update";
                    }

                    @DELETE
                    public String delete() {
                        return "delete";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/items")
                public class ItemResource {

                    @RequestMapping(method = RequestMethod.GET)
                    public String list() {
                        return "list";
                    }

                    @RequestMapping(method = RequestMethod.POST)
                    public String create() {
                        return "create";
                    }

                    @RequestMapping(method = RequestMethod.PUT)
                    public String update() {
                        return "update";
                    }

                    @RequestMapping(method = RequestMethod.DELETE)
                    public String delete() {
                        return "delete";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMethodLevelPath() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;

                @Path("/api")
                public class ApiResource {

                    @GET
                    @Path("/items")
                    public String getItems() {
                        return "items";
                    }

                    @GET
                    @Path("/users")
                    public String getUsers() {
                        return "users";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {

                    @RequestMapping(value = "/items", method = RequestMethod.GET)
                    public String getItems() {
                        return "items";
                    }

                    @RequestMapping(value = "/users", method = RequestMethod.GET)
                    public String getUsers() {
                        return "users";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMethodLevelProducesAndConsumes() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.POST;
                import javax.ws.rs.Produces;
                import javax.ws.rs.Consumes;

                @Path("/api")
                public class ApiResource {

                    @POST
                    @Produces("application/json")
                    @Consumes("application/xml")
                    public String process() {
                        return "{}";
                    }
                }
                """,
                """
                import org.springframework.http.MediaType;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {

                    @RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_XML_VALUE, method = RequestMethod.POST)
                    public String process() {
                        return "{}";
                    }
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
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.GET;

                @Path("/jakarta")
                public class JakartaResource {
                    @GET
                    public String get() {
                        return "jakarta";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/jakarta")
                public class JakartaResource {
                    @RequestMapping(method = RequestMethod.GET)
                    public String get() {
                        return "jakarta";
                    }
                }
                """
            )
        );
    }

    @Test
    void keepNonJaxRsAnnotations() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;

                @Path("/api")
                @Deprecated
                public class LegacyResource {
                    @GET
                    @SuppressWarnings("unused")
                    public String get() {
                        return "legacy";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                @Deprecated
                public class LegacyResource {
                    @RequestMapping(method = RequestMethod.GET)
                    @SuppressWarnings("unused")
                    public String get() {
                        return "legacy";
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeIfNoPathAnnotation() {
        rewriteRun(
            java(
                """
                public class PlainService {
                    public String process() {
                        return "plain";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMediaTypeConstant() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.Produces;
                import javax.ws.rs.core.MediaType;

                @Path("/api")
                public class ApiResource {
                    @GET
                    @Produces(MediaType.APPLICATION_JSON)
                    public String getData() {
                        return "{}";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {
                    @RequestMapping(produces = org.springframework.http.MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.GET)
                    public String getData() {
                        return "{}";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMediaTypeArray() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.Produces;

                @Path("/api")
                public class ApiResource {
                    @GET
                    @Produces({"application/json", "application/xml"})
                    public String getData() {
                        return "{}";
                    }
                }
                """,
                """
                import org.springframework.http.MediaType;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {
                    @RequestMapping(produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.APPLICATION_XML_VALUE}, method = RequestMethod.GET)
                    public String getData() {
                        return "{}";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateClassLevelPathWithLocalConstant() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;

                @Path(API_PATH)
                public class ApiResource {
                    private static final String API_PATH = "/api";

                    @GET
                    public String getData() {
                        return "data";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {
                    private static final String API_PATH = "/api";

                    @RequestMapping(method = RequestMethod.GET)
                    public String getData() {
                        return "data";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMethodLevelPathWithLocalConstant() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;

                @Path("/api")
                public class ApiResource {
                    private static final String USERS = "/users";

                    @GET
                    @Path(USERS)
                    public String getUsers() {
                        return "users";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {
                    private static final String USERS = "/users";

                    @RequestMapping(value = "/users", method = RequestMethod.GET)
                    public String getUsers() {
                        return "users";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMediaTypeWithLocalConstant() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.Produces;

                @Path("/api")
                public class ApiResource {
                    private static final String CUSTOM_JSON = "application/vnd.custom+json";

                    @GET
                    @Produces(ApiResource.CUSTOM_JSON)
                    public String getData() {
                        return "data";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {
                    private static final String CUSTOM_JSON = "application/vnd.custom+json";

                    @RequestMapping(produces = "application/vnd.custom+json", method = RequestMethod.GET)
                    public String getData() {
                        return "data";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMediaTypeWithConcatenatedConstant() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.Produces;

                @Path("/api")
                public class ApiResource {
                    private static final String BASE = "application/vnd";

                    @GET
                    @Produces(BASE + "+json")
                    public String getData() {
                        return "data";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {
                    private static final String BASE = "application/vnd";

                    @RequestMapping(produces = "application/vnd+json", method = RequestMethod.GET)
                    public String getData() {
                        return "data";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateClassLevelPathWithConstantInSameFile() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;

                @Path(Paths.API_PATH)
                public class ApiResource {
                    @GET
                    public String getData() {
                        return "data";
                    }
                }

                class Paths {
                    static final String API_PATH = "/api";
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {
                    @RequestMapping(method = RequestMethod.GET)
                    public String getData() {
                        return "data";
                    }
                }

                class Paths {
                    static final String API_PATH = "/api";
                }
                """
            )
        );
    }

    @Test
    void migrateClassLevelPathWithInterfaceConstantInSameFile() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;

                @Path(Paths.API_PATH)
                public class ApiResource {
                    @GET
                    public String getData() {
                        return "data";
                    }
                }

                interface Paths {
                    String API_PATH = "/api";
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {
                    @RequestMapping(method = RequestMethod.GET)
                    public String getData() {
                        return "data";
                    }
                }

                interface Paths {
                    String API_PATH = "/api";
                }
                """
            )
        );
    }

    @Test
    void migrateClassLevelPathWithConcatenatedConstant() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;

                @Path(BASE + "/users")
                public class ApiResource {
                    private static final String BASE = "/api";

                    @GET
                    public String getUsers() {
                        return "users";
                    }
                }
                """,
                """
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api/users")
                public class ApiResource {
                    private static final String BASE = "/api";

                    @RequestMapping(method = RequestMethod.GET)
                    public String getUsers() {
                        return "users";
                    }
                }
                """
            )
        );
    }

    // ========== Edge Case Tests ==========

    @Test
    void resolveClassLevelPathFromImportedConstant() {
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web")),
            java(
                """
                package com.example;
                public class Paths {
                    private static final String BASE = "/api";
                    public static final String API_PATH = BASE;
                }
                """,
                spec -> spec.path("src/main/java/com/example/Paths.java")
            ),
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import com.example.Paths;

                @Path(Paths.API_PATH)
                public class ApiResource {
                    @GET
                    public String getData() {
                        return "data";
                    }
                }
                """,
                """
                import com.example.Paths;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {
                    @RequestMapping(method = RequestMethod.GET)
                    public String getData() {
                        return "data";
                    }
                }
                """
            )
        );
    }

    @Test
    void resolveMethodLevelPathFromImportedConstant() {
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web")),
            java(
                """
                package com.example;
                public class Paths {
                    private static final String BASE = "/";
                    public static final String USERS = BASE + "users";
                }
                """,
                spec -> spec.path("src/main/java/com/example/Paths.java")
            ),
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import com.example.Paths;

                @Path("/api")
                public class ApiResource {
                    @GET
                    @Path(Paths.USERS)
                    public String getUsers() {
                        return "users";
                    }
                }
                """,
                """
                import com.example.Paths;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api")
                public class ApiResource {
                    @RequestMapping(value = "/users", method = RequestMethod.GET)
                    public String getUsers() {
                        return "users";
                    }
                }
                """
            )
        );
    }

    @Test
    void resolveMediaTypeConstantFromImportedConstant() {
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web")),
            java(
                """
                package com.example;
                public class CustomMediaTypes {
                    private static final String BASE = "application/vnd.custom";
                    public static final String CUSTOM_JSON = BASE + "+json";
                }
                """,
                spec -> spec.path("src/main/java/com/example/CustomMediaTypes.java")
            ),
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.Produces;
                import com.example.CustomMediaTypes;

                @Path("/api")
                @Produces(CustomMediaTypes.CUSTOM_JSON)
                public class ApiResource {
                    @GET
                    public String getData() {
                        return "data";
                    }
                }
                """,
                """
                import com.example.CustomMediaTypes;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RequestMethod;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping(value = "/api", produces = "application/vnd.custom+json")
                public class ApiResource {
                    @RequestMapping(method = RequestMethod.GET)
                    public String getData() {
                        return "data";
                    }
                }
                """
            )
        );
    }

    @Test
    void multipleIssuesOnSameMethodAggregated() {
        // When a method has BOTH non-literal @Path AND unresolved @Produces,
        // both issues should appear together in the message, comma-separated
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web")
                .dependsOn(
                    """
                    package com.github.rewrite.ejb.annotations;
                    public @interface NeedsReview { String reason(); Category category(); enum Category { MANUAL_MIGRATION } }
                    """
                ))
                .expectedCyclesThatMakeChanges(2),
            java(
                """
                package com.example;
                public class Paths {
                    public static final String USERS = resolveUsers();

                    private static String resolveUsers() {
                        return "/users";
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/Paths.java")
            ),
            java(
                """
                package com.example;
                public class CustomMediaTypes {
                    public static final String CUSTOM = resolve();

                    private static String resolve() {
                        return "application/vnd.custom+json";
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/CustomMediaTypes.java")
            ),
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.Produces;
                import com.example.Paths;
                import com.example.CustomMediaTypes;

                @Path("/api")
                public class ApiResource {
                    @GET
                    @Path(Paths.USERS)
                    @Produces(CustomMediaTypes.CUSTOM)
                    public String getUsers() {
                        return "users";
                    }
                }
                """,
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import javax.ws.rs.Produces;
                import com.example.Paths;
                import com.github.rewrite.ejb.annotations.NeedsReview;
                import com.example.CustomMediaTypes;

                @Path("/api")
                @NeedsReview(reason = "[jaxrs-method-issues] Manual migration required for 1 method(s): Method 'getUsers()': @Path has non-literal value (Paths.USERS), @Produces has unresolved MediaType constants (CustomMediaTypes.CUSTOM). Replace constants with literal values, then re-run migration.", category = NeedsReview.Category.MANUAL_MIGRATION)
                public class ApiResource {
                    @GET
                    @Path(Paths.USERS)
                    @Produces(CustomMediaTypes.CUSTOM)
                    public String getUsers() {
                        return "users";
                    }
                }
                """
            )
        );
    }

    @Test
    void overloadedMethodsHandledCorrectly() {
        // When two overloaded methods (same name, different parameters) both have issues,
        // they should be reported separately with their full signatures
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web")
                .dependsOn(
                    """
                    package com.github.rewrite.ejb.annotations;
                    public @interface NeedsReview { String reason(); Category category(); enum Category { MANUAL_MIGRATION } }
                    """
                ))
                .expectedCyclesThatMakeChanges(2),
            java(
                """
                package com.example;
                public class Paths {
                    public static final String USERS = base();
                    public static final String USER_BY_ID = base() + "/{id}";

                    private static String base() {
                        return "/users";
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/Paths.java")
            ),
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import com.example.Paths;

                @Path("/api")
                public class ApiResource {
                    @GET
                    @Path(Paths.USERS)
                    public String getUsers() {
                        return "users";
                    }

                    @GET
                    @Path(Paths.USER_BY_ID)
                    public String getUsers(String id) {
                        return "user " + id;
                    }
                }
                """,
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.GET;
                import com.example.Paths;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @Path("/api")
                @NeedsReview(reason = "[jaxrs-method-issues] Manual migration required for 2 method(s): Method 'getUsers()': @Path has non-literal value (Paths.USERS); Method 'getUsers(java.lang.String)': @Path has non-literal value (Paths.USER_BY_ID). Replace constants with literal values, then re-run migration.", category = NeedsReview.Category.MANUAL_MIGRATION)
                public class ApiResource {
                    @GET
                    @Path(Paths.USERS)
                    public String getUsers() {
                        return "users";
                    }

                    @GET
                    @Path(Paths.USER_BY_ID)
                    public String getUsers(String id) {
                        return "user " + id;
                    }
                }
                """
            )
        );
    }

    @Test
    void overloadedMethodsWithSameSimpleNameFromDifferentPackages() {
        // When two overloaded methods have parameters with the same simple name but different packages,
        // they should have distinct FQN-based signatures (com.foo.User vs com.bar.User)
        rewriteRun(
            spec -> spec.parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web")
                .dependsOn(
                    """
                    package com.github.rewrite.ejb.annotations;
                    public @interface NeedsReview { String reason(); Category category(); enum Category { MANUAL_MIGRATION } }
                    """,
                    """
                    package com.foo;
                    public class User { public String name; }
                    """,
                    """
                    package com.bar;
                    public class User { public String email; }
                    """
                ))
                .expectedCyclesThatMakeChanges(2),
            java(
                """
                package com.example;
                public class Paths {
                    public static final String FOO = base() + "foo";
                    public static final String BAR = base() + "bar";

                    private static String base() {
                        return "/";
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/Paths.java")
            ),
            java(
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.POST;
                import com.example.Paths;
                import com.foo.User;

                @Path("/api")
                public class ApiResource {
                    @POST
                    @Path(Paths.FOO)
                    public String processUser(com.foo.User user) {
                        return "foo";
                    }

                    @POST
                    @Path(Paths.BAR)
                    public String processUser(com.bar.User user) {
                        return "bar";
                    }
                }
                """,
                """
                import javax.ws.rs.Path;
                import javax.ws.rs.POST;
                import com.example.Paths;
                import com.foo.User;
                import com.github.rewrite.ejb.annotations.NeedsReview;

                @Path("/api")
                @NeedsReview(reason = "[jaxrs-method-issues] Manual migration required for 2 method(s): Method 'processUser(com.foo.User)': @Path has non-literal value (Paths.FOO); Method 'processUser(com.bar.User)': @Path has non-literal value (Paths.BAR). Replace constants with literal values, then re-run migration.", category = NeedsReview.Category.MANUAL_MIGRATION)
                public class ApiResource {
                    @POST
                    @Path(Paths.FOO)
                    public String processUser(com.foo.User user) {
                        return "foo";
                    }

                    @POST
                    @Path(Paths.BAR)
                    public String processUser(com.bar.User user) {
                        return "bar";
                    }
                }
                """
            )
        );
    }
}
