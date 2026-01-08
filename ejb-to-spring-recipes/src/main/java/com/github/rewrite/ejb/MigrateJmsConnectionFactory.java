package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.java.*;
import org.openrewrite.java.tree.*;

import java.util.*;

/**
 * Migrates JMS ConnectionFactory from JNDI to Spring Boot auto-configuration.
 * <p>
 * This recipe:
 * 1. Removes @NeedsReview from ConnectionFactory fields (Spring Boot provides this automatically)
 * 2. Removes @JMSDestinationDefinition annotations (not needed in Spring)
 * <p>
 * Spring Boot JMS auto-configuration provides ConnectionFactory when:
 * - spring-boot-starter-artemis is on classpath: Uses spring.artemis.* properties
 * - spring-boot-starter-activemq is on classpath: Uses spring.activemq.* properties
 * - Otherwise: Uses embedded broker
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateJmsConnectionFactory extends Recipe {

    private static final String JAKARTA_CONNECTION_FACTORY = "jakarta.jms.ConnectionFactory";
    private static final String JAVAX_CONNECTION_FACTORY = "javax.jms.ConnectionFactory";
    private static final String JAKARTA_JMS_DESTINATION_DEF = "jakarta.jms.JMSDestinationDefinition";
    private static final String JAVAX_JMS_DESTINATION_DEF = "javax.jms.JMSDestinationDefinition";
    private static final String JAKARTA_JMS_DESTINATION_DEFS = "jakarta.jms.JMSDestinationDefinitions";
    private static final String NEEDS_REVIEW_FQN = "com.github.rewrite.ejb.annotations.NeedsReview";

    @Override
    public String getDisplayName() {
        return "Migrate JMS ConnectionFactory to Spring Boot";
    }

    @Override
    public String getDescription() {
        return "Removes @NeedsReview from ConnectionFactory fields and removes @JMSDestinationDefinition annotations.";
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor() {
        return new JavaIsoVisitor<ExecutionContext>() {

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
                J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

                // Remove @JMSDestinationDefinition annotations from class
                List<J.Annotation> newAnnotations = new ArrayList<>();
                boolean changed = false;

                for (J.Annotation ann : cd.getLeadingAnnotations()) {
                    if (isJmsDestinationDefinition(ann)) {
                        changed = true;
                        maybeRemoveImport(JAKARTA_JMS_DESTINATION_DEF);
                        maybeRemoveImport(JAVAX_JMS_DESTINATION_DEF);
                        maybeRemoveImport(JAKARTA_JMS_DESTINATION_DEFS);
                    } else {
                        newAnnotations.add(ann);
                    }
                }

                if (changed) {
                    cd = cd.withLeadingAnnotations(newAnnotations);
                }

                return cd;
            }

            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations varDecls, ExecutionContext ctx) {
                J.VariableDeclarations vd = super.visitVariableDeclarations(varDecls, ctx);

                // Check if this is a ConnectionFactory field
                JavaType type = vd.getType();
                if (type == null || !isConnectionFactory(type)) {
                    return vd;
                }

                // Remove @NeedsReview annotation
                List<J.Annotation> newAnnotations = new ArrayList<>();
                boolean removedNeedsReview = false;

                for (J.Annotation ann : vd.getLeadingAnnotations()) {
                    if (isNeedsReviewForJms(ann)) {
                        removedNeedsReview = true;
                    } else {
                        newAnnotations.add(ann);
                    }
                }

                if (removedNeedsReview) {
                    maybeRemoveImport(NEEDS_REVIEW_FQN);
                    vd = vd.withLeadingAnnotations(newAnnotations);
                }

                return vd;
            }

            private boolean isConnectionFactory(JavaType type) {
                return TypeUtils.isOfClassType(type, JAKARTA_CONNECTION_FACTORY) ||
                       TypeUtils.isOfClassType(type, JAVAX_CONNECTION_FACTORY);
            }

            private boolean isJmsDestinationDefinition(J.Annotation ann) {
                return TypeUtils.isOfClassType(ann.getType(), JAKARTA_JMS_DESTINATION_DEF) ||
                       TypeUtils.isOfClassType(ann.getType(), JAVAX_JMS_DESTINATION_DEF) ||
                       TypeUtils.isOfClassType(ann.getType(), JAKARTA_JMS_DESTINATION_DEFS) ||
                       "JMSDestinationDefinition".equals(ann.getSimpleName()) ||
                       "JMSDestinationDefinitions".equals(ann.getSimpleName());
            }

            private boolean isNeedsReviewForJms(J.Annotation ann) {
                if (!TypeUtils.isOfClassType(ann.getType(), NEEDS_REVIEW_FQN) &&
                    !"NeedsReview".equals(ann.getSimpleName())) {
                    return false;
                }

                // Check if originalCode mentions JMS ConnectionFactory
                if (ann.getArguments() != null) {
                    for (Expression arg : ann.getArguments()) {
                        if (arg instanceof J.Assignment) {
                            J.Assignment assignment = (J.Assignment) arg;
                            if (assignment.getVariable() instanceof J.Identifier) {
                                String attrName = ((J.Identifier) assignment.getVariable()).getSimpleName();
                                if ("originalCode".equals(attrName)) {
                                    Expression value = assignment.getAssignment();
                                    if (value instanceof J.Literal) {
                                        Object literalValue = ((J.Literal) value).getValue();
                                        if (literalValue != null) {
                                            String code = literalValue.toString();
                                            return code.contains("JMSConnectionFactory") ||
                                                   code.contains("DefaultJMSConnectionFactory");
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                return false;
            }
        };
    }
}
