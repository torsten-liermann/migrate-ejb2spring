package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.java.tree.JRightPadded;

import java.util.*;

/**
 * Migrates Jakarta EJB @ApplicationException rollback semantics to Spring @Transactional.
 * <p>
 * MKR-005 requirements:
 * <ul>
 *   <li>Methods that throw exceptions with {@code @ApplicationException(rollback=true)} get
 *       {@code @Transactional(rollbackFor=...)} for checked exceptions</li>
 *   <li>Methods that throw exceptions with {@code @ApplicationException(rollback=false)} get
 *       {@code @Transactional(noRollbackFor=...)} for unchecked exceptions</li>
 *   <li>Existing @Transactional attributes are preserved during merge</li>
 *   <li>@ApplicationException is removed from exception classes after processing</li>
 * </ul>
 * <p>
 * Semantics mapping:
 * <ul>
 *   <li>EJB: @ApplicationException marks an exception as an "application exception" (vs system exception)</li>
 *   <li>Application exceptions don't rollback by default (rollback=false)</li>
 *   <li>Spring: RuntimeExceptions rollback by default, checked exceptions don't</li>
 *   <li>Mapping: rollback=true + checked → rollbackFor; rollback=false + unchecked → noRollbackFor</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateApplicationExceptionRollback extends ScanningRecipe<MigrateApplicationExceptionRollback.Accumulator> {

    private static final String JAKARTA_APP_EXCEPTION = "jakarta.ejb.ApplicationException";
    private static final String JAVAX_APP_EXCEPTION = "javax.ejb.ApplicationException";
    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

    @Override
    public String getDisplayName() {
        return "Migrate @ApplicationException rollback semantics to @Transactional";
    }

    @Override
    public String getDescription() {
        return "Maps EJB @ApplicationException(rollback=...) semantics to Spring @Transactional " +
               "rollbackFor/noRollbackFor attributes. Removes @ApplicationException after processing.";
    }

    static class Accumulator {
        // Maps exception FQN to its rollback info
        Map<String, AppExceptionInfo> appExceptions = new HashMap<>();
    }

    static class AppExceptionInfo {
        final String fqn;
        final String simpleName;
        final boolean rollback;
        final boolean isRuntimeException;
        final boolean inherited;

        AppExceptionInfo(String fqn, String simpleName, boolean rollback, boolean isRuntimeException, boolean inherited) {
            this.fqn = fqn;
            this.simpleName = simpleName;
            this.rollback = rollback;
            this.isRuntimeException = isRuntimeException;
            this.inherited = inherited;
        }

        /**
         * Returns true if this exception requires modification to @Transactional.
         * - rollback=true on checked exception → need rollbackFor
         * - rollback=false on unchecked exception → need noRollbackFor
         */
        boolean needsTransactionalModification() {
            if (rollback && !isRuntimeException) {
                // Checked exception with rollback=true needs rollbackFor
                return true;
            }
            if (!rollback && isRuntimeException) {
                // RuntimeException with rollback=false needs noRollbackFor
                return true;
            }
            return false;
        }

        String getRequiredAttribute() {
            if (rollback && !isRuntimeException) {
                return "rollbackFor";
            }
            if (!rollback && isRuntimeException) {
                return "noRollbackFor";
            }
            return null;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Check if this class has @ApplicationException
                for (J.Annotation annotation : cd.getLeadingAnnotations()) {
                    if (isApplicationExceptionAnnotation(annotation)) {
                        String fqn = extractFqn(cd);
                        if (fqn != null) {
                            boolean rollback = extractRollbackValue(annotation);
                            boolean inherited = extractInheritedValue(annotation);
                            boolean isRuntime = extendsRuntimeException(cd);
                            acc.appExceptions.put(fqn, new AppExceptionInfo(fqn, cd.getSimpleName(), rollback, isRuntime, inherited));
                        }
                    }
                }

                return cd;
            }

            private String extractFqn(J.ClassDeclaration classDecl) {
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(classDecl.getType());
                if (type != null) {
                    return type.getFullyQualifiedName();
                }
                // Fallback: try to build from package + class name
                Cursor cursor = getCursor();
                J.CompilationUnit cu = cursor.firstEnclosing(J.CompilationUnit.class);
                if (cu != null && cu.getPackageDeclaration() != null) {
                    return cu.getPackageDeclaration().getPackageName() + "." + classDecl.getSimpleName();
                }
                return classDecl.getSimpleName();
            }

            private boolean extractRollbackValue(J.Annotation annotation) {
                return extractBooleanAnnotationValue(annotation, "rollback", false);
            }

            private boolean extractInheritedValue(J.Annotation annotation) {
                return extractBooleanAnnotationValue(annotation, "inherited", true);  // default is inherited=true
            }

            private boolean extractBooleanAnnotationValue(J.Annotation annotation, String attrName, boolean defaultValue) {
                if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                    return defaultValue;
                }

                for (Expression arg : annotation.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assign = (J.Assignment) arg;
                        String name = assign.getVariable() instanceof J.Identifier
                            ? ((J.Identifier) assign.getVariable()).getSimpleName()
                            : "";
                        if (attrName.equals(name)) {
                            Expression value = assign.getAssignment();
                            if (value instanceof J.Literal) {
                                Object val = ((J.Literal) value).getValue();
                                return Boolean.TRUE.equals(val);
                            }
                            if (value instanceof J.Identifier) {
                                return "true".equalsIgnoreCase(((J.Identifier) value).getSimpleName());
                            }
                        }
                    }
                }
                return defaultValue;
            }

            private boolean extendsRuntimeException(J.ClassDeclaration classDecl) {
                // Check via type information
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(classDecl.getType());
                if (type != null) {
                    // Check supertype chain
                    JavaType.FullyQualified current = type;
                    while (current != null) {
                        String name = current.getFullyQualifiedName();
                        if ("java.lang.RuntimeException".equals(name) ||
                            "java.lang.Error".equals(name)) {
                            return true;
                        }
                        if ("java.lang.Exception".equals(name) ||
                            "java.lang.Throwable".equals(name)) {
                            return false;
                        }
                        current = current.getSupertype();
                    }
                }

                // Fallback: check extends clause textually
                if (classDecl.getExtends() != null) {
                    String extendsType = classDecl.getExtends().toString();
                    return extendsType.contains("RuntimeException") ||
                           extendsType.contains("IllegalArgumentException") ||
                           extendsType.contains("IllegalStateException") ||
                           extendsType.contains("NullPointerException");
                }

                return false;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (acc.appExceptions.isEmpty()) {
            return TreeVisitor.noop();
        }

        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);

                // Check if method throws any of our tracked exceptions
                if (m.getThrows() == null || m.getThrows().isEmpty()) {
                    return m;
                }

                // Collect exceptions that need @Transactional modification
                List<AppExceptionInfo> rollbackForExceptions = new ArrayList<>();
                List<AppExceptionInfo> noRollbackForExceptions = new ArrayList<>();

                for (NameTree throwsExpr : m.getThrows()) {
                    AppExceptionInfo info = findAppExceptionInfo(throwsExpr);
                    if (info != null && info.needsTransactionalModification()) {
                        if ("rollbackFor".equals(info.getRequiredAttribute())) {
                            rollbackForExceptions.add(info);
                        } else {
                            noRollbackForExceptions.add(info);
                        }
                    }
                }

                if (rollbackForExceptions.isEmpty() && noRollbackForExceptions.isEmpty()) {
                    return m;
                }

                // Find or create @Transactional annotation
                return addOrMergeTransactional(m, rollbackForExceptions, noRollbackForExceptions, ctx);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Remove @ApplicationException annotation
                List<J.Annotation> newAnnotations = new ArrayList<>();
                boolean changed = false;
                for (J.Annotation annotation : cd.getLeadingAnnotations()) {
                    if (isApplicationExceptionAnnotation(annotation)) {
                        changed = true;
                        maybeRemoveImport(JAKARTA_APP_EXCEPTION);
                        maybeRemoveImport(JAVAX_APP_EXCEPTION);
                    } else {
                        newAnnotations.add(annotation);
                    }
                }

                if (changed) {
                    return cd.withLeadingAnnotations(newAnnotations);
                }
                return cd;
            }

            /**
             * Find AppExceptionInfo for a thrown exception, also checking supertypes
             * for inherited @ApplicationException annotations.
             */
            private AppExceptionInfo findAppExceptionInfo(NameTree throwsExpr) {
                JavaType type = throwsExpr.getType();
                String thrownFqn = null;

                if (type instanceof JavaType.FullyQualified) {
                    thrownFqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();

                    // First check direct match
                    AppExceptionInfo direct = acc.appExceptions.get(thrownFqn);
                    if (direct != null) {
                        return direct;
                    }

                    // Check supertype chain for inherited @ApplicationException
                    JavaType.FullyQualified current = (JavaType.FullyQualified) type;
                    while (current != null) {
                        String currentFqn = current.getFullyQualifiedName();
                        AppExceptionInfo info = acc.appExceptions.get(currentFqn);
                        if (info != null && info.inherited) {
                            // Create new info with the actual thrown exception's FQN for correct class reference
                            // but use the parent's rollback semantics
                            String simpleName = throwsExpr.toString();
                            if (simpleName.contains(".")) {
                                simpleName = simpleName.substring(simpleName.lastIndexOf('.') + 1);
                            }
                            return new AppExceptionInfo(
                                thrownFqn,
                                simpleName,
                                info.rollback,
                                info.isRuntimeException,
                                false  // The thrown exception itself is not marked with @ApplicationException
                            );
                        }
                        current = current.getSupertype();
                    }
                } else {
                    // Fallback: simple name matching
                    String simpleName = throwsExpr.toString();
                    for (AppExceptionInfo info : acc.appExceptions.values()) {
                        if (info.simpleName.equals(simpleName)) {
                            return info;
                        }
                    }
                }
                return null;
            }

            private J.MethodDeclaration addOrMergeTransactional(
                    J.MethodDeclaration method,
                    List<AppExceptionInfo> rollbackFor,
                    List<AppExceptionInfo> noRollbackFor,
                    ExecutionContext ctx) {

                // Check if @Transactional already exists
                J.Annotation existingTx = null;
                int existingIndex = -1;
                List<J.Annotation> annotations = method.getLeadingAnnotations();
                for (int i = 0; i < annotations.size(); i++) {
                    J.Annotation a = annotations.get(i);
                    if (isTransactionalAnnotation(a)) {
                        existingTx = a;
                        existingIndex = i;
                        break;
                    }
                }

                if (existingTx != null) {
                    // Merge with existing @Transactional
                    // Add imports for new exception classes
                    for (AppExceptionInfo info : rollbackFor) {
                        maybeAddImport(info.fqn);
                    }
                    for (AppExceptionInfo info : noRollbackFor) {
                        maybeAddImport(info.fqn);
                    }
                    J.Annotation merged = mergeTransactionalAttributes(existingTx, rollbackFor, noRollbackFor);
                    if (merged != existingTx) {
                        List<J.Annotation> newAnnotations = new ArrayList<>(annotations);
                        newAnnotations.set(existingIndex, merged);
                        return method.withLeadingAnnotations(newAnnotations);
                    }
                    return method;
                } else {
                    // Create new @Transactional
                    maybeAddImport(SPRING_TRANSACTIONAL);
                    // Add imports for exception classes
                    for (AppExceptionInfo info : rollbackFor) {
                        maybeAddImport(info.fqn);
                    }
                    for (AppExceptionInfo info : noRollbackFor) {
                        maybeAddImport(info.fqn);
                    }
                    J.Annotation newTx = createTransactionalAnnotation(rollbackFor, noRollbackFor);

                    // Move method's prefix (newline + indent) to the new annotation
                    Space methodPrefix = method.getPrefix();
                    newTx = newTx.withPrefix(methodPrefix);

                    List<J.Annotation> newAnnotations = new ArrayList<>();
                    newAnnotations.add(newTx);
                    newAnnotations.addAll(annotations);

                    // Set method's prefix to empty and use autoFormat to fix formatting
                    J.MethodDeclaration result = method.withLeadingAnnotations(newAnnotations).withPrefix(Space.EMPTY);
                    return autoFormat(result, ctx);
                }
            }

            private J.Annotation mergeTransactionalAttributes(
                    J.Annotation annotation,
                    List<AppExceptionInfo> rollbackFor,
                    List<AppExceptionInfo> noRollbackFor) {

                List<Expression> args = annotation.getArguments();
                if (args == null) {
                    args = new ArrayList<>();
                }

                // Check if rollbackFor/noRollbackFor already exist
                boolean hasRollbackFor = false;
                boolean hasNoRollbackFor = false;
                List<Expression> existingRollbackForClasses = new ArrayList<>();
                List<Expression> existingNoRollbackForClasses = new ArrayList<>();

                for (Expression arg : args) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assign = (J.Assignment) arg;
                        String name = assign.getVariable() instanceof J.Identifier
                            ? ((J.Identifier) assign.getVariable()).getSimpleName()
                            : "";
                        if ("rollbackFor".equals(name)) {
                            hasRollbackFor = true;
                            extractClassArrayElements(assign.getAssignment(), existingRollbackForClasses);
                        } else if ("noRollbackFor".equals(name)) {
                            hasNoRollbackFor = true;
                            extractClassArrayElements(assign.getAssignment(), existingNoRollbackForClasses);
                        }
                    }
                }

                // Build new args list preserving existing ones
                List<Expression> newArgs = new ArrayList<>();
                boolean modified = false;

                for (Expression arg : args) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assign = (J.Assignment) arg;
                        String name = assign.getVariable() instanceof J.Identifier
                            ? ((J.Identifier) assign.getVariable()).getSimpleName()
                            : "";

                        if ("rollbackFor".equals(name) && !rollbackFor.isEmpty()) {
                            // Merge with existing rollbackFor - keep existing elements and add new ones
                            List<AppExceptionInfo> allInfos = new ArrayList<>();
                            List<String> existingFqns = new ArrayList<>();
                            for (Expression e : existingRollbackForClasses) {
                                existingFqns.add(extractClassName(e));  // Now returns FQN when possible
                            }
                            for (AppExceptionInfo info : rollbackFor) {
                                // Compare by FQN to avoid duplicates
                                if (!existingFqns.contains(info.fqn) && !existingFqns.contains(info.simpleName)) {
                                    allInfos.add(info);
                                    modified = true;
                                }
                            }
                            if (modified) {
                                // Build merged list - keep existing elements + add new ones
                                newArgs.add(mergeClassArrayAssignment(assign, allInfos));
                            } else {
                                newArgs.add(arg);
                            }
                        } else if ("noRollbackFor".equals(name) && !noRollbackFor.isEmpty()) {
                            // Merge with existing noRollbackFor
                            List<AppExceptionInfo> allInfos = new ArrayList<>();
                            List<String> existingFqns = new ArrayList<>();
                            for (Expression e : existingNoRollbackForClasses) {
                                existingFqns.add(extractClassName(e));  // Now returns FQN when possible
                            }
                            for (AppExceptionInfo info : noRollbackFor) {
                                // Compare by FQN to avoid duplicates
                                if (!existingFqns.contains(info.fqn) && !existingFqns.contains(info.simpleName)) {
                                    allInfos.add(info);
                                    modified = true;
                                }
                            }
                            if (modified) {
                                newArgs.add(mergeClassArrayAssignment(assign, allInfos));
                            } else {
                                newArgs.add(arg);
                            }
                        } else {
                            newArgs.add(arg);
                        }
                    } else {
                        newArgs.add(arg);
                    }
                }

                // Add new attributes that didn't exist before
                if (!hasRollbackFor && !rollbackFor.isEmpty()) {
                    // Adding after existing args, so needs leading comma space
                    newArgs.add(createClassArrayAssignmentFromInfos("rollbackFor", rollbackFor, !args.isEmpty()));
                    modified = true;
                }

                if (!hasNoRollbackFor && !noRollbackFor.isEmpty()) {
                    // Adding after existing args, so needs leading comma space
                    newArgs.add(createClassArrayAssignmentFromInfos("noRollbackFor", noRollbackFor, !args.isEmpty() || !rollbackFor.isEmpty()));
                    modified = true;
                }

                if (modified) {
                    return annotation.withArguments(newArgs);
                }
                return annotation;
            }

            private Expression mergeClassArrayAssignment(J.Assignment existing, List<AppExceptionInfo> newInfos) {
                // Keep existing assignment but add new classes to the value
                Expression value = existing.getAssignment();
                List<JRightPadded<Expression>> classExprs = new ArrayList<>();

                // Extract existing class expressions
                if (value instanceof J.NewArray) {
                    J.NewArray arr = (J.NewArray) value;
                    if (arr.getInitializer() != null) {
                        for (Expression e : arr.getInitializer()) {
                            classExprs.add(new JRightPadded<>(e, Space.EMPTY, Markers.EMPTY));
                        }
                    }
                } else if (value instanceof J.FieldAccess || value instanceof J.Identifier) {
                    classExprs.add(new JRightPadded<>(value, Space.EMPTY, Markers.EMPTY));
                }

                // Add new class expressions
                for (AppExceptionInfo info : newInfos) {
                    J.FieldAccess classAccess = createClassFieldAccess(info, true);
                    classExprs.add(new JRightPadded<>(classAccess, Space.EMPTY, Markers.EMPTY));
                }

                // Build new value
                Expression newValue;
                if (classExprs.size() == 1) {
                    newValue = classExprs.get(0).getElement().withPrefix(Space.format(" "));
                } else {
                    JContainer<Expression> initializer = JContainer.build(
                        Space.EMPTY,
                        classExprs,
                        Markers.EMPTY
                    );
                    newValue = new J.NewArray(
                        Tree.randomId(),
                        Space.format(" "),
                        Markers.EMPTY,
                        null,
                        Collections.emptyList(),
                        initializer,
                        null
                    );
                }

                return existing.withAssignment(newValue);
            }

            private void extractClassArrayElements(Expression expr, List<Expression> result) {
                if (expr instanceof J.NewArray) {
                    J.NewArray arr = (J.NewArray) expr;
                    if (arr.getInitializer() != null) {
                        result.addAll(arr.getInitializer());
                    }
                } else if (expr instanceof J.FieldAccess) {
                    result.add(expr);
                } else if (expr instanceof J.Identifier) {
                    result.add(expr);
                }
            }

            /**
             * Extract class name from expression, preferring FQN when type info is available.
             * Handles both simple (Ex.class) and fully-qualified (com.foo.Ex.class) forms.
             */
            private String extractClassName(Expression expr) {
                if (expr instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) expr;
                    // Check if this is a .class access
                    if ("class".equals(fa.getName().getSimpleName())) {
                        // Try to get FQN from the type of the overall expression
                        JavaType type = fa.getType();
                        if (type instanceof JavaType.Parameterized) {
                            JavaType.Parameterized paramType = (JavaType.Parameterized) type;
                            if (!paramType.getTypeParameters().isEmpty()) {
                                JavaType typeParam = paramType.getTypeParameters().get(0);
                                if (typeParam instanceof JavaType.FullyQualified) {
                                    return ((JavaType.FullyQualified) typeParam).getFullyQualifiedName();
                                }
                            }
                        }
                        // Walk the target chain to build FQN for fully-qualified class literals
                        // e.g., com.foo.Ex.class -> com.foo.Ex
                        return extractClassNameFromTarget(fa.getTarget());
                    }
                    // Regular field access (not .class)
                    if (fa.getTarget() instanceof J.Identifier) {
                        J.Identifier target = (J.Identifier) fa.getTarget();
                        JavaType.FullyQualified type = TypeUtils.asFullyQualified(target.getType());
                        if (type != null) {
                            return type.getFullyQualifiedName();
                        }
                        return target.getSimpleName();
                    }
                }
                if (expr instanceof J.Identifier) {
                    J.Identifier ident = (J.Identifier) expr;
                    JavaType.FullyQualified type = TypeUtils.asFullyQualified(ident.getType());
                    if (type != null) {
                        return type.getFullyQualifiedName();
                    }
                    return ident.getSimpleName();
                }
                return expr.toString();
            }

            /**
             * Extract class name from the target of a .class access.
             * Handles nested J.FieldAccess for fully-qualified names like com.foo.Ex
             */
            private String extractClassNameFromTarget(Expression target) {
                // First try type attribution
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(target.getType());
                if (type != null) {
                    return type.getFullyQualifiedName();
                }

                // Fall back to building name from AST structure
                if (target instanceof J.Identifier) {
                    return ((J.Identifier) target).getSimpleName();
                }
                if (target instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) target;
                    String prefix = extractClassNameFromTarget(fa.getTarget());
                    return prefix + "." + fa.getName().getSimpleName();
                }
                return target.toString();
            }

            private J.Annotation createTransactionalAnnotation(
                    List<AppExceptionInfo> rollbackFor,
                    List<AppExceptionInfo> noRollbackFor) {

                JavaType.ShallowClass txType = JavaType.ShallowClass.build(SPRING_TRANSACTIONAL);
                J.Identifier txIdent = new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "Transactional",
                    txType,
                    null
                );

                List<JRightPadded<Expression>> args = new ArrayList<>();

                if (!rollbackFor.isEmpty()) {
                    args.add(createClassArrayAssignmentPadded("rollbackFor", rollbackFor, args.isEmpty()));
                }

                if (!noRollbackFor.isEmpty()) {
                    args.add(createClassArrayAssignmentPadded("noRollbackFor", noRollbackFor, args.isEmpty()));
                }

                JContainer<Expression> argsContainer = JContainer.build(
                    Space.EMPTY,
                    args,
                    Markers.EMPTY
                );

                return new J.Annotation(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    txIdent,
                    argsContainer
                );
            }

            private JRightPadded<Expression> createClassArrayAssignmentPadded(String name, List<AppExceptionInfo> infos, boolean isFirst) {
                Expression assignment = createClassArrayAssignmentFromInfos(name, infos, !isFirst);
                return new JRightPadded<>(assignment, Space.EMPTY, Markers.EMPTY);
            }

            private Expression createClassArrayAssignmentFromInfos(String name, List<AppExceptionInfo> infos, boolean hasLeadingArgs) {
                // If there are leading args, we need a space before this assignment (after the comma)
                J.Identifier nameIdent = new J.Identifier(
                    Tree.randomId(),
                    hasLeadingArgs ? Space.format(" ") : Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    name,
                    null,
                    null
                );

                List<JRightPadded<Expression>> classExprs = new ArrayList<>();
                for (int i = 0; i < infos.size(); i++) {
                    AppExceptionInfo info = infos.get(i);
                    J.FieldAccess classAccess = createClassFieldAccess(info, i > 0);
                    classExprs.add(new JRightPadded<>(classAccess, Space.EMPTY, Markers.EMPTY));
                }

                // For single class, don't use array syntax
                Expression value;
                if (classExprs.size() == 1) {
                    // Space after '=' goes on the value's prefix
                    value = classExprs.get(0).getElement().withPrefix(Space.format(" "));
                } else {
                    // Use array initializer for multiple classes, space after '=' on array prefix
                    JContainer<Expression> initializer = JContainer.build(
                        Space.EMPTY,
                        classExprs,
                        Markers.EMPTY
                    );
                    value = new J.NewArray(
                        Tree.randomId(),
                        Space.format(" "),  // Space after '='
                        Markers.EMPTY,
                        null,
                        Collections.emptyList(),
                        initializer,
                        null
                    );
                }

                // Create Assignment with proper spacing: space before '=' from JLeftPadded.before
                return new J.Assignment(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    nameIdent,
                    JLeftPadded.build(value).withBefore(Space.format(" ")),  // space before =
                    null
                );
            }

            private J.FieldAccess createClassFieldAccess(AppExceptionInfo info, boolean hasLeadingElement) {
                // Create properly typed ClassName.class expression
                JavaType.FullyQualified exceptionType = JavaType.ShallowClass.build(info.fqn);

                J.Identifier classIdent = new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    info.simpleName,
                    exceptionType,  // Proper type attribution
                    null
                );

                J.Identifier classLiteral = new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "class",
                    JavaType.Primitive.Void,  // "class" keyword type
                    null
                );

                return new J.FieldAccess(
                    Tree.randomId(),
                    // For array elements after first, add space after comma
                    hasLeadingElement ? Space.format(" ") : Space.EMPTY,
                    Markers.EMPTY,
                    classIdent,
                    JLeftPadded.build(classLiteral),
                    JavaType.buildType("java.lang.Class")  // Type of the .class expression
                );
            }

            private boolean isTransactionalAnnotation(J.Annotation annotation) {
                return "Transactional".equals(annotation.getSimpleName()) ||
                       TypeUtils.isOfClassType(annotation.getType(), SPRING_TRANSACTIONAL);
            }

            private String extractIndent(String whitespace) {
                if (whitespace == null || whitespace.isEmpty()) {
                    return "";
                }
                // Find the last newline and return everything after it
                int lastNewline = whitespace.lastIndexOf('\n');
                if (lastNewline >= 0) {
                    return whitespace.substring(lastNewline + 1);
                }
                return whitespace;
            }
        };
    }

    private static boolean isApplicationExceptionAnnotation(J.Annotation annotation) {
        return "ApplicationException".equals(annotation.getSimpleName()) ||
               TypeUtils.isOfClassType(annotation.getType(), JAKARTA_APP_EXCEPTION) ||
               TypeUtils.isOfClassType(annotation.getType(), JAVAX_APP_EXCEPTION);
    }
}
