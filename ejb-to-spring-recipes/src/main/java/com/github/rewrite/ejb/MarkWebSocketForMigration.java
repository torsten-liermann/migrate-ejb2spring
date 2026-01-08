package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Marks Jakarta WebSocket classes for manual migration to Spring WebSocket.
 * <p>
 * Jakarta WebSocket (JSR-356) annotations like @ServerEndpoint, @OnOpen, @OnClose,
 * and @OnMessage require manual migration to Spring's WebSocket support.
 * <p>
 * Spring provides two approaches:
 * <ul>
 *   <li>Spring WebSocket with STOMP messaging</li>
 *   <li>Spring WebSocket with raw WebSocket handlers</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MarkWebSocketForMigration extends Recipe {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    // Jakarta WebSocket types to detect
    private static final String SERVER_ENDPOINT = "jakarta.websocket.server.ServerEndpoint";
    private static final String CLIENT_ENDPOINT = "jakarta.websocket.ClientEndpoint";
    private static final String ON_OPEN = "jakarta.websocket.OnOpen";
    private static final String ON_CLOSE = "jakarta.websocket.OnClose";
    private static final String ON_MESSAGE = "jakarta.websocket.OnMessage";
    private static final String ON_ERROR = "jakarta.websocket.OnError";
    private static final String SESSION = "jakarta.websocket.Session";
    private static final String ENDPOINT = "jakarta.websocket.Endpoint";
    private static final String ENDPOINT_CONFIG = "jakarta.websocket.EndpointConfig";
    private static final String SERVER_APP_CONFIG = "jakarta.websocket.server.ServerApplicationConfig";
    private static final String REMOTE_ENDPOINT = "jakarta.websocket.RemoteEndpoint";

    // javax namespace variants (legacy)
    private static final String SERVER_ENDPOINT_JAVAX = "javax.websocket.server.ServerEndpoint";
    private static final String CLIENT_ENDPOINT_JAVAX = "javax.websocket.ClientEndpoint";
    private static final String SESSION_JAVAX = "javax.websocket.Session";
    private static final String ENDPOINT_JAVAX = "javax.websocket.Endpoint";

    @Override
    public String getDisplayName() {
        return "Mark Jakarta WebSocket classes for manual migration";
    }

    @Override
    public String getDescription() {
        return "Adds @NeedsReview annotation to classes using Jakarta WebSocket APIs, indicating they need manual migration to Spring WebSocket.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        // Note: No Preconditions.check() with UsesType because it requires type resolution.
        // The visitor itself detects WebSocket patterns via annotations and extends clauses.
        return new WebSocketVisitor();
    }

    private class WebSocketVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Check if class already has @NeedsReview with MANUAL_MIGRATION
            boolean hasNeedsReview = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> a.getSimpleName().equals("NeedsReview"));

            if (hasNeedsReview) {
                return cd;
            }

            // Detect WebSocket annotations
            String wsAnnotation = detectWebSocketAnnotation(cd);
            if (wsAnnotation == null) {
                return cd;
            }

            // Add @NeedsReview annotation
            maybeAddImport(NEEDS_REVIEW_FQN);
            // Static import for enum values
            doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN + ".Category", "MANUAL_MIGRATION", false));

            Space prefix = cd.getLeadingAnnotations().isEmpty()
                ? cd.getPrefix()
                : cd.getLeadingAnnotations().get(0).getPrefix();

            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                "Jakarta WebSocket class needs migration to Spring WebSocket",
                "MANUAL_MIGRATION",
                wsAnnotation,
                "See docs/migration-guide/websocket-migration.md for Spring WebSocket configuration",
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

        private String detectWebSocketAnnotation(J.ClassDeclaration classDecl) {
            List<String> wsFeatures = new ArrayList<>();

            // Check class-level annotations
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), SERVER_ENDPOINT) ||
                    TypeUtils.isOfClassType(ann.getType(), SERVER_ENDPOINT_JAVAX)) {
                    String path = extractAnnotationStringValue(ann, "value");
                    wsFeatures.add("@ServerEndpoint" + (path != null ? "(" + path + ")" : ""));
                }
                if (TypeUtils.isOfClassType(ann.getType(), CLIENT_ENDPOINT) ||
                    TypeUtils.isOfClassType(ann.getType(), CLIENT_ENDPOINT_JAVAX)) {
                    wsFeatures.add("@ClientEndpoint");
                }
            }

            // Check extends clause for Endpoint class
            if (classDecl.getExtends() != null) {
                JavaType.FullyQualified extendsType = TypeUtils.asFullyQualified(classDecl.getExtends().getType());
                if (extendsType != null) {
                    String fqn = extendsType.getFullyQualifiedName();
                    if (ENDPOINT.equals(fqn) || ENDPOINT_JAVAX.equals(fqn)) {
                        wsFeatures.add("extends Endpoint");
                    }
                }
            }

            // Check implements clause for ServerApplicationConfig
            if (classDecl.getImplements() != null) {
                for (TypeTree impl : classDecl.getImplements()) {
                    JavaType.FullyQualified implType = TypeUtils.asFullyQualified(impl.getType());
                    if (implType != null && SERVER_APP_CONFIG.equals(implType.getFullyQualifiedName())) {
                        wsFeatures.add("implements ServerApplicationConfig");
                    }
                }
            }

            // Check method annotations
            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                        for (J.Annotation ann : method.getLeadingAnnotations()) {
                            if (TypeUtils.isOfClassType(ann.getType(), ON_OPEN)) {
                                wsFeatures.add("@OnOpen");
                            }
                            if (TypeUtils.isOfClassType(ann.getType(), ON_CLOSE)) {
                                wsFeatures.add("@OnClose");
                            }
                            if (TypeUtils.isOfClassType(ann.getType(), ON_MESSAGE)) {
                                wsFeatures.add("@OnMessage");
                            }
                            if (TypeUtils.isOfClassType(ann.getType(), ON_ERROR)) {
                                wsFeatures.add("@OnError");
                            }
                        }
                    }
                    // Check for Session field
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        JavaType varType = vd.getType();
                        if (varType instanceof JavaType.FullyQualified) {
                            String fqn = ((JavaType.FullyQualified) varType).getFullyQualifiedName();
                            if (SESSION.equals(fqn) || SESSION_JAVAX.equals(fqn)) {
                                wsFeatures.add("uses Session");
                            }
                        }
                    }
                }
            }

            // String-based fallback detection (when types aren't resolved)
            if (wsFeatures.isEmpty()) {
                // Check class-level annotations by simple name
                for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                    String annName = ann.getSimpleName();
                    if ("ServerEndpoint".equals(annName)) {
                        wsFeatures.add("@ServerEndpoint");
                    } else if ("ClientEndpoint".equals(annName)) {
                        wsFeatures.add("@ClientEndpoint");
                    }
                }

                // Check extends clause by simple name
                if (classDecl.getExtends() != null) {
                    String extendsName = classDecl.getExtends().toString();
                    if (extendsName.contains("Endpoint") && !extendsName.contains("ServerEndpoint") && !extendsName.contains("ClientEndpoint")) {
                        wsFeatures.add("extends Endpoint");
                    }
                }

                // Check implements clause by simple name
                if (classDecl.getImplements() != null) {
                    for (TypeTree impl : classDecl.getImplements()) {
                        String implName = impl.toString();
                        if (implName.contains("ServerApplicationConfig")) {
                            wsFeatures.add("implements ServerApplicationConfig");
                        }
                    }
                }

                // Check method annotations by simple name
                if (classDecl.getBody() != null) {
                    for (Statement stmt : classDecl.getBody().getStatements()) {
                        if (stmt instanceof J.MethodDeclaration) {
                            J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                            for (J.Annotation ann : method.getLeadingAnnotations()) {
                                String annName = ann.getSimpleName();
                                if ("OnOpen".equals(annName)) {
                                    wsFeatures.add("@OnOpen");
                                } else if ("OnClose".equals(annName)) {
                                    wsFeatures.add("@OnClose");
                                } else if ("OnMessage".equals(annName)) {
                                    wsFeatures.add("@OnMessage");
                                } else if ("OnError".equals(annName)) {
                                    wsFeatures.add("@OnError");
                                }
                            }
                        }
                    }
                }
            }

            // No WebSocket features detected - return null to skip marking
            if (wsFeatures.isEmpty()) {
                return null;
            }

            return String.join(", ", wsFeatures);
        }

        private String extractAnnotationStringValue(J.Annotation annotation, String attrName) {
            List<Expression> args = annotation.getArguments();
            if (args == null || args.isEmpty()) {
                return null;
            }

            for (Expression arg : args) {
                // Direct string value (single argument)
                if (arg instanceof J.Literal) {
                    Object value = ((J.Literal) arg).getValue();
                    return value != null ? value.toString() : null;
                }
                // Named argument
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                        if (attrName.equals(name)) {
                            Expression value = assignment.getAssignment();
                            if (value instanceof J.Literal) {
                                Object literalValue = ((J.Literal) value).getValue();
                                return literalValue != null ? literalValue.toString() : null;
                            }
                        }
                    }
                }
            }

            return null;
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

            // Create MANUAL_MIGRATION identifier (works with static import of NeedsReview.Category.MANUAL_MIGRATION)
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
