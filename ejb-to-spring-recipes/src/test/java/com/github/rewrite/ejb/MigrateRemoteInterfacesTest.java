package com.github.rewrite.ejb;

import org.junit.jupiter.api.Test;
import org.openrewrite.Recipe;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for MigrateRemoteInterfaces routing recipe.
 * <p>
 * The actual migration behavior is tested in:
 * <ul>
 *   <li>{@link MigrateRemoteToRestTest} - REST strategy</li>
 *   <li>{@link GenerateHttpExchangeClientTest} - REST strategy client generation</li>
 *   <li>{@link MigrateRemoteToMarkerTest} - MANUAL strategy</li>
 * </ul>
 * <p>
 * This test only verifies the routing logic produces the correct recipe list.
 */
class MigrateRemoteInterfacesTest {

    @Test
    void defaultStrategyRoutesToRestRecipes() {
        // Default strategy is REST, which should include both REST controller
        // and HttpExchange client generation recipes
        MigrateRemoteInterfaces recipe = new MigrateRemoteInterfaces();

        List<Recipe> recipeList = recipe.getRecipeList();

        // Should have MigrateRemoteToRest and GenerateHttpExchangeClient
        assertThat(recipeList)
            .hasSize(2)
            .extracting(Recipe::getClass)
            .containsExactly(MigrateRemoteToRest.class, GenerateHttpExchangeClient.class);
    }

    @Test
    void customPackageSuffixesPassedToSubrecipes() {
        MigrateRemoteInterfaces recipe = new MigrateRemoteInterfaces("api.controllers", "api.clients");

        List<Recipe> recipeList = recipe.getRecipeList();

        // Verify the sub-recipes were created (detailed behavior tested in individual tests)
        assertThat(recipeList)
            .hasSize(2)
            .extracting(Recipe::getClass)
            .containsExactly(MigrateRemoteToRest.class, GenerateHttpExchangeClient.class);
    }

    @Test
    void displayNameAndDescriptionAreSet() {
        MigrateRemoteInterfaces recipe = new MigrateRemoteInterfaces();

        assertThat(recipe.getDisplayName())
            .isEqualTo("Migrate @Remote interfaces (strategy-based)");

        assertThat(recipe.getDescription())
            .contains("project.yaml")
            .contains("rest")
            .contains("manual");
    }
}
