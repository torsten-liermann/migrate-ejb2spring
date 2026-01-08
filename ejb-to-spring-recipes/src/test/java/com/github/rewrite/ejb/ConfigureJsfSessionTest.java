/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - ViewScoped session configuration tests
 * Modified: Codex P2.3 - Added tests for multi-module support and annotation-based detection
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
import static org.openrewrite.properties.Assertions.properties;

/**
 * Tests for {@link ConfigureJsfSession}.
 * <p>
 * Test cases from P2.3 specification:
 * <ul>
 *   <li>Klasse mit jakarta.faces.view.ViewScoped -> Session-Config generiert</li>
 *   <li>Klasse mit javax.faces.view.ViewScoped -> Session-Config generiert</li>
 *   <li>Klasse mit OmniFaces ViewScoped -> Session-Config generiert</li>
 *   <li>Klasse mit Spring ViewScoped -> KEINE Session-Config</li>
 *   <li>Kein ViewScoped im Projekt -> KEINE Session-Config</li>
 *   <li>Property bereits vorhanden -> NICHT ueberschreiben</li>
 *   <li>application.properties fehlt -> Erstellen mit Config</li>
 *   <li>Multi-Modul Support -> Separate Config pro Modul</li>
 *   <li>FQCN Annotation Detection -> Erkennt vollqualifizierte Annotationen</li>
 *   <li>Unused Import -> Kein False Positive</li>
 * </ul>
 */
class ConfigureJsfSessionTest implements RewriteTest {

