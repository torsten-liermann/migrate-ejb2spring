package com.github.rewrite.ejb.config;

import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads ProjectConfiguration from project.yaml or returns Maven defaults.
 * <p>
 * The loader uses a static cache to avoid repeated file reads.
 * This is safe because project.yaml doesn't change during a recipe run.
 * <p>
 * For multi-module projects, submodules can inherit configuration from parent modules
 * using {@link #loadWithInheritance(Path)}.
 * <p>
 * Usage in a Recipe:
 * <pre>
 * ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(moduleRoot);
 * if (config.isTestSource(sourcePath)) {
 *     // handle test source
 * }
 * </pre>
 */
public class ProjectConfigurationLoader {

    private static final String PROJECT_YAML = "project.yaml";
    private static final String POM_XML = "pom.xml";
    private static final String BUILD_GRADLE = "build.gradle";
    private static final String BUILD_GRADLE_KTS = "build.gradle.kts";

    // Static cache - safe because project.yaml doesn't change during recipe execution
    private static final Map<Path, ProjectConfiguration> CACHE = new ConcurrentHashMap<>();

    // Inheritance cache - maps submodule paths to their effective configuration
    private static final Map<Path, ProjectConfiguration> INHERITANCE_CACHE = new ConcurrentHashMap<>();

    // Test injection map - allows unit tests to inject configurations without filesystem access
    private static final Map<Path, ProjectConfiguration> TEST_INJECTIONS = new ConcurrentHashMap<>();

    /**
     * Loads the ProjectConfiguration for the given project root.
     * <p>
     * If project.yaml exists, it is parsed. Otherwise, Maven defaults are returned.
     * Results are cached.
     *
     * @param projectRoot the project root directory
     * @return the ProjectConfiguration
     */
    public static ProjectConfiguration load(Path projectRoot) {
        if (projectRoot == null) {
            return ProjectConfiguration.mavenDefaults();
        }

        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();

        // Check test injections first (for unit testing without filesystem access)
        ProjectConfiguration testConfig = TEST_INJECTIONS.get(normalizedRoot);
        if (testConfig != null) {
            return testConfig;
        }

        return CACHE.computeIfAbsent(normalizedRoot, ProjectConfigurationLoader::loadFromDisk);
    }

    /**
     * Loads the ProjectConfiguration for the given module root with parent inheritance.
     * <p>
     * This method supports multi-module projects where submodules may not have their
     * own project.yaml but should inherit from a parent module's configuration.
     * <p>
     * The inheritance logic:
     * <ol>
     *   <li>If the module has its own project.yaml, load and return it</li>
     *   <li>Otherwise, walk up parent directories looking for project.yaml</li>
     *   <li>Continue traversal through intermediate directories without build files</li>
     *   <li>Stop at filesystem root or .git directory (repository boundary)</li>
     *   <li>When project.yaml is found, require a build file to use it</li>
     *   <li>If no parent config found, return defaults</li>
     * </ol>
     *
     * @param moduleRoot the module root directory (can be a submodule)
     * @return the ProjectConfiguration (own or inherited from parent)
     */
    public static ProjectConfiguration loadWithInheritance(Path moduleRoot) {
        if (moduleRoot == null) {
            return ProjectConfiguration.mavenDefaults();
        }

        Path normalizedRoot = moduleRoot.toAbsolutePath().normalize();

        // Check test injections first
        ProjectConfiguration testConfig = TEST_INJECTIONS.get(normalizedRoot);
        if (testConfig != null) {
            return testConfig;
        }

        // Check inheritance cache
        ProjectConfiguration cached = INHERITANCE_CACHE.get(normalizedRoot);
        if (cached != null) {
            return cached;
        }

        // Check if module has its own project.yaml
        Path ownConfig = normalizedRoot.resolve(PROJECT_YAML);
        if (Files.exists(ownConfig)) {
            // Use standard load which handles caching
            ProjectConfiguration config = load(normalizedRoot);
            INHERITANCE_CACHE.put(normalizedRoot, config);
            return config;
        }

        // Walk up to find parent with project.yaml
        // WFQ-010 fix: Continue through intermediate directories without build files
        // Stop only at filesystem root or .git directory (repository boundary)
        Path current = normalizedRoot.getParent();
        while (current != null) {
            // Check for repository boundary (.git directory means we've reached repo root)
            if (Files.isDirectory(current.resolve(".git"))) {
                // At repo root - check for project.yaml here, then stop
                Path repoConfig = current.resolve(PROJECT_YAML);
                if (Files.exists(repoConfig) && isValidProjectRoot(current)) {
                    ProjectConfiguration config = load(current);
                    INHERITANCE_CACHE.put(normalizedRoot, config);
                    return config;
                }
                break; // Don't traverse beyond repository root
            }

            // Check for project.yaml at this level
            Path parentConfig = current.resolve(PROJECT_YAML);
            if (Files.exists(parentConfig) && isValidProjectRoot(current)) {
                // Found parent config with build file - load and cache for this module
                ProjectConfiguration config = load(current);
                INHERITANCE_CACHE.put(normalizedRoot, config);
                return config;
            }

            // WFQ-010 fix: Do NOT break on directories without build files
            // Continue upward traversal to find project root with project.yaml
            current = current.getParent();
        }

        // No parent config found, return defaults (and cache them)
        ProjectConfiguration defaults = load(normalizedRoot);
        INHERITANCE_CACHE.put(normalizedRoot, defaults);
        return defaults;
    }

    /**
     * Checks if the given path looks like a valid project root (has build files).
     */
    private static boolean isValidProjectRoot(Path path) {
        return Files.exists(path.resolve(POM_XML)) ||
               Files.exists(path.resolve(BUILD_GRADLE)) ||
               Files.exists(path.resolve(BUILD_GRADLE_KTS)) ||
               Files.exists(path.resolve("settings.gradle")) ||
               Files.exists(path.resolve("settings.gradle.kts"));
    }

    /**
     * Clears the cache. Call this between test runs if needed.
     */
    public static void clearCache() {
        CACHE.clear();
        INHERITANCE_CACHE.clear();
    }

    /**
     * Injects a configuration for testing purposes.
     * This allows unit tests to control the configuration without needing
     * a real project.yaml file on the filesystem.
     *
     * @param projectRoot the project root path
     * @param config the configuration to inject
     */
    public static void injectForTest(Path projectRoot, ProjectConfiguration config) {
        TEST_INJECTIONS.put(projectRoot.toAbsolutePath().normalize(), config);
    }

    /**
     * Clears all test injections. Call this in @AfterEach to clean up test state.
     */
    public static void clearTestInjections() {
        TEST_INJECTIONS.clear();
    }

    /**
     * Loads the configuration from disk (called by cache if not present).
     * LOW fix: Also checks for build.gradle.kts to support Kotlin DSL projects.
     */
    private static ProjectConfiguration loadFromDisk(Path projectRoot) {
        Path yamlPath = projectRoot.resolve(PROJECT_YAML);
        if (Files.exists(yamlPath)) {
            return parseYaml(yamlPath);
        }
        // Check if this looks like a valid project root (has build files)
        // This helps avoid returning defaults for arbitrary directories
        if (!isValidProjectRoot(projectRoot)) {
            // This directory might not be a project root, return defaults
            return ProjectConfiguration.mavenDefaults();
        }
        // Log warning when using defaults for a valid project (ADR-001)
        System.err.println("Info: No " + PROJECT_YAML + " found in " + projectRoot +
                ". Using defaults (timer.strategy=SCHEDULED). " +
                "Create project.yaml to configure migration behavior explicitly.");
        return ProjectConfiguration.mavenDefaults();
    }

    /**
     * Parses the project.yaml file using SnakeYAML.
     */
    @SuppressWarnings("unchecked")
    private static ProjectConfiguration parseYaml(Path yamlPath) {
        try {
            String content = Files.readString(yamlPath);
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(content);

            if (root == null) {
                return ProjectConfiguration.mavenDefaults();
            }

            List<String> mainSourceRoots = null;
            List<String> testSourceRoots = null;
            List<String> resourceRoots = null;
            List<String> testResourceRoots = null;

            Object sourcesObj = root.get("sources");
            if (sourcesObj instanceof Map) {
                Map<String, Object> sources = (Map<String, Object>) sourcesObj;

                mainSourceRoots = extractStringList(sources.get("main"));
                testSourceRoots = extractStringList(sources.get("test"));
                resourceRoots = extractStringList(sources.get("resources"));
                testResourceRoots = extractStringList(sources.get("testResources"));
            }

            // Parse migration.timer settings
            ProjectConfiguration.TimerStrategy timerStrategy = null;
            ClusterMode clusterMode = null;
            ProjectConfiguration.JaxRsStrategy jaxRsStrategy = null;
            ProjectConfiguration.JaxRsClientStrategy jaxRsClientStrategy = null;
            String jaxRsClientProvider = null;
            String jaxRsClientProviderVersion = null;
            String jaxRsServerProvider = null;
            String jaxRsServerBasePath = null;
            ProjectConfiguration.JmsProvider jmsProvider = null;
            ProjectConfiguration.RemoteStrategy remoteStrategy = null;
            ProjectConfiguration.InjectStrategy injectStrategy = null;
            ProjectConfiguration.JsfStrategy jsfStrategy = null;
            ProjectConfiguration.SecurityStrategy securityStrategy = null;
            boolean keepWildFlyPlugins = false;
            boolean bootPluginInProfiles = false;
            List<String> allowedEjbTypes = null;
            String jaxwsProvider = null;
            String jaxwsBasePath = null;

            Object migrationObj = root.get("migration");
            if (migrationObj instanceof Map) {
                Map<String, Object> migration = (Map<String, Object>) migrationObj;
                Object timerObj = migration.get("timer");
                if (timerObj instanceof Map) {
                    Map<String, Object> timer = (Map<String, Object>) timerObj;
                    timerStrategy = parseTimerStrategy(timer.get("strategy"));
                    clusterMode = parseClusterMode(timer.get("cluster"));
                }
                // Parse migration.inject settings
                Object injectObj = migration.get("inject");
                if (injectObj instanceof Map) {
                    Map<String, Object> inject = (Map<String, Object>) injectObj;
                    injectStrategy = parseInjectStrategy(inject.get("strategy"));
                }
                // Parse migration.remote settings
                Object remoteObj = migration.get("remote");
                if (remoteObj instanceof Map) {
                    Map<String, Object> remote = (Map<String, Object>) remoteObj;
                    remoteStrategy = parseRemoteStrategy(remote.get("strategy"));
                }
                // Parse migration.jsf settings
                Object jsfObj = migration.get("jsf");
                if (jsfObj instanceof Map) {
                    Map<String, Object> jsf = (Map<String, Object>) jsfObj;
                    jsfStrategy = parseJsfStrategy(jsf.get("runtime"));
                }
                // Parse migration.security settings (WFQ-013)
                Object securityObj = migration.get("security");
                if (securityObj instanceof Map) {
                    Map<String, Object> security = (Map<String, Object>) securityObj;
                    securityStrategy = parseSecurityStrategy(security.get("strategy"));
                }
                // Parse migration.build settings (opt-out for WildFly plugin removal)
                Object buildObj = migration.get("build");
                if (buildObj instanceof Map) {
                    Map<String, Object> build = (Map<String, Object>) buildObj;
                    Object keepWildFlyObj = build.get("keepWildFlyPlugins");
                    if (keepWildFlyObj != null) {
                        keepWildFlyPlugins = Boolean.parseBoolean(keepWildFlyObj.toString().trim());
                    }
                    // Parse bootPluginInProfiles opt-in flag
                    Object bootPluginInProfilesObj = build.get("bootPluginInProfiles");
                    if (bootPluginInProfilesObj != null) {
                        bootPluginInProfiles = Boolean.parseBoolean(bootPluginInProfilesObj.toString().trim());
                    }
                }
                // Parse migration.ejb settings (EJB classification configuration)
                Object ejbObj = migration.get("ejb");
                if (ejbObj instanceof Map) {
                    Map<String, Object> ejb = (Map<String, Object>) ejbObj;
                    allowedEjbTypes = extractStringList(ejb.get("allowedTypes"));
                }
            }

            Object jaxrsObj = root.get("jaxrs");
            if (jaxrsObj instanceof Map) {
                Map<String, Object> jaxrs = (Map<String, Object>) jaxrsObj;
                jaxRsStrategy = parseJaxRsStrategy(jaxrs.get("strategy"));
                Object clientObj = jaxrs.get("client");
                if (clientObj instanceof Map) {
                    Map<String, Object> client = (Map<String, Object>) clientObj;
                    jaxRsClientStrategy = parseJaxRsClientStrategy(client.get("strategy"));
                    Object providerObj = client.get("provider");
                    if (providerObj != null) {
                        jaxRsClientProvider = providerObj.toString().trim();
                    }
                    Object providerVersionObj = client.get("providerVersion");
                    if (providerVersionObj != null) {
                        jaxRsClientProviderVersion = providerVersionObj.toString().trim();
                    }
                }
                // Parse jaxrs.server settings (JRS-002)
                Object serverObj = jaxrs.get("server");
                if (serverObj instanceof Map) {
                    Map<String, Object> server = (Map<String, Object>) serverObj;
                    Object serverProviderObj = server.get("provider");
                    if (serverProviderObj != null) {
                        jaxRsServerProvider = serverProviderObj.toString().trim().toLowerCase();
                    }
                    Object serverBasePathObj = server.get("basePath");
                    if (serverBasePathObj != null) {
                        jaxRsServerBasePath = serverBasePathObj.toString().trim();
                    }
                }
            }

            // Parse jms settings (support both root-level jms and nested migration.jms)
            // Prefer nested migration.jms.provider for schema consistency with migration.timer
            Object jmsObj = null;
            if (migrationObj instanceof Map) {
                Map<String, Object> migration = (Map<String, Object>) migrationObj;
                jmsObj = migration.get("jms");
            }
            // Fallback to root-level jms for backwards compatibility
            if (jmsObj == null) {
                jmsObj = root.get("jms");
            }
            if (jmsObj instanceof Map) {
                Map<String, Object> jms = (Map<String, Object>) jmsObj;
                jmsProvider = ProjectConfiguration.JmsProvider.fromString(
                        jms.get("provider") != null ? jms.get("provider").toString() : null);
            }

            // Parse jaxws settings
            Object jaxwsObj = root.get("jaxws");
            if (jaxwsObj instanceof Map) {
                Map<String, Object> jaxws = (Map<String, Object>) jaxwsObj;
                Object providerObj = jaxws.get("provider");
                if (providerObj != null) {
                    jaxwsProvider = providerObj.toString().trim();
                }
                Object basePathObj = jaxws.get("basePath");
                if (basePathObj != null) {
                    jaxwsBasePath = basePathObj.toString().trim();
                }
            }

            return new ProjectConfiguration(
                    mainSourceRoots, testSourceRoots, resourceRoots, testResourceRoots,
                    timerStrategy, clusterMode,
                    jaxRsStrategy, jaxRsClientStrategy, jaxRsClientProvider, jaxRsClientProviderVersion,
                    jmsProvider, remoteStrategy, injectStrategy, jsfStrategy, keepWildFlyPlugins,
                    bootPluginInProfiles, allowedEjbTypes, jaxwsProvider, jaxwsBasePath,
                    jaxRsServerProvider, jaxRsServerBasePath, securityStrategy);

        } catch (Exception e) {
            // Log warning and return defaults
            System.err.println("Warning: Failed to parse " + yamlPath + ": " + e.getMessage());
            return ProjectConfiguration.mavenDefaults();
        }
    }

    /**
     * Parses the timer strategy from YAML value.
     */
    private static ProjectConfiguration.TimerStrategy parseTimerStrategy(Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString().toUpperCase().trim();
        try {
            return ProjectConfiguration.TimerStrategy.valueOf(str);
        } catch (IllegalArgumentException e) {
            System.err.println("Warning: Unknown timer strategy '" + value + "', using default");
            return null;
        }
    }

    private static ProjectConfiguration.JaxRsStrategy parseJaxRsStrategy(Object value) {
        if (value == null) {
            return null;
        }
        ProjectConfiguration.JaxRsStrategy strategy =
                ProjectConfiguration.JaxRsStrategy.fromString(value.toString());
        if (strategy == null) {
            System.err.println("Warning: Unknown jaxrs strategy '" + value + "', using default");
        }
        return strategy;
    }

    private static ProjectConfiguration.JaxRsClientStrategy parseJaxRsClientStrategy(Object value) {
        if (value == null) {
            return null;
        }
        ProjectConfiguration.JaxRsClientStrategy strategy =
                ProjectConfiguration.JaxRsClientStrategy.fromString(value.toString());
        if (strategy == null) {
            System.err.println("Warning: Unknown jaxrs.client strategy '" + value + "', using default");
        }
        return strategy;
    }

    /**
     * Parses the inject strategy from YAML value.
     * <p>
     * Supports both enum names and YAML-friendly names:
     * <ul>
     *   <li>{@code keep-jsr330} or {@code KEEP_JSR330} (default)</li>
     *   <li>{@code migrate-to-spring} or {@code MIGRATE_TO_SPRING}</li>
     * </ul>
     */
    private static ProjectConfiguration.InjectStrategy parseInjectStrategy(Object value) {
        if (value == null) {
            return null;
        }
        ProjectConfiguration.InjectStrategy strategy =
                ProjectConfiguration.InjectStrategy.fromString(value.toString());
        if (strategy == null) {
            System.err.println("Warning: Unknown inject strategy '" + value +
                    "', using default. Valid values: keep-jsr330, migrate-to-spring");
        }
        return strategy;
    }

    /**
     * Parses the remote strategy from YAML value.
     * <p>
     * Supports both enum names and YAML-friendly names:
     * <ul>
     *   <li>{@code rest} or {@code REST} (default)</li>
     *   <li>{@code manual} or {@code MANUAL}</li>
     * </ul>
     */
    private static ProjectConfiguration.RemoteStrategy parseRemoteStrategy(Object value) {
        if (value == null) {
            return null;
        }
        ProjectConfiguration.RemoteStrategy strategy =
                ProjectConfiguration.RemoteStrategy.fromString(value.toString());
        if (strategy == null) {
            System.err.println("Warning: Unknown remote strategy '" + value +
                    "', using default. Valid values: rest, manual");
        }
        return strategy;
    }

    /**
     * Parses the JSF runtime strategy from YAML value.
     * <p>
     * Supports both enum names and YAML-friendly names:
     * <ul>
     *   <li>{@code joinfaces} or {@code JOINFACES} (default)</li>
     *   <li>{@code manual} or {@code MANUAL}</li>
     * </ul>
     */
    private static ProjectConfiguration.JsfStrategy parseJsfStrategy(Object value) {
        if (value == null) {
            return null;
        }
        ProjectConfiguration.JsfStrategy strategy =
                ProjectConfiguration.JsfStrategy.fromString(value.toString());
        if (strategy == null) {
            System.err.println("Warning: Unknown jsf.runtime strategy '" + value +
                    "', using default. Valid values: joinfaces, manual");
        }
        return strategy;
    }

    /**
     * Parses the security strategy from YAML value.
     * <p>
     * Supports both enum names and YAML-friendly names:
     * <ul>
     *   <li>{@code keep-jakarta} or {@code KEEP_JAKARTA} (default)</li>
     *   <li>{@code spring-security} or {@code SPRING_SECURITY}</li>
     * </ul>
     */
    private static ProjectConfiguration.SecurityStrategy parseSecurityStrategy(Object value) {
        if (value == null) {
            return null;
        }
        ProjectConfiguration.SecurityStrategy strategy =
                ProjectConfiguration.SecurityStrategy.fromString(value.toString());
        if (strategy == null) {
            System.err.println("Warning: Unknown security strategy '" + value +
                    "', using default. Valid values: keep-jakarta, spring-security");
        }
        return strategy;
    }

    /**
     * Parses the cluster mode from YAML value.
     * <p>
     * Supports both enum names and YAML-friendly names:
     * <ul>
     *   <li>{@code none} or {@code NONE}</li>
     *   <li>{@code quartz-jdbc} or {@code QUARTZ_JDBC}</li>
     *   <li>{@code shedlock} or {@code SHEDLOCK}</li>
     * </ul>
     */
    private static ClusterMode parseClusterMode(Object value) {
        if (value == null) {
            return null;
        }
        String str = value.toString().trim();
        // Handle YAML-friendly names with hyphens
        String normalized = str.toUpperCase().replace('-', '_');
        try {
            return ClusterMode.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            System.err.println("Warning: Unknown cluster mode '" + value +
                    "', using default. Valid values: none, quartz-jdbc, shedlock");
            return null;
        }
    }

    /**
     * Extracts a list of strings from a YAML value (can be a single string or a list).
     */
    @SuppressWarnings("unchecked")
    private static List<String> extractStringList(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof List) {
            List<String> result = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                if (item != null) {
                    result.add(item.toString());
                }
            }
            return result.isEmpty() ? null : result;
        }
        if (value instanceof String) {
            return List.of((String) value);
        }
        return null;
    }
}
