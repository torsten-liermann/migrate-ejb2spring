/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Created for JSF-JoinFaces migration - Rewrite JSF resource references in XHTML
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
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.marker.SearchResult;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Xml;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites JSF resource references in XHTML files after moving to META-INF/resources.
 * <p>
 * This recipe handles path updates in XHTML/Facelets files according to the following rules:
 * <p>
 * <b>Automatically rewritten (safe subset):</b>
 * <ul>
 *   <li>{@code <ui:include src="/...">} - Absolute paths become relative</li>
 *   <li>{@code <ui:composition template="/...">} - Absolute paths become relative</li>
 *   <li>{@code <ui:decorate template="/...">} - Absolute paths become relative</li>
 * </ul>
 * <p>
 * <b>Marked for manual review (not deterministic):</b>
 * <ul>
 *   <li>EL expressions in paths: {@code #{...}}</li>
 *   <li>HTML elements: {@code <link>}, {@code <script>}, {@code <img>}</li>
 *   <li>{@code h:link/h:button} with absolute outcome</li>
 *   <li>{@code h:outputStylesheet/h:outputScript} with value attribute</li>
 *   <li>Legacy {@code javax.faces.resource} URLs</li>
 * </ul>
 * <p>
 * <b>Rewrite rules for absolute paths:</b>
 * <ol>
 *   <li>Path starts with "/" → Remove leading slash</li>
 *   <li>Path starts with "/WEB-INF/" → Remove "/WEB-INF/"</li>
 *   <li>Path contains "#{...}" (EL expression) → Add marker (not deterministic)</li>
 * </ol>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RewriteJsfResourceReferences extends Recipe {

    // Pattern to detect EL expressions
    private static final Pattern EL_PATTERN = Pattern.compile("#\\{[^}]+\\}");

    // Pattern for javax.faces.resource URLs
    private static final Pattern JAVAX_FACES_RESOURCE_PATTERN =
        Pattern.compile(".*/javax\\.faces\\.resource/.*");

    // Tags with src attribute that can be safely rewritten
    private static final Set<String> SAFE_SRC_TAGS = new HashSet<>(Arrays.asList(
        "ui:include"
    ));

    // Tags with template attribute that can be safely rewritten
    private static final Set<String> SAFE_TEMPLATE_TAGS = new HashSet<>(Arrays.asList(
        "ui:composition",
        "ui:decorate"
    ));

    // HTML elements that always need markers (not JSF tags)
    private static final Set<String> HTML_RESOURCE_TAGS = new HashSet<>(Arrays.asList(
        "link",
        "script",
        "img"
    ));

    // JSF tags with outcome attribute that need markers for absolute paths
    private static final Set<String> OUTCOME_TAGS = new HashSet<>(Arrays.asList(
        "h:link",
        "h:button"
    ));

    // JSF resource tags with value attribute that need markers
    private static final Set<String> JSF_RESOURCE_VALUE_TAGS = new HashSet<>(Arrays.asList(
        "h:outputStylesheet",
        "h:outputScript",
        "h:graphicImage"
    ));

    @Override
    public String getDisplayName() {
        return "Rewrite JSF resource references for JoinFaces";
    }

    @Override
    public String getDescription() {
        return "Updates path references in XHTML files after moving to META-INF/resources. " +
               "Rewrites absolute paths to relative for ui:include/ui:composition, and marks " +
               "EL expressions and HTML elements for manual review.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new XmlVisitor<ExecutionContext>() {

            @Override
            public Xml visitDocument(Xml.Document document, ExecutionContext ctx) {
                String path = document.getSourcePath().toString();

                // Only process XHTML files
                if (!path.endsWith(".xhtml")) {
                    return document;
                }

                return super.visitDocument(document, ctx);
            }

            @Override
            public Xml visitTag(Xml.Tag tag, ExecutionContext ctx) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, ctx);

                String tagName = t.getName();
                String localName = getLocalName(tagName);

                // Handle safe src tags (ui:include)
                if (SAFE_SRC_TAGS.contains(tagName) || SAFE_SRC_TAGS.contains(localName)) {
                    t = handleSrcAttribute(t);
                }

                // Handle safe template tags (ui:composition, ui:decorate)
                if (SAFE_TEMPLATE_TAGS.contains(tagName) || SAFE_TEMPLATE_TAGS.contains(localName)) {
                    t = handleTemplateAttribute(t);
                }

                // Handle HTML resource tags (always marker)
                // Only treat as HTML tags when there is NO prefix (e.g., <link> but not <h:link>)
                boolean isPrefixed = tagName.contains(":");
                if (!isPrefixed && HTML_RESOURCE_TAGS.contains(localName)) {
                    t = handleHtmlResourceTag(t);
                }

                // Handle h:link/h:button outcome attribute
                if (OUTCOME_TAGS.contains(tagName) || OUTCOME_TAGS.contains(localName)) {
                    t = handleOutcomeAttribute(t);
                }

                // Handle h:outputStylesheet/h:outputScript/h:graphicImage value attribute
                if (JSF_RESOURCE_VALUE_TAGS.contains(tagName) || JSF_RESOURCE_VALUE_TAGS.contains(localName)) {
                    t = handleJsfResourceValueAttribute(t);
                }

                return t;
            }

            /**
             * Gets the local name from a potentially prefixed tag name.
             */
            private String getLocalName(String tagName) {
                int colonIndex = tagName.indexOf(':');
                return colonIndex >= 0 ? tagName.substring(colonIndex + 1) : tagName;
            }

            /**
             * Handles the src attribute for ui:include.
             */
            private Xml.Tag handleSrcAttribute(Xml.Tag tag) {
                return processPathAttribute(tag, "src", true);
            }

            /**
             * Handles the template attribute for ui:composition/ui:decorate.
             */
            private Xml.Tag handleTemplateAttribute(Xml.Tag tag) {
                return processPathAttribute(tag, "template", true);
            }

            /**
             * Handles HTML resource tags (link, script, img) - always adds marker.
             */
            private Xml.Tag handleHtmlResourceTag(Xml.Tag tag) {
                String attrName = getResourceAttributeName(tag);
                if (attrName == null) {
                    return tag;
                }

                String attrValue = getAttributeValue(tag, attrName);
                if (attrValue == null || attrValue.isEmpty()) {
                    return tag;
                }

                // Always add marker for HTML elements with resource paths
                String marker = buildHtmlElementMarker(tag.getName(), attrName, attrValue);
                return SearchResult.found(tag, marker);
            }

            /**
             * Gets the resource attribute name for HTML elements.
             */
            private String getResourceAttributeName(Xml.Tag tag) {
                String localName = getLocalName(tag.getName());
                switch (localName) {
                    case "link":
                        return "href";
                    case "script":
                        return "src";
                    case "img":
                        return "src";
                    default:
                        return null;
                }
            }

            /**
             * Handles h:link/h:button outcome attribute.
             */
            private Xml.Tag handleOutcomeAttribute(Xml.Tag tag) {
                String outcome = getAttributeValue(tag, "outcome");
                if (outcome == null || outcome.isEmpty()) {
                    return tag;
                }

                // Add marker for absolute paths or EL expressions
                if (outcome.startsWith("/") || containsEl(outcome)) {
                    String marker = buildOutcomeMarker(tag.getName(), outcome);
                    return SearchResult.found(tag, marker);
                }

                return tag;
            }

            /**
             * Handles h:outputStylesheet/h:outputScript/h:graphicImage with value attribute.
             */
            private Xml.Tag handleJsfResourceValueAttribute(Xml.Tag tag) {
                String value = getAttributeValue(tag, "value");
                if (value == null || value.isEmpty()) {
                    return tag;
                }

                // Check for javax.faces.resource URLs
                if (JAVAX_FACES_RESOURCE_PATTERN.matcher(value).matches()) {
                    String marker = buildJavaxFacesResourceMarker(value);
                    return SearchResult.found(tag, marker);
                }

                // Add marker for value attribute with paths
                if (value.startsWith("/") || value.contains("/")) {
                    String marker = buildJsfResourceValueMarker(tag.getName(), value);
                    return SearchResult.found(tag, marker);
                }

                return tag;
            }

            /**
             * Processes a path attribute - rewrites if safe, marks if not deterministic.
             */
            private Xml.Tag processPathAttribute(Xml.Tag tag, String attrName, boolean canRewrite) {
                String attrValue = getAttributeValue(tag, attrName);
                if (attrValue == null || attrValue.isEmpty()) {
                    return tag;
                }

                // Check for EL expressions - not deterministic, add marker
                if (containsEl(attrValue)) {
                    String marker = buildElExpressionMarker(tag.getName(), attrName, attrValue);
                    return SearchResult.found(tag, marker);
                }

                // Check for javax.faces.resource URLs
                if (JAVAX_FACES_RESOURCE_PATTERN.matcher(attrValue).matches()) {
                    String marker = buildJavaxFacesResourceMarker(attrValue);
                    return SearchResult.found(tag, marker);
                }

                // Rewrite absolute paths if safe
                if (canRewrite && attrValue.startsWith("/")) {
                    // Check if this is an unknown WEB-INF path (should add marker, not rewrite)
                    if (isUnknownWebInfPath(attrValue)) {
                        String marker = buildUnknownWebInfPathMarker(tag.getName(), attrName, attrValue);
                        return SearchResult.found(tag, marker);
                    }
                    String newValue = rewriteAbsolutePath(attrValue);
                    if (newValue != null) {
                        return updateAttribute(tag, attrName, newValue);
                    }
                }

                return tag;
            }

            /**
             * Checks if the value contains an EL expression.
             */
            private boolean containsEl(String value) {
                return EL_PATTERN.matcher(value).find();
            }

            /**
             * Rewrites an absolute path to a relative path.
             * Rules:
             * 1. /WEB-INF/templates/... -> templates/...
             * 2. /WEB-INF/includes/... -> includes/...
             * 3. /templates/... -> templates/...
             * 4. /pages/... -> pages/...
             *
             * Note: Other /WEB-INF/* paths are NOT rewritten because they are moved
             * to META-INF/resources/WEB-INF/* and keep their relative structure.
             */
            private String rewriteAbsolutePath(String path) {
                // Only strip /WEB-INF/ for known relocated subtrees
                if (path.startsWith("/WEB-INF/templates/") || path.startsWith("/WEB-INF/includes/")) {
                    // Remove /WEB-INF/ for templates and includes (these are moved out of WEB-INF)
                    return path.substring("/WEB-INF/".length());
                } else if (path.startsWith("/WEB-INF/")) {
                    // Other WEB-INF paths: do NOT rewrite (they stay under WEB-INF in the new location)
                    // Return null to indicate no rewrite should happen
                    return null;
                } else if (path.startsWith("/")) {
                    // Remove leading slash for non-WEB-INF paths
                    return path.substring(1);
                }
                return path;
            }

            /**
             * Checks if a path is a known WEB-INF subpath that should not be rewritten.
             */
            private boolean isUnknownWebInfPath(String path) {
                return path.startsWith("/WEB-INF/")
                    && !path.startsWith("/WEB-INF/templates/")
                    && !path.startsWith("/WEB-INF/includes/");
            }

            /**
             * Gets an attribute value from a tag.
             */
            @Nullable
            private String getAttributeValue(Xml.Tag tag, String attrName) {
                for (Xml.Attribute attr : tag.getAttributes()) {
                    if (attrName.equals(attr.getKeyAsString())) {
                        return attr.getValueAsString();
                    }
                }
                return null;
            }

            /**
             * Updates an attribute value in a tag.
             */
            private Xml.Tag updateAttribute(Xml.Tag tag, String attrName, String newValue) {
                List<Xml.Attribute> newAttrs = new ArrayList<>();
                for (Xml.Attribute attr : tag.getAttributes()) {
                    if (attrName.equals(attr.getKeyAsString())) {
                        // Create new attribute with updated value
                        Xml.Attribute.Value newAttrValue = attr.getValue()
                            .withValue(newValue);
                        newAttrs.add(attr.withValue(newAttrValue));
                    } else {
                        newAttrs.add(attr);
                    }
                }
                return tag.withAttributes(newAttrs);
            }

            /**
             * Builds a marker message for EL expressions in paths.
             */
            private String buildElExpressionMarker(String tagName, String attrName, String attrValue) {
                return String.format(
                    "%s %s contains EL expression - not deterministic. " +
                    "Verify path resolution after file relocation to META-INF/resources. " +
                    "Original: %s=\"%s\"",
                    tagName, attrName, attrName, attrValue
                );
            }

            /**
             * Builds a marker message for HTML elements with resource paths.
             */
            private String buildHtmlElementMarker(String tagName, String attrName, String attrValue) {
                return String.format(
                    "HTML %s element with resource path. Options: " +
                    "1) Convert to JSF tag (h:outputStylesheet/h:outputScript/h:graphicImage) " +
                    "2) Use EL: %s=\"#{resource['library:name']}\" " +
                    "3) Manually adjust path. Original: %s=\"%s\"",
                    tagName, attrName, attrName, attrValue
                );
            }

            /**
             * Builds a marker message for h:link/h:button outcome.
             */
            private String buildOutcomeMarker(String tagName, String outcome) {
                String reason = outcome.startsWith("/")
                    ? "absolute outcome path"
                    : "EL expression in outcome";
                return String.format(
                    "%s with %s. Verify navigation after view relocation to META-INF/resources. " +
                    "Consider relative outcome or update faces-config.xml navigation rules. " +
                    "Original: outcome=\"%s\"",
                    tagName, reason, outcome
                );
            }

            /**
             * Builds a marker message for h:outputStylesheet/h:outputScript with value.
             */
            private String buildJsfResourceValueMarker(String tagName, String value) {
                return String.format(
                    "%s with value attribute. Recommended: convert to library/name form. " +
                    "Example: value=\"/resources/css/style.css\" -> library=\"css\" name=\"style.css\". " +
                    "Original: value=\"%s\"",
                    tagName, value
                );
            }

            /**
             * Builds a marker message for javax.faces.resource URLs.
             */
            private String buildJavaxFacesResourceMarker(String url) {
                return String.format(
                    "Legacy javax.faces.resource URL detected. Convert to JSF resource tag: " +
                    "<h:outputStylesheet library=\"...\" name=\"...\"/> or " +
                    "<h:outputScript library=\"...\" name=\"...\"/>. " +
                    "Original URL: %s",
                    url
                );
            }

            /**
             * Builds a marker message for unknown WEB-INF paths that cannot be safely rewritten.
             */
            private String buildUnknownWebInfPathMarker(String tagName, String attrName, String attrValue) {
                return String.format(
                    "%s %s references a WEB-INF path outside templates/includes. " +
                    "This file was moved to META-INF/resources/WEB-INF/. " +
                    "Verify path resolution and update manually if needed. " +
                    "Original: %s=\"%s\"",
                    tagName, attrName, attrName, attrValue
                );
            }
        };
    }
}
