package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;
import org.openrewrite.text.PlainText;

import org.openrewrite.Cursor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Generates a MIGRATION-REVIEW.md report listing all @NeedsReview annotations.
 * <p>
 * This recipe should be run after all migration recipes to generate a summary
 * of items requiring manual review, grouped by category.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class GenerateMigrationReport extends ScanningRecipe<GenerateMigrationReport.Accumulator> {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final String EJB_MARKER_PACKAGE = "com.github.migration.annotations";

    // All Ejb* marker annotations indicating non-migrated EJB constructs
    private static final Set<String> EJB_MARKER_ANNOTATIONS = new LinkedHashSet<>(Arrays.asList(
        "EjbAccessTimeout",
        "EjbActivationConfigProperty",
        "EjbAfterBegin",
        "EjbAfterCompletion",
        "EjbApplicationException",
        "EjbAsynchronous",
        "EjbBeforeCompletion",
        "EjbConcurrencyManagement",
        "EjbConcurrencyManagementType",
        "EjbDependsOn",
        "EjbEJB",
        "EjbEJBs",
        "EjbInit",
        "EjbLocal",
        "EjbLocalBean",
        "EjbLocalHome",
        "EjbLock",
        "EjbLockType",
        "EjbMessageDriven",
        "EjbPostActivate",
        "EjbPrePassivate",
        "EjbQuartzSchedule",
        "EjbQuartzTimerService",
        "EjbRemote",
        "EjbRemoteHome",
        "EjbRemove",
        "EjbSchedule",
        "EjbSchedules",
        "EjbSingleton",
        "EjbStartup",
        "EjbStateful",
        "EjbStatefulTimeout",
        "EjbStateless",
        "EjbTimeout",
        "EjbTransactionAttribute",
        "EjbTransactionAttributeType",
        "EjbTransactionManagement",
        "EjbTransactionManagementType"
    ));

    // Standard source root patterns in order of preference
    private static final List<String> SOURCE_ROOT_PATTERNS = Arrays.asList(
        "src/main/java",
        "src/java",
        "src",
        "java"
    );

    @Override
    public String getDisplayName() {
        return "Generate Migration Review Report";
    }

    @Override
    public String getDescription() {
        return "Scans for @NeedsReview annotations and Ejb* marker annotations, then generates a " +
               "MIGRATION-REVIEW.md report with all items requiring manual review. " +
               "Ejb* markers indicate EJB constructs that could not be automatically migrated.";
    }

    static class ReviewItem implements Comparable<ReviewItem> {
        String sourcePath;
        String className;
        String memberName; // method or field name, null for class-level
        String reason;
        String category;
        String originalCode;
        String suggestedAction;

        @Override
        public int compareTo(ReviewItem other) {
            // Sort by sourcePath, then className, then memberName
            int cmp = this.sourcePath.compareTo(other.sourcePath);
            if (cmp != 0) return cmp;
            cmp = this.className.compareTo(other.className);
            if (cmp != 0) return cmp;
            String m1 = this.memberName != null ? this.memberName : "";
            String m2 = other.memberName != null ? other.memberName : "";
            return m1.compareTo(m2);
        }

        @Override
        public String toString() {
            return String.format("- **%s** `%s`%s\n  - File: `%s`\n  - Reason: %s\n  - Original: `%s`\n  - Action: %s\n",
                formatCategoryName(category),
                className,
                memberName != null ? "." + memberName : "",
                sourcePath,
                reason,
                originalCode,
                suggestedAction);
        }

        private static String formatCategoryName(String category) {
            return Arrays.stream(category.split("_"))
                .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
        }
    }

    /**
     * Represents an EJB marker annotation found in the code.
     * These markers indicate EJB constructs that could not be automatically migrated.
     */
    static class EjbMarkerItem implements Comparable<EjbMarkerItem> {
        String sourcePath;
        String className;
        String memberName; // method or field name, null for class-level
        String markerType; // e.g., "EjbSchedule", "EjbStateful"
        String rawAnnotation; // The full annotation text for context

        @Override
        public int compareTo(EjbMarkerItem other) {
            // Sort by markerType, then sourcePath, then className, then memberName
            int cmp = this.markerType.compareTo(other.markerType);
            if (cmp != 0) return cmp;
            cmp = this.sourcePath.compareTo(other.sourcePath);
            if (cmp != 0) return cmp;
            cmp = this.className.compareTo(other.className);
            if (cmp != 0) return cmp;
            String m1 = this.memberName != null ? this.memberName : "";
            String m2 = other.memberName != null ? other.memberName : "";
            return m1.compareTo(m2);
        }

        @Override
        public String toString() {
            String location = memberName != null ? className + "." + memberName : className;
            // Handle multi-line annotations: use fenced code block for readability
            String annotationRendered = rawAnnotation.contains("\n")
                ? "\n  ```java\n  " + rawAnnotation.replace("\n", "\n  ") + "\n  ```"
                : "`" + rawAnnotation + "`";
            return String.format("- `%s` in `%s`\n  - File: `%s`\n  - Annotation: %s\n",
                location,
                sourcePath,
                sourcePath,
                annotationRendered);
        }
    }

    static class Accumulator {
        // WFQ-007: Per-module tracking for multi-module projects
        Map<String, List<ReviewItem>> itemsByModule = new LinkedHashMap<>();
        Map<String, List<EjbMarkerItem>> markersByModule = new LinkedHashMap<>();
        String projectRoot = null;
        Set<String> detectedSourceRoots = new LinkedHashSet<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new JavaIsoVisitor<ExecutionContext>() {
            @Override
            public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
                String sourcePath = cu.getSourcePath().toString().replace('\\', '/');

                // Load project configuration for source root patterns
                ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                        extractProjectRoot(cu.getSourcePath()));

                // Build combined list of source root patterns (config + defaults)
                List<String> patterns = new ArrayList<>();
                patterns.addAll(config.getMainSourceRoots());
                patterns.addAll(config.getTestSourceRoots());
                // Add fallback defaults for legacy structures
                for (String pattern : SOURCE_ROOT_PATTERNS) {
                    if (!patterns.contains(pattern)) {
                        patterns.add(pattern);
                    }
                }

                // Detect source roots from Java files using patterns
                // Pattern must be followed by "/" to ensure it's a directory, not file extension
                for (String pattern : patterns) {
                    String dirPattern = pattern + "/";
                    int idx = sourcePath.indexOf(dirPattern);
                    if (idx >= 0) {
                        String detectedRoot = sourcePath.substring(0, idx);
                        acc.detectedSourceRoots.add(detectedRoot);
                        break; // Use first matching pattern for this file
                    }
                }

                return super.visitCompilationUnit(cu, ctx);
            }

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                String className = getFullyQualifiedClassName(cu, classDecl);
                String sourcePath = cu.getSourcePath().toString();
                String modulePath = getModulePath(sourcePath);

                // Check class-level @NeedsReview
                for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                    if (isNeedsReviewAnnotation(ann, cu)) {
                        acc.itemsByModule.computeIfAbsent(modulePath, k -> new ArrayList<>())
                            .add(extractReviewItem(ann, sourcePath, className, null));
                    }
                    // Check class-level Ejb* marker annotations
                    String ejbMarkerType = getEjbMarkerType(ann, cu);
                    if (ejbMarkerType != null) {
                        acc.markersByModule.computeIfAbsent(modulePath, k -> new ArrayList<>())
                            .add(extractEjbMarkerItem(ann, sourcePath, className, null, ejbMarkerType));
                    }
                }

                return cd;
            }

            /**
             * Extracts the module path from a source path by finding the pom.xml directory.
             * For "module-a/src/main/java/..." returns "module-a".
             * For root-level sources returns "".
             */
            private String getModulePath(String sourcePath) {
                // Normalize path separators
                sourcePath = sourcePath.replace('\\', '/');
                int srcIndex = sourcePath.indexOf("/src/");
                if (srcIndex > 0) {
                    return sourcePath.substring(0, srcIndex);
                }
                return "";
            }

            /**
             * Gets the fully qualified class name including enclosing classes for nested/inner classes.
             * For example: com.example.OuterClass$InnerClass or com.example.OuterClass.InnerClass
             */
            private String getFullyQualifiedClassName(J.CompilationUnit cu, J.ClassDeclaration cd) {
                String packageName = cu.getPackageDeclaration() != null
                    ? cu.getPackageDeclaration().getExpression().toString()
                    : "";

                // Build class name by traversing enclosing classes
                List<String> classNames = new ArrayList<>();
                classNames.add(cd.getSimpleName());

                // Walk up the cursor to find enclosing classes
                Cursor cursor = getCursor();
                while (cursor != null) {
                    Object value = cursor.getValue();
                    if (value instanceof J.ClassDeclaration && value != cd) {
                        classNames.add(0, ((J.ClassDeclaration) value).getSimpleName());
                    }
                    cursor = cursor.getParent();
                }

                String className = String.join("$", classNames);
                return packageName.isEmpty() ? className : packageName + "." + className;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
                J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

                J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                J.ClassDeclaration cd = getCursor().firstEnclosingOrThrow(J.ClassDeclaration.class);
                String sourcePath = cu.getSourcePath().toString();
                String className = getFullyQualifiedClassName(cu, cd);
                String modulePath = getModulePath(sourcePath);

                // Check method-level @NeedsReview
                for (J.Annotation ann : method.getLeadingAnnotations()) {
                    if (isNeedsReviewAnnotation(ann, cu)) {
                        acc.itemsByModule.computeIfAbsent(modulePath, k -> new ArrayList<>())
                            .add(extractReviewItem(ann, sourcePath, className, method.getSimpleName() + "()"));
                    }
                    // Check method-level Ejb* marker annotations
                    String ejbMarkerType = getEjbMarkerType(ann, cu);
                    if (ejbMarkerType != null) {
                        acc.markersByModule.computeIfAbsent(modulePath, k -> new ArrayList<>())
                            .add(extractEjbMarkerItem(ann, sourcePath, className, method.getSimpleName() + "()", ejbMarkerType));
                    }
                }

                return md;
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);

                // Only process field declarations (not local variables)
                // Fields are inside a class but NOT inside a method
                // firstEnclosing() returns the tree element, not a Cursor
                J.MethodDeclaration enclosingMethod = getCursor().firstEnclosing(J.MethodDeclaration.class);
                J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);

                // It's a field if we're inside a class but not inside a method
                if (enclosingClass != null && enclosingMethod == null) {
                    J.CompilationUnit cu = getCursor().firstEnclosingOrThrow(J.CompilationUnit.class);
                    String sourcePath = cu.getSourcePath().toString();
                    String className = getFullyQualifiedClassName(cu, enclosingClass);
                    String modulePath = getModulePath(sourcePath);

                    // Field-level annotations apply to all variables in the declaration
                    // (e.g., "@Marker Field a, b;" - the marker applies to both a and b)
                    List<J.Annotation> declAnnotations = varDecls.getLeadingAnnotations();

                    // Iterate all variables in the declaration (e.g., "Field a, b;")
                    for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                        String fieldName = var.getSimpleName();

                        // Check field-level @NeedsReview
                        for (J.Annotation ann : declAnnotations) {
                            if (isNeedsReviewAnnotation(ann, cu)) {
                                acc.itemsByModule.computeIfAbsent(modulePath, k -> new ArrayList<>())
                                    .add(extractReviewItem(ann, sourcePath, className, fieldName));
                            }
                            // Check field-level Ejb* marker annotations
                            String ejbMarkerType = getEjbMarkerType(ann, cu);
                            if (ejbMarkerType != null) {
                                acc.markersByModule.computeIfAbsent(modulePath, k -> new ArrayList<>())
                                    .add(extractEjbMarkerItem(ann, sourcePath, className, fieldName, ejbMarkerType));
                            }
                        }
                    }
                }

                return vd;
            }

            private boolean isNeedsReviewAnnotation(J.Annotation ann, J.CompilationUnit cu) {
                // Prefer FQN check if type info is available
                if (ann.getType() != null && TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN)) {
                    return true;
                }
                // Check for fully qualified annotation without type attribution (e.g., @com.github.rewrite.ejb.annotations.NeedsReview)
                String annotationFqn = getAnnotationFqn(ann);
                if (NEEDS_REVIEW_FQN.equals(annotationFqn)) {
                    return true;
                }
                // Fallback to simple name with import verification
                if ("NeedsReview".equals(ann.getSimpleName())) {
                    return hasImportForPackage(cu, "com.github.rewrite.ejb.annotations");
                }
                return false;
            }

            /**
             * Extracts the fully qualified name from an annotation's type expression.
             * Handles J.FieldAccess for FQN annotations like @com.github.migration.annotations.EjbSchedule
             */
            private String getAnnotationFqn(J.Annotation ann) {
                if (ann.getAnnotationType() instanceof J.FieldAccess) {
                    return extractFullyQualifiedName((J.FieldAccess) ann.getAnnotationType());
                }
                return null;
            }

            /**
             * Recursively extracts the fully qualified name from a J.FieldAccess.
             */
            private String extractFullyQualifiedName(J.FieldAccess fieldAccess) {
                StringBuilder fqn = new StringBuilder();
                buildFqn(fieldAccess, fqn);
                return fqn.toString();
            }

            private void buildFqn(Expression expr, StringBuilder sb) {
                if (expr instanceof J.FieldAccess) {
                    J.FieldAccess fa = (J.FieldAccess) expr;
                    buildFqn(fa.getTarget(), sb);
                    if (sb.length() > 0) {
                        sb.append(".");
                    }
                    sb.append(fa.getSimpleName());
                } else if (expr instanceof J.Identifier) {
                    sb.append(((J.Identifier) expr).getSimpleName());
                }
            }

            private ReviewItem extractReviewItem(J.Annotation ann, String sourcePath, String className, String memberName) {
                ReviewItem item = new ReviewItem();
                item.sourcePath = sourcePath;
                item.className = className;
                item.memberName = memberName;
                item.reason = extractStringAttribute(ann, "reason", "No reason specified");
                item.category = extractCategoryAttribute(ann);
                item.originalCode = extractStringAttribute(ann, "originalCode", "");
                item.suggestedAction = extractStringAttribute(ann, "suggestedAction", "Manual review required");
                return item;
            }

            private String extractStringAttribute(J.Annotation ann, String attrName, String defaultValue) {
                if (ann.getArguments() == null) return defaultValue;

                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                            if (attrName.equals(name)) {
                                Expression value = assignment.getAssignment();
                                if (value instanceof J.Literal) {
                                    Object literalValue = ((J.Literal) value).getValue();
                                    return literalValue != null ? literalValue.toString() : defaultValue;
                                }
                            }
                        }
                    }
                }
                return defaultValue;
            }

            private String extractCategoryAttribute(J.Annotation ann) {
                if (ann.getArguments() == null) return "OTHER";

                for (Expression arg : ann.getArguments()) {
                    if (arg instanceof J.Assignment) {
                        J.Assignment assignment = (J.Assignment) arg;
                        if (assignment.getVariable() instanceof J.Identifier) {
                            String name = ((J.Identifier) assignment.getVariable()).getSimpleName();
                            if ("category".equals(name)) {
                                Expression value = assignment.getAssignment();
                                // Handle both J.FieldAccess (NeedsReview.Category.MANUAL_MIGRATION)
                                // and J.Identifier (MANUAL_MIGRATION with static import)
                                if (value instanceof J.FieldAccess) {
                                    return ((J.FieldAccess) value).getSimpleName();
                                } else if (value instanceof J.Identifier) {
                                    return ((J.Identifier) value).getSimpleName();
                                }
                            }
                        }
                    }
                }
                return "OTHER";
            }

            /**
             * Checks if the annotation is an Ejb* marker annotation and returns its type.
             * Returns null if not an Ejb* marker.
             */
            private String getEjbMarkerType(J.Annotation ann, J.CompilationUnit cu) {
                // Check FQN first if type info is available
                if (ann.getType() != null) {
                    String fqn = ann.getType().toString();
                    if (fqn.startsWith(EJB_MARKER_PACKAGE + ".Ejb")) {
                        return fqn.substring(EJB_MARKER_PACKAGE.length() + 1);
                    }
                }
                // Check for fully qualified annotation without type attribution
                // (e.g., @com.github.migration.annotations.EjbSchedule)
                String annotationFqn = getAnnotationFqn(ann);
                if (annotationFqn != null && annotationFqn.startsWith(EJB_MARKER_PACKAGE + ".Ejb")) {
                    return annotationFqn.substring(EJB_MARKER_PACKAGE.length() + 1);
                }
                // Fallback to simple name check with import verification
                String simpleName = ann.getSimpleName();
                if (simpleName != null && EJB_MARKER_ANNOTATIONS.contains(simpleName)) {
                    // Verify import from com.github.migration.annotations before accepting
                    if (hasImportForPackage(cu, EJB_MARKER_PACKAGE)) {
                        return simpleName;
                    }
                }
                return null;
            }

            /**
             * Checks if the compilation unit has an import from the given package.
             * Handles both direct imports and wildcard imports.
             * Uses exact package matching to avoid false positives from prefix matches.
             */
            private boolean hasImportForPackage(J.CompilationUnit cu, String packageName) {
                for (J.Import imp : cu.getImports()) {
                    String importStr = imp.getQualid().toString();
                    // Check wildcard import (e.g., com.github.migration.annotations.*)
                    if (importStr.equals(packageName + ".*")) {
                        return true;
                    }
                    // Check direct import - extract package and verify exact match
                    // e.g., for import "com.github.migration.annotations.EjbSchedule"
                    // the package is "com.github.migration.annotations"
                    int lastDot = importStr.lastIndexOf('.');
                    if (lastDot > 0) {
                        String importPackage = importStr.substring(0, lastDot);
                        if (importPackage.equals(packageName)) {
                            return true;
                        }
                    }
                }
                return false;
            }

            /**
             * Creates an EjbMarkerItem from the given annotation.
             */
            private EjbMarkerItem extractEjbMarkerItem(J.Annotation ann, String sourcePath,
                    String className, String memberName, String markerType) {
                EjbMarkerItem item = new EjbMarkerItem();
                item.sourcePath = sourcePath;
                item.className = className;
                item.memberName = memberName;
                item.markerType = markerType;
                item.rawAnnotation = ann.printTrimmed(getCursor());
                return item;
            }
        };
    }

    // Define category order for deterministic output
    private static final List<String> CATEGORY_ORDER = Arrays.asList(
        "REMOTE_ACCESS", "CONCURRENCY", "CONFIGURATION", "SCHEDULING",
        "MESSAGING", "CDI_FEATURE", "TRANSACTION", "ASYNC", "SPRING_CONFIG",
        "MANUAL_MIGRATION", "OTHER"
    );

    @Override
    public Collection<? extends SourceFile> generate(Accumulator acc, ExecutionContext ctx) {
        // WFQ-007: Generate one report per module
        if (acc.itemsByModule.isEmpty() && acc.markersByModule.isEmpty()) {
            return Collections.emptyList();
        }

        // Collect all module paths (union of both maps)
        Set<String> allModules = new LinkedHashSet<>();
        allModules.addAll(acc.itemsByModule.keySet());
        allModules.addAll(acc.markersByModule.keySet());

        List<SourceFile> reports = new ArrayList<>();

        for (String modulePath : allModules) {
            List<ReviewItem> moduleItems = acc.itemsByModule.getOrDefault(modulePath, Collections.emptyList());
            List<EjbMarkerItem> moduleMarkers = acc.markersByModule.getOrDefault(modulePath, Collections.emptyList());

            if (moduleItems.isEmpty() && moduleMarkers.isEmpty()) {
                continue;
            }

            // Sort items for deterministic output
            Collections.sort(moduleItems);
            Collections.sort(moduleMarkers);

            // Group NeedsReview items by category, maintaining defined category order
            Map<String, List<ReviewItem>> byCategory = new LinkedHashMap<>();
            for (String category : CATEGORY_ORDER) {
                List<ReviewItem> categoryItems = moduleItems.stream()
                    .filter(item -> category.equals(item.category))
                    .collect(Collectors.toList());
                if (!categoryItems.isEmpty()) {
                    byCategory.put(category, categoryItems);
                }
            }
            // Add any unknown categories at the end
            for (ReviewItem item : moduleItems) {
                if (!CATEGORY_ORDER.contains(item.category)) {
                    byCategory.computeIfAbsent(item.category, k -> new ArrayList<>()).add(item);
                }
            }

            // Group EJB markers by marker type
            Map<String, List<EjbMarkerItem>> byMarkerType = new LinkedHashMap<>();
            for (EjbMarkerItem marker : moduleMarkers) {
                byMarkerType.computeIfAbsent(marker.markerType, k -> new ArrayList<>()).add(marker);
            }

            // Generate report content
            StringBuilder report = new StringBuilder();
            String moduleDisplayName = modulePath.isEmpty() ? "Root Project" : modulePath;
            report.append("# Migration Review Report\n\n");
            report.append(String.format("**Module:** `%s`\n\n", moduleDisplayName));
            report.append("This report lists all items requiring manual review after EJB-to-Spring migration.\n\n");

            // Summary section
            report.append("## Summary\n\n");

            // NeedsReview summary (if any)
            if (!moduleItems.isEmpty()) {
                report.append("### Items Marked with @NeedsReview\n\n");
                report.append("| Category | Count |\n");
                report.append("|----------|-------|\n");
                for (Map.Entry<String, List<ReviewItem>> entry : byCategory.entrySet()) {
                    report.append(String.format("| %s | %d |\n", formatCategory(entry.getKey()), entry.getValue().size()));
                }
                report.append(String.format("| **Total** | **%d** |\n\n", moduleItems.size()));
            }

            // EJB Markers summary (if any)
            if (!moduleMarkers.isEmpty()) {
                report.append("### EJB Marker Annotations (Non-Migrated Constructs)\n\n");
                report.append("These markers indicate EJB code that could not be automatically migrated and requires manual attention.\n\n");
                report.append("| Marker Type | Count |\n");
                report.append("|-------------|-------|\n");
                for (Map.Entry<String, List<EjbMarkerItem>> entry : byMarkerType.entrySet()) {
                    report.append(String.format("| @%s | %d |\n", entry.getKey(), entry.getValue().size()));
                }
                report.append(String.format("| **Total** | **%d** |\n\n", moduleMarkers.size()));
            }

            // Detailed NeedsReview sections by category
            if (!moduleItems.isEmpty()) {
                report.append("---\n\n");
                report.append("# Detailed @NeedsReview Items\n\n");
                for (Map.Entry<String, List<ReviewItem>> entry : byCategory.entrySet()) {
                    report.append(String.format("## %s\n\n", formatCategory(entry.getKey())));
                    for (ReviewItem item : entry.getValue()) {
                        report.append(item.toString());
                        report.append("\n");
                    }
                }
            }

            // Detailed EJB Marker sections by marker type
            if (!moduleMarkers.isEmpty()) {
                report.append("---\n\n");
                report.append("# EJB Marker Annotations\n\n");
                report.append("The following EJB constructs could not be automatically migrated. ");
                report.append("Each marker annotation preserves the original EJB configuration for manual migration.\n\n");
                for (Map.Entry<String, List<EjbMarkerItem>> entry : byMarkerType.entrySet()) {
                    report.append(String.format("## @%s\n\n", entry.getKey()));
                    for (EjbMarkerItem marker : entry.getValue()) {
                        report.append(marker.toString());
                        report.append("\n");
                    }
                }
            }

            report.append("\n---\n");
            report.append("*Generated by EJB-to-Spring Migration Recipes*\n");

            // Create the report file in the module directory
            Path reportPath = modulePath.isEmpty()
                ? Paths.get("MIGRATION-REVIEW.md")
                : Paths.get(modulePath, "MIGRATION-REVIEW.md");

            reports.add(
                PlainText.builder()
                    .sourcePath(reportPath)
                    .text(report.toString())
                    .build()
            );
        }

        return reports;
    }

    private String formatCategory(String category) {
        // Convert SNAKE_CASE to Title Case
        return Arrays.stream(category.split("_"))
            .map(s -> s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase())
            .reduce((a, b) -> a + " " + b)
            .orElse(category);
    }

    /**
     * Extracts the project root directory from the given source path.
     */
    private static Path extractProjectRoot(Path sourcePath) {
        if (sourcePath == null) {
            return Paths.get(System.getProperty("user.dir"));
        }
        Path current = sourcePath.toAbsolutePath().getParent();
        while (current != null) {
            if (Files.exists(current.resolve("pom.xml")) ||
                Files.exists(current.resolve("build.gradle")) ||
                Files.exists(current.resolve("project.yaml"))) {
                return current;
            }
            current = current.getParent();
        }
        return Paths.get(System.getProperty("user.dir"));
    }
}
