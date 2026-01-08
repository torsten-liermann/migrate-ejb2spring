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
 * Migrates injected Logger fields to static LoggerFactory pattern.
 * <p>
 * CDI/Jakarta EE pattern (injected via LoggerProducer):
 * <pre>
 * {@code @Inject} // or @Autowired after migration
 * private Logger logger;
 * </pre>
 * <p>
 * This recipe:
 * 1. Removes @Autowired/@Inject from Logger fields
 * 2. Adds 'static final' modifiers
 * 3. Adds initializer with Logger.getLogger(ClassName.class.getName())
 * 4. Renames 'logger' to 'LOGGER' (Java convention)
 * <p>
 * Result:
 * <pre>
 * private static final Logger LOGGER = Logger.getLogger(ClassName.class.getName());
 * </pre>
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateLoggerInjectionToFactory extends Recipe {

    private static final String JUL_LOGGER_FQN = "java.util.logging.Logger";
    private static final String AUTOWIRED_FQN = "org.springframework.beans.factory.annotation.Autowired";
    private static final String INJECT_FQN = "jakarta.inject.Inject";

    @Override
    public String getDisplayName() {
        return "Migrate Logger injection to LoggerFactory pattern";
    }

    @Override
    public String getDescription() {
        return "Converts @Inject/@Autowired Logger fields to static Logger.getLogger() calls. " +
               "This is necessary after removing CDI LoggerProducer.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return Preconditions.check(
            Preconditions.or(
                new UsesType<>(AUTOWIRED_FQN, false),
                new UsesType<>(INJECT_FQN, false)
            ),
            new LoggerInjectionVisitor()
        );
    }

    private static class LoggerInjectionVisitor extends JavaIsoVisitor<ExecutionContext> {

        private String enclosingClassName = null;
        private Set<String> migratedLoggerNames = new HashSet<>();

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            // Store the class name for Logger.getLogger() call
            String previousClassName = enclosingClassName;
            Set<String> previousMigrated = new HashSet<>(migratedLoggerNames);

            enclosingClassName = classDecl.getSimpleName();
            migratedLoggerNames.clear();

            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            // Restore previous class name (for nested classes)
            enclosingClassName = previousClassName;
            migratedLoggerNames = previousMigrated;

            return cd;
        }

        @Override
        public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
            // Check if this is a Logger field with @Inject or @Autowired
            if (!isLoggerField(varDecls)) {
                return varDecls;
            }

            boolean hasInjectionAnnotation = varDecls.getLeadingAnnotations().stream()
                .anyMatch(this::isInjectionAnnotation);

            if (!hasInjectionAnnotation) {
                return varDecls;
            }

            // Get original variable name
            String originalVarName = varDecls.getVariables().get(0).getSimpleName();
            migratedLoggerNames.add(originalVarName);

            // Remove injection annotations
            List<J.Annotation> cleanAnnotations = new ArrayList<>();
            for (J.Annotation ann : varDecls.getLeadingAnnotations()) {
                if (!isInjectionAnnotation(ann)) {
                    cleanAnnotations.add(ann);
                }
            }

            // Schedule import cleanup
            doAfterVisit(new RemoveImport<>(AUTOWIRED_FQN, true));
            doAfterVisit(new RemoveImport<>(INJECT_FQN, true));

            // Add static and final modifiers if not present
            List<J.Modifier> modifiers = new ArrayList<>(varDecls.getModifiers());
            boolean hasStatic = modifiers.stream().anyMatch(m -> m.getType() == J.Modifier.Type.Static);
            boolean hasFinal = modifiers.stream().anyMatch(m -> m.getType() == J.Modifier.Type.Final);

            if (!hasStatic) {
                J.Modifier staticMod = new J.Modifier(
                    Tree.randomId(),
                    Space.SINGLE_SPACE,
                    Markers.EMPTY,
                    null,
                    J.Modifier.Type.Static,
                    Collections.emptyList()
                );
                // Insert static after access modifier
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
                modifiers.add(insertIndex, staticMod);
            }

            if (!hasFinal) {
                J.Modifier finalMod = new J.Modifier(
                    Tree.randomId(),
                    Space.SINGLE_SPACE,
                    Markers.EMPTY,
                    null,
                    J.Modifier.Type.Final,
                    Collections.emptyList()
                );
                // Insert final after static
                int insertIndex = modifiers.size();
                for (int i = 0; i < modifiers.size(); i++) {
                    if (modifiers.get(i).getType() == J.Modifier.Type.Static) {
                        insertIndex = i + 1;
                        break;
                    }
                }
                modifiers.add(insertIndex, finalMod);
            }

            varDecls = varDecls.withModifiers(modifiers);
            varDecls = varDecls.withLeadingAnnotations(cleanAnnotations);

            // Use JavaTemplate to add the initializer
            // Template: Logger.getLogger(#{}.class.getName())
            JavaTemplate initTemplate = JavaTemplate.builder(
                    "Logger.getLogger(" + enclosingClassName + ".class.getName())"
                )
                .contextSensitive()
                .build();

            // Update variable name to LOGGER and add initializer via template
            List<J.VariableDeclarations.NamedVariable> newVars = new ArrayList<>();
            for (J.VariableDeclarations.NamedVariable var : varDecls.getVariables()) {
                // Rename to LOGGER (uppercase convention)
                J.Identifier newName = var.getName().withSimpleName("LOGGER");

                // Apply template to get initializer expression
                // We need to use the template in the proper context
                Cursor cursor = getCursor();

                // Create a simple identifier as initializer placeholder
                // The template application is complex, so we use a simpler approach:
                // Parse the expression directly
                J.MethodInvocation initExpr = createLoggerGetLoggerCall();

                J.VariableDeclarations.NamedVariable newVar = var
                    .withName(newName)
                    .withInitializer(initExpr);
                newVars.add(newVar);
            }

            varDecls = varDecls.withVariables(newVars);

            return varDecls;
        }

        private J.MethodInvocation createLoggerGetLoggerCall() {
            // Build: Logger.getLogger(ClassName.class.getName())

            // ClassName.class
            J.Identifier classNameId = new J.Identifier(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                Collections.emptyList(), enclosingClassName, null, null
            );

            J.FieldAccess dotClass = new J.FieldAccess(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                classNameId,
                new JLeftPadded<>(Space.EMPTY,
                    new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                        Collections.emptyList(), "class", null, null),
                    Markers.EMPTY),
                null
            );

            // .getName()
            J.MethodInvocation getName = new J.MethodInvocation(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                new JRightPadded<>(dotClass, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                    Collections.emptyList(), "getName", null, null),
                JContainer.empty(),
                null
            );

            // Logger.getLogger(...)
            J.Identifier loggerId = new J.Identifier(
                Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                Collections.emptyList(), "Logger", null, null
            );

            JContainer<Expression> args = JContainer.build(
                Space.EMPTY,
                Collections.singletonList(new JRightPadded<>(getName, Space.EMPTY, Markers.EMPTY)),
                Markers.EMPTY
            );

            return new J.MethodInvocation(
                Tree.randomId(), Space.format(" "), Markers.EMPTY,
                new JRightPadded<>(loggerId, Space.EMPTY, Markers.EMPTY),
                null,
                new J.Identifier(Tree.randomId(), Space.EMPTY, Markers.EMPTY,
                    Collections.emptyList(), "getLogger", null, null),
                args,
                null
            );
        }

        @Override
        public J.Identifier visitIdentifier(J.Identifier ident, ExecutionContext ctx) {
            // Rename logger references to LOGGER if we migrated the field
            if (migratedLoggerNames.contains(ident.getSimpleName())) {
                // Only rename if this looks like a field reference (not a declaration)
                Object parent = getCursor().getParentTreeCursor().getValue();
                if (!(parent instanceof J.VariableDeclarations.NamedVariable)) {
                    return ident.withSimpleName("LOGGER");
                }
            }
            return ident;
        }

        private boolean isLoggerField(J.VariableDeclarations varDecls) {
            TypeTree typeExpr = varDecls.getTypeExpression();
            if (typeExpr == null) return false;

            if (typeExpr instanceof J.Identifier) {
                J.Identifier ident = (J.Identifier) typeExpr;
                return TypeUtils.isOfClassType(ident.getType(), JUL_LOGGER_FQN) ||
                       "Logger".equals(ident.getSimpleName());
            }
            return TypeUtils.isOfClassType(typeExpr.getType(), JUL_LOGGER_FQN);
        }

        private boolean isInjectionAnnotation(J.Annotation ann) {
            return TypeUtils.isOfClassType(ann.getType(), AUTOWIRED_FQN) ||
                   TypeUtils.isOfClassType(ann.getType(), INJECT_FQN) ||
                   "Autowired".equals(ann.getSimpleName()) ||
                   "Inject".equals(ann.getSimpleName());
        }
    }
}
