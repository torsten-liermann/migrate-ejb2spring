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
 * Exception thrown when the project configuration is invalid.
 * <p>
 * This exception is thrown during configuration validation, for example when
 * incompatible combinations of timer strategy and cluster mode are specified.
 * <p>
 * Examples of invalid configurations:
 * <ul>
 *   <li>cluster: quartz-jdbc with strategy: scheduled (requires strategy: quartz)</li>
 *   <li>cluster: shedlock with strategy: quartz (ShedLock is not compatible with Quartz)</li>
 * </ul>
 *
 * @see ProjectConfiguration#getEffectiveTimerStrategy()
 */
public class ConfigurationException extends RuntimeException {

    /**
     * Creates a new ConfigurationException with the specified message.
     *
     * @param message the detail message explaining the configuration error
     */
    public ConfigurationException(String message) {
        super(message);
    }

    /**
     * Creates a new ConfigurationException with the specified message and cause.
     *
     * @param message the detail message explaining the configuration error
     * @param cause the cause of this exception
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
