package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.RemovePlugin;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.tree.Xml;

import java.util.Optional;

/**
 * Converts EAR modules to POM aggregator modules for Spring Boot compatibility.
 * <p>
 * EAR (Enterprise Application Archive) packaging is used in traditional Jakarta EE
 * for deploying applications to application servers. Spring Boot does not use EAR
 * packaging; instead, applications are packaged as executable JARs.
 * <p>
 * This recipe performs a non-destructive conversion:
 * <ul>
 *   <li>{@code <packaging>ear</packaging>} → {@code <packaging>pom</packaging>}</li>
 *   <li>Removes {@code maven-ear-plugin}</li>
 *   <li>Preserves all module declarations and dependencies</li>
 * </ul>
 * <p>
 * The converted module becomes a POM aggregator that can still coordinate builds
 * of its sub-modules without producing an EAR artifact.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateEarToAggregator extends Recipe {

    private static final XPathMatcher PACKAGING_MATCHER = new XPathMatcher("/project/packaging");

    public MigrateEarToAggregator() {
    }

    @Override
    public String getDisplayName() {
        return "Convert EAR packaging to POM aggregator";
    }

    @Override
    public String getDescription() {
        return "Converts Maven EAR modules to POM aggregator modules for Spring Boot compatibility. " +
               "EAR packaging is not used in Spring Boot; this recipe preserves the module structure " +
               "while changing the packaging type to 'pom'.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new MavenIsoVisitor<ExecutionContext>() {
            private boolean changedPackaging = false;

            @Override
            public Xml.Document visitDocument(Xml.Document document, ExecutionContext ctx) {
                changedPackaging = false;
                Xml.Document doc = super.visitDocument(document, ctx);

                // If we changed packaging, also remove maven-ear-plugin
                if (changedPackaging) {
                    doc = (Xml.Document) new RemovePlugin(
                        "org.apache.maven.plugins",
                        "maven-ear-plugin"
                    ).getVisitor().visit(doc, ctx);
                }

                return doc;
            }

            @Override
            public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
                Xml.Tag t = super.visitTag(tag, ctx);

                if (PACKAGING_MATCHER.matches(getCursor())) {
                    Optional<String> packagingValue = t.getValue();
                    if (packagingValue.isPresent() && "ear".equals(packagingValue.get())) {
                        changedPackaging = true;
                        // Change ear to pom
                        return t.withValue("pom");
                    }
                }
                return t;
            }
        };
    }
}
