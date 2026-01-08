/*
 * Copyright 2021 - 2023 the original author or authors.
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

class CleanupJsfWebXmlTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new CleanupJsfWebXml());
    }

    @DocumentExample
    @Test
    void removeStandardFacesServletConfig() {
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
                         version="6.0">

                    <servlet>
                        <servlet-name>FacesServlet</servlet-name>
                        <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                        <load-on-startup>1</load-on-startup>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>FacesServlet</servlet-name>
                        <url-pattern>*.xhtml</url-pattern>
                    </servlet-mapping>

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>

                </web-app>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
                         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd"
                         version="6.0">
                <!-- This web.xml can be deleted - all JSF configuration is handled by JoinFaces auto-configuration -->

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void removeJavaxFacesServletConfig() {
        // Test with javax namespace (pre-Jakarta EE 9)
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee"
                         version="4.0">

                    <servlet>
                        <servlet-name>Faces Servlet</servlet-name>
                        <servlet-class>javax.faces.webapp.FacesServlet</servlet-class>
                        <load-on-startup>1</load-on-startup>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>Faces Servlet</servlet-name>
                        <url-pattern>*.xhtml</url-pattern>
                    </servlet-mapping>

                    <context-param>
                        <param-name>javax.faces.PROJECT_STAGE</param-name>
                        <param-value>Production</param-value>
                    </context-param>
                    <context-param>
                        <param-name>javax.faces.STATE_SAVING_METHOD</param-name>
                        <param-value>server</param-value>
                    </context-param>

                </web-app>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee"
                         version="4.0">
                <!-- This web.xml can be deleted - all JSF configuration is handled by JoinFaces auto-configuration -->

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void markCustomUrlPatternWithNonJsfContent() {
        // Test with custom URL pattern (*.jsf instead of *.xhtml) + non-JSF content
        // When there is non-JSF content, marker comments are preserved
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <servlet>
                        <servlet-name>FacesServlet</servlet-name>
                        <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>FacesServlet</servlet-name>
                        <url-pattern>*.jsf</url-pattern>
                    </servlet-mapping>

                    <context-param>
                        <param-name>myapp.custom.setting</param-name>
                        <param-value>someValue</param-value>
                    </context-param>

                </web-app>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0"><!--
                    TODO: Custom JSF URL patterns detected: [*.jsf]
                    Configure in application.properties: joinfaces.faces.webapp.url-mappings=*.jsf
                    -->

                    <context-param>
                        <param-name>myapp.custom.setting</param-name>
                        <param-value>someValue</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void preserveNonJsfServlets() {
        // Test that non-JSF servlets are preserved
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <servlet>
                        <servlet-name>FacesServlet</servlet-name>
                        <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>FacesServlet</servlet-name>
                        <url-pattern>*.xhtml</url-pattern>
                    </servlet-mapping>

                    <servlet>
                        <servlet-name>RestServlet</servlet-name>
                        <servlet-class>com.example.RestServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>RestServlet</servlet-name>
                        <url-pattern>/api/*</url-pattern>
                    </servlet-mapping>

                </web-app>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <servlet>
                        <servlet-name>RestServlet</servlet-name>
                        <servlet-class>com.example.RestServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>RestServlet</servlet-name>
                        <url-pattern>/api/*</url-pattern>
                    </servlet-mapping>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void removeAllJsfContextParams() {
        // Test removal of various JSF context-params
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <servlet>
                        <servlet-name>FacesServlet</servlet-name>
                        <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>FacesServlet</servlet-name>
                        <url-pattern>*.xhtml</url-pattern>
                    </servlet-mapping>

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>
                    <context-param>
                        <param-name>jakarta.faces.STATE_SAVING_METHOD</param-name>
                        <param-value>server</param-value>
                    </context-param>
                    <context-param>
                        <param-name>jakarta.faces.FACELETS_REFRESH_PERIOD</param-name>
                        <param-value>0</param-value>
                    </context-param>
                    <context-param>
                        <param-name>facelets.DEVELOPMENT</param-name>
                        <param-value>true</param-value>
                    </context-param>
                    <context-param>
                        <param-name>primefaces.THEME</param-name>
                        <param-value>saga</param-value>
                    </context-param>

                </web-app>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">
                <!-- This web.xml can be deleted - all JSF configuration is handled by JoinFaces auto-configuration -->

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void preserveNonJsfContextParams() {
        // Test that non-JSF context-params are preserved
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <servlet>
                        <servlet-name>FacesServlet</servlet-name>
                        <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>FacesServlet</servlet-name>
                        <url-pattern>*.xhtml</url-pattern>
                    </servlet-mapping>

                    <context-param>
                        <param-name>jakarta.faces.PROJECT_STAGE</param-name>
                        <param-value>Development</param-value>
                    </context-param>
                    <context-param>
                        <param-name>myapp.custom.setting</param-name>
                        <param-value>someValue</param-value>
                    </context-param>

                </web-app>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">
                    <context-param>
                        <param-name>myapp.custom.setting</param-name>
                        <param-value>someValue</param-value>
                    </context-param>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void removeMojarraAndMyFacesParams() {
        // Test removal of implementation-specific params
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <servlet>
                        <servlet-name>FacesServlet</servlet-name>
                        <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>FacesServlet</servlet-name>
                        <url-pattern>*.xhtml</url-pattern>
                    </servlet-mapping>

                    <context-param>
                        <param-name>com.sun.faces.enableViewStateIdRendering</param-name>
                        <param-value>true</param-value>
                    </context-param>
                    <context-param>
                        <param-name>org.apache.myfaces.SERIALIZE_STATE_IN_SESSION</param-name>
                        <param-value>false</param-value>
                    </context-param>

                </web-app>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">
                <!-- This web.xml can be deleted - all JSF configuration is handled by JoinFaces auto-configuration -->

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void noChangesWhenNoFacesServlet() {
        // Test that nothing happens when there's no FacesServlet
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <servlet>
                        <servlet-name>MyServlet</servlet-name>
                        <servlet-class>com.example.MyServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>MyServlet</servlet-name>
                        <url-pattern>/my/*</url-pattern>
                    </servlet-mapping>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void preserveWelcomeFileList() {
        // Test that welcome-file-list is preserved
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <servlet>
                        <servlet-name>FacesServlet</servlet-name>
                        <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>FacesServlet</servlet-name>
                        <url-pattern>*.xhtml</url-pattern>
                    </servlet-mapping>

                    <welcome-file-list>
                        <welcome-file>index.xhtml</welcome-file>
                    </welcome-file-list>

                </web-app>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <welcome-file-list>
                        <welcome-file>index.xhtml</welcome-file>
                    </welcome-file-list>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void handleMultipleUrlPatternsWithNonJsfContent() {
        // Test with multiple custom URL patterns + non-JSF content
        // When there is non-JSF content, marker comments are preserved
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <servlet>
                        <servlet-name>FacesServlet</servlet-name>
                        <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>FacesServlet</servlet-name>
                        <url-pattern>*.jsf</url-pattern>
                    </servlet-mapping>
                    <servlet-mapping>
                        <servlet-name>FacesServlet</servlet-name>
                        <url-pattern>/faces/*</url-pattern>
                    </servlet-mapping>

                    <session-config>
                        <session-timeout>30</session-timeout>
                    </session-config>

                </web-app>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0"><!--
                    TODO: Custom JSF URL patterns detected: [*.jsf, /faces/*]
                    Configure in application.properties: joinfaces.faces.webapp.url-mappings=*.jsf,/faces/*
                    -->

                    <session-config>
                        <session-timeout>30</session-timeout>
                    </session-config>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void preserveSessionConfig() {
        // Test that session-config is preserved
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <servlet>
                        <servlet-name>FacesServlet</servlet-name>
                        <servlet-class>jakarta.faces.webapp.FacesServlet</servlet-class>
                    </servlet>
                    <servlet-mapping>
                        <servlet-name>FacesServlet</servlet-name>
                        <url-pattern>*.xhtml</url-pattern>
                    </servlet-mapping>

                    <session-config>
                        <session-timeout>30</session-timeout>
                    </session-config>

                </web-app>
                """,
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee" version="6.0">

                    <session-config>
                        <session-timeout>30</session-timeout>
                    </session-config>

                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }
}
