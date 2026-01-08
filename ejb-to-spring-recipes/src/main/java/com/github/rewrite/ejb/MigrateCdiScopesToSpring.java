package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.*;
import org.openrewrite.marker.Markers;

import java.util.*;

/**
 * Intelligently migrates CDI scope annotations to Spring.
 * <p>
 * Logic:
 * - If class already has a Spring stereotype (@Service, @Component, @Repository, @Controller):
 *   → Remove @ApplicationScoped/@Dependent (redundant)
 * - If class has no Spring stereotype:
 *   → Replace @ApplicationScoped/@Dependent with @Component
 * <p>
 * This avoids the "duplicate @Component" problem when @Named and @ApplicationScoped
 * are both present on a class.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateCdiScopesToSpring extends Recipe {

    private static final String APP_SCOPED_FQN = "jakarta.enterprise.context.ApplicationScoped";
    private static final String DEPENDENT_FQN = "jakarta.enterprise.context.Dependent";
    private static final String COMPONENT_FQN = "org.springframework.stereotype.Component";
    private static final String SERVICE_FQN = "org.springframework.stereotype.Service";
    private static final String REPOSITORY_FQN = "org.springframework.stereotype.Repository";
    private static final String CONTROLLER_FQN = "org.springframework.stereotype.Controller";
    private static final String REST_CONTROLLER_FQN = "org.springframework.web.bind.annotation.RestController";

    private static final Set<String> SPRING_STEREOTYPES = Set.of(
        COMPONENT_FQN, SERVICE_FQN, REPOSITORY_FQN, CONTROLLER_FQN, REST_CONTROLLER_FQN
    );

    private static final Set<String> SPRING_STEREOTYPE_SIMPLE_NAMES = Set.of(
        "Component", "Service", "Repository", "Controller", "RestController"
    );

    @Override
    public String getDisplayName() {
        return "Migrate CDI scopes to Spring stereotypes";
    }

    @Override
    public String getDescription() {
        return "Converts @ApplicationScoped and @Dependent to @Component, but only if " +
               "no other Spring stereotype is present. Removes CDI scopes if stereotype exists.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(APP_SCOPED_FQN, false),
                new UsesType<>(DEPENDENT_FQN, false)
            ),
            new CdiScopeVisitor()
        );
    }

    private static class CdiScopeVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Check for CDI scope annotations
            boolean hasAppScoped = hasAnnotation(cd, APP_SCOPED_FQN, "ApplicationScoped");
            boolean hasDependent = hasAnnotation(cd, DEPENDENT_FQN, "Dependent");

            if (!hasAppScoped && !hasDependent) {
                return cd;
            }

            // Check if class already has a Spring stereotype
            boolean hasSpringStereotype = cd.getLeadingAnnotations().stream()
                .anyMatch(ann -> {
                    if (ann.getType() != null) {
                        String fqn = TypeUtils.asFullyQualified(ann.getType()).getFullyQualifiedName();
                        return SPRING_STEREOTYPES.contains(fqn);
                    }
                    return SPRING_STEREOTYPE_SIMPLE_NAMES.contains(ann.getSimpleName());
                });

            // Build new annotation list
            List<J.Annotation> newAnnotations = new ArrayList<>();
            boolean addedComponent = false;

            for (J.Annotation ann : cd.getLeadingAnnotations()) {
                boolean isAppScoped = isAnnotation(ann, APP_SCOPED_FQN, "ApplicationScoped");
                boolean isDependent = isAnnotation(ann, DEPENDENT_FQN, "Dependent");

                if (isAppScoped || isDependent) {
                    // Remove CDI scope import
                    if (isAppScoped) {
                        doAfterVisit(new RemoveImport<>(APP_SCOPED_FQN, true));
                    } else {
                        doAfterVisit(new RemoveImport<>(DEPENDENT_FQN, true));
                    }

                    // If no Spring stereotype exists, replace with @Component
                    if (!hasSpringStereotype && !addedComponent) {
                        maybeAddImport(COMPONENT_FQN);
                        J.Annotation componentAnn = createComponentAnnotation(ann.getPrefix());
                        newAnnotations.add(componentAnn);
                        addedComponent = true;
                    }
                    // Otherwise just remove (don't add to newAnnotations)
                } else {
                    newAnnotations.add(ann);
                }
            }

            return cd.withLeadingAnnotations(newAnnotations);
        }

        private boolean hasAnnotation(J.ClassDeclaration cd, String fqn, String simpleName) {
            return cd.getLeadingAnnotations().stream()
                .anyMatch(ann -> isAnnotation(ann, fqn, simpleName));
        }

        private boolean isAnnotation(J.Annotation ann, String fqn, String simpleName) {
            if (ann.getType() != null && TypeUtils.isOfClassType(ann.getType(), fqn)) {
                return true;
            }
            return simpleName.equals(ann.getSimpleName());
        }

        private J.Annotation createComponentAnnotation(Space prefix) {
            JavaType.ShallowClass componentType = JavaType.ShallowClass.build(COMPONENT_FQN);
            J.Identifier componentIdent = new J.Identifier(
                Tree.randomId(),
                Space.EMPTY,
                Markers.EMPTY,
                Collections.emptyList(),
                "Component",
                componentType,
                null
            );

            return new J.Annotation(
                Tree.randomId(),
                prefix,
                Markers.EMPTY,
                componentIdent,
                null
            );
        }
    }
}
