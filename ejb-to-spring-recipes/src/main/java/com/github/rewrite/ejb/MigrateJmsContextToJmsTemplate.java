package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.Cursor;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.ScanningRecipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.RemoveImport;
import org.openrewrite.java.RemoveUnusedImports;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.marker.Markers;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Migrates simple JMSContext usage to JmsTemplate for direct send patterns.
 * <p>
 * Supported pattern:
 *   jmsContext.createProducer().send(destination, payload)
 * <p>
 * If unsupported JMSContext usage is detected for a field, it stays as-is and
 * gets a @NeedsReview marker.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateJmsContextToJmsTemplate extends ScanningRecipe<MigrateJmsContextToJmsTemplate.Accumulator> {

    private static final String JAKARTA_JMS_CONTEXT = "jakarta.jms.JMSContext";
    private static final String JAVAX_JMS_CONTEXT = "javax.jms.JMSContext";
    private static final String JMS_TEMPLATE_FQN = "org.springframework.jms.core.JmsTemplate";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Migrate JMSContext to JmsTemplate where safe";
    }

    @Override
    public String getDescription() {
        return "Replaces JMSContext fields used only for createProducer().send(...) with Spring's JmsTemplate.";
    }

    static class Accumulator {
        final Map<String, Usage> usageByFieldKey = new HashMap<>();
        final Map<String, java.util.Set<String>> contextFieldsByOwner = new HashMap<>();
    }

    static class Usage {
        boolean supportedSend;
        boolean unsupported;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_JMS_CONTEXT, false),
                new UsesType<>(JAVAX_JMS_CONTEXT, false)
            ),
            new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
                    J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);
                    if (isJmsContextField(vd)) {
                        String owner = resolveOwnerKey();
                        if (owner != null) {
                            java.util.Set<String> fields = acc.contextFieldsByOwner
                                .computeIfAbsent(owner, k -> new java.util.HashSet<>());
                            for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                                fields.add(var.getSimpleName());
                            }
                        }
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            String key = fieldKey(var.getName());
                            acc.usageByFieldKey.computeIfAbsent(key, k -> new Usage());
                        }
                    }
                    return vd;
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                    J.MethodInvocation m = super.visitMethodInvocation(method, ctx);

                    if (isSendFromCreateProducer(m)) {
                        FieldKey key = resolveFieldKeyFromCreateProducer(m);
                        if (key != null) {
                            Usage usage = acc.usageByFieldKey.computeIfAbsent(key.key, k -> new Usage());
                            if (isSupportedSend(m)) {
                                usage.supportedSend = true;
                            } else {
                                usage.unsupported = true;
                            }
                        }
                        return m;
                    }

                    if (isCreateProducerInvocation(m) && isParentSendInvocation(m)) {
                        return m;
                    }

                    FieldKey key = resolveFieldKeyFromExpression(m.getSelect());
                    if (key != null) {
                        Usage usage = acc.usageByFieldKey.computeIfAbsent(key.key, k -> new Usage());
                        usage.unsupported = true;
                    }
                    return m;
                }

                private boolean isSupportedSend(J.MethodInvocation send) {
                    return send.getArguments().size() == 2;
                }

                private boolean isSendFromCreateProducer(J.MethodInvocation send) {
                    if (!"send".equals(send.getSimpleName())) {
                        return false;
                    }
                    if (!(send.getSelect() instanceof J.MethodInvocation)) {
                        return false;
                    }
                    J.MethodInvocation createProducer = (J.MethodInvocation) send.getSelect();
                    return "createProducer".equals(createProducer.getSimpleName());
                }

                private boolean isCreateProducerInvocation(Expression expr) {
                    if (!(expr instanceof J.MethodInvocation)) {
                        return false;
                    }
                    J.MethodInvocation m = (J.MethodInvocation) expr;
                    return "createProducer".equals(m.getSimpleName());
                }

                private boolean isParentSendInvocation(J.MethodInvocation invocation) {
                    return isParentSendInvocation(getCursor(), invocation);
                }

                private boolean isParentSendInvocation(Cursor cursor, J.MethodInvocation invocation) {
                    Cursor current = cursor.getParent();
                    while (current != null) {
                        Object value = current.getValue();
                        if (value instanceof J.MethodInvocation) {
                            J.MethodInvocation parent = (J.MethodInvocation) value;
                            if ("send".equals(parent.getSimpleName()) && parent.getSelect() instanceof J.MethodInvocation) {
                                J.MethodInvocation select = (J.MethodInvocation) parent.getSelect();
                                return select.getId().equals(invocation.getId());
                            }
                            return false;
                        }
                        current = current.getParent();
                    }
                    return false;
                }

                @Nullable
                private FieldKey resolveFieldKeyFromCreateProducer(J.MethodInvocation send) {
                    if (!(send.getSelect() instanceof J.MethodInvocation)) {
                        return null;
                    }
                    J.MethodInvocation createProducer = (J.MethodInvocation) send.getSelect();
                    return resolveFieldKeyFromExpression(createProducer.getSelect());
                }

                @Nullable
                private FieldKey resolveFieldKeyFromExpression(@Nullable Expression expr) {
                    FieldKey loose = resolveFieldKeyFromExpressionLoose(expr);
                    if (loose != null) {
                        return loose;
                    }
                    if (expr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) expr;
                        if (isJmsContextFieldIdentifier(ident)) {
                            return new FieldKey(fieldKey(ident), ident.getSimpleName());
                        }
                    } else if (expr instanceof J.FieldAccess) {
                        J.FieldAccess fa = (J.FieldAccess) expr;
                        J.Identifier name = fa.getName();
                        if (isJmsContextFieldIdentifier(name)) {
                            return new FieldKey(fieldKey(name), name.getSimpleName());
                        }
                    }
                    return null;
                }

                @Nullable
                private FieldKey resolveFieldKeyFromExpressionLoose(@Nullable Expression expr) {
                    if (expr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) expr;
                        if (isKnownJmsContextName(ident.getSimpleName())) {
                            return new FieldKey(fieldKeyFromName(ident.getSimpleName()), ident.getSimpleName());
                        }
                    } else if (expr instanceof J.FieldAccess) {
                        J.FieldAccess fa = (J.FieldAccess) expr;
                        String name = fa.getSimpleName();
                        if (isKnownJmsContextName(name)) {
                            return new FieldKey(fieldKeyFromName(name), name);
                        }
                    }
                    return null;
                }

                private boolean isJmsContextField(J.VariableDeclarations varDecls) {
                    if (getCursor().firstEnclosing(J.MethodDeclaration.class) != null) {
                        return false;
                    }
                    if (TypeUtils.isOfClassType(varDecls.getType(), JAKARTA_JMS_CONTEXT) ||
                        TypeUtils.isOfClassType(varDecls.getType(), JAVAX_JMS_CONTEXT)) {
                        return true;
                    }
                    TypeTree typeExpr = varDecls.getTypeExpression();
                    if (typeExpr instanceof J.Identifier) {
                        return "JMSContext".equals(((J.Identifier) typeExpr).getSimpleName());
                    }
                    if (typeExpr instanceof J.FieldAccess) {
                        return "JMSContext".equals(((J.FieldAccess) typeExpr).getSimpleName());
                    }
                    return false;
                }

                private boolean isJmsContextFieldIdentifier(J.Identifier ident) {
                    JavaType.Variable fieldType = ident.getFieldType();
                    if (fieldType == null) {
                        return isKnownJmsContextField(ident);
                    }
                    JavaType type = fieldType.getType();
                    return TypeUtils.isOfClassType(type, JAKARTA_JMS_CONTEXT) ||
                           TypeUtils.isOfClassType(type, JAVAX_JMS_CONTEXT);
                }

                private String fieldKey(J.Identifier ident) {
                    String owner = resolveOwnerKey();
                    return (owner != null ? owner : "unknown") + "#" + ident.getSimpleName();
                }

                @Nullable
                private String resolveOwnerKey() {
                    J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    if (cd == null) {
                        return null;
                    }
                    J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                    if (cu != null && cu.getPackageDeclaration() != null) {
                        return cu.getPackageDeclaration().getExpression().toString() + "." + cd.getSimpleName();
                    }
                    return cd.getSimpleName();
                }

                private boolean isKnownJmsContextField(J.Identifier ident) {
                    String owner = resolveOwnerKey();
                    if (owner == null) {
                        return false;
                    }
                    java.util.Set<String> fields = acc.contextFieldsByOwner.get(owner);
                    return fields != null && fields.contains(ident.getSimpleName());
                }

                private boolean isKnownJmsContextName(String name) {
                    String owner = resolveOwnerKey();
                    if (owner == null) {
                        return false;
                    }
                    java.util.Set<String> fields = acc.contextFieldsByOwner.get(owner);
                    return fields != null && fields.contains(name);
                }

                private String fieldKeyFromName(String name) {
                    String owner = resolveOwnerKey();
                    return (owner != null ? owner : "unknown") + "#" + name;
                }


                private boolean isDeclarationName(J.Identifier ident) {
                    Object parent = getCursor().getParentOrThrow().getValue();
                    if (parent instanceof J.VariableDeclarations.NamedVariable) {
                        return ((J.VariableDeclarations.NamedVariable) parent).getName() == ident;
                    }
                    return false;
                }
            }
        );
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_JMS_CONTEXT, false),
                new UsesType<>(JAVAX_JMS_CONTEXT, false)
            ),
            new JavaIsoVisitor<ExecutionContext>() {
                private final JavaType.ShallowClass jmsTemplateType = JavaType.ShallowClass.build(JMS_TEMPLATE_FQN);
                private boolean convertedInCu;

                @Override
                public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                    convertedInCu = false;
                    J.CompilationUnit c = super.visitCompilationUnit(cu, ctx);
                    if (convertedInCu && !hasJmsContextUsage(c, ctx)) {
                        doAfterVisit(new RemoveImport<>(JAKARTA_JMS_CONTEXT, true));
                        doAfterVisit(new RemoveImport<>(JAVAX_JMS_CONTEXT, true));
                    }
                    return c;
                }

                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
                    J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);
                    if (!isJmsContextField(vd)) {
                        return vd;
                    }
                    boolean allSafe = true;
                    for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                        String key = fieldKey(var.getName());
                        Usage entry = acc.usageByFieldKey.get(key);
                        if (entry == null || entry.unsupported || !entry.supportedSend) {
                            allSafe = false;
                        }
                    }
                    if (allSafe) {
                        vd = replaceTypeWithJmsTemplate(vd);
                        convertedInCu = true;
                        maybeAddImport(JMS_TEMPLATE_FQN);
                        maybeRemoveImport(JAKARTA_JMS_CONTEXT);
                        maybeRemoveImport(JAVAX_JMS_CONTEXT);
                        doAfterVisit(new RemoveUnusedImports().getVisitor());
                        return vd;
                    }
                    if (!hasNeedsReview(vd)) {
                        vd = addNeedsReview(vd);
                        maybeAddImport(NEEDS_REVIEW_FQN);
                    }
                    return vd;
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
                    J.MethodInvocation m = super.visitMethodInvocation(method, ctx);
                    if (!isSendFromCreateProducer(m)) {
                        return m;
                    }
                    FieldKey key = resolveFieldKeyFromCreateProducer(m);
                    if (key == null) {
                        return m;
                    }
                    Usage entry = acc.usageByFieldKey.get(key.key);
                    if (entry == null || entry.unsupported || !entry.supportedSend) {
                        return m;
                    }
                    if (m.getArguments().size() != 2) {
                        return m;
                    }
                    J.MethodInvocation createProducer = (J.MethodInvocation) m.getSelect();
                    Expression contextExpr = withJmsTemplateType(createProducer.getSelect());
                    J.MethodInvocation updated = m.withSelect(contextExpr)
                        .withName(m.getName().withSimpleName("convertAndSend"));
                    maybeRemoveImport(JAKARTA_JMS_CONTEXT);
                    maybeRemoveImport(JAVAX_JMS_CONTEXT);
                    return updated;
                }

                private boolean hasNeedsReview(J.VariableDeclarations varDecls) {
                    for (J.Annotation ann : varDecls.getLeadingAnnotations()) {
                        if ("NeedsReview".equals(ann.getSimpleName()) ||
                            TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN)) {
                            return true;
                        }
                    }
                    return false;
                }

                private J.VariableDeclarations addNeedsReview(J.VariableDeclarations varDecls) {
                    List<J.Annotation> newAnnotations = new ArrayList<>();
                    Space prefix = varDecls.getLeadingAnnotations().isEmpty()
                        ? varDecls.getPrefix()
                        : varDecls.getLeadingAnnotations().get(0).getPrefix();
                    Space linePrefix = Space.format("\n" + varDecls.getPrefix().getIndent());
                    J.Annotation needsReview = buildNeedsReviewAnnotation(prefix);
                    newAnnotations.add(needsReview);
                    for (int i = 0; i < varDecls.getLeadingAnnotations().size(); i++) {
                        J.Annotation ann = varDecls.getLeadingAnnotations().get(i);
                        if (i == 0) {
                            ann = ann.withPrefix(linePrefix);
                        }
                        newAnnotations.add(ann);
                    }
                    J.VariableDeclarations updated = varDecls.withLeadingAnnotations(newAnnotations);
                    if (varDecls.getLeadingAnnotations().isEmpty()) {
                        updated = updated.withPrefix(Space.EMPTY);
                    }
                    return updated;
                }

                private J.Annotation buildNeedsReviewAnnotation(Space prefix) {
                    JavaType.ShallowClass type = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
                    J.Identifier ident = new J.Identifier(
                        java.util.UUID.randomUUID(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        List.of(),
                        "NeedsReview",
                        type,
                        null
                    );
                    List<Expression> args = new ArrayList<>();
                    args.add(createAssignment(
                        "reason",
                        createStringLiteral("JMSContext usage cannot be safely migrated; consider JmsTemplate or ConnectionFactory.createContext()")
                    ));
                    args.add(createAssignment("category", createCategoryExpression("MANUAL_MIGRATION"))
                        .withPrefix(Space.format(" ")));
                    return new J.Annotation(
                        java.util.UUID.randomUUID(),
                        prefix,
                        Markers.EMPTY,
                        ident,
                        JContainer.build(Space.EMPTY, pad(args), Markers.EMPTY)
                    );
                }

                private J.Literal createStringLiteral(String value) {
                    return new J.Literal(
                        java.util.UUID.randomUUID(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        value,
                        "\"" + value.replace("\"", "\\\"") + "\"",
                        null,
                        JavaType.Primitive.String
                    );
                }

                private J.Assignment createAssignment(String name, Expression value) {
                    return new J.Assignment(
                        java.util.UUID.randomUUID(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        new J.Identifier(
                            java.util.UUID.randomUUID(),
                            Space.EMPTY,
                            Markers.EMPTY,
                            List.of(),
                            name,
                            null,
                            null
                        ),
                        new JLeftPadded<>(Space.format(" "), value.withPrefix(Space.format(" ")), Markers.EMPTY),
                        null
                    );
                }

                private J.FieldAccess createCategoryExpression(String categoryName) {
                    JavaType.ShallowClass needsReviewType = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN);
                    JavaType.ShallowClass categoryType = JavaType.ShallowClass.build(NEEDS_REVIEW_FQN + ".Category");

                    J.Identifier needsReviewIdent = new J.Identifier(
                        java.util.UUID.randomUUID(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        List.of(),
                        "NeedsReview",
                        needsReviewType,
                        null
                    );

                    J.FieldAccess categoryAccess = new J.FieldAccess(
                        java.util.UUID.randomUUID(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        needsReviewIdent,
                        JLeftPadded.build(new J.Identifier(
                            java.util.UUID.randomUUID(),
                            Space.EMPTY,
                            Markers.EMPTY,
                            List.of(),
                            "Category",
                            categoryType,
                            null
                        )),
                        categoryType
                    );

                    return new J.FieldAccess(
                        java.util.UUID.randomUUID(),
                        Space.format(" "),
                        Markers.EMPTY,
                        categoryAccess,
                        JLeftPadded.build(new J.Identifier(
                            java.util.UUID.randomUUID(),
                            Space.EMPTY,
                            Markers.EMPTY,
                            List.of(),
                            categoryName,
                            categoryType,
                            null
                        )),
                        categoryType
                    );
                }

                private List<JRightPadded<Expression>> pad(List<Expression> args) {
                    List<JRightPadded<Expression>> padded = new ArrayList<>(args.size());
                    for (Expression arg : args) {
                        padded.add(new JRightPadded<>(arg, Space.EMPTY, Markers.EMPTY));
                    }
                    return padded;
                }

                private J.VariableDeclarations replaceTypeWithJmsTemplate(J.VariableDeclarations varDecls) {
                    TypeTree typeExpr = varDecls.getTypeExpression();
                    TypeTree updatedTypeExpr = typeExpr;
                    if (typeExpr instanceof J.Identifier) {
                        updatedTypeExpr = ((J.Identifier) typeExpr).withSimpleName("JmsTemplate").withType(jmsTemplateType);
                    } else if (typeExpr instanceof J.FieldAccess) {
                        updatedTypeExpr = new J.Identifier(
                            java.util.UUID.randomUUID(),
                            typeExpr.getPrefix(),
                            Markers.EMPTY,
                            List.of(),
                            "JmsTemplate",
                            jmsTemplateType,
                            null
                        );
                    }
                    List<J.VariableDeclarations.NamedVariable> updatedVars = new ArrayList<>();
                    for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                        JavaType.Variable varType = var.getVariableType();
                        if (varType != null) {
                            varType = varType.withType(jmsTemplateType);
                        }
                        updatedVars.add(var.withType(jmsTemplateType).withVariableType(varType));
                    }
                    return varDecls.withType(jmsTemplateType)
                        .withTypeExpression(updatedTypeExpr)
                        .withVariables(updatedVars);
                }

                private Expression withJmsTemplateType(Expression expr) {
                    if (expr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) expr;
                        JavaType.Variable fieldType = ident.getFieldType();
                        if (fieldType != null) {
                            fieldType = fieldType.withType(jmsTemplateType);
                        }
                        return ident.withType(jmsTemplateType).withFieldType(fieldType);
                    }
                    if (expr instanceof J.FieldAccess) {
                        J.FieldAccess fa = (J.FieldAccess) expr;
                        J.Identifier name = fa.getName();
                        JavaType.Variable fieldType = name.getFieldType();
                        if (fieldType != null) {
                            fieldType = fieldType.withType(jmsTemplateType);
                        }
                        J.Identifier updatedName = name.withType(jmsTemplateType).withFieldType(fieldType);
                        return fa.withName(updatedName).withType(jmsTemplateType);
                    }
                    return expr;
                }

                private boolean hasJmsContextUsage(J.CompilationUnit cu, ExecutionContext ctx) {
                    class Finder extends JavaIsoVisitor<ExecutionContext> {
                        boolean found;

                        @Override
                        public J.Import visitImport(J.Import _import, ExecutionContext ctx) {
                            return _import;
                        }

                        @Override
                        public J.Identifier visitIdentifier(J.Identifier ident, ExecutionContext ctx) {
                            if ("JMSContext".equals(ident.getSimpleName())) {
                                found = true;
                            }
                            return ident;
                        }

                        @Override
                        public J.FieldAccess visitFieldAccess(J.FieldAccess fa, ExecutionContext ctx) {
                            if ("JMSContext".equals(fa.getSimpleName())) {
                                found = true;
                            }
                            return fa;
                        }
                    }
                    Finder finder = new Finder();
                    finder.visit(cu, ctx);
                    return finder.found;
                }

                private boolean isSendFromCreateProducer(J.MethodInvocation send) {
                    if (!"send".equals(send.getSimpleName())) {
                        return false;
                    }
                    if (!(send.getSelect() instanceof J.MethodInvocation)) {
                        return false;
                    }
                    J.MethodInvocation createProducer = (J.MethodInvocation) send.getSelect();
                    return "createProducer".equals(createProducer.getSimpleName());
                }

                private boolean isCreateProducerInvocation(@Nullable Expression expr) {
                    if (!(expr instanceof J.MethodInvocation)) {
                        return false;
                    }
                    J.MethodInvocation m = (J.MethodInvocation) expr;
                    return "createProducer".equals(m.getSimpleName());
                }

                @Nullable
                private FieldKey resolveFieldKeyFromCreateProducer(J.MethodInvocation send) {
                    if (!(send.getSelect() instanceof J.MethodInvocation)) {
                        return null;
                    }
                    J.MethodInvocation createProducer = (J.MethodInvocation) send.getSelect();
                    return resolveFieldKeyFromExpression(createProducer.getSelect());
                }

                @Nullable
                private FieldKey resolveFieldKeyFromExpression(@Nullable Expression expr) {
                    FieldKey loose = resolveFieldKeyFromExpressionLoose(expr);
                    if (loose != null) {
                        return loose;
                    }
                    if (expr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) expr;
                        if (isJmsContextFieldIdentifier(ident)) {
                            return new FieldKey(fieldKey(ident), ident.getSimpleName());
                        }
                    } else if (expr instanceof J.FieldAccess) {
                        J.FieldAccess fa = (J.FieldAccess) expr;
                        J.Identifier name = fa.getName();
                        if (isJmsContextFieldIdentifier(name)) {
                            return new FieldKey(fieldKey(name), name.getSimpleName());
                        }
                    }
                    return null;
                }

                @Nullable
                private FieldKey resolveFieldKeyFromExpressionLoose(@Nullable Expression expr) {
                    if (expr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) expr;
                        if (isKnownJmsContextName(ident.getSimpleName())) {
                            return new FieldKey(fieldKeyFromName(ident.getSimpleName()), ident.getSimpleName());
                        }
                    } else if (expr instanceof J.FieldAccess) {
                        J.FieldAccess fa = (J.FieldAccess) expr;
                        String name = fa.getSimpleName();
                        if (isKnownJmsContextName(name)) {
                            return new FieldKey(fieldKeyFromName(name), name);
                        }
                    }
                    return null;
                }

                private boolean isJmsContextField(J.VariableDeclarations varDecls) {
                    if (getCursor().firstEnclosing(J.MethodDeclaration.class) != null) {
                        return false;
                    }
                    if (TypeUtils.isOfClassType(varDecls.getType(), JAKARTA_JMS_CONTEXT) ||
                        TypeUtils.isOfClassType(varDecls.getType(), JAVAX_JMS_CONTEXT)) {
                        return true;
                    }
                    TypeTree typeExpr = varDecls.getTypeExpression();
                    if (typeExpr instanceof J.Identifier) {
                        return "JMSContext".equals(((J.Identifier) typeExpr).getSimpleName());
                    }
                    if (typeExpr instanceof J.FieldAccess) {
                        return "JMSContext".equals(((J.FieldAccess) typeExpr).getSimpleName());
                    }
                    return false;
                }

                private boolean isJmsContextFieldIdentifier(J.Identifier ident) {
                    JavaType.Variable fieldType = ident.getFieldType();
                    if (fieldType == null) {
                        return isKnownJmsContextField(ident);
                    }
                    JavaType type = fieldType.getType();
                    return TypeUtils.isOfClassType(type, JAKARTA_JMS_CONTEXT) ||
                           TypeUtils.isOfClassType(type, JAVAX_JMS_CONTEXT);
                }

                private String fieldKey(J.Identifier ident) {
                    String owner = resolveOwnerKey();
                    return (owner != null ? owner : "unknown") + "#" + ident.getSimpleName();
                }

                @Nullable
                private String resolveOwnerKey() {
                    J.ClassDeclaration cd = getCursor().firstEnclosing(J.ClassDeclaration.class);
                    if (cd == null) {
                        return null;
                    }
                    J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                    if (cu != null && cu.getPackageDeclaration() != null) {
                        return cu.getPackageDeclaration().getExpression().toString() + "." + cd.getSimpleName();
                    }
                    return cd.getSimpleName();
                }

                private boolean isKnownJmsContextField(J.Identifier ident) {
                    String owner = resolveOwnerKey();
                    if (owner == null) {
                        return false;
                    }
                    java.util.Set<String> fields = acc.contextFieldsByOwner.get(owner);
                    return fields != null && fields.contains(ident.getSimpleName());
                }

                private boolean isKnownJmsContextName(String name) {
                    String owner = resolveOwnerKey();
                    if (owner == null) {
                        return false;
                    }
                    java.util.Set<String> fields = acc.contextFieldsByOwner.get(owner);
                    return fields != null && fields.contains(name);
                }

                private String fieldKeyFromName(String name) {
                    String owner = resolveOwnerKey();
                    return (owner != null ? owner : "unknown") + "#" + name;
                }
            }
        );
    }

    private static class FieldKey {
        final String key;
        final String name;

        private FieldKey(String key, String name) {
            this.key = key;
            this.name = name;
        }
    }
}
