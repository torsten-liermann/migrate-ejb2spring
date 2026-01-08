package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.SourceFile;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.JavaTemplate;
import org.openrewrite.java.tree.J;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Path;
import java.util.*;

/**
 * GAP-DESC-001: Parses jboss-ejb3.xml and marks classes with @NeedsReview for JBoss-specific features.
 * <p>
 * This recipe handles:
 * <ul>
 *   <li>delivery-active=false on MDBs: Marks with hint "@JmsListener(autoStartup=false)"</li>
 *   <li>clustered-singleton=true: Marks with cluster coordination options (ShedLock, Spring Integration, etc.)</li>
 * </ul>
 * <p>
 * <b>Known Limitations:</b>
 * <ul>
 *   <li>EJB name matching relies on the class simple name or the explicit {@code name} attribute
 *       in EJB annotations (@MessageDriven, @Singleton, etc.). If the ejb-name is defined only
 *       in ejb-jar.xml (not in an annotation), this recipe falls back to matching by simple class
 *       name. A future enhancement could parse ejb-jar.xml to extract ejb-name to class mappings.</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MarkJbossEjb3XmlForMigration extends ScanningRecipe<MarkJbossEjb3XmlForMigration.Accumulator> {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Mark JBoss EJB3 XML configurations for migration";
    }

    @Override
    public String getDescription() {
        return "Parses jboss-ejb3.xml and adds @NeedsReview annotations to beans that use JBoss-specific " +
               "features like delivery-active=false (MDB) or clustered-singleton=true. " +
               "Provides migration hints for Spring equivalents.";
    }

    static class Accumulator {
        /** Maps EJB name to MDB info with delivery-active=false */
        Map<String, MdbDeliveryInfo> mdbDeliverySettings = new LinkedHashMap<>();

        /** Maps EJB name to singleton clustering info */
        Map<String, ClusteredSingletonInfo> clusteredSingletons = new LinkedHashMap<>();
    }

    static class MdbDeliveryInfo {
        final String ejbName;
        final String sourcePath;
        final String xmlSnippet;

        MdbDeliveryInfo(String ejbName, String sourcePath, String xmlSnippet) {
            this.ejbName = ejbName;
            this.sourcePath = sourcePath;
            this.xmlSnippet = xmlSnippet;
        }
    }

    static class ClusteredSingletonInfo {
        final String ejbName;
        final String sourcePath;
        final String xmlSnippet;

        ClusteredSingletonInfo(String ejbName, String sourcePath, String xmlSnippet) {
            this.ejbName = ejbName;
            this.sourcePath = sourcePath;
            this.xmlSnippet = xmlSnippet;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    Path sourcePath = sourceFile.getSourcePath();

                    // Parse jboss-ejb3.xml
                    if (sourceFile instanceof Xml.Document) {
                        String pathString = normalizePath(sourcePath);
                        if (pathString.endsWith("jboss-ejb3.xml")) {
                            Xml.Document doc = (Xml.Document) sourceFile;
                            parseJbossEjb3Xml(doc, pathString, acc);
                        }
                    }
                }
                return tree;
            }
        };
    }

    /**
     * Check if the class declaration has a specific EJB annotation.
     * Supports both jakarta.ejb and javax.ejb namespaces.
     *
     * @param classDecl the class declaration to check
     * @param annotationSimpleName the simple name of the annotation (e.g., "MessageDriven", "Singleton")
     * @return true if the class has the specified annotation
     */
    private static boolean hasEjbAnnotation(J.ClassDeclaration classDecl, String annotationSimpleName) {
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            String annName = ann.getSimpleName();
            if (annotationSimpleName.equals(annName)) {
                // Check if it's actually an EJB annotation (not some other annotation with same simple name)
                // The annotation type could be jakarta.ejb.X or javax.ejb.X
                if (ann.getAnnotationType() != null) {
                    String typeStr = ann.getAnnotationType().toString();
                    if (typeStr.contains("jakarta.ejb.") || typeStr.contains("javax.ejb.") ||
                        typeStr.equals(annotationSimpleName)) {
                        return true;
                    }
                } else {
                    // No type info available, accept by simple name
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Extract explicit EJB name from annotations like @MessageDriven(name="..."), @Singleton(name="..."), etc.
     */
    private static @Nullable String extractExplicitEjbName(J.ClassDeclaration classDecl) {
        for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
            String annName = ann.getSimpleName();
            // Check EJB annotations that can specify a name
            if ("MessageDriven".equals(annName) || "Singleton".equals(annName) ||
                "Stateless".equals(annName) || "Stateful".equals(annName)) {
                if (ann.getArguments() != null) {
                    for (org.openrewrite.java.tree.Expression arg : ann.getArguments()) {
                        if (arg instanceof J.Assignment) {
                            J.Assignment assignment = (J.Assignment) arg;
                            if (assignment.getVariable() instanceof J.Identifier) {
                                J.Identifier id = (J.Identifier) assignment.getVariable();
                                if ("name".equals(id.getSimpleName())) {
                                    org.openrewrite.java.tree.Expression value = assignment.getAssignment();
                                    if (value instanceof J.Literal) {
                                        Object literalValue = ((J.Literal) value).getValue();
                                        if (literalValue instanceof String) {
                                            return (String) literalValue;
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private void parseJbossEjb3Xml(Xml.Document doc, String sourcePath, Accumulator acc) {
        Xml.Tag root = doc.getRoot();
        if (root == null) {
            return;
        }

        // Handle various root element names (ejb-jar, jboss with namespace prefixes)
        String rootName = root.getName();
        String rootLocalName = getLocalName(rootName);
        if (!"ejb-jar".equals(rootLocalName) && !"jboss".equals(rootLocalName)) {
            return;
        }

        // Parse all children of root looking for assembly-descriptor and enterprise-beans
        for (Content content : root.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag tag = (Xml.Tag) content;
            String tagLocalName = getLocalName(tag.getName());

            // Handle assembly-descriptor section
            if ("assembly-descriptor".equals(tagLocalName)) {
                parseAssemblyDescriptor(tag, sourcePath, acc);
            }
            // Handle enterprise-beans section for delivery-group
            else if ("enterprise-beans".equals(tagLocalName)) {
                parseEnterpriseBeansTag(tag, sourcePath, acc);
            }
        }
    }

    private void parseAssemblyDescriptor(Xml.Tag assemblyDescriptor, String sourcePath, Accumulator acc) {
        // Parse MDB delivery settings and clustered singletons
        for (Content content : assemblyDescriptor.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag tag = (Xml.Tag) content;
            String tagLocalName = getLocalName(tag.getName());

            // Handle <mdb> or namespace-prefixed version
            if ("mdb".equals(tagLocalName)) {
                parseMdbTag(tag, sourcePath, acc);
            }
            // Handle <singleton> or <s:singleton> for clustered-singleton
            else if ("singleton".equals(tagLocalName)) {
                parseSingletonTag(tag, sourcePath, acc);
            }
        }
    }

    private void parseMdbTag(Xml.Tag mdbTag, String sourcePath, Accumulator acc) {
        String ejbName = null;
        boolean deliveryActiveFalse = false;

        for (Content content : mdbTag.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag child = (Xml.Tag) content;
            String localName = getLocalName(child.getName());

            if ("ejb-name".equals(localName)) {
                ejbName = getTagValue(child);
            } else if ("delivery-active".equals(localName)) {
                String value = getTagValue(child);
                deliveryActiveFalse = "false".equalsIgnoreCase(value);
            }
        }

        if (ejbName != null && deliveryActiveFalse) {
            String xmlSnippet = "<mdb><ejb-name>" + ejbName + "</ejb-name><delivery-active>false</delivery-active></mdb>";
            acc.mdbDeliverySettings.put(ejbName, new MdbDeliveryInfo(ejbName, sourcePath, xmlSnippet));
        }
    }

    private void parseSingletonTag(Xml.Tag singletonTag, String sourcePath, Accumulator acc) {
        String ejbName = null;
        boolean clusteredSingletonTrue = false;

        for (Content content : singletonTag.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag child = (Xml.Tag) content;
            String localName = getLocalName(child.getName());

            if ("ejb-name".equals(localName)) {
                ejbName = getTagValue(child);
            } else if ("clustered-singleton".equals(localName)) {
                String value = getTagValue(child);
                clusteredSingletonTrue = "true".equalsIgnoreCase(value);
            }
        }

        if (ejbName != null && clusteredSingletonTrue) {
            String xmlSnippet = "<singleton><ejb-name>" + ejbName + "</ejb-name><clustered-singleton>true</clustered-singleton></singleton>";
            acc.clusteredSingletons.put(ejbName, new ClusteredSingletonInfo(ejbName, sourcePath, xmlSnippet));
        }
    }

    private void parseEnterpriseBeansTag(Xml.Tag enterpriseBeansTag, String sourcePath, Accumulator acc) {
        for (Content content : enterpriseBeansTag.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag child = (Xml.Tag) content;
            String localName = getLocalName(child.getName());

            // Handle message-driven beans
            if ("message-driven".equals(localName)) {
                parseMessageDrivenTag(child, sourcePath, acc);
            }
            // Handle session beans (for singleton)
            else if ("session".equals(localName)) {
                parseSessionBeanTag(child, sourcePath, acc);
            }
        }
    }

    private void parseMessageDrivenTag(Xml.Tag mdTag, String sourcePath, Accumulator acc) {
        String ejbName = null;
        boolean deliveryActiveFalse = false;

        for (Content content : mdTag.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag child = (Xml.Tag) content;
            String localName = getLocalName(child.getName());

            if ("ejb-name".equals(localName)) {
                ejbName = getTagValue(child);
            } else if ("delivery-active".equals(localName)) {
                String value = getTagValue(child);
                deliveryActiveFalse = "false".equalsIgnoreCase(value);
            }
        }

        if (ejbName != null && deliveryActiveFalse) {
            String xmlSnippet = "<message-driven><ejb-name>" + ejbName + "</ejb-name><delivery-active>false</delivery-active></message-driven>";
            acc.mdbDeliverySettings.put(ejbName, new MdbDeliveryInfo(ejbName, sourcePath, xmlSnippet));
        }
    }

    private void parseSessionBeanTag(Xml.Tag sessionTag, String sourcePath, Accumulator acc) {
        String ejbName = null;
        boolean clusteredSingletonTrue = false;

        for (Content content : sessionTag.getContent()) {
            if (!(content instanceof Xml.Tag)) {
                continue;
            }
            Xml.Tag child = (Xml.Tag) content;
            String localName = getLocalName(child.getName());

            if ("ejb-name".equals(localName)) {
                ejbName = getTagValue(child);
            } else if ("clustered-singleton".equals(localName)) {
                String value = getTagValue(child);
                clusteredSingletonTrue = "true".equalsIgnoreCase(value);
            }
        }

        if (ejbName != null && clusteredSingletonTrue) {
            String xmlSnippet = "<session><ejb-name>" + ejbName + "</ejb-name><clustered-singleton>true</clustered-singleton></session>";
            acc.clusteredSingletons.put(ejbName, new ClusteredSingletonInfo(ejbName, sourcePath, xmlSnippet));
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (acc.mdbDeliverySettings.isEmpty() && acc.clusteredSingletons.isEmpty()) {
            return TreeVisitor.noop();
        }

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
                String simpleName = cd.getSimpleName();

                // Get current source path for module scoping
                J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                String currentSourcePath = normalizePath(cu.getSourcePath());

                // Extract explicit EJB name from annotation (if any)
                String explicitName = extractExplicitEjbName(cd);

                // Check if this class needs @NeedsReview for delivery-active=false
                // Guard: Only apply to classes that actually have @MessageDriven annotation
                MdbDeliveryInfo mdbInfo = findMatchingMdbInfo(acc, simpleName, explicitName, currentSourcePath);
                if (mdbInfo != null && hasEjbAnnotation(cd, "MessageDriven")) {
                    cd = addNeedsReviewForDeliveryActive(cd, mdbInfo, ctx);
                }

                // Check if this class needs @NeedsReview for clustered-singleton
                // Guard: Only apply to classes that actually have @Singleton annotation
                ClusteredSingletonInfo clusterInfo = findMatchingClusterInfo(acc, simpleName, explicitName, currentSourcePath);
                if (clusterInfo != null && hasEjbAnnotation(cd, "Singleton")) {
                    cd = addNeedsReviewForClusteredSingleton(cd, clusterInfo, ctx);
                }

                return cd;
            }

            /**
             * Find matching MDB info by EJB name, considering explicit annotation name and module scoping.
             */
            private @Nullable MdbDeliveryInfo findMatchingMdbInfo(Accumulator acc, String simpleName,
                                                                   @Nullable String explicitName, String currentSourcePath) {
                // Try explicit name first (from @MessageDriven(name="..."))
                if (explicitName != null) {
                    MdbDeliveryInfo info = acc.mdbDeliverySettings.get(explicitName);
                    if (info != null && isSameModule(info.sourcePath, currentSourcePath)) {
                        return info;
                    }
                }
                // Fall back to simple class name
                MdbDeliveryInfo info = acc.mdbDeliverySettings.get(simpleName);
                if (info != null && isSameModule(info.sourcePath, currentSourcePath)) {
                    return info;
                }
                return null;
            }

            /**
             * Find matching clustered singleton info by EJB name, considering explicit annotation name and module scoping.
             */
            private @Nullable ClusteredSingletonInfo findMatchingClusterInfo(Accumulator acc, String simpleName,
                                                                              @Nullable String explicitName, String currentSourcePath) {
                // Try explicit name first (from @Singleton(name="..."))
                if (explicitName != null) {
                    ClusteredSingletonInfo info = acc.clusteredSingletons.get(explicitName);
                    if (info != null && isSameModule(info.sourcePath, currentSourcePath)) {
                        return info;
                    }
                }
                // Fall back to simple class name
                ClusteredSingletonInfo info = acc.clusteredSingletons.get(simpleName);
                if (info != null && isSameModule(info.sourcePath, currentSourcePath)) {
                    return info;
                }
                return null;
            }

            /**
             * Check if the XML source path and Java source path are in the same module.
             * Extracts module root by finding common path prefix up to src/main or src/test.
             */
            private boolean isSameModule(String xmlSourcePath, String javaSourcePath) {
                String xmlModuleRoot = extractModuleRoot(xmlSourcePath);
                String javaModuleRoot = extractModuleRoot(javaSourcePath);
                return xmlModuleRoot.equals(javaModuleRoot);
            }

            private J.ClassDeclaration addNeedsReviewForDeliveryActive(J.ClassDeclaration cd, MdbDeliveryInfo info, ExecutionContext ctx) {
                // Check if already annotated
                if (hasNeedsReviewWithReason(cd, "delivery-active=false")) {
                    return cd;
                }

                maybeAddImport(NEEDS_REVIEW_FQN);
                maybeAddImport(NEEDS_REVIEW_FQN + ".Category");

                String annotationCode = String.format(
                    "@NeedsReview(\n" +
                    "    reason = \"JBoss delivery-active=false detected in jboss-ejb3.xml\",\n" +
                    "    category = NeedsReview.Category.MESSAGING,\n" +
                    "    originalCode = \"%s\",\n" +
                    "    suggestedAction = \"Use @JmsListener(autoStartup = false) to disable message delivery at startup\"\n" +
                    ")",
                    escapeJava(info.xmlSnippet)
                );

                return JavaTemplate.builder(annotationCode)
                    .imports(NEEDS_REVIEW_FQN, NEEDS_REVIEW_FQN + ".Category")
                    .javaParser(JavaParser.fromJavaVersion()
                        .dependsOn(getNeedsReviewSource()))
                    .build()
                    .apply(getCursor(), cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
            }

            private J.ClassDeclaration addNeedsReviewForClusteredSingleton(J.ClassDeclaration cd, ClusteredSingletonInfo info, ExecutionContext ctx) {
                // Check if already annotated
                if (hasNeedsReviewWithReason(cd, "clustered-singleton=true")) {
                    return cd;
                }

                maybeAddImport(NEEDS_REVIEW_FQN);
                maybeAddImport(NEEDS_REVIEW_FQN + ".Category");

                String annotationCode = String.format(
                    "@NeedsReview(\n" +
                    "    reason = \"JBoss clustered-singleton=true detected in jboss-ejb3.xml\",\n" +
                    "    category = NeedsReview.Category.CONCURRENCY,\n" +
                    "    originalCode = \"%s\",\n" +
                    "    suggestedAction = \"Options: ShedLock (JDBC, recommended), Spring Integration JdbcLockRegistry, or Spring Cloud Leader Election\"\n" +
                    ")",
                    escapeJava(info.xmlSnippet)
                );

                return JavaTemplate.builder(annotationCode)
                    .imports(NEEDS_REVIEW_FQN, NEEDS_REVIEW_FQN + ".Category")
                    .javaParser(JavaParser.fromJavaVersion()
                        .dependsOn(getNeedsReviewSource()))
                    .build()
                    .apply(getCursor(), cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName)));
            }

            private boolean hasNeedsReviewWithReason(J.ClassDeclaration cd, String reasonSubstring) {
                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    if (ann.getSimpleName().equals("NeedsReview")) {
                        if (ann.getArguments() != null) {
                            for (org.openrewrite.java.tree.Expression arg : ann.getArguments()) {
                                String argText = arg.toString();
                                if (argText.contains(reasonSubstring)) {
                                    return true;
                                }
                            }
                        }
                    }
                }
                return false;
            }
        };
    }

    // Utility methods

    private static String getLocalName(String name) {
        int colon = name.indexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }

    private static @Nullable String getTagValue(Xml.Tag tag) {
        return tag.getValue().map(String::trim).orElse(null);
    }

    private static String normalizePath(Path sourcePath) {
        if (sourcePath == null) {
            return "";
        }
        return sourcePath.toString().replace('\\', '/');
    }

    /**
     * Extract the module root from a source path.
     * For paths like "module-a/src/main/java/..." returns "module-a".
     * For paths like "src/main/java/..." returns "" (root module).
     */
    private static String extractModuleRoot(String sourcePath) {
        if (sourcePath == null || sourcePath.isEmpty()) {
            return "";
        }
        // Look for src/main or src/test as module boundary markers
        int srcMainIdx = sourcePath.indexOf("src/main");
        if (srcMainIdx == -1) {
            srcMainIdx = sourcePath.indexOf("src/test");
        }
        if (srcMainIdx <= 0) {
            // No src/main or src/test found, or it's at the beginning
            return "";
        }
        // Extract everything before src/main (e.g., "module-a/")
        String prefix = sourcePath.substring(0, srcMainIdx);
        // Remove trailing slash if present
        if (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    private static String escapeJava(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }

    private static String getNeedsReviewSource() {
        return """
            package com.github.rewrite.ejb.annotations;

            import java.lang.annotation.*;

            @Documented
            @Repeatable(NeedsReview.Container.class)
            @Retention(RetentionPolicy.SOURCE)
            @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
            public @interface NeedsReview {
                String reason();
                Category category();
                String originalCode() default "";
                String suggestedAction() default "";
                String stableKey() default "";

                enum Category {
                    REMOTE_ACCESS, CONCURRENCY, CONFIGURATION, SCHEDULING, MESSAGING,
                    CDI_FEATURE, TRANSACTION, ASYNC, SPRING_CONFIG, MANUAL_MIGRATION,
                    SEMANTIC_CHANGE, OTHER
                }

                @Documented
                @Retention(RetentionPolicy.SOURCE)
                @Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
                @interface Container {
                    NeedsReview[] value();
                }
            }
            """;
    }
}
