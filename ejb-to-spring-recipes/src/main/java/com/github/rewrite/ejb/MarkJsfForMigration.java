package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Marks JSF (Jakarta Server Faces) classes for migration based on configured strategy.
 * <p>
 * Strategy configuration via project.yaml:
 * <pre>
 * migration:
 *   jsf:
 *     runtime: joinfaces | manual
 * </pre>
 * <p>
 * Behavior by strategy:
 * <ul>
 *   <li><b>joinfaces (default)</b> - JSF scopes (@ViewScoped, etc.) remain unchanged;
 *       JoinFaces provides the runtime. Classes are marked with CONFIGURATION category
 *       for file structure verification.</li>
 *   <li><b>manual</b> - JSF scope usage is marked with MANUAL_MIGRATION category
 *       for manual migration to Spring scopes.</li>
 * </ul>
 * <p>
 * Required file moves (handled separately):
 * <ul>
 *   <li>faces-config.xml → src/main/resources/META-INF/</li>
 *   <li>*.xhtml → src/main/resources/META-INF/resources/</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MarkJsfForMigration extends ScanningRecipe<MarkJsfForMigration.Accumulator> {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    // JSF types to detect (jakarta namespace)
    private static final String FACES_CONTEXT = "jakarta.faces.context.FacesContext";
    private static final String FACES_MESSAGE = "jakarta.faces.application.FacesMessage";
    private static final String UI_COMPONENT = "jakarta.faces.component.UIComponent";
    private static final String UI_VIEW_ROOT = "jakarta.faces.component.UIViewRoot";
    private static final String MANAGED_BEAN = "jakarta.faces.bean.ManagedBean";
    private static final String CONVERSATION_SCOPED = "jakarta.enterprise.context.ConversationScoped";
    private static final String CONVERSATION = "jakarta.enterprise.context.Conversation";
    private static final String VIEW_SCOPED = "jakarta.faces.view.ViewScoped";
    private static final String FLOW_SCOPED = "jakarta.faces.flow.FlowScoped";
    private static final String EXTERNAL_CONTEXT = "jakarta.faces.context.ExternalContext";
    private static final String NAVIGATION_HANDLER = "jakarta.faces.application.NavigationHandler";

    // javax namespace variants (for legacy code)
    private static final String FACES_CONTEXT_JAVAX = "javax.faces.context.FacesContext";
    private static final String FACES_MESSAGE_JAVAX = "javax.faces.application.FacesMessage";
    private static final String MANAGED_BEAN_JAVAX = "javax.faces.bean.ManagedBean";
    private static final String UI_COMPONENT_JAVAX = "javax.faces.component.UIComponent";
    private static final String CONVERSATION_SCOPED_JAVAX = "javax.enterprise.context.ConversationScoped";
    private static final String CONVERSATION_JAVAX = "javax.enterprise.context.Conversation";

    @Override
    public String getDisplayName() {
        return "Mark JSF classes for migration";
    }

    @Override
    public String getDescription() {
        return "Marks JSF backing beans with @NeedsReview based on configured strategy. " +
               "With 'joinfaces' strategy (default), marks for file structure verification. " +
               "With 'manual' strategy, marks for manual Spring scope migration.";
    }

    /**
     * Accumulator to hold the loaded JSF strategy configuration.
     */
    public static class Accumulator {
        private ProjectConfiguration.JsfStrategy jsfStrategy = ProjectConfiguration.JsfStrategy.JOINFACES;

        public ProjectConfiguration.JsfStrategy getJsfStrategy() {
            return jsfStrategy;
        }

        public void setJsfStrategy(ProjectConfiguration.JsfStrategy jsfStrategy) {
            this.jsfStrategy = jsfStrategy;
        }

        public boolean isJoinFacesStrategy() {
            return jsfStrategy == ProjectConfiguration.JsfStrategy.JOINFACES;
        }

        public boolean isManualStrategy() {
            return jsfStrategy == ProjectConfiguration.JsfStrategy.MANUAL;
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
                    if (sourcePath != null) {
                        // Navigate to project root (go up from source file)
                        Path projectRoot = sourcePath.toAbsolutePath().getParent();
                        while (projectRoot != null && !java.nio.file.Files.exists(projectRoot.resolve("pom.xml"))
                                && !java.nio.file.Files.exists(projectRoot.resolve("build.gradle"))
                                && !java.nio.file.Files.exists(projectRoot.resolve("project.yaml"))) {
                            projectRoot = projectRoot.getParent();
                        }
                        if (projectRoot != null) {
                            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(projectRoot);
                            acc.setJsfStrategy(config.getJsfStrategy());
                        }
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new JsfVisitor(acc);
    }

    private class JsfVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final Accumulator accumulator;

        JsfVisitor(Accumulator accumulator) {
            this.accumulator = accumulator;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Check if class already has @NeedsReview
            boolean hasNeedsReview = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> a.getSimpleName().equals("NeedsReview"));

            if (hasNeedsReview) {
                return cd;
            }

            // First check for @ConversationScoped - this has special handling (SEMANTIC_CHANGE)
            ConversationScopedInfo conversationInfo = detectConversationScoped(cd);
            if (conversationInfo.isConversationScoped) {
                return markConversationScoped(cd, conversationInfo);
            }

            // Detect other JSF usage
            String jsfUsage = detectJsfUsage(cd);
            if (jsfUsage == null) {
                return cd;
            }

            // Determine category and messages based on strategy
            String category;
            String reason;
            String suggestedAction;

            if (accumulator.isJoinFacesStrategy()) {
                // JoinFaces strategy: JSF scopes stay, only file moves needed
                category = "CONFIGURATION";
                reason = "JSF backing bean - verify JoinFaces file structure";
                suggestedAction = "Move: faces-config.xml -> META-INF/, *.xhtml -> META-INF/resources/. JSF code unchanged.";
            } else {
                // Manual strategy: JSF scopes need manual migration to Spring
                category = "MANUAL_MIGRATION";
                reason = "JSF backing bean - manual migration to Spring scopes required";
                suggestedAction = "Migrate JSF scopes to Spring: @ViewScoped -> @Scope(\"view\") or custom, @FlowScoped -> Spring WebFlow. Remove JSF dependencies.";
            }

            // Add @NeedsReview annotation
            maybeAddImport(NEEDS_REVIEW_FQN);
            // Static import for enum values
            doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN + ".Category", category, false));

            Space prefix = cd.getLeadingAnnotations().isEmpty()
                ? cd.getPrefix()
                : cd.getLeadingAnnotations().get(0).getPrefix();

            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                reason,
                category,
                jsfUsage,
                suggestedAction,
                prefix
            );

            List<J.Annotation> newAnnotations = new ArrayList<>();
            newAnnotations.add(needsReviewAnn);

            for (int i = 0; i < cd.getLeadingAnnotations().size(); i++) {
                J.Annotation ann = cd.getLeadingAnnotations().get(i);
                if (i == 0) {
                    String ws = prefix.getWhitespace();
                    ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n"));
                }
                newAnnotations.add(ann);
            }

            return cd.withLeadingAnnotations(newAnnotations);
        }

        /**
         * Information about ConversationScoped usage in a class.
         */
        private static class ConversationScopedInfo {
            boolean isConversationScoped = false;
            boolean hasConversationInjection = false;
            boolean hasBeginCall = false;
            boolean hasEndCall = false;
        }

        /**
         * Detects if the class uses @ConversationScoped and checks for explicit
         * Conversation lifecycle management (begin/end calls).
         */
        private ConversationScopedInfo detectConversationScoped(J.ClassDeclaration classDecl) {
            ConversationScopedInfo info = new ConversationScopedInfo();

            // Check class-level annotations for @ConversationScoped
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                String annName = ann.getSimpleName();
                if ("ConversationScoped".equals(annName)) {
                    info.isConversationScoped = true;
                    break;
                }
                // Also check via type if available
                if (TypeUtils.isOfClassType(ann.getType(), CONVERSATION_SCOPED) ||
                    TypeUtils.isOfClassType(ann.getType(), CONVERSATION_SCOPED_JAVAX)) {
                    info.isConversationScoped = true;
                    break;
                }
            }

            // Also check imports for ConversationScoped
            if (!info.isConversationScoped) {
                J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                if (cu != null) {
                    for (J.Import imp : cu.getImports()) {
                        String importPath = imp.getQualid().toString();
                        if (importPath.equals(CONVERSATION_SCOPED) || importPath.equals(CONVERSATION_SCOPED_JAVAX)) {
                            // Import present - check if annotation is used on class
                            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                                if ("ConversationScoped".equals(ann.getSimpleName())) {
                                    info.isConversationScoped = true;
                                    break;
                                }
                            }
                        }
                    }
                }
            }

            if (!info.isConversationScoped) {
                return info;
            }

            // Check for Conversation field injection and method calls
            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    // Check field declarations for Conversation type
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        JavaType varType = vd.getType();
                        if (varType instanceof JavaType.FullyQualified) {
                            String fqn = ((JavaType.FullyQualified) varType).getFullyQualifiedName();
                            if (CONVERSATION.equals(fqn) || CONVERSATION_JAVAX.equals(fqn)) {
                                info.hasConversationInjection = true;
                            }
                        }
                        // Fallback: check type expression string
                        if (vd.getTypeExpression() != null) {
                            String typeStr = vd.getTypeExpression().toString();
                            if ("Conversation".equals(typeStr)) {
                                info.hasConversationInjection = true;
                            }
                        }
                    }

                    // Check method bodies for conversation.begin() and conversation.end()
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                        if (method.getBody() != null) {
                            // Use a simple visitor to find method invocations
                            new JavaIsoVisitor<ConversationScopedInfo>() {
                                @Override
                                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation methodInvocation, ConversationScopedInfo innerInfo) {
                                    String methodName = methodInvocation.getSimpleName();
                                    if ("begin".equals(methodName)) {
                                        // Check if called on Conversation type
                                        if (isConversationMethod(methodInvocation)) {
                                            innerInfo.hasBeginCall = true;
                                        }
                                    } else if ("end".equals(methodName)) {
                                        if (isConversationMethod(methodInvocation)) {
                                            innerInfo.hasEndCall = true;
                                        }
                                    }
                                    return super.visitMethodInvocation(methodInvocation, innerInfo);
                                }

                                private boolean isConversationMethod(J.MethodInvocation mi) {
                                    // Check method type if available
                                    JavaType.Method methodType = mi.getMethodType();
                                    if (methodType != null && methodType.getDeclaringType() != null) {
                                        String declaringType = methodType.getDeclaringType().getFullyQualifiedName();
                                        return CONVERSATION.equals(declaringType) || CONVERSATION_JAVAX.equals(declaringType);
                                    }
                                    // Fallback: check if select expression looks like a conversation variable
                                    if (mi.getSelect() != null) {
                                        String selectStr = mi.getSelect().toString();
                                        // Common variable names for Conversation
                                        return selectStr.equals("conversation") ||
                                               selectStr.equals("conv") ||
                                               selectStr.contains("conversation") ||
                                               selectStr.contains("Conversation");
                                    }
                                    return false;
                                }
                            }.visit(method.getBody(), info);
                        }
                    }
                }
            }

            return info;
        }

        /**
         * Marks a ConversationScoped class with @NeedsReview(category=SEMANTIC_CHANGE).
         */
        private J.ClassDeclaration markConversationScoped(J.ClassDeclaration cd, ConversationScopedInfo info) {
            maybeAddImport(NEEDS_REVIEW_FQN);
            doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN + ".Category", "SEMANTIC_CHANGE", false));

            // Build reason message
            StringBuilder reason = new StringBuilder("ConversationScoped is mapped to session scope");
            if (info.hasBeginCall || info.hasEndCall) {
                reason.append(" - explicit begin()/end() calls detected");
            }

            // Build originalCode
            List<String> originalCodeParts = new ArrayList<>();
            originalCodeParts.add("@ConversationScoped");
            if (info.hasConversationInjection) {
                originalCodeParts.add("Conversation injection");
            }
            if (info.hasBeginCall) {
                originalCodeParts.add("Conversation.begin()");
            }
            if (info.hasEndCall) {
                originalCodeParts.add("Conversation.end()");
            }
            String originalCode = String.join(", ", originalCodeParts);

            // Build suggestedAction
            String suggestedAction;
            if (info.hasBeginCall || info.hasEndCall) {
                suggestedAction = "Manual review required: implement conversation boundaries in Spring or use session scope. begin()/end() must be handled manually.";
            } else {
                suggestedAction = "ConversationScoped is mapped to session scope; validate conversation boundaries. Alternative: @ViewScoped if a shorter lifecycle is sufficient.";
            }

            Space prefix = cd.getLeadingAnnotations().isEmpty()
                ? cd.getPrefix()
                : cd.getLeadingAnnotations().get(0).getPrefix();

            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                reason.toString(),
                "SEMANTIC_CHANGE",
                originalCode,
                suggestedAction,
                prefix
            );

            List<J.Annotation> newAnnotations = new ArrayList<>();
            newAnnotations.add(needsReviewAnn);

            for (int i = 0; i < cd.getLeadingAnnotations().size(); i++) {
                J.Annotation ann = cd.getLeadingAnnotations().get(i);
                if (i == 0) {
                    String ws = prefix.getWhitespace();
                    ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n"));
                }
                newAnnotations.add(ann);
            }

            return cd.withLeadingAnnotations(newAnnotations);
        }

        private String detectJsfUsage(J.ClassDeclaration classDecl) {
            List<String> jsfFeatures = new ArrayList<>();

            // First: Check imports for jakarta.faces.* or javax.faces.* (most robust detection)
            J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
            if (cu != null) {
                for (J.Import imp : cu.getImports()) {
                    String importPath = imp.getQualid().toString();
                    if (importPath.startsWith("jakarta.faces.") || importPath.startsWith("javax.faces.")) {
                        // Detect specific JSF APIs from import
                        if (importPath.contains("FacesContext")) {
                            jsfFeatures.add("FacesContext");
                        } else if (importPath.contains("FacesMessage")) {
                            jsfFeatures.add("FacesMessage");
                        } else if (importPath.contains("UIComponent")) {
                            jsfFeatures.add("UIComponent");
                        } else if (importPath.contains("UIViewRoot")) {
                            jsfFeatures.add("UIViewRoot");
                        } else if (importPath.contains("ExternalContext")) {
                            jsfFeatures.add("ExternalContext");
                        } else if (importPath.contains("NavigationHandler")) {
                            jsfFeatures.add("NavigationHandler");
                        } else if (importPath.contains("ManagedBean")) {
                            jsfFeatures.add("@ManagedBean");
                        } else if (importPath.contains("ViewScoped")) {
                            jsfFeatures.add("@ViewScoped");
                        } else if (importPath.contains("FlowScoped")) {
                            jsfFeatures.add("@FlowScoped");
                        } else if (!jsfFeatures.contains("uses JSF API")) {
                            // Generic fallback for any jakarta.faces import
                            jsfFeatures.add("uses JSF API");
                        }
                    }
                    // Note: ConversationScoped is handled separately with SEMANTIC_CHANGE category
                    // Skip adding it as a general JSF feature here
                }
            }

            // If imports detected something, return early (most reliable detection)
            if (!jsfFeatures.isEmpty()) {
                return "Uses: " + String.join(", ", jsfFeatures);
            }

            // Check class-level annotations
            // Note: ConversationScoped is handled separately with SEMANTIC_CHANGE
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), MANAGED_BEAN) ||
                    TypeUtils.isOfClassType(ann.getType(), MANAGED_BEAN_JAVAX)) {
                    jsfFeatures.add("@ManagedBean");
                }
                if (TypeUtils.isOfClassType(ann.getType(), VIEW_SCOPED)) {
                    jsfFeatures.add("@ViewScoped");
                }
                if (TypeUtils.isOfClassType(ann.getType(), FLOW_SCOPED)) {
                    jsfFeatures.add("@FlowScoped");
                }
            }

            // Check field types for JSF dependencies
            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        JavaType varType = vd.getType();
                        if (varType instanceof JavaType.FullyQualified) {
                            String fqn = ((JavaType.FullyQualified) varType).getFullyQualifiedName();
                            if (FACES_CONTEXT.equals(fqn) || FACES_CONTEXT_JAVAX.equals(fqn)) {
                                jsfFeatures.add("FacesContext");
                            } else if (FACES_MESSAGE.equals(fqn) || FACES_MESSAGE_JAVAX.equals(fqn)) {
                                jsfFeatures.add("FacesMessage");
                            } else if (UI_COMPONENT.equals(fqn) || UI_COMPONENT_JAVAX.equals(fqn)) {
                                jsfFeatures.add("UIComponent");
                            } else if (UI_VIEW_ROOT.equals(fqn)) {
                                jsfFeatures.add("UIViewRoot");
                            } else if (EXTERNAL_CONTEXT.equals(fqn)) {
                                jsfFeatures.add("ExternalContext");
                            } else if (CONVERSATION.equals(fqn)) {
                                jsfFeatures.add("Conversation");
                            } else if (NAVIGATION_HANDLER.equals(fqn)) {
                                jsfFeatures.add("NavigationHandler");
                            }
                        }
                    }
                }
            }

            // String-based fallback detection (when types aren't resolved)
            // Note: ConversationScoped is handled separately with SEMANTIC_CHANGE
            if (jsfFeatures.isEmpty()) {
                // Check class-level annotations by simple name
                for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                    String annName = ann.getSimpleName();
                    if ("ManagedBean".equals(annName)) {
                        jsfFeatures.add("@ManagedBean");
                    } else if ("ViewScoped".equals(annName)) {
                        jsfFeatures.add("@ViewScoped");
                    } else if ("FlowScoped".equals(annName)) {
                        jsfFeatures.add("@FlowScoped");
                    }
                }
            }

            // No JSF features detected - return null to skip marking
            if (jsfFeatures.isEmpty()) {
                return null;
            }

            return "Uses: " + String.join(", ", jsfFeatures);
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
            if (value == null) return "";
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }

        private JRightPadded<Expression> createAssignmentArg(String key, String value, boolean leadingSpace) {
            J.Identifier keyIdent = new J.Identifier(
                Tree.randomId(),
                leadingSpace ? Space.format(" ") : Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                key,
                null,
                null
            );

            String escapedValue = escapeJavaString(value);
            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                value,
                "\"" + escapedValue + "\"",
                Collections.emptyList(),
                JavaType.Primitive.String
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

            // Create category identifier (works with static import of NeedsReview.Category.*)
            J.Identifier valueExpr = new J.Identifier(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                Collections.emptyList(),
                categoryName,
                categoryType,
                null
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
}
