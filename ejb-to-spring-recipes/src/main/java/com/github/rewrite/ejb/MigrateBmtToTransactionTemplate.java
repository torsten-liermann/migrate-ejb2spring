package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;

import java.util.*;

/**
 * MKR-006: Classifies and marks Bean-Managed Transaction patterns with specific transformation guidance.
 * <p>
 * Detects UserTransaction usage patterns and adds @NeedsReview with pattern-specific guidance:
 * <ul>
 *   <li>LINEAR: Simple try-begin-commit-catch-rollback → specific TransactionTemplate guidance</li>
 *   <li>COMPLEX: Multiple transactions, loops, conditionals → manual analysis required</li>
 * </ul>
 * <p>
 * Linear patterns are identified for easy manual transformation:
 * <pre>
 * // Linear Pattern (safe to transform):
 * try {
 *     utx.begin();
 *     // business logic
 *     utx.commit();
 * } catch (Exception e) {
 *     utx.rollback();
 *     throw new RuntimeException(e);
 * }
 *
 * // Becomes:
 * transactionTemplate.executeWithoutResult(status -> {
 *     // business logic
 * });
 * </pre>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateBmtToTransactionTemplate extends Recipe {

    private static final String JAKARTA_USER_TX = "jakarta.transaction.UserTransaction";
    private static final String JAVAX_USER_TX = "javax.transaction.UserTransaction";
    private static final String JAKARTA_SESSION_CTX = "jakarta.ejb.SessionContext";
    private static final String JAVAX_SESSION_CTX = "javax.ejb.SessionContext";
    private static final String JAKARTA_EJB_CTX = "jakarta.ejb.EJBContext";
    private static final String JAVAX_EJB_CTX = "javax.ejb.EJBContext";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Classify BMT UserTransaction patterns for migration";
    }

    @Override
    public String getDescription() {
        return "Classifies Bean-Managed Transaction patterns and adds @NeedsReview with pattern-specific " +
               "transformation guidance. Linear patterns receive specific TransactionTemplate migration steps; " +
               "complex patterns are flagged for manual analysis.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_USER_TX, false),
                new UsesType<>(JAVAX_USER_TX, false),
                new UsesType<>(JAKARTA_SESSION_CTX, false),
                new UsesType<>(JAVAX_SESSION_CTX, false),
                new UsesType<>(JAKARTA_EJB_CTX, false),
                new UsesType<>(JAVAX_EJB_CTX, false)
            ),
            new BmtClassifierVisitor()
        );
    }

    private static class BmtClassifierVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // Check if this class has any UserTransaction or EJBContext/SessionContext fields
            Set<String> utxFieldNames = findAllUtxFieldNames(classDecl);
            Set<String> contextFieldNames = findAllContextFieldNames(classDecl);

            // Already has @NeedsReview? Skip
            if (hasNeedsReviewAnnotation(classDecl)) {
                return super.visitClassDeclaration(classDecl, ctx);
            }

            // Analyze patterns in all methods (also checks for context params and local vars)
            PatternAnalysis analysis = analyzeClass(classDecl, utxFieldNames, contextFieldNames);

            // Only add annotation if UTX usage was found
            if (analysis.linearCount == 0 && analysis.complexCount == 0) {
                return classDecl;
            }

            // Add @NeedsReview with pattern-specific guidance
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);
            cd = addNeedsReviewAnnotation(cd, analysis, ctx);

            return cd;
        }

        private Set<String> findAllUtxFieldNames(J.ClassDeclaration classDecl) {
            Set<String> fieldNames = new HashSet<>();
            if (classDecl.getBody() == null) {
                return fieldNames;
            }

            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (isUserTransactionType(vd)) {
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            fieldNames.add(var.getSimpleName());
                        }
                    }
                }
            }
            return fieldNames;
        }

        private Set<String> findAllContextFieldNames(J.ClassDeclaration classDecl) {
            Set<String> fieldNames = new HashSet<>();
            if (classDecl.getBody() == null) {
                return fieldNames;
            }

            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (isSessionContextType(vd)) {
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            fieldNames.add(var.getSimpleName());
                        }
                    }
                }
            }
            return fieldNames;
        }

        private boolean isSessionContextType(J.VariableDeclarations vd) {
            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }

            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                String name = ident.getSimpleName();
                if ("SessionContext".equals(name) || "EJBContext".equals(name)) {
                    return true;
                }
            }

            JavaType type = typeExpr.getType();
            return type != null && (
                TypeUtils.isOfClassType(type, JAKARTA_SESSION_CTX) ||
                TypeUtils.isOfClassType(type, JAVAX_SESSION_CTX) ||
                TypeUtils.isOfClassType(type, JAKARTA_EJB_CTX) ||
                TypeUtils.isOfClassType(type, JAVAX_EJB_CTX)
            );
        }

        private boolean isUserTransactionType(J.VariableDeclarations vd) {
            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }

            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                if ("UserTransaction".equals(ident.getSimpleName())) {
                    return true;
                }
            }

            JavaType type = typeExpr.getType();
            return type != null && (
                TypeUtils.isOfClassType(type, JAKARTA_USER_TX) ||
                TypeUtils.isOfClassType(type, JAVAX_USER_TX)
            );
        }

        private boolean hasNeedsReviewAnnotation(J.ClassDeclaration cd) {
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) ||
                    "NeedsReview".equals(ann.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }

        private PatternAnalysis analyzeClass(J.ClassDeclaration classDecl, Set<String> utxFieldNames, Set<String> contextFieldNames) {
            PatternAnalysis analysis = new PatternAnalysis();

            if (classDecl.getBody() == null) {
                return analysis;
            }

            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                    if (method.getBody() != null) {
                        MethodPattern methodPattern = analyzeMethod(method, utxFieldNames, contextFieldNames);
                        if (methodPattern.hasUtxUsage) {
                            analysis.methodPatterns.add(methodPattern);
                            if (methodPattern.isLinear) {
                                analysis.linearCount++;
                            } else {
                                analysis.complexCount++;
                            }
                        }
                    }
                }
            }

            return analysis;
        }

        private MethodPattern analyzeMethod(J.MethodDeclaration method, Set<String> utxFieldNames, Set<String> contextFieldNames) {
            MethodPattern pattern = new MethodPattern();
            pattern.methodName = method.getSimpleName();

            if (method.getBody() == null) {
                return pattern;
            }

            // Build complete set of context names (fields + local vars + method parameters)
            Set<String> allContextNames = new HashSet<>(contextFieldNames);
            allContextNames.addAll(findContextMethodParameters(method));
            allContextNames.addAll(findLocalContextVariables(method.getBody()));

            // Find local variables assigned from getUserTransaction()
            Set<String> localUtxVars = findLocalUtxVariables(method.getBody(), allContextNames);
            Set<String> allUtxNames = new HashSet<>(utxFieldNames);
            allUtxNames.addAll(localUtxVars);

            // Count UTX calls in this method
            UtxCallCounter counter = new UtxCallCounter(allUtxNames, allContextNames);
            counter.visit(method.getBody(), null);
            pattern.hasUtxUsage = counter.totalCalls > 0;

            if (!pattern.hasUtxUsage) {
                return pattern;
            }

            // Check for loop patterns
            if (counter.hasUtxInLoop) {
                pattern.isLinear = false;
                pattern.complexity = "UTX calls inside loop";
                return pattern;
            }

            // Check for multiple begin/commit pairs
            if (counter.beginCount > 1 || counter.commitCount > 1) {
                pattern.isLinear = false;
                pattern.complexity = "Multiple transaction blocks";
                return pattern;
            }

            // Check if multiple UTX sources are used - force COMPLEX
            if (counter.distinctUtxSources.size() > 1) {
                pattern.isLinear = false;
                pattern.complexity = "Multiple UTX sources used";
                return pattern;
            }

            // Check for try-catch pattern with proper rollback handling
            TryPatternChecker tryChecker = new TryPatternChecker(allUtxNames, allContextNames);
            tryChecker.visit(method.getBody(), null);

            // If multiple UTX sources in try-catch, force COMPLEX
            if (tryChecker.distinctUtxSources.size() > 1) {
                pattern.isLinear = false;
                pattern.complexity = "Multiple UTX sources in transaction";
                return pattern;
            }

            if (tryChecker.hasLinearTryPattern && tryChecker.hasRollbackInCatch) {
                // Linear pattern with proper rollback handling
                pattern.isLinear = true;
            } else if (tryChecker.hasLinearTryPattern && !tryChecker.hasRollbackInCatch) {
                // Linear begin/commit but no rollback - mark as complex
                pattern.isLinear = false;
                pattern.complexity = "No rollback in catch block";
            } else if (counter.beginCount == 1 && counter.commitCount == 1 && counter.rollbackCount == 0) {
                // Direct begin/commit without try - complex
                pattern.isLinear = false;
                pattern.complexity = "No explicit rollback handling";
            } else {
                pattern.isLinear = false;
                pattern.complexity = "Complex control flow";
            }

            return pattern;
        }

        /**
         * Find local variables assigned from getUserTransaction() calls.
         * e.g., UserTransaction utx = ctx.getUserTransaction();
         * Uses a visitor to find variables in nested blocks (try, catch, etc.)
         */
        private Set<String> findLocalUtxVariables(J.Block body, Set<String> contextNames) {
            LocalUtxVarFinder finder = new LocalUtxVarFinder(contextNames);
            finder.visit(body, null);
            return finder.localVars;
        }

        /**
         * Find method parameters of SessionContext/EJBContext type.
         */
        private Set<String> findContextMethodParameters(J.MethodDeclaration method) {
            Set<String> params = new HashSet<>();
            for (Statement param : method.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    if (isSessionContextType(vd)) {
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            params.add(var.getSimpleName());
                        }
                    }
                }
            }
            return params;
        }

        /**
         * Find local variables of SessionContext/EJBContext type within a method body.
         */
        private Set<String> findLocalContextVariables(J.Block body) {
            LocalContextVarFinder finder = new LocalContextVarFinder();
            finder.visit(body, null);
            return finder.localContextVars;
        }

        private J.ClassDeclaration addNeedsReviewAnnotation(J.ClassDeclaration cd, PatternAnalysis analysis, ExecutionContext ctx) {
            addImportIfNeeded(NEEDS_REVIEW_FQN);

            String reason = buildReason(analysis);
            String suggestedAction = buildSuggestedAction(analysis);

            JavaTemplate template = JavaTemplate.builder(
                "@NeedsReview(reason = \"" + reason + "\", " +
                "category = NeedsReview.Category.MANUAL_MIGRATION, " +
                "originalCode = \"UserTransaction.begin/commit/rollback\", " +
                "suggestedAction = \"" + suggestedAction + "\")"
            ).javaParser(JavaParser.fromJavaVersion()
                .dependsOn(
                    "package com.github.rewrite.ejb.annotations;\n" +
                    "import java.lang.annotation.*;\n" +
                    "@Retention(RetentionPolicy.RUNTIME)\n" +
                    "@Target({ElementType.TYPE, ElementType.FIELD, ElementType.METHOD})\n" +
                    "public @interface NeedsReview {\n" +
                    "    String reason() default \"\";\n" +
                    "    Category category();\n" +
                    "    String originalCode() default \"\";\n" +
                    "    String suggestedAction() default \"\";\n" +
                    "    enum Category { MANUAL_MIGRATION }\n" +
                    "}"
                ))
                .imports(NEEDS_REVIEW_FQN)
                .build();

            return template.apply(
                new Cursor(getCursor(), cd),
                cd.getCoordinates().addAnnotation(Comparator.comparing(J.Annotation::getSimpleName))
            );
        }

        private String buildReason(PatternAnalysis analysis) {
            if (analysis.linearCount > 0 && analysis.complexCount == 0) {
                return "BMT with " + analysis.linearCount + " linear pattern(s) - can be converted to TransactionTemplate";
            } else if (analysis.complexCount > 0 && analysis.linearCount == 0) {
                return "BMT with " + analysis.complexCount + " complex pattern(s) - requires manual analysis";
            } else {
                return "BMT with mixed patterns (" + analysis.linearCount + " linear, " + analysis.complexCount + " complex)";
            }
        }

        private String buildSuggestedAction(PatternAnalysis analysis) {
            StringBuilder sb = new StringBuilder();

            if (analysis.linearCount > 0) {
                sb.append("LINEAR PATTERNS: Replace try { utx.begin(); ... utx.commit(); } catch { utx.rollback(); } with ")
                  .append("transactionTemplate.executeWithoutResult(status -> { ... }); ");
                sb.append("Inject TransactionTemplate instead of UserTransaction. ");
            }

            if (analysis.complexCount > 0) {
                sb.append("COMPLEX PATTERNS: Analyze control flow carefully. ");
                for (MethodPattern mp : analysis.methodPatterns) {
                    if (!mp.isLinear && mp.complexity != null) {
                        sb.append(mp.methodName).append(": ").append(mp.complexity).append("; ");
                    }
                }
            }

            return sb.toString().replace("\"", "'");
        }

        private void addImportIfNeeded(String fqn) {
            doAfterVisit(new AddImport<>(fqn, null, false));
        }
    }

    // Analysis helper classes

    private static class UtxCallCounter extends JavaIsoVisitor<Void> {
        private final Set<String> utxFieldNames;
        private final Set<String> contextFieldNames;
        int beginCount = 0;
        int commitCount = 0;
        int rollbackCount = 0;
        int totalCalls = 0;
        boolean hasUtxInLoop = false;
        Set<String> distinctUtxSources = new HashSet<>();
        private int loopDepth = 0;

        UtxCallCounter(Set<String> utxFieldNames, Set<String> contextFieldNames) {
            this.utxFieldNames = utxFieldNames;
            this.contextFieldNames = contextFieldNames;
        }

        @Override
        public J.ForLoop visitForLoop(J.ForLoop forLoop, Void unused) {
            loopDepth++;
            J.ForLoop result = super.visitForLoop(forLoop, unused);
            loopDepth--;
            return result;
        }

        @Override
        public J.ForEachLoop visitForEachLoop(J.ForEachLoop forEachLoop, Void unused) {
            loopDepth++;
            J.ForEachLoop result = super.visitForEachLoop(forEachLoop, unused);
            loopDepth--;
            return result;
        }

        @Override
        public J.WhileLoop visitWhileLoop(J.WhileLoop whileLoop, Void unused) {
            loopDepth++;
            J.WhileLoop result = super.visitWhileLoop(whileLoop, unused);
            loopDepth--;
            return result;
        }

        @Override
        public J.DoWhileLoop visitDoWhileLoop(J.DoWhileLoop doWhileLoop, Void unused) {
            loopDepth++;
            J.DoWhileLoop result = super.visitDoWhileLoop(doWhileLoop, unused);
            loopDepth--;
            return result;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Void unused) {
            String utxSource = getUtxSource(mi);
            if (utxSource != null) {
                distinctUtxSources.add(utxSource);
                String method = mi.getSimpleName();
                if ("begin".equals(method)) {
                    beginCount++;
                    totalCalls++;
                    if (loopDepth > 0) hasUtxInLoop = true;
                } else if ("commit".equals(method)) {
                    commitCount++;
                    totalCalls++;
                    if (loopDepth > 0) hasUtxInLoop = true;
                } else if ("rollback".equals(method)) {
                    rollbackCount++;
                    totalCalls++;
                    if (loopDepth > 0) hasUtxInLoop = true;
                }
            }
            return super.visitMethodInvocation(mi, unused);
        }

        private String getUtxSource(J.MethodInvocation mi) {
            Expression select = mi.getSelect();
            if (select == null) {
                return null;
            }

            // Handle direct field access: utx.begin()
            if (select instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) select;
                if (utxFieldNames.contains(ident.getSimpleName())) {
                    return ident.getSimpleName();
                }
            }

            // Handle this.field access: this.utx.begin() - normalize to bare field name
            if (select instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) select;
                if (fa.getTarget() instanceof J.Identifier) {
                    J.Identifier target = (J.Identifier) fa.getTarget();
                    if ("this".equals(target.getSimpleName())) {
                        if (utxFieldNames.contains(fa.getSimpleName())) {
                            return fa.getSimpleName(); // Normalize: strip "this."
                        }
                    }
                }
            }

            // Handle ctx.getUserTransaction().begin()
            if (select instanceof J.MethodInvocation) {
                J.MethodInvocation selectMi = (J.MethodInvocation) select;
                if ("getUserTransaction".equals(selectMi.getSimpleName())) {
                    Expression ctxSelect = selectMi.getSelect();
                    if (ctxSelect instanceof J.Identifier) {
                        J.Identifier ctxIdent = (J.Identifier) ctxSelect;
                        if (contextFieldNames.contains(ctxIdent.getSimpleName())) {
                            return ctxIdent.getSimpleName() + ".getUserTransaction()";
                        }
                    }
                    // Handle this.ctx.getUserTransaction() - normalize to bare ctx name
                    if (ctxSelect instanceof J.FieldAccess) {
                        J.FieldAccess fa = (J.FieldAccess) ctxSelect;
                        if (fa.getTarget() instanceof J.Identifier) {
                            J.Identifier target = (J.Identifier) fa.getTarget();
                            if ("this".equals(target.getSimpleName()) && contextFieldNames.contains(fa.getSimpleName())) {
                                return fa.getSimpleName() + ".getUserTransaction()"; // Normalize: strip "this."
                            }
                        }
                    }
                }
            }

            return null;
        }
    }

    private static class TryPatternChecker extends JavaIsoVisitor<Void> {
        private final Set<String> utxFieldNames;
        private final Set<String> contextFieldNames;
        boolean hasLinearTryPattern = false;
        boolean hasRollbackInCatch = false;
        Set<String> distinctUtxSources = new HashSet<>();

        TryPatternChecker(Set<String> utxFieldNames, Set<String> contextFieldNames) {
            this.utxFieldNames = utxFieldNames;
            this.contextFieldNames = contextFieldNames;
        }

        @Override
        public J.Try visitTry(J.Try tryStmt, Void unused) {
            List<Statement> tryBody = tryStmt.getBody().getStatements();

            int beginCount = 0;
            int commitCount = 0;

            for (Statement stmt : tryBody) {
                if (stmt instanceof J.MethodInvocation) {
                    J.MethodInvocation mi = (J.MethodInvocation) stmt;
                    String utxSource = getUtxSource(mi);
                    if (utxSource != null) {
                        distinctUtxSources.add(utxSource);
                        if ("begin".equals(mi.getSimpleName())) beginCount++;
                        if ("commit".equals(mi.getSimpleName())) commitCount++;
                    }
                }
            }

            // Check for rollback in catch
            for (J.Try.Catch catchBlock : tryStmt.getCatches()) {
                checkForRollbackInStatements(catchBlock.getBody().getStatements());
            }

            if (beginCount == 1 && commitCount == 1) {
                hasLinearTryPattern = true;
            }

            return super.visitTry(tryStmt, unused);
        }

        private void checkForRollbackInStatements(List<Statement> statements) {
            for (Statement stmt : statements) {
                if (stmt instanceof J.MethodInvocation) {
                    J.MethodInvocation mi = (J.MethodInvocation) stmt;
                    String utxSource = getUtxSource(mi);
                    if (utxSource != null && "rollback".equals(mi.getSimpleName())) {
                        distinctUtxSources.add(utxSource);
                        hasRollbackInCatch = true;
                    }
                }
                // Also check nested try for rollback
                if (stmt instanceof J.Try) {
                    J.Try nestedTry = (J.Try) stmt;
                    checkForRollbackInStatements(nestedTry.getBody().getStatements());
                }
            }
        }

        private String getUtxSource(J.MethodInvocation mi) {
            Expression select = mi.getSelect();
            if (select == null) {
                return null;
            }

            // Handle direct field access: utx.begin()
            if (select instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) select;
                if (utxFieldNames.contains(ident.getSimpleName())) {
                    return ident.getSimpleName();
                }
            }

            // Handle this.field access: this.utx.begin() - normalize to bare field name
            if (select instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) select;
                if (fa.getTarget() instanceof J.Identifier) {
                    J.Identifier target = (J.Identifier) fa.getTarget();
                    if ("this".equals(target.getSimpleName())) {
                        if (utxFieldNames.contains(fa.getSimpleName())) {
                            return fa.getSimpleName(); // Normalize: strip "this."
                        }
                    }
                }
            }

            // Handle ctx.getUserTransaction().begin()
            if (select instanceof J.MethodInvocation) {
                J.MethodInvocation selectMi = (J.MethodInvocation) select;
                if ("getUserTransaction".equals(selectMi.getSimpleName())) {
                    Expression ctxSelect = selectMi.getSelect();
                    if (ctxSelect instanceof J.Identifier) {
                        J.Identifier ctxIdent = (J.Identifier) ctxSelect;
                        if (contextFieldNames.contains(ctxIdent.getSimpleName())) {
                            return ctxIdent.getSimpleName() + ".getUserTransaction()";
                        }
                    }
                    // Handle this.ctx.getUserTransaction() - normalize to bare ctx name
                    if (ctxSelect instanceof J.FieldAccess) {
                        J.FieldAccess fa = (J.FieldAccess) ctxSelect;
                        if (fa.getTarget() instanceof J.Identifier) {
                            J.Identifier target = (J.Identifier) fa.getTarget();
                            if ("this".equals(target.getSimpleName()) && contextFieldNames.contains(fa.getSimpleName())) {
                                return fa.getSimpleName() + ".getUserTransaction()"; // Normalize: strip "this."
                            }
                        }
                    }
                }
            }

            return null;
        }
    }

    /**
     * Visitor to find local variables assigned from getUserTransaction() calls.
     * Handles both inline initialization and separate assignment:
     * - UserTransaction utx = ctx.getUserTransaction();
     * - UserTransaction utx; utx = ctx.getUserTransaction();
     */
    private static class LocalUtxVarFinder extends JavaIsoVisitor<Void> {
        private final Set<String> contextNames;
        Set<String> localVars = new HashSet<>();
        private Set<String> utxVarCandidates = new HashSet<>(); // UserTransaction vars without initializer

        LocalUtxVarFinder(Set<String> contextNames) {
            this.contextNames = contextNames;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, Void unused) {
            // Check if it's UserTransaction type
            if (isUserTransactionType(vd)) {
                for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                    Expression init = var.getInitializer();
                    if (init instanceof J.MethodInvocation) {
                        // Inline initialization: UserTransaction utx = ctx.getUserTransaction();
                        J.MethodInvocation mi = (J.MethodInvocation) init;
                        if ("getUserTransaction".equals(mi.getSimpleName())) {
                            if (isFromContextName(mi)) {
                                localVars.add(var.getSimpleName());
                            }
                        }
                    } else if (init == null) {
                        // No initializer: UserTransaction utx; - track for separate assignment
                        utxVarCandidates.add(var.getSimpleName());
                    }
                }
            }
            return super.visitVariableDeclarations(vd, unused);
        }

        @Override
        public J.Assignment visitAssignment(J.Assignment assignment, Void unused) {
            // Handle separate assignment: utx = ctx.getUserTransaction();
            Expression variable = assignment.getVariable();
            Expression assigned = assignment.getAssignment();

            if (variable instanceof J.Identifier && assigned instanceof J.MethodInvocation) {
                J.Identifier varIdent = (J.Identifier) variable;
                J.MethodInvocation mi = (J.MethodInvocation) assigned;

                if (utxVarCandidates.contains(varIdent.getSimpleName()) &&
                    "getUserTransaction".equals(mi.getSimpleName()) &&
                    isFromContextName(mi)) {
                    localVars.add(varIdent.getSimpleName());
                }
            }
            return super.visitAssignment(assignment, unused);
        }

        private boolean isUserTransactionType(J.VariableDeclarations vd) {
            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }

            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                if ("UserTransaction".equals(ident.getSimpleName())) {
                    return true;
                }
            }

            JavaType type = typeExpr.getType();
            return type != null && (
                TypeUtils.isOfClassType(type, JAKARTA_USER_TX) ||
                TypeUtils.isOfClassType(type, JAVAX_USER_TX)
            );
        }

        private boolean isFromContextName(J.MethodInvocation mi) {
            Expression ctxSelect = mi.getSelect();
            if (ctxSelect instanceof J.Identifier) {
                J.Identifier ctxIdent = (J.Identifier) ctxSelect;
                return contextNames.contains(ctxIdent.getSimpleName());
            }
            if (ctxSelect instanceof J.FieldAccess) {
                J.FieldAccess fa = (J.FieldAccess) ctxSelect;
                if (fa.getTarget() instanceof J.Identifier) {
                    J.Identifier target = (J.Identifier) fa.getTarget();
                    if ("this".equals(target.getSimpleName())) {
                        return contextNames.contains(fa.getSimpleName());
                    }
                }
            }
            return false;
        }
    }

    /**
     * Visitor to find local variables of SessionContext/EJBContext type.
     */
    private static class LocalContextVarFinder extends JavaIsoVisitor<Void> {
        Set<String> localContextVars = new HashSet<>();

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, Void unused) {
            if (isSessionContextType(vd)) {
                for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                    localContextVars.add(var.getSimpleName());
                }
            }
            return super.visitVariableDeclarations(vd, unused);
        }

        private boolean isSessionContextType(J.VariableDeclarations vd) {
            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr == null) {
                return false;
            }

            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                String name = ident.getSimpleName();
                if ("SessionContext".equals(name) || "EJBContext".equals(name)) {
                    return true;
                }
            }

            JavaType type = typeExpr.getType();
            return type != null && (
                TypeUtils.isOfClassType(type, JAKARTA_SESSION_CTX) ||
                TypeUtils.isOfClassType(type, JAVAX_SESSION_CTX) ||
                TypeUtils.isOfClassType(type, JAKARTA_EJB_CTX) ||
                TypeUtils.isOfClassType(type, JAVAX_EJB_CTX)
            );
        }
    }

    private static class PatternAnalysis {
        int linearCount = 0;
        int complexCount = 0;
        List<MethodPattern> methodPatterns = new ArrayList<>();
    }

    private static class MethodPattern {
        String methodName;
        boolean hasUtxUsage = false;
        boolean isLinear = false;
        String complexity;
    }
}
