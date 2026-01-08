/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - tests for RewriteJsfResourceReferences
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

import static org.openrewrite.xml.Assertions.xml;

/**
 * Tests for {@link RewriteJsfResourceReferences}.
 * <p>
 * Test cases from P1.1 specification:
 * - ui:include with absolute path -> Rewrite to relative
 * - ui:include with relative path -> Unchanged
 * - ui:include with EL expression -> Marker
 * - ui:composition with absolute path -> Rewrite to relative
 * - HTML link element -> Marker
 * - h:link with absolute outcome -> Marker
 * - h:button with EL outcome -> Marker
 * - javax.faces.resource URL -> Marker
 */
class RewriteJsfResourceReferencesTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new RewriteJsfResourceReferences());
    }

    @DocumentExample
    @Test
    void uiIncludeWithAbsolutePath_shouldRewriteToRelative() {
        // Test: ui:include with absolute path /templates/x.xhtml -> templates/x.xhtml
        rewriteRun(
            xml(
                """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets"
                      xmlns:h="jakarta.faces.html">
                <h:body>
                    <ui:include src="/templates/header.xhtml"/>
                    <div class="content">
                        <ui:insert name="content"/>
                    </div>
                    <ui:include src="/templates/footer.xhtml"/>
                </h:body>
                </html>
                """,
                """
                <!DOCTYPE html>
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets"
                      xmlns:h="jakarta.faces.html">
                <h:body>
                    <ui:include src="templates/header.xhtml"/>
                    <div class="content">
                        <ui:insert name="content"/>
                    </div>
                    <ui:include src="templates/footer.xhtml"/>
                </h:body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/pages/home.xhtml")
            )
        );
    }

    @Test
    void uiIncludeWithWebInfPath_shouldRewriteToRelative() {
        // Test: ui:include with /WEB-INF/templates/... -> templates/...
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <ui:include src="/WEB-INF/templates/header.xhtml"/>
                    <ui:include src="/WEB-INF/includes/navigation.xhtml"/>
                </body>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <ui:include src="templates/header.xhtml"/>
                    <ui:include src="includes/navigation.xhtml"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void uiIncludeWithRelativePath_shouldRemainUnchanged() {
        // Test: ui:include with relative path -> Unchanged
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <ui:include src="templates/header.xhtml"/>
                    <ui:include src="../common/footer.xhtml"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/pages/home.xhtml")
            )
        );
    }

    @Test
    void uiIncludeWithElExpression_shouldAddMarker() {
        // Test: ui:include with EL expression -> Marker at the tag
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <ui:include src="#{bean.templatePath}"/>
                </body>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <!--~~(ui:include src contains EL expression - not deterministic. Verify path resolution after file relocation to META-INF/resources. Original: src="#{bean.templatePath}")~~>--><ui:include src="#{bean.templatePath}"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/pages/dynamic.xhtml")
            )
        );
    }

    @Test
    void uiCompositionWithAbsoluteTemplate_shouldRewriteToRelative() {
        // Test: ui:composition with absolute template -> Rewrite to relative
        rewriteRun(
            xml(
                """
                <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                                xmlns:ui="jakarta.faces.facelets"
                                xmlns:h="jakarta.faces.html"
                                template="/templates/layout.xhtml">
                    <ui:define name="content">
                        <h1>Page Content</h1>
                    </ui:define>
                </ui:composition>
                """,
                """
                <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                                xmlns:ui="jakarta.faces.facelets"
                                xmlns:h="jakarta.faces.html"
                                template="templates/layout.xhtml">
                    <ui:define name="content">
                        <h1>Page Content</h1>
                    </ui:define>
                </ui:composition>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/pages/content.xhtml")
            )
        );
    }

    @Test
    void uiCompositionWithWebInfTemplate_shouldRewriteToRelative() {
        // Test: ui:composition with /WEB-INF/templates/... -> templates/...
        rewriteRun(
            xml(
                """
                <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                                xmlns:ui="jakarta.faces.facelets"
                                template="/WEB-INF/templates/layout.xhtml">
                    <ui:define name="title">My Page</ui:define>
                </ui:composition>
                """,
                """
                <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                                xmlns:ui="jakarta.faces.facelets"
                                template="templates/layout.xhtml">
                    <ui:define name="title">My Page</ui:define>
                </ui:composition>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/pages/mypage.xhtml")
            )
        );
    }

    @Test
    void uiDecorateWithAbsoluteTemplate_shouldRewriteToRelative() {
        // Test: ui:decorate with absolute template -> Rewrite to relative
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <ui:decorate template="/templates/panel.xhtml">
                        <ui:define name="header">Panel Title</ui:define>
                        <ui:define name="body">Panel Content</ui:define>
                    </ui:decorate>
                </body>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <ui:decorate template="templates/panel.xhtml">
                        <ui:define name="header">Panel Title</ui:define>
                        <ui:define name="body">Panel Content</ui:define>
                    </ui:decorate>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/pages/decorated.xhtml")
            )
        );
    }

    @Test
    void htmlLinkElement_shouldAddMarker() {
        // Test: HTML link element -> Marker at the tag
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <link href="/resources/css/style.css" rel="stylesheet"/>
                </head>
                <body/>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <!--~~(HTML link element with resource path. Options: 1) Convert to JSF tag (h:outputStylesheet/h:outputScript/h:graphicImage) 2) Use EL: href="#{resource['library:name']}" 3) Manually adjust path. Original: href="/resources/css/style.css")~~>--><link href="/resources/css/style.css" rel="stylesheet"/>
                </head>
                <body/>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void htmlScriptElement_shouldAddMarker() {
        // Test: HTML script element -> Marker at the tag
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <script src="/resources/js/app.js"></script>
                </head>
                <body/>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <head>
                    <!--~~(HTML script element with resource path. Options: 1) Convert to JSF tag (h:outputStylesheet/h:outputScript/h:graphicImage) 2) Use EL: src="#{resource['library:name']}" 3) Manually adjust path. Original: src="/resources/js/app.js")~~>--><script src="/resources/js/app.js"></script>
                </head>
                <body/>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void htmlImgElement_shouldAddMarker() {
        // Test: HTML img element -> Marker at the tag
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                    <img src="/images/logo.png"/>
                </body>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml">
                <body>
                    <!--~~(HTML img element with resource path. Options: 1) Convert to JSF tag (h:outputStylesheet/h:outputScript/h:graphicImage) 2) Use EL: src="#{resource['library:name']}" 3) Manually adjust path. Original: src="/images/logo.png")~~>--><img src="/images/logo.png"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void hLinkWithAbsoluteOutcome_shouldAddMarker() {
        // Test: h:link with absolute outcome -> Marker at the tag
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <body>
                    <h:link outcome="/admin/dashboard" value="Dashboard"/>
                </body>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <body>
                    <!--~~(h:link with absolute outcome path. Verify navigation after view relocation to META-INF/resources. Consider relative outcome or update faces-config.xml navigation rules. Original: outcome="/admin/dashboard")~~>--><h:link outcome="/admin/dashboard" value="Dashboard"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void hButtonWithElOutcome_shouldAddMarker() {
        // Test: h:button with EL outcome -> Marker at the tag
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <body>
                    <h:button outcome="#{navigation.target}" value="Go"/>
                </body>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <body>
                    <!--~~(h:button with EL expression in outcome. Verify navigation after view relocation to META-INF/resources. Consider relative outcome or update faces-config.xml navigation rules. Original: outcome="#{navigation.target}")~~>--><h:button outcome="#{navigation.target}" value="Go"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void hLinkWithRelativeOutcome_shouldRemainUnchanged() {
        // Test: h:link with relative outcome -> No change
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <body>
                    <h:link outcome="details" value="View Details"/>
                    <h:button outcome="confirm" value="Confirm"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void hOutputStylesheetWithValue_shouldAddMarker() {
        // Test: h:outputStylesheet with value attribute -> Marker at the tag
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <h:head>
                    <h:outputStylesheet value="/resources/css/style.css"/>
                </h:head>
                <body/>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <h:head>
                    <!--~~(h:outputStylesheet with value attribute. Recommended: convert to library/name form. Example: value="/resources/css/style.css" -> library="css" name="style.css". Original: value="/resources/css/style.css")~~>--><h:outputStylesheet value="/resources/css/style.css"/>
                </h:head>
                <body/>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void hOutputScriptWithValue_shouldAddMarker() {
        // Test: h:outputScript with value attribute -> Marker at the tag
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <h:head>
                    <h:outputScript value="/resources/js/app.js"/>
                </h:head>
                <body/>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <h:head>
                    <!--~~(h:outputScript with value attribute. Recommended: convert to library/name form. Example: value="/resources/css/style.css" -> library="css" name="style.css". Original: value="/resources/js/app.js")~~>--><h:outputScript value="/resources/js/app.js"/>
                </h:head>
                <body/>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void hOutputStylesheetWithLibraryName_shouldRemainUnchanged() {
        // Test: h:outputStylesheet with library/name -> No change (safe form)
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <h:head>
                    <h:outputStylesheet library="css" name="style.css"/>
                    <h:outputScript library="js" name="app.js"/>
                </h:head>
                <body/>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void javaxFacesResourceUrl_shouldAddMarker() {
        // Test: javax.faces.resource URL in ui:include -> Marker at the tag
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <ui:include src="/javax.faces.resource/css/style.css.xhtml?ln=primefaces"/>
                </body>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <!--~~(Legacy javax.faces.resource URL detected. Convert to JSF resource tag: <h:outputStylesheet library="..." name="..."/> or <h:outputScript library="..." name="..."/>. Original URL: /javax.faces.resource/css/style.css.xhtml?ln=primefaces)~~>--><ui:include src="/javax.faces.resource/css/style.css.xhtml?ln=primefaces"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void nonXhtmlFile_shouldNotBeProcessed() {
        // Test: Non-XHTML files should not be processed
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                    <modelVersion>4.0.0</modelVersion>
                </project>
                """,
                spec -> spec.path("pom.xml")
            )
        );
    }

    @Test
    void combinedRewritesAndMarkers() {
        // Test: File with both rewrites and markers
        rewriteRun(
            xml(
                """
                <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                                xmlns:ui="jakarta.faces.facelets"
                                xmlns:h="jakarta.faces.html"
                                template="/templates/layout.xhtml">
                    <ui:define name="content">
                        <ui:include src="/WEB-INF/includes/header.xhtml"/>
                        <h:link outcome="/admin/page" value="Admin"/>
                        <ui:include src="relative/footer.xhtml"/>
                    </ui:define>
                </ui:composition>
                """,
                """
                <ui:composition xmlns="http://www.w3.org/1999/xhtml"
                                xmlns:ui="jakarta.faces.facelets"
                                xmlns:h="jakarta.faces.html"
                                template="templates/layout.xhtml">
                    <ui:define name="content">
                        <ui:include src="includes/header.xhtml"/>
                        <!--~~(h:link with absolute outcome path. Verify navigation after view relocation to META-INF/resources. Consider relative outcome or update faces-config.xml navigation rules. Original: outcome="/admin/page")~~>--><h:link outcome="/admin/page" value="Admin"/>
                        <ui:include src="relative/footer.xhtml"/>
                    </ui:define>
                </ui:composition>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/pages/combined.xhtml")
            )
        );
    }

    @Test
    void mixedElAndStaticPaths_shouldHandleBoth() {
        // Test: EL in one include, static in another
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <ui:include src="/templates/header.xhtml"/>
                    <ui:include src="#{dynamicPath}"/>
                    <ui:include src="/templates/footer.xhtml"/>
                </body>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <ui:include src="templates/header.xhtml"/>
                    <!--~~(ui:include src contains EL expression - not deterministic. Verify path resolution after file relocation to META-INF/resources. Original: src="#{dynamicPath}")~~>--><ui:include src="#{dynamicPath}"/>
                    <ui:include src="templates/footer.xhtml"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/pages/mixed.xhtml")
            )
        );
    }

    @Test
    void uiIncludeWithUnknownWebInfPath_shouldAddMarker() {
        // Test: ui:include with /WEB-INF/ path outside templates/includes -> Marker (not rewrite)
        // These files are moved to META-INF/resources/WEB-INF/ and should not be rewritten
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <ui:include src="/WEB-INF/fragments/menu.xhtml"/>
                    <ui:include src="/WEB-INF/custom/dialog.xhtml"/>
                </body>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:ui="jakarta.faces.facelets">
                <body>
                    <!--~~(ui:include src references a WEB-INF path outside templates/includes. This file was moved to META-INF/resources/WEB-INF/. Verify path resolution and update manually if needed. Original: src="/WEB-INF/fragments/menu.xhtml")~~>--><ui:include src="/WEB-INF/fragments/menu.xhtml"/>
                    <!--~~(ui:include src references a WEB-INF path outside templates/includes. This file was moved to META-INF/resources/WEB-INF/. Verify path resolution and update manually if needed. Original: src="/WEB-INF/custom/dialog.xhtml")~~>--><ui:include src="/WEB-INF/custom/dialog.xhtml"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }

    @Test
    void hLinkShouldNotReceiveHtmlMarker() {
        // Test: h:link should only receive outcome marker, NOT HTML link marker
        // This tests the fix for misclassification where h:link localName "link" matched HTML_RESOURCE_TAGS
        rewriteRun(
            xml(
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <body>
                    <h:link outcome="/pages/detail" value="View Details"/>
                </body>
                </html>
                """,
                """
                <html xmlns="http://www.w3.org/1999/xhtml"
                      xmlns:h="jakarta.faces.html">
                <body>
                    <!--~~(h:link with absolute outcome path. Verify navigation after view relocation to META-INF/resources. Consider relative outcome or update faces-config.xml navigation rules. Original: outcome="/pages/detail")~~>--><h:link outcome="/pages/detail" value="View Details"/>
                </body>
                </html>
                """,
                spec -> spec.path("src/main/resources/META-INF/resources/index.xhtml")
            )
        );
    }
}
