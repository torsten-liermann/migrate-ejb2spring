package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class GenerateRemoteEjbStubsTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.jakartaee-api"))
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void generatesRestStubForRemoteInterface() {
        rewriteRun(
            spec -> spec.recipe(new GenerateRemoteEjbStubs(null, null)),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface CustomerService {
                    String find(String id);
                    void update(String id, int value);
                }
                """,
                spec -> spec.path("src/main/java/com/example/CustomerService.java")
            ),
            java(
                null,
                """
                package com.example.remote.stub;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @NeedsReview(reason = "Remote EJB should be migrated to REST controller", category = NeedsReview.Category.REMOTE_ACCESS, originalCode = "@Remote", suggestedAction = "Design REST endpoints and implement controller for com.example.CustomerService")
                @RestController
                @RequestMapping("/remote/CustomerService")
                public class CustomerServiceRemoteController {
                    // TODO: Generated from com.example.CustomerService
                    @PostMapping("/find")
                    public java.lang.String find(java.lang.String id) {
                        throw new UnsupportedOperationException("TODO: implement find");
                    }

                    @PostMapping("/update")
                    public void update(java.lang.String id, int value) {
                        throw new UnsupportedOperationException("TODO: implement update");
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/remote/stub/CustomerServiceRemoteController.java")
            )
        );
    }

    @Test
    void generatesGrpcStubForRemoteInterface() {
        rewriteRun(
            spec -> spec.recipe(new GenerateRemoteEjbStubs("grpc", "grpc")),
            java(
                """
                package com.example;

                import jakarta.ejb.Remote;

                @Remote
                public interface CustomerService {
                    String find(String id);
                }
                """,
                spec -> spec.path("src/main/java/com/example/CustomerService.java")
            ),
            java(
                null,
                """
                package com.example.grpc;

                import com.github.rewrite.ejb.annotations.NeedsReview;
                import org.springframework.stereotype.Service;

                @NeedsReview(reason = "Remote EJB should be migrated to gRPC service", category = NeedsReview.Category.REMOTE_ACCESS, originalCode = "@Remote", suggestedAction = "Define .proto and implement gRPC service for com.example.CustomerService")
                @Service
                public class CustomerServiceGrpcService {
                    // TODO: Generated from com.example.CustomerService
                    public java.lang.String find(java.lang.String id) {
                        throw new UnsupportedOperationException("TODO: implement find");
                    }

                }
                """,
                spec -> spec.path("src/main/java/com/example/grpc/CustomerServiceGrpcService.java")
            )
        );
    }
}
