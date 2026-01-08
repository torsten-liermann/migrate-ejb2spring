package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.AnnotationMatcher;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JLeftPadded;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Space;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Converts JBoss @TransactionTimeout to Spring @Transactional(timeout = ...seconds).
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ConvertJBossTransactionTimeout extends Recipe {

    private static final String JBOSS_TIMEOUT_FQN = "org.jboss.ejb3.annotation.TransactionTimeout";
    private static final String TRANSACTIONAL_FQN = "org.springframework.transaction.annotation.Transactional";
    private static final String TIME_UNIT_FQN = "java.util.concurrent.TimeUnit";
    private static final String SIMPLE_NAME = "TransactionTimeout";
    private static final AnnotationMatcher TIMEOUT_MATCHER = new AnnotationMatcher("@" + JBOSS_TIMEOUT_FQN);

    private static final Map<String, TimeUnit> TIME_UNITS = Map.of(
        "NANOSECONDS", TimeUnit.NANOSECONDS,
        "MICROSECONDS", TimeUnit.MICROSECONDS,
        "MILLISECONDS", TimeUnit.MILLISECONDS,
        "SECONDS", TimeUnit.SECONDS,
        "MINUTES", TimeUnit.MINUTES,
        "HOURS", TimeUnit.HOURS,
        "DAYS", TimeUnit.DAYS
    );

    @Override
    public String getDisplayName() {
        return "Convert JBoss @TransactionTimeout to Spring @Transactional(timeout=...)";
    }

    @Override
    public String getDescription() {
        return "Replaces JBoss @TransactionTimeout with Spring @Transactional(timeout=...) in seconds.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new TransactionTimeoutVisitor();
    }

    private class TransactionTimeoutVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
            UpdateResult result = updateAnnotations(cd.getLeadingAnnotations());
            if (!result.changed) {
                return cd;
            }
            return cd.withLeadingAnnotations(result.annotations);
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);
            UpdateResult result = updateAnnotations(md.getLeadingAnnotations());
            if (!result.changed) {
                return md;
            }
            return md.withLeadingAnnotations(result.annotations);
        }

        private UpdateResult updateAnnotations(List<J.Annotation> annotations) {
            int timeoutIndex = -1;
            int transactionalIndex = -1;
            for (int i = 0; i < annotations.size(); i++) {
                J.Annotation ann = annotations.get(i);
                if (timeoutIndex < 0 && isTransactionTimeout(ann)) {
                    timeoutIndex = i;
                    continue;
                }
                if (transactionalIndex < 0 && isTransactional(ann)) {
                    transactionalIndex = i;
                }
            }

            if (timeoutIndex < 0) {
                return UpdateResult.noop();
            }

            J.Annotation timeoutAnn = annotations.get(timeoutIndex);
            Expression timeoutExpr = buildTimeoutExpression(timeoutAnn);
            if (timeoutExpr == null) {
                return UpdateResult.noop();
            }

            List<J.Annotation> updated = new ArrayList<>(annotations);
            maybeAddImport(TRANSACTIONAL_FQN);
            maybeRemoveImport(JBOSS_TIMEOUT_FQN);
            maybeRemoveImport(TIME_UNIT_FQN);

            if (transactionalIndex >= 0) {
                J.Annotation existing = updated.get(transactionalIndex);
                J.Annotation withTimeout = applyTimeout(existing, timeoutExpr);
                updated.set(transactionalIndex, withTimeout);
                updated.remove(timeoutIndex);
            } else {
                J.Annotation replacement = buildTransactionalAnnotation(timeoutAnn, timeoutExpr);
                updated.set(timeoutIndex, replacement);
            }

            return new UpdateResult(updated, true);
        }

        private boolean isTransactionTimeout(J.Annotation annotation) {
            if (TIMEOUT_MATCHER.matches(annotation)) {
                return true;
            }
            if (annotation.getType() != null) {
                return TypeUtils.isOfClassType(annotation.getType(), JBOSS_TIMEOUT_FQN);
            }
            if (annotation.getAnnotationType() instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) annotation.getAnnotationType();
                return SIMPLE_NAME.equals(ident.getSimpleName());
            }
            if (annotation.getAnnotationType() instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) annotation.getAnnotationType();
                return SIMPLE_NAME.equals(fa.getSimpleName()) && fa.toString().contains("jboss");
            }
            return false;
        }

        private boolean isTransactional(J.Annotation annotation) {
            if ("Transactional".equals(annotation.getSimpleName())) {
                return true;
            }
            return TypeUtils.isOfClassType(annotation.getType(), TRANSACTIONAL_FQN);
        }

        private @Nullable Expression buildTimeoutExpression(J.Annotation annotation) {
            Expression valueExpr = extractValueExpression(annotation);
            if (valueExpr == null) {
                return null;
            }

            TimeUnit unit = extractTimeUnit(annotation);
            if (unit == null) {
                unit = TimeUnit.SECONDS;
            }

            Integer constant = evaluateInt(valueExpr);
            if (constant != null) {
                long seconds = unit.toSeconds(constant.longValue());
                try {
                    int timeoutSeconds = Math.toIntExact(seconds);
                    return new J.Literal(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        timeoutSeconds,
                        String.valueOf(timeoutSeconds),
                        Collections.emptyList(),
                        JavaType.Primitive.Int
                    );
                } catch (ArithmeticException ignored) {
                    return null;
                }
            }

            if (unit == TimeUnit.SECONDS) {
                return valueExpr.withPrefix(Space.EMPTY);
            }

            return buildScaledExpression(valueExpr, unit);
        }

        private @Nullable Expression extractValueExpression(J.Annotation annotation) {
            List<Expression> args = annotation.getArguments();
            if (args == null || args.isEmpty()) {
                return null;
            }
            for (Expression arg : args) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assign = (J.Assignment) arg;
                    if (assign.getVariable() instanceof J.Identifier) {
                        if ("value".equals(((J.Identifier) assign.getVariable()).getSimpleName())) {
                            return assign.getAssignment();
                        }
                    }
                }
            }
            Expression first = args.get(0);
            if (first instanceof J.Empty) {
                return null;
            }
            if (first instanceof J.Assignment) {
                return ((J.Assignment) first).getAssignment();
            }
            return first;
        }

        private @Nullable TimeUnit extractTimeUnit(J.Annotation annotation) {
            List<Expression> args = annotation.getArguments();
            if (args == null || args.isEmpty()) {
                return TimeUnit.SECONDS;
            }
            for (Expression arg : args) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assign = (J.Assignment) arg;
                    if (assign.getVariable() instanceof J.Identifier) {
                        if ("unit".equals(((J.Identifier) assign.getVariable()).getSimpleName())) {
                            return resolveTimeUnit(assign.getAssignment());
                        }
                    }
                }
            }
            return TimeUnit.SECONDS;
        }

        private @Nullable TimeUnit resolveTimeUnit(Expression expr) {
            if (expr instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) expr;
                TimeUnit unit = TIME_UNITS.get(fa.getSimpleName());
                if (unit == null) {
                    return null;
                }
                Expression target = fa.getTarget();
                if (target instanceof J.Identifier &&
                    "TimeUnit".equals(((J.Identifier) target).getSimpleName())) {
                    return unit;
                }
                if (target instanceof J.FieldAccess &&
                    target.toString().endsWith("TimeUnit")) {
                    return unit;
                }
                return unit;
            }
            if (expr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) expr;
                TimeUnit unit = TIME_UNITS.get(ident.getSimpleName());
                if (unit == null) {
                    return null;
                }
                JavaType type = ident.getType();
                if (type instanceof JavaType.FullyQualified) {
                    String fqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
                    if (TIME_UNIT_FQN.equals(fqn)) {
                        return unit;
                    }
                }
                J.CompilationUnit cu = getCursor().firstEnclosing(J.CompilationUnit.class);
                if (cu != null && hasStaticTimeUnitImport(cu, ident.getSimpleName())) {
                    return unit;
                }
            }
            return null;
        }

        private boolean hasStaticTimeUnitImport(J.CompilationUnit cu, String simpleName) {
            for (J.Import imp : cu.getImports()) {
                if (!imp.isStatic()) {
                    continue;
                }
                String typeName = imp.getTypeName();
                if ((TIME_UNIT_FQN + "." + simpleName).equals(typeName) ||
                    (TIME_UNIT_FQN + ".*").equals(typeName) ||
                    TIME_UNIT_FQN.equals(typeName)) {
                    return true;
                }
            }
            return false;
        }

        private @Nullable Expression buildScaledExpression(Expression valueExpr, TimeUnit unit) {
            long factor;
            boolean divide;
            switch (unit) {
                case MINUTES:
                    factor = 60;
                    divide = false;
                    break;
                case HOURS:
                    factor = 3600;
                    divide = false;
                    break;
                case DAYS:
                    factor = 86400;
                    divide = false;
                    break;
                case MILLISECONDS:
                    factor = 1000;
                    divide = true;
                    break;
                case MICROSECONDS:
                    factor = 1_000_000;
                    divide = true;
                    break;
                case NANOSECONDS:
                    factor = 1_000_000_000L;
                    divide = true;
                    break;
                default:
                    return valueExpr.withPrefix(Space.EMPTY);
            }

            Expression left = parenthesizeIfNeeded(valueExpr);
            J.Literal right = new J.Literal(
                Tree.randomId(),
                Space.format(" "),
                Markers.EMPTY,
                factor,
                String.valueOf(factor),
                Collections.emptyList(),
                JavaType.Primitive.Int
            );

            JLeftPadded<J.Binary.Type> operator = JLeftPadded.build(
                divide ? J.Binary.Type.Division : J.Binary.Type.Multiplication
            ).withBefore(Space.format(" "));

            return new J.Binary(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                left.withPrefix(Space.EMPTY),
                operator,
                right,
                JavaType.Primitive.Int
            );
        }

        private Expression parenthesizeIfNeeded(Expression expr) {
            if (expr instanceof J.Literal || expr instanceof J.Identifier || expr instanceof J.FieldAccess) {
                return expr.withPrefix(Space.EMPTY);
            }
            return new J.Parentheses<>(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                JRightPadded.build(expr.withPrefix(Space.EMPTY))
            );
        }

        private J.Annotation buildTransactionalAnnotation(J.Annotation source, Expression timeoutExpr) {
            JavaType.ShallowClass type = JavaType.ShallowClass.build(TRANSACTIONAL_FQN);
            J.Identifier ident = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Transactional",
                type,
                null
            );
            List<Expression> args = new ArrayList<>();
            args.add(buildTimeoutAssignment(timeoutExpr, Space.EMPTY));
            return source
                .withAnnotationType(ident)
                .withArguments(args);
        }

        private J.Annotation applyTimeout(J.Annotation existing, Expression timeoutExpr) {
            List<Expression> args = existing.getArguments();
            List<Expression> updatedArgs = new ArrayList<>();
            boolean replaced = false;

            if (args != null) {
                for (Expression arg : args) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assign = (J.Assignment) arg;
                        if (assign.getVariable() instanceof J.Identifier) {
                            if ("timeout".equals(((J.Identifier) assign.getVariable()).getSimpleName())) {
                                updatedArgs.add(buildTimeoutAssignment(timeoutExpr, assign.getPrefix()));
                                replaced = true;
                                continue;
                            }
                        }
                    }
                    updatedArgs.add(arg);
                }
            }

            if (!replaced) {
                boolean hasArgs = args != null && !args.isEmpty() && !(args.size() == 1 && args.get(0) instanceof J.Empty);
                Space prefix = hasArgs ? Space.format(" ") : Space.EMPTY;
                updatedArgs.add(buildTimeoutAssignment(timeoutExpr, prefix));
            }

            return existing.withArguments(updatedArgs.isEmpty() ? null : updatedArgs);
        }

        private J.Assignment buildTimeoutAssignment(Expression timeoutExpr, Space prefix) {
            J.Identifier key = new J.Identifier(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                Collections.emptyList(),
                "timeout",
                null,
                null
            );
            return new J.Assignment(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                key,
                JLeftPadded.<Expression>build(timeoutExpr.withPrefix(Space.format(" ")))
                    .withBefore(Space.format(" ")),
                null
            );
        }

        private @Nullable Integer evaluateInt(Expression expr) {
            if (expr instanceof J.Parentheses) {
                J tree = ((J.Parentheses<?>) expr).getTree();
                if (tree instanceof Expression) {
                    return evaluateInt((Expression) tree);
                }
                return null;
            }
            if (expr instanceof J.Literal) {
                Object value = ((J.Literal) expr).getValue();
                if (value instanceof Integer) {
                    return (Integer) value;
                }
                if (value instanceof Long) {
                    long longVal = (Long) value;
                    if (longVal > Integer.MAX_VALUE || longVal < Integer.MIN_VALUE) {
                        return null;
                    }
                    return (int) longVal;
                }
            }
            if (expr instanceof J.Unary) {
                J.Unary unary = (J.Unary) expr;
                Integer val = evaluateInt(unary.getExpression());
                if (val == null) {
                    return null;
                }
                if (unary.getOperator() == J.Unary.Type.Negative) {
                    return -val;
                }
                return val;
            }
            if (expr instanceof J.Binary) {
                J.Binary binary = (J.Binary) expr;
                Integer left = evaluateInt(binary.getLeft());
                Integer right = evaluateInt(binary.getRight());
                if (left == null || right == null) {
                    return null;
                }
                switch (binary.getOperator()) {
                    case Addition:
                        return left + right;
                    case Subtraction:
                        return left - right;
                    case Multiplication:
                        return left * right;
                    case Division:
                        if (right == 0) {
                            return null;
                        }
                        return left / right;
                    default:
                        return null;
                }
            }
            return null;
        }
    }

    private static class UpdateResult {
        final List<J.Annotation> annotations;
        final boolean changed;

        UpdateResult(List<J.Annotation> annotations, boolean changed) {
            this.annotations = annotations;
            this.changed = changed;
        }

        static UpdateResult noop() {
            return new UpdateResult(Collections.emptyList(), false);
        }
    }
}
