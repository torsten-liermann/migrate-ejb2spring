package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

/**
 * Adds @NeedsReview annotation to fields of type JMSContext.
 * For local variables (which cannot have annotations), adds a comment instead.
 * <p>
 * JMSContext is a CDI-managed bean that doesn't have a direct Spring equivalent.
 * In Spring, you should use either:
 * <ul>
 *   <li>JmsTemplate for simplified messaging operations</li>
 *   <li>ConnectionFactory.createContext() for JMS 2.0 API compatibility</li>
 * </ul>
 * <p>
 * This recipe marks JMSContext usages for manual review since automatic migration
 * would require understanding the specific messaging patterns used.
 * <p>
 * WFQ-005: For local variables, uses comment-based fallback since Java annotations
 * cannot be placed on local variables without {@code @Target(ElementType.LOCAL_VARIABLE)}.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MarkJmsContextForReview extends Recipe {

    private static final String JMS_CONTEXT_FQN = "jakarta.jms.JMSContext";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    // WFQ-005: Comment format for local variables
    private static final String LOCAL_VAR_COMMENT =
        " NeedsReview(category=MESSAGING, reason=\"JMSContext is CDI-specific and has no direct Spring equivalent. " +
        "Replace with JmsTemplate (recommended) or ConnectionFactory.createContext()\") ";

    @Override
    public String getDisplayName() {
        return "Mark JMSContext usages for manual review";
    }

    @Override
    public String getDescription() {
        return "Marks JMSContext usages for manual review. For fields, adds @NeedsReview annotation. " +
               "For local variables, adds a comment (WFQ-005: annotations cannot be placed on local variables). " +
               "JMSContext is CDI-specific and needs to be replaced with Spring JMS (JmsTemplate or ConnectionFactory).";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            new UsesType<>(JMS_CONTEXT_FQN, false),
            new JavaIsoVisitor<ExecutionContext>() {

                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
                    J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);

                    // Check if this is a JMSContext variable
                    if (!isJmsContextType(vd)) {
                        return vd;
                    }

                    // WFQ-005: Determine variable context by checking direct parent
                    // - ClassDeclaration parent -> field -> @NeedsReview annotation
                    // - MethodDeclaration parent -> parameter -> @NeedsReview annotation
                    // - Block/other parent -> local variable -> comment fallback
                    boolean useAnnotation = isFieldOrParameter();

                    if (useAnnotation) {
                        // Field or method parameter - use annotation
                        return addNeedsReviewAnnotation(vd);
                    } else {
                        // WFQ-005: Local variables (in method body, initializer blocks) cannot have annotations
                        return addNeedsReviewComment(vd);
                    }
                }

                /**
                 * WFQ-005: Determines if this variable declaration is a field or method parameter.
                 * Fields and parameters can have annotations, local variables cannot.
                 * <p>
                 * AST structure in OpenRewrite:
                 * - Field: VariableDeclarations -> Block (class body) -> ClassDeclaration
                 * - Method local: VariableDeclarations -> Block (method body) -> MethodDeclaration
                 * - Initializer local: VariableDeclarations -> Block (init block) -> Block (class body) -> ClassDeclaration
                 * - Method parameter: VariableDeclarations -> MethodDeclaration (directly)
                 */
                private boolean isFieldOrParameter() {
                    Cursor parentCursor = getCursor().getParentTreeCursor();
                    if (parentCursor == null) {
                        return false;
                    }
                    Object parent = parentCursor.getValue();

                    // Method parameter: direct child of MethodDeclaration (not in a block)
                    if (parent instanceof J.MethodDeclaration) {
                        return true;
                    }

                    // Field vs local: both are in a Block, but check grandparent
                    if (parent instanceof J.Block) {
                        Cursor grandparentCursor = parentCursor.getParentTreeCursor();
                        if (grandparentCursor != null) {
                            Object grandparent = grandparentCursor.getValue();
                            // Field: parent=Block (class body), grandparent=ClassDeclaration
                            if (grandparent instanceof J.ClassDeclaration) {
                                return true;
                            }
                            // Method local: parent=Block (method body), grandparent=MethodDeclaration
                            // Initializer local: parent=Block (init block), grandparent=Block (class body)
                            // Both are local variables -> return false
                        }
                    }

                    // All other cases are local variables (inside blocks)
                    return false;
                }

                /**
                 * WFQ-005: Adds a NeedsReview comment to a local variable declaration.
                 * Java annotations cannot be placed on local variables without @Target(ElementType.LOCAL_VARIABLE).
                 */
                private J.VariableDeclarations addNeedsReviewComment(J.VariableDeclarations vd) {
                    // Check if comment already exists (idempotency)
                    if (hasNeedsReviewComment(vd)) {
                        return vd;
                    }

                    // Add comment to the prefix
                    List<Comment> existingComments = vd.getPrefix().getComments();
                    List<Comment> newComments = new ArrayList<>(existingComments);
                    // Use suffix "\n" + indent to place comment on its own line
                    String indent = extractIndent(vd.getPrefix());
                    newComments.add(0, new TextComment(true, LOCAL_VAR_COMMENT, "\n" + indent, Markers.EMPTY));

                    Space newPrefix = vd.getPrefix().withComments(newComments);
                    return vd.withPrefix(newPrefix);
                }

                /**
                 * WFQ-005: Check if the variable declaration already has a NeedsReview comment.
                 */
                private boolean hasNeedsReviewComment(J.VariableDeclarations vd) {
                    for (Comment comment : vd.getPrefix().getComments()) {
                        if (comment instanceof TextComment) {
                            String text = ((TextComment) comment).getText();
                            if (text.contains("NeedsReview")) {
                                return true;
                            }
                        }
                    }
                    return false;
                }

                /**
                 * Extract indentation from prefix whitespace.
                 */
                private String extractIndent(Space prefix) {
                    String ws = prefix.getWhitespace();
                    int lastNewline = ws.lastIndexOf('\n');
                    if (lastNewline >= 0) {
                        return ws.substring(lastNewline + 1);
                    }
                    return "";
                }

                /**
                 * Adds @NeedsReview annotation to a field declaration.
                 */
                private J.VariableDeclarations addNeedsReviewAnnotation(J.VariableDeclarations vd) {
                    // Check if already has @NeedsReview
                    boolean hasNeedsReview = vd.getLeadingAnnotations().stream()
                        .anyMatch(a -> "NeedsReview".equals(a.getSimpleName()) ||
                                      TypeUtils.isOfClassType(a.getType(), NEEDS_REVIEW_FQN));

                    if (hasNeedsReview) {
                        return vd;
                    }

                    // Add @NeedsReview annotation
                    maybeAddImport(NEEDS_REVIEW_FQN);

                    List<J.Annotation> newAnnotations = new ArrayList<>();

                    // Determine prefix for @NeedsReview
                    Space needsReviewPrefix;
                    if (vd.getLeadingAnnotations().isEmpty()) {
                        // No existing annotations - use field's prefix
                        needsReviewPrefix = vd.getPrefix();
                    } else {
                        // Has annotations - use first annotation's prefix
                        needsReviewPrefix = vd.getLeadingAnnotations().get(0).getPrefix();
                    }

                    J.Annotation needsReviewAnn = createNeedsReviewAnnotation(needsReviewPrefix);
                    newAnnotations.add(needsReviewAnn);

                    // Add existing annotations with adjusted prefixes
                    for (int i = 0; i < vd.getLeadingAnnotations().size(); i++) {
                        J.Annotation ann = vd.getLeadingAnnotations().get(i);
                        if (i == 0) {
                            // First annotation now comes after @NeedsReview - just a space
                            ann = ann.withPrefix(Space.format(" "));
                        }
                        newAnnotations.add(ann);
                    }

                    // Keep field prefix if there were no annotations, otherwise clear it
                    J.VariableDeclarations result = vd.withLeadingAnnotations(newAnnotations);
                    if (!vd.getLeadingAnnotations().isEmpty()) {
                        // Field had annotations - prefix was moved to @NeedsReview
                        // Leave field's prefix as-is (it's on the type now)
                    }

                    return result;
                }

                private boolean isJmsContextType(J.VariableDeclarations varDecls) {
                    TypeTree typeExpr = varDecls.getTypeExpression();
                    if (typeExpr == null) return false;

                    if (typeExpr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) typeExpr;
                        return TypeUtils.isOfClassType(ident.getType(), JMS_CONTEXT_FQN) ||
                               "JMSContext".equals(ident.getSimpleName());
                    }
                    return TypeUtils.isOfClassType(typeExpr.getType(), JMS_CONTEXT_FQN);
                }

                private J.Annotation createNeedsReviewAnnotation(Space prefix) {
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

                    // reason = "..."
                    arguments.add(createStringAssignmentArg("reason",
                        "JMSContext is CDI-specific and has no direct Spring equivalent",
                        false));

                    // category = NeedsReview.Category.CONFIGURATION
                    arguments.add(createCategoryArg("CONFIGURATION"));

                    // originalCode = "..."
                    arguments.add(createStringAssignmentArg("originalCode",
                        "@Inject JMSContext",
                        true));

                    // suggestedAction = "..."
                    arguments.add(createStringAssignmentArg("suggestedAction",
                        "Replace with JmsTemplate (recommended) or inject ConnectionFactory and use connectionFactory.createContext()",
                        true));

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

                private JRightPadded<Expression> createStringAssignmentArg(String key, String value, boolean leadingSpace) {
                    J.Identifier keyIdent = new J.Identifier(
                        Tree.randomId(),
                        leadingSpace ? Space.format(" ") : Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        key,
                        null,
                        null
                    );

                    J.Literal valueExpr = new J.Literal(
                        Tree.randomId(),
                        Space.format(" "),
                        Markers.EMPTY,
                        value,
                        "\"" + value.replace("\"", "\\\"") + "\"",
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
        );
    }
}
