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
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Migrates @Singleton EJB annotations to Spring @Service.
 * <p>
 * Handles:
 * - @Singleton -> @Service (singleton is default in Spring)
 * - @Lock annotations are removed with @NeedsReview (Spring uses synchronized)
 * - @ConcurrencyManagement removed (handled differently in Spring)
 * - @Startup + @PostConstruct -> @EventListener(ApplicationReadyEvent.class)
 *   (EJB spec: @Startup beans are initialized before external client requests)
 * - @DependsOn -> @DependsOn (jakarta.ejb -> org.springframework.context.annotation)
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateSingletonToService extends Recipe {

    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";
    private static final String POST_CONSTRUCT_JAKARTA_FQN = "jakarta.annotation.PostConstruct";
    private static final String POST_CONSTRUCT_JAVAX_FQN = "javax.annotation.PostConstruct";
    private static final String EVENT_LISTENER_FQN = "org.springframework.context.event.EventListener";
    private static final String APPLICATION_READY_EVENT_FQN = "org.springframework.boot.context.event.ApplicationReadyEvent";
    private static final String LOCK_FQN = "jakarta.ejb.Lock";
    private static final String ACCESS_TIMEOUT_FQN = "jakarta.ejb.AccessTimeout";
    private static final String CONCURRENCY_MANAGEMENT_FQN = "jakarta.ejb.ConcurrencyManagement";

    @Override
    public String getDisplayName() {
        return "Migrate @Singleton to @Service";
    }

    @Override
    public String getDescription() {
        return "Converts EJB @Singleton annotations to Spring @Service. " +
               "Spring beans are singletons by default.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>("jakarta.ejb.Singleton", false),
                new UsesType<>("jakarta.ejb.Lock", false),
                new UsesType<>("jakarta.ejb.ConcurrencyManagement", false),
                new UsesType<>("jakarta.ejb.Startup", false),
                new UsesType<>("jakarta.ejb.DependsOn", false)
            ),
            new SingletonToComponentVisitor()
        );
    }

    private class SingletonToComponentVisitor extends JavaIsoVisitor<ExecutionContext> {
        private enum LockMode {
            READ,
            WRITE,
            UNKNOWN
        }

        private static class LockInfo {
            boolean hasLockAnnotations;
            boolean hasStartup;
            boolean hasAccessTimeout;
            boolean concurrencyManagementBean;
            LockMode classLockMode;
            final Map<UUID, LockMode> methodLocks = new HashMap<>();
        }

        private static class LockMigrationResult {
            final J.ClassDeclaration classDecl;
            final boolean needsReview;
            final String reason;
            final boolean appliedAny;
            final boolean hasReadLock;

            private LockMigrationResult(J.ClassDeclaration classDecl, boolean needsReview, String reason, boolean appliedAny, boolean hasReadLock) {
                this.classDecl = classDecl;
                this.needsReview = needsReview;
                this.reason = reason;
                this.appliedAny = appliedAny;
                this.hasReadLock = hasReadLock;
            }
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            // When annotations like @Lock are removed, OpenRewrite can leave behind whitespace-only
            // indentation lines in prefixes. Normalize those away to avoid patch noise.
            Space prefix = md.getPrefix();
            String ws = prefix.getWhitespace();
            String cleaned = ws;

            if (cleaned.contains("\n")) {
                // Collapse whitespace-only lines (a newline + indent + newline) to a single newline.
                cleaned = cleaned.replaceAll("\n[ \t]+\n", "\n");
            }

            // If this method prefix ends with indentation but the next printed segment starts with a newline
            // (e.g. modifier prefix), those trailing spaces would form a whitespace-only line. Trim them.
            Space nextPrefix = null;
            if (md.getLeadingAnnotations() != null && !md.getLeadingAnnotations().isEmpty()) {
                nextPrefix = md.getLeadingAnnotations().get(0).getPrefix();
            } else if (md.getModifiers() != null && !md.getModifiers().isEmpty()) {
                nextPrefix = md.getModifiers().get(0).getPrefix();
            }

            if (nextPrefix != null && nextPrefix.getWhitespace().startsWith("\n")) {
                cleaned = cleaned.replaceAll("[ \t]+$", "");
            }

            // If the first modifier starts with a newline, OpenRewrite will print an extra blank line.
            // Keep the line breaks in the method prefix and only keep indentation on the modifier.
            if (md.getModifiers() != null && !md.getModifiers().isEmpty()) {
                J.Modifier firstModifier = md.getModifiers().get(0);
                Space modifierPrefix = firstModifier.getPrefix();
                String modifierWs = modifierPrefix.getWhitespace();

                if (modifierWs.startsWith("\n") && cleaned.contains("\n")) {
                    String cleanedModifierWs = modifierWs.replaceFirst("^\n+", "");
                    if (!cleanedModifierWs.equals(modifierWs)) {
                        List<J.Modifier> newModifiers = new ArrayList<>(md.getModifiers());
                        newModifiers.set(0, firstModifier.withPrefix(modifierPrefix.withWhitespace(cleanedModifierWs)));
                        md = md.withModifiers(newModifiers);
                    }
                }
            }

            // Remove extra blank lines left behind when an annotation line was removed.
            // For the first statement in a class body, no leading blank line is expected.
            if (cleaned.startsWith("\n")) {
                if (isFirstStatementInEnclosingClass(method)) {
                    cleaned = cleaned.replaceFirst("^\n{2,}", "\n");
                } else {
                    cleaned = cleaned.replaceFirst("^\n{3,}", "\n\n");
                }
            }

            if (!cleaned.equals(ws)) {
                md = md.withPrefix(prefix.withWhitespace(cleaned));
            }

            return md;
        }

        private boolean isFirstStatementInEnclosingClass(J.MethodDeclaration method) {
            J.ClassDeclaration enclosingClass = getCursor().firstEnclosing(J.ClassDeclaration.class);
            if (enclosingClass == null || enclosingClass.getBody() == null) {
                return false;
            }

            for (Statement statement : enclosingClass.getBody().getStatements()) {
                if (statement instanceof J.Empty) {
                    continue;
                }
                return statement.getId().equals(method.getId());
            }
            return false;
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            LockInfo lockInfo = collectLockInfo(classDecl);
            boolean hasStartup = lockInfo.hasStartup;

            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // If @Startup was present, transform @PostConstruct to @EventListener(ApplicationReadyEvent.class)
            if (hasStartup && cd.getBody() != null) {
                List<Statement> newStatements = new ArrayList<>();
                boolean transformed = false;

                for (Statement stmt : cd.getBody().getStatements()) {
                    if (stmt instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                        J.MethodDeclaration transformedMethod = transformPostConstructToEventListener(method);
                        if (transformedMethod != method) {
                            transformed = true;
                            newStatements.add(transformedMethod);
                        } else {
                            newStatements.add(stmt);
                        }
                    } else {
                        newStatements.add(stmt);
                    }
                }

                if (transformed) {
                    cd = cd.withBody(cd.getBody().withStatements(newStatements));
                    maybeAddImport(EVENT_LISTENER_FQN);
                    maybeAddImport(APPLICATION_READY_EVENT_FQN);
                    maybeRemoveImport(POST_CONSTRUCT_JAKARTA_FQN);
                    maybeRemoveImport(POST_CONSTRUCT_JAVAX_FQN);
                }
            }

            if (lockInfo.hasLockAnnotations && cd.getBody() != null) {
                LockMigrationResult migration = migrateLocks(cd, lockInfo);
                cd = migration.classDecl;
                if (migration.needsReview) {
                    cd = addNeedsReviewAnnotation(cd, migration.reason, migration.hasReadLock);
                }
            }

            return cd;
        }

        private LockInfo collectLockInfo(J.ClassDeclaration classDecl) {
            LockInfo info = new LockInfo();

            for (J.Annotation ann : classDecl.getLeadingAnnotations()) {
                if (TypeUtils.isOfClassType(ann.getType(), LOCK_FQN)) {
                    info.hasLockAnnotations = true;
                    info.classLockMode = extractLockMode(ann);
                }
                if (TypeUtils.isOfClassType(ann.getType(), "jakarta.ejb.Startup")) {
                    info.hasStartup = true;
                }
                if (isAccessTimeoutAnnotation(ann)) {
                    info.hasAccessTimeout = true;
                }
                if (isConcurrencyManagementBean(ann)) {
                    info.concurrencyManagementBean = true;
                }
            }

            if (classDecl.getBody() != null) {
                for (Statement stmt : classDecl.getBody().getStatements()) {
                    if (!(stmt instanceof J.MethodDeclaration)) {
                        continue;
                    }
                    J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                    for (J.Annotation ann : method.getLeadingAnnotations()) {
                        if (TypeUtils.isOfClassType(ann.getType(), LOCK_FQN)) {
                            info.hasLockAnnotations = true;
                            info.methodLocks.put(method.getId(), extractLockMode(ann));
                        }
                        if (isAccessTimeoutAnnotation(ann)) {
                            info.hasAccessTimeout = true;
                        }
                    }
                }
            }

            return info;
        }

        private LockMigrationResult migrateLocks(J.ClassDeclaration cd, LockInfo lockInfo) {
            if (cd.getBody() == null || !lockInfo.hasLockAnnotations) {
                return new LockMigrationResult(cd, false, null, false, false);
            }

            Set<String> issues = new LinkedHashSet<>();
            boolean skipAll = lockInfo.concurrencyManagementBean;
            if (skipAll) {
                issues.add("@ConcurrencyManagement(BEAN) present");
            }

            LockMode classLockMode = lockInfo.classLockMode;
            if (classLockMode == LockMode.UNKNOWN) {
                issues.add("Unsupported class-level @Lock value");
                classLockMode = null;
            }

            List<Statement> newStatements = new ArrayList<>();
            boolean appliedAny = false;
            boolean hasReadLock = false;

            for (Statement stmt : cd.getBody().getStatements()) {
                if (!(stmt instanceof J.MethodDeclaration)) {
                    newStatements.add(stmt);
                    continue;
                }

                J.MethodDeclaration method = (J.MethodDeclaration) stmt;
                LockMode effectiveLock = lockInfo.methodLocks.get(method.getId());
                if (effectiveLock == null) {
                    effectiveLock = classLockMode;
                }

                if (effectiveLock == null) {
                    newStatements.add(stmt);
                    continue;
                }

                if (effectiveLock == LockMode.UNKNOWN) {
                    issues.add("@Lock on " + method.getSimpleName() + "() has unsupported value");
                    newStatements.add(stmt);
                    continue;
                }

                if (skipAll) {
                    newStatements.add(stmt);
                    continue;
                }

                if (method.getBody() == null) {
                    issues.add("@Lock on " + method.getSimpleName() + "() has no method body");
                    newStatements.add(stmt);
                    continue;
                }

                if (isStaticMethod(method)) {
                    issues.add("@Lock on static method " + method.getSimpleName() + "() cannot be auto-wrapped");
                    newStatements.add(stmt);
                    continue;
                }

                if (effectiveLock == LockMode.READ) {
                    hasReadLock = true;
                }

                J.MethodDeclaration wrapped = addSynchronizedModifier(method);
                appliedAny = true;
                newStatements.add(wrapped);
            }

            J.ClassDeclaration updated = cd;
            if (appliedAny) {
                updated = updated.withBody(updated.getBody().withStatements(newStatements));
            }

            if (lockInfo.hasAccessTimeout) {
                issues.add("@AccessTimeout present; lock timeouts not migrated");
            }

            // Only add @NeedsReview when READ locks exist (synchronized reduces concurrency)
            // or when there are other issues. WRITE-only with synchronized is semantically equivalent.
            boolean needsReview = hasReadLock || !issues.isEmpty();
            String reason = needsReview ? buildLockReviewReason(appliedAny, issues, hasReadLock) : null;
            return new LockMigrationResult(updated, needsReview, reason, appliedAny, hasReadLock);
        }

        private String buildLockReviewReason(boolean appliedAny, Set<String> issues, boolean hasReadLock) {
            StringBuilder reason = new StringBuilder();
            if (appliedAny) {
                reason.append("EJB @Lock annotations migrated to synchronized. ");
                if (hasReadLock) {
                    reason.append("For better READ/WRITE semantics, consider using ReadWriteLock instead. ");
                }
                if (!issues.isEmpty()) {
                    reason.append("Issues: ").append(String.join("; ", issues)).append(".");
                }
            } else {
                reason.append("EJB @Lock annotations removed. Automatic wrapping skipped: ");
                reason.append(String.join("; ", issues)).append(".");
            }
            return reason.toString();
        }

        private J.MethodDeclaration addSynchronizedModifier(J.MethodDeclaration method) {
            boolean hasSynchronized = method.getModifiers().stream()
                .anyMatch(mod -> mod.getType() == J.Modifier.Type.Synchronized);
            if (hasSynchronized) {
                return method;
            }

            J.Modifier synchronizedMod = new J.Modifier(
                Tree.randomId(),
                Space.SINGLE_SPACE,
                Markers.EMPTY,
                null,
                J.Modifier.Type.Synchronized,
                Collections.emptyList()
            );

            List<J.Modifier> modifiers = new ArrayList<>(method.getModifiers());
            int insertIndex = 0;
            for (int i = 0; i < modifiers.size(); i++) {
                J.Modifier.Type type = modifiers.get(i).getType();
                if (type == J.Modifier.Type.Private ||
                    type == J.Modifier.Type.Protected ||
                    type == J.Modifier.Type.Public) {
                    insertIndex = i + 1;
                    break;
                }
            }
            modifiers.add(insertIndex, synchronizedMod);
            return method.withModifiers(modifiers);
        }

        private boolean isStaticMethod(J.MethodDeclaration method) {
            for (J.Modifier modifier : method.getModifiers()) {
                if (modifier.getType() == J.Modifier.Type.Static) {
                    return true;
                }
            }
            return false;
        }


        private LockMode extractLockMode(J.Annotation annotation) {
            if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                return LockMode.WRITE;
            }
            Expression arg = annotation.getArguments().get(0);
            if (arg instanceof J.Assignment) {
                arg = ((J.Assignment) arg).getAssignment();
            }
            String name = null;
            if (arg instanceof J.FieldAccess) {
                name = ((J.FieldAccess) arg).getName().getSimpleName();
            } else if (arg instanceof J.Identifier) {
                name = ((J.Identifier) arg).getSimpleName();
            } else if (arg instanceof J.MemberReference) {
                name = ((J.MemberReference) arg).getReference().getSimpleName();
            }
            return parseLockModeName(name);
        }

        private LockMode parseLockModeName(String name) {
            if ("READ".equals(name)) {
                return LockMode.READ;
            }
            if ("WRITE".equals(name)) {
                return LockMode.WRITE;
            }
            return LockMode.UNKNOWN;
        }

        private boolean isAccessTimeoutAnnotation(J.Annotation annotation) {
            return TypeUtils.isOfClassType(annotation.getType(), ACCESS_TIMEOUT_FQN);
        }

        private boolean isConcurrencyManagementBean(J.Annotation annotation) {
            if (!TypeUtils.isOfClassType(annotation.getType(), CONCURRENCY_MANAGEMENT_FQN)) {
                return false;
            }
            if (annotation.getArguments() == null || annotation.getArguments().isEmpty()) {
                return false;
            }
            Expression arg = annotation.getArguments().get(0);
            if (arg instanceof J.Assignment) {
                arg = ((J.Assignment) arg).getAssignment();
            }
            return isEnumValue(arg, "BEAN");
        }

        private boolean isEnumValue(Expression expr, String expected) {
            if (expr instanceof J.FieldAccess) {
                return expected.equals(((J.FieldAccess) expr).getName().getSimpleName());
            }
            if (expr instanceof J.Identifier) {
                return expected.equals(((J.Identifier) expr).getSimpleName());
            }
            if (expr instanceof J.MemberReference) {
                return expected.equals(((J.MemberReference) expr).getReference().getSimpleName());
            }
            return false;
        }

        private J.ClassDeclaration addNeedsReviewAnnotation(J.ClassDeclaration cd, String reason, boolean hasReadLock) {
            if (reason == null) {
                return cd;
            }
            boolean hasNeedsReview = cd.getLeadingAnnotations().stream()
                .anyMatch(a -> a.getSimpleName().equals("NeedsReview"));
            if (hasNeedsReview) {
                return cd;
            }

            maybeAddImport(NEEDS_REVIEW_FQN);

            Space needsReviewPrefix = cd.getLeadingAnnotations().isEmpty()
                ? cd.getPrefix()
                : cd.getLeadingAnnotations().get(0).getPrefix();

            // Only suggest ReadWriteLock upgrade when READ locks were present
            String suggestedAction = hasReadLock
                ? "For READ/WRITE semantics, upgrade to ReadWriteLock: private final ReadWriteLock lock = new ReentrantReadWriteLock(); then use lock.readLock()/lock.writeLock()"
                : "Verify concurrency semantics";

            J.Annotation needsReviewAnn = createNeedsReviewAnnotation(
                reason,
                "CONCURRENCY",
                "@Lock annotations",
                suggestedAction,
                needsReviewPrefix
            );

            List<J.Annotation> newAnnotations = new ArrayList<>();
            newAnnotations.add(needsReviewAnn);

            for (int i = 0; i < cd.getLeadingAnnotations().size(); i++) {
                J.Annotation ann = cd.getLeadingAnnotations().get(i);
                if (i == 0) {
                    String ws = needsReviewPrefix.getWhitespace();
                    ann = ann.withPrefix(Space.format(ws.contains("\n") ? ws : "\n"));
                }
                newAnnotations.add(ann);
            }

            return cd.withLeadingAnnotations(newAnnotations);
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

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);

            if (TypeUtils.isOfClassType(a.getType(), "jakarta.ejb.Singleton")) {
                maybeAddImport("org.springframework.stereotype.Service");
                maybeRemoveImport("jakarta.ejb.Singleton");
                doAfterVisit(new ChangeType("jakarta.ejb.Singleton", "org.springframework.stereotype.Service", true).getVisitor());
                return a;
            }

            // Transform @DependsOn from EJB to Spring (same semantics, different package)
            if (TypeUtils.isOfClassType(a.getType(), "jakarta.ejb.DependsOn")) {
                maybeAddImport("org.springframework.context.annotation.DependsOn");
                maybeRemoveImport("jakarta.ejb.DependsOn");
                doAfterVisit(new ChangeType("jakarta.ejb.DependsOn", "org.springframework.context.annotation.DependsOn", true).getVisitor());
                return a;
            }

            // Remove @Lock - with @NeedsReview added at class level
            if (TypeUtils.isOfClassType(a.getType(), LOCK_FQN)) {
                maybeRemoveImport("jakarta.ejb.Lock");
                maybeRemoveImport("jakarta.ejb.LockType");
                //noinspection DataFlowIssue
                return null;
            }

            // Remove @ConcurrencyManagement
            if (TypeUtils.isOfClassType(a.getType(), CONCURRENCY_MANAGEMENT_FQN)) {
                maybeRemoveImport("jakarta.ejb.ConcurrencyManagement");
                maybeRemoveImport("jakarta.ejb.ConcurrencyManagementType");
                //noinspection DataFlowIssue
                return null;
            }

            // Remove @Startup
            if (TypeUtils.isOfClassType(a.getType(), "jakarta.ejb.Startup")) {
                maybeRemoveImport("jakarta.ejb.Startup");
                //noinspection DataFlowIssue
                return null;
            }

            return a;
        }

        /**
         * Transforms @PostConstruct to @EventListener(ApplicationReadyEvent.class).
         * This is called when @Startup was present on the class.
         */
        private J.MethodDeclaration transformPostConstructToEventListener(J.MethodDeclaration method) {
            List<J.Annotation> annotations = method.getLeadingAnnotations();
            boolean hasPostConstruct = false;
            int postConstructIndex = -1;

            for (int i = 0; i < annotations.size(); i++) {
                J.Annotation ann = annotations.get(i);
                if (TypeUtils.isOfClassType(ann.getType(), POST_CONSTRUCT_JAKARTA_FQN) ||
                    TypeUtils.isOfClassType(ann.getType(), POST_CONSTRUCT_JAVAX_FQN) ||
                    "PostConstruct".equals(ann.getSimpleName())) {
                    hasPostConstruct = true;
                    postConstructIndex = i;
                    break;
                }
            }

            if (!hasPostConstruct) {
                return method;
            }

            // Create @EventListener(ApplicationReadyEvent.class) annotation
            J.Annotation oldAnn = annotations.get(postConstructIndex);
            J.Annotation eventListenerAnn = createEventListenerAnnotation(oldAnn.getPrefix());

            // Replace @PostConstruct with @EventListener
            List<J.Annotation> newAnnotations = new ArrayList<>(annotations);
            newAnnotations.set(postConstructIndex, eventListenerAnn);

            return method.withLeadingAnnotations(newAnnotations);
        }

        /**
         * Creates @EventListener(ApplicationReadyEvent.class) annotation.
         */
        private J.Annotation createEventListenerAnnotation(Space prefix) {
            JavaType.ShallowClass eventListenerType = JavaType.ShallowClass.build(EVENT_LISTENER_FQN);

            J.Identifier eventListenerIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "EventListener",
                eventListenerType,
                null
            );

            // Create ApplicationReadyEvent.class argument
            JavaType.ShallowClass appReadyEventType = JavaType.ShallowClass.build(APPLICATION_READY_EVENT_FQN);

            J.Identifier appReadyEventIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "ApplicationReadyEvent",
                appReadyEventType,
                null
            );

            J.FieldAccess classAccess = new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                appReadyEventIdent,
                JLeftPadded.build(new J.Identifier(
                    Tree.randomId(),
                    Space.EMPTY,
                    Markers.EMPTY,
                    Collections.emptyList(),
                    "class",
                    JavaType.Primitive.None,
                    null
                )),
                appReadyEventType
            );

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                Collections.singletonList(new JRightPadded<>(classAccess, Space.EMPTY, Markers.EMPTY)),
                Markers.EMPTY
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                eventListenerIdent,
                args
            );
        }
    }
}
