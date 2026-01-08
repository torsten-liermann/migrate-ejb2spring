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

/**
 * Migrates CDI Events to Spring ApplicationEventPublisher.
 * <p>
 * Handles:
 * 1. Event&lt;T&gt; -> ApplicationEventPublisher (removes generics)
 * 2. @Observes on parameter -> @EventListener on method
 * 3. .fire() -> .publishEvent()
 * <p>
 * Note: CDI Qualifiers like @CargoInspected are left since
 * Spring doesn't have a direct equivalent (manual migration required).
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateCdiEventsToSpring extends Recipe {

    @Override
    public String getDisplayName() {
        return "Migrate CDI Events to Spring ApplicationEventPublisher";
    }

    @Override
    public String getDescription() {
        return "Converts CDI Event<T> to Spring ApplicationEventPublisher and @Observes to @EventListener.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>("jakarta.enterprise.event.Event", false),
                new UsesType<>("jakarta.enterprise.event.Observes", false)
            ),
            new CdiEventsVisitor()
        );
    }

    private static class CdiEventsVisitor extends JavaIsoVisitor<ExecutionContext> {

        private static final String EVENT_FQN = "jakarta.enterprise.event.Event";
        private static final String OBSERVES_FQN = "jakarta.enterprise.event.Observes";
        private static final String PUBLISHER_FQN = "org.springframework.context.ApplicationEventPublisher";
        private static final String EVENT_LISTENER_FQN = "org.springframework.context.event.EventListener";

        // Tracking ob Transformationen stattgefunden haben
        private boolean transformedEvent = false;
        private boolean transformedObserves = false;

        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            // Reset tracking fuer diese Datei
            transformedEvent = false;
            transformedObserves = false;

            J.CompilationUnit result = super.visitCompilationUnit(cu, ctx);

            // Nach Transformation explizit Imports entfernen
            // (doAfterVisit umgeht das "unknown types" Problem von RemoveUnusedImports)
            if (transformedEvent) {
                doAfterVisit(new RemoveImport<>(EVENT_FQN, true));
            }
            if (transformedObserves) {
                doAfterVisit(new RemoveImport<>(OBSERVES_FQN, true));
            }

            return result;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations variableDeclarations, ExecutionContext ctx) {
            J.VariableDeclarations vd = super.visitVariableDeclarations(variableDeclarations, ctx);

            // Check if this is a field declaration with Event<T> type
            TypeTree typeExpression = vd.getTypeExpression();
            if (typeExpression instanceof J.ParameterizedType) {
                J.ParameterizedType pt = (J.ParameterizedType) typeExpression;
                if (pt.getClazz() instanceof J.Identifier) {
                    J.Identifier clazz = (J.Identifier) pt.getClazz();
                    if (isEventType(clazz)) {
                        // Replace Event<T> with ApplicationEventPublisher
                        maybeAddImport(PUBLISHER_FQN);
                        transformedEvent = true;

                        // Create new identifier with correct type
                        // WICHTIG: Prefix vom ParameterizedType verwenden, nicht vom inneren Identifier!
                        // Der Prefix des ParameterizedType enthält den Whitespace vor dem Typ (z.B. nach "private ")
                        JavaType.ShallowClass publisherType = JavaType.ShallowClass.build(PUBLISHER_FQN);
                        J.Identifier newClazz = new J.Identifier(
                            Tree.randomId(),
                            pt.getPrefix(),  // Prefix vom ParameterizedType, nicht clazz.getPrefix()!
                            Markers.EMPTY,
                            Collections.emptyList(),
                            "ApplicationEventPublisher",
                            publisherType,
                            null
                        );

                        // Remove type parameters (ApplicationEventPublisher is not generic)
                        vd = vd.withTypeExpression(newClazz);
                    }
                }
            } else if (typeExpression instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpression;
                if (isEventType(ident)) {
                    maybeAddImport(PUBLISHER_FQN);
                    transformedEvent = true;

                    JavaType.ShallowClass publisherType = JavaType.ShallowClass.build(PUBLISHER_FQN);
                    J.Identifier newIdent = new J.Identifier(
                        Tree.randomId(),
                        ident.getPrefix(),
                        Markers.EMPTY,
                        Collections.emptyList(),
                        "ApplicationEventPublisher",
                        publisherType,
                        null
                    );
                    vd = vd.withTypeExpression(newIdent);
                }
            }

            return vd;
        }

        private boolean isEventType(J.Identifier ident) {
            // KRITISCH: TypeUtils zuerst pruefen (sicher)
            // SimpleName-Check kann False-Positives erzeugen (z.B. java.awt.Event)
            if (ident.getType() != null) {
                return TypeUtils.isOfClassType(ident.getType(), EVENT_FQN);
            }
            // Fallback auf SimpleName nur wenn keine Typ-Information verfuegbar
            // (sollte bei korrekter LST-Aufbau selten vorkommen)
            return "Event".equals(ident.getSimpleName());
        }

        @Override
        public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration method, ExecutionContext ctx) {
            J.MethodDeclaration md = super.visitMethodDeclaration(method, ctx);

            // Check if any parameter has @Observes annotation
            List<Statement> params = md.getParameters();
            boolean hasObserves = false;
            J.VariableDeclarations observesParam = null;

            for (Statement param : params) {
                if (param instanceof J.VariableDeclarations) {
                    J.VariableDeclarations vd = (J.VariableDeclarations) param;
                    for (J.Annotation ann : vd.getLeadingAnnotations()) {
                        if (TypeUtils.isOfClassType(ann.getType(), OBSERVES_FQN) ||
                            "Observes".equals(ann.getSimpleName())) {
                            hasObserves = true;
                            observesParam = vd;
                            break;
                        }
                    }
                }
                if (hasObserves) break;
            }

            if (hasObserves && observesParam != null) {
                // Check if method already has @EventListener
                boolean hasMethodLevelEventListener = md.getLeadingAnnotations().stream()
                    .anyMatch(ann -> TypeUtils.isOfClassType(ann.getType(), EVENT_LISTENER_FQN) ||
                                    "EventListener".equals(ann.getSimpleName()));

                if (!hasMethodLevelEventListener) {
                    // Add @EventListener to method
                    maybeAddImport(EVENT_LISTENER_FQN);

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

                    J.Annotation eventListenerAnn = new J.Annotation(
                        Tree.randomId(),
                        Space.format("\n    "),
                        Markers.EMPTY,
                        eventListenerIdent,
                        null
                    );

                    List<J.Annotation> newAnnotations = new ArrayList<>(md.getLeadingAnnotations());
                    newAnnotations.add(eventListenerAnn);
                    md = md.withLeadingAnnotations(newAnnotations);
                }

                // Remove @Observes from parameters
                List<Statement> newParams = new ArrayList<>();
                for (Statement param : md.getParameters()) {
                    if (param instanceof J.VariableDeclarations) {
                        J.VariableDeclarations vd = (J.VariableDeclarations) param;
                        List<J.Annotation> filteredAnnotations = new ArrayList<>();
                        for (J.Annotation ann : vd.getLeadingAnnotations()) {
                            if (!TypeUtils.isOfClassType(ann.getType(), OBSERVES_FQN) &&
                                !"Observes".equals(ann.getSimpleName())) {
                                filteredAnnotations.add(ann);
                            }
                        }
                        if (filteredAnnotations.size() != vd.getLeadingAnnotations().size()) {
                            vd = vd.withLeadingAnnotations(filteredAnnotations);
                            transformedObserves = true;
                        }
                        newParams.add(vd);
                    } else {
                        newParams.add(param);
                    }
                }
                md = md.withParameters(newParams);
            }

            return md;
        }

        @Override
        public J.MethodInvocation visitMethodInvocation(J.MethodInvocation method, ExecutionContext ctx) {
            J.MethodInvocation mi = super.visitMethodInvocation(method, ctx);

            // Check if this is a .fire() call on Event
            if ("fire".equals(mi.getSimpleName())) {
                Expression select = mi.getSelect();
                if (select != null && select.getType() != null) {
                    if (TypeUtils.isAssignableTo(EVENT_FQN, select.getType()) ||
                        TypeUtils.isAssignableTo(PUBLISHER_FQN, select.getType())) {
                        // Replace .fire() with .publishEvent()
                        mi = mi.withName(mi.getName().withSimpleName("publishEvent"));
                    }
                }
            }

            return mi;
        }
    }
}
