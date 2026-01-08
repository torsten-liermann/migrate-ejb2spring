package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.text.PlainText;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Migrates JMS Destination lookups to Spring Boot bean configuration.
 * <p>
 * This recipe:
 * 1. Scans for @NeedsReview + @Autowired Destination fields
 * 2. Extracts destination names from the JNDI lookup paths
 * 3. Generates a JmsConfiguration class with @Bean methods for each queue/topic
 * 4. KEEPS @NeedsReview because the generated lambda Queue may not work with all JMS providers
 * <p>
 * If project.yaml configures a JMS provider, generate provider-specific Queue beans
 * and remove @NeedsReview for those Destination fields.
 * <p>
 * IMPORTANT: The generated JmsConfiguration may use lambda-based JMS implementations.
 * This is a TEMPLATE that works with some JMS providers but may need adjustment for:
 * - Apache Artemis: Use org.apache.activemq.artemis.jms.client.ActiveMQQueue
 * - ActiveMQ Classic: Use org.apache.activemq.command.ActiveMQQueue
 * - IBM MQ: Use com.ibm.mq.jms.MQQueue
 * <p>
 * The @NeedsReview annotation is intentionally kept to remind developers to:
 * 1. Verify the generated JmsConfiguration works with their JMS provider
 * 2. Adjust Queue implementations if necessary
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateJmsDestination extends ScanningRecipe<MigrateJmsDestination.Accumulator> {

    private static final String JAKARTA_DESTINATION = "jakarta.jms.Destination";
    private static final String JAVAX_DESTINATION = "javax.jms.Destination";
    private static final String JAKARTA_QUEUE = "jakarta.jms.Queue";
    private static final String JAVAX_QUEUE = "javax.jms.Queue";
    private static final String JAKARTA_TOPIC = "jakarta.jms.Topic";
    private static final String JAVAX_TOPIC = "javax.jms.Topic";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    // Patterns to extract JNDI lookup values and destination names
    private static final Pattern JNDI_LOOKUP_PATTERN = Pattern.compile("lookup\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern JNDI_JMS_SEGMENT_PATTERN = Pattern.compile("(?i)jms/([^'\"]+)");

    @Override
    public String getDisplayName() {
        return "Migrate JMS Destination to Spring Bean configuration";
    }

    @Override
    public String getDescription() {
        return "Creates a JmsConfiguration class with @Bean definitions for JMS queues. " +
               "IMPORTANT: Keeps @NeedsReview because the generated lambda Queue may need adjustment for specific JMS providers.";
    }

    static class DestinationInfo {
        String fieldName;
        String destinationName;
        DestinationKind kind;
        String className;
        String packageName;
        boolean isTest;
        boolean jndiFallback;
        String originalJndi;
    }

    enum DestinationKind {
        QUEUE,
        TOPIC,
        DESTINATION
    }

    static class DestinationEntry {
        final String name;
        final boolean jndiFallback;
        final String originalJndi;

        DestinationEntry(String name, boolean jndiFallback, String originalJndi) {
            this.name = name;
            this.jndiFallback = jndiFallback;
            this.originalJndi = originalJndi;
        }
    }

    static class Accumulator {
        List<DestinationInfo> destinations = new ArrayList<>();
        Set<String> detectedSourceRoots = new LinkedHashSet<>();
        Set<Path> existingConfigPaths = new HashSet<>();
        String mainPackage = null;
        String testPackage = null;
        String mainSourceRoot = null;
        String testSourceRoot = null;
        ProjectConfiguration.JmsProvider jmsProvider = null;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<SourceFile, ExecutionContext>() {
            private final JavaIsoVisitor<ExecutionContext> javaVisitor = new JavaIsoVisitor<ExecutionContext>() {

                @Override
                public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                    String sourcePath = cu.getSourcePath().toString().replace('\\', '/');

                    // Load project configuration
                    ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                            extractProjectRoot(cu.getSourcePath()));

                    // Capture JMS provider from configuration (if set)
                    if (acc.jmsProvider == null && config.getJmsProvider() != null) {
                        acc.jmsProvider = config.getJmsProvider();
                    }

                    // Detect source roots from configuration
                    for (String root : config.getMainSourceRoots()) {
                        int idx = sourcePath.indexOf(root + "/");
                        if (idx >= 0) {
                            acc.detectedSourceRoots.add(sourcePath.substring(0, idx));
                            if (acc.mainSourceRoot == null) {
                                acc.mainSourceRoot = root;
                            }
                            break;
                        }
                    }
                    for (String root : config.getTestSourceRoots()) {
                        int idx = sourcePath.indexOf(root + "/");
                        if (idx >= 0) {
                            if (acc.testSourceRoot == null) {
                                acc.testSourceRoot = root;
                            }
                            break;
                        }
                    }

                    return super.visitCompilationUnit(cu, ctx);
                }

                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
                    J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);

                    // Check if this is a Destination field
                    JavaType type = vd.getType();
                    if (type == null || !isDestination(type)) {
                        return vd;
                    }

                    // Check for @NeedsReview with JNDI info
                    DestinationName destinationName = null;
                    for (J.Annotation ann : vd.getLeadingAnnotations()) {
                        if (isNeedsReview(ann)) {
                            destinationName = extractDestinationName(ann);
                            if (destinationName != null) {
                                break;
                            }
                        }
                    }

                    if (destinationName == null) {
                        return vd;
                    }

                    // Get context info
                    J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                    J.ClassDeclaration cd = getCursor().firstEnclosingOrThrow(J.ClassDeclaration.class);
                    String sourcePath = cu.getSourcePath().toString().replace('\\', '/');

                    // Load config to check if test source
                    ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                            extractProjectRoot(cu.getSourcePath()));
                    boolean isTest = config.isTestSource(sourcePath);

                    DestinationInfo info = new DestinationInfo();
                    info.fieldName = vd.getVariables().get(0).getSimpleName();
                    info.destinationName = destinationName.name;
                    info.kind = detectKind(type);
                    info.className = cd.getSimpleName();
                    info.packageName = cu.getPackageDeclaration() != null
                        ? cu.getPackageDeclaration().getExpression().toString()
                        : "";
                    info.isTest = isTest;
                    info.jndiFallback = destinationName.fallback;
                    info.originalJndi = destinationName.originalJndi;

                    acc.destinations.add(info);

                    // Track packages for config generation
                    if (isTest) {
                        if (acc.testPackage == null) {
                            acc.testPackage = info.packageName;
                        }
                    } else {
                        if (acc.mainPackage == null) {
                            acc.mainPackage = info.packageName;
                        }
                    }

                    return vd;
                }

                private boolean isDestination(JavaType type) {
                    return detectKind(type) != null;
                }

                private DestinationKind detectKind(JavaType type) {
                    if (type == null) {
                        return null;
                    }
                    if (TypeUtils.isAssignableTo(JAKARTA_QUEUE, type) ||
                        TypeUtils.isAssignableTo(JAVAX_QUEUE, type)) {
                        return DestinationKind.QUEUE;
                    }
                    if (TypeUtils.isAssignableTo(JAKARTA_TOPIC, type) ||
                        TypeUtils.isAssignableTo(JAVAX_TOPIC, type)) {
                        return DestinationKind.TOPIC;
                    }
                    if (TypeUtils.isAssignableTo(JAKARTA_DESTINATION, type) ||
                        TypeUtils.isAssignableTo(JAVAX_DESTINATION, type)) {
                        return DestinationKind.DESTINATION;
                    }
                    String typeName = type.toString();
                    if (typeName.contains("Queue")) {
                        return DestinationKind.QUEUE;
                    }
                    if (typeName.contains("Topic")) {
                        return DestinationKind.TOPIC;
                    }
                    if (typeName.contains("Destination")) {
                        return DestinationKind.DESTINATION;
                    }
                    return null;
                }

                private boolean isNeedsReview(J.Annotation ann) {
                    return TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) ||
                           "NeedsReview".equals(ann.getSimpleName());
                }
            };

            @Override
            public SourceFile visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof PlainText) {
                    PlainText text = (PlainText) tree;
                    acc.existingConfigPaths.add(text.getSourcePath());
                    return text;
                }
                if (tree instanceof SourceFile) {
                    return (SourceFile) javaVisitor.visit(tree, ctx);
                }
                return (SourceFile) tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        String queueImplClass = resolveQueueImplementation(acc.jmsProvider);
        String topicImplClass = resolveTopicImplementation(acc.jmsProvider);

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);
                JavaType type = vd.getType();
                DestinationKind kind = detectKind(type);
                if (kind == null) {
                    return vd;
                }
                if (kind == DestinationKind.DESTINATION) {
                    return addDestinationNeedsReview(vd);
                }
                boolean canRemove = (kind == DestinationKind.QUEUE && queueImplClass != null) ||
                                    (kind == DestinationKind.TOPIC && topicImplClass != null);
                if (!canRemove) {
                    return vd;
                }
                boolean removed = false;
                List<J.Annotation> newAnnotations = new ArrayList<>();
                Space preservedPrefix = null;
                for (J.Annotation ann : vd.getLeadingAnnotations()) {
                    if (isNeedsReview(ann) && extractDestinationName(ann) != null) {
                        removed = true;
                        if (preservedPrefix == null) {
                            preservedPrefix = ann.getPrefix();
                        }
                        continue;
                    }
                    if (preservedPrefix != null && newAnnotations.isEmpty()) {
                        ann = ann.withPrefix(preservedPrefix);
                    }
                    newAnnotations.add(ann);
                }
                if (removed) {
                    maybeRemoveImport(NEEDS_REVIEW_FQN);
                    return vd.withLeadingAnnotations(newAnnotations);
                }
                return vd;
            }

            private J.VariableDeclarations addDestinationNeedsReview(J.VariableDeclarations vd) {
                List<J.Annotation> annotations = vd.getLeadingAnnotations();
                boolean updated = false;
                List<J.Annotation> newAnnotations = new ArrayList<>();
                for (J.Annotation ann : annotations) {
                    if (isNeedsReview(ann)) {
                        J.Annotation updatedAnn = updateDestinationReason(ann);
                        newAnnotations.add(updatedAnn);
                        updated = updated || updatedAnn != ann;
                    } else {
                        newAnnotations.add(ann);
                    }
                }
                if (updated) {
                    return vd.withLeadingAnnotations(newAnnotations);
                }
                return vd;
            }

            private J.Annotation updateDestinationReason(J.Annotation ann) {
                if (ann.getArguments() == null) {
                    return ann;
                }
                List<Expression> args = new ArrayList<>();
                boolean changed = false;
                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                            if ("reason".equals(name)) {
                                Expression value = assignment.getAssignment();
                                if (value instanceof J.Literal) {
                                    J.Literal literal = (J.Literal) value;
                                    Object literalValue = literal.getValue();
                                    if (literalValue instanceof String) {
                                        String reason = (String) literalValue;
                                        if (!reason.contains("Destination type")) {
                                            String updatedReason = reason + " (Destination type: define Queue/Topic bean manually)";
                                            J.Literal updatedLiteral = literal.withValue(updatedReason)
                                                .withValueSource("\"" + escapeJava(updatedReason) + "\"");
                                            assignment = assignment.withAssignment(updatedLiteral);
                                            changed = true;
                                        }
                                    }
                                }
                            }
                        }
                        args.add(assignment);
                    } else {
                        args.add(arg);
                    }
                }
                if (changed) {
                    return ann.withArguments(args);
                }
                return ann;
            }

            private DestinationKind detectKind(JavaType type) {
                if (type == null) {
                    return null;
                }
                if (TypeUtils.isAssignableTo(JAKARTA_QUEUE, type) ||
                    TypeUtils.isAssignableTo(JAVAX_QUEUE, type)) {
                    return DestinationKind.QUEUE;
                }
                if (TypeUtils.isAssignableTo(JAKARTA_TOPIC, type) ||
                    TypeUtils.isAssignableTo(JAVAX_TOPIC, type)) {
                    return DestinationKind.TOPIC;
                }
                if (TypeUtils.isAssignableTo(JAKARTA_DESTINATION, type) ||
                    TypeUtils.isAssignableTo(JAVAX_DESTINATION, type)) {
                    return DestinationKind.DESTINATION;
                }
                String typeName = type.toString();
                if (typeName.contains("Queue")) {
                    return DestinationKind.QUEUE;
                }
                if (typeName.contains("Topic")) {
                    return DestinationKind.TOPIC;
                }
                if (typeName.contains("Destination")) {
                    return DestinationKind.DESTINATION;
                }
                return null;
            }

            private boolean isNeedsReview(J.Annotation ann) {
                return TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) ||
                       "NeedsReview".equals(ann.getSimpleName());
            }
        };
    }

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        if (acc.destinations.isEmpty()) {
            return Collections.emptyList();
        }

        List<SourceFile> generated = new ArrayList<>();

        Map<String, DestinationEntry> mainQueues = new LinkedHashMap<>();
        Map<String, DestinationEntry> mainTopics = new LinkedHashMap<>();
        Map<String, DestinationEntry> testQueues = new LinkedHashMap<>();
        Map<String, DestinationEntry> testTopics = new LinkedHashMap<>();
        List<DestinationEntry> mainGeneric = new ArrayList<>();
        List<DestinationEntry> testGeneric = new ArrayList<>();

        for (DestinationInfo info : acc.destinations) {
            if (info.destinationName == null || info.destinationName.isBlank()) {
                continue;
            }
            DestinationEntry entry = new DestinationEntry(info.destinationName, info.jndiFallback, info.originalJndi);
            if (info.isTest) {
                addDestination(entry, info.kind, testQueues, testTopics, testGeneric);
            } else {
                addDestination(entry, info.kind, mainQueues, mainTopics, mainGeneric);
            }
        }

        String projectRoot = determineProjectRoot(acc);
        String mainSourceRoot = acc.mainSourceRoot != null ? acc.mainSourceRoot : "src/main/java";
        String testSourceRoot = acc.testSourceRoot != null ? acc.testSourceRoot : "src/test/java";

        String queueImplClass = resolveQueueImplementation(acc.jmsProvider);
        String topicImplClass = resolveTopicImplementation(acc.jmsProvider);
        String providerLabel = resolveProviderLabel(acc.jmsProvider);

        // Generate main JmsConfiguration if needed
        if ((!mainQueues.isEmpty() || !mainTopics.isEmpty() || !mainGeneric.isEmpty()) && acc.mainPackage != null) {
            String configContent = generateJmsConfiguration(mainQueues, mainTopics, mainGeneric, acc.mainPackage, false,
                queueImplClass, topicImplClass, providerLabel);
            String packagePath = acc.mainPackage.replace('.', '/');
            Path configPath = Paths.get(projectRoot, mainSourceRoot, packagePath, "JmsConfiguration.java");
            if (!acc.existingConfigPaths.contains(configPath)) {
                generated.add(PlainText.builder()
                    .sourcePath(configPath)
                    .text(configContent)
                    .build());
            }
        }

        // Generate test JmsConfiguration if needed and different from main
        if ((!testQueues.isEmpty() || !testTopics.isEmpty() || !testGeneric.isEmpty()) && acc.testPackage != null) {
            // Only generate test config if there are test-specific queues not covered by main
            removeExisting(testQueues, mainQueues);
            removeExisting(testTopics, mainTopics);
            testGeneric.removeIf(entry -> containsDestination(mainGeneric, entry));
            if (!testQueues.isEmpty() || !testTopics.isEmpty() || !testGeneric.isEmpty()) {
                String configContent = generateJmsConfiguration(testQueues, testTopics, testGeneric, acc.testPackage, true,
                    queueImplClass, topicImplClass, providerLabel);
                String packagePath = acc.testPackage.replace('.', '/');
                Path configPath = Paths.get(projectRoot, testSourceRoot, packagePath, "JmsTestConfiguration.java");
                if (!acc.existingConfigPaths.contains(configPath)) {
                    generated.add(PlainText.builder()
                        .sourcePath(configPath)
                        .text(configContent)
                        .build());
                }
            }
        }

        return generated;
    }

    private String generateJmsConfiguration(Map<String, DestinationEntry> queueNames,
                                            Map<String, DestinationEntry> topicNames,
                                            List<DestinationEntry> genericDestinations,
                                            String packageName, boolean isTest,
                                            String queueImplClass, String topicImplClass, String providerLabel) {
        StringBuilder sb = new StringBuilder();
        String className = isTest ? "JmsTestConfiguration" : "JmsConfiguration";
        String queueImplSimple = queueImplClass != null ? simpleName(queueImplClass) : null;
        String topicImplSimple = topicImplClass != null ? simpleName(topicImplClass) : null;

        sb.append("package ").append(packageName).append(";\n\n");
        boolean hasQueues = !queueNames.isEmpty();
        boolean hasTopics = !topicNames.isEmpty();
        if (hasQueues) {
            sb.append("import jakarta.jms.Queue;\n");
        }
        if (hasTopics) {
            sb.append("import jakarta.jms.Topic;\n");
        }
        if (queueImplClass != null && hasQueues) {
            sb.append("import ").append(queueImplClass).append(";\n");
        }
        if (topicImplClass != null && hasTopics) {
            sb.append("import ").append(topicImplClass).append(";\n");
        }
        sb.append("import org.springframework.context.annotation.Bean;\n");
        sb.append("import org.springframework.context.annotation.Configuration;\n");
        if (isTest) {
            sb.append("import org.springframework.boot.test.context.TestConfiguration;\n");
        }
        sb.append("\n");
        sb.append("/**\n");
        if (!hasQueues && !hasTopics && !genericDestinations.isEmpty()) {
            sb.append(" * JMS Destination bean definitions.\n");
        } else if (hasQueues && hasTopics) {
            sb.append(" * JMS Queue/Topic bean definitions.\n");
        } else if (hasTopics) {
            sb.append(" * JMS Topic bean definitions.\n");
        } else {
            sb.append(" * JMS Queue bean definitions.\n");
        }
        sb.append(" * <p>\n");
        sb.append(" * Auto-generated during EJB-to-Spring migration.\n");
        if ((queueImplClass == null && hasQueues) || (topicImplClass == null && hasTopics)) {
            String lambdaLabel = hasQueues && !hasTopics ? "Queue" : (hasTopics && !hasQueues ? "Topic" : "JMS types");
            sb.append(" * <p>\n");
            sb.append(" * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n");
            sb.append(" * IMPORTANT: This is a TEMPLATE configuration that uses lambda-based ").append(lambdaLabel).append(".\n");
            sb.append(" * The lambda ").append(lambdaLabel).append(" implementation MAY NOT WORK with all JMS providers!\n");
            sb.append(" * !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!\n");
            sb.append(" * <p>\n");
            boolean wroteSection = false;
            if (hasQueues) {
                sb.append(" * TODO: Adjust Queue implementations for your JMS provider:\n");
                sb.append(" * - Apache Artemis: Use org.apache.activemq.artemis.jms.client.ActiveMQQueue(\"queueName\")\n");
                sb.append(" * - ActiveMQ Classic: Use org.apache.activemq.command.ActiveMQQueue(\"queueName\")\n");
                sb.append(" * - IBM MQ: Use com.ibm.mq.jms.MQQueue(\"queueName\")\n");
                wroteSection = true;
            }
            if (hasTopics) {
                if (wroteSection) {
                    sb.append(" * <p>\n");
                }
                sb.append(" * TODO: Adjust Topic implementations for your JMS provider:\n");
                sb.append(" * - Apache Artemis: Use org.apache.activemq.artemis.jms.client.ActiveMQTopic(\"topicName\")\n");
                sb.append(" * - ActiveMQ Classic: Use org.apache.activemq.command.ActiveMQTopic(\"topicName\")\n");
            }
            sb.append(" * <p>\n");
            sb.append(" * After verifying JMS configuration works, remove @NeedsReview from the\n");
            sb.append(" * Destination fields in the classes that use these destinations.\n");
        } else if (providerLabel != null) {
            sb.append(" * <p>\n");
            sb.append(" * Provider-specific JMS implementation: ").append(providerLabel).append(".\n");
        }
        if (!genericDestinations.isEmpty()) {
            sb.append(" * <p>\n");
            sb.append(" * TODO: Some Destination fields could not be classified as Queue/Topic.\n");
            sb.append(" * Verify these JNDI names and create matching beans manually.\n");
        }
        sb.append(" */\n");
        if (isTest) {
            sb.append("@TestConfiguration\n");
        } else {
            sb.append("@Configuration\n");
        }
        sb.append("public class ").append(className).append(" {\n\n");

        for (DestinationEntry queueEntry : queueNames.values()) {
            String queueName = queueEntry.name;
            String beanName = destinationNameToBeanName(queueName);
            sb.append("    /**\n");
            sb.append("     * Queue bean for '").append(queueName).append("'.\n");
            if (queueEntry.jndiFallback) {
                sb.append("     * <p>\n");
                sb.append("     * TODO: Destination name derived from JNDI fallback (original: ").append(queueEntry.originalJndi).append(").\n");
                sb.append("     * Verify the physical destination name.\n");
            }
            if (queueImplClass == null) {
                sb.append("     * <p>\n");
                sb.append("     * TODO: Replace lambda with provider-specific Queue implementation if needed.\n");
                sb.append("     * Example for Artemis: return new org.apache.activemq.artemis.jms.client.ActiveMQQueue(\"").append(queueName).append("\");\n");
            }
            sb.append("     */\n");
            sb.append("    @Bean\n");
            sb.append("    public Queue ").append(beanName).append("() {\n");
            if (queueImplClass == null) {
                sb.append("        // Lambda Queue - works with some JMS providers but may need adjustment\n");
                sb.append("        return () -> \"").append(queueName).append("\";\n");
            } else {
                sb.append("        return new ").append(queueImplSimple).append("(\"").append(queueName).append("\");\n");
            }
            sb.append("    }\n\n");
        }

        for (DestinationEntry topicEntry : topicNames.values()) {
            String topicName = topicEntry.name;
            String beanName = destinationNameToBeanName(topicName);
            sb.append("    /**\n");
            sb.append("     * Topic bean for '").append(topicName).append("'.\n");
            if (topicEntry.jndiFallback) {
                sb.append("     * <p>\n");
                sb.append("     * TODO: Destination name derived from JNDI fallback (original: ").append(topicEntry.originalJndi).append(").\n");
                sb.append("     * Verify the physical destination name.\n");
            }
            if (topicImplClass == null) {
                sb.append("     * <p>\n");
                sb.append("     * TODO: Replace lambda with provider-specific Topic implementation if needed.\n");
                sb.append("     * Example for Artemis: return new org.apache.activemq.artemis.jms.client.ActiveMQTopic(\"").append(topicName).append("\");\n");
            }
            sb.append("     */\n");
            sb.append("    @Bean\n");
            sb.append("    public Topic ").append(beanName).append("() {\n");
            if (topicImplClass == null) {
                sb.append("        // Lambda Topic - works with some JMS providers but may need adjustment\n");
                sb.append("        return () -> \"").append(topicName).append("\";\n");
            } else {
                sb.append("        return new ").append(topicImplSimple).append("(\"").append(topicName).append("\");\n");
            }
            sb.append("    }\n\n");
        }

        sb.append("}\n");

        return sb.toString();
    }

    private String destinationNameToBeanName(String destinationName) {
        if (destinationName == null || destinationName.isEmpty()) {
            return destinationName;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < destinationName.length(); i++) {
            char ch = destinationName.charAt(i);
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                sb.append(ch);
            } else {
                sb.append('_');
            }
        }
        if (sb.length() == 0) {
            return destinationName;
        }
        if (!Character.isJavaIdentifierStart(sb.charAt(0))) {
            sb.insert(0, '_');
        }
        sb.setCharAt(0, Character.toLowerCase(sb.charAt(0)));
        return sb.toString();
    }

    private void addDestination(DestinationEntry entry, DestinationKind kind,
                                Map<String, DestinationEntry> queues,
                                Map<String, DestinationEntry> topics,
                                List<DestinationEntry> generic) {
        if (kind == null) {
            return;
        }
        switch (kind) {
            case QUEUE:
                queues.putIfAbsent(entry.name, entry);
                break;
            case TOPIC:
                topics.putIfAbsent(entry.name, entry);
                break;
            case DESTINATION:
                generic.add(entry);
                break;
            default:
                break;
        }
    }

    private void removeExisting(Map<String, DestinationEntry> target, Map<String, DestinationEntry> existing) {
        for (String name : new ArrayList<>(target.keySet())) {
            if (existing.containsKey(name)) {
                target.remove(name);
            }
        }
    }

    private boolean containsDestination(List<DestinationEntry> destinations, DestinationEntry entry) {
        for (DestinationEntry existing : destinations) {
            if (existing.name.equals(entry.name)) {
                return true;
            }
        }
        return false;
    }

    private String determineProjectRoot(Accumulator acc) {
        if (!acc.detectedSourceRoots.isEmpty()) {
            return acc.detectedSourceRoots.iterator().next();
        }
        return "";
    }

    private static String resolveQueueImplementation(ProjectConfiguration.JmsProvider provider) {
        if (provider == null || provider == ProjectConfiguration.JmsProvider.NONE) {
            return null;
        }
        switch (provider) {
            case ARTEMIS:
                return "org.apache.activemq.artemis.jms.client.ActiveMQQueue";
            case ACTIVEMQ:
                return "org.apache.activemq.command.ActiveMQQueue";
            case EMBEDDED:
            default:
                return null;
        }
    }

    private static String resolveTopicImplementation(ProjectConfiguration.JmsProvider provider) {
        if (provider == null || provider == ProjectConfiguration.JmsProvider.NONE) {
            return null;
        }
        switch (provider) {
            case ARTEMIS:
                return "org.apache.activemq.artemis.jms.client.ActiveMQTopic";
            case ACTIVEMQ:
                return "org.apache.activemq.command.ActiveMQTopic";
            case EMBEDDED:
            default:
                return null;
        }
    }

    private static String resolveProviderLabel(ProjectConfiguration.JmsProvider provider) {
        if (provider == null || provider == ProjectConfiguration.JmsProvider.NONE) {
            return null;
        }
        return provider.name().toLowerCase(Locale.ROOT);
    }

    private static String simpleName(String fqn) {
        if (fqn == null) {
            return null;
        }
        int lastDot = fqn.lastIndexOf('.');
        return lastDot >= 0 ? fqn.substring(lastDot + 1) : fqn;
    }

    private static DestinationName extractDestinationName(J.Annotation ann) {
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
                                return parseDestinationName(code);
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    private static DestinationName parseDestinationName(String originalCode) {
        if (originalCode == null) {
            return null;
        }
        String trimmed = originalCode.trim();
        String jndiValue = extractLookupValue(trimmed);
        if (jndiValue == null || jndiValue.isBlank()) {
            jndiValue = trimmed;
        }
        Matcher matcher = JNDI_JMS_SEGMENT_PATTERN.matcher(jndiValue);
        if (matcher.find()) {
            return new DestinationName(matcher.group(1), false, jndiValue);
        }
        String lastSegment = extractLastSegment(jndiValue);
        if (lastSegment != null) {
            return new DestinationName(lastSegment, true, jndiValue);
        }
        return null;
    }

    private static String extractLookupValue(String originalCode) {
        Matcher matcher = JNDI_LOOKUP_PATTERN.matcher(originalCode);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String extractLastSegment(String jndiValue) {
        if (jndiValue == null) {
            return null;
        }
        String normalized = jndiValue.trim();
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0 && lastSlash + 1 < normalized.length()) {
            return normalized.substring(lastSlash + 1);
        }
        int lastColon = normalized.lastIndexOf(':');
        if (lastColon >= 0 && lastColon + 1 < normalized.length()) {
            return normalized.substring(lastColon + 1);
        }
        if (!normalized.isBlank()) {
            return normalized;
        }
        return null;
    }

    static class DestinationName {
        final String name;
        final boolean fallback;
        final String originalJndi;

        DestinationName(String name, boolean fallback, String originalJndi) {
            this.name = name;
            this.fallback = fallback;
            this.originalJndi = originalJndi;
        }
    }

    private static String escapeJava(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\\\"");
    }

    /**
     * Extracts the project root directory from the given source path.
     */
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
