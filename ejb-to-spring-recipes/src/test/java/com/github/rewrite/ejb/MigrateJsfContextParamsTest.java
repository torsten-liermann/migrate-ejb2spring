/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - web.xml context-param to application.properties tests
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
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.xml.Assertions.xml;

/**
 * Tests for {@link MigrateJsfContextParams}.
 * <p>
 * Test cases from P1.3 specification:
 * - PROJECT_STAGE in web.xml -> joinfaces.jsf.project-stage generated
 * - PrimeFaces THEME -> joinfaces.primefaces.theme generated
 * - Unknown context-param -> Marker
 */
class MigrateJsfContextParamsTest implements RewriteTest {

    @TempDir
    Path tempDir;

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigrateJsfContextParams());
    }

    @DocumentExample
    @Test
    void migrateProjectStageAndStateSavingMethod() {
        // Test: Standard JSF context-params -> JoinFaces application.properties
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
                             https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
                         version="6.0">

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>
                    <context-param>
                        <param-name>jakarta.faces.STATE_SAVING_METHOD</param-name>
                        <param-value>server</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.jsf.project-stage=Development
                joinfaces.jsf.state-saving-method=server
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migratePrimeFacesTheme() {
        // Test: PrimeFaces THEME -> joinfaces.primefaces.theme
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>primefaces.THEME</param-name>
                        <param-value>saga</param-value>
                    </context-param>
                    <context-param>
                        <param-name>primefaces.FONT_AWESOME</param-name>
                        <param-value>true</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.primefaces.theme=saga
                joinfaces.primefaces.font-awesome=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateJavaxFacesParams() {
        // Test: Legacy javax.faces.* params -> Same JoinFaces properties
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee" version="3.1">

                    <context-param>
                        <param-name>javax.faces.PROJECT_STAGE</param-name>
                        <param-value>Production</param-value>
                    </context-param>
                    <context-param>
                        <param-name>javax.faces.FACELETS_SKIP_COMMENTS</param-name>
                        <param-value>true</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.jsf.project-stage=Production
                joinfaces.jsf.facelets-skip-comments=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateMyFacesParams() {
        // Test: org.apache.myfaces.* params -> joinfaces.myfaces.*
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>org.apache.myfaces.STRICT_JSF_2_REFRESH_TARGET_AJAX</param-name>
                        <param-value>true</param-value>
                    </context-param>
                    <context-param>
                        <param-name>org.apache.myfaces.SERIALIZE_STATE_IN_SESSION</param-name>
                        <param-value>false</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.myfaces.strict-jsf-2-refresh-target-ajax=true
                joinfaces.myfaces.serialize-state-in-session=false
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateWithExistingApplicationProperties() {
        // Test: Append to existing application.properties
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                """
                server.port=8080
                spring.application.name=myapp
                """,
                """
                server.port=8080
                spring.application.name=myapp

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.jsf.project-stage=Development
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void noWebXmlNoChanges() {
        // Test: No web.xml -> No changes
        rewriteRun(
            properties(
                """
                server.port=8080
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void noJsfContextParamsNoChanges() {
        // Test: web.xml without JSF context-params -> No changes
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>some.other.param</param-name>
                        <param-value>value</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                """
                server.port=8080
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void idempotentMigrationDoesNotDuplicate() {
        // Test: Running twice doesn't duplicate
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            // Already has JoinFaces properties
            properties(
                """
                server.port=8080

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.jsf.project-stage=Development
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateAllJsfParams() {
        // Test: Multiple JSF params from different sources
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>
                    <context-param>
                        <param-name>jakarta.faces.FACELETS_REFRESH_PERIOD</param-name>
                        <param-value>1</param-value>
                    </context-param>
                    <context-param>
                        <param-name>jakarta.faces.INTERPRET_EMPTY_STRING_SUBMITTED_VALUES_AS_NULL</param-name>
                        <param-value>true</param-value>
                    </context-param>
                    <context-param>
                        <param-name>primefaces.THEME</param-name>
                        <param-value>nova-light</param-value>
                    </context-param>
                    <context-param>
                        <param-name>primefaces.CLIENT_SIDE_VALIDATION</param-name>
                        <param-value>true</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.jsf.project-stage=Development
                joinfaces.jsf.facelets-refresh-period=1
                joinfaces.jsf.interpret-empty-string-submitted-values-as-null=true
                joinfaces.primefaces.theme=nova-light
                joinfaces.primefaces.client-side-validation=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateUnknownJakartaFacesParam() {
        // Test: Unknown jakarta.faces.* param -> Generates commented out with TODO
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>jakarta.faces.CUSTOM_UNKNOWN_PARAM</param-name>
                        <param-value>someValue</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                # TODO: Unknown JSF param - verify JoinFaces property name
                # Original: jakarta.faces.CUSTOM_UNKNOWN_PARAM=someValue
                # joinfaces.jsf.custom-unknown-param=someValue
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migratePrimeFacesAdvancedParams() {
        // Test: More PrimeFaces params
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>primefaces.SUBMIT</param-name>
                        <param-value>partial</param-value>
                    </context-param>
                    <context-param>
                        <param-name>primefaces.MOVE_SCRIPTS_TO_BOTTOM</param-name>
                        <param-value>true</param-value>
                    </context-param>
                    <context-param>
                        <param-name>primefaces.CSP</param-name>
                        <param-value>true</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.primefaces.submit=partial
                joinfaces.primefaces.move-scripts-to-bottom=true
                joinfaces.primefaces.csp=true
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateOnlyJsfParamsIgnoreOthers() {
        // Test: Only migrate JSF params, ignore non-JSF params
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>
                    <context-param>
                        <param-name>some.custom.application.param</param-name>
                        <param-value>customValue</param-value>
                    </context-param>
                    <context-param>
                        <param-name>spring.profiles.active</param-name>
                        <param-value>dev</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.jsf.project-stage=Development
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateWithExistingPartialJoinfacesConfig() {
        // Test: Existing partial JoinFaces config - should still migrate new params
        // This is the fix for HIGH issue: migration was skipped when any joinfaces.* key existed
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>
                    <context-param>
                        <param-name>primefaces.THEME</param-name>
                        <param-value>saga</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            // Existing application.properties has some manual JoinFaces config but no migration marker
            properties(
                """
                server.port=8080
                joinfaces.jsf.facelets-skip-comments=true
                """,
                """
                server.port=8080
                joinfaces.jsf.facelets-skip-comments=true

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.jsf.project-stage=Development
                joinfaces.primefaces.theme=saga
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void migrateFromMultipleWebXmlFiles() throws IOException {
        // Test: Multiple web.xml files in multi-module project - outputs are per module
        Path projectDir = tempDir.resolve("jsf-multi-module");
        Path moduleA = projectDir.resolve("module-a");
        Path moduleB = projectDir.resolve("module-b");
        Files.createDirectories(moduleA);
        Files.createDirectories(moduleB);
        Files.writeString(moduleA.resolve("pom.xml"), "<project/>");
        Files.writeString(moduleB.resolve("pom.xml"), "<project/>");
        Files.writeString(moduleA.resolve("project.yaml"), """
                sources:
                  resources:
                    - src/module-a-resources
                """);
        Files.writeString(moduleB.resolve("project.yaml"), """
                sources:
                  resources:
                    - src/module-b-resources
                """);

        rewriteRun(
            // First module's web.xml
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path(moduleA.resolve("src/main/webapp/WEB-INF/web.xml").toString())
            ),
            // Second module's web.xml
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>primefaces.THEME</param-name>
                        <param-value>saga</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path(moduleB.resolve("src/main/webapp/WEB-INF/web.xml").toString())
            ),
            // Module A properties
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.jsf.project-stage=Development
                """,
                spec -> spec.path(moduleA.resolve("src/module-a-resources/application.properties").toString())
            ),
            // Module B properties
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.primefaces.theme=saga
                """,
                spec -> spec.path(moduleB.resolve("src/module-b-resources/application.properties").toString())
            )
        );
    }

    @Test
    void migrateWithCustomResourceRoot() throws IOException {
        Path projectDir = tempDir.resolve("jsf-custom-resources");
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
        Files.writeString(projectDir.resolve("project.yaml"), """
                sources:
                  resources:
                    - src/custom/resources
                """);

        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path(projectDir.resolve("src/main/webapp/WEB-INF/web.xml").toString())
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.jsf.project-stage=Development
                """,
                spec -> spec.path(projectDir.resolve("src/custom/resources/application.properties").toString())
            )
        );
    }

    @Test
    void migrateWithRootProjectYamlForModule() throws IOException {
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
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path(moduleA.resolve("src/main/webapp/WEB-INF/web.xml").toString())
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                joinfaces.jsf.project-stage=Development
                """,
                spec -> spec.path(moduleA.resolve("src/root-resources/application.properties").toString())
            )
        );
    }

    @Test
    void migrateUnknownPrimeFacesParam() {
        // Test: Unknown primefaces.* param -> Generates commented out with TODO
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <context-param>
                        <param-name>primefaces.CUSTOM_SETTING</param-name>
                        <param-value>customValue</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            ),
            properties(
                null,
                """
                # Spring Boot Application Properties
                # Generated from web.xml JSF context-params migration

                # ===== JoinFaces JSF Configuration (migrated from web.xml) =====
                # TODO: Unknown PrimeFaces param - verify JoinFaces property name
                # Original: primefaces.CUSTOM_SETTING=customValue
                # joinfaces.primefaces.custom-setting=customValue
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }
}
