package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.*;
import org.openrewrite.java.ChangeMethodName;
import org.openrewrite.java.ChangeType;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Migrates JAX-RS Response to Spring ResponseEntity.
 * <p>
 * Handles both javax.ws.rs.core and jakarta.ws.rs.core namespaces.
 * <p>
 * Transformations:
 * <ul>
 *   <li>Response -> ResponseEntity</li>
 *   <li>Response.ok() -> ResponseEntity.ok()</li>
 *   <li>Response.ok(entity) -> ResponseEntity.ok(entity)</li>
 *   <li>Response.status(Status.OK) -> ResponseEntity.status(HttpStatus.OK)</li>
 *   <li>Response.created(uri) -> ResponseEntity.created(uri)</li>
 *   <li>Response.noContent() -> ResponseEntity.noContent()</li>
 *   <li>Response.accepted() -> ResponseEntity.accepted()</li>
 *   <li>Response.Status.* -> HttpStatus.*</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateJaxRsResponseToResponseEntity extends Recipe {

    private static final String JAVAX_RESPONSE = "javax.ws.rs.core.Response";
    private static final String JAKARTA_RESPONSE = "jakarta.ws.rs.core.Response";
    private static final String SPRING_RESPONSE_ENTITY = "org.springframework.http.ResponseEntity";
    private static final String SPRING_HTTP_STATUS = "org.springframework.http.HttpStatus";
    private static final String SPRING_BODY_BUILDER = "org.springframework.http.ResponseEntity.BodyBuilder";
    private static final String SPRING_HTTP_STATUS_CODE = "org.springframework.http.HttpStatusCode";

    @Option(displayName = "JAX-RS strategy override",
            description = "Override project.yaml JAX-RS server strategy: keep-jaxrs or migrate-to-spring-mvc. " +
                          "If not set, project.yaml (or defaults) are used.",
            example = "migrate-to-spring-mvc",
            required = false)
    @Nullable
    String strategy;

    public MigrateJaxRsResponseToResponseEntity() {
        this.strategy = null;
    }

    public MigrateJaxRsResponseToResponseEntity(@Nullable String strategy) {
        this.strategy = strategy;
    }

    @Override
    public String getDisplayName() {
        return "Migrate JAX-RS Response to Spring ResponseEntity";
    }

    @Override
    public String getDescription() {
        return "Converts JAX-RS Response and Response.Status to Spring ResponseEntity and HttpStatus. " +
               "Supports both javax.ws.rs.core and jakarta.ws.rs.core namespaces.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        TreeVisitor<?, ExecutionContext> delegate = Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAVAX_RESPONSE, false),
                new UsesType<>(JAKARTA_RESPONSE, false)
            ),
            new TreeVisitor<Tree, ExecutionContext>() {
                @Override
                public Tree visit(Tree tree, ExecutionContext ctx) {
                    // First, rename methods BEFORE type changes (match on original JAX-RS types)
                    // ResponseBuilder.entity() -> body()
                    tree = new ChangeMethodName(
                        "javax.ws.rs.core.Response$ResponseBuilder entity(..)",
                        "body",
                        true,  // matchOverrides
                        null   // ignoreDefinitions
                    ).getVisitor().visit(tree, ctx);
                    tree = new ChangeMethodName(
                        "jakarta.ws.rs.core.Response$ResponseBuilder entity(..)",
                        "body",
                        true,  // matchOverrides
                        null   // ignoreDefinitions
                    ).getVisitor().visit(tree, ctx);

                    // Response.getStatusInfo() -> ResponseEntity.getStatusCode()
                    tree = new ChangeMethodName(
                        "javax.ws.rs.core.Response getStatusInfo()",
                        "getStatusCode",
                        true,  // matchOverrides
                        null   // ignoreDefinitions
                    ).getVisitor().visit(tree, ctx);
                    tree = new ChangeMethodName(
                        "jakarta.ws.rs.core.Response getStatusInfo()",
                        "getStatusCode",
                        true,  // matchOverrides
                        null   // ignoreDefinitions
                    ).getVisitor().visit(tree, ctx);

                    // Change Response.Status to HttpStatus first (inner type)
                    tree = new ChangeType("javax.ws.rs.core.Response$Status", SPRING_HTTP_STATUS, true)
                        .getVisitor().visit(tree, ctx);
                    tree = new ChangeType("jakarta.ws.rs.core.Response$Status", SPRING_HTTP_STATUS, true)
                        .getVisitor().visit(tree, ctx);

                    // Change Response.StatusType to HttpStatusCode
                    tree = new ChangeType("javax.ws.rs.core.Response$StatusType", SPRING_HTTP_STATUS_CODE, true)
                        .getVisitor().visit(tree, ctx);
                    tree = new ChangeType("jakarta.ws.rs.core.Response$StatusType", SPRING_HTTP_STATUS_CODE, true)
                        .getVisitor().visit(tree, ctx);

                    // Change Response.ResponseBuilder to ResponseEntity.BodyBuilder
                    tree = new ChangeType("javax.ws.rs.core.Response$ResponseBuilder", SPRING_BODY_BUILDER, true)
                        .getVisitor().visit(tree, ctx);
                    tree = new ChangeType("jakarta.ws.rs.core.Response$ResponseBuilder", SPRING_BODY_BUILDER, true)
                        .getVisitor().visit(tree, ctx);

                    // Then change Response to ResponseEntity
                    tree = new ChangeType(JAVAX_RESPONSE, SPRING_RESPONSE_ENTITY, true)
                        .getVisitor().visit(tree, ctx);
                    tree = new ChangeType(JAKARTA_RESPONSE, SPRING_RESPONSE_ENTITY, true)
                        .getVisitor().visit(tree, ctx);

                    return tree;
                }
            }
        );
        return new org.openrewrite.java.JavaIsoVisitor<ExecutionContext>() {
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

    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return null;
        }
        Path current = sourcePath.toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("build.gradle.kts")) ||
                Files.exists(current.resolve("settings.gradle")) ||
                Files.exists(current.resolve("settings.gradle.kts")) ||
                Files.exists(current.resolve("project.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return sourcePath.toAbsolutePath().getParent();
    }
}
