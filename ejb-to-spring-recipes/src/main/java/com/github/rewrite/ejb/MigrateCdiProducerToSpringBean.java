package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Migrates CDI @Produces to Spring @Bean.
 * <p>
 * Handles:
 * 1. Simple @Produces methods -> @Bean
 * 2. Class without @Configuration -> adds @Configuration
 * 3. @Produces methods WITH InjectionPoint -> REMOVED with TODO comment
 *    (CDI InjectionPoint has different API than Spring's, no automatic migration possible)
 * <p>
 * CDI InjectionPoint methods typically create beans based on injection target (e.g., Logger).
 * In Spring, this pattern is either:
 * - Not needed (use @Slf4j from Lombok)
 * - Requires prototype-scoped @Bean with Spring's InjectionPoint (different API)
 * - Uses factory methods or ObjectProvider
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateCdiProducerToSpringBean extends Recipe {

    private static final String PRODUCES_FQN = "jakarta.enterprise.inject.Produces";
    private static final String INJECTION_POINT_FQN = "jakarta.enterprise.inject.spi.InjectionPoint";
    private static final String SPRING_BEAN_FQN = "org.springframework.context.annotation.Bean";
    private static final String SPRING_CONFIG_FQN = "org.springframework.context.annotation.Configuration";
    private static final String SPRING_INJECTION_POINT_FQN = "org.springframework.beans.factory.InjectionPoint";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final Set<String> SAFE_INJECTION_POINT_METHODS = Set.of(
        "getMember",
        "getMethodParameter",
        "getField",
        "getAnnotatedElement"
    );

    @Override
    public String getDisplayName() {
        return "Migrate CDI @Produces to Spring @Bean";
    }

    @Override
    public String getDescription() {
        return "Converts CDI @Produces methods to Spring @Bean. Methods using InjectionPoint are removed with TODO comments.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            new UsesType<>(PRODUCES_FQN, false),
            new CdiProducerVisitor()
        );
    }

    private static class CdiProducerVisitor extends JavaIsoVisitor<ExecutionContext> {

        private boolean hasProducesMethod = false;
        private boolean usesSpringInjectionPoint = false;

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            hasProducesMethod = false;
            usesSpringInjectionPoint = false;

            J.CompilationUnit result = super.visitCompilationUnit(cu, ctx);

            if (hasProducesMethod) {
                doAfterVisit(new RemoveImport<>(PRODUCES_FQN, true));
            }
            if (usesSpringInjectionPoint && !hasCdiInjectionPointType(result, ctx)) {
                doAfterVisit(new RemoveImport<>(INJECTION_POINT_FQN, true));
            }

            return result;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // Check for @Produces methods BEFORE super call
            boolean classHasProduces = classDecl.getBody().getStatements().stream()
                .filter(s -> s instanceof J.MethodDeclaration)
                .map(s -> (J.MethodDeclaration) s)
                .anyMatch(m -> m.getLeadingAnnotations().stream()
                    .anyMatch(a -> TypeUtils.isOfClassType(a.getType(), PRODUCES_FQN) ||
                                  "Produces".equals(a.getSimpleName())));

            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            if (!classHasProduces) {
                return cd;
            }

            // Add @Configuration if not present
            boolean hasConfiguration = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> TypeUtils.isOfClassType(a.getType(), SPRING_CONFIG_FQN) ||
                              "Configuration".equals(a.getSimpleName()));

            if (!hasConfiguration) {
                maybeAddImport(SPRING_CONFIG_FQN);

                JavaType.ShallowClass configType = JavaType.ShallowClass.build(SPRING_CONFIG_FQN);
                J.Identifier configIdent = new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "Configuration",
                    configType,
                    null
                );

                Space prefix = cd.getPrefix();
                J.Annotation configAnnotation = new J.Annotation(
                    Tree.randomId(),
                    prefix,
                    Markers.EMPTY,
                    configIdent,
                    null
                );

                List<J.Annotation> newAnnotations = new ArrayList<>(cd.getLeadingAnnotations());
                newAnnotations.add(0, configAnnotation);
                cd = cd.withLeadingAnnotations(newAnnotations).withPrefix(Space.EMPTY);
                cd = autoFormat(cd, ctx);
            }

            return cd;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            // Check if method has @Produces
            boolean hasProduces = md.getLeadingAnnotations().stream()
                .anyMatch(a -> TypeUtils.isOfClassType(a.getType(), PRODUCES_FQN) ||
                              "Produces".equals(a.getSimpleName()));

            if (!hasProduces) {
                return md;
            }

            hasProducesMethod = true;

            // Check if method has InjectionPoint parameter
            List<String> injectionPointParams = md.getParameters().stream()
                .filter(p -> p instanceof J.VariableDeclarations)
                .map(p -> (J.VariableDeclarations) p)
                .filter(vd -> {
                    TypeTree typeExpr = vd.getTypeExpression();
                    if (typeExpr instanceof J.Identifier) {
                        J.Identifier ident = (J.Identifier) typeExpr;
                        return TypeUtils.isOfClassType(ident.getType(), INJECTION_POINT_FQN) ||
                            "InjectionPoint".equals(ident.getSimpleName());
                    }
                    return false;
                })
                .flatMap(vd -> vd.getVariables().stream()
                    .map(J.VariableDeclarations.NamedVariable::getSimpleName))
                .collect(Collectors.toList());

            if (!injectionPointParams.isEmpty()) {
                usesSpringInjectionPoint = true;
                doAfterVisit(new AddImport<>(SPRING_INJECTION_POINT_FQN, null, false));
                md = replaceInjectionPointParams(md, injectionPointParams);
            }

            // Simple @Produces -> @Bean conversion
            List<J.Annotation> newAnnotations = new ArrayList<>();
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), PRODUCES_FQN) ||
                    "Produces".equals(ann.getSimpleName())) {
                    maybeAddImport(SPRING_BEAN_FQN);

                    JavaType.ShallowClass beanType = JavaType.ShallowClass.build(SPRING_BEAN_FQN);
                    J.Identifier beanIdent = new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "Bean",
                        beanType,
                        null
                    );

                    J.Annotation beanAnnotation = new J.Annotation(
                        Tree.randomId(),
                        ann.getPrefix(),
                        Markers.EMPTY,
                        beanIdent,
                        null
                    );
                    newAnnotations.add(beanAnnotation);
                } else {
                    newAnnotations.add(ann);
                }
            }
            md = md.withLeadingAnnotations(newAnnotations);

            if (!injectionPointParams.isEmpty()) {
                boolean needsReview = hasUnsupportedInjectionPointUsage(md, injectionPointParams, ctx);
                if (needsReview && !hasNeedsReviewAnnotation(md.getLeadingAnnotations())) {
                    md = addNeedsReviewAnnotation(md);
                }
            }

            return md;
        }

        private J.MethodDeclaration replaceInjectionPointParams(J.MethodDeclaration md, List<String> injectionPointParams) {
            List<Statement> updatedParams = new ArrayList<>();
            for (Statement param : md.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    if (isCdiInjectionPointType(vd.getTypeExpression())) {
                        updatedParams.add(replaceInjectionPointType(vd));
                    } else {
                        updatedParams.add(vd);
                    }
                } else {
                    updatedParams.add(param);
                }
            }
            return md.withParameters(updatedParams);
        }

        private boolean isCdiInjectionPointType(TypeTree typeExpr) {
            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                return TypeUtils.isOfClassType(ident.getType(), INJECTION_POINT_FQN)
                    || "InjectionPoint".equals(ident.getSimpleName());
            }
            if (typeExpr instanceof J.FieldAccess) {
                return "InjectionPoint".equals(((J.FieldAccess) typeExpr).getSimpleName());
            }
            return false;
        }

        private J.VariableDeclarations replaceInjectionPointType(J.VariableDeclarations varDecls) {
            JavaType.ShallowClass springType = JavaType.ShallowClass.build(SPRING_INJECTION_POINT_FQN);
            TypeTree typeExpr = varDecls.getTypeExpression();
            TypeTree updatedTypeExpr = typeExpr;
            if (typeExpr instanceof J.Identifier) {
                updatedTypeExpr = ((J.Identifier) typeExpr).withSimpleName("InjectionPoint").withType(springType);
            } else if (typeExpr instanceof J.FieldAccess) {
                updatedTypeExpr = new J.Identifier(
                    Tree.randomId(),
                    typeExpr.getPrefix(),
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "InjectionPoint",
                    springType,
                    null
                );
            }
            List<J.VariableDeclarations.NamedVariable> updatedVars = new ArrayList<>();
            for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                JavaType.Variable varType = var.getVariableType();
                if (varType != null) {
                    updatedVars.add(var.withVariableType(varType.withType(springType)));
                } else {
                    updatedVars.add(var);
                }
            }
            return varDecls.withType(springType)
                .withTypeExpression(updatedTypeExpr)
                .withVariables(updatedVars);
        }

        private boolean hasUnsupportedInjectionPointUsage(J.MethodDeclaration md, List<String> injectionPointParams, ExecutionContext ctx) {
            if (md.getBody() == null) {
                return false;
            }
            final boolean[] needsReview = {false};
            new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation methodInvocation, ExecutionContext ctx) {
                    Expression select = methodInvocation.getSelect();
                    if (select instanceof J.Identifier) {
                        String name = ((J.Identifier) select).getSimpleName();
                        if (injectionPointParams.contains(name)) {
                            String methodName = methodInvocation.getSimpleName();
                            if (!SAFE_INJECTION_POINT_METHODS.contains(methodName)) {
                                needsReview[0] = true;
                            }
                        }
                    }
                    return super.visitMethodInvocation(methodInvocation, ctx);
                }
            }.visit(md.getBody(), ctx);
            return needsReview[0];
        }

        private boolean hasNeedsReviewAnnotation(List<J.Annotation> annotations) {
            return annotations.stream().anyMatch(a -> "NeedsReview".equals(a.getSimpleName()));
        }

        private J.MethodDeclaration addNeedsReviewAnnotation(J.MethodDeclaration md) {
            maybeAddImport(NEEDS_REVIEW_FQN);
            List<J.Annotation> annotations = new ArrayList<>(md.getLeadingAnnotations());
            Space prefix = annotations.isEmpty() ? md.getPrefix() : annotations.get(0).getPrefix();
            String indentation = extractIndentation(prefix.getWhitespace());

            J.Annotation needsReview = createNeedsReviewAnnotation(
                "CDI InjectionPoint uses API not fully compatible with Spring. Verify usage of InjectionPoint methods.",
                "CDI_FEATURE",
                "@Produces",
                "Review InjectionPoint API differences and adjust as needed",
                prefix
            );

            List<J.Annotation> updated = new ArrayList<>();
            updated.add(needsReview);
            for (int i = 0; i < annotations.size(); i++) {
                J.Annotation ann = annotations.get(i);
                if (i == 0) {
                    ann = ann.withPrefix(Space.format("\n" + indentation));
                }
                updated.add(ann);
            }
            md = md.withLeadingAnnotations(updated);
            if (!annotations.isEmpty()) {
                md = md.withPrefix(Space.EMPTY);
            }
            return md;
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

            J.Literal valueExpr = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                value,
                "\"" + escapeJavaString(value) + "\"",
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

        private String escapeJavaString(String value) {
            return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        }

        private String extractIndentation(String whitespace) {
            if (whitespace == null || whitespace.isEmpty()) {
                return "    ";
            }
            int lastNewline = whitespace.lastIndexOf('\n');
            if (lastNewline >= 0 && lastNewline < whitespace.length() - 1) {
                return whitespace.substring(lastNewline + 1);
            }
            if (whitespace.matches("^\\s+$")) {
                return whitespace;
            }
            return "    ";
        }

        private boolean hasCdiInjectionPointType(J.CompilationUnit cu, ExecutionContext ctx) {
            final boolean[] found = {false};
            new JavaIsoVisitor<ExecutionContext>() {
                @Override
                public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext ctx) {
                    if (TypeUtils.isOfClassType(vd.getType(), INJECTION_POINT_FQN)) {
                        found[0] = true;
                    } else if (vd.getTypeExpression() != null &&
                        TypeUtils.isOfClassType(vd.getTypeExpression().getType(), INJECTION_POINT_FQN)) {
                        found[0] = true;
                    }
                    return vd;
                }

                @Override
                public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                    TypeTree returnType = method.getReturnTypeExpression();
                    if (returnType != null && TypeUtils.isOfClassType(returnType.getType(), INJECTION_POINT_FQN)) {
                        found[0] = true;
                    }
                    return method;
                }
            }.visit(cu, ctx);
            return found[0];
        }
    }
}
