package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

/**
 * Renames the 'name' attribute to 'value' in Spring stereotype annotations.
 * <p>
 * Spring's @Service, @Component, @Repository annotations only support 'value',
 * not 'name'. When migrating from EJB's @Stateless(name="...") to @Service,
 * the 'name' attribute must be converted to 'value'.
 * <p>
 * Examples:
 * <pre>
 * @Service(name = "auditService")  →  @Service("auditService")
 * @Component(name = "myBean")      →  @Component("myBean")
 * </pre>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RenameServiceNameToValue extends Recipe {

    private static final String SPRING_SERVICE = "org.springframework.stereotype.Service";
    private static final String SPRING_COMPONENT = "org.springframework.stereotype.Component";
    private static final String SPRING_REPOSITORY = "org.springframework.stereotype.Repository";

    @Override
    public String getDisplayName() {
        return "Rename 'name' to 'value' in Spring stereotype annotations";
    }

    @Override
    public String getDescription() {
        return "Converts @Service(name=\"...\") to @Service(\"...\") since Spring " +
               "stereotype annotations only support the 'value' attribute, not 'name'.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(SPRING_SERVICE, false),
                new UsesType<>(SPRING_COMPONENT, false),
                new UsesType<>(SPRING_REPOSITORY, false)
            ),
            new RenameNameToValueVisitor()
        );
    }

    private static class RenameNameToValueVisitor extends JavaIsoVisitor<ExecutionContext> {

        @Override
        public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
            J.Annotation a = super.visitAnnotation(annotation, ctx);

            // Only process Spring stereotype annotations
            if (!isSpringStereotype(a)) {
                return a;
            }

            // Check if annotation has a 'name' argument
            if (a.getArguments() == null || a.getArguments().isEmpty()) {
                return a;
            }

            // Find name and description attributes
            org.openrewrite.java.tree.Expression nameValue = null;
            java.util.List<org.openrewrite.java.tree.Expression> newArgs = new java.util.ArrayList<>();

            for (org.openrewrite.java.tree.Expression arg : a.getArguments()) {
                if (arg instanceof J.Assignment) {
                    J.Assignment assignment = (J.Assignment) arg;
                    if (assignment.getVariable() instanceof J.Identifier) {
                        J.Identifier varName = (J.Identifier) assignment.getVariable();
                        String attrName = varName.getSimpleName();
                        if ("name".equals(attrName)) {
                            // Keep the name value for later
                            nameValue = assignment.getAssignment();
                        } else if ("description".equals(attrName)) {
                            // Drop description - Spring @Service doesn't support it
                            // Could add as comment in future enhancement
                        } else {
                            // Keep other attributes
                            newArgs.add(arg);
                        }
                    }
                } else {
                    // Keep non-assignment args (like plain strings)
                    newArgs.add(arg);
                }
            }

            // If we found a name, use it as the value argument
            if (nameValue != null) {
                newArgs.add(0, nameValue);
            }

            return a.withArguments(newArgs.isEmpty() ? null : newArgs);
        }

        private boolean isSpringStereotype(J.Annotation a) {
            return TypeUtils.isOfClassType(a.getType(), SPRING_SERVICE) ||
                   TypeUtils.isOfClassType(a.getType(), SPRING_COMPONENT) ||
                   TypeUtils.isOfClassType(a.getType(), SPRING_REPOSITORY);
        }
    }
}
