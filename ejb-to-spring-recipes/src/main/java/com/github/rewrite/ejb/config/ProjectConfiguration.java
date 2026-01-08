package com.github.rewrite.ejb.config;

import java.util.List;

/**
 * Project-specific configuration for source roots and migration settings.
 * <p>
 * If no project.yaml exists in the project root, Maven defaults are used.
 * <p>
 * Example project.yaml:
 * <pre>
 * sources:
 *   main:
 *     - src/main/java
 *     - src/main/kotlin
 *   test:
 *     - src/test/java
 *     - src/testFixtures/java
 *     - src/integrationTest/java
 *   resources:
 *     - src/main/resources
 *
 * migration:
 *   timer:
 *     strategy: scheduled | taskscheduler | quartz
 *     cluster: none | quartz-jdbc | shedlock
 *   jms:
 *     provider: none | artemis | activemq | embedded
 *   inject:
 *     strategy: keep-jsr330 | migrate-to-spring
 *   remote:
 *     strategy: rest | manual
 *   jsf:
 *     runtime: joinfaces | manual
 *   ejb:
 *     allowedTypes:         # EJB types to NOT flag with @NeedsReview
 *       - jakarta.ejb.Timer
 *       - jakarta.ejb.TimerHandle
 *
 * jaxrs:
 *   strategy: keep-jaxrs | migrate-to-spring-mvc
 *   client:
 *     strategy: keep-jaxrs | manual | migrate-restclient | migrate-webclient
 *     provider: jersey | resteasy | cxf
 *     providerVersion: "3.1.5"
 *
 * jaxws:
 *   provider: cxf | manual        # default: cxf
 *   basePath: /services           # default: /services
 * </pre>
 * <p>
 * Strategy and Cluster Compatibility Matrix:
 * <table border="1">
 *   <tr><th>cluster</th><th>Allowed strategies</th><th>Conflict action</th></tr>
 *   <tr><td>none</td><td>scheduled, taskscheduler, quartz</td><td>-</td></tr>
 *   <tr><td>quartz-jdbc</td><td>quartz only</td><td>ConfigurationException</td></tr>
 *   <tr><td>shedlock</td><td>scheduled, taskscheduler</td><td>ConfigurationException</td></tr>
 * </table>
 *
 * @see ClusterMode
 * @see #getEffectiveTimerStrategy()
 */
public class ProjectConfiguration {

    /**
     * Timer migration strategy.
     * <ul>
     *   <li>{@code SCHEDULED} - Migrate simple @Schedule to @Scheduled, marker for complex cases (default)</li>
     *   <li>{@code TASKSCHEDULER} - Migrate to Spring TaskScheduler for Timer parameter support</li>
     *   <li>{@code QUARTZ} - Migrate to Quartz for persistence and cluster support</li>
     * </ul>
     */
    public enum TimerStrategy {
        SCHEDULED,
        TASKSCHEDULER,
        QUARTZ
    }

    /**
     * JAX-RS server migration strategy.
     * <ul>
     *   <li>{@code KEEP_JAXRS} - Keep JAX-RS server annotations (default)</li>
     *   <li>{@code MIGRATE_TO_SPRING_MVC} - Migrate JAX-RS server annotations to Spring MVC</li>
     * </ul>
     */
    public enum JaxRsStrategy {
        KEEP_JAXRS,
        MIGRATE_TO_SPRING_MVC;

