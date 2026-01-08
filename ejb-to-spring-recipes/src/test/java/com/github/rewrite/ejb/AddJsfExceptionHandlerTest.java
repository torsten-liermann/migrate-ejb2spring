/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.java.Assertions.java;
import static org.openrewrite.xml.Assertions.xml;

/**
 * Tests for {@link AddJsfExceptionHandler}.
 * <p>
 * Test coverage:
 * <ul>
 *   <li>JSF project (via imports) -> ErrorPageConfig generated</li>
 *   <li>JSF project (via faces-config.xml) -> ErrorPageConfig generated</li>
 *   <li>JSF project (via FacesServlet in web.xml) -> ErrorPageConfig generated</li>
 *   <li>ErrorPageConfig already present -> No change</li>
 *   <li>No JSF usage -> No config generated</li>
 *   <li>Uses @SpringBootApplication package when available</li>
 *   <li>Uses common prefix when @SpringBootApplication not present</li>
 *   <li>Multi-module support: generates per-module configs</li>
 *   <li>Default package support: generates with warning</li>
 *   <li>AST-based handler detection: checks @Bean method returning ErrorPageRegistrar</li>
 * </ul>
 */
class AddJsfExceptionHandlerTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddJsfExceptionHandler())
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.faces-api", "spring-boot", "spring-context", "slf4j-api"));
    }

    @Test
    void generatesErrorPageConfigWhenJsfImportDetected() {
        // Test: JSF project (via imports) -> ErrorPageConfig generated
        rewriteRun(
            spec -> spec
                .expectedCyclesThatMakeChanges(1)
                .afterRecipe(run -> {
                    // Find the generated file
                    boolean configGenerated = run.getChangeset().getAllResults().stream()
                        .filter(r -> r.getAfter() != null)
                        .anyMatch(r -> {
                            String path = r.getAfter().getSourcePath().toString();
                            return path.endsWith("JsfErrorPageConfig.java");
                        });

                    assertThat(configGenerated)
                        .as("JsfErrorPageConfig should be generated for JSF project")
                        .isTrue();

                    // Verify the content of the generated file
                    run.getChangeset().getAllResults().stream()
                        .filter(r -> r.getAfter() != null)
                        .filter(r -> r.getAfter().getSourcePath().toString().endsWith("JsfErrorPageConfig.java"))
                        .findFirst()
                        .ifPresent(result -> {
                            String content = result.getAfter().printAll();
                            assertThat(content)
                                .contains("@Configuration")
                                .contains("@Bean")
                                .contains("ErrorPageRegistrar")
                                .contains("ViewExpiredException.class")
                                .contains("import jakarta.faces.application.ViewExpiredException")
                                .contains("import org.springframework.boot.web.server.ErrorPage")
                                .contains("import org.springframework.boot.web.server.ErrorPageRegistrar");
                            // Verify no @ControllerAdvice annotation in the class (only in Javadoc comment is ok)
                            // The generated code should use ErrorPageRegistrar, not @ControllerAdvice
                            String codeWithoutComments = content.replaceAll("/\\*\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/", "")
                                                                .replaceAll("//.*", "");
                            assertThat(codeWithoutComments).doesNotContain("@ControllerAdvice");
                        });
                }),
            java(
                """
                package com.example.web;

                import jakarta.faces.bean.ManagedBean;

                @ManagedBean
                public class MyBean {
                }
                """
            )
        );
    }

    @Test
    void generatesConfigInSpringBootAppPackage() {
        // Test: Config is generated in @SpringBootApplication package
        rewriteRun(
            spec -> spec
                .expectedCyclesThatMakeChanges(1)
                .afterRecipe(run -> {
                    // Find the generated config
                    run.getChangeset().getAllResults().stream()
                        .filter(r -> r.getAfter() != null)
                        .filter(r -> r.getAfter().getSourcePath().toString().endsWith("JsfErrorPageConfig.java"))
                        .findFirst()
                        .ifPresent(result -> {
                            String path = result.getAfter().getSourcePath().toString();
                            String content = result.getAfter().printAll();

                            // Verify path is in the @SpringBootApplication package
                            assertThat(path).contains("org/example/myapp");
                            assertThat(content).contains("package org.example.myapp");
                        });
                }),
            java(
                """
                package org.example.myapp;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class MyApplication {
                    public static void main(String[] args) {}
                }
                """
            ),
            java(
                """
                package org.example.myapp.web;

                import jakarta.faces.view.ViewScoped;

                @ViewScoped
                public class SomeBean {
                }
                """
            )
        );
    }

    @Test
    void usesCommonPrefixWhenNoSpringBootApp() {
        // Test: Uses common prefix when @SpringBootApplication not present
        rewriteRun(
            spec -> spec
                .expectedCyclesThatMakeChanges(1)
                .afterRecipe(run -> {
                    run.getChangeset().getAllResults().stream()
                        .filter(r -> r.getAfter() != null)
                        .filter(r -> r.getAfter().getSourcePath().toString().endsWith("JsfErrorPageConfig.java"))
                        .findFirst()
                        .ifPresent(result -> {
                            String content = result.getAfter().printAll();
                            // Common prefix of org.example.web and org.example.service is org.example
                            assertThat(content).contains("package org.example");
                        });
                }),
            java(
                """
                package org.example.web;

                import jakarta.faces.view.ViewScoped;

                @ViewScoped
                public class WebBean {
                }
                """
            ),
            java(
                """
                package org.example.service;

                import jakarta.faces.bean.ManagedBean;

                @ManagedBean
                public class ServiceBean {
                }
                """
            )
        );
    }

    @Test
    void noChangeWhenErrorPageRegistrarExists() {
        // Test: ErrorPageConfig already present -> No change
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example.web;

                import jakarta.faces.bean.ManagedBean;

                @ManagedBean
                public class MyBean {
                }
                """
            ),
            java(
                """
                package com.example.config;

                import jakarta.faces.application.ViewExpiredException;
                import org.springframework.boot.web.server.ErrorPage;
                import org.springframework.boot.web.server.ErrorPageRegistrar;
                import org.springframework.context.annotation.Bean;
                import org.springframework.context.annotation.Configuration;

                @Configuration
                public class ExistingErrorPageConfig {

                    @Bean
                    public ErrorPageRegistrar errorPageRegistrar() {
                        return registry -> {
                            registry.addErrorPages(new ErrorPage(ViewExpiredException.class, "/login"));
                        };
                    }
                }
                """
            )
        );
    }

    @Test
    void noChangeWhenNoJsfUsage() {
        // Test: No JSF imports -> No config generated
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example.service;

                import org.springframework.stereotype.Service;

                @Service
                public class MyService {
                }
                """
            )
        );
    }

    @Test
    void detectsJavaxFacesImports() {
        // Test: javax.faces.* imports are also detected
        rewriteRun(
            spec -> spec
                .expectedCyclesThatMakeChanges(1)
                .afterRecipe(run -> {
                    boolean configGenerated = run.getChangeset().getAllResults().stream()
                        .filter(r -> r.getAfter() != null)
                        .anyMatch(r -> r.getAfter().getSourcePath().toString().endsWith("JsfErrorPageConfig.java"));

                    assertThat(configGenerated)
                        .as("JsfErrorPageConfig should be generated for javax.faces project")
                        .isTrue();
                }),
            java(
                """
                package com.example.legacy;

                import javax.faces.bean.ManagedBean;

                @ManagedBean
                public class LegacyBean {
                }
                """
            )
        );
    }

    @Test
    void detectsJsfViaFacesConfigXml() {
        // Test: faces-config.xml triggers JSF detection
        rewriteRun(
            spec -> spec
                .expectedCyclesThatMakeChanges(1)
                .afterRecipe(run -> {
                    boolean configGenerated = run.getChangeset().getAllResults().stream()
                        .filter(r -> r.getAfter() != null)
                        .anyMatch(r -> r.getAfter().getSourcePath().toString().endsWith("JsfErrorPageConfig.java"));

                    assertThat(configGenerated)
                        .as("JsfErrorPageConfig should be generated when faces-config.xml is present")
                        .isTrue();
                }),
            java(
                """
                package com.example;

                public class SomeClass {
                }
                """
            ),
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void detectsJsfViaWebXml() {
        // Test: FacesServlet in web.xml triggers JSF detection
        rewriteRun(
            spec -> spec
                .expectedCyclesThatMakeChanges(1)
                .afterRecipe(run -> {
                    boolean configGenerated = run.getChangeset().getAllResults().stream()
                        .filter(r -> r.getAfter() != null)
                        .anyMatch(r -> r.getAfter().getSourcePath().toString().endsWith("JsfErrorPageConfig.java"));

                    assertThat(configGenerated)
                        .as("JsfErrorPageConfig should be generated when FacesServlet is in web.xml")
                        .isTrue();
                }),
            java(
                """
                package com.example;

                public class SomeClass {
                }
                """
            ),
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">
                    <servlet>
                        <servlet-name>FacesServlet</servlet-name>
                        <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                    </servlet>
                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    // ==================== Accumulator Unit Tests ====================

    @Test
    void scannerDetectsJakartaFacesImportPerModule() {
        // Test: Scanner now tracks JSF usage per module (source root)
        String javaSource = """
            package com.example;

            import jakarta.faces.bean.ManagedBean;

            @ManagedBean
            public class TestBean {
            }
            """;

        List<SourceFile> sources = JavaParser.fromJavaVersion()
            .build()
            .parse(javaSource)
            .toList();

        assertThat(sources).hasSize(1);
        // Modify the source path to simulate a real project structure
        SourceFile sourceWithPath = sources.get(0).withSourcePath(
            java.nio.file.Paths.get("src/main/java/com/example/TestBean.java"));

        AddJsfExceptionHandler recipe = new AddJsfExceptionHandler();
        AddJsfExceptionHandler.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(sourceWithPath, new InMemoryExecutionContext());

        // Verify multi-module accumulator structure
        assertThat(acc.modules).isNotEmpty();
        // Find the module state for the detected source root
        AddJsfExceptionHandler.ModuleState moduleState = acc.modules.values().iterator().next();
        assertThat(moduleState.hasJsfUsage).isTrue();
        assertThat(moduleState.hasExistingHandler).isFalse();
        assertThat(moduleState.packages).contains("com.example");
    }

    @Test
    void scannerDetectsSpringBootApplicationPackagePerModule() {
        String javaSource = """
            package org.example.myapp;

            import org.springframework.boot.autoconfigure.SpringBootApplication;

            @SpringBootApplication
            public class MyApplication {
            }
            """;

        List<SourceFile> sources = JavaParser.fromJavaVersion()
            .build()
            .parse(javaSource)
            .toList();

        assertThat(sources).hasSize(1);
        // Modify the source path to simulate a real project structure
        SourceFile sourceWithPath = sources.get(0).withSourcePath(
            java.nio.file.Paths.get("src/main/java/org/example/myapp/MyApplication.java"));

        AddJsfExceptionHandler recipe = new AddJsfExceptionHandler();
        AddJsfExceptionHandler.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(sourceWithPath, new InMemoryExecutionContext());

        // Find the module state for the detected source root
        assertThat(acc.modules).isNotEmpty();
        AddJsfExceptionHandler.ModuleState moduleState = acc.modules.values().iterator().next();
        assertThat(moduleState.springBootAppPackage).isEqualTo("org.example.myapp");
    }

    @Test
    void scannerDetectsExistingErrorPageRegistrarWithAstInspection() {
        // Test: AST-based detection of @Bean method returning ErrorPageRegistrar with ViewExpiredException
        String javaSource = """
            package com.example.config;

            import jakarta.faces.application.ViewExpiredException;
            import org.springframework.boot.web.server.ErrorPage;
            import org.springframework.boot.web.server.ErrorPageRegistrar;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;

            @Configuration
            public class ExistingConfig {

                @Bean
                public ErrorPageRegistrar errorPageRegistrar() {
                    return registry -> {
                        registry.addErrorPages(new ErrorPage(ViewExpiredException.class, "/"));
                    };
                }
            }
            """;

        List<SourceFile> sources = JavaParser.fromJavaVersion()
            .classpath("jakarta.faces-api", "spring-boot", "spring-context")
            .build()
            .parse(javaSource)
            .toList();

        assertThat(sources).hasSize(1);
        SourceFile sourceWithPath = sources.get(0).withSourcePath(
            java.nio.file.Paths.get("src/main/java/com/example/config/ExistingConfig.java"));

        AddJsfExceptionHandler recipe = new AddJsfExceptionHandler();
        AddJsfExceptionHandler.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(sourceWithPath, new InMemoryExecutionContext());

        assertThat(acc.modules).isNotEmpty();
        AddJsfExceptionHandler.ModuleState moduleState = acc.modules.values().iterator().next();
        assertThat(moduleState.hasExistingHandler).isTrue();
    }

    @Test
    void scannerIgnoresGenericErrorPageRegistrarWithAstInspection() {
        // ErrorPageRegistrar without ViewExpiredException should not count as existing handler
        // This tests the AST-based detection (requires ViewExpiredException import)
        String javaSource = """
            package com.example.config;

            import org.springframework.boot.web.server.ErrorPage;
            import org.springframework.boot.web.server.ErrorPageRegistrar;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.http.HttpStatus;

            @Configuration
            public class GenericErrorConfig {

                @Bean
                public ErrorPageRegistrar errorPageRegistrar() {
                    return registry -> {
                        registry.addErrorPages(new ErrorPage(HttpStatus.NOT_FOUND, "/404"));
                    };
                }
            }
            """;

        List<SourceFile> sources = JavaParser.fromJavaVersion()
            .classpath("spring-boot", "spring-context", "spring-web")
            .build()
            .parse(javaSource)
            .toList();

        assertThat(sources).hasSize(1);
        SourceFile sourceWithPath = sources.get(0).withSourcePath(
            java.nio.file.Paths.get("src/main/java/com/example/config/GenericErrorConfig.java"));

        AddJsfExceptionHandler recipe = new AddJsfExceptionHandler();
        AddJsfExceptionHandler.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(sourceWithPath, new InMemoryExecutionContext());

        assertThat(acc.modules).isNotEmpty();
        AddJsfExceptionHandler.ModuleState moduleState = acc.modules.values().iterator().next();
        // Generic handler should NOT count as existing ViewExpiredException handler
        assertThat(moduleState.hasExistingHandler).isFalse();
    }

    @Test
    void generatesForDefaultPackageWithWarning() {
        // Test: Default package generates config with warning comment
        AddJsfExceptionHandler recipe = new AddJsfExceptionHandler();
        AddJsfExceptionHandler.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Simulate a module with JSF usage but no packages (default package)
        AddJsfExceptionHandler.ModuleState moduleState = new AddJsfExceptionHandler.ModuleState();
        moduleState.hasJsfUsage = true;
        // Default package is detected when packages is empty
        acc.modules.put("src/main/java", moduleState);

        var generated = recipe.generate(acc, new InMemoryExecutionContext());

        // Should generate (no longer skips default package)
        assertThat(generated).hasSize(1);
        String content = generated.iterator().next().printAll();
        assertThat(content).contains("WARNING:");
        assertThat(content).contains("@NeedsReview");
        assertThat(content).contains("default package");
    }

    @Test
    void multiModuleGeneratesPerModule() {
        // Test: Multi-module project generates one config per module with JSF usage
        AddJsfExceptionHandler recipe = new AddJsfExceptionHandler();
        AddJsfExceptionHandler.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Module 1: Has JSF usage
        AddJsfExceptionHandler.ModuleState module1 = new AddJsfExceptionHandler.ModuleState();
        module1.hasJsfUsage = true;
        module1.packages.add("com.example.module1");
        acc.modules.put("module1/src/main/java", module1);

        // Module 2: Has JSF usage
        AddJsfExceptionHandler.ModuleState module2 = new AddJsfExceptionHandler.ModuleState();
        module2.hasJsfUsage = true;
        module2.packages.add("com.example.module2");
        acc.modules.put("module2/src/main/java", module2);

        // Module 3: No JSF usage
        AddJsfExceptionHandler.ModuleState module3 = new AddJsfExceptionHandler.ModuleState();
        module3.hasJsfUsage = false;
        module3.packages.add("com.example.module3");
        acc.modules.put("module3/src/main/java", module3);

        var generated = recipe.generate(acc, new InMemoryExecutionContext());

        // Should generate 2 configs (only for modules with JSF usage)
        assertThat(generated).hasSize(2);

        // Verify paths include module prefixes
        var paths = generated.stream()
            .map(sf -> sf.getSourcePath().toString().replace('\\', '/'))
            .toList();
        assertThat(paths).anyMatch(p -> p.contains("module1"));
        assertThat(paths).anyMatch(p -> p.contains("module2"));
        assertThat(paths).noneMatch(p -> p.contains("module3"));
    }

    @Test
    void multiModuleSkipsModuleWithExistingHandler() {
        // Test: Multi-module skips module that already has handler
        AddJsfExceptionHandler recipe = new AddJsfExceptionHandler();
        AddJsfExceptionHandler.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        // Module 1: Has JSF usage and existing handler
        AddJsfExceptionHandler.ModuleState module1 = new AddJsfExceptionHandler.ModuleState();
        module1.hasJsfUsage = true;
        module1.hasExistingHandler = true;
        module1.packages.add("com.example.module1");
        acc.modules.put("module1/src/main/java", module1);

        // Module 2: Has JSF usage but no existing handler
        AddJsfExceptionHandler.ModuleState module2 = new AddJsfExceptionHandler.ModuleState();
        module2.hasJsfUsage = true;
        module2.hasExistingHandler = false;
        module2.packages.add("com.example.module2");
        acc.modules.put("module2/src/main/java", module2);

        var generated = recipe.generate(acc, new InMemoryExecutionContext());

        // Should generate only 1 config (module 2)
        assertThat(generated).hasSize(1);

        var paths = generated.stream()
            .map(sf -> sf.getSourcePath().toString().replace('\\', '/'))
            .toList();
        assertThat(paths).noneMatch(p -> p.contains("module1"));
        assertThat(paths).anyMatch(p -> p.contains("module2"));
    }

    @Test
    void scannerDetectsAnnotatedReturnTypeErrorPageRegistrar() {
        // Test: @NonNull ErrorPageRegistrar (annotated return type) should be recognized
        String javaSource = """
            package com.example.config;

            import jakarta.faces.application.ViewExpiredException;
            import org.springframework.boot.web.server.ErrorPage;
            import org.springframework.boot.web.server.ErrorPageRegistrar;
            import org.springframework.context.annotation.Bean;
            import org.springframework.context.annotation.Configuration;
            import org.springframework.lang.NonNull;

            @Configuration
            public class AnnotatedExistingConfig {

                @Bean
                @NonNull
                public ErrorPageRegistrar viewExpiredHandler() {
                    return registry -> {
                        registry.addErrorPages(new ErrorPage(ViewExpiredException.class, "/"));
                    };
                }
            }
            """;

        List<SourceFile> sources = JavaParser.fromJavaVersion()
            .classpath("jakarta.faces-api", "spring-boot", "spring-context", "spring-core")
            .build()
            .parse(javaSource)
            .toList();

        assertThat(sources).hasSize(1);
        SourceFile sourceWithPath = sources.get(0).withSourcePath(
            java.nio.file.Paths.get("src/main/java/com/example/config/AnnotatedExistingConfig.java"));

        AddJsfExceptionHandler recipe = new AddJsfExceptionHandler();
        AddJsfExceptionHandler.Accumulator acc = recipe.getInitialValue(new InMemoryExecutionContext());

        recipe.getScanner(acc).visit(sourceWithPath, new InMemoryExecutionContext());

        assertThat(acc.modules).isNotEmpty();
        AddJsfExceptionHandler.ModuleState moduleState = acc.modules.values().iterator().next();
        // Annotated return type should still be recognized as existing handler
        assertThat(moduleState.hasExistingHandler).isTrue();
    }
}
