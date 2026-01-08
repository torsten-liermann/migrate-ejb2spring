package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaParser;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;
import org.openrewrite.test.TypeValidation;

import static org.openrewrite.java.Assertions.java;

class AddMatrixVariableConfigurationTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new AddMatrixVariableConfiguration())
            .parser(JavaParser.fromJavaVersion())
            .typeValidationOptions(TypeValidation.none());
    }

    @Test
    void generatesConfigurationForMatrixParamUsage() {
        rewriteRun(
            java(
                """
                package com.example;

                import javax.ws.rs.MatrixParam;

                public class Resource {
                    public String get(@MatrixParam("id") String id) {
                        return id;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/Resource.java")
            ),
            java(
                null,
                """
                package com.example;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.http.server.PathContainer;
                import org.springframework.web.util.pattern.PathPatternParser;
                import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

                @Configuration
                public class MatrixVariableConfiguration implements WebMvcConfigurer {
                    @Override
                    public void configurePathMatch(PathMatchConfigurer configurer) {
                        PathPatternParser parser = new PathPatternParser();
                        parser.setPathOptions(PathContainer.Options.HTTP_PATH);
                        configurer.setPatternParser(parser);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/MatrixVariableConfiguration.java")
            )
        );
    }

    @Test
    void doesNotGenerateIfMatrixConfigAlreadyExists() {
        rewriteRun(
            java(
                """
                package com.example;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.http.server.PathContainer;
                import org.springframework.web.util.pattern.PathPatternParser;
                import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

                @Configuration
                public class WebConfig implements WebMvcConfigurer {
                    @Override
                    public void configurePathMatch(PathMatchConfigurer configurer) {
                        PathPatternParser parser = new PathPatternParser();
                        parser.setPathOptions(PathContainer.Options.HTTP_PATH);
                        configurer.setPatternParser(parser);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/WebConfig.java")
            ),
            java(
                """
                package com.example;

                import org.springframework.web.bind.annotation.MatrixVariable;

                public class Resource {
                    public String get(@MatrixVariable("id") String id) {
                        return id;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/Resource.java")
            )
        );
    }

    @Test
    void doesNotGenerateIfLegacyMatrixConfigAlreadyExists() {
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

                import org.springframework.web.bind.annotation.MatrixVariable;

                public class Resource {
                    public String get(@MatrixVariable("id") String id) {
                        return id;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/example/Resource.java")
            )
        );
    }

    @Test
    void generatesConfigurationUsingCommonPackagePrefix() {
        rewriteRun(
            java(
                """
                package com.acme.feature.api;

                import javax.ws.rs.MatrixParam;

                public class ApiResource {
                    public String get(@MatrixParam("id") String id) {
                        return id;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/acme/feature/api/ApiResource.java")
            ),
            java(
                """
                package com.acme.feature.impl;

                import javax.ws.rs.MatrixParam;

                public class ImplResource {
                    public String get(@MatrixParam("id") String id) {
                        return id;
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/acme/feature/impl/ImplResource.java")
            ),
            java(
                null,
                """
                package com.acme.feature;

                import org.springframework.context.annotation.Configuration;
                import org.springframework.http.server.PathContainer;
                import org.springframework.web.util.pattern.PathPatternParser;
                import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

                @Configuration
                public class MatrixVariableConfiguration implements WebMvcConfigurer {
                    @Override
                    public void configurePathMatch(PathMatchConfigurer configurer) {
                        PathPatternParser parser = new PathPatternParser();
                        parser.setPathOptions(PathContainer.Options.HTTP_PATH);
                        configurer.setPatternParser(parser);
                    }
                }
                """,
                spec -> spec.path("src/main/java/com/acme/feature/MatrixVariableConfiguration.java")
            )
        );
    }

    @Test
    void generatesConfigurationWhenNoPackageDeclarationsExist() {
        rewriteRun(
            spec -> spec.typeValidationOptions(TypeValidation.none()),
            java(
                """
                import javax.ws.rs.MatrixParam;

                public class MatrixResource {
                    public String read(@MatrixParam("lang") String lang) {
                        return lang;
                    }
                }
                """,
                spec -> spec.path("src/main/java/MatrixResource.java")
            ),
            java(
                null,
                """
                import org.springframework.context.annotation.Configuration;
                import org.springframework.http.server.PathContainer;
                import org.springframework.web.util.pattern.PathPatternParser;
                import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
                import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

                @Configuration
                public class MatrixVariableConfiguration implements WebMvcConfigurer {
                    @Override
                    public void configurePathMatch(PathMatchConfigurer configurer) {
                        PathPatternParser parser = new PathPatternParser();
                        parser.setPathOptions(PathContainer.Options.HTTP_PATH);
                        configurer.setPatternParser(parser);
                    }
                }
                """,
                spec -> spec.path("src/main/java/MatrixVariableConfiguration.java")
            )
        );
    }
}
