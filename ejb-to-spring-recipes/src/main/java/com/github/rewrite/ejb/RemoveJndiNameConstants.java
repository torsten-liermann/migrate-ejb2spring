package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * JUS-003: Removes unused JNDI_NAME, JNDI_LOCAL_NAME and JNDI_GLOBAL_NAME constants from interfaces.
 * <p>
 * This is a ScanningRecipe with two phases:
 * <ol>
 *   <li>Scanner: Tracks definitions (where constants are declared) and usages (where they're referenced)</li>
 *   <li>Visitor: Removes constants with no usages, marks constants with usages using @NeedsReview</li>
 * </ol>
 * <p>
 * Safety guarantees:
 * <ul>
 *   <li>FQN-based tracking: Keys are "com.example.IFoo#JNDI_NAME" to avoid collisions</li>
 *   <li>Separate definition/usage maps: No heuristics, only remove when usageCount == 0</li>
 *   <li>Type resolution required: If interface type is unresolved, mark with @NeedsReview</li>
 *   <li>Multi-var handling: Splits declarations when needed to annotate specific constants</li>
 * </ul>
 * <p>
 * Pipeline ordering: This recipe should run AFTER RemoveStatelessNameAttribute (JUS-004)
 * which removes the main usages of these constants (e.g., @Stateless(name=IFoo.JNDI_NAME)).
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveJndiNameConstants extends ScanningRecipe<RemoveJndiNameConstants.JndiConstantTracker> {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final Set<String> JNDI_CONSTANT_NAMES = Set.of("JNDI_NAME", "JNDI_LOCAL_NAME", "JNDI_GLOBAL_NAME");

    @Override
    public String getDisplayName() {
        return "Remove unused JNDI_NAME constants";
    }

    @Override
    public String getDescription() {
        return "Removes JNDI_NAME, JNDI_LOCAL_NAME and JNDI_GLOBAL_NAME constants from interfaces when they have no usages. " +
               "Constants with usages are marked with @NeedsReview for manual migration. " +
               "Should run after RemoveStatelessNameAttribute (JUS-004) to maximize automatic removal.";
    }

    /**
     * Accumulator to track definitions and usages of JNDI constants across all source files.
     */
    static class JndiConstantTracker {
        // Key: "com.example.IFooHome#JNDI_NAME"
        final Map<String, FieldDefinition> definitions = new ConcurrentHashMap<>();
        final Map<String, AtomicInteger> usages = new ConcurrentHashMap<>();

        // Track simple names that have unresolved usages.
        // If we see an unresolved reference to "JNDI_NAME", we must be conservative
        // and not remove ANY constant with that simple name.
        final Set<String> unresolvedUsageSimpleNames = ConcurrentHashMap.newKeySet();

        static class FieldDefinition {
            final String fqn;           // Full key: "com.example.IFoo#JNDI_NAME"
            final String simpleName;    // "JNDI_NAME"
            final boolean multiVar;     // true if declaration has multiple variables

            FieldDefinition(String fqn, String simpleName, boolean multiVar) {
                this.fqn = fqn;
                this.simpleName = simpleName;
                this.multiVar = multiVar;
            }
        }
    }

    @Override
    public JndiConstantTracker getInitialValue(ExecutionContext ctx) {
        return new JndiConstantTracker();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(JndiConstantTracker acc) {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext ctx) {
                J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
                if (enclosing == null || enclosing.getKind() != J.ClassDeclaration.Kind.Type.Interface) {
                    return super.visitVariableDeclarations(vd, ctx);
                }

                JavaType.FullyQualified interfaceType = TypeUtils.asFullyQualified(enclosing.getType());
                if (interfaceType == null) {
                    // Track unresolved definitions with a special key
                    String unresolvedKey = "UNRESOLVED#" + enclosing.getSimpleName();
                    boolean multiVar = vd.getVariables().size() > 1;
                    for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                        if (JNDI_CONSTANT_NAMES.contains(nv.getSimpleName())) {
                            String key = unresolvedKey + "#" + nv.getSimpleName();
                            acc.definitions.put(key, new JndiConstantTracker.FieldDefinition(key, nv.getSimpleName(), multiVar));
                            // For unresolved, we mark usages as -1 (unknown)
                            acc.usages.put(key, new AtomicInteger(-1));
                        }
                    }
                    return super.visitVariableDeclarations(vd, ctx);
                }

                boolean multiVar = vd.getVariables().size() > 1;

                for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                    if (JNDI_CONSTANT_NAMES.contains(nv.getSimpleName())) {
                        String key = interfaceType.getFullyQualifiedName() + "#" + nv.getSimpleName();
                        acc.definitions.put(key, new JndiConstantTracker.FieldDefinition(key, nv.getSimpleName(), multiVar));
                        acc.usages.putIfAbsent(key, new AtomicInteger(0)); // Init with 0 usages
                    }
                }
                return super.visitVariableDeclarations(vd, ctx);
            }

            @Override
            public J.FieldAccess visitFieldAccess(J.FieldAccess fa, ExecutionContext ctx) {
                if (JNDI_CONSTANT_NAMES.contains(fa.getSimpleName())) {
                    JavaType.Variable fieldType = fa.getName().getFieldType();
                    if (fieldType != null && fieldType.getOwner() != null) {
                        JavaType.FullyQualified owner = TypeUtils.asFullyQualified(fieldType.getOwner());
                        if (owner != null) {
                            String key = owner.getFullyQualifiedName() + "#" + fa.getSimpleName();
                            acc.usages.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                        } else {
                            // Unresolved owner - track this simple name as having unresolved usage
                            acc.unresolvedUsageSimpleNames.add(fa.getSimpleName());
                        }
                    } else {
                        // Unresolved field type - track this simple name as having unresolved usage
                        acc.unresolvedUsageSimpleNames.add(fa.getSimpleName());
                    }
                }
                return super.visitFieldAccess(fa, ctx);
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier ident, ExecutionContext ctx) {
                if (JNDI_CONSTANT_NAMES.contains(ident.getSimpleName())) {
                    // Skip if this identifier is the name of a variable declaration (not a usage)
                    if (getCursor().getParent() != null &&
                        getCursor().getParent().getValue() instanceof J.VariableDeclarations.NamedVariable) {
                        return super.visitIdentifier(ident, ctx);
                    }

                    // Skip if this identifier is part of a FieldAccess (already counted in visitFieldAccess)
                    // Check up the cursor path for FieldAccess since it might be wrapped in JLeftPadded
                    if (getCursor().firstEnclosing(J.FieldAccess.class) != null) {
                        return super.visitIdentifier(ident, ctx);
                    }

                    JavaType.Variable fieldType = ident.getFieldType();
                    if (fieldType != null && fieldType.getOwner() != null) {
                        JavaType.FullyQualified owner = TypeUtils.asFullyQualified(fieldType.getOwner());
                        if (owner != null) {
                            String key = owner.getFullyQualifiedName() + "#" + ident.getSimpleName();
                            acc.usages.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                        } else {
                            // Unresolved owner - track this simple name as having unresolved usage
                            acc.unresolvedUsageSimpleNames.add(ident.getSimpleName());
                        }
                    } else {
                        // Unresolved field type - track this simple name as having unresolved usage
                        acc.unresolvedUsageSimpleNames.add(ident.getSimpleName());
                    }
                }
                return super.visitIdentifier(ident, ctx);
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(JndiConstantTracker tracker) {
        return new JndiConstantVisitor(tracker);
    }

    private class JndiConstantVisitor extends JavaIsoVisitor<ExecutionContext> {
        private final JndiConstantTracker tracker;

        JndiConstantVisitor(JndiConstantTracker tracker) {
            this.tracker = tracker;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            if (classDecl.getKind() != J.ClassDeclaration.Kind.Type.Interface) {
                return super.visitClassDeclaration(classDecl, ctx);
            }

            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Check if we need to split any multi-var declarations
            JavaType.FullyQualified interfaceType = TypeUtils.asFullyQualified(classDecl.getType());
            if (interfaceType == null) {
                // Unresolved type: just mark all JNDI constants with @NeedsReview
                return cd;
            }

            // Find multi-var declarations that need splitting
            List<Statement> newStatements = new ArrayList<>();
            boolean bodyChanged = false;

            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;

                    // Check if this is a multi-var declaration with JNDI constants that have usages OR unresolved usages
                    List<J.VariableDeclarations.NamedVariable> jndiVarsNeedingReview = vd.getVariables().stream()
                        .filter(nv -> JNDI_CONSTANT_NAMES.contains(nv.getSimpleName()))
                        .filter(nv -> {
                            String key = interfaceType.getFullyQualifiedName() + "#" + nv.getSimpleName();
                            int usageCount = tracker.usages.getOrDefault(key, new AtomicInteger(0)).get();
                            boolean hasUnresolvedUsage = tracker.unresolvedUsageSimpleNames.contains(nv.getSimpleName());
                            return usageCount > 0 || hasUnresolvedUsage;
                        })
                        .collect(Collectors.toList());

                    if (vd.getVariables().size() > 1 && !jndiVarsNeedingReview.isEmpty()) {
                        // Need to split: create separate declarations
                        bodyChanged = true;
                        for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                            J.VariableDeclarations singleVarDecl = vd.withVariables(Collections.singletonList(nv));

                            // Add @NeedsReview to JNDI vars with usages or unresolved usages
                            if (JNDI_CONSTANT_NAMES.contains(nv.getSimpleName())) {
                                String key = interfaceType.getFullyQualifiedName() + "#" + nv.getSimpleName();
                                int usageCount = tracker.usages.getOrDefault(key, new AtomicInteger(0)).get();
                                boolean hasUnresolvedUsage = tracker.unresolvedUsageSimpleNames.contains(nv.getSimpleName());

                                if (usageCount > 0 || hasUnresolvedUsage) {
                                    String reason;
                                    if (hasUnresolvedUsage && usageCount == 0) {
                                        reason = "JNDI constant has unresolved usages - manual verification required";
                                    } else if (hasUnresolvedUsage) {
                                        reason = "JNDI constant has " + usageCount + " resolved + unresolved usage(s) - manual migration required";
                                    } else {
                                        reason = "JNDI constant has " + usageCount + " usage(s) - manual migration required";
                                    }
                                    singleVarDecl = addNeedsReviewAnnotation(singleVarDecl, reason);
                                    maybeAddImport(NEEDS_REVIEW_FQN);
                                }
                            }
                            newStatements.add(singleVarDecl);
                        }
                    } else {
                        newStatements.add(stmt);
                    }
                } else {
                    newStatements.add(stmt);
                }
            }

            if (bodyChanged) {
                cd = cd.withBody(cd.getBody().withStatements(newStatements));
            }

            return cd;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, ExecutionContext ctx) {
            J.ClassDeclaration enclosing = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if (enclosing == null || enclosing.getKind() != J.ClassDeclaration.Kind.Type.Interface) {
                return super.visitVariableDeclarations(vd, ctx);
            }

            // Check if any variable is a JNDI constant
            List<J.VariableDeclarations.NamedVariable> jndiVars = vd.getVariables().stream()
                .filter(nv -> JNDI_CONSTANT_NAMES.contains(nv.getSimpleName()))
                .collect(Collectors.toList());

            if (jndiVars.isEmpty()) {
                return super.visitVariableDeclarations(vd, ctx);
            }

            JavaType.FullyQualified interfaceType = TypeUtils.asFullyQualified(enclosing.getType());
            boolean multiVar = vd.getVariables().size() > 1;

            // CASE 1: Type unresolved → conservative @NeedsReview
            if (interfaceType == null) {
                // Check if already has @NeedsReview
                boolean hasNeedsReview = vd.getLeadingAnnotations().stream()
                    .anyMatch(a -> "NeedsReview".equals(a.getSimpleName()));
                if (!hasNeedsReview) {
                    maybeAddImport(NEEDS_REVIEW_FQN);
                    return addNeedsReviewAnnotation(vd,
                        "JNDI constant in interface with unresolved type - manual usage verification required");
                }
                return vd;
            }

            // Check if already has @NeedsReview (don't remove if already marked)
            boolean hasExistingNeedsReview = vd.getLeadingAnnotations().stream()
                .anyMatch(a -> "NeedsReview".equals(a.getSimpleName()));

            // CASE 2: Type resolved → check usages
            List<J.VariableDeclarations.NamedVariable> remaining = new ArrayList<>();
            boolean changed = false;
            boolean hasUsedJndiConstant = false;

            for (J.VariableDeclarations.NamedVariable nv : vd.getVariables()) {
                String name = nv.getSimpleName();
                if (JNDI_CONSTANT_NAMES.contains(name)) {
                    String key = interfaceType.getFullyQualifiedName() + "#" + name;
                    int usageCount = tracker.usages.getOrDefault(key, new AtomicInteger(0)).get();

                    // Check if there are unresolved usages for this simple name
                    // If yes, we must be conservative and NOT remove
                    boolean hasUnresolvedUsage = tracker.unresolvedUsageSimpleNames.contains(name);

                    if (usageCount == 0 && !hasExistingNeedsReview && !hasUnresolvedUsage) {
                        // NO usages, no unresolved usages, not already marked → REMOVE
                        changed = true;
                        // Don't add to remaining
                    } else {
                        // HAS usages OR has unresolved usages → KEEP (will be annotated below if single-var)
                        hasUsedJndiConstant = true;
                        remaining.add(nv);
                    }
                } else {
                    remaining.add(nv);
                }
            }

            // Apply transformations
            if (changed) {
                if (remaining.isEmpty()) {
                    // Remove entire declaration
                    //noinspection DataFlowIssue
                    return null;
                }
                vd = vd.withVariables(remaining);
            }

            // Add @NeedsReview for single-var declarations with usages or unresolved usages
            if (!multiVar && hasUsedJndiConstant) {
                boolean hasNeedsReview = vd.getLeadingAnnotations().stream()
                    .anyMatch(a -> "NeedsReview".equals(a.getSimpleName()));
                if (!hasNeedsReview) {
                    String simpleName = jndiVars.get(0).getSimpleName();
                    String key = interfaceType.getFullyQualifiedName() + "#" + simpleName;
                    int usageCount = tracker.usages.getOrDefault(key, new AtomicInteger(0)).get();
                    boolean hasUnresolvedUsage = tracker.unresolvedUsageSimpleNames.contains(simpleName);

                    String reason;
                    if (hasUnresolvedUsage && usageCount == 0) {
                        reason = "JNDI constant has unresolved usages - manual verification required";
                    } else if (hasUnresolvedUsage) {
                        reason = "JNDI constant has " + usageCount + " resolved + unresolved usage(s) - manual migration required";
                    } else {
                        reason = "JNDI constant has " + usageCount + " usage(s) - manual migration required";
                    }

                    maybeAddImport(NEEDS_REVIEW_FQN);
                    vd = addNeedsReviewAnnotation(vd, reason);
                }
            }

            return vd;
        }

        private J.VariableDeclarations addNeedsReviewAnnotation(J.VariableDeclarations vd, String reason) {
            // Get the original prefix (space before the field, includes newline and indent)
            Space originalPrefix = vd.getPrefix();

            // Create annotation with the original prefix (positions it correctly)
            J.Annotation needsReview = createNeedsReviewAnnotation(reason, originalPrefix);

            List<J.Annotation> newAnnotations = new ArrayList<>();
            newAnnotations.add(needsReview);

            // Add existing annotations (if any) with their original prefixes
            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                newAnnotations.add(ann);
            }

            // Extract indent from original prefix
            String ws = originalPrefix.getWhitespace();
            String indent = "";
            if (ws.contains("\n")) {
                indent = ws.substring(ws.lastIndexOf('\n') + 1);
            }
            Space typePrefix = Space.format("\n" + indent);

            // Set vd.prefix to EMPTY since the annotation's prefix handles the positioning
            J.VariableDeclarations result = vd.withLeadingAnnotations(newAnnotations)
                                              .withPrefix(Space.EMPTY);

            // The type expression needs newline+indent prefix to appear on the next line
            if (result.getTypeExpression() != null) {
                result = result.withTypeExpression(
                    result.getTypeExpression().withPrefix(typePrefix)
                );
            }

            return result;
        }

        private J.Annotation createNeedsReviewAnnotation(String reason, Space prefix) {
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
            arguments.add(createCategoryArg("OTHER"));

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
                "\"" + value + "\"",
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
}
