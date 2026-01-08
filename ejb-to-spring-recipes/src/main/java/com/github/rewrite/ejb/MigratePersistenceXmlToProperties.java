package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.internal.lang.Nullable;
import org.openrewrite.properties.PropertiesParser;
import org.openrewrite.properties.tree.Properties;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.XmlVisitor;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Migrates JPA persistence.xml configuration to Spring Boot application.properties.
 * <p>
 * This recipe:
 * 1. Scans persistence.xml for JPA/Hibernate configuration
 * 2. Creates or updates application.properties with Spring Boot equivalents
 * 3. Adds TODO comments for datasource configuration that needs manual setup
 * <p>
 * Property mappings:
 * - jakarta.persistence.schema-generation.database.action → spring.jpa.hibernate.ddl-auto
 * - hibernate.dialect → spring.jpa.database-platform (with NOTE: usually not needed in Hibernate 6.x)
 * - hibernate.show_sql → spring.jpa.show-sql
 * - hibernate.format_sql → spring.jpa.properties.hibernate.format_sql
 * - jta-data-source → TODO comment for spring.datasource.* configuration
 * - EclipseLink properties → TODO comments (Spring Boot uses Hibernate by default)
 * <p>
 * Note: Hibernate 6.x (used in Spring Boot 3.x) auto-detects the database dialect,
 * so spring.jpa.database-platform is usually not required anymore. The recipe adds
 * a NOTE comment to inform developers about this.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigratePersistenceXmlToProperties extends ScanningRecipe<MigratePersistenceXmlToProperties.Accumulator> {

    private static final String DEFAULT_RESOURCE_ROOT = "src/main/resources";

    @Override
    public String getDisplayName() {
        return "Migrate persistence.xml to Spring Boot application.properties";
    }

    @Override
    public String getDescription() {
        return "Extracts JPA configuration from persistence.xml and generates Spring Boot application.properties entries.";
    }

    static class Accumulator {
        boolean foundPersistenceXml = false;
        boolean hasApplicationProperties = false;
        boolean applicationPropertiesAlreadyMigrated = false;
        String persistenceUnitName = null;
        String dataSourceJndi = null;
        Map<String, String> jpaProperties = new LinkedHashMap<>();
        List<String> springProperties = new ArrayList<>();
        List<String> todoComments = new ArrayList<>();
        Path persistenceModuleRoot = null;
        String persistenceResourceRoot = DEFAULT_RESOURCE_ROOT;
        Map<Path, ProjectConfiguration> configCache = new HashMap<>();
        Map<Path, Path> applicationPropertiesPaths = new HashMap<>();
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
                    Path moduleRoot = extractProjectRoot(sourceFile.getSourcePath());
                    ProjectConfiguration config = acc.configCache.computeIfAbsent(
                        moduleRoot, ProjectConfigurationLoader::load);
                    Path applicationPropertiesPath = acc.applicationPropertiesPaths.computeIfAbsent(
                        moduleRoot, root -> resolveApplicationPropertiesPath(config));

                    // Check for src/main/resources/application.properties only (not test resources)
                    if (sourceFile.getSourcePath().equals(applicationPropertiesPath)) {
                        acc.hasApplicationProperties = true;
                        // Check if migration marker already exists
                        String content = sourceFile.printAll();
                        if (content.contains("# ===== JPA Configuration (migrated from persistence.xml) =====") ||
                            content.contains("# Migrated from persistence.xml")) {
                            acc.applicationPropertiesAlreadyMigrated = true;
                        }
                    }

                    // Parse persistence.xml (only process once)
                    if (path.endsWith("persistence.xml") && tree instanceof Xml.Document && !acc.foundPersistenceXml) {
                        acc.foundPersistenceXml = true;
                        acc.persistenceModuleRoot = moduleRoot;
                        acc.persistenceResourceRoot = resolveResourceRoot(config);
                        extractPersistenceConfig((Xml.Document) tree, acc);
                    }
                }
                return tree;
            }
        };
    }

    private void extractPersistenceConfig(Xml.Document doc, Accumulator acc) {
        new XmlVisitor<Accumulator>() {
            private final XPathMatcher persistenceUnitMatcher = new XPathMatcher("/persistence/persistence-unit");
            private final XPathMatcher jtaDataSourceMatcher = new XPathMatcher("/persistence/persistence-unit/jta-data-source");
            private final XPathMatcher nonJtaDataSourceMatcher = new XPathMatcher("/persistence/persistence-unit/non-jta-data-source");
            private final XPathMatcher propertyMatcher = new XPathMatcher("/persistence/persistence-unit/properties/property");

            @Override
            public Xml visitTag(Xml.Tag tag, Accumulator accumulator) {
                Xml.Tag t = (Xml.Tag) super.visitTag(tag, accumulator);

                if (persistenceUnitMatcher.matches(getCursor())) {
                    // Extract persistence-unit name
                    for (Xml.Attribute attr : tag.getAttributes()) {
                        if ("name".equals(attr.getKeyAsString())) {
                            accumulator.persistenceUnitName = attr.getValueAsString();
                        }
                    }
                }

                if (jtaDataSourceMatcher.matches(getCursor()) || nonJtaDataSourceMatcher.matches(getCursor())) {
                    // Extract datasource JNDI name
                    tag.getValue().ifPresent(value -> {
                        accumulator.dataSourceJndi = value.trim();
                    });
                }

                if (propertyMatcher.matches(getCursor())) {
                    // Extract property name and value
                    String name = null;
                    String value = null;
                    for (Xml.Attribute attr : tag.getAttributes()) {
                        if ("name".equals(attr.getKeyAsString())) {
                            name = attr.getValueAsString();
                        } else if ("value".equals(attr.getKeyAsString())) {
                            value = attr.getValueAsString();
                        }
                    }
                    if (name != null && value != null) {
                        accumulator.jpaProperties.put(name, value);
                    }
                }

                return t;
            }
        }.visit(doc, acc);

        // Convert to Spring Boot properties
        convertToSpringProperties(acc);
    }

    private void convertToSpringProperties(Accumulator acc) {
        // Add header comment
        if (acc.persistenceUnitName != null) {
            acc.todoComments.add("# Migrated from persistence.xml (persistence-unit: " + acc.persistenceUnitName + ")");
        }

        // Handle datasource with BEGIN/END markers for reliable cleanup by MigrateDataSourceDefinition
        if (acc.dataSourceJndi != null) {
            acc.todoComments.add("# BEGIN DATASOURCE TODO (jndi=" + acc.dataSourceJndi + ")");
            acc.todoComments.add("# TODO: DATASOURCE CONFIGURATION REQUIRED");
            acc.todoComments.add("# Original JNDI: " + acc.dataSourceJndi);
            acc.todoComments.add("# Configure one of the following:");
            acc.todoComments.add("# spring.datasource.url=jdbc:postgresql://localhost:5432/yourdb");
            acc.todoComments.add("# spring.datasource.username=");
            acc.todoComments.add("# spring.datasource.password=");
            acc.todoComments.add("# spring.datasource.driver-class-name=org.postgresql.Driver");
            acc.todoComments.add("# END DATASOURCE TODO");
        }

        // Map JPA properties to Spring Boot equivalents
        for (Map.Entry<String, String> entry : acc.jpaProperties.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();

            // Schema generation
            if ("jakarta.persistence.schema-generation.database.action".equals(name) ||
                "javax.persistence.schema-generation.database.action".equals(name)) {
                String ddlAuto = mapSchemaGenerationAction(value);
                if (ddlAuto != null) {
                    acc.springProperties.add("spring.jpa.hibernate.ddl-auto=" + ddlAuto);
                } else if ("drop".equalsIgnoreCase(value)) {
                    // "drop" has no safe Spring equivalent - don't auto-map to avoid data loss
                    acc.todoComments.add("# TODO: schema-generation.database.action=drop has no Spring equivalent");
                    acc.todoComments.add("# Consider using spring.jpa.hibernate.ddl-auto=none and manual DDL scripts");
                }
            }
            // Hibernate dialect - Note: Hibernate 6.x (Spring Boot 3.x) auto-detects dialect
            else if ("hibernate.dialect".equals(name)) {
                acc.todoComments.add("# NOTE: spring.jpa.database-platform is usually not needed with Hibernate 6.x (auto-detected)");
                acc.springProperties.add("spring.jpa.database-platform=" + value);
            }
            // Hibernate show_sql
            else if ("hibernate.show_sql".equals(name)) {
                acc.springProperties.add("spring.jpa.show-sql=" + value);
            }
            // Hibernate format_sql
            else if ("hibernate.format_sql".equals(name)) {
                acc.springProperties.add("spring.jpa.properties.hibernate.format_sql=" + value);
            }
            // Hibernate use_sql_comments
            else if ("hibernate.use_sql_comments".equals(name)) {
                acc.springProperties.add("spring.jpa.properties.hibernate.use_sql_comments=" + value);
            }
            // Hibernate interceptor (deprecated property name → modern property name)
            // hibernate.ejb.interceptor is the deprecated JPA-era property name
            // In Hibernate 6.x (Spring Boot 3.x), use hibernate.session_factory.interceptor
            else if ("hibernate.ejb.interceptor".equals(name)) {
                acc.springProperties.add("spring.jpa.properties.hibernate.session_factory.interceptor=" + value);
            }
            // EclipseLink properties - add as comments since Spring Boot uses Hibernate by default
            else if (name.startsWith("eclipselink.")) {
                acc.todoComments.add("# EclipseLink property (Spring Boot uses Hibernate): " + name + "=" + value);
            }
            // SQL load script
            else if ("jakarta.persistence.sql-load-script-source".equals(name) ||
                     "javax.persistence.sql-load-script-source".equals(name)) {
                // Spring Boot uses data.sql by default
                if (!"META-INF/data.sql".equals(value)) {
                    acc.todoComments.add("# TODO: Move " + value + " to " +
                        acc.persistenceResourceRoot + "/data.sql");
                }
                acc.springProperties.add("spring.sql.init.mode=always");
                // Important: When using ddl-auto with data.sql, defer initialization is often needed
                acc.todoComments.add("# NOTE: If using ddl-auto=create with data.sql, you may need:");
                acc.todoComments.add("# spring.jpa.defer-datasource-initialization=true");
            }
            // JDBC properties - can be directly mapped to spring.datasource.*
            else if ("jakarta.persistence.jdbc.url".equals(name) || "javax.persistence.jdbc.url".equals(name)) {
                acc.springProperties.add("spring.datasource.url=" + value);
            }
            else if ("jakarta.persistence.jdbc.user".equals(name) || "javax.persistence.jdbc.user".equals(name)) {
                acc.springProperties.add("spring.datasource.username=" + value);
            }
            else if ("jakarta.persistence.jdbc.password".equals(name) || "javax.persistence.jdbc.password".equals(name)) {
                acc.springProperties.add("spring.datasource.password=" + value);
            }
            else if ("jakarta.persistence.jdbc.driver".equals(name) || "javax.persistence.jdbc.driver".equals(name)) {
                acc.springProperties.add("spring.datasource.driver-class-name=" + value);
            }
            // Other jakarta.persistence properties - pass through to spring.jpa.properties
            // These are standard JPA properties that Hibernate/Spring can process directly
            else if (name.startsWith("jakarta.persistence.") || name.startsWith("javax.persistence.")) {
                // Passthrough: jakarta.persistence.* → spring.jpa.properties.jakarta.persistence.*
                // This includes: lock.timeout, query.timeout, validation.mode, etc.
                acc.springProperties.add("spring.jpa.properties." + name + "=" + value);
            }
            // Pass through other Hibernate properties
            else if (name.startsWith("hibernate.")) {
                acc.springProperties.add("spring.jpa.properties." + name + "=" + value);
            }
        }
    }

    private String mapSchemaGenerationAction(String action) {
        if (action == null) return null;
        switch (action.toLowerCase()) {
            case "create":
                return "create";
            case "drop-and-create":
                return "create-drop";
            case "drop":
                // "drop" alone is dangerous - return null to trigger TODO comment
                return null;
            case "none":
                return "none";
            case "validate":
                return "validate";
            case "update":
                return "update";
            default:
                return null;
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // Skip if:
        // - no persistence.xml found
        // - already migrated
        // - nothing to add
        // Note: If hasApplicationProperties is false, generate() will create the file
        if (!acc.foundPersistenceXml ||
            acc.applicationPropertiesAlreadyMigrated ||
            (acc.springProperties.isEmpty() && acc.todoComments.isEmpty())) {
            return TreeVisitor.noop();
        }

        // If no application.properties exists, generate() will handle it
        if (!acc.hasApplicationProperties) {
            return TreeVisitor.noop();
        }

        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public @Nullable Tree visit(@Nullable Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;

                    Path moduleRoot = extractProjectRoot(sourceFile.getSourcePath());
                    ProjectConfiguration config = acc.configCache.computeIfAbsent(
                        moduleRoot, ProjectConfigurationLoader::load);
                    Path applicationPropertiesPath = acc.applicationPropertiesPaths.computeIfAbsent(
                        moduleRoot, root -> resolveApplicationPropertiesPath(config));

                    // Update existing application.properties only
                    if (sourceFile.getSourcePath().equals(applicationPropertiesPath)) {
                        return updateApplicationProperties(sourceFile, acc);
                    }
                }
                return tree;
            }
        };
    }

    private SourceFile updateApplicationProperties(SourceFile sourceFile, Accumulator acc) {
        // Build the new content to append
        StringBuilder newContent = new StringBuilder();
        newContent.append("\n\n# ===== JPA Configuration (migrated from persistence.xml) =====\n");

        // Add TODO comments
        for (String comment : acc.todoComments) {
            newContent.append(comment).append("\n");
        }

        // Add blank line before properties
        if (!acc.springProperties.isEmpty() && !acc.todoComments.isEmpty()) {
            newContent.append("\n");
        }

        // Add Spring properties
        for (int i = 0; i < acc.springProperties.size(); i++) {
            newContent.append(acc.springProperties.get(i));
            if (i < acc.springProperties.size() - 1) {
                newContent.append("\n");
            }
        }

        // Get existing content and append new content
        String existingContent = sourceFile.printAll();
        // Remove trailing newline from existing content if present (we'll add proper formatting)
        if (existingContent.endsWith("\n")) {
            existingContent = existingContent.substring(0, existingContent.length() - 1);
        }
        String updatedContent = existingContent + newContent.toString();

        // Use PropertiesParser to create a proper Properties.File AST
        // This enables other OpenRewrite recipes to operate on the generated properties
        // (e.g., ChangePropertyValue, DeleteProperty) and maintains LST integrity.
        return PropertiesParser.builder().build()
            .parse(updatedContent)
            .findFirst()
            .map(parsed -> (SourceFile) ((Properties.File) parsed)
                .withId(sourceFile.getId())
                .withSourcePath(sourceFile.getSourcePath()))
            .orElse(sourceFile);
    }

    @Override
    public List<SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        // If no application.properties exists and not already migrated, generate one
        if (!acc.hasApplicationProperties && !acc.applicationPropertiesAlreadyMigrated &&
            acc.foundPersistenceXml &&
            (!acc.springProperties.isEmpty() || !acc.todoComments.isEmpty())) {

            StringBuilder content = new StringBuilder();
            content.append("# Spring Boot Application Properties\n");
            content.append("# Generated from persistence.xml migration\n\n");

            // Add TODO comments
            for (String comment : acc.todoComments) {
                content.append(comment).append("\n");
            }

            // Add blank line before properties
            if (!acc.springProperties.isEmpty() && !acc.todoComments.isEmpty()) {
                content.append("\n");
            }

            // Add Spring properties
            for (String prop : acc.springProperties) {
                content.append(prop).append("\n");
            }

            // Use PropertiesParser to create a proper Properties.File AST
            // This enables other OpenRewrite recipes to operate on the generated properties
            return PropertiesParser.builder().build()
                .parse(content.toString())
                .map(parsed -> (SourceFile) parsed.withSourcePath(resolveGeneratedPropertiesPath(acc)))
                .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private Path resolveGeneratedPropertiesPath(Accumulator acc) {
        Path moduleRoot = acc.persistenceModuleRoot != null
            ? acc.persistenceModuleRoot
            : Paths.get(System.getProperty("user.dir"));
        ProjectConfiguration config = acc.configCache.computeIfAbsent(
            moduleRoot, ProjectConfigurationLoader::load);
        return acc.applicationPropertiesPaths.computeIfAbsent(
            moduleRoot, root -> resolveApplicationPropertiesPath(config));
    }

    private static Path resolveApplicationPropertiesPath(ProjectConfiguration config) {
        return Paths.get(resolveResourceRoot(config), "application.properties");
    }

    private static String resolveResourceRoot(ProjectConfiguration config) {
        List<String> resourceRoots = config.getResourceRoots();
        if (resourceRoots == null || resourceRoots.isEmpty()) {
            return DEFAULT_RESOURCE_ROOT;
        }
        return resourceRoots.get(0);
    }

    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = sourcePath.toAbsolutePath().getParent();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("project.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get(System.getProperty("user.dir"));
    }
}
