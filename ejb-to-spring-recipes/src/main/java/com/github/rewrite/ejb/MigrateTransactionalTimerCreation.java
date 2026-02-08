/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: Implements P2.7 - Transactional Timer Creation with TimerCreateEvent
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;
import org.openrewrite.text.PlainText;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Migrates EJB Timer creation in @Transactional methods to Spring's event-driven pattern.
 * <p>
 * EJB timer creation is transactional (timer is created only after TX commit).
 * Spring scheduling is not transactional. This recipe migrates to an event-driven
 * pattern with @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT).
 * <p>
 * Transformation:
 * <pre>
 * // Original EJB code (inside @Transactional method)
 * @Transactional
 * public void businessMethod() {
 *     // ... business logic
 *     timerService.createTimer(5000, "myInfo");
 * }
 *
 * // Migrated
 * @Transactional
 * public void businessMethod() {
 *     // ... business logic
 *     eventPublisher.publishEvent(new TimerCreateEvent(
 *         this, 5000, 0, "myInfo", true, "MyBean.timeout"));
 * }
 * </pre>
 * <p>
 * Generated support classes:
 * <ul>
 *   <li>TimerCreateEvent - Application event carrying timer creation parameters</li>
 *   <li>TimerCreateEventListener - Listens for events after TX commit and schedules via Quartz</li>
 * </ul>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateTransactionalTimerCreation extends ScanningRecipe<MigrateTransactionalTimerCreation.Accumulator> {

    // ========== FQN Constants ==========

    // EJB Types
    private static final String JAKARTA_TIMER_SERVICE = "jakarta.ejb.TimerService";
    private static final String JAVAX_TIMER_SERVICE = "javax.ejb.TimerService";
    private static final String JAKARTA_TIMEOUT = "jakarta.ejb.Timeout";
    private static final String JAVAX_TIMEOUT = "javax.ejb.Timeout";
    private static final String JAKARTA_TIMER_CONFIG = "jakarta.ejb.TimerConfig";
    private static final String JAVAX_TIMER_CONFIG = "javax.ejb.TimerConfig";
    private static final String JAKARTA_RESOURCE = "jakarta.annotation.Resource";
    private static final String JAVAX_RESOURCE = "javax.annotation.Resource";

    // Spring Types
    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";
    private static final String SPRING_AUTOWIRED = "org.springframework.beans.factory.annotation.Autowired";
    private static final String SPRING_EVENT_PUBLISHER = "org.springframework.context.ApplicationEventPublisher";

    // Generated Types
    private static final String TIMER_CREATE_EVENT_FQN = "com.github.migration.timer.TimerCreateEvent";
    private static final String TIMER_CREATE_EVENT_LISTENER_FQN = "com.github.migration.timer.TimerCreateEventListener";

    // Stub for TimerCreateEvent for template parsing
    private static final String TIMER_CREATE_EVENT_STUB =
        "package com.github.migration.timer;\n" +
        "import org.springframework.context.ApplicationEvent;\n" +
        "import java.io.Serializable;\n" +
        "public class TimerCreateEvent extends ApplicationEvent {\n" +
        "    public TimerCreateEvent(Object source, long delay, long interval, Serializable info, boolean persistent, String targetMethod) { super(source); }\n" +
        "}\n";

    @Override
    public String getDisplayName() {
        return "Migrate transactional timer creation to TimerCreateEvent";
    }

    @Override
    public String getDescription() {
        return "Converts timerService.createTimer() calls in @Transactional methods to " +
               "eventPublisher.publishEvent(new TimerCreateEvent(...)) for transactional timer creation. " +
               "Generates TimerCreateEvent and TimerCreateEventListener classes.";
    }

    // ========== ScanningRecipe Infrastructure ==========

    static class Accumulator {
        final Map<String, SourceRootInfo> sourceRoots = new ConcurrentHashMap<>();
        // P2.7 Review 4 HIGH: Store existing AutoConfiguration.imports content for merging
        final Map<String, String> existingAutoConfigImports = new ConcurrentHashMap<>();
        // P2.7 Review 12 MEDIUM: Track existing imports files by FULL PATH (not derived resourcesRoot)
        // Keys are the full normalized path to the imports file (e.g., "module-a/src/main/resources/META-INF/spring/...imports")
        // This prevents collisions in multi-module builds where modules may share similar relative paths.
        final Set<String> existingImportsFilePaths = ConcurrentHashMap.newKeySet();
        // P2.7 Review 12 MEDIUM: Track imports files modified by visitor, keyed by FULL PATH
        final Set<String> modifiedImportsFilePaths = ConcurrentHashMap.newKeySet();
        // P2.7 Review 13 HIGH: Track which resourcesRoots will have timer transformations (per-module tracking)
        // Keys are resourcesRoot paths (e.g., "module-a/src/main/resources")
        // Used by scanner to mark modules with transformations, and by visitor/generate to check eligibility.
        final Set<String> resourcesRootsWithTransformation = ConcurrentHashMap.newKeySet();

        void recordSourceRoot(String sourceRoot, boolean isTestSource) {
            sourceRoots.computeIfAbsent(sourceRoot, k -> new SourceRootInfo(k, isTestSource));
        }

        void recordTransformation(String sourceRoot) {
            SourceRootInfo info = sourceRoots.computeIfAbsent(sourceRoot, k ->
                new SourceRootInfo(k, sourceRoot.contains("/test/") ||
                                      sourceRoot.endsWith("/test/java") ||
                                      sourceRoot.contains("src/test")));
            info.hasTransformation = true;
        }

        /**
         * P2.7 Review 4 HIGH + Review 12 MEDIUM: Record existing AutoConfiguration.imports content.
         * Uses the full imports file path as key to avoid collisions across modules.
         *
         * @param importsFilePath the full path to the imports file
         * @param resourcesRoot the derived resources root (for content lookup)
         * @param content the file content
         */
        void recordExistingAutoConfigImports(String importsFilePath, String resourcesRoot, String content) {
            if (importsFilePath != null && content != null) {
                existingImportsFilePaths.add(importsFilePath);
                if (resourcesRoot != null) {
                    existingAutoConfigImports.put(resourcesRoot, content);
                }
            }
        }

        /**
         * P2.7 Review 13 HIGH: Check if auto-config update is needed for a specific resourcesRoot.
         * This uses per-module tracking to support multi-module builds correctly.
         *
         * @param resourcesRoot the resourcesRoot to check
         * @return true if the resourcesRoot will have timer transformations
         */
        boolean needsAutoConfigUpdate(String resourcesRoot) {
            return resourcesRoot != null && resourcesRootsWithTransformation.contains(resourcesRoot);
        }

        /**
         * P2.7 Review 13 HIGH: Record that a resourcesRoot will have timer transformations.
         *
         * @param resourcesRoot the resourcesRoot that will have transformations
         */
        void recordTransformationForResourcesRoot(String resourcesRoot) {
            if (resourcesRoot != null) {
                resourcesRootsWithTransformation.add(resourcesRoot);
            }
        }

        static String deriveResourcesRoot(String sourceRoot) {
            if (sourceRoot == null) return null;
            String normalized = sourceRoot.replace('\\', '/');
            // P2.7 Review 7: Handle both absolute paths ("/project/src/main/java")
            // and relative paths ("src/main/java") for test compatibility
            if (normalized.contains("src/main/java")) {
                return normalized.replace("src/main/java", "src/main/resources");
            } else if (normalized.contains("src/test/java")) {
                return normalized.replace("src/test/java", "src/test/resources");
            }
            return null;
        }
    }

    static class SourceRootInfo {
        final String sourceRoot;
        final boolean isTestSource;
        volatile boolean hasTransformation;

        SourceRootInfo(String sourceRoot, boolean isTestSource) {
            this.sourceRoot = sourceRoot;
            this.isTestSource = isTestSource;
        }
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    private static final String AUTO_CONFIG_IMPORTS_PATH = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sourceFile = (SourceFile) tree;
                    Path sourcePath = sourceFile.getSourcePath();
                    String sourcePathStr = sourcePath.toString().replace('\\', '/');

                    if (tree instanceof J.CompilationUnit) {
                        J.CompilationUnit cu = (J.CompilationUnit) tree;
                        boolean isTest = sourcePathStr.contains("/test/") ||
                                       sourcePathStr.contains("/src/test/");
                        String sourceRoot = detectSourceRoot(cu);
                        if (sourceRoot != null) {
                            acc.recordSourceRoot(sourceRoot, isTest);
                        }

                        // P2.7 Review 9 HIGH + Review 13 HIGH: Pre-scan for timer transformation eligibility.
                        // This AST-only analysis determines if the file will be transformed,
                        // setting a per-resourcesRoot flag that allows the PlainText visitor to safely merge
                        // the AutoConfiguration.imports file regardless of visit order.
                        // Review 13 HIGH: Use per-resourcesRoot tracking instead of global flag.
                        if (!isTest && sourceRoot != null && willBeTransformed(cu)) {
                            String resourcesRoot = Accumulator.deriveResourcesRoot(sourceRoot);
                            acc.recordTransformationForResourcesRoot(resourcesRoot);
                        }
                    }

                    // P2.7 Review 4 HIGH + Review 12 MEDIUM: Detect and read existing AutoConfiguration.imports files
                    // Use the full source path as key to avoid collisions across modules
                    if (tree instanceof PlainText && sourcePathStr.endsWith(AUTO_CONFIG_IMPORTS_PATH)) {
                        PlainText plainText = (PlainText) tree;
                        String resourcesRoot = deriveResourcesRootFromImportsPath(sourcePathStr);
                        acc.recordExistingAutoConfigImports(sourcePathStr, resourcesRoot, plainText.getText());
                    }
                }
                return tree;
            }

            /**
             * P2.7 Review 9 HIGH: AST-only pre-scan to determine if file will be transformed.
             * This checks:
             * - Uses TimerService
             * - Has @Transactional method with timer creation call
             * - No escape patterns that would prevent transformation
             *
             * This is a conservative check - it may return true for files that won't
             * actually be transformed, but should never return false for files that will.
             */
            private boolean willBeTransformed(J.CompilationUnit cu) {
                // Check if the compilation unit uses TimerService
                boolean usesTimerService = false;
                for (J.Import imp : cu.getImports()) {
                    String impStr = imp.getQualid().print(new Cursor(null, cu)).trim();
                    if (impStr.equals(JAKARTA_TIMER_SERVICE) || impStr.equals(JAVAX_TIMER_SERVICE)) {
                        usesTimerService = true;
                        break;
                    }
                }
                if (!usesTimerService) {
                    // Quick check: could also have TimerService as simple name without import
                    // Walk the tree to find TimerService field
                    TimerServiceScanner scanner = new TimerServiceScanner();
                    scanner.visit(cu, null);
                    if (!scanner.hasTimerService) {
                        return false;
                    }
                }

                // Scan for transactional timer creation
                TransformationEligibilityScanner eligibilityScanner = new TransformationEligibilityScanner();
                eligibilityScanner.visit(cu, null);
                return eligibilityScanner.willBeTransformed();
            }

            private String detectSourceRoot(J.CompilationUnit cu) {
                if (cu.getPackageDeclaration() == null) {
                    return null;
                }
                String pkg = cu.getPackageDeclaration().getExpression().print(new Cursor(null, cu)).trim();
                String pkgPath = pkg.replace('.', '/');
                String sourcePath = cu.getSourcePath().toString().replace('\\', '/');
                int idx = sourcePath.lastIndexOf("/" + pkgPath + "/");
                if (idx > 0) {
                    return sourcePath.substring(0, idx);
                }
                return null;
            }

            /**
             * P2.7 Review 4 HIGH: Derive resources root from AutoConfiguration.imports path.
             */
            private String deriveResourcesRootFromImportsPath(String importsPath) {
                // importsPath: .../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
                // resourcesRoot: .../src/main/resources
                // Handle both "/META-INF/..." and "META-INF/..." patterns
                int idx = importsPath.indexOf("/" + AUTO_CONFIG_IMPORTS_PATH);
                if (idx > 0) {
                    return importsPath.substring(0, idx);
                }
                // Try without leading slash
                idx = importsPath.indexOf(AUTO_CONFIG_IMPORTS_PATH);
                if (idx > 0) {
                    // Remove trailing separator if present
                    String result = importsPath.substring(0, idx);
                    if (result.endsWith("/")) {
                        result = result.substring(0, result.length() - 1);
                    }
                    return result;
                }
                return null;
            }
        };
    }

    /**
     * P2.7 Review 9 HIGH: Simple scanner to detect TimerService usage.
     */
    private static class TimerServiceScanner extends JavaIsoVisitor<Void> {
        boolean hasTimerService = false;

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, Void v) {
            TypeTree typeExpr = vd.getTypeExpression();
            if (typeExpr != null) {
                JavaType type = typeExpr.getType();
                if (type != null && (
                    TypeUtils.isOfClassType(type, JAKARTA_TIMER_SERVICE) ||
                    TypeUtils.isOfClassType(type, JAVAX_TIMER_SERVICE))) {
                    hasTimerService = true;
                }
                if (typeExpr instanceof J.Identifier) {
                    if ("TimerService".equals(((J.Identifier) typeExpr).getSimpleName())) {
                        hasTimerService = true;
                    }
                }
            }
            return super.visitVariableDeclarations(vd, v);
        }
    }

    /**
     * P2.7 Review 9 HIGH + Review 10 HIGH + Review 11 HIGH: Scanner to determine if a file is eligible for transformation.
     * This checks for @Transactional methods with timer creation calls and escape patterns.
     * <p>
     * IMPORTANT: This pre-scan MUST mirror the escape analysis from TransactionalTimerVisitor.
     * If any escape pattern is detected, we set hasEscapePattern=true to avoid false positives.
     * <p>
     * Escape patterns include:
     * - TimerService used as method parameter
     * - TimerService used as local variable
     * - Timer creation result stored (variable initialization or assignment)
     * - Non-transactional timer calls (mixed usage)
     * - Non-createTimer methods (getTimers, etc.)
     * - Date/Duration/ScheduleExpression overloads
     * - TimerConfig passed as variable (not inline new)
     * - Lambda/anonymous class bodies with timer calls
     * <p>
     * P2.7 Review 11 HIGH: State is reset per-class to avoid leakage between multiple classes in one file.
     * The final result is per-CU: ANY class that will be transformed AND has no escape patterns qualifies.
     */
    private static class TransformationEligibilityScanner extends JavaIsoVisitor<Void> {
        // P2.7 Review 11 HIGH: CU-level result (any class qualifies the CU)
        boolean anyClassWillBeTransformed = false;
        // P2.7 Review 11 HIGH: Per-class state (reset for each class)
        private boolean currentClassWillBeTransformed = false;
        private boolean currentClassHasEscapePattern = false;
        private boolean inTransactionalMethod = false;
        private Set<String> timerServiceFieldNames = new HashSet<>();

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, Void v) {
            // P2.7 Review 11 HIGH: Reset per-class state to avoid leakage between classes
            currentClassWillBeTransformed = false;
            currentClassHasEscapePattern = false;
            timerServiceFieldNames.clear();

            // Analyze fields for TimerService
            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                        TypeTree typeExpr = vd.getTypeExpression();
                        if (typeExpr != null) {
                            JavaType type = typeExpr.getType();
                            boolean isTimerService = (type != null && (
                                TypeUtils.isOfClassType(type, JAKARTA_TIMER_SERVICE) ||
                                TypeUtils.isOfClassType(type, JAVAX_TIMER_SERVICE)));
                            if (!isTimerService && typeExpr instanceof J.Identifier) {
                                isTimerService = "TimerService".equals(((J.Identifier) typeExpr).getSimpleName());
                            }
                            if (isTimerService) {
                                for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                                    timerServiceFieldNames.add(var.getSimpleName());
                                }
                            }
                        }
                    }
                }
            }

            // Check for class-level @Transactional
            boolean classLevelTransactional = hasTransactionalAnnotation(classDecl.getLeadingAnnotations());

            // Check methods
            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                        boolean wasInTransactional = inTransactionalMethod;
                        inTransactionalMethod = classLevelTransactional ||
                            hasTransactionalAnnotation(md.getLeadingAnnotations());

                        // P2.7 Review 10 HIGH: Check for TimerService as parameter (escape)
                        for (Statement param : md.getParameters()) {
                            if (param instanceof J.VariableDeclarations) {
                                J.VariableDeclarations vd = (J.VariableDeclarations) param;
                                if (isTimerServiceType(vd.getTypeExpression())) {
                                    currentClassHasEscapePattern = true;
                                }
                            }
                        }

                        // Analyze method body
                        if (md.getBody() != null) {
                            analyzeBlockForEscapes(md.getBody());
                        }

                        inTransactionalMethod = wasInTransactional;
                    }
                }
            }

            // P2.7 Review 11 HIGH: Check if THIS class (not any previous class) qualifies
            // A class qualifies if it will be transformed AND has no escape patterns
            if (currentClassWillBeTransformed && !currentClassHasEscapePattern) {
                anyClassWillBeTransformed = true;
            }

            return classDecl;
        }

        // P2.7 Review 11 HIGH: Getter for the final result
        boolean willBeTransformed() {
            return anyClassWillBeTransformed;
        }

        /**
         * P2.7 Review 10 HIGH: Analyze block statements for escape patterns.
         * Mirrors TransactionalTimerVisitor.analyzeBlockForEscapes logic.
         */
        private void analyzeBlockForEscapes(J.Block block) {
            for (Statement stmt : block.getStatements()) {
                analyzeStatementForEscapes(stmt);
            }
        }

        /**
         * P2.7 Review 10 HIGH: Analyze statement for escape patterns.
         * Mirrors TransactionalTimerVisitor.analyzeStatementForEscapes logic.
         */
        private void analyzeStatementForEscapes(Statement stmt) {
            if (stmt instanceof J.VariableDeclarations) {
                J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                // P2.7 Review 10 HIGH: TimerService as local variable = escape
                if (isTimerServiceType(vd.getTypeExpression())) {
                    currentClassHasEscapePattern = true;
                }
                // P2.7 Review 10 HIGH: Timer creation result stored = escape
                for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                    if (var.getInitializer() != null) {
                        if (isTimerCreationInvocation(var.getInitializer())) {
                            currentClassHasEscapePattern = true;
                        } else {
                            analyzeExpressionForEscapes(var.getInitializer());
                        }
                    }
                }
            } else if (stmt instanceof J.Assignment) {
                // P2.7 Review 10 HIGH: Timer creation result assigned = escape
                J.Assignment assign = (J.Assignment) stmt;
                if (isTimerCreationInvocation(assign.getAssignment())) {
                    currentClassHasEscapePattern = true;
                } else {
                    analyzeExpressionForEscapes(assign.getAssignment());
                }
            } else if (stmt instanceof J.MethodInvocation) {
                analyzeMethodInvocationForEscapes((J.MethodInvocation) stmt);
            } else if (stmt instanceof J.If) {
                J.If ifStmt = (J.If) stmt;
                if (ifStmt.getThenPart() instanceof J.Block) {
                    analyzeBlockForEscapes((J.Block) ifStmt.getThenPart());
                }
                if (ifStmt.getElsePart() != null && ifStmt.getElsePart().getBody() instanceof J.Block) {
                    analyzeBlockForEscapes((J.Block) ifStmt.getElsePart().getBody());
                }
            } else if (stmt instanceof J.ForLoop) {
                J.ForLoop forLoop = (J.ForLoop) stmt;
                if (forLoop.getBody() instanceof J.Block) {
                    analyzeBlockForEscapes((J.Block) forLoop.getBody());
                }
            } else if (stmt instanceof J.WhileLoop) {
                J.WhileLoop whileLoop = (J.WhileLoop) stmt;
                if (whileLoop.getBody() instanceof J.Block) {
                    analyzeBlockForEscapes((J.Block) whileLoop.getBody());
                }
            } else if (stmt instanceof J.Try) {
                J.Try tryStmt = (J.Try) stmt;
                analyzeBlockForEscapes(tryStmt.getBody());
                for (J.Try.Catch catchClause : tryStmt.getCatches()) {
                    analyzeBlockForEscapes(catchClause.getBody());
                }
                if (tryStmt.getFinally() != null) {
                    analyzeBlockForEscapes(tryStmt.getFinally());
                }
            } else if (stmt instanceof Expression) {
                analyzeExpressionForEscapes((Expression) stmt);
            }
        }

        /**
         * P2.7 Review 10 HIGH: Analyze expression for escape patterns.
         * Mirrors TransactionalTimerVisitor.analyzeExpressionTreeForEscapes logic.
         */
        private void analyzeExpressionForEscapes(Expression expr) {
            if (expr == null) return;

            if (expr instanceof J.MethodInvocation) {
                analyzeMethodInvocationForEscapes((J.MethodInvocation) expr);
                // Also check arguments
                J.MethodInvocation mi = (J.MethodInvocation) expr;
                if (mi.getArguments() != null) {
                    for (Expression arg : mi.getArguments()) {
                        analyzeExpressionForEscapes(arg);
                    }
                }
            } else if (expr instanceof J.Lambda) {
                // P2.7 Review 10 HIGH: Lambda bodies with timer calls = escape
                J.Lambda lambda = (J.Lambda) expr;
                if (lambda.getBody() instanceof J.Block) {
                    analyzeBlockForEscapes((J.Block) lambda.getBody());
                } else if (lambda.getBody() instanceof Expression) {
                    analyzeExpressionForEscapes((Expression) lambda.getBody());
                }
            } else if (expr instanceof J.NewClass) {
                // P2.7 Review 10 HIGH: Anonymous class bodies with timer calls = escape
                J.NewClass nc = (J.NewClass) expr;
                if (nc.getBody() != null) {
                    for (Statement stmt : nc.getBody().getStatements()) {
                        if (stmt instanceof J.MethodDeclaration) {
                            J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                            if (md.getBody() != null) {
                                // Save and restore transactional state
                                boolean saved = inTransactionalMethod;
                                inTransactionalMethod = hasTransactionalAnnotation(md.getLeadingAnnotations());
                                analyzeBlockForEscapes(md.getBody());
                                inTransactionalMethod = saved;
                            }
                        }
                    }
                }
                // Also analyze constructor arguments
                if (nc.getArguments() != null) {
                    for (Expression arg : nc.getArguments()) {
                        analyzeExpressionForEscapes(arg);
                    }
                }
            } else if (expr instanceof J.Parentheses) {
                J.Parentheses<?> parens = (J.Parentheses<?>) expr;
                if (parens.getTree() instanceof Expression) {
                    analyzeExpressionForEscapes((Expression) parens.getTree());
                }
            } else if (expr instanceof J.Ternary) {
                J.Ternary ternary = (J.Ternary) expr;
                analyzeExpressionForEscapes(ternary.getTruePart());
                analyzeExpressionForEscapes(ternary.getFalsePart());
            } else if (expr instanceof J.TypeCast) {
                J.TypeCast cast = (J.TypeCast) expr;
                analyzeExpressionForEscapes(cast.getExpression());
            }
        }

        /**
         * P2.7 Review 10 HIGH: Analyze timer service method invocation for escape patterns.
         * Mirrors TransactionalTimerVisitor.analyzeMethodInvocationForEscapes logic.
         */
        private void analyzeMethodInvocationForEscapes(J.MethodInvocation mi) {
            Expression select = mi.getSelect();
            if (select == null) return;

            // Check if this is a timer service call
            boolean isTimerServiceCall = false;
            if (select.getType() != null) {
                isTimerServiceCall = TypeUtils.isOfClassType(select.getType(), JAKARTA_TIMER_SERVICE) ||
                                    TypeUtils.isOfClassType(select.getType(), JAVAX_TIMER_SERVICE);
            }
            if (!isTimerServiceCall && select instanceof J.Identifier) {
                isTimerServiceCall = timerServiceFieldNames.contains(((J.Identifier) select).getSimpleName());
            }

            if (!isTimerServiceCall) return;

            String methodName = mi.getSimpleName();
            List<Expression> args = mi.getArguments();

            // Non-createTimer methods = escape
            if (!isTimerCreationMethod(methodName)) {
                currentClassHasEscapePattern = true;
                return;
            }

            // Timer creation outside @Transactional = mixed usage = escape
            if (!inTransactionalMethod) {
                currentClassHasEscapePattern = true;
                return;
            }

            // Timer creation in @Transactional = potential transformation
            currentClassWillBeTransformed = true;

            // P2.7 Review 10 HIGH: Check for Date/Duration overloads (escape)
            if (args != null && !args.isEmpty()) {
                JavaType firstArgType = args.get(0).getType();
                if (firstArgType != null) {
                    if (TypeUtils.isOfClassType(firstArgType, "java.util.Date") ||
                        TypeUtils.isOfClassType(firstArgType, "java.time.Duration") ||
                        TypeUtils.isOfClassType(firstArgType, "jakarta.ejb.ScheduleExpression") ||
                        TypeUtils.isOfClassType(firstArgType, "javax.ejb.ScheduleExpression")) {
                        currentClassHasEscapePattern = true;
                        return;
                    }
                }
            }

            // P2.7 Review 10 HIGH: Check for TimerConfig variable (not inline new) = escape
            Expression configArg = getTimerConfigArg(methodName, args);
            if (configArg != null) {
                if (configArg instanceof J.NewClass) {
                    // Inline new is OK
                } else if (configArg instanceof J.Literal) {
                    // Literal (null, string) is OK
                } else if (configArg instanceof J.Identifier) {
                    // Variable reference - check if it's TimerConfig type
                    JavaType type = configArg.getType();
                    if (type != null) {
                        if (TypeUtils.isOfClassType(type, JAKARTA_TIMER_CONFIG) ||
                            TypeUtils.isOfClassType(type, JAVAX_TIMER_CONFIG)) {
                            currentClassHasEscapePattern = true;
                        }
                    } else {
                        // No type info - conservatively mark as escape
                        currentClassHasEscapePattern = true;
                    }
                } else {
                    // Unknown expression - conservatively mark as escape
                    currentClassHasEscapePattern = true;
                }
            }
        }

        private boolean isTimerCreationMethod(String methodName) {
            return "createTimer".equals(methodName) ||
                   "createSingleActionTimer".equals(methodName) ||
                   "createIntervalTimer".equals(methodName);
        }

        private boolean hasTransactionalAnnotation(List<J.Annotation> annotations) {
            for (J.Annotation ann : annotations) {
                if (TypeUtils.isOfClassType(ann.getType(), SPRING_TRANSACTIONAL) ||
                    TypeUtils.isOfClassType(ann.getType(), JAKARTA_TRANSACTIONAL) ||
                    "Transactional".equals(ann.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }

        private boolean isTimerServiceType(TypeTree typeExpr) {
            if (typeExpr == null) return false;
            JavaType type = typeExpr.getType();
            if (type != null && (
                TypeUtils.isOfClassType(type, JAKARTA_TIMER_SERVICE) ||
                TypeUtils.isOfClassType(type, JAVAX_TIMER_SERVICE))) {
                return true;
            }
            if (typeExpr instanceof J.Identifier) {
                return "TimerService".equals(((J.Identifier) typeExpr).getSimpleName());
            }
            return false;
        }

        private boolean isTimerCreationInvocation(Expression expr) {
            if (!(expr instanceof J.MethodInvocation)) return false;
            J.MethodInvocation mi = (J.MethodInvocation) expr;
            Expression select = mi.getSelect();
            if (select == null) return false;

            boolean isTimerServiceCall = false;
            if (select.getType() != null) {
                isTimerServiceCall = TypeUtils.isOfClassType(select.getType(), JAKARTA_TIMER_SERVICE) ||
                                    TypeUtils.isOfClassType(select.getType(), JAVAX_TIMER_SERVICE);
            }
            if (!isTimerServiceCall && select instanceof J.Identifier) {
                isTimerServiceCall = timerServiceFieldNames.contains(((J.Identifier) select).getSimpleName());
            }

            return isTimerServiceCall && isTimerCreationMethod(mi.getSimpleName());
        }

        private Expression getTimerConfigArg(String methodName, List<Expression> args) {
            if (args == null || args.isEmpty()) return null;
            // createSingleActionTimer(long, TimerConfig) - config is arg index 1
            // createIntervalTimer(long, long, TimerConfig) - config is arg index 2
            // createTimer(long, Serializable) - no config, info is arg index 1
            // createTimer(long, long, Serializable) - no config, info is arg index 2
            if ("createSingleActionTimer".equals(methodName) && args.size() >= 2) {
                return args.get(1);
            } else if ("createIntervalTimer".equals(methodName) && args.size() >= 3) {
                return args.get(2);
            }
            // For createTimer, the second arg is Serializable info, not TimerConfig
            return null;
        }
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        // P2.7 Review 9 HIGH + Review 13 HIGH: Combined visitor that handles both Java and PlainText files.
        // The visit-order race condition is eliminated by using pre-scanned per-resourcesRoot flags:
        // - Scanner phase: AST-only analysis records resourcesRootsWithTransformation per module
        // - Visitor phase: PlainText visitor merges only when the current resourcesRoot is marked
        // This ensures PlainText merging happens correctly regardless of visit order and per-module.
        return new TreeVisitor<Tree, ExecutionContext>() {
            private final TreeVisitor<?, ExecutionContext> javaVisitor = Preconditions.check(
                Preconditions.or(
                    new UsesType<>(JAKARTA_TIMER_SERVICE, false),
                    new UsesType<>(JAVAX_TIMER_SERVICE, false)
                ),
                new TransactionalTimerVisitor(acc)
            );

            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof J.CompilationUnit) {
                    return javaVisitor.visit(tree, ctx);
                } else if (tree instanceof PlainText) {
                    PlainText plainText = (PlainText) tree;
                    String sourcePath = plainText.getSourcePath().toString().replace('\\', '/');
                    if (sourcePath.endsWith(AUTO_CONFIG_IMPORTS_PATH)) {
                        // P2.7 Review 9 HIGH + Review 13 HIGH: Use per-resourcesRoot flag for PlainText merging.
                        // This flag is set in the scanner phase BEFORE the visitor runs,
                        // so it's deterministic regardless of visit order.
                        // Review 13 HIGH: Only merge if THIS module has timer transformations.
                        String resourcesRoot = deriveResourcesRootFromImportsPath(sourcePath);
                        if (acc.needsAutoConfigUpdate(resourcesRoot)) {
                            String newEntry = "com.github.migration.timer.TimerAutoConfiguration";
                            String existingContent = plainText.getText();
                            String mergedContent = mergeAutoConfigImportsContent(existingContent, newEntry);
                            if (!mergedContent.equals(existingContent)) {
                                // P2.7 Review 12 MEDIUM: Track that visitor modified this file by full path
                                acc.modifiedImportsFilePaths.add(sourcePath);
                                return plainText.withText(mergedContent);
                            } else {
                                // P2.7 Review 12 MEDIUM: Entry already exists, still mark as handled
                                // This prevents generate() from creating a new file
                                acc.modifiedImportsFilePaths.add(sourcePath);
                            }
                        }
                    }
                }
                return tree;
            }
        };
    }

    /**
     * P2.7 Review 9 HIGH: Merge new entry with existing content directly (for visitor use).
     * Same logic as mergeAutoConfigImports but works on content directly without accumulator.
     *
     * @param existingContent the existing file content
     * @param newEntry the new auto-configuration class to add
     * @return the merged content
     */
    private String mergeAutoConfigImportsContent(String existingContent, String newEntry) {
        if (existingContent == null || existingContent.isBlank()) {
            return newEntry + "\n";
        }

        // Check if entry already exists (idempotency guard)
        String[] lines = existingContent.split("\n", -1);
        for (String line : lines) {
            if (line.trim().equals(newEntry)) {
                // Entry already present - return existing content unchanged
                return existingContent;
            }
        }

        // Append new entry, preserving original trailing newline behavior
        boolean hadTrailingNewline = existingContent.endsWith("\n");
        StringBuilder result = new StringBuilder(existingContent);
        if (!hadTrailingNewline) {
            result.append("\n");
        }
        result.append(newEntry);
        if (hadTrailingNewline) {
            result.append("\n");
        }
        return result.toString();
    }

    private String deriveResourcesRootFromImportsPath(String importsPath) {
        int idx = importsPath.indexOf("/" + AUTO_CONFIG_IMPORTS_PATH);
        if (idx > 0) {
            return importsPath.substring(0, idx);
        }
        idx = importsPath.indexOf(AUTO_CONFIG_IMPORTS_PATH);
        if (idx > 0) {
            String result = importsPath.substring(0, idx);
            if (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        }
        return null;
    }

    @Override
    public Collection<SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        List<SourceFile> generated = new ArrayList<>();

        for (SourceRootInfo info : acc.sourceRoots.values()) {
            if (info.hasTransformation && !info.isTestSource) {
                // Generate TimerCreateEvent class
                String eventPath = info.sourceRoot + "/com/github/rewrite/migration/timer/TimerCreateEvent.java";
                String eventContent = generateTimerCreateEventClass();
                PlainText eventFile = PlainText.builder()
                    .sourcePath(Paths.get(eventPath))
                    .text(eventContent)
                    .build();
                generated.add(eventFile);

                // Generate TimerCreateEventListener class
                String listenerPath = info.sourceRoot + "/com/github/rewrite/migration/timer/TimerCreateEventListener.java";
                String listenerContent = generateTimerCreateEventListenerClass();
                PlainText listenerFile = PlainText.builder()
                    .sourcePath(Paths.get(listenerPath))
                    .text(listenerContent)
                    .build();
                generated.add(listenerFile);

                // P2.7 Review 2 HIGH 2: Generate auto-configuration class
                String autoConfigPath = info.sourceRoot + "/com/github/rewrite/migration/timer/TimerAutoConfiguration.java";
                String autoConfigContent = generateTimerAutoConfigurationClass();
                PlainText autoConfigFile = PlainText.builder()
                    .sourcePath(Paths.get(autoConfigPath))
                    .text(autoConfigContent)
                    .build();
                generated.add(autoConfigFile);

                // P2.7 Review 2 HIGH 2 + Review 4 HIGH + Review 9 HIGH + Review 12 MEDIUM:
                // Generate Spring Boot auto-configuration imports file ONLY if:
                // 1. The file doesn't exist on disk (visitor can't modify non-existent files), AND
                // 2. The file wasn't handled by visitor (merged or already has entry)
                //
                // Key insight: generate() can create NEW files, visitor can modify EXISTING files.
                // - If file doesn't exist: generate() creates it
                // - If file exists: visitor modified it (or entry was already present), so we skip
                //
                // P2.7 Review 12 MEDIUM: Use full imports path as key to avoid collisions across modules
                String resourcesRoot = Accumulator.deriveResourcesRoot(info.sourceRoot);
                if (resourcesRoot != null) {
                    String importsPath = resourcesRoot + "/" + AUTO_CONFIG_IMPORTS_PATH;
                    boolean fileExistsOnDisk = acc.existingImportsFilePaths.contains(importsPath);
                    boolean visitorHandledFile = acc.modifiedImportsFilePaths.contains(importsPath);

                    // Only generate new file if file doesn't exist on disk and visitor didn't handle it
                    if (!fileExistsOnDisk && !visitorHandledFile) {
                        String newEntry = "com.github.migration.timer.TimerAutoConfiguration";
                        String importsContent = mergeAutoConfigImports(acc, resourcesRoot, newEntry);
                        PlainText importsFile = PlainText.builder()
                            .sourcePath(Paths.get(importsPath))
                            .text(importsContent)
                            .build();
                        generated.add(importsFile);
                    }
                }
            }
        }

        return generated;
    }

    /**
     * P2.7 Review 4 HIGH: Merge new entry with existing AutoConfiguration.imports content.
     * <p>
     * This method:
     * - Reads existing content from accumulator (if present)
     * - Preserves existing entries and their order
     * - Preserves comments
     * - Appends new entry only if not already present (idempotency)
     * - Ensures trailing newline
     *
     * @param acc the accumulator with existing content
     * @param resourcesRoot the resources root path
     * @param newEntry the new auto-configuration class to add
     * @return the merged content
     */
    private String mergeAutoConfigImports(Accumulator acc, String resourcesRoot, String newEntry) {
        String existingContent = acc.existingAutoConfigImports.get(resourcesRoot);

        if (existingContent == null || existingContent.isBlank()) {
            // No existing file - just return new entry with newline
            return newEntry + "\n";
        }

        // Delegate to the common merge implementation
        return mergeAutoConfigImportsContent(existingContent, newEntry);
    }

    // P2.7 Review 9 HIGH: Re-introduced mergeAutoConfigImportsContent method.
    // PlainText merging is now done by visitor using pre-scanned flag (eliminates visit-order race).

    private String generateTimerCreateEventClass() {
        return """
            /*
             * Copyright 2026 Torsten Liermann
             *
             * Licensed under the Apache License, Version 2.0 (the "License");
             * you may not use this file except in compliance with the License.
             * You may obtain a copy of the License at
             *
             *      https://www.apache.org/licenses/LICENSE-2.0
             *
             * Unless required by applicable law or agreed to in writing, software
             * distributed under the License is distributed on an "AS IS" BASIS,
             * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
             * See the License for the specific language governing permissions and
             * limitations under the License.
             */
            package com.github.migration.timer;

            import org.springframework.context.ApplicationEvent;

            import java.io.Serializable;

            /**
             * Event for transactional timer creation.
             * <p>
             * This event is published when a timer should be created transactionally.
             * The TimerCreateEventListener will handle this event AFTER_COMMIT to ensure
             * the timer is only created when the transaction commits successfully.
             * <p>
             * Generated by MigrateTransactionalTimerCreation recipe.
             */
            public class TimerCreateEvent extends ApplicationEvent {

                private final long delay;
                private final long interval;  // 0 for single-action timers
                private final Serializable info;
                private final boolean persistent;
                private final String targetMethod;  // e.g., "MyBean.timeout"

                public TimerCreateEvent(Object source, long delay, long interval,
                                        Serializable info, boolean persistent, String targetMethod) {
                    super(source);
                    this.delay = delay;
                    this.interval = interval;
                    this.info = info;
                    this.persistent = persistent;
                    this.targetMethod = targetMethod;
                }

                public long getDelay() {
                    return delay;
                }

                public long getInterval() {
                    return interval;
                }

                public Serializable getInfo() {
                    return info;
                }

                public boolean isPersistent() {
                    return persistent;
                }

                public String getTargetMethod() {
                    return targetMethod;
                }

                /**
                 * Checks if this is an interval timer.
                 *
                 * @return true if interval > 0
                 */
                public boolean isIntervalTimer() {
                    return interval > 0;
                }
            }
            """;
    }

    private String generateTimerCreateEventListenerClass() {
        // P2.7 Review 2 HIGH 2: Use constructor injection, no @Component
        // Bean registration happens via TimerAutoConfiguration
        return """
            /*
             * Copyright 2026 Torsten Liermann
             *
             * Licensed under the Apache License, Version 2.0 (the "License");
             * you may not use this file except in compliance with the License.
             * You may obtain a copy of the License at
             *
             *      https://www.apache.org/licenses/LICENSE-2.0
             *
             * Unless required by applicable law or agreed to in writing, software
             * distributed under the License is distributed on an "AS IS" BASIS,
             * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
             * See the License for the specific language governing permissions and
             * limitations under the License.
             */
            package com.github.migration.timer;

            import org.quartz.*;
            import org.slf4j.Logger;
            import org.slf4j.LoggerFactory;
            import org.springframework.transaction.event.TransactionPhase;
            import org.springframework.transaction.event.TransactionalEventListener;

            import java.time.Instant;
            import java.util.Date;
            import java.util.UUID;

            /**
             * Listens for TimerCreateEvent and schedules Quartz jobs AFTER transaction commit.
             * <p>
             * This ensures transactional timer creation semantics: the timer is only created
             * when the transaction commits successfully.
             * <p>
             * P2.7 Review 2 HIGH 2: Uses constructor injection instead of field injection.
             * Bean registration happens via {@link TimerAutoConfiguration}.
             * <p>
             * Generated by MigrateTransactionalTimerCreation recipe.
             */
            public class TimerCreateEventListener {

                private static final Logger LOGGER = LoggerFactory.getLogger(TimerCreateEventListener.class);

                private final Scheduler scheduler;

                /**
                 * Constructor injection for Scheduler dependency.
                 * <p>
                 * P2.7 Review 2 HIGH 2: Uses constructor injection instead of @Autowired field injection.
                 *
                 * @param scheduler the Quartz Scheduler
                 */
                public TimerCreateEventListener(Scheduler scheduler) {
                    this.scheduler = scheduler;
                }

                @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
                public void handleTimerCreate(TimerCreateEvent event) {
                    try {
                        if (event.isIntervalTimer()) {
                            scheduleIntervalJob(event);
                        } else {
                            scheduleSingleJob(event);
                        }
                    } catch (SchedulerException e) {
                        LOGGER.error("Failed to schedule timer for {}: {}", event.getTargetMethod(), e.getMessage(), e);
                        throw new RuntimeException("Failed to schedule timer", e);
                    }
                }

                /**
                 * P2.7 Review 1: Creates JobDataMap with Serializable info preserved.
                 */
                private JobDataMap createJobDataMap(TimerCreateEvent event) {
                    JobDataMap jobDataMap = new JobDataMap();
                    // P2.7 Review 1 HIGH 5: Store Serializable directly, not toString()
                    if (event.getInfo() != null) {
                        jobDataMap.put("info", event.getInfo());
                    }
                    jobDataMap.put("targetMethod", event.getTargetMethod());
                    return jobDataMap;
                }

                private void scheduleSingleJob(TimerCreateEvent event) throws SchedulerException {
                    JobDetail job = JobBuilder.newJob(findJobClass(event.getTargetMethod()))
                        .withIdentity(UUID.randomUUID().toString(), "timer-create-event")
                        .setJobData(createJobDataMap(event))
                        .storeDurably(event.isPersistent())
                        .build();

                    Trigger trigger = TriggerBuilder.newTrigger()
                        .withIdentity(UUID.randomUUID().toString(), "timer-create-event")
                        .startAt(Date.from(Instant.now().plusMillis(event.getDelay())))
                        .build();

                    scheduler.scheduleJob(job, trigger);
                    LOGGER.info("Scheduled single-action timer for {} with delay {}ms", event.getTargetMethod(), event.getDelay());
                }

                private void scheduleIntervalJob(TimerCreateEvent event) throws SchedulerException {
                    JobDetail job = JobBuilder.newJob(findJobClass(event.getTargetMethod()))
                        .withIdentity(UUID.randomUUID().toString(), "timer-create-event")
                        .setJobData(createJobDataMap(event))
                        .storeDurably(event.isPersistent())
                        .build();

                    Trigger trigger = TriggerBuilder.newTrigger()
                        .withIdentity(UUID.randomUUID().toString(), "timer-create-event")
                        .startAt(Date.from(Instant.now().plusMillis(event.getDelay())))
                        .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInMilliseconds(event.getInterval())
                            .repeatForever())
                        .build();

                    scheduler.scheduleJob(job, trigger);
                    LOGGER.info("Scheduled interval timer for {} with delay {}ms and interval {}ms",
                        event.getTargetMethod(), event.getDelay(), event.getInterval());
                }

                /**
                 * Finds the Job class for the given target method.
                 * <p>
                 * P2.7 Review 1 HIGH 6: This implementation uses the FQN from targetMethod:
                 * For "com.example.MyBean.timeout" -> looks for com.example.MyBeanJob
                 * <p>
                 * Override this method to customize job class resolution.
                 *
                 * @param targetMethod the target method in FQN format "package.ClassName.methodName"
                 * @return the Job class
                 */
                @SuppressWarnings("unchecked")
                protected Class<? extends Job> findJobClass(String targetMethod) {
                    // P2.7 Review 1 HIGH 6: Extract FQN from targetMethod (e.g., "com.example.MyBean.timeout")
                    int lastDot = targetMethod.lastIndexOf('.');
                    if (lastDot <= 0) {
                        LOGGER.warn("Invalid targetMethod format: {}, using GenericTimerJob", targetMethod);
                        return GenericTimerJob.class;
                    }

                    String fqClassName = targetMethod.substring(0, lastDot);  // "com.example.MyBean"
                    int classNameStart = fqClassName.lastIndexOf('.');
                    String packageName = classNameStart > 0 ? fqClassName.substring(0, classNameStart) : "";
                    String simpleClassName = classNameStart > 0 ? fqClassName.substring(classNameStart + 1) : fqClassName;
                    String jobClassName = simpleClassName + "Job";
                    String fqJobClassName = packageName.isEmpty() ? jobClassName : packageName + "." + jobClassName;

                    try {
                        return (Class<? extends Job>) Class.forName(fqJobClassName);
                    } catch (ClassNotFoundException e) {
                        LOGGER.warn("Job class {} not found, using default GenericTimerJob", fqJobClassName);
                        return GenericTimerJob.class;
                    }
                }

                /**
                 * Generic fallback Job implementation.
                 * <p>
                 * This job logs a warning and should be replaced with proper Job implementations.
                 */
                public static class GenericTimerJob implements Job {
                    private static final Logger JOB_LOGGER = LoggerFactory.getLogger(GenericTimerJob.class);

                    @Override
                    public void execute(JobExecutionContext context) throws JobExecutionException {
                        String targetMethod = context.getJobDetail().getJobDataMap().getString("targetMethod");
                        String info = context.getJobDetail().getJobDataMap().getString("info");
                        JOB_LOGGER.warn("GenericTimerJob executed for {} with info {}. " +
                            "Please implement a proper Job class.", targetMethod, info);
                    }
                }
            }
            """;
    }

    /**
     * P2.7 Review 2 HIGH 2: Generate auto-configuration class for TimerCreateEventListener.
     * <p>
     * This auto-configuration class:
     * - Uses @AutoConfiguration instead of @Configuration
     * - Uses @Bean factory method to create TimerCreateEventListener
     * - Uses constructor injection (via parameter) instead of field @Autowired
     * - Is registered via META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
     */
    private String generateTimerAutoConfigurationClass() {
        return """
            /*
             * Copyright 2026 Torsten Liermann
             *
             * Licensed under the Apache License, Version 2.0 (the "License");
             * you may not use this file except in compliance with the License.
             * You may obtain a copy of the License at
             *
             *      https://www.apache.org/licenses/LICENSE-2.0
             *
             * Unless required by applicable law or agreed to in writing, software
             * distributed under the License is distributed on an "AS IS" BASIS,
             * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
             * See the License for the specific language governing permissions and
             * limitations under the License.
             */
            package com.github.migration.timer;

            import org.quartz.Scheduler;
            import org.springframework.boot.autoconfigure.AutoConfiguration;
            import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
            import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
            import org.springframework.context.annotation.Bean;

            /**
             * Auto-configuration for transactional timer support.
             * <p>
             * This configuration is automatically applied when Quartz is on the classpath.
             * It provides the TimerCreateEventListener bean that handles transactional
             * timer creation events.
             * <p>
             * P2.7 Review 2 HIGH 2: Uses @AutoConfiguration and @Bean factory methods
             * instead of @Component scanning for explicit bean registration.
             * <p>
             * Generated by MigrateTransactionalTimerCreation recipe.
             */
            @AutoConfiguration
            @ConditionalOnClass({Scheduler.class, TimerCreateEvent.class})
            public class TimerAutoConfiguration {

                /**
                 * Creates the TimerCreateEventListener bean.
                 * <p>
                 * P2.7 Review 2 HIGH 2: Uses constructor injection via factory method parameter.
                 *
                 * @param scheduler the Quartz Scheduler (auto-wired by Spring)
                 * @return the configured TimerCreateEventListener
                 */
                @Bean
                @ConditionalOnMissingBean
                public TimerCreateEventListener timerCreateEventListener(Scheduler scheduler) {
                    return new TimerCreateEventListener(scheduler);
                }
            }
            """;
    }

    // ========== Visitor Implementation ==========

    private static class TransactionalTimerVisitor extends JavaIsoVisitor<ExecutionContext> {

        private final Accumulator acc;

        // State for current class
        private Set<String> timerServiceFieldNames = new HashSet<>();
        private String timeoutMethodName;
        private String className;
        private String classPackage;
        private boolean inTransactionalMethod;
        private boolean transformed;
        private boolean needsEventPublisherField;
        private boolean hasEscapePattern;  // P2.7 Review 1: Track escape patterns

        TransactionalTimerVisitor(Accumulator acc) {
            this.acc = acc;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // Save and reset state for nested classes
            Set<String> savedTimerServiceFieldNames = timerServiceFieldNames;
            String savedTimeoutMethodName = timeoutMethodName;
            String savedClassName = className;
            String savedClassPackage = classPackage;
            boolean savedTransformed = transformed;
            boolean savedNeedsEventPublisherField = needsEventPublisherField;
            boolean savedHasEscapePattern = hasEscapePattern;

            timerServiceFieldNames = new HashSet<>();
            timeoutMethodName = null;
            className = classDecl.getSimpleName();
            classPackage = detectClassPackage(getCursor());
            transformed = false;
            needsEventPublisherField = false;
            hasEscapePattern = false;

            // P2.7 Review 1: Pre-analyze the class for escape patterns
            analyzeClass(classDecl);
            analyzeForEscapePatterns(classDecl);

            // P2.7 Review 1: If escape patterns detected, skip transformation entirely
            if (hasEscapePattern) {
                // Restore state and return unchanged
                timerServiceFieldNames = savedTimerServiceFieldNames;
                timeoutMethodName = savedTimeoutMethodName;
                className = savedClassName;
                classPackage = savedClassPackage;
                transformed = savedTransformed;
                needsEventPublisherField = savedNeedsEventPublisherField;
                hasEscapePattern = savedHasEscapePattern;
                return classDecl;
            }

            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // If we transformed any timer creation, transform the TimerService field to EventPublisher
            if (transformed && needsEventPublisherField) {
                cd = transformTimerServiceField(cd, ctx);

                // P2.7 Review 8: Record transformation for file generation.
                // Use the classPackage which was captured during class analysis.
                if (classPackage != null && !classPackage.isEmpty()) {
                    String pkgPath = classPackage.replace('.', '/');
                    // Find the compilation unit to get source path
                    J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                    String sourcePath = cu.getSourcePath().toString().replace('\\', '/');
                    int idx = sourcePath.lastIndexOf("/" + pkgPath + "/");
                    if (idx > 0) {
                        String sourceRoot = sourcePath.substring(0, idx);
                        acc.recordTransformation(sourceRoot);
                    }
                }
            }

            // Restore state
            timerServiceFieldNames = savedTimerServiceFieldNames;
            timeoutMethodName = savedTimeoutMethodName;
            className = savedClassName;
            classPackage = savedClassPackage;
            transformed = savedTransformed;
            needsEventPublisherField = savedNeedsEventPublisherField;
            hasEscapePattern = savedHasEscapePattern;

            return cd;
        }

        /**
         * P2.7 Review 1: Analyze class for escape patterns that prevent safe transformation.
         * <p>
         * Escape patterns include:
         * - TimerService used as method parameter
         * - TimerService used as local variable
         * - Non-transactional timer calls (mixed usage)
         * - Date/Duration overloads that cannot be converted
         * - TimerConfig passed as variable (not inline new)
         */
        private void analyzeForEscapePatterns(J.ClassDeclaration classDecl) {
            // Check for class-level @Transactional
            boolean classLevelTransactional = hasTransactionalAnnotationOnClass(classDecl);

            // Use simple tree walking instead of visitor to avoid cursor issues
            analyzeClassBodyForEscapes(classDecl, classLevelTransactional);
        }

        private void analyzeClassBodyForEscapes(J.ClassDeclaration classDecl, boolean classLevelTransactional) {
            if (classDecl.getBody() == null) {
                return;
            }

            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    analyzeMethodForEscapes(md, classLevelTransactional);
                }
            }
        }

        private void analyzeMethodForEscapes(J.MethodDeclaration method, boolean classLevelTransactional) {
            // P2.7 Review 1 HIGH 4: Check for TimerService as method parameter
            for (Statement param : method.getParameters()) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    if (isTimerServiceType(vd.getTypeExpression())) {
                        hasEscapePattern = true;
                        return;
                    }
                }
            }

            // Track if we're in a @Transactional method
            boolean inTransactionalScope = classLevelTransactional || hasTransactionalAnnotationOnMethod(method);

            // Analyze method body
            if (method.getBody() != null) {
                analyzeBlockForEscapes(method.getBody(), inTransactionalScope);
            }
        }

        private void analyzeBlockForEscapes(J.Block block, boolean inTransactionalScope) {
            for (Statement stmt : block.getStatements()) {
                analyzeStatementForEscapes(stmt, inTransactionalScope);
            }
        }

        private void analyzeStatementForEscapes(Statement stmt, boolean inTransactionalScope) {
            if (stmt instanceof J.VariableDeclarations) {
                // P2.7 Review 1 HIGH 4: Check for TimerService as local variable
                J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                if (isTimerServiceType(vd.getTypeExpression())) {
                    hasEscapePattern = true;
                }
                // P2.7 Review 2 HIGH 1: Check initializers for timer calls
                // If the result of a timer creation is being stored, it's an escape
                // because publishEvent returns void, not Timer
                for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                    if (var.getInitializer() != null) {
                        // Check if initializer IS a timer creation call - escape because result is used
                        if (isTimerCreationInvocation(var.getInitializer())) {
                            hasEscapePattern = true;
                        } else {
                            analyzeExpressionTreeForEscapes(var.getInitializer(), inTransactionalScope);
                        }
                    }
                }
            } else if (stmt instanceof J.Assignment) {
                // P2.7 Review 2 HIGH 1: Check assignments for timer calls
                // If the result of a timer creation is being stored, it's an escape
                J.Assignment assign = (J.Assignment) stmt;
                if (isTimerCreationInvocation(assign.getAssignment())) {
                    hasEscapePattern = true;
                } else {
                    analyzeExpressionTreeForEscapes(assign.getAssignment(), inTransactionalScope);
                }
            } else if (stmt instanceof J.MethodInvocation) {
                analyzeMethodInvocationForEscapes((J.MethodInvocation) stmt, inTransactionalScope);
            } else if (stmt instanceof J.If) {
                J.If ifStmt = (J.If) stmt;
                analyzeExpressionTreeForEscapes(ifStmt.getIfCondition().getTree(), inTransactionalScope);
                if (ifStmt.getThenPart() instanceof J.Block) {
                    analyzeBlockForEscapes((J.Block) ifStmt.getThenPart(), inTransactionalScope);
                } else if (ifStmt.getThenPart() != null) {
                    analyzeStatementForEscapes(ifStmt.getThenPart(), inTransactionalScope);
                }
                if (ifStmt.getElsePart() != null) {
                    if (ifStmt.getElsePart().getBody() instanceof J.Block) {
                        analyzeBlockForEscapes((J.Block) ifStmt.getElsePart().getBody(), inTransactionalScope);
                    } else {
                        analyzeStatementForEscapes(ifStmt.getElsePart().getBody(), inTransactionalScope);
                    }
                }
            } else if (stmt instanceof J.ForLoop) {
                J.ForLoop forLoop = (J.ForLoop) stmt;
                if (forLoop.getBody() instanceof J.Block) {
                    analyzeBlockForEscapes((J.Block) forLoop.getBody(), inTransactionalScope);
                }
            } else if (stmt instanceof J.WhileLoop) {
                J.WhileLoop whileLoop = (J.WhileLoop) stmt;
                if (whileLoop.getBody() instanceof J.Block) {
                    analyzeBlockForEscapes((J.Block) whileLoop.getBody(), inTransactionalScope);
                }
            } else if (stmt instanceof J.Try) {
                J.Try tryStmt = (J.Try) stmt;
                analyzeBlockForEscapes(tryStmt.getBody(), inTransactionalScope);
                for (J.Try.Catch catchClause : tryStmt.getCatches()) {
                    analyzeBlockForEscapes(catchClause.getBody(), inTransactionalScope);
                }
                if (tryStmt.getFinally() != null) {
                    analyzeBlockForEscapes(tryStmt.getFinally(), inTransactionalScope);
                }
            } else if (stmt instanceof J.Return) {
                J.Return ret = (J.Return) stmt;
                if (ret.getExpression() != null) {
                    analyzeExpressionTreeForEscapes(ret.getExpression(), inTransactionalScope);
                }
            } else if (stmt instanceof Expression) {
                analyzeExpressionTreeForEscapes((Expression) stmt, inTransactionalScope);
            }
        }

        private void analyzeExpressionTreeForEscapes(Expression expr, boolean inTransactionalScope) {
            if (expr == null) {
                return;
            }
            if (expr instanceof J.MethodInvocation) {
                analyzeMethodInvocationForEscapes((J.MethodInvocation) expr, inTransactionalScope);
                // Also check arguments
                J.MethodInvocation mi = (J.MethodInvocation) expr;
                if (mi.getArguments() != null) {
                    for (Expression arg : mi.getArguments()) {
                        analyzeExpressionTreeForEscapes(arg, inTransactionalScope);
                    }
                }
            }
            // P2.7 Review 2 HIGH 1: Recursively check more expression types
            if (expr instanceof J.Binary) {
                J.Binary binary = (J.Binary) expr;
                analyzeExpressionTreeForEscapes(binary.getLeft(), inTransactionalScope);
                analyzeExpressionTreeForEscapes(binary.getRight(), inTransactionalScope);
            } else if (expr instanceof J.Assignment) {
                J.Assignment assign = (J.Assignment) expr;
                analyzeExpressionTreeForEscapes(assign.getAssignment(), inTransactionalScope);
            } else if (expr instanceof J.Parentheses) {
                J.Parentheses<?> parens = (J.Parentheses<?>) expr;
                if (parens.getTree() instanceof Expression) {
                    analyzeExpressionTreeForEscapes((Expression) parens.getTree(), inTransactionalScope);
                }
            } else if (expr instanceof J.Ternary) {
                J.Ternary ternary = (J.Ternary) expr;
                analyzeExpressionTreeForEscapes(ternary.getCondition(), inTransactionalScope);
                analyzeExpressionTreeForEscapes(ternary.getTruePart(), inTransactionalScope);
                analyzeExpressionTreeForEscapes(ternary.getFalsePart(), inTransactionalScope);
            } else if (expr instanceof J.TypeCast) {
                J.TypeCast cast = (J.TypeCast) expr;
                analyzeExpressionTreeForEscapes(cast.getExpression(), inTransactionalScope);
            } else if (expr instanceof J.Lambda) {
                // P2.7 Review 3 HIGH 2: Analyze lambda bodies for timer calls
                J.Lambda lambda = (J.Lambda) expr;
                if (lambda.getBody() instanceof J.Block) {
                    analyzeBlockForEscapes((J.Block) lambda.getBody(), inTransactionalScope);
                } else if (lambda.getBody() instanceof Expression) {
                    analyzeExpressionTreeForEscapes((Expression) lambda.getBody(), inTransactionalScope);
                }
            } else if (expr instanceof J.NewClass) {
                // P2.7 Review 3 HIGH 2: Analyze anonymous class bodies for timer calls
                J.NewClass nc = (J.NewClass) expr;
                if (nc.getBody() != null) {
                    // This is an anonymous class - analyze its body statements
                    for (Statement stmt : nc.getBody().getStatements()) {
                        if (stmt instanceof J.MethodDeclaration) {
                            J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                            if (md.getBody() != null) {
                                // For anonymous class methods, treat as not in transactional scope
                                // unless the method itself has @Transactional
                                boolean methodTransactional = hasTransactionalAnnotationOnMethod(md);
                                analyzeBlockForEscapes(md.getBody(), methodTransactional);
                            }
                        }
                    }
                }
                // Also analyze constructor arguments
                if (nc.getArguments() != null) {
                    for (Expression arg : nc.getArguments()) {
                        analyzeExpressionTreeForEscapes(arg, inTransactionalScope);
                    }
                }
            }
        }

        private void analyzeMethodInvocationForEscapes(J.MethodInvocation mi, boolean inTransactionalScope) {
            if (!isTimerServiceInvocationForAnalysis(mi)) {
                return;
            }

            String methodName = mi.getSimpleName();
            List<Expression> args = mi.getArguments();

            // P2.7 Review 1 HIGH 1: Non-transactional timer call = mixed usage = escape
            if (isTimerCreationMethod(methodName) && !inTransactionalScope) {
                hasEscapePattern = true;
                return;
            }

            // P2.7 Review 1 HIGH 1: Non-createTimer methods like getTimers() = escape
            if (!isTimerCreationMethod(methodName)) {
                hasEscapePattern = true;
                return;
            }

            // P2.7 Review 1 HIGH 3: Check for Date/Duration overloads
            if (args != null && !args.isEmpty()) {
                JavaType firstArgType = args.get(0).getType();
                if (firstArgType != null) {
                    if (TypeUtils.isOfClassType(firstArgType, "java.util.Date") ||
                        TypeUtils.isOfClassType(firstArgType, "java.time.Duration") ||
                        TypeUtils.isOfClassType(firstArgType, "jakarta.ejb.ScheduleExpression") ||
                        TypeUtils.isOfClassType(firstArgType, "javax.ejb.ScheduleExpression")) {
                        hasEscapePattern = true;
                        return;
                    }
                }
            }

            // P2.7 Review 1 HIGH 2 + Review 2 HIGH 3: Check for TimerConfig variable (not inline new)
            // Be conservative when type attribution is missing
            if (isTimerCreationMethod(methodName) && args != null) {
                Expression configArg = getTimerConfigArgForAnalysis(methodName, args);
                if (configArg != null) {
                    if (configArg instanceof J.NewClass) {
                        // inline new TimerConfig(...) is OK, check the type
                        J.NewClass nc = (J.NewClass) configArg;
                        if (isTimerConfigNewClass(nc)) {
                            // OK - inline TimerConfig with literal values
                        }
                    } else if (configArg instanceof J.Literal && ((J.Literal) configArg).getValue() == null) {
                        // null is OK (Serializable info)
                    } else if (configArg instanceof J.Literal) {
                        // String/number literal is OK (Serializable info)
                    } else if (configArg instanceof J.Identifier) {
                        // P2.7 Review 2 HIGH 3: Identifier could be TimerConfig or Serializable
                        // If we have type info, check it; otherwise be conservative
                        JavaType type = configArg.getType();
                        if (type != null) {
                            if (TypeUtils.isOfClassType(type, JAKARTA_TIMER_CONFIG) ||
                                TypeUtils.isOfClassType(type, JAVAX_TIMER_CONFIG)) {
                                // Definitely a TimerConfig variable - escape
                                hasEscapePattern = true;
                            }
                            // Otherwise it's likely Serializable info - OK
                        } else {
                            // No type info - conservatively mark as escape
                            hasEscapePattern = true;
                        }
                    } else {
                        // Unknown expression type - conservatively mark as escape
                        hasEscapePattern = true;
                    }
                }
            }
        }

        /**
         * Check if NewClass is a TimerConfig construction.
         */
        private boolean isTimerConfigNewClass(J.NewClass nc) {
            JavaType type = nc.getType();
            if (type != null) {
                return TypeUtils.isOfClassType(type, JAKARTA_TIMER_CONFIG) ||
                       TypeUtils.isOfClassType(type, JAVAX_TIMER_CONFIG);
            }
            // Fallback: check by simple name
            if (nc.getClazz() instanceof J.Identifier) {
                return "TimerConfig".equals(((J.Identifier) nc.getClazz()).getSimpleName());
            }
            return false;
        }

        private boolean isTimerCreationMethod(String methodName) {
            return "createTimer".equals(methodName) ||
                   "createSingleActionTimer".equals(methodName) ||
                   "createIntervalTimer".equals(methodName);
        }

        /**
         * P2.7 Review 2 HIGH 1: Check if an expression is a timer creation invocation.
         * Used to detect when timer creation result is stored (escape pattern).
         */
        private boolean isTimerCreationInvocation(Expression expr) {
            if (!(expr instanceof J.MethodInvocation)) {
                return false;
            }
            J.MethodInvocation mi = (J.MethodInvocation) expr;
            return isTimerServiceInvocationForAnalysis(mi) && isTimerCreationMethod(mi.getSimpleName());
        }

        private Expression getTimerConfigArgForAnalysis(String methodName, List<Expression> args) {
            if ("createIntervalTimer".equals(methodName) && args.size() >= 3) {
                return args.get(2);  // createIntervalTimer(delay, interval, config)
            } else if (args.size() >= 2) {
                return args.get(1);  // createTimer(delay, config) or createSingleActionTimer(delay, config)
            }
            return null;
        }

        /**
         * Check if method invocation is on TimerService without using cursor.
         */
        private boolean isTimerServiceInvocationForAnalysis(J.MethodInvocation mi) {
            Expression select = mi.getSelect();
            if (select == null) {
                return false;
            }

            if (select.getType() != null) {
                if (TypeUtils.isOfClassType(select.getType(), JAKARTA_TIMER_SERVICE) ||
                    TypeUtils.isOfClassType(select.getType(), JAVAX_TIMER_SERVICE)) {
                    return true;
                }
            }

            if (select instanceof J.Identifier) {
                return timerServiceFieldNames.contains(((J.Identifier) select).getSimpleName());
            }

            return false;
        }

        private boolean hasTransactionalAnnotationOnClass(J.ClassDeclaration classDecl) {
            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), SPRING_TRANSACTIONAL) ||
                    TypeUtils.isOfClassType(ann.getType(), JAKARTA_TRANSACTIONAL) ||
                    "Transactional".equals(ann.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasTransactionalAnnotationOnMethod(J.MethodDeclaration md) {
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), SPRING_TRANSACTIONAL) ||
                    TypeUtils.isOfClassType(ann.getType(), JAKARTA_TRANSACTIONAL) ||
                    "Transactional".equals(ann.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }

        private boolean isTimerServiceType(TypeTree typeExpr) {
            if (typeExpr == null) return false;
            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                return TypeUtils.isOfClassType(ident.getType(), JAKARTA_TIMER_SERVICE) ||
                       TypeUtils.isOfClassType(ident.getType(), JAVAX_TIMER_SERVICE) ||
                       "TimerService".equals(ident.getSimpleName());
            }
            return TypeUtils.isOfClassType(typeExpr.getType(), JAKARTA_TIMER_SERVICE) ||
                   TypeUtils.isOfClassType(typeExpr.getType(), JAVAX_TIMER_SERVICE);
        }

        private void analyzeClass(J.ClassDeclaration classDecl) {
            if (classDecl.getBody() == null) {
                return;
            }

            for (Statement stmt : classDecl.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (isTimerServiceField(vd)) {
                        for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                            timerServiceFieldNames.add(var.getSimpleName());
                        }
                    }
                } else if (stmt instanceof J.MethodDeclaration) {
                    J.MethodDeclaration md = (J.MethodDeclaration) stmt;
                    if (hasTimeoutAnnotation(md)) {
                        timeoutMethodName = md.getSimpleName();
                    }
                }
            }
        }

        private String detectClassPackage(Cursor cursor) {
            Cursor cuCursor = cursor.dropParentUntil(c -> c instanceof J.CompilationUnit);
            if (cuCursor == null) {
                return "";
            }
            J.CompilationUnit cu = cuCursor.getValue();
            if (cu.getPackageDeclaration() == null) {
                return "";
            }
            return cu.getPackageDeclaration().getExpression().print(new Cursor(null, cu)).trim();
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            // Check if method is @Transactional
            boolean wasInTransactional = inTransactionalMethod;

            inTransactionalMethod = hasTransactionalAnnotation(method);

            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            inTransactionalMethod = wasInTransactional;

            return md;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);

            // Only transform if we're in a @Transactional method
            if (!inTransactionalMethod) {
                return mi;
            }

            // Check if this is a timer service call
            if (!isTimerServiceInvocation(mi)) {
                return mi;
            }

            String methodName = mi.getSimpleName();
            List<Expression> args = mi.getArguments();

            // Handle createTimer(delay, info) or createTimer(delay, timerConfig)
            if ("createTimer".equals(methodName) || "createSingleActionTimer".equals(methodName)) {
                if (args != null && args.size() >= 2) {
                    mi = transformToTimerCreateEvent(mi, args.get(0), null, args.get(1), ctx);
                    transformed = true;
                    needsEventPublisherField = true;
                }
            }
            // Handle createIntervalTimer(delay, interval, timerConfig)
            else if ("createIntervalTimer".equals(methodName)) {
                if (args != null && args.size() >= 3) {
                    mi = transformToTimerCreateEvent(mi, args.get(0), args.get(1), args.get(2), ctx);
                    transformed = true;
                    needsEventPublisherField = true;
                }
            }

            return mi;
        }

        private J.MethodInvocation transformToTimerCreateEvent(
                J.MethodInvocation original,
                Expression delayExpr,
                Expression intervalExpr,
                Expression infoOrConfigExpr,
                ExecutionContext ctx) {

            // Extract info and persistent values from TimerConfig or direct arguments
            Expression infoExpr = null;
            boolean persistent = true;  // EJB default

            if (infoOrConfigExpr instanceof J.NewClass) {
                J.NewClass newClass = (J.NewClass) infoOrConfigExpr;
                if (isTimerConfigType(newClass)) {
                    List<Expression> configArgs = newClass.getArguments();
                    if (configArgs != null && configArgs.size() == 2) {
                        infoExpr = configArgs.get(0);
                        Expression persistentArg = configArgs.get(1);
                        if (persistentArg instanceof J.Literal) {
                            Object value = ((J.Literal) persistentArg).getValue();
                            persistent = !Boolean.FALSE.equals(value);
                        }
                    }
                }
            } else {
                // Direct info argument (createTimer(delay, info))
                infoExpr = infoOrConfigExpr;
            }

            // P2.7 Review 1 HIGH 6: Build the target method string with FQN for proper job class resolution
            String fqClassName = (classPackage != null && !classPackage.isEmpty())
                ? classPackage + "." + className
                : className;
            String targetMethod = fqClassName + "." + (timeoutMethodName != null ? timeoutMethodName : "timeout");

            // Add imports
            maybeAddImport(SPRING_EVENT_PUBLISHER);
            maybeAddImport(TIMER_CREATE_EVENT_FQN);

            // Use template with #{any()} placeholders to preserve type information
            // Keep using the original field name (timerService) to preserve type references
            // Parameters: select, delay, interval, info, persistent, targetMethod
            Expression select = original.getSelect();

            if (intervalExpr != null && infoExpr != null) {
                // createIntervalTimer with info
                JavaTemplate template = JavaTemplate.builder(
                        "#{any(org.springframework.context.ApplicationEventPublisher)}.publishEvent(new TimerCreateEvent(this, #{any(long)}, #{any(long)}, #{any(java.io.Serializable)}, " + persistent + ", \"" + targetMethod + "\"))"
                    )
                    .javaParser(JavaParser.fromJavaVersion()
                        .classpath("spring-context")
                        .dependsOn(TIMER_CREATE_EVENT_STUB))
                    .imports(TIMER_CREATE_EVENT_FQN, SPRING_EVENT_PUBLISHER)
                    .contextSensitive()
                    .build();
                return template.apply(getCursor(), original.getCoordinates().replace(), select, delayExpr, intervalExpr, infoExpr);
            } else if (intervalExpr != null) {
                // createIntervalTimer with null info
                JavaTemplate template = JavaTemplate.builder(
                        "#{any(org.springframework.context.ApplicationEventPublisher)}.publishEvent(new TimerCreateEvent(this, #{any(long)}, #{any(long)}, null, " + persistent + ", \"" + targetMethod + "\"))"
                    )
                    .javaParser(JavaParser.fromJavaVersion()
                        .classpath("spring-context")
                        .dependsOn(TIMER_CREATE_EVENT_STUB))
                    .imports(TIMER_CREATE_EVENT_FQN, SPRING_EVENT_PUBLISHER)
                    .contextSensitive()
                    .build();
                return template.apply(getCursor(), original.getCoordinates().replace(), select, delayExpr, intervalExpr);
            } else if (infoExpr != null) {
                // createTimer/createSingleActionTimer with info
                JavaTemplate template = JavaTemplate.builder(
                        "#{any(org.springframework.context.ApplicationEventPublisher)}.publishEvent(new TimerCreateEvent(this, #{any(long)}, 0, #{any(java.io.Serializable)}, " + persistent + ", \"" + targetMethod + "\"))"
                    )
                    .javaParser(JavaParser.fromJavaVersion()
                        .classpath("spring-context")
                        .dependsOn(TIMER_CREATE_EVENT_STUB))
                    .imports(TIMER_CREATE_EVENT_FQN, SPRING_EVENT_PUBLISHER)
                    .contextSensitive()
                    .build();
                return template.apply(getCursor(), original.getCoordinates().replace(), select, delayExpr, infoExpr);
            } else {
                // createTimer/createSingleActionTimer with null info
                JavaTemplate template = JavaTemplate.builder(
                        "#{any(org.springframework.context.ApplicationEventPublisher)}.publishEvent(new TimerCreateEvent(this, #{any(long)}, 0, null, " + persistent + ", \"" + targetMethod + "\"))"
                    )
                    .javaParser(JavaParser.fromJavaVersion()
                        .classpath("spring-context")
                        .dependsOn(TIMER_CREATE_EVENT_STUB))
                    .imports(TIMER_CREATE_EVENT_FQN, SPRING_EVENT_PUBLISHER)
                    .contextSensitive()
                    .build();
                return template.apply(getCursor(), original.getCoordinates().replace(), select, delayExpr);
            }
        }

        private J.ClassDeclaration transformTimerServiceField(J.ClassDeclaration cd, ExecutionContext ctx) {
            List<Statement> newStatements = new ArrayList<>();
            boolean transformed = false;

            for (Statement stmt : cd.getBody().getStatements()) {
                if (stmt instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) stmt;
                    if (isTimerServiceField(vd)) {
                        // Transform the field: TimerService -> ApplicationEventPublisher
                        // Also rename: timerService -> eventPublisher
                        vd = transformToEventPublisherField(vd);
                        transformed = true;

                        // Remove TimerService imports
                        doAfterVisit(new RemoveImport<>(JAKARTA_TIMER_SERVICE, true));
                        doAfterVisit(new RemoveImport<>(JAVAX_TIMER_SERVICE, true));
                        doAfterVisit(new RemoveImport<>(JAKARTA_RESOURCE, true));
                        doAfterVisit(new RemoveImport<>(JAVAX_RESOURCE, true));

                        // Add the TRANSFORMED vd, not the original stmt
                        newStatements.add(vd);
                        continue;
                    }
                }
                newStatements.add(stmt);
            }

            if (transformed) {
                maybeAddImport(SPRING_AUTOWIRED);
                maybeAddImport(SPRING_EVENT_PUBLISHER);
                return cd.withBody(cd.getBody().withStatements(newStatements));
            }
            return cd;
        }

        private J.VariableDeclarations transformToEventPublisherField(J.VariableDeclarations vd) {
            // Change type from TimerService to ApplicationEventPublisher
            // Keep the field name (timerService) to preserve type references
            TypeTree typeExpr = vd.getTypeExpression();
            JavaType.ShallowClass newType = JavaType.ShallowClass.build(SPRING_EVENT_PUBLISHER);

            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                ident = ident.withSimpleName("ApplicationEventPublisher").withType(newType);
                vd = vd.withTypeExpression(ident);
            }

            // Update variable type only (keep name unchanged)
            List<J.VariableDeclarations.NamedVariable> newVars = new ArrayList<>();
            for (J.VariableDeclarations.NamedVariable var : vd.getVariables()) {
                String varName = var.getSimpleName();
                JavaType.Variable varType = var.getVariableType();
                if (varType != null) {
                    varType = varType.withType(newType);
                } else {
                    varType = new JavaType.Variable(null, 0, varName, null, newType, null);
                }
                // Update the variable type, keep name unchanged
                J.Identifier nameIdent = var.getName().withFieldType(varType);
                var = var.withName(nameIdent).withVariableType(varType);
                newVars.add(var);
            }
            vd = vd.withVariables(newVars);

            // Update annotations: replace @Resource/@Inject with @Autowired in-place, preserve others
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean foundInjectionAnnotation = false;

            for (J.Annotation ann : vd.getLeadingAnnotations()) {
                if (isInjectionAnnotation(ann)) {
                    // Replace @Resource/@Inject with @Autowired, keeping the same prefix
                    if (!foundInjectionAnnotation) {
                        newAnnotations.add(createSimpleAnnotation("Autowired", SPRING_AUTOWIRED, ann.getPrefix()));
                        foundInjectionAnnotation = true;
                    }
                    // Skip additional injection annotations
                } else {
                    // Keep other annotations as-is
                    newAnnotations.add(ann);
                }
            }

            // If no injection annotation was found, add @Autowired at the beginning
            if (!foundInjectionAnnotation) {
                Space prefix = vd.getLeadingAnnotations().isEmpty()
                    ? vd.getPrefix()
                    : vd.getLeadingAnnotations().get(0).getPrefix();
                newAnnotations.add(0, createSimpleAnnotation("Autowired", SPRING_AUTOWIRED, prefix));
            }

            return vd.withLeadingAnnotations(newAnnotations);
        }

        private boolean isInjectionAnnotation(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), SPRING_AUTOWIRED) ||
                   TypeUtils.isOfClassType(ann.getType(), JAKARTA_RESOURCE) ||
                   TypeUtils.isOfClassType(ann.getType(), JAVAX_RESOURCE) ||
                   TypeUtils.isOfClassType(ann.getType(), "jakarta.inject.Inject") ||
                   TypeUtils.isOfClassType(ann.getType(), "javax.inject.Inject") ||
                   "Autowired".equals(ann.getSimpleName()) ||
                   "Resource".equals(ann.getSimpleName()) ||
                   "Inject".equals(ann.getSimpleName());
        }

        private J.Annotation createSimpleAnnotation(String simpleName, String fqn, Space prefix) {
            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    simpleName,
                    JavaType.ShallowClass.build(fqn),
                    null
                ),
                null
            );
        }

        // ========== Helper Methods ==========

        private boolean isTimerServiceField(J.VariableDeclarations varDecls) {
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr == null) return false;

            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                return TypeUtils.isOfClassType(ident.getType(), JAKARTA_TIMER_SERVICE) ||
                       TypeUtils.isOfClassType(ident.getType(), JAVAX_TIMER_SERVICE) ||
                       "TimerService".equals(ident.getSimpleName());
            }
            return TypeUtils.isOfClassType(typeExpr.getType(), JAKARTA_TIMER_SERVICE) ||
                   TypeUtils.isOfClassType(typeExpr.getType(), JAVAX_TIMER_SERVICE);
        }

        private boolean hasTimeoutAnnotation(J.MethodDeclaration md) {
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), JAKARTA_TIMEOUT) ||
                    TypeUtils.isOfClassType(ann.getType(), JAVAX_TIMEOUT) ||
                    "Timeout".equals(ann.getSimpleName())) {
                    return true;
                }
            }
            return false;
        }

        private boolean hasTransactionalAnnotation(J.MethodDeclaration md) {
            for (J.Annotation ann : md.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), SPRING_TRANSACTIONAL) ||
                    TypeUtils.isOfClassType(ann.getType(), JAKARTA_TRANSACTIONAL) ||
                    "Transactional".equals(ann.getSimpleName())) {
                    return true;
                }
            }
            // Also check class-level @Transactional
            Cursor classCursor = getCursor().dropParentUntil(c -> c instanceof J.ClassDeclaration);
            if (classCursor != null) {
                J.ClassDeclaration classDecl = classCursor.getValue();
                for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                    if (TypeUtils.isOfClassType(ann.getType(), SPRING_TRANSACTIONAL) ||
                        TypeUtils.isOfClassType(ann.getType(), JAKARTA_TRANSACTIONAL) ||
                        "Transactional".equals(ann.getSimpleName())) {
                        return true;
                    }
                }
            }
            return false;
        }

        private boolean isTimerServiceInvocation(J.MethodInvocation mi) {
            Expression select = mi.getSelect();
            if (select == null) {
                return false;
            }

            if (select.getType() != null) {
                if (TypeUtils.isOfClassType(select.getType(), JAKARTA_TIMER_SERVICE) ||
                    TypeUtils.isOfClassType(select.getType(), JAVAX_TIMER_SERVICE)) {
                    return true;
                }
            }

            if (select instanceof J.Identifier) {
                return timerServiceFieldNames.contains(((J.Identifier) select).getSimpleName());
            }

            return false;
        }

        private boolean isTimerConfigType(J.NewClass newClass) {
            if (newClass.getClazz() == null) {
                return false;
            }
            JavaType type = newClass.getType();
            if (type != null) {
                return TypeUtils.isOfClassType(type, JAKARTA_TIMER_CONFIG) ||
                       TypeUtils.isOfClassType(type, JAVAX_TIMER_CONFIG);
            }
            if (newClass.getClazz() instanceof J.Identifier) {
                return "TimerConfig".equals(((J.Identifier) newClass.getClazz()).getSimpleName());
            }
            return false;
        }

        // P2.7 Review 8: Removed detectSourceRoot(Cursor) method.
        // Source root detection is now done inline in visitClassDeclaration using
        // classPackage and getCursor().firstEnclosingOrThrow(J.CompilationUnit.class).
    }
}
