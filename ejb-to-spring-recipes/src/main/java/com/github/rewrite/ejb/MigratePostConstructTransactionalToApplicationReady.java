package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Preconditions;
import org.openrewrite.Recipe;
import org.openrewrite.Tree;
import org.openrewrite.TreeVisitor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Migrates @PostConstruct + @Transactional to a transactional startup hook.
 * <p>
 * In Spring, @Transactional does not apply to @PostConstruct because proxies
 * are created after lifecycle callbacks. Replace with:
 *   @EventListener(ApplicationReadyEvent.class)
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigratePostConstructTransactionalToApplicationReady extends Recipe {

    private static final String JAKARTA_POST_CONSTRUCT = "jakarta.annotation.PostConstruct";
    private static final String JAVAX_POST_CONSTRUCT = "javax.annotation.PostConstruct";
    private static final String SPRING_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String JAKARTA_TRANSACTIONAL = "jakarta.transaction.Transactional";
    private static final String JAVAX_TRANSACTIONAL = "javax.transaction.Transactional";
    private static final String SPRING_EVENT_LISTENER = "org.springframework.context.event.EventListener";
    private static final String SPRING_APPLICATION_READY_EVENT = "org.springframework.boot.context.event.ApplicationReadyEvent";

    @Override
    public String getDisplayName() {
        return "Replace @PostConstruct + @Transactional with ApplicationReadyEvent listener";
    }

    @Override
    public String getDescription() {
        return "Moves transactional startup logic from @PostConstruct to " +
               "@EventListener(ApplicationReadyEvent.class) to ensure an active transaction.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(JAKARTA_POST_CONSTRUCT, false),
                new UsesType<>(JAVAX_POST_CONSTRUCT, false),
                new UsesType<>(SPRING_TRANSACTIONAL, false),
                new UsesType<>(JAKARTA_TRANSACTIONAL, false),
                new UsesType<>(JAVAX_TRANSACTIONAL, false)
            ),
            new PostConstructVisitor()
        );
    }

    private static class PostConstructVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            List<J.Annotation> annotations = md.getLeadingAnnotations();
            if (annotations.isEmpty()) {
                return md;
            }

            int postConstructIndex = -1;
            boolean hasTransactional = false;
            boolean hasEventListener = false;

            for (int i = 0; i < annotations.size(); i++) {
                J.Annotation ann = annotations.get(i);
                if (postConstructIndex < 0 && isPostConstruct(ann)) {
                    postConstructIndex = i;
                }
                if (isTransactional(ann)) {
                    hasTransactional = true;
                }
                if (isEventListener(ann)) {
                    hasEventListener = true;
                }
            }

            if (postConstructIndex < 0 || !hasTransactional || hasEventListener) {
                return md;
            }

            maybeAddImport(SPRING_EVENT_LISTENER);
            maybeAddImport(SPRING_APPLICATION_READY_EVENT);
            maybeRemoveImport(JAKARTA_POST_CONSTRUCT);
            maybeRemoveImport(JAVAX_POST_CONSTRUCT);

            J.Annotation postConstructAnn = annotations.get(postConstructIndex);
            J.Annotation eventListenerAnn = createEventListenerAnnotation(postConstructAnn.getPrefix());

            List<J.Annotation> newAnnotations = new ArrayList<>();
            for (int i = 0; i < annotations.size(); i++) {
                if (i == postConstructIndex) {
                    newAnnotations.add(eventListenerAnn);
                } else {
                    newAnnotations.add(annotations.get(i));
                }
            }

            return md.withLeadingAnnotations(newAnnotations);
        }

        private boolean isPostConstruct(J.Annotation ann) {
            if (TypeUtils.isOfClassType(ann.getType(), JAKARTA_POST_CONSTRUCT) ||
                TypeUtils.isOfClassType(ann.getType(), JAVAX_POST_CONSTRUCT)) {
                return true;
            }
            return "PostConstruct".equals(ann.getSimpleName());
        }

        private boolean isTransactional(J.Annotation ann) {
            if (TypeUtils.isOfClassType(ann.getType(), SPRING_TRANSACTIONAL) ||
                TypeUtils.isOfClassType(ann.getType(), JAKARTA_TRANSACTIONAL) ||
                TypeUtils.isOfClassType(ann.getType(), JAVAX_TRANSACTIONAL)) {
                return true;
            }
            return "Transactional".equals(ann.getSimpleName());
        }

        private boolean isEventListener(J.Annotation ann) {
            if (TypeUtils.isOfClassType(ann.getType(), SPRING_EVENT_LISTENER)) {
                return true;
            }
            return "EventListener".equals(ann.getSimpleName());
        }

        private J.Annotation createEventListenerAnnotation(Space prefix) {
            JavaType.ShallowClass eventListenerType = JavaType.ShallowClass.build(SPRING_EVENT_LISTENER);
            J.Identifier eventListenerIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "EventListener",
                eventListenerType,
                null
            );

            JavaType.ShallowClass readyEventType = JavaType.ShallowClass.build(SPRING_APPLICATION_READY_EVENT);
            J.Identifier readyEventIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "ApplicationReadyEvent",
                readyEventType,
                null
            );

            J.FieldAccess classLiteral = new J.FieldAccess(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                readyEventIdent,
                new JLeftPadded<>(
                    Space.EMPTY,
                    new J.Identifier(
                        Tree.randomId(),
                        Space.EMPTY,
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "class",
                        null,
                        null
                    ),
                    Markers.EMPTY
                ),
                null
            );

            List<JRightPadded<Expression>> argsList = new ArrayList<>();
            argsList.add(new JRightPadded<>(classLiteral, Space.EMPTY, Markers.EMPTY));

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                argsList,
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
