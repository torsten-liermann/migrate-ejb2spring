package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.text.PlainText;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrates JNDI String lookups to Spring @Value annotations.
 * <p>
 * This recipe:
 * 1. Finds fields with @Autowired String that have @NeedsReview
 * 2. Replaces @Autowired with @Value("${property.name}")
 * 3. Removes @NeedsReview annotation
 * 4. Adds the property key to application.properties with a TODO placeholder
 * <p>
 * The property name is derived from the JNDI path in the originalCode.
 * <p>
 * IMPORTANT: This recipe MUST run in a separate phase AFTER MigratePersistenceXmlToProperties
 * (which creates application.properties).
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateJndiStringToValue extends ScanningRecipe<MigrateJndiStringToValue.Accumulator> {

    private static final String SPRING_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final String SPRING_VALUE = "org.springframework.beans.factory.annotation.Value";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    // Pattern to extract JNDI name from originalCode like "@Resource(lookup='java:app/configuration/GraphTraversalUrl')"
    private static final Pattern JNDI_PATTERN = Pattern.compile("lookup=['\"]([^'\"]+)['\"]");

    @Override
    public String getDisplayName() {
        return "Migrate JNDI String lookup to @Value";
    }

    @Override
    public String getDescription() {
        return "Replaces @Autowired String fields with JNDI lookup @NeedsReview to @Value(\"${property.name}\") " +
               "and adds the property to application.properties.";
    }

    static class StringPropertyInfo {
        String jndiPath;        // Original JNDI path
        String propertyName;    // Spring property name (kebab-case)
        String fieldName;       // Java field name
        String className;       // Containing class
    }

    static class Accumulator {
        List<StringPropertyInfo> stringProperties = new ArrayList<>();
        boolean hasApplicationProperties = false;
        String applicationPropertiesPath = null;
        boolean propertiesWritten = false;
        String existingPropertiesContent = "";
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
                    SourceFile sf = (SourceFile) tree;
                    String path = sf.getSourcePath().toString();

                    // Detect application.properties - can be any SourceFile type
                    if (path.endsWith("application.properties") && path.contains("src/main/resources")) {
                        acc.hasApplicationProperties = true;
                        acc.applicationPropertiesPath = path;
                        acc.existingPropertiesContent = sf.printAll();
                    }
                }

                // For Java files, use Java visitor
                if (tree instanceof J.CompilationUnit) {
                    return new JavaScanner(acc).visit(tree, ctx);
                }

                return tree;
            }
        };
    }

    private static class JavaScanner extends JavaIsoVisitor<ExecutionContext> {
        private final Accumulator acc;

        JavaScanner(Accumulator acc) {
            this.acc = acc;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
            J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);

            // Check if this is a String field
            JavaType type = vd.getType();
            if (type == null || !TypeUtils.isOfClassType(type, "java.lang.String")) {
                return vd;
            }

            // Find @NeedsReview with JNDI info and @Autowired
            String jndiPath = null;
            boolean hasAutowired = false;

            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                if (isNeedsReview(ann)) {
                    jndiPath = extractJndiPath(ann);
                }
                if (isAutowired(ann)) {
                    hasAutowired = true;
                }
            }

            // Only collect if we have both @NeedsReview with JNDI and @Autowired
            if (jndiPath != null && hasAutowired) {
                StringPropertyInfo info = new StringPropertyInfo();
                info.jndiPath = jndiPath;
                info.propertyName = jndiPathToPropertyName(jndiPath);

                // Extract field name
                if (!vd.getVariables().isEmpty()) {
                    info.fieldName = vd.getVariables().get(0).getSimpleName();
                }

                // Extract class name
                J.ClassDeclaration classDecl = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (classDecl != null) {
                    info.className = classDecl.getSimpleName();
                }

                acc.stringProperties.add(info);
            }

            return vd;
        }

        private boolean isNeedsReview(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) ||
                   "NeedsReview".equals(ann.getSimpleName());
        }

        private boolean isAutowired(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), SPRING_AUTOWIRED) ||
                   "Autowired".equals(ann.getSimpleName());
        }

        private String extractJndiPath(J.Annotation ann) {
            if (ann.getArguments() == null) return null;

            for (Expression arg : ann.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        if ("originalCode".equals(attrName)) {
                            Expression value = assignment.getAssignment();
                            if (value instanceof J.Literal) {
                                Object literalValue = ((J.Literal) value).getValue();
                                if (literalValue != null) {
                                    String code = literalValue.toString();
                                    Matcher matcher = JNDI_PATTERN.matcher(code);
                                    if (matcher.find()) {
                                        return matcher.group(1);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }

        /**
         * Converts JNDI path to Spring property name.
         * Example: "java:app/configuration/GraphTraversalUrl" -> "app.configuration.graph-traversal-url"
         */
        private String jndiPathToPropertyName(String jndiPath) {
            // Remove java: prefix
            String path = jndiPath;
            if (path.startsWith("java:")) {
                path = path.substring(5);
            }

            // Split by /
            String[] parts = path.split("/");

            // Convert each part to kebab-case and join with dots
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    result.append(".");
                }
                result.append(camelToKebab(parts[i]));
            }

            return result.toString();
        }

        private String camelToKebab(String input) {
            if (input == null || input.isEmpty()) {
                return input;
            }

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (result.length() > 0) {
                        result.append('-');
                    }
                    result.append(Character.toLowerCase(c));
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (acc.stringProperties.isEmpty()) {
            return TreeVisitor.noop();
        }

        // Only proceed if application.properties exists
        if (!acc.hasApplicationProperties) {
            return TreeVisitor.noop();
        }

        // Mark that we will write properties
        acc.propertiesWritten = true;

        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                // Handle Java files - replace @Autowired with @Value and remove @NeedsReview
                if (tree instanceof J.CompilationUnit) {
                    return new StringPropertyMigrator(acc).visit(tree, ctx);
                }

                // Handle application.properties - append property stubs
                if (tree instanceof SourceFile) {
                    SourceFile sf = (SourceFile) tree;
                    String path = sf.getSourcePath().toString();
                    if (path.equals(acc.applicationPropertiesPath)) {
                        return appendStringProperties(sf, acc.stringProperties);
                    }
                }

                return tree;
            }
        };
    }

    /**
     * Appends JNDI String properties to application.properties.
     * Codex P2.1h Fix: Preserves AST type (Properties.File) to avoid overwriting
     * changes from other recipes like MigrateDataSourceDefinition.
     */
    private SourceFile appendStringProperties(SourceFile propertiesFile, List<StringPropertyInfo> stringProperties) {
        String content = propertiesFile.printAll();
        StringBuilder newContent = new StringBuilder(content);

        // Add separator if file doesn't end with newlines
        if (!newContent.toString().endsWith("\n\n")) {
            if (!newContent.toString().endsWith("\n")) {
                newContent.append("\n");
            }
            newContent.append("\n");
        }

        newContent.append("# ===========================================\n");
        newContent.append("# JNDI String Properties (migrated from @Resource lookup)\n");
        newContent.append("# TODO: Set appropriate values for your environment\n");
        newContent.append("# ===========================================\n");

        for (StringPropertyInfo prop : stringProperties) {
            newContent.append("# Original JNDI: ").append(prop.jndiPath).append("\n");
            if (prop.className != null && prop.fieldName != null) {
                newContent.append("# Used in: ").append(prop.className).append(".").append(prop.fieldName).append("\n");
            }
            newContent.append(prop.propertyName).append("=TODO_SET_VALUE\n");
        }

        // Codex P2.1h Fix: Use PropertiesParser to preserve AST type.
        // This ensures Properties.File created by MigrateDataSourceDefinition is not
        // replaced by PlainText, which would lose its structured content.
        return PropertiesParser.builder().build()
            .parse(newContent.toString())
            .findFirst()
            .map(parsed -> (SourceFile) ((Properties.File) parsed)
                .withId(propertiesFile.getId())
                .withSourcePath(propertiesFile.getSourcePath()))
            .orElse(propertiesFile);
    }

    private static class StringPropertyMigrator extends JavaIsoVisitor<ExecutionContext> {
        private final Accumulator acc;

        StringPropertyMigrator(Accumulator acc) {
            this.acc = acc;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
            J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);

            // Only process if properties were written
            if (!acc.propertiesWritten || !acc.hasApplicationProperties) {
                return vd;
            }

            // Check if this is a String field
            JavaType type = vd.getType();
            if (type == null || !TypeUtils.isOfClassType(type, "java.lang.String")) {
                return vd;
            }

            // Find @NeedsReview with JNDI info and @Autowired
            String jndiPath = null;
            boolean hasAutowired = false;
            J.Annotation needsReviewAnn = null;
            J.Annotation autowiredAnn = null;

            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                if (isNeedsReview(ann)) {
                    jndiPath = extractJndiPath(ann);
                    if (jndiPath != null) {
                        needsReviewAnn = ann;
                    }
                }
                if (isAutowired(ann)) {
                    hasAutowired = true;
                    autowiredAnn = ann;
                }
            }

            // Only proceed if we have both @NeedsReview with JNDI and @Autowired
            if (jndiPath == null || !hasAutowired) {
                return vd;
            }

            // Convert JNDI path to property name
            String propertyName = jndiPathToPropertyName(jndiPath);

            // Build new annotation list
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean addedValue = false;

            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                if (ann == needsReviewAnn) {
                    // Skip @NeedsReview - we're removing it
                    maybeRemoveImport(NEEDS_REVIEW_FQN);
                    continue;
                }
                if (ann == autowiredAnn) {
                    // Replace @Autowired with @Value
                    maybeRemoveImport(SPRING_AUTOWIRED);
                    maybeAddImport(SPRING_VALUE);

                    J.Annotation valueAnn = createValueAnnotation(propertyName, ann.getPrefix());
                    newAnnotations.add(valueAnn);
                    addedValue = true;
                } else {
                    newAnnotations.add(ann);
                }
            }

            if (addedValue) {
                vd = vd.withLeadingAnnotations(newAnnotations);
            }

            return vd;
        }

        private boolean isNeedsReview(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) ||
                   "NeedsReview".equals(ann.getSimpleName());
        }

        private boolean isAutowired(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), SPRING_AUTOWIRED) ||
                   "Autowired".equals(ann.getSimpleName());
        }

        private String extractJndiPath(J.Annotation ann) {
            if (ann.getArguments() == null) return null;

            for (Expression arg : ann.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        if ("originalCode".equals(attrName)) {
                            Expression value = assignment.getAssignment();
                            if (value instanceof J.Literal) {
                                Object literalValue = ((J.Literal) value).getValue();
                                if (literalValue != null) {
                                    String code = literalValue.toString();
                                    Matcher matcher = JNDI_PATTERN.matcher(code);
                                    if (matcher.find()) {
                                        return matcher.group(1);
                                    }
                                }
                            }
                        }
                    }
                }
            }
            return null;
        }

        private String jndiPathToPropertyName(String jndiPath) {
            String path = jndiPath;
            if (path.startsWith("java:")) {
                path = path.substring(5);
            }

            String[] parts = path.split("/");
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) {
                    result.append(".");
                }
                result.append(camelToKebab(parts[i]));
            }

            return result.toString();
        }

        private String camelToKebab(String input) {
            if (input == null || input.isEmpty()) {
                return input;
            }

            StringBuilder result = new StringBuilder();
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (Character.isUpperCase(c)) {
                    if (result.length() > 0) {
                        result.append('-');
                    }
                    result.append(Character.toLowerCase(c));
                } else {
                    result.append(c);
                }
            }
            return result.toString();
        }

        private J.Annotation createValueAnnotation(String propertyName, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(SPRING_VALUE);

            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Value",
                type,
                null
            );

            // Create the value literal: "${property.name}"
            String valueStr = "${" + propertyName + "}";
            J.Literal valueLiteral = new J.Literal(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                valueStr,
                "\"" + valueStr + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                Collections.singletonList(new JRightPadded<>(valueLiteral, Space.EMPTY, Markers.EMPTY)),
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                args
            );
        }
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        // Properties are appended directly to application.properties in the visitor phase
        return Collections.emptyList();
    }
}
