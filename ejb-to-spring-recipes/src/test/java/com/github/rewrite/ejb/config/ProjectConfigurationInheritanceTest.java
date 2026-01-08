/*
 * Copyright 2021 - 2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rewrite.ejb.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for WFQ-010: project.yaml inheritance for multi-module projects.
 * <p>
 * Validates that submodules without their own project.yaml inherit configuration
 * from parent modules.
 */
class ProjectConfigurationInheritanceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ProjectConfigurationLoader.clearCache();
        ProjectConfigurationLoader.clearTestInjections();
    }

    @AfterEach
    void tearDown() {
        ProjectConfigurationLoader.clearCache();
        ProjectConfigurationLoader.clearTestInjections();
    }

    @Nested
    @DisplayName("Submodule inherits from parent")
    class SubmoduleInheritance {

        @Test
        @DisplayName("Submodule without project.yaml inherits parent configuration")
        void submoduleInheritsFromParent() throws IOException {
            // Create parent project with project.yaml
            Path parentDir = tempDir.resolve("parent-project");
            Files.createDirectories(parentDir);
            Files.writeString(parentDir.resolve("pom.xml"), "<project/>");
            Files.writeString(parentDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: quartz
                        cluster: quartz-jdbc
                      remote:
                        strategy: manual
                    """);

            // Create submodule WITHOUT project.yaml
            Path submoduleDir = parentDir.resolve("submodule");
            Files.createDirectories(submoduleDir);
            Files.writeString(submoduleDir.resolve("pom.xml"), "<project/>");
            // Note: no project.yaml in submodule

            // Load with inheritance from submodule
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Should inherit parent's settings
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.QUARTZ_JDBC);
            assertThat(config.getRemoteStrategy())
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.MANUAL);
        }

        @Test
        @DisplayName("Submodule WITH own project.yaml uses its own configuration")
        void submoduleWithOwnConfigUsesOwn() throws IOException {
            // Create parent project with project.yaml
            Path parentDir = tempDir.resolve("parent-with-child-config");
            Files.createDirectories(parentDir);
            Files.writeString(parentDir.resolve("pom.xml"), "<project/>");
            Files.writeString(parentDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: quartz
                    """);

            // Create submodule WITH its own project.yaml
            Path submoduleDir = parentDir.resolve("submodule");
            Files.createDirectories(submoduleDir);
            Files.writeString(submoduleDir.resolve("pom.xml"), "<project/>");
            Files.writeString(submoduleDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: scheduled
                    """);

            // Load with inheritance from submodule
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Should use submodule's own settings
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
        }

        @Test
        @DisplayName("Deeply nested submodule inherits from grandparent")
        void deeplyNestedSubmoduleInheritsFromGrandparent() throws IOException {
            // Create root project with project.yaml
            Path rootDir = tempDir.resolve("root-project");
            Files.createDirectories(rootDir);
            Files.writeString(rootDir.resolve("pom.xml"), "<project/>");
            Files.writeString(rootDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: taskscheduler
                      jsf:
                        runtime: manual
                    jaxrs:
                      strategy: migrate-to-spring-mvc
                    """);

            // Create intermediate module WITHOUT project.yaml but with pom.xml
            // (standard Maven multi-module structure)
            Path modulesDir = rootDir.resolve("modules");
            Files.createDirectories(modulesDir);
            Files.writeString(modulesDir.resolve("pom.xml"), "<project/>");

            Path coreDir = modulesDir.resolve("core");
            Files.createDirectories(coreDir);
            Files.writeString(coreDir.resolve("pom.xml"), "<project/>");

            // Create grandchild module WITHOUT project.yaml
            Path grandchildDir = coreDir.resolve("api");
            Files.createDirectories(grandchildDir);
            Files.writeString(grandchildDir.resolve("pom.xml"), "<project/>");

            // Load with inheritance from grandchild
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(grandchildDir);

            // Should inherit root's settings
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.TASKSCHEDULER);
            assertThat(config.getJsfStrategy())
                    .isEqualTo(ProjectConfiguration.JsfStrategy.MANUAL);
            assertThat(config.getJaxRsStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsStrategy.MIGRATE_TO_SPRING_MVC);
        }
    }

    @Nested
    @DisplayName("Inheritance through intermediate directories")
    class IntermediateDirectories {

        @Test
        @DisplayName("WFQ-010: Inheritance continues through intermediate directories without build files")
        void inheritanceContinuesThroughIntermediateDirectoriesWithoutBuildFiles() throws IOException {
            // Create root project with project.yaml (has pom.xml)
            Path rootDir = tempDir.resolve("wfq010-root");
            Files.createDirectories(rootDir);
            Files.writeString(rootDir.resolve("pom.xml"), "<project/>");
            Files.writeString(rootDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: quartz
                        cluster: quartz-jdbc
                    """);

            // Create intermediate directory WITHOUT build file (no pom.xml, no build.gradle)
            // This is the WFQ-010 bug scenario - traversal used to stop here
            Path modulesDir = rootDir.resolve("modules");
            Files.createDirectories(modulesDir);
            // Note: NO pom.xml or build.gradle in modules/ directory

            // Create submodule with pom.xml but no project.yaml
            Path serviceADir = modulesDir.resolve("service-a");
            Files.createDirectories(serviceADir);
            Files.writeString(serviceADir.resolve("pom.xml"), "<project/>");
            // Note: no project.yaml - should inherit from root

            // Load with inheritance from service-a
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(serviceADir);

            // Should inherit from root's project.yaml despite modules/ having no pom.xml
            assertThat(config.getTimerStrategy())
                    .as("Timer strategy should be inherited from root through intermediate dir without build file")
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
            assertThat(config.getClusterMode())
                    .as("Cluster mode should be inherited from root")
                    .isEqualTo(ClusterMode.QUARTZ_JDBC);
        }

        @Test
        @DisplayName("WFQ-010: Multiple intermediate directories without build files")
        void multipleIntermediateDirectoriesWithoutBuildFiles() throws IOException {
            // Create root project with project.yaml
            Path rootDir = tempDir.resolve("wfq010-deep");
            Files.createDirectories(rootDir);
            Files.writeString(rootDir.resolve("pom.xml"), "<project/>");
            Files.writeString(rootDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: taskscheduler
                      remote:
                        strategy: manual
                    """);

            // Create multiple intermediate directories WITHOUT build files
            Path level1 = rootDir.resolve("category");
            Path level2 = level1.resolve("subcategory");
            Path level3 = level2.resolve("group");
            Files.createDirectories(level3);
            // None of these have pom.xml or build.gradle

            // Create actual module at deepest level
            Path moduleDir = level3.resolve("my-module");
            Files.createDirectories(moduleDir);
            Files.writeString(moduleDir.resolve("pom.xml"), "<project/>");

            // Load with inheritance
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(moduleDir);

            // Should still find root's project.yaml through all intermediate directories
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.TASKSCHEDULER);
            assertThat(config.getRemoteStrategy())
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.MANUAL);
        }

        @Test
        @DisplayName("WFQ-010: Stops at .git directory (repository boundary)")
        void stopsAtGitDirectory() throws IOException {
            // Create a directory structure simulating repository root with .git
            Path repoRoot = tempDir.resolve("repo-with-git");
            Files.createDirectories(repoRoot);
            Files.createDirectories(repoRoot.resolve(".git")); // Mark as repo root
            Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
            Files.writeString(repoRoot.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: quartz
                    """);

            // Create submodule
            Path submoduleDir = repoRoot.resolve("submodule");
            Files.createDirectories(submoduleDir);
            Files.writeString(submoduleDir.resolve("pom.xml"), "<project/>");

            // Load with inheritance
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Should find the config at repo root
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
        }

        @Test
        @DisplayName("WFQ-010: Does not traverse beyond .git directory")
        void doesNotTraverseBeyondGitDirectory() throws IOException {
            // Create parent directory with project.yaml (outside repo)
            Path outsideRepo = tempDir.resolve("outside-repo");
            Files.createDirectories(outsideRepo);
            Files.writeString(outsideRepo.resolve("pom.xml"), "<project/>");
            Files.writeString(outsideRepo.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: quartz
                    """);

            // Create repository root with .git (child of outside-repo)
            Path repoRoot = outsideRepo.resolve("my-repo");
            Files.createDirectories(repoRoot);
            Files.createDirectories(repoRoot.resolve(".git")); // Mark as repo root
            Files.writeString(repoRoot.resolve("pom.xml"), "<project/>");
            // No project.yaml in repo root

            // Create submodule
            Path submoduleDir = repoRoot.resolve("submodule");
            Files.createDirectories(submoduleDir);
            Files.writeString(submoduleDir.resolve("pom.xml"), "<project/>");

            // Load with inheritance
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Should NOT inherit from outside-repo, should get defaults
            // because .git marks repo boundary
            assertThat(config.getTimerStrategy())
                    .as("Should get defaults because traversal stops at .git")
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
        }
    }

    @Nested
    @DisplayName("Inheritance stops at project boundaries")
    class InheritanceBoundaries {

        @Test
        @DisplayName("Submodule without project.yaml reaches filesystem root gets defaults")
        void submoduleReachingFilesystemRootGetsDefaults() throws IOException {
            // Create actual project inside tempDir (tempDir has no project.yaml)
            Path projectDir = tempDir.resolve("isolated-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            // Note: no project.yaml anywhere up to filesystem root

            // Load with inheritance
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(projectDir);

            // Should get defaults since no project.yaml found anywhere
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED); // default
        }

        @Test
        @DisplayName("Submodule without project.yaml and no parent config gets defaults")
        void submoduleWithoutParentConfigGetsDefaults() throws IOException {
            // Create parent project WITHOUT project.yaml
            Path parentDir = tempDir.resolve("parent-no-config");
            Files.createDirectories(parentDir);
            Files.writeString(parentDir.resolve("pom.xml"), "<project/>");
            // Note: no project.yaml in parent either

            // Create submodule WITHOUT project.yaml
            Path submoduleDir = parentDir.resolve("submodule");
            Files.createDirectories(submoduleDir);
            Files.writeString(submoduleDir.resolve("pom.xml"), "<project/>");

            // Load with inheritance from submodule
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Should get defaults
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.NONE);
        }
    }

    @Nested
    @DisplayName("Caching behavior")
    class CachingBehavior {

        @Test
        @DisplayName("Inherited configuration is cached for submodule path")
        void inheritedConfigIsCached() throws IOException {
            // Create parent project with project.yaml
            Path parentDir = tempDir.resolve("parent-cached");
            Files.createDirectories(parentDir);
            Files.writeString(parentDir.resolve("pom.xml"), "<project/>");
            Files.writeString(parentDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: quartz
                    """);

            // Create submodule WITHOUT project.yaml
            Path submoduleDir = parentDir.resolve("submodule");
            Files.createDirectories(submoduleDir);
            Files.writeString(submoduleDir.resolve("pom.xml"), "<project/>");

            // Load twice
            ProjectConfiguration config1 = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);
            ProjectConfiguration config2 = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Should return same instance (cached)
            assertThat(config1).isSameAs(config2);
        }

        @Test
        @DisplayName("clearCache also clears inheritance cache")
        void clearCacheIncludesInheritance() throws IOException {
            // Create parent project with project.yaml
            Path parentDir = tempDir.resolve("parent-clear-cache");
            Files.createDirectories(parentDir);
            Files.writeString(parentDir.resolve("pom.xml"), "<project/>");
            Files.writeString(parentDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: quartz
                    """);

            // Create submodule
            Path submoduleDir = parentDir.resolve("submodule");
            Files.createDirectories(submoduleDir);
            Files.writeString(submoduleDir.resolve("pom.xml"), "<project/>");

            // Load once
            ProjectConfiguration config1 = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Clear cache
            ProjectConfigurationLoader.clearCache();

            // Modify parent config
            Files.writeString(parentDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: scheduled
                    """);

            // Load again
            ProjectConfiguration config2 = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Should be different (cache was cleared)
            assertThat(config1.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
            assertThat(config2.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
        }
    }

    @Nested
    @DisplayName("Gradle project support")
    class GradleProjectSupport {

        @Test
        @DisplayName("Inheritance works with build.gradle parent")
        void inheritanceWorksWithGradleParent() throws IOException {
            // Create Gradle parent project with project.yaml
            Path parentDir = tempDir.resolve("gradle-parent");
            Files.createDirectories(parentDir);
            Files.writeString(parentDir.resolve("build.gradle"), "// Gradle build");
            Files.writeString(parentDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: quartz
                    """);

            // Create submodule with pom.xml
            Path submoduleDir = parentDir.resolve("submodule");
            Files.createDirectories(submoduleDir);
            Files.writeString(submoduleDir.resolve("pom.xml"), "<project/>");

            // Load with inheritance
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Should inherit from Gradle parent
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
        }

        @Test
        @DisplayName("Inheritance works with build.gradle.kts parent")
        void inheritanceWorksWithGradleKtsParent() throws IOException {
            // Create Kotlin DSL Gradle parent project with project.yaml
            Path parentDir = tempDir.resolve("gradle-kts-parent");
            Files.createDirectories(parentDir);
            Files.writeString(parentDir.resolve("build.gradle.kts"), "// Gradle Kotlin DSL build");
            Files.writeString(parentDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: taskscheduler
                    """);

            // Create submodule with build.gradle
            Path submoduleDir = parentDir.resolve("submodule");
            Files.createDirectories(submoduleDir);
            Files.writeString(submoduleDir.resolve("build.gradle"), "// Gradle build");

            // Load with inheritance
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Should inherit from Kotlin DSL Gradle parent
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.TASKSCHEDULER);
        }
    }

    @Nested
    @DisplayName("Null and edge cases")
    class EdgeCases {

        @Test
        @DisplayName("loadWithInheritance with null returns Maven defaults")
        void loadWithInheritanceNullReturnDefaults() {
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(null);

            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.NONE);
        }

        @Test
        @DisplayName("Test injections work with loadWithInheritance")
        void testInjectionsWorkWithInheritance() throws IOException {
            // Create a directory
            Path projectDir = tempDir.resolve("injected-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");

            // Inject a config
            ProjectConfiguration injected = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.QUARTZ,
                    ClusterMode.QUARTZ_JDBC
            );
            ProjectConfigurationLoader.injectForTest(projectDir, injected);

            // Load with inheritance should return injected config
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(projectDir);

            assertThat(config).isSameAs(injected);
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
        }
    }

    @Nested
    @DisplayName("Complete configuration inheritance")
    class CompleteConfigurationInheritance {

        @Test
        @DisplayName("All configuration fields are inherited from parent")
        void allFieldsAreInherited() throws IOException {
            // Create parent project with comprehensive project.yaml
            Path parentDir = tempDir.resolve("comprehensive-parent");
            Files.createDirectories(parentDir);
            Files.writeString(parentDir.resolve("pom.xml"), "<project/>");
            Files.writeString(parentDir.resolve("project.yaml"), """
                    sources:
                      main:
                        - src/main/java
                        - src/main/kotlin
                      test:
                        - src/test/java
                        - src/integrationTest/java

                    migration:
                      timer:
                        strategy: quartz
                        cluster: quartz-jdbc
                      remote:
                        strategy: manual
                      inject:
                        strategy: migrate-to-spring
                      jsf:
                        runtime: manual
                      build:
                        keepWildFlyPlugins: true
                        bootPluginInProfiles: true
                      jms:
                        provider: artemis

                    jaxrs:
                      strategy: migrate-to-spring-mvc
                      client:
                        strategy: manual
                        provider: resteasy
                        providerVersion: "6.2.0"

                    jaxws:
                      provider: cxf
                      basePath: /ws
                    """);

            // Create submodule WITHOUT project.yaml
            Path submoduleDir = parentDir.resolve("submodule");
            Files.createDirectories(submoduleDir);
            Files.writeString(submoduleDir.resolve("pom.xml"), "<project/>");

            // Load with inheritance
            ProjectConfiguration config = ProjectConfigurationLoader.loadWithInheritance(submoduleDir);

            // Verify all fields are inherited
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.QUARTZ_JDBC);
            assertThat(config.getRemoteStrategy())
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.MANUAL);
            assertThat(config.getInjectStrategy())
                    .isEqualTo(ProjectConfiguration.InjectStrategy.MIGRATE_TO_SPRING);
            assertThat(config.getJsfStrategy())
                    .isEqualTo(ProjectConfiguration.JsfStrategy.MANUAL);
            assertThat(config.isKeepWildFlyPlugins()).isTrue();
            assertThat(config.isBootPluginInProfiles()).isTrue();
            assertThat(config.getJmsProvider())
                    .isEqualTo(ProjectConfiguration.JmsProvider.ARTEMIS);
            assertThat(config.getJaxRsStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsStrategy.MIGRATE_TO_SPRING_MVC);
            assertThat(config.getJaxRsClientStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsClientStrategy.MANUAL);
            assertThat(config.getJaxRsClientProvider())
                    .isEqualTo("resteasy");
            assertThat(config.getJaxRsClientProviderVersion())
                    .isEqualTo("6.2.0");
            assertThat(config.getJaxwsProvider())
                    .isEqualTo("cxf");
            assertThat(config.getJaxwsBasePath())
                    .isEqualTo("/ws");

            // Verify source roots are also inherited
            assertThat(config.getMainSourceRoots())
                    .containsExactly("src/main/java", "src/main/kotlin");
            assertThat(config.getTestSourceRoots())
                    .containsExactly("src/test/java", "src/integrationTest/java");
        }
    }
}