        public static JaxRsStrategy fromString(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toUpperCase().replace('-', '_');
            try {
                return JaxRsStrategy.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * JAX-RS client migration strategy.
     * <ul>
     *   <li>{@code KEEP_JAXRS} - Keep JAX-RS client usage and add runtime dependencies (default)</li>
     *   <li>{@code MANUAL} - Mark usages for manual migration</li>
     *   <li>{@code MIGRATE_RESTCLIENT} - Migrate to Spring RestClient (future)</li>
     *   <li>{@code MIGRATE_WEBCLIENT} - Migrate to Spring WebClient (future)</li>
     * </ul>
     */
    public enum JaxRsClientStrategy {
        KEEP_JAXRS,
        MANUAL,
        MIGRATE_RESTCLIENT,
        MIGRATE_WEBCLIENT;

        public static JaxRsClientStrategy fromString(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toUpperCase().replace('-', '_');
            try {
                return JaxRsClientStrategy.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    // Maven defaults
    private static final List<String> DEFAULT_MAIN_SOURCE_ROOTS = List.of(
            "src/main/java",
            "src/main/kotlin"
    );

    private static final List<String> DEFAULT_TEST_SOURCE_ROOTS = List.of(
            "src/test/java",
            "src/test/kotlin"
    );

    private static final List<String> DEFAULT_RESOURCE_ROOTS = List.of(
            "src/main/resources"
    );

    private static final List<String> DEFAULT_TEST_RESOURCE_ROOTS = List.of(
            "src/test/resources"
    );

    // Timer migration defaults
    private static final TimerStrategy DEFAULT_TIMER_STRATEGY = TimerStrategy.SCHEDULED;
    private static final ClusterMode DEFAULT_CLUSTER_MODE = ClusterMode.NONE;
    // JAX-RS defaults (project.yaml optional)
    private static final JaxRsStrategy DEFAULT_JAXRS_STRATEGY = JaxRsStrategy.KEEP_JAXRS;
    private static final JaxRsClientStrategy DEFAULT_JAXRS_CLIENT_STRATEGY = JaxRsClientStrategy.KEEP_JAXRS;
    private static final String DEFAULT_JAXRS_CLIENT_PROVIDER = "jersey";
    private static final String DEFAULT_JAXRS_CLIENT_PROVIDER_VERSION = null;
    // JAX-RS server defaults (JRS-002)
    private static final String DEFAULT_JAXRS_SERVER_PROVIDER = null;  // null = auto-detect from dependencies
    private static final String DEFAULT_JAXRS_SERVER_BASE_PATH = "/api";

    /**
     * JSR-330 / CDI injection annotation migration strategy.
     * <ul>
     *   <li>{@code KEEP_JSR330} - Keep @Named/@Inject, add jakarta.inject-api dependency (default)</li>
     *   <li>{@code MIGRATE_TO_SPRING} - Migrate @Named to @Component/@Qualifier, @Inject to @Autowired</li>
     * </ul>
     */
    public enum InjectStrategy {
        KEEP_JSR330,
        MIGRATE_TO_SPRING;

        public static InjectStrategy fromString(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toUpperCase().replace('-', '_');
            try {
                return InjectStrategy.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * Remote interface migration strategy.
     * <ul>
     *   <li>{@code REST} - Generate @RestController with delegation + @HttpExchange client (default)</li>
     *   <li>{@code MANUAL} - Only add @NeedsReview marker for manual migration</li>
     * </ul>
     */
    public enum RemoteStrategy {
        REST,
        MANUAL;

        public static RemoteStrategy fromString(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toUpperCase().replace('-', '_');
            try {
                return RemoteStrategy.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * JSF runtime migration strategy.
     * <ul>
     *   <li>{@code JOINFACES} - Keep JSF scopes (@ViewScoped, etc.) unchanged; JoinFaces provides runtime (default)</li>
     *   <li>{@code MANUAL} - Mark JSF scope usage for manual migration to Spring scopes</li>
     * </ul>
     */
    public enum JsfStrategy {
        JOINFACES,
        MANUAL;

        public static JsfStrategy fromString(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toUpperCase().replace('-', '_');
            try {
                return JsfStrategy.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * Security migration strategy for EJB context security APIs.
     * <ul>
     *   <li>{@code KEEP_JAKARTA} - Keep Jakarta EJB context for security methods, mark for manual migration (default)</li>
     *   <li>{@code SPRING_SECURITY} - Migrate getCallerPrincipal/isCallerInRole to Spring Security APIs</li>
     * </ul>
     */
    public enum SecurityStrategy {
        KEEP_JAKARTA,
        SPRING_SECURITY;

        public static SecurityStrategy fromString(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toUpperCase().replace('-', '_');
            try {
                return SecurityStrategy.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
    }

    /**
     * JMS provider for message-driven bean migration.
     * <ul>
     *   <li>{@code NONE} - No provider configured, @NeedsReview will be added (default)</li>
     *   <li>{@code ARTEMIS} - Apache ActiveMQ Artemis (spring-boot-starter-artemis)</li>
     *   <li>{@code ACTIVEMQ} - Apache ActiveMQ Classic (spring-boot-starter-activemq)</li>
     *   <li>{@code EMBEDDED} - Embedded broker for testing</li>
     * </ul>
     */
    public enum JmsProvider {
        NONE,
        ARTEMIS,
        ACTIVEMQ,
        EMBEDDED;

        public static JmsProvider fromString(String value) {
            if (value == null || value.isBlank()) {
                return NONE;
            }
            String normalized = value.trim().toUpperCase().replace('-', '_');
            try {
                return JmsProvider.valueOf(normalized);
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }
    }

    // JMS defaults
    private static final JmsProvider DEFAULT_JMS_PROVIDER = JmsProvider.NONE;

    // Remote defaults
    private static final RemoteStrategy DEFAULT_REMOTE_STRATEGY = RemoteStrategy.REST;

    // Inject defaults (JSR-330 / CDI)
    private static final InjectStrategy DEFAULT_INJECT_STRATEGY = InjectStrategy.KEEP_JSR330;

    // JSF defaults
    private static final JsfStrategy DEFAULT_JSF_STRATEGY = JsfStrategy.JOINFACES;

    // Security defaults
    private static final SecurityStrategy DEFAULT_SECURITY_STRATEGY = SecurityStrategy.KEEP_JAKARTA;

    // Build plugin migration defaults
    private static final boolean DEFAULT_KEEP_WILDFLY_PLUGINS = false;
    private static final boolean DEFAULT_BOOT_PLUGIN_IN_PROFILES = false;

    // EJB classification defaults
    private static final List<String> DEFAULT_ALLOWED_EJB_TYPES = List.of();

    // JAX-WS defaults
    private static final String DEFAULT_JAXWS_PROVIDER = "cxf";
    private static final String DEFAULT_JAXWS_BASE_PATH = "/services";

    private final List<String> mainSourceRoots;
    private final List<String> testSourceRoots;
    private final List<String> resourceRoots;
    private final List<String> testResourceRoots;

    // Timer migration settings
    private final TimerStrategy timerStrategy;
    private final ClusterMode clusterMode;

    // JAX-RS migration settings
    private final JaxRsStrategy jaxRsStrategy;
    private final JaxRsClientStrategy jaxRsClientStrategy;
    private final String jaxRsClientProvider;
    private final String jaxRsClientProviderVersion;
    // JAX-RS server settings (JRS-002)
    private final String jaxRsServerProvider;
    private final String jaxRsServerBasePath;

    // JMS migration settings
    private final JmsProvider jmsProvider;

    // Remote migration settings
    private final RemoteStrategy remoteStrategy;

    // Inject migration settings (JSR-330 / CDI)
    private final InjectStrategy injectStrategy;

    // JSF migration settings
    private final JsfStrategy jsfStrategy;

    // Security migration settings
    private final SecurityStrategy securityStrategy;

    // Build plugin migration settings
    private final boolean keepWildFlyPlugins;
    private final boolean bootPluginInProfiles;

    // EJB classification settings
    private final List<String> allowedEjbTypes;

    // JAX-WS migration settings
    private final String jaxwsProvider;
    private final String jaxwsBasePath;

    /**
     * Creates a ProjectConfiguration with the given source roots and default timer settings.
     */
    public ProjectConfiguration(
            List<String> mainSourceRoots,
            List<String> testSourceRoots,
            List<String> resourceRoots,
            List<String> testResourceRoots) {
        this(mainSourceRoots, testSourceRoots, resourceRoots, testResourceRoots,
             DEFAULT_TIMER_STRATEGY, DEFAULT_CLUSTER_MODE,
             DEFAULT_JAXRS_STRATEGY, DEFAULT_JAXRS_CLIENT_STRATEGY,
             DEFAULT_JAXRS_CLIENT_PROVIDER, DEFAULT_JAXRS_CLIENT_PROVIDER_VERSION,
             DEFAULT_JMS_PROVIDER, DEFAULT_REMOTE_STRATEGY, DEFAULT_INJECT_STRATEGY,
             DEFAULT_JSF_STRATEGY, DEFAULT_KEEP_WILDFLY_PLUGINS, DEFAULT_BOOT_PLUGIN_IN_PROFILES,
             DEFAULT_ALLOWED_EJB_TYPES, DEFAULT_JAXWS_PROVIDER, DEFAULT_JAXWS_BASE_PATH,
             DEFAULT_JAXRS_SERVER_PROVIDER, DEFAULT_JAXRS_SERVER_BASE_PATH);
    }

    /**
     * Creates a ProjectConfiguration with the given source roots and timer settings.
     *
     * @param mainSourceRoots main source directories
     * @param testSourceRoots test source directories
     * @param resourceRoots resource directories
     * @param testResourceRoots test resource directories
     * @param timerStrategy the timer migration strategy
     * @param clusterMode the cluster coordination mode
     */
    public ProjectConfiguration(
            List<String> mainSourceRoots,
            List<String> testSourceRoots,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            TimerStrategy timerStrategy,
            ClusterMode clusterMode) {
        this(mainSourceRoots, testSourceRoots, resourceRoots, testResourceRoots,
             timerStrategy, clusterMode,
             DEFAULT_JAXRS_STRATEGY, DEFAULT_JAXRS_CLIENT_STRATEGY,
             DEFAULT_JAXRS_CLIENT_PROVIDER, DEFAULT_JAXRS_CLIENT_PROVIDER_VERSION,
             DEFAULT_JMS_PROVIDER, DEFAULT_REMOTE_STRATEGY, DEFAULT_INJECT_STRATEGY,
             DEFAULT_JSF_STRATEGY, DEFAULT_KEEP_WILDFLY_PLUGINS, DEFAULT_BOOT_PLUGIN_IN_PROFILES,
             DEFAULT_ALLOWED_EJB_TYPES, DEFAULT_JAXWS_PROVIDER, DEFAULT_JAXWS_BASE_PATH,
             DEFAULT_JAXRS_SERVER_PROVIDER, DEFAULT_JAXRS_SERVER_BASE_PATH);
    }

    /**
     * Creates a ProjectConfiguration with full migration settings (without keepWildFlyPlugins).
     * @deprecated Use the constructor with keepWildFlyPlugins parameter instead.
     */
    @Deprecated
    public ProjectConfiguration(
            List<String> mainSourceRoots,
            List<String> testSourceRoots,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            TimerStrategy timerStrategy,
            ClusterMode clusterMode,
            JaxRsStrategy jaxRsStrategy,
            JaxRsClientStrategy jaxRsClientStrategy,
            String jaxRsClientProvider,
            String jaxRsClientProviderVersion,
            JmsProvider jmsProvider,
            RemoteStrategy remoteStrategy,
            InjectStrategy injectStrategy,
            JsfStrategy jsfStrategy) {
        this(mainSourceRoots, testSourceRoots, resourceRoots, testResourceRoots,
             timerStrategy, clusterMode, jaxRsStrategy, jaxRsClientStrategy,
             jaxRsClientProvider, jaxRsClientProviderVersion, jmsProvider,
             remoteStrategy, injectStrategy, jsfStrategy, DEFAULT_KEEP_WILDFLY_PLUGINS,
             DEFAULT_BOOT_PLUGIN_IN_PROFILES, DEFAULT_ALLOWED_EJB_TYPES,
             DEFAULT_JAXWS_PROVIDER, DEFAULT_JAXWS_BASE_PATH,
             DEFAULT_JAXRS_SERVER_PROVIDER, DEFAULT_JAXRS_SERVER_BASE_PATH);
    }

    /**
     * Creates a ProjectConfiguration with full migration settings (without bootPluginInProfiles).
     * @deprecated Use the constructor with bootPluginInProfiles parameter instead.
     */
    @Deprecated
    public ProjectConfiguration(
            List<String> mainSourceRoots,
            List<String> testSourceRoots,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            TimerStrategy timerStrategy,
            ClusterMode clusterMode,
            JaxRsStrategy jaxRsStrategy,
            JaxRsClientStrategy jaxRsClientStrategy,
            String jaxRsClientProvider,
            String jaxRsClientProviderVersion,
            JmsProvider jmsProvider,
            RemoteStrategy remoteStrategy,
            InjectStrategy injectStrategy,
            JsfStrategy jsfStrategy,
            boolean keepWildFlyPlugins) {
        this(mainSourceRoots, testSourceRoots, resourceRoots, testResourceRoots,
             timerStrategy, clusterMode, jaxRsStrategy, jaxRsClientStrategy,
             jaxRsClientProvider, jaxRsClientProviderVersion, jmsProvider,
             remoteStrategy, injectStrategy, jsfStrategy, keepWildFlyPlugins,
             DEFAULT_BOOT_PLUGIN_IN_PROFILES, DEFAULT_ALLOWED_EJB_TYPES,
             DEFAULT_JAXWS_PROVIDER, DEFAULT_JAXWS_BASE_PATH,
             DEFAULT_JAXRS_SERVER_PROVIDER, DEFAULT_JAXRS_SERVER_BASE_PATH);
    }

    /**
     * Creates a ProjectConfiguration with full migration settings (without allowedEjbTypes).
     * @deprecated Use the constructor with allowedEjbTypes parameter instead.
     */
    @Deprecated
    public ProjectConfiguration(
            List<String> mainSourceRoots,
            List<String> testSourceRoots,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            TimerStrategy timerStrategy,
            ClusterMode clusterMode,
            JaxRsStrategy jaxRsStrategy,
            JaxRsClientStrategy jaxRsClientStrategy,
            String jaxRsClientProvider,
            String jaxRsClientProviderVersion,
            JmsProvider jmsProvider,
            RemoteStrategy remoteStrategy,
            InjectStrategy injectStrategy,
            JsfStrategy jsfStrategy,
            boolean keepWildFlyPlugins,
            boolean bootPluginInProfiles) {
        this(mainSourceRoots, testSourceRoots, resourceRoots, testResourceRoots,
             timerStrategy, clusterMode, jaxRsStrategy, jaxRsClientStrategy,
             jaxRsClientProvider, jaxRsClientProviderVersion, jmsProvider,
             remoteStrategy, injectStrategy, jsfStrategy, keepWildFlyPlugins,
             bootPluginInProfiles, DEFAULT_ALLOWED_EJB_TYPES,
             DEFAULT_JAXWS_PROVIDER, DEFAULT_JAXWS_BASE_PATH,
             DEFAULT_JAXRS_SERVER_PROVIDER, DEFAULT_JAXRS_SERVER_BASE_PATH);
    }

    /**
     * Creates a ProjectConfiguration with full migration settings (without securityStrategy).
     * @deprecated Use the constructor with securityStrategy parameter instead.
     */
    @Deprecated
    public ProjectConfiguration(
            List<String> mainSourceRoots,
            List<String> testSourceRoots,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            TimerStrategy timerStrategy,
            ClusterMode clusterMode,
            JaxRsStrategy jaxRsStrategy,
            JaxRsClientStrategy jaxRsClientStrategy,
            String jaxRsClientProvider,
            String jaxRsClientProviderVersion,
            JmsProvider jmsProvider,
            RemoteStrategy remoteStrategy,
            InjectStrategy injectStrategy,
            JsfStrategy jsfStrategy,
            boolean keepWildFlyPlugins,
            boolean bootPluginInProfiles,
            List<String> allowedEjbTypes,
            String jaxwsProvider,
            String jaxwsBasePath,
            String jaxRsServerProvider,
            String jaxRsServerBasePath) {
        this(mainSourceRoots, testSourceRoots, resourceRoots, testResourceRoots,
             timerStrategy, clusterMode, jaxRsStrategy, jaxRsClientStrategy,
             jaxRsClientProvider, jaxRsClientProviderVersion, jmsProvider,
             remoteStrategy, injectStrategy, jsfStrategy, keepWildFlyPlugins,
             bootPluginInProfiles, allowedEjbTypes, jaxwsProvider, jaxwsBasePath,
             jaxRsServerProvider, jaxRsServerBasePath, DEFAULT_SECURITY_STRATEGY);
    }

    /**
     * Creates a ProjectConfiguration with full migration settings.
     *
     * @param mainSourceRoots main source directories
     * @param testSourceRoots test source directories
     * @param resourceRoots resource directories
     * @param testResourceRoots test resource directories
     * @param timerStrategy the timer migration strategy
     * @param clusterMode the cluster coordination mode
     * @param jaxRsStrategy JAX-RS server migration strategy
     * @param jaxRsClientStrategy JAX-RS client migration strategy
     * @param jaxRsClientProvider JAX-RS client provider
     * @param jaxRsClientProviderVersion JAX-RS client provider version
     * @param jmsProvider JMS provider for MDB migration
     * @param remoteStrategy remote interface migration strategy
     * @param injectStrategy JSR-330 injection annotation strategy
     * @param jsfStrategy JSF runtime migration strategy
     * @param keepWildFlyPlugins if true, skip WildFly plugin removal
     * @param bootPluginInProfiles if true, detect spring-boot-maven-plugin in profiles too
     * @param allowedEjbTypes list of EJB FQNs that are explicitly allowed (not flagged by ClassifyRemainingEjbUsage)
     * @param jaxwsProvider JAX-WS provider (cxf or manual, default: cxf)
     * @param jaxwsBasePath JAX-WS servlet base path (default: /services)
     * @param securityStrategy security migration strategy (keep-jakarta or spring-security)
     */
    public ProjectConfiguration(
            List<String> mainSourceRoots,
            List<String> testSourceRoots,
            List<String> resourceRoots,
            List<String> testResourceRoots,
            TimerStrategy timerStrategy,
            ClusterMode clusterMode,
            JaxRsStrategy jaxRsStrategy,
            JaxRsClientStrategy jaxRsClientStrategy,
            String jaxRsClientProvider,
            String jaxRsClientProviderVersion,
            JmsProvider jmsProvider,
            RemoteStrategy remoteStrategy,
            InjectStrategy injectStrategy,
            JsfStrategy jsfStrategy,
            boolean keepWildFlyPlugins,
            boolean bootPluginInProfiles,
            List<String> allowedEjbTypes,
            String jaxwsProvider,
            String jaxwsBasePath,
            String jaxRsServerProvider,
            String jaxRsServerBasePath,
            SecurityStrategy securityStrategy) {
        this.mainSourceRoots = mainSourceRoots != null ? List.copyOf(mainSourceRoots) : DEFAULT_MAIN_SOURCE_ROOTS;
        this.testSourceRoots = testSourceRoots != null ? List.copyOf(testSourceRoots) : DEFAULT_TEST_SOURCE_ROOTS;
        this.resourceRoots = resourceRoots != null ? List.copyOf(resourceRoots) : DEFAULT_RESOURCE_ROOTS;
        this.testResourceRoots = testResourceRoots != null ? List.copyOf(testResourceRoots) : DEFAULT_TEST_RESOURCE_ROOTS;
        this.timerStrategy = timerStrategy != null ? timerStrategy : DEFAULT_TIMER_STRATEGY;
        this.clusterMode = clusterMode != null ? clusterMode : DEFAULT_CLUSTER_MODE;
        this.jaxRsStrategy = jaxRsStrategy != null ? jaxRsStrategy : DEFAULT_JAXRS_STRATEGY;
        this.jaxRsClientStrategy = jaxRsClientStrategy != null ? jaxRsClientStrategy : DEFAULT_JAXRS_CLIENT_STRATEGY;
        this.jaxRsClientProvider = jaxRsClientProvider != null ? jaxRsClientProvider : DEFAULT_JAXRS_CLIENT_PROVIDER;
        this.jaxRsClientProviderVersion = jaxRsClientProviderVersion;
        this.jaxRsServerProvider = jaxRsServerProvider != null ? jaxRsServerProvider : DEFAULT_JAXRS_SERVER_PROVIDER;
        this.jaxRsServerBasePath = jaxRsServerBasePath != null ? jaxRsServerBasePath : DEFAULT_JAXRS_SERVER_BASE_PATH;
        this.jmsProvider = jmsProvider != null ? jmsProvider : DEFAULT_JMS_PROVIDER;
        this.remoteStrategy = remoteStrategy != null ? remoteStrategy : DEFAULT_REMOTE_STRATEGY;
        this.injectStrategy = injectStrategy != null ? injectStrategy : DEFAULT_INJECT_STRATEGY;
        this.jsfStrategy = jsfStrategy != null ? jsfStrategy : DEFAULT_JSF_STRATEGY;
        this.securityStrategy = securityStrategy != null ? securityStrategy : DEFAULT_SECURITY_STRATEGY;
        this.keepWildFlyPlugins = keepWildFlyPlugins;
        this.bootPluginInProfiles = bootPluginInProfiles;
        this.allowedEjbTypes = allowedEjbTypes != null ? List.copyOf(allowedEjbTypes) : DEFAULT_ALLOWED_EJB_TYPES;
        this.jaxwsProvider = jaxwsProvider != null ? jaxwsProvider : DEFAULT_JAXWS_PROVIDER;
        this.jaxwsBasePath = jaxwsBasePath != null ? jaxwsBasePath : DEFAULT_JAXWS_BASE_PATH;
    }

    /**
     * Returns the default Maven configuration.
     */
    public static ProjectConfiguration mavenDefaults() {
        return new ProjectConfiguration(
                DEFAULT_MAIN_SOURCE_ROOTS,
                DEFAULT_TEST_SOURCE_ROOTS,
                DEFAULT_RESOURCE_ROOTS,
                DEFAULT_TEST_RESOURCE_ROOTS
        );
    }

    public List<String> getMainSourceRoots() {
        return mainSourceRoots;
    }

    public List<String> getTestSourceRoots() {
        return testSourceRoots;
    }

    public List<String> getResourceRoots() {
        return resourceRoots;
    }

    public List<String> getTestResourceRoots() {
        return testResourceRoots;
    }

    /**
     * Returns the timer migration strategy as configured.
     * <p>
     * Note: Use {@link #getEffectiveTimerStrategy()} for validated strategy
     * that takes cluster mode constraints into account.
     *
     * @return the timer strategy (default: SCHEDULED)
     */
    public TimerStrategy getTimerStrategy() {
        return timerStrategy;
    }

    /**
     * Returns the cluster coordination mode for timer migration.
     *
     * @return the cluster mode (default: NONE)
     */
    public ClusterMode getClusterMode() {
        return clusterMode;
    }

    /**
     * Returns the JAX-RS server migration strategy.
     *
     * @return the JAX-RS strategy (default: KEEP_JAXRS)
     */
    public JaxRsStrategy getJaxRsStrategy() {
        return jaxRsStrategy;
    }

    /**
     * Returns the JAX-RS client migration strategy.
     *
     * @return the JAX-RS client strategy (default: KEEP_JAXRS)
     */
    public JaxRsClientStrategy getJaxRsClientStrategy() {
        return jaxRsClientStrategy;
    }

    /**
     * Returns the JAX-RS client provider to use for keep-jaxrs strategy.
     *
     * @return provider id (default: jersey)
     */
    public String getJaxRsClientProvider() {
        return jaxRsClientProvider;
    }

    /**
     * Returns the configured provider version, if any.
     *
     * @return provider version or null
     */
    public String getJaxRsClientProviderVersion() {
        return jaxRsClientProviderVersion;
    }

    /**
     * Returns the JAX-RS server provider to use for keep-jaxrs strategy.
     * <p>
     * Valid values: jersey, resteasy, cxf
     * <p>
     * If null, the provider should be auto-detected from existing dependencies.
     *
     * @return provider id or null for auto-detection
     */
    public String getJaxRsServerProvider() {
        return jaxRsServerProvider;
    }

    /**
     * Returns the JAX-RS server base path for endpoint configuration.
     *
     * @return base path (default: /api)
     */
    public String getJaxRsServerBasePath() {
        return jaxRsServerBasePath;
    }

    /**
     * Returns the JMS provider configuration for message-driven bean migration.
     *
     * @return the JMS provider (default: NONE)
     */
    public JmsProvider getJmsProvider() {
        return jmsProvider;
    }

    /**
     * Checks if a JMS provider is configured (not NONE).
     *
     * @return true if a specific JMS provider is configured
     */
    public boolean hasJmsProviderConfigured() {
        return jmsProvider != JmsProvider.NONE;
    }

    /**
     * Returns the injection annotation migration strategy.
     *
     * @return the inject strategy (default: KEEP_JSR330)
     */
    public InjectStrategy getInjectStrategy() {
        return injectStrategy;
    }

    /**
     * Checks if JSR-330 annotations should be kept (instead of migrating to Spring).
     *
     * @return true if strategy is KEEP_JSR330
     */
    public boolean isKeepJsr330() {
        return injectStrategy == InjectStrategy.KEEP_JSR330;
    }

    /**
     * Returns the remote interface migration strategy.
     *
     * @return the remote strategy (default: REST)
     */
    public RemoteStrategy getRemoteStrategy() {
        return remoteStrategy;
    }

    /**
     * Checks if REST strategy is configured for remote interface migration.
     *
     * @return true if strategy is REST
     */
    public boolean isRestRemoteStrategy() {
        return remoteStrategy == RemoteStrategy.REST;
    }

    /**
     * Returns the JSF runtime migration strategy.
     *
     * @return the JSF strategy (default: JOINFACES)
     */
    public JsfStrategy getJsfStrategy() {
        return jsfStrategy;
    }

    /**
     * Checks if JoinFaces strategy is configured for JSF migration.
     *
     * @return true if strategy is JOINFACES
     */
    public boolean isJoinFacesStrategy() {
        return jsfStrategy == JsfStrategy.JOINFACES;
    }

    /**
     * Checks if manual strategy is configured for JSF migration.
     *
     * @return true if strategy is MANUAL
     */
    public boolean isManualJsfStrategy() {
        return jsfStrategy == JsfStrategy.MANUAL;
    }

    /**
     * Returns the security migration strategy.
     *
     * @return the security strategy (default: KEEP_JAKARTA)
     */
    public SecurityStrategy getSecurityStrategy() {
        return securityStrategy;
    }

    /**
     * Checks if Spring Security strategy is configured.
     * <p>
     * When true, security-related EJB context APIs (getCallerPrincipal, isCallerInRole)
     * will be migrated to Spring Security equivalents.
     *
     * @return true if strategy is SPRING_SECURITY
     */
    public boolean isSpringSecurityStrategy() {
        return securityStrategy == SecurityStrategy.SPRING_SECURITY;
    }

    /**
     * Checks if Jakarta EJB context should be kept for security methods.
     *
     * @return true if strategy is KEEP_JAKARTA
     */
    public boolean isKeepJakartaSecurityStrategy() {
        return securityStrategy == SecurityStrategy.KEEP_JAKARTA;
    }

    /**
     * Checks if WildFly plugin removal should be skipped.
     * <p>
     * When this returns true, the RemoveWildFlyPlugins recipe will not remove
     * wildfly-maven-plugin from the build configuration.
     *
     * @return true if WildFly plugins should be kept (opt-out)
     */
    public boolean isKeepWildFlyPlugins() {
        return keepWildFlyPlugins;
    }

    /**
     * Checks if spring-boot-maven-plugin detection should include profiles.
     * <p>
     * By default (false), only {@code /project/build/plugins} and
     * {@code /project/build/pluginManagement/plugins} are checked for Spring Boot plugin.
     * <p>
     * When true, profiles are also checked. This is an opt-in behavior because
     * having spring-boot-maven-plugin only in an optional profile should not
     * trigger WildFly plugin removal in the default build.
     *
     * @return true if profiles should be checked for Spring Boot plugin
     */
    public boolean isBootPluginInProfiles() {
        return bootPluginInProfiles;
    }

    /**
     * Returns the list of allowed EJB types that should not be flagged by ClassifyRemainingEjbUsage.
     * <p>
     * These are fully qualified names (e.g., "jakarta.ejb.Timer") that are intentionally
     * kept in the codebase and should not trigger @NeedsReview annotations.
     *
     * @return unmodifiable list of allowed EJB type FQNs (default: empty list)
     */
    public List<String> getAllowedEjbTypes() {
        return allowedEjbTypes;
    }

    /**
     * Returns the JAX-WS provider configuration.
     * <ul>
     *   <li>{@code cxf} - Use Apache CXF Spring Boot Starter (default)</li>
     *   <li>{@code manual} - Only mark JAX-WS endpoints for manual migration</li>
     * </ul>
     *
     * @return the JAX-WS provider (default: cxf)
     */
    public String getJaxwsProvider() {
        return jaxwsProvider;
    }

    /**
     * Returns the JAX-WS servlet base path.
     * <p>
     * This is the base path where all JAX-WS endpoints will be published.
     * Individual endpoints will be available at {@code {basePath}/{endpointPath}}.
     *
     * @return the JAX-WS base path (default: /services)
     */
    public String getJaxwsBasePath() {
        return jaxwsBasePath;
    }

    /**
     * Checks if CXF provider is configured for JAX-WS migration.
     *
     * @return true if provider is cxf (default)
     */
    public boolean isCxfJaxwsProvider() {
        return "cxf".equalsIgnoreCase(jaxwsProvider);
    }

    /**
     * Checks if manual mode is configured for JAX-WS migration.
     *
     * @return true if provider is manual
     */
    public boolean isManualJaxwsProvider() {
        return "manual".equalsIgnoreCase(jaxwsProvider);
    }

    /**
     * Returns the effective timer strategy after validating against cluster mode constraints.
     * <p>
     * Validation rules:
     * <ul>
     *   <li>cluster: quartz-jdbc requires strategy: quartz</li>
     *   <li>cluster: shedlock is not compatible with strategy: quartz</li>
     *   <li>cluster: none allows any strategy</li>
     * </ul>
     *
     * @return the validated timer strategy
     * @throws ConfigurationException if strategy and cluster mode are incompatible
     */
    public TimerStrategy getEffectiveTimerStrategy() {
        if (clusterMode == ClusterMode.QUARTZ_JDBC) {
            if (timerStrategy != TimerStrategy.QUARTZ) {
                throw new ConfigurationException(
                        "cluster: quartz-jdbc erfordert strategy: quartz (aktuell: " +
                        timerStrategy.name().toLowerCase() + ")");
            }
            return TimerStrategy.QUARTZ;
        }
        if (clusterMode == ClusterMode.SHEDLOCK && timerStrategy == TimerStrategy.QUARTZ) {
            throw new ConfigurationException(
                    "cluster: shedlock ist nicht kompatibel mit strategy: quartz. " +
                    "Verwenden Sie cluster: quartz-jdbc fuer Quartz-Cluster-Support.");
        }
        return timerStrategy;
    }

    /**
     * Checks if cluster coordination is enabled.
     *
     * @return true if cluster mode is not NONE
     */
    public boolean isClusterEnabled() {
        return clusterMode != ClusterMode.NONE;
    }

    /**
     * Checks if Quartz JDBC clustering is configured.
     *
     * @return true if cluster mode is QUARTZ_JDBC
     */
    public boolean isQuartzJdbcCluster() {
        return clusterMode == ClusterMode.QUARTZ_JDBC;
    }

    /**
     * Checks if ShedLock distributed locking is configured.
     *
     * @return true if cluster mode is SHEDLOCK
     */
    public boolean isShedLockCluster() {
        return clusterMode == ClusterMode.SHEDLOCK;
    }

    /**
     * Checks if the given path is within a test source root.
     *
     * @param sourcePath the source path to check (e.g., "module/src/test/java/com/example/Test.java")
     * @return true if the path is in a test source root
     */
    public boolean isTestSource(String sourcePath) {
        if (sourcePath == null) {
            return false;
        }
        String normalizedPath = sourcePath.replace('\\', '/');
        for (String testRoot : testSourceRoots) {
            // Check for pattern like "src/test/java" or "/src/test/java"
            if (normalizedPath.contains("/" + testRoot + "/") || normalizedPath.contains(testRoot + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the given path is within a main source root.
     *
     * @param sourcePath the source path to check
     * @return true if the path is in a main source root
     */
    public boolean isMainSource(String sourcePath) {
        if (sourcePath == null) {
            return false;
        }
        String normalizedPath = sourcePath.replace('\\', '/');
        for (String mainRoot : mainSourceRoots) {
            if (normalizedPath.contains("/" + mainRoot + "/") || normalizedPath.contains(mainRoot + "/")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the given path is within a resource root.
     *
     * @param sourcePath the source path to check
     * @return true if the path is in a resource root
     */
    public boolean isResource(String sourcePath) {
        if (sourcePath == null) {
            return false;
        }
        String normalizedPath = sourcePath.replace('\\', '/');
        for (String resourceRoot : resourceRoots) {
            if (normalizedPath.contains("/" + resourceRoot + "/") || normalizedPath.contains(resourceRoot + "/")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "ProjectConfiguration{" +
                "mainSourceRoots=" + mainSourceRoots +
                ", testSourceRoots=" + testSourceRoots +
                ", resourceRoots=" + resourceRoots +
                ", testResourceRoots=" + testResourceRoots +
                ", timerStrategy=" + timerStrategy +
                ", clusterMode=" + clusterMode +
                ", jaxRsStrategy=" + jaxRsStrategy +
                ", jaxRsClientStrategy=" + jaxRsClientStrategy +
                ", jaxRsClientProvider=" + jaxRsClientProvider +
                ", jaxRsClientProviderVersion=" + jaxRsClientProviderVersion +
                ", jmsProvider=" + jmsProvider +
                ", remoteStrategy=" + remoteStrategy +
                ", injectStrategy=" + injectStrategy +
                ", jsfStrategy=" + jsfStrategy +
                ", securityStrategy=" + securityStrategy +
                ", keepWildFlyPlugins=" + keepWildFlyPlugins +
                ", bootPluginInProfiles=" + bootPluginInProfiles +
                ", allowedEjbTypes=" + allowedEjbTypes +
                ", jaxwsProvider='" + jaxwsProvider + '\'' +
                ", jaxwsBasePath='" + jaxwsBasePath + '\'' +
                '}';
    }
}
