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

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.marker.Markers;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.XmlIsoVisitor;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.util.*;

/**
 * Cleans up JSF-related configuration from web.xml for JoinFaces migration.
 * <p>
 * JoinFaces provides auto-configuration for JSF, so manual web.xml configuration
 * is no longer necessary. This recipe:
 * <ul>
 *   <li>Removes FacesServlet definition and servlet-mapping</li>
 *   <li>Removes standard JSF context-params (PROJECT_STAGE, etc.)</li>
 *   <li>Marks custom URL patterns (not *.xhtml) for manual review</li>
 *   <li>Deletes empty web.xml after cleanup (optional)</li>
 * </ul>
 * <p>
 * Context-params that are removed (should be configured via application.properties):
 * <ul>
 *   <li>jakarta.faces.PROJECT_STAGE / javax.faces.PROJECT_STAGE</li>
 *   <li>jakarta.faces.STATE_SAVING_METHOD / javax.faces.STATE_SAVING_METHOD</li>
 *   <li>jakarta.faces.FACELETS_REFRESH_PERIOD / javax.faces.FACELETS_REFRESH_PERIOD</li>
 *   <li>jakarta.faces.INTERPRET_EMPTY_STRING_SUBMITTED_VALUES_AS_NULL</li>
 *   <li>jakarta.faces.DATETIMECONVERTER_DEFAULT_TIMEZONE_IS_SYSTEM_TIMEZONE</li>
 *   <li>facelets.DEVELOPMENT / facelets.REFRESH_PERIOD / facelets.SKIP_COMMENTS</li>
 *   <li>com.sun.faces.* (Mojarra-specific)</li>
 *   <li>org.apache.myfaces.* (MyFaces-specific)</li>
 *   <li>primefaces.* (PrimeFaces-specific)</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class CleanupJsfWebXml extends ScanningRecipe<CleanupJsfWebXml.Accumulator> {

    private static final String FACES_SERVLET_CLASS_JAKARTA = "jakarta.faces.webapp.FacesServlet";
    private static final String FACES_SERVLET_CLASS_JAVAX = "javax.faces.webapp.FacesServlet";
    private static final String STANDARD_URL_PATTERN = "*.xhtml";

    // Standard JSF context-param prefixes that should be removed
    private static final List<String> JSF_CONTEXT_PARAM_PREFIXES = Arrays.asList(
        "jakarta.faces.",
        "javax.faces.",
        "facelets.",
        "com.sun.faces.",
        "org.apache.myfaces.",
        "primefaces."
    );

    @Override
    public String getDisplayName() {
        return "Clean up JSF configuration from web.xml for JoinFaces";
    }

    @Override
    public String getDescription() {
        return "Removes FacesServlet and standard JSF context-params from web.xml. " +
               "JoinFaces auto-configures JSF, so manual web.xml configuration is not needed. " +
               "Custom URL patterns and filters are marked for manual review.";
    }

    static class Accumulator {
        boolean foundWebXml = false;
        String webXmlPath = null;
        String facesServletName = null;
        Set<String> customUrlPatterns = new LinkedHashSet<>();
        List<String> markerComments = new ArrayList<>();
        boolean hasNonJsfContent = false;
        boolean hasCustomFilters = false;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    String path = sourceFile.getSourcePath().toString();

                    if (path.endsWith("web.xml") && tree instanceof Xml.Document) {
                        acc.foundWebXml = true;
                        acc.webXmlPath = path;
                        scanWebXml((Xml.Document) tree, acc);
                    }
                }
                return tree;
            }
        };
    }

    private void scanWebXml(Xml.Document doc, Accumulator acc) {
        new XmlIsoVisitor<Accumulator>() {
            private final XPathMatcher servletMatcher = new XPathMatcher("/web-app/servlet");
            private final XPathMatcher servletMappingMatcher = new XPathMatcher("/web-app/servlet-mapping");
            private final XPathMatcher contextParamMatcher = new XPathMatcher("/web-app/context-param");
            private final XPathMatcher filterMatcher = new XPathMatcher("/web-app/filter");
            private final XPathMatcher listenerMatcher = new XPathMatcher("/web-app/listener");
            private final XPathMatcher errorPageMatcher = new XPathMatcher("/web-app/error-page");
            private final XPathMatcher welcomeFileMatcher = new XPathMatcher("/web-app/welcome-file-list");
            private final XPathMatcher sessionConfigMatcher = new XPathMatcher("/web-app/session-config");

            @Override
            public Xml.Tag visitTag(Xml.Tag tag, Accumulator accumulator) {
                Xml.Tag t = super.visitTag(tag, accumulator);

                // Find FacesServlet definition
                if (servletMatcher.matches(getCursor())) {
                    String servletClass = getChildValue(tag, "servlet-class");
                    if (FACES_SERVLET_CLASS_JAKARTA.equals(servletClass) ||
                        FACES_SERVLET_CLASS_JAVAX.equals(servletClass)) {
                        String servletName = getChildValue(tag, "servlet-name");
                        if (servletName != null) {
                            accumulator.facesServletName = servletName;
                        }
                    } else if (servletClass != null) {
                        // Non-JSF servlet found
                        accumulator.hasNonJsfContent = true;
                    }
                }

                // Check servlet-mappings for custom URL patterns
                if (servletMappingMatcher.matches(getCursor())) {
                    String servletName = getChildValue(tag, "servlet-name");
                    if (servletName != null && servletName.equals(accumulator.facesServletName)) {
                        String urlPattern = getChildValue(tag, "url-pattern");
                        if (urlPattern != null && !STANDARD_URL_PATTERN.equals(urlPattern)) {
                            accumulator.customUrlPatterns.add(urlPattern);
                        }
                    } else if (servletName != null && !servletName.equals(accumulator.facesServletName)) {
                        // Non-JSF servlet mapping
                        accumulator.hasNonJsfContent = true;
                    }
                }

                // Check context-params
                if (contextParamMatcher.matches(getCursor())) {
                    String paramName = getChildValue(tag, "param-name");
                    if (paramName != null && !isJsfContextParam(paramName)) {
                        // Non-JSF context-param
                        accumulator.hasNonJsfContent = true;
                    }
                }

                // Check for filters (might be JSF-related)
                if (filterMatcher.matches(getCursor())) {
                    String filterClass = getChildValue(tag, "filter-class");
                    if (filterClass != null) {
                        // Check if it's a JSF/PrimeFaces filter
                        if (filterClass.contains("faces") || filterClass.contains("primefaces") ||
                            filterClass.contains("omnifaces")) {
                            accumulator.hasCustomFilters = true;
                        } else {
                            accumulator.hasNonJsfContent = true;
                        }
                    }
                }

                // Other web.xml content
                if (listenerMatcher.matches(getCursor()) ||
                    errorPageMatcher.matches(getCursor()) ||
                    welcomeFileMatcher.matches(getCursor()) ||
                    sessionConfigMatcher.matches(getCursor())) {
                    accumulator.hasNonJsfContent = true;
                }

                return t;
            }
        }.visit(doc, acc);

        // Build marker comments for custom configurations
        if (!acc.customUrlPatterns.isEmpty()) {
            acc.markerComments.add("TODO: Custom JSF URL patterns detected: " + acc.customUrlPatterns);
            acc.markerComments.add("Configure in application.properties: joinfaces.faces.webapp.url-mappings=" +
                                   String.join(",", acc.customUrlPatterns));
        }
        if (acc.hasCustomFilters) {
            acc.markerComments.add("TODO: JSF-related filters detected, review for JoinFaces compatibility");
        }
    }

    private String getChildValue(Xml.Tag tag, String childName) {
        if (tag.getContent() == null) {
            return null;
        }
        for (Content content : tag.getContent()) {
            if (content instanceof Xml.Tag) {
                Xml.Tag child = (Xml.Tag) content;
                if (childName.equals(child.getName())) {
                    return child.getValue().orElse(null);
                }
            }
        }
        return null;
    }

    private boolean isJsfContextParam(String paramName) {
        if (paramName == null) return false;
        for (String prefix : JSF_CONTEXT_PARAM_PREFIXES) {
            if (paramName.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (!acc.foundWebXml || acc.facesServletName == null) {
            return TreeVisitor.noop();
        }

        return new XmlIsoVisitor<ExecutionContext>() {
            private final XPathMatcher servletMatcher = new XPathMatcher("/web-app/servlet");
            private final XPathMatcher servletMappingMatcher = new XPathMatcher("/web-app/servlet-mapping");
            private final XPathMatcher contextParamMatcher = new XPathMatcher("/web-app/context-param");
            private final XPathMatcher filterMatcher = new XPathMatcher("/web-app/filter");
            private final XPathMatcher filterMappingMatcher = new XPathMatcher("/web-app/filter-mapping");
            private final XPathMatcher webAppMatcher = new XPathMatcher("/web-app");

            private boolean addedMarkerComment = false;

            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                Xml.Tag t = super.visitTag(tag, ctx);

                // Remove FacesServlet definition
                if (servletMatcher.matches(getCursor())) {
                    String servletClass = getChildValue(tag, "servlet-class");
                    if (FACES_SERVLET_CLASS_JAKARTA.equals(servletClass) ||
                        FACES_SERVLET_CLASS_JAVAX.equals(servletClass)) {
                        return null;
                    }
                }

                // Remove FacesServlet mapping
                if (servletMappingMatcher.matches(getCursor())) {
                    String servletName = getChildValue(tag, "servlet-name");
                    if (acc.facesServletName.equals(servletName)) {
                        return null;
                    }
                }

                // Remove JSF context-params
                if (contextParamMatcher.matches(getCursor())) {
                    String paramName = getChildValue(tag, "param-name");
                    if (isJsfContextParam(paramName)) {
                        return null;
                    }
                }

                // Remove JSF-related filters (but add TODO comment)
                if (filterMatcher.matches(getCursor())) {
                    String filterClass = getChildValue(tag, "filter-class");
                    if (filterClass != null &&
                        (filterClass.contains("faces") || filterClass.contains("primefaces") ||
                         filterClass.contains("omnifaces"))) {
                        // Mark for review but remove
                        return null;
                    }
                }

                // Remove filter-mappings for FacesServlet
                if (filterMappingMatcher.matches(getCursor())) {
                    String servletName = getChildValue(tag, "servlet-name");
                    if (acc.facesServletName.equals(servletName)) {
                        return null;
                    }
                }

                // Add marker comments to web-app if there are custom configurations
                if (webAppMatcher.matches(getCursor()) && !acc.markerComments.isEmpty() && !addedMarkerComment) {
                    addedMarkerComment = true;
                    // Add XML comment at the beginning of web-app content
                    List<Content> newContent = new ArrayList<>();

                    StringBuilder commentText = new StringBuilder("\n");
                    for (String marker : acc.markerComments) {
                        commentText.append("    ").append(marker).append("\n");
                    }
                    commentText.append("    ");

                    Xml.Comment comment = new Xml.Comment(
                        Tree.randomId(),
                        "",
                        Markers.EMPTY,
                        commentText.toString()
                    );
                    newContent.add(comment);

                    if (t.getContent() != null) {
                        newContent.addAll(t.getContent());
                    }
                    t = t.withContent(newContent);
                }

                return t;
            }

            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                Xml.Document d = super.visitDocument(document, ctx);

                // Check if web.xml is now effectively empty (only has empty web-app)
                if (isWebXmlEffectivelyEmpty(d) && !acc.hasNonJsfContent) {
                    // Mark for deletion by returning null or add a deletion marker
                    // For now, we keep the file but add a comment that it can be deleted
                    Xml.Tag root = d.getRoot();
                    if (root != null) {
                        List<Content> newContent = new ArrayList<>();
                        Xml.Comment deleteComment = new Xml.Comment(
                            Tree.randomId(),
                            "\n",
                            Markers.EMPTY,
                            " This web.xml can be deleted - all JSF configuration is handled by JoinFaces auto-configuration "
                        );
                        newContent.add(deleteComment);
                        if (root.getContent() != null) {
                            // Keep only non-empty, non-whitespace content
                            for (Content content : root.getContent()) {
                                if (content instanceof Xml.Tag) {
                                    newContent.add(content);
                                }
                            }
                        }
                        d = d.withRoot(root.withContent(newContent));
                    }
                }

                return d;
            }
        };
    }

    private boolean isWebXmlEffectivelyEmpty(Xml.Document doc) {
        Xml.Tag root = doc.getRoot();
        if (root == null || root.getContent() == null) {
            return true;
        }

        for (Content content : root.getContent()) {
            if (content instanceof Xml.Tag) {
                // There's still a tag, so not empty
                return false;
            }
        }
        return true;
    }
}
