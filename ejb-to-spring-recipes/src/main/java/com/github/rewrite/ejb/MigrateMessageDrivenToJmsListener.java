package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrates EJB @MessageDriven beans to Spring @JmsListener.
 * <p>
 * Transformation:
 * - @MessageDriven with activationConfig -> @Component on class + @JmsListener on onMessage method
 * - Extracts destination from destinationLookup property (strips JNDI prefix)
 * - messageSelector -> selector parameter in @JmsListener
 * - maxSession -> concurrency parameter in @JmsListener
 * - acknowledgeMode -> @NeedsReview annotation (Spring handles differently)
 * - Removes MessageListener interface (Spring doesn't need it)
 * - Adds required imports
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateMessageDrivenToJmsListener extends Recipe {

    private static final String MESSAGE_DRIVEN_FQN = "jakarta.ejb.MessageDriven";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final String ACTIVATION_CONFIG_FQN = "jakarta.ejb.ActivationConfigProperty";
    private static final String MESSAGE_LISTENER_FQN = "jakarta.jms.MessageListener";
    private static final String JMS_LISTENER_FQN = "org.springframework.jms.annotation.JmsListener";
    private static final String COMPONENT_FQN = "org.springframework.stereotype.Component";
    private static final String SPRING_CONFIGURATION_FQN = "org.springframework.context.annotation.Configuration";
    private static final String SPRING_BEAN_FQN = "org.springframework.context.annotation.Bean";
    private static final String DEFAULT_JMS_LISTENER_CONTAINER_FACTORY_FQN = "org.springframework.jms.config.DefaultJmsListenerContainerFactory";
    private static final String JAKARTA_JMS_CONNECTION_FACTORY_FQN = "jakarta.jms.ConnectionFactory";
    private static final String CONFIGURATION_STUB = "package org.springframework.context.annotation; public @interface Configuration {}";
    private static final String BEAN_STUB = "package org.springframework.context.annotation; public @interface Bean {}";
    private static final String CONNECTION_FACTORY_STUB = "package jakarta.jms; public interface ConnectionFactory {}";
    private static final String SESSION_STUB = "package jakarta.jms; public interface Session { " +
        "int AUTO_ACKNOWLEDGE = 1; int CLIENT_ACKNOWLEDGE = 2; int DUPS_OK_ACKNOWLEDGE = 3; }";
    private static final String DEFAULT_JMS_LISTENER_CONTAINER_FACTORY_STUB =
        "package org.springframework.jms.config; " +
        "public class DefaultJmsListenerContainerFactory { " +
        "  public void setConnectionFactory(jakarta.jms.ConnectionFactory cf) {} " +
        "  public void setSessionAcknowledgeMode(int mode) {} " +
        "  public void setSubscriptionDurable(boolean durable) {} " +
        "  public void setPubSubDomain(boolean pubSubDomain) {} " +
        "  public void setClientId(String clientId) {} " +
        "}";
    private static final String JAKARTA_JMS_SESSION_FQN = "jakarta.jms.Session";

    private enum MdbDestinationType {
        QUEUE,
        TOPIC,
        UNKNOWN
    }

    // Pattern to extract queue name from JNDI lookup like "java:app/jms/CargoHandledQueue"
    private static final Pattern JNDI_PATTERN = Pattern.compile("java:[^/]*/(?:jms/)?(.+)");

    @Override
    public String getDisplayName() {
        return "Migrate @MessageDriven to Spring @JmsListener";
    }

    @Override
    public String getDescription() {
        return "Converts EJB @MessageDriven beans to Spring @Component with @JmsListener on the message handler method.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            new UsesType<>(MESSAGE_DRIVEN_FQN, false),
            new MessageDrivenVisitor()
        );
    }

    private static class MessageDrivenVisitor extends JavaIsoVisitor<ExecutionContext> {
        private MdbConfig mdbConfig = null;
        private boolean addedJmsListener = false;
        private String currentClassName = null;
        private boolean jmsProviderConfigured = false;

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            MdbConfig previousConfig = mdbConfig;
            boolean previousAdded = addedJmsListener;
            String previousClassName = currentClassName;
            boolean previousProviderConfigured = jmsProviderConfigured;

            mdbConfig = new MdbConfig();
            addedJmsListener = false;
            currentClassName = classDecl.getSimpleName();
            jmsProviderConfigured = false;

            try {
                // Load project configuration to check if JMS provider is configured
                J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                if (cu != null && cu.getSourcePath() != null) {
                    Path projectRoot = extractProjectRoot(cu.getSourcePath());
                    ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(projectRoot);
                    jmsProviderConfigured = config.hasJmsProviderConfigured();
                }

                // Find @MessageDriven annotation and extract config
                J.Annotation messageDrivenAnn = null;
                for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                    if (isMessageDrivenAnnotation(ann)) {
                        messageDrivenAnn = ann;
                        extractActivationConfig(ann);
                        break;
                    }
                }

                if (messageDrivenAnn == null) {
                    return classDecl;
                }

                if (mdbConfig.shouldGenerateContainerFactory()) {
                    mdbConfig.prepareContainerFactoryNames(currentClassName);
                }

                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Remove @MessageDriven, add @Component and optionally @NeedsReview for broker config
                List<J.Annotation> newAnnotations = new ArrayList<>();
                boolean addedComponent = false;
                Space firstAnnotationPrefix = null;

                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    if (isMessageDrivenAnnotation(ann)) {
                        if (firstAnnotationPrefix == null) {
                            firstAnnotationPrefix = ann.getPrefix();
                        }
                        if (!addedComponent) {
                            maybeAddImport(COMPONENT_FQN);
                            newAnnotations.add(createSimpleAnnotation("Component", COMPONENT_FQN, ann.getPrefix()));
                            addedComponent = true;
                        }
                    } else {
                        newAnnotations.add(ann);
                    }
                }

                if (!jmsProviderConfigured) {
                    maybeAddImport(NEEDS_REVIEW_FQN);
                    Space brokerReviewPrefix = firstAnnotationPrefix != null ? firstAnnotationPrefix : Space.EMPTY;
                    J.Annotation brokerReviewAnn = createNeedsReviewAnnotation(
                        "JMS broker must be configured in application.properties (spring.artemis.* or spring.activemq.*)",
                        "MESSAGING",
                        "@MessageDriven",
                        "Add spring-boot-starter-artemis or spring-boot-starter-activemq dependency and configure broker connection",
                        brokerReviewPrefix
                    );

                    if (!newAnnotations.isEmpty()) {
                        J.Annotation first = newAnnotations.get(0);
                        newAnnotations.set(0, first.withPrefix(Space.format("\n")));
                    }
                    newAnnotations.add(0, brokerReviewAnn);
                }

                cd = cd.withLeadingAnnotations(newAnnotations);

                // Remove MessageListener interface
                if (cd.getImplements() != null) {
                    List<TypeTree> newImplements = new ArrayList<>();
                    for (TypeTree impl : cd.getImplements()) {
                        if (!isMessageListenerInterface(impl)) {
                            newImplements.add(impl);
                        }
                    }
                    if (newImplements.isEmpty()) {
                        cd = cd.withImplements(null);
                    } else if (newImplements.size() != cd.getImplements().size()) {
                        cd = cd.withImplements(newImplements);
                    }
                }

                if (addedJmsListener && mdbConfig.shouldGenerateContainerFactory()) {
                    cd = addContainerFactoryConfiguration(cd, ctx);
                }

                // Remove imports
                doAfterVisit(new RemoveImport<>(MESSAGE_DRIVEN_FQN, true));
                doAfterVisit(new RemoveImport<>(ACTIVATION_CONFIG_FQN, true));
                doAfterVisit(new RemoveImport<>(MESSAGE_LISTENER_FQN, true));

                return cd;
            } finally {
                mdbConfig = previousConfig;
                addedJmsListener = previousAdded;
                currentClassName = previousClassName;
                jmsProviderConfigured = previousProviderConfigured;
            }
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            // Check if this is the onMessage method
            if (!"onMessage".equals(md.getSimpleName())) {
                return md;
            }

            // Check if we have a destination to add
            if (mdbConfig == null || mdbConfig.destination == null) {
                return md;
            }

            // Check if @JmsListener already exists
            boolean hasJmsListener = md.getLeadingAnnotations().stream()
                .anyMatch(a -> "JmsListener".equals(a.getSimpleName()));

            if (hasJmsListener) {
                return md;
            }

            // Add @JmsListener annotation and remove @Override (no longer implements interface)
            maybeAddImport(JMS_LISTENER_FQN);

            List<J.Annotation> newAnnotations = new ArrayList<>();
            Space overridePrefix = null;
            // Remove @Override since we removed MessageListener interface
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if ("Override".equals(ann.getSimpleName())) {
                    // Capture prefix from @Override to avoid leaving blank line
                    overridePrefix = ann.getPrefix();
                } else {
                    newAnnotations.add(ann);
                }
            }

            // Capture the method's prefix (which includes leading whitespace and possibly Javadoc)
            // When the method has leading annotations, this prefix goes BEFORE the first annotation
            Space methodOriginalPrefix = md.getPrefix();
            String indentation = extractIndentation(methodOriginalPrefix.getWhitespace());

            // For @JmsListener prefix: use simple newline+indent (not the full method prefix with Javadoc)
            // If @Override was present, its prefix would be used, otherwise the method had no annotations
            Space jmsListenerPrefix;
            if (overridePrefix != null && !overridePrefix.isEmpty()) {
                // Use @Override's prefix (usually empty, but let's be safe)
                jmsListenerPrefix = stripTrailingWhitespace(overridePrefix);
            } else {
                // No previous annotation, use simple newline+indent
                jmsListenerPrefix = Space.EMPTY;
            }
            newAnnotations.add(0, createJmsListenerAnnotation(jmsListenerPrefix));

            md = md.withLeadingAnnotations(newAnnotations);
            addedJmsListener = true;

            // Keep the method's original prefix (which provides the newline before the method)
            // Strip any Javadoc comments from it since we're adding annotations now
            if (methodOriginalPrefix.getComments().isEmpty()) {
                // No Javadoc, keep the prefix as-is (it provides the newline)
                md = md.withPrefix(methodOriginalPrefix);
            } else {
                // Has Javadoc - the first annotation should get the full prefix including Javadoc
                // The method prefix should be EMPTY since the annotation prefix contains all needed whitespace
                J.Annotation firstAnn = md.getLeadingAnnotations().get(0);
                Space prefixWithJavadoc = stripTrailingWhitespace(methodOriginalPrefix);
                md = md.withPrefix(Space.EMPTY);

                // Update first annotation to have the Javadoc prefix
                List<J.Annotation> updatedAnnotations = new ArrayList<>(md.getLeadingAnnotations());
                updatedAnnotations.set(0, firstAnn.withPrefix(prefixWithJavadoc));
                md = md.withLeadingAnnotations(updatedAnnotations);
            }

            // Add @NeedsReview if there are unmapped configurations
            if (mdbConfig.hasUnmappedConfig()) {
                boolean hasNeedsReview = md.getLeadingAnnotations().stream()
                    .anyMatch(a -> a.getSimpleName().equals("NeedsReview"));

                if (!hasNeedsReview) {
                    String warningStr = mdbConfig.getUnmappedWarning().replace("\"", "'");
                    maybeAddImport(NEEDS_REVIEW_FQN);

                    // Get prefix from first annotation for proper positioning (includes Javadoc)
                    Space needsReviewPrefix = md.getLeadingAnnotations().isEmpty()
                        ? md.getPrefix()
                        : md.getLeadingAnnotations().get(0).getPrefix();

                    // Create @NeedsReview annotation directly (for idempotency)
                    J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                        "MDB configuration needs review: " + warningStr,
                        "MESSAGING",
                        "@MessageDriven",
                        "Configure JMS container factory or connection settings",
                        needsReviewPrefix
                    );

                    // Insert @NeedsReview at beginning, move Javadoc to it
                    List<J.Annotation> finalAnnotations = new ArrayList<>();
                    finalAnnotations.add(needsReviewAnn);

                    for (int i = 0; i < md.getLeadingAnnotations().size(); i++) {
                        J.Annotation ann = md.getLeadingAnnotations().get(i);
                        if (i == 0) {
                            // First original annotation gets simple newline prefix (Javadoc moved to @NeedsReview)
                            ann = ann.withPrefix(Space.format("\n" + indentation));
                        }
                        finalAnnotations.add(ann);
                    }

                    md = md.withLeadingAnnotations(finalAnnotations);
                }
            }

            // When leading annotations are present, we need to add newline to the first modifier's prefix
            // (the method prefix doesn't add space before the modifiers when annotations exist)
            if (!md.getLeadingAnnotations().isEmpty() && !md.getModifiers().isEmpty()) {
                List<J.Modifier> modifiers = new ArrayList<>(md.getModifiers());
                J.Modifier firstMod = modifiers.get(0);
                // Only add newline if not already present
                String currentPrefix = firstMod.getPrefix().getWhitespace();
                if (!currentPrefix.contains("\n")) {
                    modifiers.set(0, firstMod.withPrefix(Space.format("\n" + indentation)));
                    md = md.withModifiers(modifiers);
                }
            }

            return md;
        }

        private boolean isMessageDrivenAnnotation(J.Annotation ann) {
            if (ann.getType() != null && TypeUtils.isOfClassType(ann.getType(), MESSAGE_DRIVEN_FQN)) {
                return true;
            }
            return "MessageDriven".equals(ann.getSimpleName());
        }

        private boolean isMessageListenerInterface(TypeTree typeTree) {
            if (typeTree instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeTree;
                if (ident.getType() != null && TypeUtils.isOfClassType(ident.getType(), MESSAGE_LISTENER_FQN)) {
                    return true;
                }
                return "MessageListener".equals(ident.getSimpleName());
            }
            return false;
        }

        private void extractActivationConfig(J.Annotation ann) {
            if (ann.getArguments() == null) {
                return;
            }

            for (Expression arg : ann.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        if ("activationConfig".equals(name)) {
                            extractFromActivationConfig(assignment.getAssignment());
                        }
                    }
                }
            }
        }

        private void extractFromActivationConfig(Expression configArray) {
            if (!(configArray instanceof J.NewArray)) {
                return;
            }

            J.NewArray newArray = (J.NewArray) configArray;
            if (newArray.getInitializer() == null) {
                return;
            }

            for (Expression element : newArray.getInitializer()) {
                if (element instanceof J.Annotation) {
                    J.Annotation configProp = (J.Annotation) element;
                    extractActivationConfigProperty(configProp);
                }
            }
        }

        private void extractActivationConfigProperty(J.Annotation prop) {
            if (prop.getArguments() == null) {
                return;
            }

            String propertyName = null;
            String propertyValue = null;
            Expression propertyValueExpr = null;

            for (Expression arg : prop.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        Expression value = assignment.getAssignment();

                        if ("propertyName".equals(name) && value instanceof J.Literal) {
                            propertyName = (String) ((J.Literal) value).getValue();
                        } else if ("propertyValue".equals(name)) {
                            propertyValueExpr = value;
                            if (value instanceof J.Literal) {
                                propertyValue = (String) ((J.Literal) value).getValue();
                            }
                        }
                    }
                }
            }

            if (propertyName == null) {
                return;
            }

            if ("destinationType".equals(propertyName)) {
                mdbConfig.destinationTypeExplicit = true;
                if (propertyValueExpr instanceof J.Literal) {
                    mdbConfig.destinationType = propertyValue;
                } else if (propertyValueExpr != null) {
                    mdbConfig.destinationTypeNonLiteral = true;
                    mdbConfig.destinationType = propertyValueExpr.print(new Cursor(getCursor(), propertyValueExpr));
                }
                return;
            }

            if (propertyValue != null) {
                switch (propertyName) {
                    case "destinationLookup":
                    case "destination":
                        // Extract queue name from JNDI lookup
                        Matcher matcher = JNDI_PATTERN.matcher(propertyValue);
                        if (matcher.matches()) {
                            mdbConfig.destination = matcher.group(1);
                        } else {
                            mdbConfig.destination = propertyValue;
                        }
                        break;
                    case "messageSelector":
                        mdbConfig.selector = propertyValue;
                        break;
                    case "acknowledgeMode":
                        mdbConfig.acknowledgeMode = propertyValue;
                        break;
                    case "maxSession":
                    case "maxConcurrency":
                        mdbConfig.concurrency = propertyValue;
                        break;
                    case "subscriptionDurability":
                        if ("Durable".equals(propertyValue)) {
                            mdbConfig.subscriptionDurable = true;
                        }
                        break;
                    case "clientId":
                        mdbConfig.clientId = propertyValue;
                        break;
                    case "subscriptionName":
                        mdbConfig.subscriptionName = propertyValue;
                        break;
                    case "connectionFactoryLookup":
                    case "connectionFactoryJndiName":
                        mdbConfig.connectionFactory = propertyValue;
                        break;
                }
            }
        }

        private J.ClassDeclaration addContainerFactoryConfiguration(J.ClassDeclaration cd, ExecutionContext ctx) {
            if (cd.getBody() == null || mdbConfig.containerFactoryConfigClassName == null || mdbConfig.containerFactoryBeanName == null) {
                return cd;
            }
            if (hasNestedClassNamed(cd, mdbConfig.containerFactoryConfigClassName)) {
                return cd;
            }

            doAfterVisit(new AddImport<>(SPRING_CONFIGURATION_FQN, null, false));
            doAfterVisit(new AddImport<>(SPRING_BEAN_FQN, null, false));
            doAfterVisit(new AddImport<>(DEFAULT_JMS_LISTENER_CONTAINER_FACTORY_FQN, null, false));
            doAfterVisit(new AddImport<>(JAKARTA_JMS_CONNECTION_FACTORY_FQN, null, false));

            // Add Session import if acknowledge mode is configured
            boolean needsSessionImport = mdbConfig.resolveAcknowledgeMode() != null;
            if (needsSessionImport) {
                doAfterVisit(new AddImport<>(JAKARTA_JMS_SESSION_FQN, null, false));
            }

            String source = buildContainerFactoryConfigurationSource();
            JavaTemplate.Builder templateBuilder = JavaTemplate.builder(source)
                .imports(
                    SPRING_CONFIGURATION_FQN,
                    SPRING_BEAN_FQN,
                    DEFAULT_JMS_LISTENER_CONTAINER_FACTORY_FQN,
                    JAKARTA_JMS_CONNECTION_FACTORY_FQN
                )
                .javaParser(JavaParser.fromJavaVersion().dependsOn(
                    CONFIGURATION_STUB,
                    BEAN_STUB,
                    DEFAULT_JMS_LISTENER_CONTAINER_FACTORY_STUB,
                    CONNECTION_FACTORY_STUB,
                    SESSION_STUB
                ))
                .contextSensitive();

            if (needsSessionImport) {
                templateBuilder = templateBuilder.imports(JAKARTA_JMS_SESSION_FQN);
            }

            JavaTemplate template = templateBuilder.build();

            J.ClassDeclaration updated = template.apply(
                new Cursor(getCursor(), cd),
                cd.getBody().getCoordinates().lastStatement()
            );
            return autoFormat(updated, ctx);
        }

        private boolean hasNestedClassNamed(J.ClassDeclaration cd, String className) {
            if (cd.getBody() == null) {
                return false;
            }
            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.ClassDeclaration) {
                    if (className.equals(((J.ClassDeclaration) stmt).getSimpleName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        private String buildContainerFactoryConfigurationSource() {
            StringBuilder builder = new StringBuilder();
            builder.append("\n\n    @Configuration\n")
                .append("    static class ").append(mdbConfig.containerFactoryConfigClassName).append(" {\n\n")
                .append("        @Bean\n")
                .append("        public DefaultJmsListenerContainerFactory ")
                .append(mdbConfig.containerFactoryBeanName)
                .append("(ConnectionFactory connectionFactory) {\n")
                .append("            DefaultJmsListenerContainerFactory factory = new DefaultJmsListenerContainerFactory();\n")
                .append("            factory.setConnectionFactory(connectionFactory);\n");

            String acknowledgeMode = mdbConfig.resolveAcknowledgeMode();
            if (acknowledgeMode != null) {
                // Use setSessionAcknowledgeMode with Session constant (not setSessionAcknowledgeModeName which doesn't exist)
                builder.append("            factory.setSessionAcknowledgeMode(Session.")
                    .append(acknowledgeMode)
                    .append(");\n");
            }
            boolean pubSubDomainSet = false;
            if (mdbConfig.subscriptionDurable && mdbConfig.subscriptionName != null) {
                builder.append("            factory.setSubscriptionDurable(true);\n")
                    .append("            factory.setPubSubDomain(true);\n");
                pubSubDomainSet = true;
            }
            if (!pubSubDomainSet && mdbConfig.resolveDestinationType() == MdbDestinationType.TOPIC) {
                builder.append("            factory.setPubSubDomain(true);\n");
            }
            if (mdbConfig.clientId != null) {
                builder.append("            factory.setClientId(\"")
                    .append(escapeJavaString(mdbConfig.clientId))
                    .append("\");\n");
            }

            builder.append("            return factory;\n")
                .append("        }\n")
                .append("    }\n");

            return builder.toString();
        }

        private J.Annotation createSimpleAnnotation(String simpleName, String fqn, Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(fqn);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                simpleName,
                type,
                null
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                ident,
                null
            );
        }

        private J.Annotation createJmsListenerAnnotation(Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(JMS_LISTENER_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "JmsListener",
                type,
                null
            );

            List<JRightPadded<Expression>> arguments = new ArrayList<>();

            // destination (required)
            arguments.add(createAssignmentArg("destination", mdbConfig.destination, false));

            // containerFactory (optional)
            if (mdbConfig.containerFactoryBeanName != null) {
                arguments.add(createAssignmentArg("containerFactory", mdbConfig.containerFactoryBeanName, false));
            }

            // selector (optional)
            if (mdbConfig.selector != null) {
                arguments.add(createAssignmentArg("selector", mdbConfig.selector, true));
            }

            // concurrency (optional)
            if (mdbConfig.concurrency != null) {
                arguments.add(createAssignmentArg("concurrency", mdbConfig.concurrency, true));
            }

            // subscription (for durable topics)
            if (mdbConfig.subscriptionName != null) {
                arguments.add(createAssignmentArg("subscription", mdbConfig.subscriptionName, true));
            }

            // Wrap in JContainer for annotation arguments
            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
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

        private JRightPadded<Expression> createAssignmentArg(String key, String value, boolean leadingSpace) {
            String escapedValue = escapeJavaString(value);
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                leadingSpace ? Space.format(" ") : Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                key,
                null,
                null
            );

            // The value needs prefix space (= space AFTER the = sign)
            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),  // Space after = (before value)
                Markers.EMPTY,
                value,
                "\"" + escapedValue + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
            );

            // JLeftPadded's before is space BEFORE the = sign
            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),  // Space before =
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }

        private J.Annotation createNeedsReviewAnnotation(String reason, String category,
                                                          String originalCode, String suggestedAction,
                                                          Space prefix) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                type,
                null
            );

            List<JRightPadded<Expression>> arguments = new ArrayList<>();
            arguments.add(createAssignmentArg("reason", reason, false));
            arguments.add(createCategoryArg(category));
            arguments.add(createAssignmentArg("originalCode", originalCode, true));
            arguments.add(createAssignmentArg("suggestedAction", suggestedAction, true));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                arguments,
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

        private String escapeJavaString(String value) {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }

        /**
         * Extracts the indentation (spaces/tabs after last newline) from a whitespace string.
         * For example, from "\n\n    " returns "    ".
         */
        private String extractIndentation(String whitespace) {
            if (whitespace == null || whitespace.isEmpty()) {
                return "    "; // Default indentation
            }
            int lastNewline = whitespace.lastIndexOf('\n');
            if (lastNewline >= 0 && lastNewline < whitespace.length() - 1) {
                return whitespace.substring(lastNewline + 1);
            }
            // No newline found, might be pure spaces
            if (whitespace.matches("^\\s+$")) {
                return whitespace;
            }
            return "    "; // Default
        }

        /**
         * Strips trailing whitespace from each line in the given Space prefix.
         */
        private Space stripTrailingWhitespace(Space space) {
            String ws = space.getWhitespace();
            if (ws == null || ws.isEmpty()) {
                return space;
            }
            // Strip trailing spaces from each line (but preserve newlines)
            String cleaned = ws.replaceAll("[ \\t]+\\n", "\n");
            if (cleaned.equals(ws)) {
                return space;
            }
            return space.withWhitespace(cleaned);
        }

        /**
         * Extracts the project root directory from the given source path.
         */
        private Path extractProjectRoot(Path sourcePath) {
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

        private JRightPadded<Expression> createCategoryArg(String categoryName) {
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                "category",
                null,
                null
            );

            JavaType.ShallowClass categoryType = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN + ".Category");

            J.Identifier needsReviewIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "NeedsReview",
                JavaType.ShallowClass.build(NEEDS_REVIEW_FQN),
                null
            );

            J.FieldAccess categoryAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                needsReviewIdent,
                JLeftPadded.build(new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "Category",
                    categoryType,
                    null
                )),
                categoryType
            );

            J.FieldAccess valueExpr = new J.FieldAccess(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                categoryAccess,
                JLeftPadded.build(new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    categoryName,
                    categoryType,
                    null
                )),
                categoryType
            );

            J.Assignment assignment = new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                keyIdent,
                JLeftPadded.<Expression>build(valueExpr).withBefore(Space.format(" ")),
                null
            );

            return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
        }
    }

    private static class MdbConfig {
        String destination = null;
        String selector = null;
        String acknowledgeMode = null;
        String concurrency = null;
        boolean subscriptionDurable = false;
        String clientId = null;
        String subscriptionName = null;
        String connectionFactory = null;
        String containerFactoryBeanName = null;
        String containerFactoryConfigClassName = null;
        String destinationType = null;
        boolean destinationTypeExplicit = false;
        boolean destinationTypeNonLiteral = false;

        void prepareContainerFactoryNames(String className) {
            if (className == null || className.isEmpty()) {
                return;
            }
            if (containerFactoryBeanName == null) {
                containerFactoryBeanName = decapitalize(className) + "JmsListenerContainerFactory";
            }
            if (containerFactoryConfigClassName == null) {
                containerFactoryConfigClassName = className + "JmsListenerConfiguration";
            }
        }

        boolean shouldGenerateContainerFactory() {
            if (connectionFactory != null) {
                return false;
            }
            if (resolveAcknowledgeMode() != null) {
                return true;
            }
            if (clientId != null) {
                return true;
            }
            if (resolveDestinationType() == MdbDestinationType.TOPIC) {
                return true;
            }
            return subscriptionDurable && subscriptionName != null;
        }

        boolean hasUnmappedConfig() {
            boolean ackNeedsReview = acknowledgeMode != null
                && (resolveAcknowledgeMode() == null || connectionFactory != null);
            boolean clientNeedsReview = clientId != null && connectionFactory != null;
            boolean connectionFactoryNeedsReview = connectionFactory != null;
            boolean durableNeedsReview = subscriptionDurable && (subscriptionName == null || connectionFactory != null);
            boolean destinationTypeNeedsReview = isUnknownDestinationType();
            return ackNeedsReview || clientNeedsReview || connectionFactoryNeedsReview || durableNeedsReview
                || destinationTypeNeedsReview;
        }

        String getUnmappedWarning() {
            List<String> warnings = new ArrayList<>();
            if (acknowledgeMode != null && (resolveAcknowledgeMode() == null || connectionFactory != null)) {
                warnings.add("acknowledgeMode=" + acknowledgeMode + " (configure via JmsListenerContainerFactory)");
            }
            if (clientId != null && connectionFactory != null) {
                warnings.add("clientId=" + clientId + " (configure via ConnectionFactory)");
            }
            if (connectionFactory != null) {
                warnings.add("connectionFactory=" + connectionFactory + " (configure via containerFactory parameter or JmsListenerContainerFactory bean)");
            }
            if (subscriptionDurable && (subscriptionName == null || connectionFactory != null)) {
                warnings.add("subscriptionDurability=Durable requires subscription parameter (add subscriptionName to @JmsListener)");
            }
            if (isUnknownDestinationType()) {
                warnings.add("destinationType=" + formatDestinationType() + " (cannot determine queue vs topic)");
            }
            return String.join("; ", warnings);
        }

        String resolveAcknowledgeMode() {
            if (acknowledgeMode == null) {
                return null;
            }
            String normalized = acknowledgeMode.trim().toLowerCase(Locale.ROOT)
                .replace('_', '-')
                .replace(' ', '-');
            switch (normalized) {
                case "auto-acknowledge":
                    return "AUTO_ACKNOWLEDGE";
                case "client-acknowledge":
                    return "CLIENT_ACKNOWLEDGE";
                case "dups-ok-acknowledge":
                    return "DUPS_OK_ACKNOWLEDGE";
                default:
                    return null;
            }
        }

        private String decapitalize(String value) {
            if (value == null || value.isEmpty()) {
                return value;
            }
            if (value.length() == 1) {
                return value.toLowerCase(Locale.ROOT);
            }
            return Character.toLowerCase(value.charAt(0)) + value.substring(1);
        }

        private boolean isUnknownDestinationType() {
            return destinationTypeExplicit && resolveDestinationType() == MdbDestinationType.UNKNOWN;
        }

        private String formatDestinationType() {
            if (!destinationTypeExplicit) {
                return "<not-set>";
            }
            if (destinationTypeNonLiteral) {
                return destinationType != null ? destinationType : "<non-literal>";
            }
            return destinationType != null ? destinationType : "<unknown>";
        }

        private MdbDestinationType resolveDestinationType() {
            if (!destinationTypeExplicit) {
                return null;
            }
            if (destinationTypeNonLiteral || destinationType == null) {
                return MdbDestinationType.UNKNOWN;
            }
            String normalized = destinationType.trim();
            if (normalized.endsWith(".Topic") || "Topic".equals(normalized)) {
                return MdbDestinationType.TOPIC;
            }
            if (normalized.endsWith(".Queue") || "Queue".equals(normalized)) {
                return MdbDestinationType.QUEUE;
            }
            return MdbDestinationType.UNKNOWN;
        }
    }
}