    @TempDir
    Path tempDir;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ConfigureJsfSession())
            .typeValidationOptions(TypeValidation.none())
            .parser(JavaParser.fromJavaVersion()
                .classpath("jakarta.faces-api", "spring-context"));
    }

    @DocumentExample
    @Test
    void generatesSessionConfigForJakartaFacesViewScoped() {
        // Test: Klasse mit jakarta.faces.view.ViewScoped -> Session-Config generiert
        rewriteRun(
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class MyViewBean {
                }
                """
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated for JSF ViewScoped session configuration

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void generatesSessionConfigForJavaxFacesViewScoped() {
        // Test: Klasse mit javax.faces.view.ViewScoped -> Session-Config generiert
        rewriteRun(
            java(
                """
                package com.example.legacy;

                import javax.faces.view.ViewScoped;
                import javax.inject.Named;

                @Named
                @ViewScoped
                public class LegacyViewBean {
                }
                """
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated for JSF ViewScoped session configuration

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void generatesSessionConfigForOmniFacesViewScoped() {
        // Test: Klasse mit OmniFaces ViewScoped -> Session-Config generiert
        rewriteRun(
            java(
                """
                package com.example.omni;

                import org.omnifaces.cdi.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class OmniViewBean {
                }
                """
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated for JSF ViewScoped session configuration

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void noChangeForSpringViewScoped() {
        // Test: Klasse mit Spring ViewScoped -> KEINE Session-Config
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example.spring;

                import org.springframework.web.context.annotation.ViewScoped;
                import org.springframework.stereotype.Component;

                @Component
                @ViewScoped
                public class SpringViewBean {
                }
                """
            )
        );
    }

    @Test
    void noChangeWhenNoViewScoped() {
        // Test: Kein ViewScoped im Projekt -> KEINE Session-Config
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
    void doesNotOverwriteExistingTrackingModes() {
        // Test: server.servlet.session.tracking-modes bereits vorhanden -> NICHT ueberschreiben
        rewriteRun(
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class MyViewBean {
                }
                """
            ),
            properties(
                """
                server.port=8080
                server.servlet.session.tracking-modes=url
                """,
                """
                server.port=8080
                server.servlet.session.tracking-modes=url

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void doesNotOverwriteExistingClientWindowMode() {
        // Test: joinfaces.jsf.client-window-mode bereits vorhanden -> NICHT ueberschreiben
        rewriteRun(
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class MyViewBean {
                }
                """
            ),
            properties(
                """
                server.port=8080
                joinfaces.jsf.client-window-mode=none
                """,
                """
                server.port=8080
                joinfaces.jsf.client-window-mode=none

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void doesNotOverwriteExistingSameSite() {
        // Test: server.servlet.session.cookie.same-site bereits vorhanden -> NICHT ueberschreiben
        rewriteRun(
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class MyViewBean {
                }
                """
            ),
            properties(
                """
                server.port=8080
                server.servlet.session.cookie.same-site=strict
                """,
                """
                server.port=8080
                server.servlet.session.cookie.same-site=strict

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void noChangeWhenAllPropertiesAlreadyExist() {
        // Test: Alle Properties bereits vorhanden -> KEINE Aenderung
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class MyViewBean {
                }
                """
            ),
            properties(
                """
                server.port=8080
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void appendsToExistingApplicationProperties() {
        // Test: Existierende application.properties erweitern
        rewriteRun(
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class MyViewBean {
                }
                """
            ),
            properties(
                """
                server.port=8080
                spring.application.name=myapp
                """,
                """
                server.port=8080
                spring.application.name=myapp

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void idempotentMigrationDoesNotDuplicate() {
        // Test: Zweimaliges Ausfuehren dupliziert nicht
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class MyViewBean {
                }
                """
            ),
            properties(
                """
                server.port=8080

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void skipsTestSources() {
        // Test: ViewScoped in Test-Klassen wird ignoriert
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class TestViewBean {
                }
                """,
                spec -> spec.path("src/test/java/com/example/web/TestViewBean.java")
            )
        );
    }

    @Test
    void handlesMultipleViewScopedClasses() {
        // Test: Mehrere ViewScoped-Klassen generieren nur einmal Config
        rewriteRun(
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class FirstViewBean {
                }
                """
            ),
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class SecondViewBean {
                }
                """
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated for JSF ViewScoped session configuration

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void handlesOnlyPartialExistingProperties() {
        // Test: Nur fehlende Properties werden hinzugefuegt
        rewriteRun(
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class MyViewBean {
                }
                """
            ),
            properties(
                """
                server.port=8080
                server.servlet.session.tracking-modes=cookie
                joinfaces.jsf.client-window-mode=url
                """,
                """
                server.port=8080
                server.servlet.session.tracking-modes=cookie
                joinfaces.jsf.client-window-mode=url

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.cookie.same-site=lax
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void generatesSessionConfigWithCustomResourceRoot() throws IOException {
        Path projectDir = tempDir.resolve("jsf-custom-root");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        Files.writeString(projectDir.resolve("project.yaml"), """
                sources:
                  resources:
                    - src/custom/resources
                """);

        rewriteRun(
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class CustomRootViewBean {
                }
                """,
                spec -> spec.path(projectDir.resolve("src/main/java/com/example/web/CustomRootViewBean.java").toString())
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated for JSF ViewScoped session configuration

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path(projectDir.resolve("src/custom/resources/application.properties").toString())
            )
        );
    }

    // ===== Multi-Modul Support Tests =====

    @Test
    void multiModuleGeneratesConfigPerModule() {
        // Test: Multi-Modul Projekt - Config wird pro Modul mit ViewScoped generiert
        rewriteRun(
            // Module A hat ViewScoped und eigene application.properties
            java(
                """
                package com.example.modulea;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class ModuleAViewBean {
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/modulea/ModuleAViewBean.java")
            ),
            properties(
                """
                server.port=8081
                """,
                """
                server.port=8081

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("module-a/src/main/resources/application.properties")
            )
        );
    }

    @Test
    void multiModuleOnlyAffectsModuleWithViewScoped() {
        // Test: Multi-Modul - Modul ohne ViewScoped bleibt unveraendert
        rewriteRun(
            // Module A hat ViewScoped
            java(
                """
                package com.example.modulea;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class ModuleAViewBean {
                }
                """,
                spec -> spec.path("module-a/src/main/java/com/example/modulea/ModuleAViewBean.java")
            ),
            properties(
                """
                server.port=8081
                """,
                """
                server.port=8081

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path("module-a/src/main/resources/application.properties")
            ),
            // Module B hat KEIN ViewScoped - bleibt unveraendert
            java(
                """
                package com.example.moduleb;

                import org.springframework.stereotype.Service;

                @Service
                public class ModuleBService {
                }
                """,
                spec -> spec.path("module-b/src/main/java/com/example/moduleb/ModuleBService.java")
            ),
            properties(
                """
                server.port=8082
                """,
                spec -> spec.path("module-b/src/main/resources/application.properties")
            )
        );
    }

    @Test
    void rootProjectYamlAppliesToModuleResourceRoots() throws IOException {
        Path projectDir = tempDir.resolve("jsf-root-config");
        Path moduleA = projectDir.resolve("module-a");
        Files.createDirectories(moduleA);
        Files.writeString(projectDir.resolve("project.yaml"), """
                sources:
                  resources:
                    - src/root-resources
                """);
        Files.writeString(moduleA.resolve("pom.xml"), "<project/>");

        rewriteRun(
            java(
                """
                package com.example.modulea;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                @Named
                @ViewScoped
                public class ModuleAViewBean {
                }
                """,
                spec -> spec.path(moduleA.resolve("src/main/java/com/example/modulea/ModuleAViewBean.java").toString())
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated for JSF ViewScoped session configuration

                # ===== JSF ViewScoped Session Configuration =====
                # Generated because @ViewScoped beans detected
                server.servlet.session.tracking-modes=cookie
                server.servlet.session.cookie.same-site=lax
                joinfaces.jsf.client-window-mode=url
                """,
                spec -> spec.path(moduleA.resolve("src/root-resources/application.properties").toString())
            )
        );
    }

    // ===== FQCN Annotation Detection Tests =====

    @Test
    void noFalsePositiveForUnusedImport() {
        // Test: Unused Import loest KEINE Config-Generierung aus (MEDIUM Fix)
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example.web;

                import jakarta.faces.view.ViewScoped;
                import jakarta.inject.Named;

                // @ViewScoped annotation NOT used - import is dead code
                @Named
                public class MyBeanWithUnusedImport {
                }
                """
            )
        );
    }

    @Test
    void noChangeWhenSpringViewScopedWithSpringImport() {
        // Test: Spring ViewScoped mit Spring Import -> KEINE Session-Config
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example.spring;

                import org.springframework.stereotype.Component;

                @Component
                @org.springframework.web.context.annotation.ViewScoped
                public class FullyQualifiedSpringBean {
                }
                """
            )
        );
    }

    @Test
    void noFalsePositiveWhenSpringFqcnWithStaleJsfImport() {
        // Test: Spring FQCN ViewScoped mit ungenutztem JSF Import -> KEINE Session-Config
        // Dies testet das MEDIUM Finding: Stale JSF Import darf nicht zu false positive fuehren
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            java(
                """
                package com.example.mixed;

                // Stale/unused JSF import (dead code)
                import jakarta.faces.view.ViewScoped;
                import org.springframework.stereotype.Component;

                @Component
                // Using Spring ViewScoped via FQCN, NOT the JSF import
                @org.springframework.web.context.annotation.ViewScoped
                public class MixedImportBean {
                }
                """
            )
        );
    }
}
