/*
 * Copyright 2021 - 2023 the original author or authors.
 * Modifications Copyright 2026 Torsten Liermann
 *
 * Modified: New recipe for classifying remaining EJB usages after migration
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

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.AddImport;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Classifies remaining EJB usages that haven't been migrated or marked yet.
 * <p>
 * This recipe scans for all {@code jakarta.ejb.*} and {@code javax.ejb.*} references
 * and adds {@code @NeedsReview} annotations to those that are not:
 * <ul>
 *   <li>Already converted by other recipes (e.g., @Stateless -> @Service)</li>
 *   <li>Already marked with Ejb* marker annotations from the migration-annotations module</li>
 *   <li>Explicitly allowed via the {@code allowedEjbTypes} configuration</li>
 * </ul>
 * <p>
 * Configure allowed types in project.yaml:
 * <pre>
 * migration:
 *   ejb:
 *     allowedTypes:
 *       - jakarta.ejb.Timer
 *       - jakarta.ejb.TimerHandle
 * </pre>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class ClassifyRemainingEjbUsage extends ScanningRecipe<ClassifyRemainingEjbUsage.Accumulator> {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final String MARKER_PACKAGE = "com.github.migration.annotations";

    /**
     * EJB types that are considered "already migrated" by other recipes.
     * These are annotation types that are directly transformed to Spring equivalents.
     */
    private static final Set<String> MIGRATED_ANNOTATIONS = Set.of(
        // Bean types - these become @Service, @Component, etc.
        "Stateless", "Stateful", "Singleton", "MessageDriven",
        // Transaction annotations - transformed to @Transactional
        "TransactionAttribute", "TransactionManagement",
        // Scheduling - transformed to @Scheduled or Quartz
        "Schedule", "Schedules",
        // Async - transformed to @Async
        "Asynchronous",
        // Injection - transformed to @Autowired
        "EJB"
    );

    /**
     * EJB types that are mapped to Ejb* marker annotations.
     * These are tracked via MapEjbAnnotationsToMarkers recipe.
     */
    private static final Set<String> MARKER_MAPPED_ANNOTATIONS = Set.of(
        "AccessTimeout", "ActivationConfigProperty", "AfterBegin", "AfterCompletion",
        "ApplicationException", "Asynchronous", "BeforeCompletion", "ConcurrencyManagement",
        "DependsOn", "EJB", "EJBs", "Init", "Local", "LocalBean", "LocalHome", "Lock",
        "MessageDriven", "PostActivate", "PrePassivate", "Remote", "RemoteHome", "Remove",
        "Schedule", "Schedules", "Singleton", "Startup", "Stateful", "StatefulTimeout",
        "Stateless", "Timeout", "TransactionAttribute", "TransactionManagement"
    );

    /**
     * EJB enums that are mapped to Ejb* marker enums.
     */
    private static final Set<String> MARKER_MAPPED_ENUMS = Set.of(
        "ConcurrencyManagementType", "LockType", "TransactionAttributeType", "TransactionManagementType"
    );

    @Override
    public String getDisplayName() {
        return "Classify remaining EJB usage";
    }

    @Override
    public String getDescription() {
        return "Finds all remaining jakarta.ejb/javax.ejb references that haven't been migrated " +
               "or marked yet, and adds @NeedsReview annotations to flag them for manual review.";
    }

    static class Accumulator {
        /** Files that contain EJB imports/usages and need processing */
        Set<String> filesWithEjbUsage = new LinkedHashSet<>();
        /** Loaded configuration (cached) */
        ProjectConfiguration config = null;
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (tree instanceof SourceFile) {
                    SourceFile sf = (SourceFile) tree;
                    String path = sf.getSourcePath().toString().replace('\\', '/');

                    // Only process Java files
                    if (!path.endsWith(".java")) {
                        return tree;
                    }

                    // Check if this file has any EJB imports or FQN type usages
                    if (sf instanceof J.CompilationUnit) {
                        J.CompilationUnit cu = (J.CompilationUnit) sf;

                        // Check imports first
                        for (J.Import imp : cu.getImports()) {
                            String importName = imp.getTypeName();
                            if (importName != null &&
                                (importName.startsWith("jakarta.ejb.") || importName.startsWith("javax.ejb."))) {
                                acc.filesWithEjbUsage.add(path);
                                return tree; // Already marked, skip FQN check
                            }
                        }

                        // Issue 2 Fix: Also check for FQN EJB types without imports
                        // Visit the tree to find any type references to jakarta.ejb.* or javax.ejb.*
                        new JavaIsoVisitor<ExecutionContext>() {
                            @Override
                            public J.Identifier visitIdentifier(J.Identifier id, ExecutionContext ctx) {
                                JavaType type = id.getType();
                                if (type instanceof JavaType.FullyQualified) {
                                    String fqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
                                    if (fqn.startsWith("jakarta.ejb.") || fqn.startsWith("javax.ejb.")) {
                                        acc.filesWithEjbUsage.add(path);
                                    }
                                }
                                return id;
                            }

                            // Issue 3 Fix: Also check J.FieldAccess for FQN patterns like jakarta.ejb.TimerService
                            @Override
                            public J.FieldAccess visitFieldAccess(J.FieldAccess fa, ExecutionContext ctx) {
                                String target = fa.toString();
                                if (target.startsWith("jakarta.ejb.") || target.startsWith("javax.ejb.")) {
                                    acc.filesWithEjbUsage.add(path);
                                }
                                return super.visitFieldAccess(fa, ctx);
                            }
                        }.visit(cu, ctx);
                    }
                }
                return tree;
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        if (acc.filesWithEjbUsage.isEmpty()) {
            return TreeVisitor.noop();
        }

        // Issue 3 Fix: Config is now loaded per-file in the visitor using extractProjectRoot
        return new EjbClassificationVisitor(acc.filesWithEjbUsage);
    }

    /**
     * Visitor that classifies remaining EJB usages and adds @NeedsReview.
     */
    private static class EjbClassificationVisitor extends JavaIsoVisitor<ExecutionContext> {

        private final Set<String> filesWithEjbUsage;
        private Set<String> allowedTypes;
        private boolean importAdded = false;

        // WFQ-005: Comment format for local variables (cannot have annotations)
        private static final String LOCAL_VAR_COMMENT_TEMPLATE =
            " TODO: NeedsReview - Remaining EJB reference: %s. " +
            "Evaluate if this EJB type needs migration or can be added to allowedEjbTypes ";

        EjbClassificationVisitor(Set<String> filesWithEjbUsage) {
            this.filesWithEjbUsage = filesWithEjbUsage;
            this.allowedTypes = new HashSet<>();
        }

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            String path = cu.getSourcePath().toString().replace('\\', '/');
            if (!filesWithEjbUsage.contains(path)) {
                return cu;
            }

            // Issue 3 Fix: Load config from this file's project root
            Path projectRoot = extractProjectRoot(cu.getSourcePath());
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(projectRoot);
            this.allowedTypes = new HashSet<>(config.getAllowedEjbTypes());

            // Reset per-file state
            importAdded = false;

            return super.visitCompilationUnit(cu, ctx);
        }

        @Override
        public J.Import visitImport(J.Import import_, ExecutionContext ctx) {
            String typeName = import_.getTypeName();
            if (typeName == null) {
                return import_;
            }

            // Only process EJB imports
            if (!typeName.startsWith("jakarta.ejb.") && !typeName.startsWith("javax.ejb.")) {
                return import_;
            }

            // Extract simple name
            String simpleName = typeName.substring(typeName.lastIndexOf('.') + 1);

            // Skip if it's a migrated or marker-mapped type
            if (isMigratedOrMapped(simpleName)) {
                return import_;
            }

            // Skip if explicitly allowed
            if (allowedTypes.contains(typeName)) {
                return import_;
            }

            // This is a remaining EJB import that needs review
            // We'll mark the classes/fields/methods that use it rather than the import itself
            return import_;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecl, ExecutionContext ctx) {
            J.VariableDeclarations v = super.visitVariableDeclarations(varDecl, ctx);

            // Check if type is an EJB type
            TypeTree typeExpr = v.getTypeExpression();
            if (typeExpr == null) {
                return v;
            }

            String ejbTypeName = getEjbTypeName(typeExpr);
            if (ejbTypeName == null) {
                return v;
            }

            // Skip if migrated/mapped or allowed
            String simpleName = ejbTypeName.substring(ejbTypeName.lastIndexOf('.') + 1);
            if (isMigratedOrMapped(simpleName) || allowedTypes.contains(ejbTypeName)) {
                return v;
            }

            // Issue 1 & 2 Fix: Check if this is a field/parameter or a local variable
            // Use cursor parent/grandparent inspection for accurate classification
            if (isFieldOrParameter()) {
                // It's a field or method parameter - apply @NeedsReview annotation
                // Skip if already has @NeedsReview
                if (hasNeedsReviewAnnotation(v.getLeadingAnnotations())) {
                    return v;
                }

                // Add @NeedsReview
                if (!importAdded) {
                    doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN, null, false));
                    importAdded = true;
                }

                J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                    calculatePrefix(v),
                    "Remaining EJB reference: " + simpleName,
                    ejbTypeName,
                    "Evaluate if this EJB type needs migration or can be added to allowedEjbTypes"
                );

                List<J.Annotation> newAnnotations = new ArrayList<>();
                newAnnotations.add(needsReviewAnn);
                newAnnotations.addAll(v.getLeadingAnnotations());

                return v.withLeadingAnnotations(newAnnotations);
            } else {
                // It's a local variable - add a comment instead (annotations are illegal)
                // Check if comment already exists (idempotency)
                if (hasNeedsReviewComment(v)) {
                    return v;
                }

                return addNeedsReviewComment(v, simpleName);
            }
        }

        /**
         * Issue 1 Fix: Adds a NeedsReview comment to a local variable declaration.
         * Java annotations cannot be placed on local variables without @Target(ElementType.LOCAL_VARIABLE).
         */
        private J.VariableDeclarations addNeedsReviewComment(J.VariableDeclarations vd, String ejbSimpleName) {
            String commentText = String.format(LOCAL_VAR_COMMENT_TEMPLATE, ejbSimpleName);

            // Add comment to the prefix
            List<Comment> existingComments = vd.getPrefix().getComments();
            List<Comment> newComments = new ArrayList<>(existingComments);
            // Use suffix with newline and indent to place comment on its own line
            String indent = extractIndent(vd.getPrefix());
            newComments.add(0, new TextComment(true, commentText, "\n" + indent, Markers.EMPTY));

            Space newPrefix = vd.getPrefix().withComments(newComments);
            return vd.withPrefix(newPrefix);
        }

        /**
         * Issue 1 Fix: Check if the variable declaration already has a NeedsReview comment.
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
         * Issue 1 & 2 Fix: Determines if this variable declaration is a field or method parameter.
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

            // If parent is ClassDeclaration directly (rare case), it's a field
            if (parent instanceof J.ClassDeclaration) {
                return true;
            }

            // All other cases are local variables (inside blocks)
            return false;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Check for EJB annotations on the class
            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                String ejbTypeName = getEjbAnnotationTypeName(ann);
                if (ejbTypeName == null) {
                    continue;
                }

                String simpleName = ejbTypeName.substring(ejbTypeName.lastIndexOf('.') + 1);
                if (isMigratedOrMapped(simpleName) || allowedTypes.contains(ejbTypeName)) {
                    continue;
                }

                // Skip if already has @NeedsReview with same category
                if (hasNeedsReviewForEjb(cd.getLeadingAnnotations(), simpleName)) {
                    continue;
                }

                // Add @NeedsReview
                if (!importAdded) {
                    doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN, null, false));
                    importAdded = true;
                }

                J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                    calculatePrefix(cd),
                    "Remaining EJB reference: " + simpleName,
                    "@" + simpleName,
                    "Evaluate if this EJB annotation needs migration or can be added to allowedEjbTypes"
                );

                List<J.Annotation> newAnnotations = new ArrayList<>();
                newAnnotations.add(needsReviewAnn);
                newAnnotations.addAll(cd.getLeadingAnnotations());

                return cd.withLeadingAnnotations(newAnnotations);
            }

            // Check for EJB interfaces in implements/extends
            if (cd.getImplements() != null) {
                for (TypeTree impl : cd.getImplements()) {
                    String ejbTypeName = getEjbTypeName(impl);
                    if (ejbTypeName != null) {
                        String simpleName = ejbTypeName.substring(ejbTypeName.lastIndexOf('.') + 1);
                        if (!isMigratedOrMapped(simpleName) && !allowedTypes.contains(ejbTypeName)) {
                            if (!hasNeedsReviewForEjb(cd.getLeadingAnnotations(), simpleName)) {
                                if (!importAdded) {
                                    doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN, null, false));
                                    importAdded = true;
                                }

                                J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                                    calculatePrefix(cd),
                                    "Remaining EJB reference: " + simpleName,
                                    "implements " + simpleName,
                                    "Evaluate if this EJB interface needs migration or can be added to allowedEjbTypes"
                                );

                                List<J.Annotation> newAnnotations = new ArrayList<>();
                                newAnnotations.add(needsReviewAnn);
                                newAnnotations.addAll(cd.getLeadingAnnotations());

                                return cd.withLeadingAnnotations(newAnnotations);
                            }
                        }
                    }
                }
            }

            return cd;
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration m = super.visitMethodDeclaration(method, ctx);

            // Check for EJB annotations on the method
            for (J.Annotation ann : m.getLeadingAnnotations()) {
                String ejbTypeName = getEjbAnnotationTypeName(ann);
                if (ejbTypeName == null) {
                    continue;
                }

                String simpleName = ejbTypeName.substring(ejbTypeName.lastIndexOf('.') + 1);
                if (isMigratedOrMapped(simpleName) || allowedTypes.contains(ejbTypeName)) {
                    continue;
                }

                // Skip if already has @NeedsReview
                if (hasNeedsReviewForEjb(m.getLeadingAnnotations(), simpleName)) {
                    continue;
                }

                // Add @NeedsReview
                if (!importAdded) {
                    doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN, null, false));
                    importAdded = true;
                }

                J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                    calculatePrefix(m),
                    "Remaining EJB reference: " + simpleName,
                    "@" + simpleName,
                    "Evaluate if this EJB annotation needs migration or can be added to allowedEjbTypes"
                );

                List<J.Annotation> newAnnotations = new ArrayList<>();
                newAnnotations.add(needsReviewAnn);
                newAnnotations.addAll(m.getLeadingAnnotations());

                return m.withLeadingAnnotations(newAnnotations);
            }

            // Check method parameters for EJB types
            // (Handled by visitVariableDeclarations for parameters)

            // Check return type for EJB types
            TypeTree returnType = m.getReturnTypeExpression();
            if (returnType != null) {
                String ejbTypeName = getEjbTypeName(returnType);
                if (ejbTypeName != null) {
                    String simpleName = ejbTypeName.substring(ejbTypeName.lastIndexOf('.') + 1);
                    if (!isMigratedOrMapped(simpleName) && !allowedTypes.contains(ejbTypeName)) {
                        if (!hasNeedsReviewForEjb(m.getLeadingAnnotations(), simpleName)) {
                            if (!importAdded) {
                                doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN, null, false));
                                importAdded = true;
                            }

                            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                                calculatePrefix(m),
                                "Remaining EJB reference: " + simpleName,
                                "return type " + simpleName,
                                "Evaluate if this EJB type needs migration or can be added to allowedEjbTypes"
                            );

                            List<J.Annotation> newAnnotations = new ArrayList<>();
                            newAnnotations.add(needsReviewAnn);
                            newAnnotations.addAll(m.getLeadingAnnotations());

                            return m.withLeadingAnnotations(newAnnotations);
                        }
                    }
                }
            }

            // WFQ-011: Check throws clause for EJB exception types
            if (m.getThrows() != null) {
                for (NameTree thrownType : m.getThrows()) {
                    String ejbTypeName = getEjbTypeName(thrownType);
                    if (ejbTypeName != null) {
                        String simpleName = ejbTypeName.substring(ejbTypeName.lastIndexOf('.') + 1);
                        if (!isMigratedOrMapped(simpleName) && !allowedTypes.contains(ejbTypeName)) {
                            if (!hasNeedsReviewForEjb(m.getLeadingAnnotations(), simpleName)) {
                                if (!importAdded) {
                                    doAfterVisit(new AddImport<>(NEEDS_REVIEW_FQN, null, false));
                                    importAdded = true;
                                }

                                J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                                    calculatePrefix(m),
                                    "Remaining EJB reference: " + simpleName,
                                    "throws clause " + simpleName,
                                    "Evaluate if this EJB exception needs migration or can be added to allowedEjbTypes"
                                );

                                List<J.Annotation> newAnnotations = new ArrayList<>();
                                newAnnotations.add(needsReviewAnn);
                                newAnnotations.addAll(m.getLeadingAnnotations());

                                return m.withLeadingAnnotations(newAnnotations);
                            }
                        }
                    }
                }
            }

            return m;
        }

        /**
         * Checks if the simple name corresponds to a migrated or marker-mapped EJB type.
         */
        private boolean isMigratedOrMapped(String simpleName) {
            return MIGRATED_ANNOTATIONS.contains(simpleName) ||
                   MARKER_MAPPED_ANNOTATIONS.contains(simpleName) ||
                   MARKER_MAPPED_ENUMS.contains(simpleName);
        }

        /**
         * Gets the EJB type name from a type tree, if it's an EJB type.
         */
        private String getEjbTypeName(TypeTree typeTree) {
            if (typeTree == null) {
                return null;
            }

            JavaType type = typeTree.getType();
            if (type instanceof JavaType.FullyQualified) {
                String fqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
                if (fqn.startsWith("jakarta.ejb.") || fqn.startsWith("javax.ejb.")) {
                    return fqn;
                }
            }

            // Fallback: check simple name for known EJB types
            if (typeTree instanceof J.Identifier) {
                String simpleName = ((J.Identifier) typeTree).getSimpleName();
                // Known EJB interfaces/classes that might be used
                if (isKnownEjbType(simpleName)) {
                    return "jakarta.ejb." + simpleName;
                }
            }

            return null;
        }

        /**
         * WFQ-011: Gets EJB type name from NameTree (used for throws clauses).
         */
        private String getEjbTypeName(NameTree nameTree) {
            if (nameTree == null) {
                return null;
            }

            JavaType type = nameTree.getType();
            if (type instanceof JavaType.FullyQualified) {
                String fqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
                if (fqn.startsWith("jakarta.ejb.") || fqn.startsWith("javax.ejb.")) {
                    return fqn;
                }
            }

            // Fallback: check simple name for known EJB types
            if (nameTree instanceof J.Identifier) {
                String simpleName = ((J.Identifier) nameTree).getSimpleName();
                if (isKnownEjbType(simpleName)) {
                    return "jakarta.ejb." + simpleName;
                }
            }

            return null;
        }

        /**
         * Gets the EJB annotation type name, if it's an EJB annotation.
         */
        private String getEjbAnnotationTypeName(J.Annotation ann) {
            JavaType type = ann.getType();
            if (type instanceof JavaType.FullyQualified) {
                String fqn = ((JavaType.FullyQualified) type).getFullyQualifiedName();
                if (fqn.startsWith("jakarta.ejb.") || fqn.startsWith("javax.ejb.")) {
                    return fqn;
                }
            }

            // Fallback: check simple name
            String simpleName = ann.getSimpleName();
            if (isKnownEjbAnnotation(simpleName)) {
                return "jakarta.ejb." + simpleName;
            }

            return null;
        }

        /**
         * Known EJB interfaces/classes (non-annotation types).
         */
        private boolean isKnownEjbType(String simpleName) {
            // WFQ-011: Added EJBAccessException to handle context-related exceptions
            return Set.of(
                "Timer", "TimerService", "TimerHandle", "TimerConfig",
                "SessionContext", "EJBContext", "EJBObject", "EJBHome",
                "EJBLocalObject", "EJBLocalHome", "EJBException",
                "MessageDrivenContext", "ScheduleExpression",
                "EJBAccessException"
            ).contains(simpleName);
        }

        /**
         * Known EJB annotations by simple name.
         */
        private boolean isKnownEjbAnnotation(String simpleName) {
            return MARKER_MAPPED_ANNOTATIONS.contains(simpleName);
        }

        /**
         * Checks if annotations already contain @NeedsReview.
         */
        private boolean hasNeedsReviewAnnotation(List<J.Annotation> annotations) {
            return annotations.stream().anyMatch(a -> "NeedsReview".equals(a.getSimpleName()));
        }

        /**
         * Checks if annotations already contain @NeedsReview for the specific EJB type.
         */
        private boolean hasNeedsReviewForEjb(List<J.Annotation> annotations, String ejbSimpleName) {
            for (J.Annotation ann : annotations) {
                if (!"NeedsReview".equals(ann.getSimpleName())) {
                    continue;
                }
                // Check if the reason mentions this EJB type
                if (ann.getArguments() != null) {
                    for (Expression arg : ann.getArguments()) {
                        if (arg.toString().contains(ejbSimpleName)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /**
         * Calculate prefix for new annotation.
         */
        private Space calculatePrefix(J.VariableDeclarations v) {
            if (!v.getLeadingAnnotations().isEmpty()) {
                return v.getLeadingAnnotations().get(0).getPrefix();
            }
            return v.getPrefix();
        }

        private Space calculatePrefix(J.ClassDeclaration cd) {
            if (!cd.getLeadingAnnotations().isEmpty()) {
                return cd.getLeadingAnnotations().get(0).getPrefix();
            }
            return cd.getPrefix();
        }

        private Space calculatePrefix(J.MethodDeclaration md) {
            if (!md.getLeadingAnnotations().isEmpty()) {
                return md.getLeadingAnnotations().get(0).getPrefix();
            }
            return md.getPrefix();
        }

        /**
         * Creates a @NeedsReview annotation with the specified attributes.
         */
        private J.Annotation createNeedsReviewAnnotation(Space prefix, String reason, String originalCode, String suggestedAction) {
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
            arguments.add(createStringAssignment("reason", reason, false));

            // category = NeedsReview.Category.MANUAL_MIGRATION
            arguments.add(createCategoryAssignment("MANUAL_MIGRATION"));

            // originalCode = "..."
            arguments.add(createStringAssignment("originalCode", originalCode, true));

            // suggestedAction = "..."
            arguments.add(createStringAssignment("suggestedAction", suggestedAction, true));

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

        private JRightPadded<Expression> createStringAssignment(String key, String value, boolean leadingSpace) {
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

        private JRightPadded<Expression> createCategoryAssignment(String categoryName) {
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

        /**
         * Issue 3 Fix: Extracts the project root directory from the given source path.
         * Walks up the directory tree looking for pom.xml, build.gradle, or project.yaml.
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
}
