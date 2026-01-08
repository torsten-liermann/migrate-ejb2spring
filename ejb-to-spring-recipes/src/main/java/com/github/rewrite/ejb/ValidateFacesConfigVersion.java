/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - faces-config.xml version validation
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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Xml;

/**
 * Validates faces-config.xml version for JoinFaces 5.x compatibility.
 * <p>
 * JoinFaces 5.3.0 requires Jakarta Faces 4.0+ (faces-config version 4.0).
 * Older versions (2.x, 3.x) are incompatible and require upgrade.
 * <p>
 * <b>Version Compatibility:</b>
 * <table border="1">
 *   <tr><th>Version</th><th>Namespace</th><th>JoinFaces 5.x</th><th>Action</th></tr>
 *   <tr><td>missing</td><td>*</td><td>Incompatible</td><td>Marker: Upgrade to 4.0</td></tr>
 *   <tr><td>2.x</td><td>Legacy</td><td>Incompatible</td><td>Marker: Upgrade + namespace change</td></tr>
 *   <tr><td>3.0</td><td>jakarta.ee</td><td>Incompatible</td><td>Marker: Upgrade to 4.0</td></tr>
 *   <tr><td>4.0+</td><td>jakarta.ee</td><td>Compatible</td><td>OK, no marker</td></tr>
 * </table>
 * <p>
 * <b>Legacy Namespaces (all treated as incompatible):</b>
 * <ul>
 *   <li>{@code http://java.sun.com/xml/ns/javaee} - Legacy Sun/Oracle (JSF 1.x - 2.0)</li>
 *   <li>{@code http://xmlns.jcp.org/xml/ns/javaee} - Legacy JCP HTTP (JSF 2.1 - 2.3)</li>
 *   <li>{@code https://xmlns.jcp.org/xml/ns/javaee} - Legacy JCP HTTPS (JSF 2.1 - 2.3)</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ValidateFacesConfigVersion extends Recipe {

    // Jakarta EE 10 namespace (required for JoinFaces 5.x)
    private static final String JAKARTA_EE_NAMESPACE = "jakarta.ee/xml/ns/jakartaee";

    // Legacy namespaces (all incompatible with JoinFaces 5.x)
    private static final String LEGACY_SUN_NAMESPACE = "java.sun.com/xml/ns/javaee";
    private static final String LEGACY_JCP_NAMESPACE = "xmlns.jcp.org/xml/ns/javaee";

    // Minimum compatible version
    private static final double MIN_COMPATIBLE_VERSION = 4.0;

    @Override
    public String getDisplayName() {
        return "Validate faces-config.xml version for JoinFaces 5.x";
    }

    @Override
    public String getDescription() {
        return "Validates that faces-config.xml is compatible with JoinFaces 5.x (requires Jakarta Faces 4.0+). " +
               "Adds markers for incompatible versions with upgrade instructions.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new XmlVisitor<ExecutionContext>() {
            @Override
            public Xml visitDocument(Xml.Document document, ExecutionContext ctx) {
                String path = document.getSourcePath().toString();

                // Only process faces-config.xml files
                if (!path.endsWith("faces-config.xml")) {
                    return document;
                }

                // Get root tag
                Xml.Tag root = document.getRoot();
                if (root == null || !"faces-config".equals(root.getName())) {
                    return document;
                }

                // Extract version and namespace
                String version = extractVersion(root);
                String namespace = extractNamespace(root);

                // Determine compatibility and create marker if needed
                String marker = determineMarker(version, namespace);
                if (marker != null) {
                    return SearchResult.found(document, marker);
                }

                return document;
            }
        };
    }

    /**
     * Extracts the version attribute from the faces-config root element.
     *
     * @param root the faces-config root tag
     * @return the version string, or null if not present
     */
    private String extractVersion(Xml.Tag root) {
        for (Xml.Attribute attr : root.getAttributes()) {
            if ("version".equals(attr.getKeyAsString())) {
                return attr.getValueAsString();
            }
        }
        return null;
    }

    /**
     * Extracts the default namespace (xmlns) from the faces-config root element.
     *
     * @param root the faces-config root tag
     * @return the namespace URI, or null if not present
     */
    private String extractNamespace(Xml.Tag root) {
        for (Xml.Attribute attr : root.getAttributes()) {
            if ("xmlns".equals(attr.getKeyAsString())) {
                return attr.getValueAsString();
            }
        }
        return null;
    }

    /**
     * Determines the appropriate marker message based on version and namespace.
     *
     * @param version   the faces-config version (may be null)
     * @param namespace the faces-config namespace (may be null)
     * @return the marker message, or null if compatible
     */
    private String determineMarker(String version, String namespace) {
        boolean isLegacyNamespace = isLegacyNamespace(namespace);
        boolean isJakartaNamespace = isJakartaNamespace(namespace);

        // Case 1: No version attribute (Legacy faces-config)
        if (version == null || version.isEmpty()) {
            // Any non-Jakarta namespace requires namespace to be added/changed
            if (!isJakartaNamespace) {
                String details;
                if (namespace == null) {
                    details = "Missing xmlns namespace";
                } else if (isLegacyNamespace) {
                    details = "Legacy namespace detected: " + namespace;
                } else {
                    details = "Unknown namespace not compatible: " + namespace;
                }
                return buildMarker(
                    "faces-config.xml without version attribute",
                    details,
                    true
                );
            }
            // No version but jakarta namespace - still needs version 4.0
            return buildMarker(
                "faces-config.xml without version attribute",
                "Add version=\"4.0\" for JoinFaces 5.x compatibility",
                false
            );
        }

        // Parse version number
        double versionNum;
        try {
            versionNum = Double.parseDouble(version);
        } catch (NumberFormatException e) {
            return buildMarker(
                "Invalid faces-config.xml version: " + version,
                "Expected numeric version (e.g., 4.0)",
                isLegacyNamespace
            );
        }

        // Case 2: Version 4.0+ with jakarta namespace - Compatible!
        if (versionNum >= MIN_COMPATIBLE_VERSION && isJakartaNamespace) {
            return null; // No marker needed
        }

        // Case 3: Version 4.0+ but wrong namespace
        if (versionNum >= MIN_COMPATIBLE_VERSION && !isJakartaNamespace) {
            return buildMarker(
                "faces-config.xml version " + version + " requires jakarta.ee namespace",
                "Current namespace: " + namespace,
                true
            );
        }

        // Case 4: Version 3.x with jakarta namespace - needs upgrade to 4.0
        if (versionNum >= 3.0 && versionNum < MIN_COMPATIBLE_VERSION && isJakartaNamespace) {
            return buildMarker(
                "faces-config.xml version " + version + " is incompatible with JoinFaces 5.x",
                "Jakarta Faces 3.0 is Jakarta EE 9/9.1 - JoinFaces 5.x requires Jakarta EE 10 (Faces 4.0)",
                false
            );
        }

        // Case 5: Version 2.x or 3.x with legacy namespace - needs upgrade and namespace change
        if (isLegacyNamespace) {
            return buildMarker(
                "faces-config.xml version " + version + " with legacy namespace is incompatible",
                "Namespace: " + namespace,
                true
            );
        }

        // Case 6: Version < 4.0 with unknown namespace
        return buildMarker(
            "faces-config.xml version " + version + " is incompatible with JoinFaces 5.x",
            "Upgrade to version 4.0 required",
            namespace != null && !isJakartaNamespace
        );
    }

    /**
     * Checks if the namespace is a legacy (pre-Jakarta) namespace.
     */
    private boolean isLegacyNamespace(String namespace) {
        if (namespace == null) {
            return false;
        }
        // Check for legacy patterns (both http and https variants)
        return namespace.contains(LEGACY_SUN_NAMESPACE) ||
               namespace.contains(LEGACY_JCP_NAMESPACE);
    }

    /**
     * Checks if the namespace is the Jakarta EE namespace.
     */
    private boolean isJakartaNamespace(String namespace) {
        if (namespace == null) {
            return false;
        }
        return namespace.contains(JAKARTA_EE_NAMESPACE);
    }

    /**
     * Builds a marker message with upgrade instructions.
     *
     * @param problem           the detected problem
     * @param details           additional details
     * @param needsNamespaceChange whether namespace needs to change from javax to jakarta
     * @return the formatted marker message
     */
    private String buildMarker(String problem, String details, boolean needsNamespaceChange) {
        StringBuilder sb = new StringBuilder();
        sb.append(problem);
        if (details != null && !details.isEmpty()) {
            sb.append(". ").append(details);
        }
        sb.append(".\n\nUpgrade faces-config.xml to version 4.0:\n");
        sb.append("1. Set version=\"4.0\"\n");
        if (needsNamespaceChange) {
            sb.append("2. Change namespace: xmlns=\"https://jakarta.ee/xml/ns/jakartaee\"\n");
            sb.append("3. Update xsi:schemaLocation to https://jakarta.ee/xml/ns/jakartaee/web-facesconfig_4_0.xsd\n");
        } else {
            sb.append("2. Verify xsi:schemaLocation uses web-facesconfig_4_0.xsd\n");
        }
        sb.append("\nSee: https://jakarta.ee/specifications/faces/4.0/");
        return sb.toString();
    }
}
