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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for P2.8: cluster coordination YAML switch.
 * <p>
 * Validates the strategy + cluster interaction rules:
 * <table border="1">
 *   <tr><th>cluster</th><th>Allowed strategy</th><th>Action on conflict</th></tr>
 *   <tr><td>none</td><td>scheduled, taskscheduler, quartz</td><td>-</td></tr>
 *   <tr><td>quartz-jdbc</td><td>quartz only</td><td>ConfigurationException</td></tr>
 *   <tr><td>shedlock</td><td>scheduled, taskscheduler</td><td>ConfigurationException for quartz</td></tr>
 * </table>
 */
class ProjectConfigurationClusterTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ProjectConfigurationLoader.clearCache();
    }

    @Nested
    @DisplayName("ClusterMode NONE - all strategies allowed")
    class ClusterModeNone {

        @Test
        @DisplayName("cluster: none + strategy: scheduled -> OK")
        void clusterNoneWithScheduled() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.SCHEDULED,
                    ClusterMode.NONE
            );

            assertThat(config.getEffectiveTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
            assertThat(config.isClusterEnabled()).isFalse();
        }

        @Test
        @DisplayName("cluster: none + strategy: taskscheduler -> OK")
        void clusterNoneWithTaskScheduler() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.TASKSCHEDULER,
                    ClusterMode.NONE
            );

            assertThat(config.getEffectiveTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.TASKSCHEDULER);
        }

        @Test
        @DisplayName("cluster: none + strategy: quartz -> OK")
        void clusterNoneWithQuartz() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.QUARTZ,
                    ClusterMode.NONE
            );

            assertThat(config.getEffectiveTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
        }
    }

    @Nested
    @DisplayName("ClusterMode QUARTZ_JDBC - only quartz allowed")
    class ClusterModeQuartzJdbc {

        @Test
        @DisplayName("cluster: quartz-jdbc + strategy: quartz -> OK with cluster properties")
        void quartzJdbcWithQuartz() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.QUARTZ,
                    ClusterMode.QUARTZ_JDBC
            );

            assertThat(config.getEffectiveTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
            assertThat(config.isClusterEnabled()).isTrue();
            assertThat(config.isQuartzJdbcCluster()).isTrue();
            assertThat(config.isShedLockCluster()).isFalse();
        }

        @Test
        @DisplayName("cluster: quartz-jdbc + strategy: scheduled -> ConfigurationException")
        void quartzJdbcWithScheduled_throwsException() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.SCHEDULED,
                    ClusterMode.QUARTZ_JDBC
            );

            assertThatThrownBy(config::getEffectiveTimerStrategy)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("quartz-jdbc")
                    .hasMessageContaining("strategy: quartz");
        }

        @Test
        @DisplayName("cluster: quartz-jdbc + strategy: taskscheduler -> ConfigurationException")
        void quartzJdbcWithTaskScheduler_throwsException() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.TASKSCHEDULER,
                    ClusterMode.QUARTZ_JDBC
            );

            assertThatThrownBy(config::getEffectiveTimerStrategy)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("quartz-jdbc")
                    .hasMessageContaining("strategy: quartz");
        }
    }

    @Nested
    @DisplayName("ClusterMode SHEDLOCK - scheduled and taskscheduler allowed")
    class ClusterModeShedLock {

        @Test
        @DisplayName("cluster: shedlock + strategy: scheduled -> OK with @SchedulerLock")
        void shedLockWithScheduled() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.SCHEDULED,
                    ClusterMode.SHEDLOCK
            );

            assertThat(config.getEffectiveTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
            assertThat(config.isClusterEnabled()).isTrue();
            assertThat(config.isShedLockCluster()).isTrue();
            assertThat(config.isQuartzJdbcCluster()).isFalse();
        }

        @Test
        @DisplayName("cluster: shedlock + strategy: taskscheduler -> OK")
        void shedLockWithTaskScheduler() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.TASKSCHEDULER,
                    ClusterMode.SHEDLOCK
            );

            assertThat(config.getEffectiveTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.TASKSCHEDULER);
            assertThat(config.isShedLockCluster()).isTrue();
        }

        @Test
        @DisplayName("cluster: shedlock + strategy: quartz -> ConfigurationException")
        void shedLockWithQuartz_throwsException() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.QUARTZ,
                    ClusterMode.SHEDLOCK
            );

            assertThatThrownBy(config::getEffectiveTimerStrategy)
                    .isInstanceOf(ConfigurationException.class)
                    .hasMessageContaining("shedlock")
                    .hasMessageContaining("not compatible")
                    .hasMessageContaining("quartz");
        }
    }

    @Nested
    @DisplayName("YAML Parsing Tests")
    class YamlParsingTests {

        @Test
        @DisplayName("Parse cluster: quartz-jdbc from YAML")
        void parseQuartzJdbcFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("quartz-jdbc-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: quartz
                        cluster: quartz-jdbc
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.QUARTZ_JDBC);
            assertThat(config.getEffectiveTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.QUARTZ);
        }

        @Test
        @DisplayName("Parse cluster: shedlock from YAML")
        void parseShedLockFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("shedlock-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: scheduled
                        cluster: shedlock
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.SHEDLOCK);
            assertThat(config.getEffectiveTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
        }

        @Test
        @DisplayName("Parse cluster: none from YAML")
        void parseClusterNoneFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("no-cluster-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: taskscheduler
                        cluster: none
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.TASKSCHEDULER);
            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.NONE);
        }

        @Test
        @DisplayName("Default cluster mode is NONE when not specified")
        void defaultClusterModeIsNone() throws IOException {
            Path projectDir = tempDir.resolve("default-cluster-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: quartz
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.NONE);
        }

        @Test
        @DisplayName("Parse invalid cluster value falls back to default")
        void parseInvalidClusterFallsBackToDefault() throws IOException {
            Path projectDir = tempDir.resolve("invalid-cluster-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: scheduled
                        cluster: invalid-value
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            // Invalid value should fall back to default (NONE)
            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.NONE);
        }

        @Test
        @DisplayName("Parse YAML with conflicting strategy and cluster throws on getEffectiveTimerStrategy")
        void parseYamlWithConflict_throwsOnGetEffective() throws IOException {
            Path projectDir = tempDir.resolve("conflict-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: scheduled
                        cluster: quartz-jdbc
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            // Loading succeeds, but validation fails
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.QUARTZ_JDBC);

            assertThatThrownBy(config::getEffectiveTimerStrategy)
                    .isInstanceOf(ConfigurationException.class);
        }

        @Test
        @DisplayName("Parse jaxrs.strategy migrate-to-spring-mvc from YAML")
        void parseJaxRsStrategyFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("jaxrs-strategy-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    jaxrs:
                      strategy: migrate-to-spring-mvc
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getJaxRsStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsStrategy.MIGRATE_TO_SPRING_MVC);
            assertThat(config.getJaxRsClientStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsClientStrategy.KEEP_JAXRS);
            assertThat(config.getJaxRsClientProvider())
                    .isEqualTo("jersey");
            assertThat(config.getJaxRsClientProviderVersion())
                    .isNull();
        }

        @Test
        @DisplayName("Parse jaxrs.client strategy/provider/version from YAML")
        void parseJaxRsClientConfigFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("jaxrs-client-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    jaxrs:
                      client:
                        strategy: manual
                        provider: resteasy
                        providerVersion: "6.2.7.Final"
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getJaxRsStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS);
            assertThat(config.getJaxRsClientStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsClientStrategy.MANUAL);
            assertThat(config.getJaxRsClientProvider())
                    .isEqualTo("resteasy");
            assertThat(config.getJaxRsClientProviderVersion())
                    .isEqualTo("6.2.7.Final");
        }

        @Test
        @DisplayName("Parse jms.provider from YAML")
        void parseJmsProviderFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("jms-provider-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    jms:
                      provider: artemis
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getJmsProvider())
                    .isEqualTo(ProjectConfiguration.JmsProvider.ARTEMIS);
        }

        @Test
        @DisplayName("Invalid jaxrs strategies fall back to defaults")
        void parseInvalidJaxRsStrategiesFallBackToDefaults() throws IOException {
            Path projectDir = tempDir.resolve("invalid-jaxrs-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    jaxrs:
                      strategy: invalid-value
                      client:
                        strategy: not-a-strategy
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getJaxRsStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS);
            assertThat(config.getJaxRsClientStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsClientStrategy.KEEP_JAXRS);
        }
    }

    @Nested
    @DisplayName("Helper Methods")
    class HelperMethodsTests {

        @Test
        @DisplayName("isClusterEnabled returns false for NONE")
        void isClusterEnabledFalseForNone() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.SCHEDULED,
                    ClusterMode.NONE
            );

            assertThat(config.isClusterEnabled()).isFalse();
        }

        @Test
        @DisplayName("isClusterEnabled returns true for QUARTZ_JDBC")
        void isClusterEnabledTrueForQuartzJdbc() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.QUARTZ,
                    ClusterMode.QUARTZ_JDBC
            );

            assertThat(config.isClusterEnabled()).isTrue();
        }

        @Test
        @DisplayName("isClusterEnabled returns true for SHEDLOCK")
        void isClusterEnabledTrueForShedLock() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.SCHEDULED,
                    ClusterMode.SHEDLOCK
            );

            assertThat(config.isClusterEnabled()).isTrue();
        }

        @Test
        @DisplayName("toString includes clusterMode")
        void toStringIncludesClusterMode() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.QUARTZ,
                    ClusterMode.QUARTZ_JDBC
            );

            assertThat(config.toString())
                    .contains("clusterMode=QUARTZ_JDBC")
                    .contains("timerStrategy=QUARTZ");
        }
    }

    @Nested
    @DisplayName("Default Configuration")
    class DefaultConfigurationTests {

        @Test
        @DisplayName("Maven defaults use NONE cluster mode")
        void mavenDefaultsUseNoneClusterMode() {
            ProjectConfiguration config = ProjectConfiguration.mavenDefaults();

            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.NONE);
            assertThat(config.getTimerStrategy())
                    .isEqualTo(ProjectConfiguration.TimerStrategy.SCHEDULED);
            assertThat(config.getJaxRsStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsStrategy.KEEP_JAXRS);
            assertThat(config.getJaxRsClientStrategy())
                    .isEqualTo(ProjectConfiguration.JaxRsClientStrategy.KEEP_JAXRS);
            assertThat(config.getJaxRsClientProvider())
                    .isEqualTo("jersey");
            assertThat(config.getJaxRsClientProviderVersion())
                    .isNull();
            assertThat(config.getRemoteStrategy())
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.REST);
            assertThat(config.getJsfStrategy())
                    .isEqualTo(ProjectConfiguration.JsfStrategy.JOINFACES);
            assertThat(config.isClusterEnabled()).isFalse();
        }

        @Test
        @DisplayName("Constructor with null clusterMode defaults to NONE")
        void constructorWithNullClusterModeDefaultsToNone() {
            ProjectConfiguration config = new ProjectConfiguration(
                    null, null, null, null,
                    ProjectConfiguration.TimerStrategy.QUARTZ,
                    null
            );

            assertThat(config.getClusterMode())
                    .isEqualTo(ClusterMode.NONE);
        }
    }

    @Nested
    @DisplayName("Remote Strategy Tests")
    class RemoteStrategyTests {

        @Test
        @DisplayName("Default remote strategy is REST")
        void defaultRemoteStrategyIsRest() {
            ProjectConfiguration config = ProjectConfiguration.mavenDefaults();

            assertThat(config.getRemoteStrategy())
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.REST);
            assertThat(config.isRestRemoteStrategy()).isTrue();
        }

        @Test
        @DisplayName("Parse migration.remote.strategy: rest from YAML")
        void parseRemoteStrategyRestFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("remote-rest-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      remote:
                        strategy: rest
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getRemoteStrategy())
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.REST);
            assertThat(config.isRestRemoteStrategy()).isTrue();
        }

        @Test
        @DisplayName("Parse migration.remote.strategy: manual from YAML")
        void parseRemoteStrategyManualFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("remote-manual-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      remote:
                        strategy: manual
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getRemoteStrategy())
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.MANUAL);
            assertThat(config.isRestRemoteStrategy()).isFalse();
        }

        @Test
        @DisplayName("Parse migration.remote.strategy: MANUAL (uppercase) from YAML")
        void parseRemoteStrategyUppercaseFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("remote-uppercase-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      remote:
                        strategy: MANUAL
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getRemoteStrategy())
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.MANUAL);
        }

        @Test
        @DisplayName("Invalid remote strategy falls back to default REST")
        void parseInvalidRemoteStrategyFallsBackToDefault() throws IOException {
            Path projectDir = tempDir.resolve("remote-invalid-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      remote:
                        strategy: invalid-value
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            // Invalid value should fall back to default (REST)
            assertThat(config.getRemoteStrategy())
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.REST);
        }

        @Test
        @DisplayName("Missing remote section uses default REST")
        void missingRemoteSectionUsesDefault() throws IOException {
            Path projectDir = tempDir.resolve("remote-missing-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: scheduled
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getRemoteStrategy())
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.REST);
        }

        @Test
        @DisplayName("toString includes remoteStrategy")
        void toStringIncludesRemoteStrategy() {
            ProjectConfiguration config = ProjectConfiguration.mavenDefaults();

            assertThat(config.toString())
                    .contains("remoteStrategy=REST");
        }

        @Test
        @DisplayName("RemoteStrategy.fromString handles null")
        void remoteStrategyFromStringHandlesNull() {
            assertThat(ProjectConfiguration.RemoteStrategy.fromString(null)).isNull();
        }

        @Test
        @DisplayName("RemoteStrategy.fromString handles invalid values")
        void remoteStrategyFromStringHandlesInvalidValues() {
            assertThat(ProjectConfiguration.RemoteStrategy.fromString("invalid")).isNull();
            assertThat(ProjectConfiguration.RemoteStrategy.fromString("")).isNull();
            assertThat(ProjectConfiguration.RemoteStrategy.fromString("  ")).isNull();
        }

        @Test
        @DisplayName("RemoteStrategy.fromString handles valid values")
        void remoteStrategyFromStringHandlesValidValues() {
            assertThat(ProjectConfiguration.RemoteStrategy.fromString("rest"))
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.REST);
            assertThat(ProjectConfiguration.RemoteStrategy.fromString("REST"))
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.REST);
            assertThat(ProjectConfiguration.RemoteStrategy.fromString("manual"))
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.MANUAL);
            assertThat(ProjectConfiguration.RemoteStrategy.fromString("MANUAL"))
                    .isEqualTo(ProjectConfiguration.RemoteStrategy.MANUAL);
        }
    }

    @Nested
    @DisplayName("JSF Strategy Tests")
    class JsfStrategyTests {

        @Test
        @DisplayName("Default JSF strategy is JOINFACES")
        void defaultJsfStrategyIsJoinfaces() {
            ProjectConfiguration config = ProjectConfiguration.mavenDefaults();

            assertThat(config.getJsfStrategy())
                    .isEqualTo(ProjectConfiguration.JsfStrategy.JOINFACES);
            assertThat(config.isJoinFacesStrategy()).isTrue();
            assertThat(config.isManualJsfStrategy()).isFalse();
        }

        @Test
        @DisplayName("Parse migration.jsf.runtime: joinfaces from YAML")
        void parseJsfStrategyJoinfacesFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("jsf-joinfaces-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      jsf:
                        runtime: joinfaces
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getJsfStrategy())
                    .isEqualTo(ProjectConfiguration.JsfStrategy.JOINFACES);
            assertThat(config.isJoinFacesStrategy()).isTrue();
        }

        @Test
        @DisplayName("Parse migration.jsf.runtime: manual from YAML")
        void parseJsfStrategyManualFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("jsf-manual-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      jsf:
                        runtime: manual
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getJsfStrategy())
                    .isEqualTo(ProjectConfiguration.JsfStrategy.MANUAL);
            assertThat(config.isManualJsfStrategy()).isTrue();
            assertThat(config.isJoinFacesStrategy()).isFalse();
        }

        @Test
        @DisplayName("Parse migration.jsf.runtime: MANUAL (uppercase) from YAML")
        void parseJsfStrategyUppercaseFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("jsf-uppercase-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      jsf:
                        runtime: MANUAL
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getJsfStrategy())
                    .isEqualTo(ProjectConfiguration.JsfStrategy.MANUAL);
        }

        @Test
        @DisplayName("Invalid JSF strategy falls back to default JOINFACES")
        void parseInvalidJsfStrategyFallsBackToDefault() throws IOException {
            Path projectDir = tempDir.resolve("jsf-invalid-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      jsf:
                        runtime: invalid-value
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            // Invalid value should fall back to default (JOINFACES)
            assertThat(config.getJsfStrategy())
                    .isEqualTo(ProjectConfiguration.JsfStrategy.JOINFACES);
        }

        @Test
        @DisplayName("Missing JSF section uses default JOINFACES")
        void missingJsfSectionUsesDefault() throws IOException {
            Path projectDir = tempDir.resolve("jsf-missing-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: scheduled
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.getJsfStrategy())
                    .isEqualTo(ProjectConfiguration.JsfStrategy.JOINFACES);
        }

        @Test
        @DisplayName("toString includes jsfStrategy")
        void toStringIncludesJsfStrategy() {
            ProjectConfiguration config = ProjectConfiguration.mavenDefaults();

            assertThat(config.toString())
                    .contains("jsfStrategy=JOINFACES");
        }

        @Test
        @DisplayName("JsfStrategy.fromString handles null")
        void jsfStrategyFromStringHandlesNull() {
            assertThat(ProjectConfiguration.JsfStrategy.fromString(null)).isNull();
        }

        @Test
        @DisplayName("JsfStrategy.fromString handles invalid values")
        void jsfStrategyFromStringHandlesInvalidValues() {
            assertThat(ProjectConfiguration.JsfStrategy.fromString("invalid")).isNull();
            assertThat(ProjectConfiguration.JsfStrategy.fromString("")).isNull();
            assertThat(ProjectConfiguration.JsfStrategy.fromString("  ")).isNull();
        }

        @Test
        @DisplayName("JsfStrategy.fromString handles valid values")
        void jsfStrategyFromStringHandlesValidValues() {
            assertThat(ProjectConfiguration.JsfStrategy.fromString("joinfaces"))
                    .isEqualTo(ProjectConfiguration.JsfStrategy.JOINFACES);
            assertThat(ProjectConfiguration.JsfStrategy.fromString("JOINFACES"))
                    .isEqualTo(ProjectConfiguration.JsfStrategy.JOINFACES);
            assertThat(ProjectConfiguration.JsfStrategy.fromString("manual"))
                    .isEqualTo(ProjectConfiguration.JsfStrategy.MANUAL);
            assertThat(ProjectConfiguration.JsfStrategy.fromString("MANUAL"))
                    .isEqualTo(ProjectConfiguration.JsfStrategy.MANUAL);
        }
    }

    @Nested
    @DisplayName("KeepWildFlyPlugins Opt-Out Tests")
    class KeepWildFlyPluginsTests {

        @Test
        @DisplayName("Default keepWildFlyPlugins is false")
        void defaultKeepWildFlyPluginsIsFalse() {
            ProjectConfiguration config = ProjectConfiguration.mavenDefaults();

            assertThat(config.isKeepWildFlyPlugins()).isFalse();
        }

        @Test
        @DisplayName("Parse migration.build.keepWildFlyPlugins: true from YAML")
        void parseKeepWildFlyPluginsTrueFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("keep-wildfly-true-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      build:
                        keepWildFlyPlugins: true
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.isKeepWildFlyPlugins()).isTrue();
        }

        @Test
        @DisplayName("Parse migration.build.keepWildFlyPlugins: false from YAML")
        void parseKeepWildFlyPluginsFalseFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("keep-wildfly-false-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      build:
                        keepWildFlyPlugins: false
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.isKeepWildFlyPlugins()).isFalse();
        }

        @Test
        @DisplayName("Missing build section uses default false")
        void missingBuildSectionUsesDefault() throws IOException {
            Path projectDir = tempDir.resolve("keep-wildfly-missing-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      timer:
                        strategy: scheduled
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.isKeepWildFlyPlugins()).isFalse();
        }

        @Test
        @DisplayName("toString includes keepWildFlyPlugins")
        void toStringIncludesKeepWildFlyPlugins() {
            ProjectConfiguration config = ProjectConfiguration.mavenDefaults();

            assertThat(config.toString())
                    .contains("keepWildFlyPlugins=false");
        }
    }

    @Nested
    @DisplayName("BootPluginInProfiles Opt-In Tests")
    class BootPluginInProfilesTests {

        @Test
        @DisplayName("Default bootPluginInProfiles is false")
        void defaultBootPluginInProfilesIsFalse() {
            ProjectConfiguration config = ProjectConfiguration.mavenDefaults();

            assertThat(config.isBootPluginInProfiles()).isFalse();
        }

        @Test
        @DisplayName("Parse migration.build.bootPluginInProfiles: true from YAML")
        void parseBootPluginInProfilesTrueFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("boot-plugin-profiles-true-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      build:
                        bootPluginInProfiles: true
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.isBootPluginInProfiles()).isTrue();
        }

        @Test
        @DisplayName("Parse migration.build.bootPluginInProfiles: false from YAML")
        void parseBootPluginInProfilesFalseFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("boot-plugin-profiles-false-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      build:
                        bootPluginInProfiles: false
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.isBootPluginInProfiles()).isFalse();
        }

        @Test
        @DisplayName("Parse both keepWildFlyPlugins and bootPluginInProfiles from YAML")
        void parseBothBuildFlagsFromYaml() throws IOException {
            Path projectDir = tempDir.resolve("both-build-flags-project");
            Files.createDirectories(projectDir);
            Files.writeString(projectDir.resolve("pom.xml"), "<project/>");
            Files.writeString(projectDir.resolve("project.yaml"), """
                    migration:
                      build:
                        keepWildFlyPlugins: true
                        bootPluginInProfiles: true
                    """);

            ProjectConfiguration config = ProjectConfigurationLoader.load(projectDir);

            assertThat(config.isKeepWildFlyPlugins()).isTrue();
            assertThat(config.isBootPluginInProfiles()).isTrue();
        }

        @Test
        @DisplayName("toString includes bootPluginInProfiles")
        void toStringIncludesBootPluginInProfiles() {
            ProjectConfiguration config = ProjectConfiguration.mavenDefaults();

            assertThat(config.toString())
                    .contains("bootPluginInProfiles=false");
        }
    }
}
