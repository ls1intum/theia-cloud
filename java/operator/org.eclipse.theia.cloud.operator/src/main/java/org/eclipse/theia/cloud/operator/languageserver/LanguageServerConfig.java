/********************************************************************************
 * Copyright (C) 2025 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.languageserver;

import java.util.Map;
import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;

/**
 * Configuration for a language server type.
 * Contains all information needed to create and configure a language server container.
 */
public record LanguageServerConfig(
    /** Unique key identifying this language (e.g., "java", "rust") */
    String languageKey,
    
    /** Docker image for the language server */
    String image,
    
    /** Port the LS container listens on (standardized to 5000) */
    int containerPort,
    
    /** Environment variable name for host injection into Theia (e.g., "LS_JAVA_HOST") */
    String hostEnvVar,
    
    /** Environment variable name for port injection into Theia (e.g., "LS_JAVA_PORT") */
    String portEnvVar,
    
    /** CPU limit for the container */
    String cpuLimit,
    
    /** Memory limit for the container */
    String memoryLimit,
    
    /** CPU request for the container */
    String cpuRequest,
    
    /** Memory request for the container */
    String memoryRequest
) {
    /** Standard port all LS containers should use internally */
    public static final int STANDARD_LS_PORT = 5000;
    
    /** AppDefinition option key for language server image */
    public static final String OPTION_LS_IMAGE = "langserver-image";
    
    /** AppDefinition option key for language type */
    public static final String OPTION_LS_LANGUAGE = "langserver-language";
    
    /** AppDefinition option key for CPU limit override */
    public static final String OPTION_LS_CPU_LIMIT = "langserver-cpu-limit";
    
    /** AppDefinition option key for memory limit override */
    public static final String OPTION_LS_MEMORY_LIMIT = "langserver-memory-limit";
    
    /** AppDefinition option key for CPU request override */
    public static final String OPTION_LS_CPU_REQUEST = "langserver-cpu-request";
    
    /** AppDefinition option key for memory request override */
    public static final String OPTION_LS_MEMORY_REQUEST = "langserver-memory-request";
    
    /**
     * Creates a new config with overridden values from AppDefinition options.
     * This allows per-AppDefinition customization of resources while using
     * the registry's base configuration.
     */
    public LanguageServerConfig withOverrides(Map<String, String> options) {
        if (options == null) {
            return this;
        }
        return new LanguageServerConfig(
            this.languageKey,
            options.getOrDefault(OPTION_LS_IMAGE, this.image),
            this.containerPort,
            this.hostEnvVar,
            this.portEnvVar,
            options.getOrDefault(OPTION_LS_CPU_LIMIT, this.cpuLimit),
            options.getOrDefault(OPTION_LS_MEMORY_LIMIT, this.memoryLimit),
            options.getOrDefault(OPTION_LS_CPU_REQUEST, this.cpuRequest),
            options.getOrDefault(OPTION_LS_MEMORY_REQUEST, this.memoryRequest)
        );
    }
    
    /**
     * Creates a new config with a different image.
     */
    public LanguageServerConfig withImage(String newImage) {
        return new LanguageServerConfig(
            this.languageKey,
            newImage,
            this.containerPort,
            this.hostEnvVar,
            this.portEnvVar,
            this.cpuLimit,
            this.memoryLimit,
            this.cpuRequest,
            this.memoryRequest
        );
    }
    
    /**
     * Checks if the given AppDefinition has language server configuration.
     */
    public static boolean isConfigured(AppDefinition appDef) {
        if (appDef == null || appDef.getSpec() == null) {
            return false;
        }
        Map<String, String> options = appDef.getSpec().getOptions();
        if (options == null) {
            return false;
        }
        String lsImage = options.get(OPTION_LS_IMAGE);
        return lsImage != null && !lsImage.isBlank();
    }
    
    /**
     * Extracts the language server image from AppDefinition options.
     */
    public static Optional<String> getImageFromAppDefinition(AppDefinition appDef) {
        if (appDef == null || appDef.getSpec() == null) {
            return Optional.empty();
        }
        Map<String, String> options = appDef.getSpec().getOptions();
        if (options == null) {
            return Optional.empty();
        }
        String lsImage = options.get(OPTION_LS_IMAGE);
        if (lsImage == null || lsImage.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(lsImage);
    }
    
    /**
     * Extracts the explicit language key from AppDefinition options.
     */
    public static Optional<String> getLanguageFromAppDefinition(AppDefinition appDef) {
        if (appDef == null || appDef.getSpec() == null) {
            return Optional.empty();
        }
        Map<String, String> options = appDef.getSpec().getOptions();
        if (options == null) {
            return Optional.empty();
        }
        String language = options.get(OPTION_LS_LANGUAGE);
        if (language == null || language.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(language);
    }
}
