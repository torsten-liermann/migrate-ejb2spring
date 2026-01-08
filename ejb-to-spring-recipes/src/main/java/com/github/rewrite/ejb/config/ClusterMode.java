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

/**
 * Cluster coordination mode for timer migration.
 * <p>
 * Defines how scheduled tasks are coordinated in a clustered environment:
 * <ul>
 *   <li>{@code NONE} - No cluster coordination. Each node executes timers independently.
 *       Compatible with all timer strategies (scheduled, taskscheduler, quartz).</li>
 *   <li>{@code QUARTZ_JDBC} - Use Quartz JDBC JobStore for cluster coordination.
 *       Requires strategy: quartz. Provides persistent, clustered scheduling.</li>
 *   <li>{@code SHEDLOCK} - Use ShedLock for distributed locking.
 *       Compatible with scheduled and taskscheduler strategies. NOT compatible with quartz.</li>
 * </ul>
 * <p>
 * Strategy compatibility matrix:
 * <pre>
 * | cluster     | Allowed strategies           |
 * |-------------|------------------------------|
 * | none        | scheduled, taskscheduler, quartz |
 * | quartz-jdbc | quartz only                  |
 * | shedlock    | scheduled, taskscheduler     |
 * </pre>
 *
 * @see ProjectConfiguration#getEffectiveTimerStrategy()
 */
public enum ClusterMode {
    /**
     * No cluster coordination. Each node executes timers independently.
     * This is the default mode for single-node deployments.
     */
    NONE,

    /**
     * Use Quartz JDBC JobStore for cluster coordination.
     * <p>
     * Requires strategy: quartz. Generates the following properties:
     * <ul>
     *   <li>spring.quartz.job-store-type=jdbc</li>
     *   <li>spring.quartz.properties.org.quartz.jobStore.isClustered=true</li>
     *   <li>spring.quartz.properties.org.quartz.jobStore.clusterCheckinInterval=20000</li>
     * </ul>
     */
    QUARTZ_JDBC,

    /**
     * Use ShedLock for distributed locking.
     * <p>
     * Compatible with scheduled and taskscheduler strategies.
     * NOT compatible with quartz strategy.
     * <p>
     * Requires:
     * <ul>
     *   <li>ShedLock dependencies in pom.xml</li>
     *   <li>@SchedulerLock annotation on all @Scheduled methods</li>
     *   <li>DataSource configuration for ShedLock</li>
     * </ul>
     */
    SHEDLOCK
}
