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

class MigrateJaxRsResponseToResponseEntityTest implements RewriteTest {

    @TempDir
    Path tempDir;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateJaxRsResponseToResponseEntity("migrate-to-spring-mvc"))
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion()
                .classpath("javax.ws.rs-api", "jakarta.jakartaee-api", "spring-web"));
    }

    @DocumentExample
    @Test
    void migrateResponseReturnType() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Response;

                public class UserResource {
                    public Response getUser() {
                        return Response.ok().build();
                    }
                }
                """,
                """
                import org.springframework.http.ResponseEntity;

                public class UserResource {
                    public ResponseEntity getUser() {
                        return ResponseEntity.ok().build();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateResponseOkWithEntity() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Response;

                public class UserResource {
                    public Response getUser(Long id) {
                        User user = findUser(id);
                        return Response.ok(user).build();
                    }

                    private User findUser(Long id) {
                        return null;
                    }
                }

                class User {
                    private String name;
                }
                """,
                """
                import org.springframework.http.ResponseEntity;

                public class UserResource {
                    public ResponseEntity getUser(Long id) {
                        User user = findUser(id);
                        return ResponseEntity.ok(user).build();
                    }

                    private User findUser(Long id) {
                        return null;
                    }
                }

                class User {
                    private String name;
                }
                """
            )
        );
    }

    @Test
    void migrateResponseStatus() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Response;
                import javax.ws.rs.core.Response.Status;

                public class UserResource {
                    public Response createUser() {
                        return Response.status(Status.CREATED).build();
                    }
                }
                """,
                """
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;

                public class UserResource {
                    public ResponseEntity createUser() {
                        return ResponseEntity.status(HttpStatus.CREATED).build();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateResponseNoContent() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Response;

                public class UserResource {
                    public Response deleteUser(Long id) {
                        return Response.noContent().build();
                    }
                }
                """,
                """
                import org.springframework.http.ResponseEntity;

                public class UserResource {
                    public ResponseEntity deleteUser(Long id) {
                        return ResponseEntity.noContent().build();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateResponseAccepted() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Response;

                public class AsyncResource {
                    public Response submitJob() {
                        return Response.accepted().build();
                    }
                }
                """,
                """
                import org.springframework.http.ResponseEntity;

                public class AsyncResource {
                    public ResponseEntity submitJob() {
                        return ResponseEntity.accepted().build();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateMultipleStatusCodes() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Response;
                import javax.ws.rs.core.Response.Status;

                public class ItemResource {

                    public Response getItem(Long id) {
                        if (id == null) {
                            return Response.status(Status.BAD_REQUEST).build();
                        }
                        Item item = findItem(id);
                        if (item == null) {
                            return Response.status(Status.NOT_FOUND).build();
                        }
                        return Response.ok(item).build();
                    }

                    private Item findItem(Long id) {
                        return null;
                    }
                }

                class Item {
                    private String name;
                }
                """,
                """
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;

                public class ItemResource {

                    public ResponseEntity getItem(Long id) {
                        if (id == null) {
                            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
                        }
                        Item item = findItem(id);
                        if (item == null) {
                            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                        }
                        return ResponseEntity.ok(item).build();
                    }

                    private Item findItem(Long id) {
                        return null;
                    }
                }

                class Item {
                    private String name;
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
                import jakarta.ws.rs.core.Response;

                public class JakartaResource {
                    public Response getData() {
                        return Response.ok().build();
                    }
                }
                """,
                """
                import org.springframework.http.ResponseEntity;

                public class JakartaResource {
                    public ResponseEntity getData() {
                        return ResponseEntity.ok().build();
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateJakartaStatusEnum() {
        rewriteRun(
            java(
                """
                import jakarta.ws.rs.core.Response;
                import jakarta.ws.rs.core.Response.Status;

                public class JakartaResource {
                    public Response create() {
                        return Response.status(Status.CREATED).build();
                    }
                }
                """,
                """
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;

                public class JakartaResource {
                    public ResponseEntity create() {
                        return ResponseEntity.status(HttpStatus.CREATED).build();
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeIfNoResponse() {
        rewriteRun(
            java(
                """
                public class PlainService {
                    public String process() {
                        return "processed";
                    }
                }
                """
            )
        );
    }

    @Test
    void migrateResponseBuilderEntityToBody() {
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Response;

                public class UserResource {
                    public Response createUser(User user) {
                        return Response.status(Response.Status.CREATED).entity(user).build();
                    }
                }

                class User {
                    private String name;
                }
                """,
                """
                import org.springframework.http.HttpStatus;
                import org.springframework.http.ResponseEntity;

                public class UserResource {
                    public ResponseEntity createUser(User user) {
                        return ResponseEntity.status(HttpStatus.CREATED).body(user).build();
                    }
                }

                class User {
                    private String name;
                }
                """
            )
        );
    }

    @Test
    void migrateResponseStatusType() {
        // Response.StatusType is an interface for custom status codes - migrates to HttpStatusCode
        rewriteRun(
            java(
                """
                import javax.ws.rs.core.Response;
                import javax.ws.rs.core.Response.StatusType;

                public class CustomStatusResource {
                    public Response handleCustomStatus(StatusType status) {
                        return Response.status(status).build();
                    }

                    public StatusType getStatus(Response response) {
                        return response.getStatusInfo();
                    }
                }
                """,
                """
                import org.springframework.http.HttpStatusCode;
                import org.springframework.http.ResponseEntity;

                public class CustomStatusResource {
                    public ResponseEntity handleCustomStatus(HttpStatusCode status) {
                        return ResponseEntity.status(status).build();
                    }

                    public HttpStatusCode getStatus(ResponseEntity response) {
                        return response.getStatusCode();
                    }
                }
                """
            )
        );
    }

    @Test
    void keepsJaxRsResponseWhenNoProjectYaml() {
        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsResponseToResponseEntity())
                .expectedCyclesThatMakeChanges(0),
            java(
                """
                import javax.ws.rs.core.Response;

                public class Resource {
                    public Response ok() {
                        return Response.ok("ok").build();
                    }
                }
                """
            )
        );
    }

    @Test
    void migratesWhenProjectYamlRequestsSpringMvc() throws IOException {
        Path projectDir = tempDir.resolve("jaxrs-response-project");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        Files.writeString(projectDir.resolve("project.yaml"), """
                jaxrs:
                  strategy: migrate-to-spring-mvc
                """);

        rewriteRun(
            spec -> spec.recipe(new MigrateJaxRsResponseToResponseEntity()),
            java(
                """
                import javax.ws.rs.core.Response;

                public class UserResource {
                    public Response getUser() {
                        return Response.ok().build();
                    }
                }
                """,
                """
                import org.springframework.http.ResponseEntity;

                public class UserResource {
                    public ResponseEntity getUser() {
                        return ResponseEntity.ok().build();
                    }
                }
                """,
                spec -> spec.path(projectDir.resolve("src/main/java/com/example/UserResource.java").toString())
            )
        );
    }
}
