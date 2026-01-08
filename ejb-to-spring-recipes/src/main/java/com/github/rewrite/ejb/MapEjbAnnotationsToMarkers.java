package com.github.rewrite.ejb;

import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.Recipe;
import org.openrewrite.java.ChangeType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Maps EJB annotations to project-local no-op marker annotations.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MapEjbAnnotationsToMarkers extends Recipe {

    private static final String TARGET_PACKAGE = "com.github.migration.annotations.";

    /**
     * EJB annotation names that are mapped to marker annotations.
     * Package-private for test access.
     */
    static final List<String> EJB_ANNOTATIONS = Arrays.asList(
        "AccessTimeout",
        "ActivationConfigProperty",
        "AfterBegin",
        "AfterCompletion",
        "ApplicationException",
        "Asynchronous",
        "BeforeCompletion",
        "ConcurrencyManagement",
        "DependsOn",
        "EJB",
        "EJBs",
        "Init",
        "Local",
        "LocalBean",
        "LocalHome",
        "Lock",
        "MessageDriven",
        "PostActivate",
        "PrePassivate",
        "Remote",
        "RemoteHome",
        "Remove",
        "Schedule",
        "Schedules",
        "Singleton",
        "Startup",
        "Stateful",
        "StatefulTimeout",
        "Stateless",
        "Timeout",
        "TransactionAttribute",
        "TransactionManagement"
    );

    /**
     * EJB enum names that are mapped to marker enums.
     * Package-private for test access.
     */
    static final List<String> EJB_ENUMS = Arrays.asList(
        "ConcurrencyManagementType",
        "LockType",
        "TransactionAttributeType",
        "TransactionManagementType"
    );

    @Override
    public String getDisplayName() {
        return "Map EJB annotations to no-op markers";
    }

    @Override
    public String getDescription() {
        return "Replaces javax/jakarta EJB annotations with project-local marker annotations without runtime semantics.";
    }

    @Override
    public List<Recipe> getRecipeList() {
        List<Recipe> recipes = new ArrayList<>();
        for (String name : EJB_ANNOTATIONS) {
            String target = TARGET_PACKAGE + "Ejb" + name;
            recipes.add(new ChangeType("javax.ejb." + name, target, true));
            recipes.add(new ChangeType("jakarta.ejb." + name, target, true));
        }
        for (String name : EJB_ENUMS) {
            String target = TARGET_PACKAGE + "Ejb" + name;
            recipes.add(new ChangeType("javax.ejb." + name, target, true));
            recipes.add(new ChangeType("jakarta.ejb." + name, target, true));
        }
        return recipes;
    }
}
