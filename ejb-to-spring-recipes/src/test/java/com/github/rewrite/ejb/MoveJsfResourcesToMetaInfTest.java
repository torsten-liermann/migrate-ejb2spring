/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - tests for MoveJsfResourcesToMetaInf
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
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.xml.Assertions.xml;
import static org.openrewrite.test.SourceSpecs.text;

/**
 * Tests for {@link MoveJsfResourcesToMetaInf}.
 * <p>
 * Test cases from P1.1 specification:
 * - faces-config.xml in WEB-INF -> Moved to META-INF
 * - XHTML in webapp -> Moved to META-INF/resources
 * - Templates in WEB-INF/templates -> Moved to META-INF/resources/templates
 * - Static resources -> Moved to META-INF/resources
 * - web.xml -> NOT moved (excluded)
 */
class MoveJsfResourcesToMetaInfTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MoveJsfResourcesToMetaInf())
            .expectedCyclesThatMakeChanges(1);
    }

    @DocumentExample
    @Test
    void facesConfigInWebInf_shouldMoveToMetaInf() {
        // Test: faces-config.xml in WEB-INF -> Moved to META-INF
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://jakarta.ee/xml/ns/jakartaee"
                              version="4.0">
                    <navigation-rule>
                        <from-view-id>/login.xhtml</from-view-id>
                        <navigation-case>
                            <to-view-id>/home.xhtml</to-view-id>
                        </navigation-case>
                    </navigation-rule>
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/faces-config.xml"));
                            })
            )
        );
    }

    @Test
    void xhtmlInWebappRoot_shouldMoveToMetaInfResources() {
        // Test: XHTML in webapp root -> Moved to META-INF/resources
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            xml(
                """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <h:head>
                    <title>Home</title>
                </h:head>
                <h:body>
                    <h1>Welcome</h1>
                </h:body>
                </html>
                """,
                spec -> spec.path("src/main/webapp/index.xhtml")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/index.xhtml"));
                            })
            )
        );
    }

    @Test
    void xhtmlInPagesSubdirectory_shouldMoveToMetaInfResourcesPages() {
        // Test: XHTML in pages/ subdirectory -> Moved to META-INF/resources/pages/
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            xml(
                """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <h:head>
                    <title>User List</title>
                </h:head>
                <h:body>
                    <h1>Users</h1>
                </h:body>
                </html>
                """,
                spec -> spec.path("src/main/webapp/pages/userList.xhtml")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/pages/userList.xhtml"));
                            })
            )
        );
    }

    @Test
    void templatesInWebInf_shouldMoveToMetaInfResourcesTemplates() {
        // Test: Templates in WEB-INF/templates -> Moved to META-INF/resources/templates
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            xml(
                """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html"
                      xmlns:ui="jakarta.faces.facelets">
                <h:head>
                    <title><ui:insert name="title">Default Title</ui:insert></title>
                </h:head>
                <h:body>
                    <div id="header">
                        <ui:insert name="header"/>
                    </div>
                    <div id="content">
                        <ui:insert name="content"/>
                    </div>
                </h:body>
                </html>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/templates/layout.xhtml")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/templates/layout.xhtml"));
                            })
            )
        );
    }

    @Test
    void includesInWebInf_shouldMoveToMetaInfResourcesIncludes() {
        // Test: Includes in WEB-INF/includes -> Moved to META-INF/resources/includes
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            xml(
                """
                <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                                xmlns:ui="jakarta.faces.facelets"
                                xmlns:h="jakarta.faces.html">
                    <nav>
                        <h:link outcome="home" value="Home"/>
                        <h:link outcome="about" value="About"/>
                    </nav>
                </ui:composition>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/includes/navigation.xhtml")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/includes/navigation.xhtml"));
                            })
            )
        );
    }

    @Test
    void cssInResources_shouldMoveToMetaInfResources() {
        // Test: CSS in resources/ -> Moved to META-INF/resources/
        // Strip the leading "resources/" to preserve JSF library resolution
        // e.g., <h:outputStylesheet library="css" name="style.css"/> expects META-INF/resources/css/style.css
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            text(
                """
                body {
                    font-family: Arial, sans-serif;
                    margin: 0;
                    padding: 0;
                }
                """,
                spec -> spec.path("src/main/webapp/resources/css/style.css")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/css/style.css"));
                            })
            )
        );
    }

    @Test
    void jsInResources_shouldMoveToMetaInfResources() {
        // Test: JS in resources/ -> Moved to META-INF/resources/
        // Strip the leading "resources/" to preserve JSF library resolution
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            text(
                """
                function init() {
                    console.log('App initialized');
                }
                """,
                spec -> spec.path("src/main/webapp/resources/js/app.js")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/js/app.js"));
                            })
            )
        );
    }

    @Test
    void imageInResources_shouldMoveToMetaInfResources() {
        // Test: Images in resources/images -> Moved to META-INF/resources/images
        // Strip the leading "resources/" to preserve JSF library resolution
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            text(
                "PNG image content placeholder",
                spec -> spec.path("src/main/webapp/resources/images/logo.png")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/images/logo.png"));
                            })
            )
        );
    }

    @Test
    void webXml_shouldNotBeMoved() {
        // Test: web.xml should NOT be moved (handled separately)
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
                         version="6.0">
                    <display-name>My JSF App</display-name>
                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void cssInCssDirectory_shouldMoveToMetaInfResources() {
        // Test: CSS files in css/ directory (not under resources/)
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            text(
                """
                .container {
                    max-width: 1200px;
                }
                """,
                spec -> spec.path("src/main/webapp/css/main.css")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/css/main.css"));
                            })
            )
        );
    }

    @Test
    void jsInJsDirectory_shouldMoveToMetaInfResources() {
        // Test: JS files in js/ directory
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            text(
                """
                console.log("Hello");
                """,
                spec -> spec.path("src/main/webapp/js/app.js")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/js/app.js"));
                            })
            )
        );
    }

    @Test
    void fontFile_shouldMoveToMetaInfResources() {
        // Test: Font files should be moved
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            text(
                "WOFF font content placeholder",
                spec -> spec.path("src/main/webapp/fonts/OpenSans.woff2")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/fonts/OpenSans.woff2"));
                            })
            )
        );
    }

    @Test
    void assetsDirectory_shouldMoveToMetaInfResources() {
        // Test: Assets directory structure should be preserved
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            text(
                "SVG icon content",
                spec -> spec.path("src/main/webapp/assets/icons/menu.svg")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/assets/icons/menu.svg"));
                            })
            )
        );
    }

    @Test
    void nonWebappFile_shouldNotBeMoved() {
        // Test: Files not in webapp should not be moved
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(0),
            text(
                """
                server.port=8080
                """,
                spec -> spec.path("src/main/resources/application.properties")
            )
        );
    }

    @Test
    void deeplyNestedXhtml_shouldMoveWithStructurePreserved() {
        // Test: Deeply nested XHTML maintains directory structure
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            xml(
                """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>Admin Dashboard</body>
                </html>
                """,
                spec -> spec.path("src/main/webapp/admin/reports/quarterly/dashboard.xhtml")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/admin/reports/quarterly/dashboard.xhtml"));
                            })
            )
        );
    }

    @Test
    void multipleFilesInDifferentLocations_shouldAllBeMoved() {
        // Test: Multiple files from different locations
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://jakarta.ee/xml/ns/jakartaee" version="4.0"/>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/faces-config.xml"));
                            })
            ),
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"><body>Index</body></html>
                """,
                spec -> spec.path("src/main/webapp/index.xhtml")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/index.xhtml"));
                            })
            ),
            xml(
                """
                <ui:composition xmlns:ui="jakarta.faces.facelets">Header</ui:composition>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/templates/header.xhtml")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/templates/header.xhtml"));
                            })
            )
        );
    }

    @Test
    void otherWebInfContent_shouldMoveToMetaInfResources() {
        // Test: Other WEB-INF content (not templates, includes, web.xml, faces-config.xml)
        // should move to META-INF/resources/WEB-INF/
        rewriteRun(
            spec -> spec.expectedCyclesThatMakeChanges(1),
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <custom-config>
                    <setting>value</setting>
                </custom-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/custom-config.xml")
                            .afterRecipe(sourceFile -> {
                                assertThat(sourceFile.getSourcePath())
                                    .isEqualTo(Paths.get("src/main/resources/META-INF/resources/WEB-INF/custom-config.xml"));
                            })
            )
        );
    }
}
