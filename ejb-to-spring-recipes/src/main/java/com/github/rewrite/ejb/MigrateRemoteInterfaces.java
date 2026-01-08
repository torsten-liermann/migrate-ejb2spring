package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.jspecify.annotations.Nullable;
import org.openrewrite.ExecutionContext;
import org.openrewrite.Option;
import org.openrewrite.Recipe;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Routes @Remote interface migration based on project.yaml configuration.
 * <p>
 * Strategy routing:
 * <ul>
 *   <li>{@code rest} (default): Generates REST controllers and HttpExchange clients</li>
 *   <li>{@code manual}: Adds @NeedsReview marker for manual migration</li>
 * </ul>
 * <p>
 * Configure in project.yaml:
 * <pre>
 * migration:
 *   remote:
 *     strategy: rest | manual
 * </pre>
 *
 * @see MigrateRemoteToRest
 * @see GenerateHttpExchangeClient
 * @see MigrateRemoteToMarker
 * @see ProjectConfiguration.RemoteStrategy
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class MigrateRemoteInterfaces extends Recipe {

    @Option(displayName = "Target package suffix for REST controllers",
            description = "Suffix appended to the remote type package for generated controllers (REST strategy).",
            example = "rest",
            required = false)
    @Nullable
    String restPackageSuffix;

    @Option(displayName = "Target package suffix for HttpExchange clients",
            description = "Suffix appended to the remote type package for generated clients (REST strategy).",
            example = "client",
            required = false)
    @Nullable
    String clientPackageSuffix;

    public MigrateRemoteInterfaces() {
        this.restPackageSuffix = null;
        this.clientPackageSuffix = null;
    }

    public MigrateRemoteInterfaces(@Nullable String restPackageSuffix, @Nullable String clientPackageSuffix) {
        this.restPackageSuffix = restPackageSuffix;
        this.clientPackageSuffix = clientPackageSuffix;
    }

    @Override
    public String getDisplayName() {
        return "Migrate @Remote interfaces (strategy-based)";
    }

    @Override
    public String getDescription() {
        return "Migrates @Remote EJB interfaces based on project.yaml configuration. " +
               "With 'rest' strategy (default), generates REST controllers and HttpExchange clients. " +
               "With 'manual' strategy, adds @NeedsReview markers for manual migration.";
    }

    @Override
    public List<Recipe> getRecipeList() {
        ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(
                Paths.get(System.getProperty("user.dir")));

        List<Recipe> recipes = new ArrayList<>();

        if (config.getRemoteStrategy() == ProjectConfiguration.RemoteStrategy.REST) {
            // REST strategy: Generate controllers + clients
            recipes.add(new MigrateRemoteToRest(restPackageSuffix));
            recipes.add(new GenerateHttpExchangeClient(clientPackageSuffix));
        } else {
            // MANUAL strategy: Add @NeedsReview markers
            recipes.add(new MigrateRemoteToMarker());
        }

        return recipes;
    }
}
