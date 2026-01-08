package com.github.rewrite.ejb;

import com.github.rewrite.ejb.config.ProjectConfiguration;
import com.github.rewrite.ejb.config.ProjectConfigurationLoader;
import lombok.EqualsAndHashCode;
import lombok.Value;
import org.openrewrite.*;
import org.openrewrite.maven.MavenIsoVisitor;
import org.openrewrite.maven.RemovePlugin;
import org.openrewrite.maven.RemoveProperty;
import org.openrewrite.xml.XPathMatcher;
import org.openrewrite.xml.tree.Content;
import org.openrewrite.xml.tree.Xml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Removes WildFly-specific build plugins and profiles after Spring Boot migration.
 * <p>
 * This recipe performs the following transformations:
 * <ul>
 *   <li>Removes {@code wildfly-maven-plugin} from build/plugins</li>
 *   <li>Removes {@code wildfly-maven-plugin} from build/pluginManagement/plugins</li>
 *   <li>Removes profiles that ONLY contain WildFly plugin configuration (no other elements)</li>
 *   <li>Removes WildFly plugin blocks from mixed profiles (from both plugins and pluginManagement)</li>
 *   <li>Removes unused version properties (e.g., {@code version.plugin.wildfly})</li>
 * </ul>
 * <p>
 * Prerequisites:
 * <ul>
 *   <li>Only acts if {@code spring-boot-maven-plugin} exists in this module or any ancestor</li>
 *   <li>Opt-out via {@code migration.build.keepWildFlyPlugins=true} in project.yaml</li>
 * </ul>
 * <p>
 * Profile detection: A profile is considered "WildFly-only" if it contains ONLY the WildFly plugin
 * and no other meaningful content (dependencies, properties, other plugins, etc.). Profiles with
 * any other content are kept but have the WildFly plugin removed.
 * <p>
 * <b>Configuration Inheritance:</b> Configuration flags ({@code keepWildFlyPlugins} and
 * {@code bootPluginInProfiles}) use "sticky TRUE" semantics:
 * <ul>
 *   <li>If any ancestor module sets a flag to TRUE, it propagates to all descendants and cannot
 *       be overridden to FALSE by child modules</li>
 *   <li>If no ancestor has the flag set to TRUE, a module can enable it for itself and its
 *       descendants by setting it to TRUE in its own project.yaml</li>
 *   <li>This ensures opt-outs (keepWildFlyPlugins) and opt-ins (bootPluginInProfiles) propagate
 *       downward consistently but cannot be reverted by child modules</li>
 * </ul>
 * Configuration is loaded from the first project.yaml found walking up from the module directory.
 * <p>
 * <b>Test Configuration:</b> For unit testing, configuration can be injected via
 * {@link ExecutionContext} using the message key {@link #TEST_CONFIG_KEY}.
 */
@Value
@EqualsAndHashCode(callSuper = false)
public class RemoveWildFlyPlugins extends ScanningRecipe<RemoveWildFlyPlugins.Accumulator> {

    private static final String WILDFLY_GROUP_ID = "org.wildfly.plugins";
    private static final String WILDFLY_ARTIFACT_ID = "wildfly-maven-plugin";
    private static final String SPRING_BOOT_GROUP_ID = "org.springframework.boot";
    private static final String SPRING_BOOT_ARTIFACT_ID = "spring-boot-maven-plugin";

    // XPath matchers for precise element detection
    private static final XPathMatcher BUILD_PLUGIN_MATCHER = new XPathMatcher("/project/build/plugins/plugin");
    private static final XPathMatcher PLUGIN_MGMT_PLUGIN_MATCHER = new XPathMatcher("/project/build/pluginManagement/plugins/plugin");
    private static final XPathMatcher PROFILE_PLUGIN_MATCHER = new XPathMatcher("/project/profiles/profile/build/plugins/plugin");
    private static final XPathMatcher PROFILE_PLUGIN_MGMT_MATCHER = new XPathMatcher("/project/profiles/profile/build/pluginManagement/plugins/plugin");
    private static final XPathMatcher PROFILE_MATCHER = new XPathMatcher("/project/profiles/profile");
    private static final XPathMatcher PROPERTIES_MATCHER = new XPathMatcher("/project/properties");
    private static final XPathMatcher PROFILE_PROPERTIES_MATCHER = new XPathMatcher("/project/profiles/profile/properties");

    // Pattern for WildFly version properties
    private static final Pattern WILDFLY_PROPERTY_PATTERN = Pattern.compile(
        "version\\.plugin\\.wildfly|wildfly\\.plugin\\.version|wildfly-maven-plugin\\.version",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * ExecutionContext message key for test configuration injection.
     * Tests can set a {@link ProjectConfiguration} via {@code ctx.putMessage(TEST_CONFIG_KEY, config)}.
     */
    public static final String TEST_CONFIG_KEY = "test.config.RemoveWildFlyPlugins";

    @Override
    public String getDisplayName() {
        return "Remove WildFly build plugins after Spring Boot migration";
    }

    @Override
    public String getDescription() {
        return "Removes wildfly-maven-plugin from build plugins, pluginManagement, and profiles " +
               "after Spring Boot migration. Only applies when spring-boot-maven-plugin is present. " +
               "Also removes WildFly-only profiles and unused version properties.";
    }

    static class Accumulator {
        // Track modules with spring-boot-maven-plugin in build/plugins or pluginManagement (migration completed)
        Set<String> modulesWithSpringBoot = new HashSet<>();
        // MEDIUM fix: Track modules with spring-boot-maven-plugin ONLY in profiles (not default build)
        Set<String> modulesWithSpringBootInProfileOnly = new HashSet<>();
        // Track modules with wildfly-maven-plugin
        Set<String> modulesWithWildFly = new HashSet<>();
        // Track WildFly version properties found in /project/properties
        Set<String> wildflyVersionProperties = new HashSet<>();
        // LOW fix: Track WildFly version properties found in profile properties
        // Keyed by modulePath + ":" + profileId to avoid collision in multi-module builds
        Map<String, Set<String>> profileWildflyVersionProperties = new HashMap<>();
        // Track profile IDs that only contain WildFly plugin config
        Map<String, Set<String>> modulesWithWildFlyOnlyProfiles = new HashMap<>();
        // HIGH fix: Track parent-child relationships for inheritance checking
        Map<String, String> moduleParent = new HashMap<>();  // child -> parent module path
        // HIGH fix: Track modules where opt-out is configured
        Set<String> modulesWithOptOut = new HashSet<>();
        // MEDIUM fix: Track modules where bootPluginInProfiles is enabled
        Set<String> modulesWithBootPluginInProfiles = new HashSet<>();
    }

    @Override
    public Accumulator getInitialValue(ExecutionContext ctx) {
        return new Accumulator();
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getScanner(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof Xml.Document)) {
                    return tree;
                }

                Xml.Document doc = (Xml.Document) tree;
                String sourcePath = doc.getSourcePath().toString().replace('\\', '/');
                if (!sourcePath.endsWith("pom.xml")) {
                    return tree;
                }

                String modulePath = getModulePathFromPom(sourcePath);

                // HIGH fix: Check for opt-out configuration via project.yaml
                ProjectConfiguration config = loadConfigForModule(ctx, sourcePath, modulePath);
                if (config != null && config.isKeepWildFlyPlugins()) {
                    acc.modulesWithOptOut.add(modulePath);
                }
                // MEDIUM fix: Check for bootPluginInProfiles opt-in
                if (config != null && config.isBootPluginInProfiles()) {
                    acc.modulesWithBootPluginInProfiles.add(modulePath);
                }

                // HIGH fix: Scan for parent-child relationships (modules element)
                scanModuleRelationships(doc, modulePath, acc);

                scanPom(doc, modulePath, acc);

                return tree;
            }

            /**
             * Loads the ProjectConfiguration for a module.
             * Returns null if project root cannot be determined.
             * <p>
             * If a test configuration is set via ExecutionContext message key {@link #TEST_CONFIG_KEY},
             * that is returned instead of loading from file.
             * <p>
             * Note: Configuration flags use "sticky TRUE" semantics. If any ancestor sets a flag to TRUE,
             * it cannot be overridden to FALSE by descendants. However, a module can enable a flag for
             * itself and its descendants if no ancestor has already enabled it. Configuration is loaded
             * from the module-specific project.yaml if present, otherwise from the first project.yaml
             * found walking up from the module directory.
             *
             * @param ctx ExecutionContext that may contain test configuration
             * @param sourcePath the source path of the pom.xml
             * @param modulePath the module path relative to project root
             * @return the configuration, or null if not found
             */
            private ProjectConfiguration loadConfigForModule(ExecutionContext ctx, String sourcePath, String modulePath) {
                // Check for test configuration override via ExecutionContext
                ProjectConfiguration testConfig = ctx.getMessage(TEST_CONFIG_KEY);
                if (testConfig != null) {
                    return testConfig;
                }
                Path projectRoot = extractProjectRootFromPom(sourcePath, modulePath);
                if (projectRoot == null) {
                    return null;
                }
                return ProjectConfigurationLoader.loadWithInheritance(projectRoot);
            }

            /**
             * HIGH fix: Extracts the project root for loading configuration.
             * For submodules, checks for module-specific project.yaml first.
             */
            private Path extractProjectRootFromPom(String pomPath, String modulePath) {
                // Get the absolute path to the pom.xml
                Path pomAbsPath = Paths.get(pomPath);
                if (!pomAbsPath.isAbsolute()) {
                    pomAbsPath = Paths.get(System.getProperty("user.dir")).resolve(pomPath);
                }
                Path pomDir = pomAbsPath.getParent();
                if (pomDir == null) {
                    return null;
                }

                // Check for module-specific project.yaml
                if (Files.exists(pomDir.resolve("project.yaml"))) {
                    return pomDir;
                }

                // Walk up to find root project.yaml or build file
                Path current = pomDir.getParent();
                while (current != null) {
                    if (Files.exists(current.resolve("project.yaml"))) {
                        return current;
                    }
                    if (Files.exists(current.resolve("pom.xml"))) {
                        // This might be the root pom
                        if (Files.exists(current.resolve("project.yaml"))) {
                            return current;
                        }
                    }
                    current = current.getParent();
                }

                // Return the pom directory as fallback
                return pomDir;
            }

            /**
             * HIGH fix: Scans for module definitions to build parent-child relationships.
             * This enables ancestor checking for Spring Boot plugin detection.
             */
            private void scanModuleRelationships(Xml.Document doc, String parentModulePath, Accumulator acc) {
                Xml.Tag root = doc.getRoot();
                if (root == null) {
                    return;
                }
                for (Content content : root.getContent()) {
                    if (content instanceof Xml.Tag) {
                        Xml.Tag child = (Xml.Tag) content;
                        if ("modules".equals(child.getName())) {
                            for (Content moduleContent : child.getContent()) {
                                if (moduleContent instanceof Xml.Tag) {
                                    Xml.Tag moduleTag = (Xml.Tag) moduleContent;
                                    if ("module".equals(moduleTag.getName())) {
                                        String moduleName = moduleTag.getValue().orElse("");
                                        if (!moduleName.isEmpty()) {
                                            String childPath = parentModulePath.isEmpty()
                                                ? moduleName
                                                : parentModulePath + "/" + moduleName;
                                            acc.moduleParent.put(childPath, parentModulePath);
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            private void scanPom(Xml.Document doc, String modulePath, Accumulator acc) {
                new org.openrewrite.xml.XmlVisitor<Accumulator>() {
                    // Track current profile ID for property scanning
                    private String currentProfileId = null;

                    @Override
                    public Xml visitTag(Xml.Tag tag, Accumulator a) {
                        // Track profile context for property scanning
                        if (PROFILE_MATCHER.matches(getCursor())) {
                            currentProfileId = extractChildValue(tag, "id");
                            Xml result = super.visitTag(tag, a);

                            // Check profiles for WildFly-only content
                            if (currentProfileId != null && isWildFlyOnlyProfile(tag)) {
                                a.modulesWithWildFlyOnlyProfiles
                                    .computeIfAbsent(modulePath, k -> new HashSet<>())
                                    .add(currentProfileId);
                            }
                            currentProfileId = null;
                            return result;
                        }

                        // MEDIUM fix: Check build plugins - distinguish between main build and profiles
                        boolean isInProfile = PROFILE_PLUGIN_MATCHER.matches(getCursor()) ||
                                              PROFILE_PLUGIN_MGMT_MATCHER.matches(getCursor());
                        boolean isInMainBuild = BUILD_PLUGIN_MATCHER.matches(getCursor()) ||
                                                PLUGIN_MGMT_PLUGIN_MATCHER.matches(getCursor());

                        if (isInMainBuild || isInProfile) {
                            String groupId = extractChildValue(tag, "groupId");
                            String artifactId = extractChildValue(tag, "artifactId");

                            if (SPRING_BOOT_GROUP_ID.equals(groupId) &&
                                SPRING_BOOT_ARTIFACT_ID.equals(artifactId)) {
                                if (isInMainBuild) {
                                    // Spring Boot in main build - always counts as migration signal
                                    a.modulesWithSpringBoot.add(modulePath);
                                } else {
                                    // MEDIUM fix: Spring Boot only in profile - track separately
                                    // Only count if not already found in main build
                                    if (!a.modulesWithSpringBoot.contains(modulePath)) {
                                        a.modulesWithSpringBootInProfileOnly.add(modulePath);
                                    }
                                }
                            }

                            if (WILDFLY_GROUP_ID.equals(groupId) &&
                                WILDFLY_ARTIFACT_ID.equals(artifactId)) {
                                a.modulesWithWildFly.add(modulePath);
                            }
                        }

                        // Check for WildFly version properties in /project/properties
                        if (PROPERTIES_MATCHER.matches(getCursor().getParent())) {
                            String propName = tag.getName();
                            if (WILDFLY_PROPERTY_PATTERN.matcher(propName).matches()) {
                                a.wildflyVersionProperties.add(propName);
                            }
                        }

                        // LOW fix: Check for WildFly version properties in profile properties
                        // Key by modulePath + ":" + profileId to avoid collision in multi-module builds
                        if (currentProfileId != null && PROFILE_PROPERTIES_MATCHER.matches(getCursor().getParent())) {
                            String propName = tag.getName();
                            if (WILDFLY_PROPERTY_PATTERN.matcher(propName).matches()) {
                                String moduleProfileKey = modulePath + ":" + currentProfileId;
                                a.profileWildflyVersionProperties
                                    .computeIfAbsent(moduleProfileKey, k -> new HashSet<>())
                                    .add(propName);
                            }
                        }

                        return super.visitTag(tag, a);
                    }

                    /**
                     * MEDIUM fix: Determines if a profile only contains WildFly plugin configuration.
                     * A profile is WildFly-only if:
                     * 1. It has a build section with only wildfly-maven-plugin
                     * 2. It has NO other meaningful content (dependencies, properties, reporting, etc.)
                     *
                     * Any element other than id, activation, and build (with only WildFly plugin)
                     * is considered "other content" that prevents profile removal.
                     */
                    private boolean isWildFlyOnlyProfile(Xml.Tag profileTag) {
                        boolean hasWildFlyPlugin = false;
                        boolean hasOtherPlugins = false;
                        boolean hasOtherContent = false;

                        for (Content content : profileTag.getContent()) {
                            if (content instanceof Xml.Tag) {
                                Xml.Tag child = (Xml.Tag) content;
                                String name = child.getName();

                                if ("id".equals(name)) {
                                    continue; // ID is always present, skip
                                }

                                if ("activation".equals(name)) {
                                    continue; // Activation alone doesn't count as content
                                }

                                if ("build".equals(name)) {
                                    // Check build content for plugins
                                    BuildAnalysisResult buildResult = analyzeBuildElement(child);
                                    hasWildFlyPlugin = hasWildFlyPlugin || buildResult.hasWildFlyPlugin;
                                    hasOtherPlugins = hasOtherPlugins || buildResult.hasOtherPlugins;
                                    hasOtherContent = hasOtherContent || buildResult.hasOtherBuildContent;
                                } else {
                                    // MEDIUM fix: ANY other element (properties, dependencies,
                                    // dependencyManagement, reporting, repositories, etc.)
                                    // counts as other content that prevents profile removal
                                    hasOtherContent = true;
                                }
                            }
                        }

                        // Profile is WildFly-only if it has WildFly plugin and nothing else
                        return hasWildFlyPlugin && !hasOtherPlugins && !hasOtherContent;
                    }

                    /**
                     * Helper class to hold build element analysis results.
                     */
                    private static class BuildAnalysisResult {
                        boolean hasWildFlyPlugin = false;
                        boolean hasOtherPlugins = false;
                        boolean hasOtherBuildContent = false;
                    }

                    /**
                     * Analyzes a build element to find plugins and other content.
                     */
                    private BuildAnalysisResult analyzeBuildElement(Xml.Tag buildTag) {
                        BuildAnalysisResult result = new BuildAnalysisResult();

                        for (Content buildContent : buildTag.getContent()) {
                            if (buildContent instanceof Xml.Tag) {
                                Xml.Tag buildChild = (Xml.Tag) buildContent;
                                String buildChildName = buildChild.getName();

                                if ("plugins".equals(buildChildName)) {
                                    analyzePluginsElement(buildChild, result);
                                } else if ("pluginManagement".equals(buildChildName)) {
                                    // Check pluginManagement/plugins for WildFly plugin
                                    for (Content pmContent : buildChild.getContent()) {
                                        if (pmContent instanceof Xml.Tag) {
                                            Xml.Tag pmChild = (Xml.Tag) pmContent;
                                            if ("plugins".equals(pmChild.getName())) {
                                                analyzePluginsElement(pmChild, result);
                                            }
                                        }
                                    }
                                } else {
                                    // Other build content (resources, directory, filters, etc.)
                                    result.hasOtherBuildContent = true;
                                }
                            }
                        }

                        return result;
                    }

                    /**
                     * Analyzes a plugins element to find WildFly and other plugins.
                     */
                    private void analyzePluginsElement(Xml.Tag pluginsTag, BuildAnalysisResult result) {
                        for (Content pluginContent : pluginsTag.getContent()) {
                            if (pluginContent instanceof Xml.Tag) {
                                Xml.Tag pluginTag = (Xml.Tag) pluginContent;
                                if ("plugin".equals(pluginTag.getName())) {
                                    String gid = extractChildValue(pluginTag, "groupId");
                                    String aid = extractChildValue(pluginTag, "artifactId");
                                    if (WILDFLY_GROUP_ID.equals(gid) && WILDFLY_ARTIFACT_ID.equals(aid)) {
                                        result.hasWildFlyPlugin = true;
                                    } else {
                                        result.hasOtherPlugins = true;
                                    }
                                }
                            }
                        }
                    }

                    private String extractChildValue(Xml.Tag parent, String childName) {
                        for (Content content : parent.getContent()) {
                            if (content instanceof Xml.Tag) {
                                Xml.Tag child = (Xml.Tag) content;
                                if (childName.equals(child.getName())) {
                                    return child.getValue().orElse("");
                                }
                            }
                        }
                        return null;
                    }
                }.visit(doc, acc);
            }

            private String getModulePathFromPom(String pomPath) {
                if (pomPath.equals("pom.xml")) {
                    return "";
                }
                int pomIndex = pomPath.indexOf("/pom.xml");
                if (pomIndex > 0) {
                    return pomPath.substring(0, pomIndex);
                }
                return "";
            }
        };
    }

    @Override
    public TreeVisitor<?, ExecutionContext> getVisitor(Accumulator acc) {
        return new TreeVisitor<Tree, ExecutionContext>() {
            @Override
            public Tree visit(Tree tree, ExecutionContext ctx) {
                if (!(tree instanceof Xml.Document)) {
                    return tree;
                }

                Xml.Document doc = (Xml.Document) tree;
                String sourcePath = doc.getSourcePath().toString().replace('\\', '/');
                if (!sourcePath.endsWith("pom.xml")) {
                    return tree;
                }

                String modulePath = getModulePathFromPom(sourcePath);

                // HIGH fix: Check for opt-out configuration
                if (acc.modulesWithOptOut.contains(modulePath) || hasAncestorWithOptOut(modulePath, acc)) {
                    return tree;
                }

                // HIGH fix: Check if spring-boot-maven-plugin is present in this module OR any ancestor
                // MEDIUM fix: Profile-only Spring Boot plugins only count if bootPluginInProfiles is enabled
                if (!hasSpringBootInModuleOrAncestor(modulePath, acc)) {
                    return tree;
                }

                // Only process if wildfly-maven-plugin is present
                if (!acc.modulesWithWildFly.contains(modulePath)) {
                    return tree;
                }

                Tree result = tree;

                // 1. Remove wildfly-maven-plugin from build/plugins and pluginManagement
                result = new RemovePlugin(WILDFLY_GROUP_ID, WILDFLY_ARTIFACT_ID)
                    .getVisitor().visit(result, ctx);

                // 2. Remove WildFly-only profiles
                Set<String> profilesToRemove = acc.modulesWithWildFlyOnlyProfiles.get(modulePath);
                if (profilesToRemove != null && !profilesToRemove.isEmpty()) {
                    result = new RemoveWildFlyOnlyProfiles(profilesToRemove).visit(result, ctx);
                }

                // 3. Remove WildFly plugin from mixed profiles (from both plugins and pluginManagement)
                result = new RemoveWildFlyPluginFromProfiles().visit(result, ctx);

                // 4. Remove WildFly version properties from /project/properties
                for (String propName : acc.wildflyVersionProperties) {
                    result = new RemoveProperty(propName).getVisitor().visit(result, ctx);
                }

                // 5. LOW fix: Remove WildFly version properties from profile properties
                // Pass modulePath for module-aware key lookup
                if (!acc.profileWildflyVersionProperties.isEmpty()) {
                    result = new RemoveWildFlyPropertiesFromProfiles(acc.profileWildflyVersionProperties, modulePath)
                        .visit(result, ctx);
                }

                return result;
            }

            /**
             * HIGH fix: Checks if Spring Boot plugin is present in this module or any ancestor.
             * This handles the common case where spring-boot-maven-plugin is declared only in the parent POM.
             * <p>
             * MEDIUM fix: Profile-only Spring Boot plugins only count if bootPluginInProfiles is enabled
             * for the module (opt-in behavior to prevent breaking default builds).
             * <p>
             * MEDIUM fix (Round 6): bootPluginInProfiles uses "sticky TRUE" semantics - if any ancestor
             * has it set to TRUE, it propagates to all descendants and cannot be overridden to FALSE.
             */
            private boolean hasSpringBootInModuleOrAncestor(String modulePath, Accumulator acc) {
                // Check this module directly (main build)
                if (acc.modulesWithSpringBoot.contains(modulePath)) {
                    return true;
                }

                // MEDIUM fix (Round 6): Check profile-only Spring Boot with "sticky TRUE" semantics
                // If this module OR any ancestor has bootPluginInProfiles=true, the flag applies
                boolean bootPluginInProfilesEffective = acc.modulesWithBootPluginInProfiles.contains(modulePath) ||
                                                        hasAncestorBootPluginInProfiles(modulePath, acc);
                if (bootPluginInProfilesEffective &&
                    acc.modulesWithSpringBootInProfileOnly.contains(modulePath)) {
                    return true;
                }

                // Check ancestors (walk up parent chain)
                String current = modulePath;
                while (true) {
                    String parent = acc.moduleParent.get(current);
                    if (parent == null) {
                        // No more ancestors - try to infer parent from path structure
                        int lastSlash = current.lastIndexOf('/');
                        if (lastSlash > 0) {
                            parent = current.substring(0, lastSlash);
                        } else if (!current.isEmpty()) {
                            parent = ""; // Root module
                        } else {
                            break; // Already at root
                        }
                    }

                    if (acc.modulesWithSpringBoot.contains(parent)) {
                        return true;
                    }

                    // MEDIUM fix (Round 6): Check profile-only Spring Boot in parent with "sticky TRUE"
                    // The bootPluginInProfilesEffective already considers all ancestors, so use it here
                    if (bootPluginInProfilesEffective &&
                        acc.modulesWithSpringBootInProfileOnly.contains(parent)) {
                        return true;
                    }

                    if (parent.isEmpty() || parent.equals(current)) {
                        break;
                    }
                    current = parent;
                }

                return false;
            }

            /**
             * MEDIUM fix (Round 6): Checks if any ancestor module has bootPluginInProfiles enabled.
             * This implements "sticky TRUE" semantics for configuration inheritance.
             */
            private boolean hasAncestorBootPluginInProfiles(String modulePath, Accumulator acc) {
                String current = modulePath;
                while (true) {
                    String parent = acc.moduleParent.get(current);
                    if (parent == null) {
                        int lastSlash = current.lastIndexOf('/');
                        if (lastSlash > 0) {
                            parent = current.substring(0, lastSlash);
                        } else if (!current.isEmpty()) {
                            parent = "";
                        } else {
                            break;
                        }
                    }

                    if (acc.modulesWithBootPluginInProfiles.contains(parent)) {
                        return true;
                    }

                    if (parent.isEmpty() || parent.equals(current)) {
                        break;
                    }
                    current = parent;
                }
                return false;
            }

            /**
             * HIGH fix: Checks if any ancestor module has opt-out configured.
             */
            private boolean hasAncestorWithOptOut(String modulePath, Accumulator acc) {
                String current = modulePath;
                while (true) {
                    String parent = acc.moduleParent.get(current);
                    if (parent == null) {
                        int lastSlash = current.lastIndexOf('/');
                        if (lastSlash > 0) {
                            parent = current.substring(0, lastSlash);
                        } else if (!current.isEmpty()) {
                            parent = "";
                        } else {
                            break;
                        }
                    }

                    if (acc.modulesWithOptOut.contains(parent)) {
                        return true;
                    }

                    if (parent.isEmpty() || parent.equals(current)) {
                        break;
                    }
                    current = parent;
                }
                return false;
            }

            private String getModulePathFromPom(String pomPath) {
                pomPath = pomPath.replace('\\', '/');
                if (pomPath.equals("pom.xml")) {
                    return "";
                }
                int pomIndex = pomPath.indexOf("/pom.xml");
                if (pomIndex > 0) {
                    return pomPath.substring(0, pomIndex);
                }
                return "";
            }
        };
    }

    /**
     * Visitor that removes profiles that only contain WildFly plugin configuration.
     */
    private static class RemoveWildFlyOnlyProfiles extends MavenIsoVisitor<ExecutionContext> {
        private final Set<String> profileIdsToRemove;

        RemoveWildFlyOnlyProfiles(Set<String> profileIdsToRemove) {
            this.profileIdsToRemove = profileIdsToRemove;
        }

        @Override
        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
            Xml.Tag t = super.visitTag(tag, ctx);

            if ("profile".equals(t.getName())) {
                String profileId = extractChildValue(t, "id");
                if (profileId != null && profileIdsToRemove.contains(profileId)) {
                    // Remove this profile entirely
                    return null;
                }
            }

            return t;
        }

        private String extractChildValue(Xml.Tag parent, String childName) {
            for (Content content : parent.getContent()) {
                if (content instanceof Xml.Tag) {
                    Xml.Tag child = (Xml.Tag) content;
                    if (childName.equals(child.getName())) {
                        return child.getValue().orElse("");
                    }
                }
            }
            return null;
        }
    }

    /**
     * Visitor that removes WildFly plugin from profiles that contain other content.
     * <p>
     * LOW fix: This visitor removes WildFly plugin blocks from BOTH locations within profiles:
     * <ul>
     *   <li>{@code profile/build/plugins/plugin} - direct plugin declarations</li>
     *   <li>{@code profile/build/pluginManagement/plugins/plugin} - plugin management declarations</li>
     * </ul>
     * This is intentional behavior to ensure complete cleanup of WildFly plugin references.
     */
    private static class RemoveWildFlyPluginFromProfiles extends MavenIsoVisitor<ExecutionContext> {
        private boolean inProfile = false;

        @Override
        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
            if ("profile".equals(tag.getName())) {
                inProfile = true;
                Xml.Tag result = super.visitTag(tag, ctx);
                inProfile = false;
                return result;
            }

            // LOW fix: Remove WildFly plugin from both plugins and pluginManagement sections
            if (inProfile && "plugin".equals(tag.getName())) {
                String groupId = extractChildValue(tag, "groupId");
                String artifactId = extractChildValue(tag, "artifactId");

                if (WILDFLY_GROUP_ID.equals(groupId) && WILDFLY_ARTIFACT_ID.equals(artifactId)) {
                    // Remove this plugin from the profile (regardless of location)
                    return null;
                }
            }

            return super.visitTag(tag, ctx);
        }

        private String extractChildValue(Xml.Tag parent, String childName) {
            for (Content content : parent.getContent()) {
                if (content instanceof Xml.Tag) {
                    Xml.Tag child = (Xml.Tag) content;
                    if (childName.equals(child.getName())) {
                        return child.getValue().orElse("");
                    }
                }
            }
            return null;
        }
    }

    /**
     * LOW fix: Visitor that removes WildFly version properties from profile properties.
     * <p>
     * This handles the case where WildFly version properties are defined in profile-specific
     * properties sections (e.g., {@code /project/profiles/profile/properties}).
     * <p>
     * LOW fix #3: Properties are now keyed by modulePath + ":" + profileId to avoid
     * incorrect removal in multi-module builds with identical profile IDs.
     */
    private static class RemoveWildFlyPropertiesFromProfiles extends MavenIsoVisitor<ExecutionContext> {
        private final Map<String, Set<String>> profileProperties;
        private final String modulePath;
        private String currentProfileId = null;

        RemoveWildFlyPropertiesFromProfiles(Map<String, Set<String>> profileProperties, String modulePath) {
            this.profileProperties = profileProperties;
            this.modulePath = modulePath;
        }

        @Override
        public Xml.Tag visitTag(Xml.Tag tag, ExecutionContext ctx) {
            if ("profile".equals(tag.getName())) {
                currentProfileId = extractChildValue(tag, "id");
                Xml.Tag result = super.visitTag(tag, ctx);
                currentProfileId = null;
                return result;
            }

            // Check if we're in a properties section of a profile that has WildFly properties
            // LOW fix #3: Use module-aware key (modulePath + ":" + profileId)
            if (currentProfileId != null) {
                String moduleProfileKey = modulePath + ":" + currentProfileId;
                if (profileProperties.containsKey(moduleProfileKey)) {
                    Set<String> propsToRemove = profileProperties.get(moduleProfileKey);
                    if (propsToRemove.contains(tag.getName())) {
                        // Check if parent is "properties" element
                        Xml.Tag parent = getCursor().getParentTreeCursor().getValue() instanceof Xml.Tag
                            ? (Xml.Tag) getCursor().getParentTreeCursor().getValue()
                            : null;
                        if (parent != null && "properties".equals(parent.getName())) {
                            // Remove this property
                            return null;
                        }
                    }
                }
            }

            return super.visitTag(tag, ctx);
        }

        private String extractChildValue(Xml.Tag parent, String childName) {
            for (Content content : parent.getContent()) {
                if (content instanceof Xml.Tag) {
                    Xml.Tag child = (Xml.Tag) content;
                    if (childName.equals(child.getName())) {
                        return child.getValue().orElse("");
                    }
                }
            }
            return null;
        }
    }
}
