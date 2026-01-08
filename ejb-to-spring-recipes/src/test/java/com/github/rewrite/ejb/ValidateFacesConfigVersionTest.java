/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - faces-config.xml version validation tests
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
 * Tests for {@link ValidateFacesConfigVersion}.
 * <p>
 * Test cases from P0.2 specification:
 * - faces-config 2.3 with http://xmlns.jcp.org/... -> Marker with namespace upgrade
 * - faces-config 2.3 with https://xmlns.jcp.org/... -> Marker (HTTPS variant)
 * - faces-config without version with http://java.sun.com/... -> Marker "Legacy"
 * - faces-config 3.0 with jakarta.ee -> Marker "Upgrade to 4.0"
 * - faces-config 4.0 -> No marker
 * - No faces-config -> No marker
 */
class ValidateFacesConfigVersionTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ValidateFacesConfigVersion());
    }

    @DocumentExample
    @Test
    void facesConfig23WithJcpHttpNamespace_shouldAddMarker() {
        // Test: faces-config 2.3 with http://xmlns.jcp.org/... -> Marker with namespace upgrade
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://xmlns.jcp.org/xml/ns/javaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee
                                  http://xmlns.jcp.org/xml/ns/javaee/web-facesconfig_2_3.xsd"
                              version="2.3">
                </faces-config>
                """,
                """
                <!--~~(faces-config.xml version 2.3 with legacy namespace is incompatible. Namespace: http://xmlns.jcp.org/xml/ns/javaee.

                Upgrade faces-config.xml to version 4.0:
                1. Set version="4.0"
                2. Change namespace: xmlns="https://jakarta.ee/xml/ns/jakartaee"
                3. Update xsi:schemaLocation to https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd

                See: https://jakarta.ee/specifications/faces/4.0/)~~>--><?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://xmlns.jcp.org/xml/ns/javaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="http://xmlns.jcp.org/xml/ns/javaee
                                  http://xmlns.jcp.org/xml/ns/javaee/web-facesconfig_2_3.xsd"
                              version="2.3">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfig23WithJcpHttpsNamespace_shouldAddMarker() {
        // Test: faces-config 2.3 with https://xmlns.jcp.org/... -> Marker (HTTPS variant)
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://xmlns.jcp.org/xml/ns/javaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              version="2.3">
                </faces-config>
                """,
                """
                <!--~~(faces-config.xml version 2.3 with legacy namespace is incompatible. Namespace: https://xmlns.jcp.org/xml/ns/javaee.

                Upgrade faces-config.xml to version 4.0:
                1. Set version="4.0"
                2. Change namespace: xmlns="https://jakarta.ee/xml/ns/jakartaee"
                3. Update xsi:schemaLocation to https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd

                See: https://jakarta.ee/specifications/faces/4.0/)~~>--><?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://xmlns.jcp.org/xml/ns/javaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              version="2.3">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfigWithoutVersionAndSunNamespace_shouldAddLegacyMarker() {
        // Test: faces-config without version with http://java.sun.com/... -> Marker "Legacy"
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://java.sun.com/xml/ns/javaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                </faces-config>
                """,
                """
                <!--~~(faces-config.xml without version attribute. Legacy namespace detected: http://java.sun.com/xml/ns/javaee.

                Upgrade faces-config.xml to version 4.0:
                1. Set version="4.0"
                2. Change namespace: xmlns="https://jakarta.ee/xml/ns/jakartaee"
                3. Update xsi:schemaLocation to https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd

                See: https://jakarta.ee/specifications/faces/4.0/)~~>--><?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://java.sun.com/xml/ns/javaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfig30WithJakartaNamespace_shouldAddUpgradeMarker() {
        // Test: faces-config 3.0 with jakarta.ee -> Marker "Upgrade to 4.0"
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://jakarta.ee/xml/ns/jakartaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
                                  https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_3_0.xsd"
                              version="3.0">
                </faces-config>
                """,
                """
                <!--~~(faces-config.xml version 3.0 is incompatible with JoinFaces 5.x. Jakarta Faces 3.0 is Jakarta EE 9/9.1 - JoinFaces 5.x requires Jakarta EE 10 (Faces 4.0).

                Upgrade faces-config.xml to version 4.0:
                1. Set version="4.0"
                2. Verify xsi:schemaLocation uses web-facesconfig_4_0.xsd

                See: https://jakarta.ee/specifications/faces/4.0/)~~>--><?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://jakarta.ee/xml/ns/jakartaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
                                  https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_3_0.xsd"
                              version="3.0">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfig40WithJakartaNamespace_shouldNotAddMarker() {
        // Test: faces-config 4.0 -> No marker (compatible)
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://jakarta.ee/xml/ns/jakartaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="https://jakarta.ee/xml/ns/jakartaee
                                  https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd"
                              version="4.0">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfig41WithJakartaNamespace_shouldNotAddMarker() {
        // Test: faces-config 4.1 (future version) -> No marker (compatible)
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://jakarta.ee/xml/ns/jakartaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              version="4.1">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void noFacesConfig_shouldNotAddMarker() {
        // Test: No faces-config.xml -> No marker
        // Just verify other XML files are not affected
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <web-app xmlns="https://jakarta.ee/xml/ns/jakartaee"
                         version="6.0">
                </web-app>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/web.xml")
            )
        );
    }

    @Test
    void facesConfig22WithSunNamespace_shouldAddMarker() {
        // Test: JSF 2.2 with old Sun namespace
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://java.sun.com/xml/ns/javaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              version="2.2">
                </faces-config>
                """,
                """
                <!--~~(faces-config.xml version 2.2 with legacy namespace is incompatible. Namespace: http://java.sun.com/xml/ns/javaee.

                Upgrade faces-config.xml to version 4.0:
                1. Set version="4.0"
                2. Change namespace: xmlns="https://jakarta.ee/xml/ns/jakartaee"
                3. Update xsi:schemaLocation to https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd

                See: https://jakarta.ee/specifications/faces/4.0/)~~>--><?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://java.sun.com/xml/ns/javaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              version="2.2">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfig40WithHttpJakartaNamespace_shouldNotAddMarker() {
        // Test: faces-config 4.0 with http (not https) jakarta.ee namespace -> Compatible
        // Both http and https variants of jakarta.ee namespace are acceptable
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://jakarta.ee/xml/ns/jakartaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              version="4.0">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfigWithContentAndVersion40_shouldNotAddMarker() {
        // Test: Real-world faces-config.xml with navigation rules (version 4.0)
        rewriteRun(
            xml(
                """
                <?xml version="1.0"?>
                <faces-config xmlns="https://jakarta.ee/xml/ns/jakartaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                              xsi:schemaLocation="
                                  https://jakarta.ee/xml/ns/jakartaee
                                  https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd"
                              version="4.0">

                  <navigation-rule>
                    <from-view-id>/addCustomer.xhtml</from-view-id>
                    <navigation-case>
                      <from-action>#{customerManager.addCustomer}</from-action>
                      <from-outcome>customerAdded</from-outcome>
                      <to-view-id>/customers.xhtml</to-view-id>
                      <redirect />
                    </navigation-case>
                  </navigation-rule>

                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfigInMetaInf_shouldAlsoBeValidated() {
        // Test: faces-config.xml in META-INF/resources (Spring Boot location)
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://xmlns.jcp.org/xml/ns/javaee"
                              version="2.3">
                </faces-config>
                """,
                """
                <!--~~(faces-config.xml version 2.3 with legacy namespace is incompatible. Namespace: http://xmlns.jcp.org/xml/ns/javaee.

                Upgrade faces-config.xml to version 4.0:
                1. Set version="4.0"
                2. Change namespace: xmlns="https://jakarta.ee/xml/ns/jakartaee"
                3. Update xsi:schemaLocation to https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd

                See: https://jakarta.ee/specifications/faces/4.0/)~~>--><?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://xmlns.jcp.org/xml/ns/javaee"
                              version="2.3">
                </faces-config>
                """,
                spec -> spec.path("src/main/resources/META-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfigWithoutVersionButJakartaNamespace_shouldAddMarker() {
        // Test: faces-config without version but with jakarta.ee namespace
        // Still needs version attribute for clarity
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://jakarta.ee/xml/ns/jakartaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                </faces-config>
                """,
                """
                <!--~~(faces-config.xml without version attribute. Add version="4.0" for JoinFaces 5.x compatibility.

                Upgrade faces-config.xml to version 4.0:
                1. Set version="4.0"
                2. Verify xsi:schemaLocation uses web-facesconfig_4_0.xsd

                See: https://jakarta.ee/specifications/faces/4.0/)~~>--><?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="https://jakarta.ee/xml/ns/jakartaee"
                              xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfig20WithSunNamespace_shouldAddMarker() {
        // Test: Very old JSF 2.0 with Sun namespace
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://java.sun.com/xml/ns/javaee"
                              version="2.0">
                </faces-config>
                """,
                """
                <!--~~(faces-config.xml version 2.0 with legacy namespace is incompatible. Namespace: http://java.sun.com/xml/ns/javaee.

                Upgrade faces-config.xml to version 4.0:
                1. Set version="4.0"
                2. Change namespace: xmlns="https://jakarta.ee/xml/ns/jakartaee"
                3. Update xsi:schemaLocation to https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd

                See: https://jakarta.ee/specifications/faces/4.0/)~~>--><?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://java.sun.com/xml/ns/javaee"
                              version="2.0">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfigWithoutNamespace_shouldAddMarkerWithNamespaceInstructions() {
        // Test: faces-config.xml without xmlns namespace - needs both version and namespace
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config>
                </faces-config>
                """,
                """
                <!--~~(faces-config.xml without version attribute. Missing xmlns namespace.

                Upgrade faces-config.xml to version 4.0:
                1. Set version="4.0"
                2. Change namespace: xmlns="https://jakarta.ee/xml/ns/jakartaee"
                3. Update xsi:schemaLocation to https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd

                See: https://jakarta.ee/specifications/faces/4.0/)~~>--><?xml version="1.0" encoding="UTF-8"?>
                <faces-config>
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }

    @Test
    void facesConfigWithUnknownNamespaceAndNoVersion_shouldAddMarkerWithNamespaceInstructions() {
        // Test: faces-config.xml with unknown/custom namespace but no version - needs namespace change
        rewriteRun(
            xml(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://example.com/custom/namespace">
                </faces-config>
                """,
                """
                <!--~~(faces-config.xml without version attribute. Unknown namespace not compatible: http://example.com/custom/namespace.

                Upgrade faces-config.xml to version 4.0:
                1. Set version="4.0"
                2. Change namespace: xmlns="https://jakarta.ee/xml/ns/jakartaee"
                3. Update xsi:schemaLocation to https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd

                See: https://jakarta.ee/specifications/faces/4.0/)~~>--><?xml version="1.0" encoding="UTF-8"?>
                <faces-config xmlns="http://example.com/custom/namespace">
                </faces-config>
                """,
                spec -> spec.path("src/main/webapp/WEB-INF/faces-config.xml")
            )
        );
    }
}
