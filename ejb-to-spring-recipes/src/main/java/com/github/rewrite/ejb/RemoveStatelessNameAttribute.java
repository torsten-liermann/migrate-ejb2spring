package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JContainer;
import org.openrewrite.java.tree.JRightPadded;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;

/**
 * JUS-004: Removes the {@code name} attribute from {@code @Stateless}, {@code @Singleton},
 * and {@code @Stateful} EJB annotations.
 * <p>
 * Pattern:
 * <pre>
 * // Input
 * &#64;Stateless(name = IFoo.JNDI_NAME)
 * public class MyBean { }
 *
 * &#64;Singleton(name = "MySingleton")
 * public class MySingleton { }
 *
 * // Output
 * &#64;Stateless
 * public class MyBean { }
 *
 * &#64;Singleton
 * public class MySingleton { }
 * </pre>
 * <p>
 * This recipe handles:
 * <ul>
 *   <li>Explicit name attribute: {@code @Stateless(name = "Foo")}</li>
 *   <li>Field reference: {@code @Stateless(name = IFoo.JNDI_NAME)}</li>
 *   <li>Implicit name (single argument): {@code @Stateless("Foo")}</li>
 *   <li>Multiple attributes: keeps other attributes like {@code mappedName}, {@code description}</li>
 * </ul>
 * <p>
 * Pipeline ordering: This recipe should run BEFORE RemoveJndiNameConstants (JUS-003).
 * After removing {@code @Stateless(name = IFoo.JNDI_NAME)}, the constant {@code IFoo.JNDI_NAME}
 * will have no more usages and can be safely removed by JUS-003.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveStatelessNameAttribute extends Recipe {

    private static final List<String> TARGET_ANNOTATIONS = List.of(
        "jakarta.ejb.Stateless", "jakarta.ejb.Singleton", "jakarta.ejb.Stateful",
        "javax.ejb.Stateless", "javax.ejb.Singleton", "javax.ejb.Stateful"
    );

    @Override
    public String getDisplayName() {
        return "Remove EJB name attribute from @Stateless/@Singleton/@Stateful";
    }

    @Override
    public String getDescription() {
        return "Removes the 'name' attribute from @Stateless, @Singleton, and @Stateful EJB annotations. " +
               "The EJB name attribute defines the JNDI binding name, which is not used in Spring Boot. " +
               "Should run before RemoveJndiNameConstants (JUS-003) to enable automatic cleanup of JNDI constants.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.Annotation visitAnnotation(J.Annotation annotation, ExecutionContext ctx) {
                J.Annotation a = super.visitAnnotation(annotation, ctx);

                if (!isTargetAnnotation(a)) {
                    return a;
                }

                if (a.getArguments() == null || a.getArguments().isEmpty()) {
                    return a;
                }

                JContainer<Expression> container = a.getPadding().getArguments();
                if (container == null) {
                    return a;
                }

                List<JRightPadded<Expression>> args = container.getPadding().getElements();
                int originalSize = args.size();

                // Filter out 'name' attribute while preserving spacing
                List<JRightPadded<Expression>> newArgs = ListUtils.map(args, (i, arg) -> {
                    if (isNameAttribute(arg.getElement(), originalSize)) {
                        return null; // Remove this argument
                    }
                    return arg;
                });

                // Count remaining args (ListUtils.map preserves nulls, need to filter)
                int remainingCount = (int) newArgs.stream().filter(java.util.Objects::nonNull).count();

                if (remainingCount == 0) {
                    // Remove empty parentheses
                    return a.withArguments(null);
                }

                if (remainingCount == originalSize) {
                    // Nothing changed
                    return a;
                }

                // Rebuild container with remaining args
                List<JRightPadded<Expression>> filteredArgs = newArgs.stream()
                    .filter(java.util.Objects::nonNull)
                    .toList();

                // Fix spacing: first arg should not have leading space, others should have space after comma
                List<JRightPadded<Expression>> spacingFixedArgs = ListUtils.map(filteredArgs, (i, arg) -> {
                    if (i == 0) {
                        // First argument: ensure no leading space
                        Expression elem = arg.getElement();
                        if (elem.getPrefix().getWhitespace().startsWith(" ")) {
                            elem = elem.withPrefix(elem.getPrefix().withWhitespace(""));
                        }
                        return arg.withElement(elem);
                    }
                    return arg;
                });

                return a.getPadding().withArguments(
                    container.getPadding().withElements(spacingFixedArgs)
                );
            }

            private boolean isTargetAnnotation(J.Annotation annotation) {
                for (String target : TARGET_ANNOTATIONS) {
                    if (TypeUtils.isOfClassType(annotation.getType(), target)) {
                        return true;
                    }
                }
                return false;
            }

            /**
             * Determines if the given argument represents the 'name' attribute.
             * <p>
             * Handles:
             * <ul>
             *   <li>Explicit: {@code name = "value"}</li>
             *   <li>Implicit: {@code @Stateless("Foo")} - only when it's the sole argument
             *       and not an assignment (per EJB spec, the single value argument is the name)</li>
             * </ul>
             *
             * @param arg the annotation argument to check
             * @param totalArgCount total number of arguments in the annotation
             * @return true if this argument is the name attribute
             */
            private boolean isNameAttribute(Expression arg, int totalArgCount) {
                // Handle explicit: name = "value"
                if (arg instanceof J.Assignment) {
                    J.Assignment assign = (J.Assignment) arg;
                    if (assign.getVariable() instanceof J.Identifier) {
                        return "name".equals(((J.Identifier) assign.getVariable()).getSimpleName());
                    }
                    return false;
                }

                // Handle implicit: @Stateless("Foo") - only when single non-assignment argument
                // Per EJB spec, the single value argument is the 'name' attribute
                if (totalArgCount == 1) {
                    // Single argument that is NOT an assignment → implicit name
                    return true;
                }

                // Multiple arguments but this one is not an assignment?
                // This is unusual/invalid according to EJB spec, leave it alone
                return false;
            }
        };
    }
}
