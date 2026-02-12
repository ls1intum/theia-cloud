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
import java.util.concurrent.ConcurrentHashMap;

import com.google.inject.Singleton;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;

@Singleton
public class LanguageServerRegistry {

    private final Map<String, LanguageServerConfig> configs = new ConcurrentHashMap<>();

    public LanguageServerRegistry() {
        registerBuiltInLanguages();
    }

    private void registerBuiltInLanguages() {
        register(new LanguageServerConfig(
            "java",
            "ghcr.io/ls1intum/theia/langserver-java:latest",
            LanguageServerConfig.STANDARD_LS_PORT,
            "LS_JAVA_HOST",
            "LS_JAVA_PORT",
            "500m",
            "1Gi",
            "100m",
            "256Mi"
        ));

        register(new LanguageServerConfig(
            "rust",
            "ghcr.io/ls1intum/theia/langserver-rust:latest",
            LanguageServerConfig.STANDARD_LS_PORT,
            "LS_RUST_HOST",
            "LS_RUST_PORT",
            "500m",
            "1Gi",
            "100m",
            "256Mi"
        ));

        register(new LanguageServerConfig(
            "python",
            "ghcr.io/ls1intum/theia/langserver-python:latest",
            LanguageServerConfig.STANDARD_LS_PORT,
            "LS_PYTHON_HOST",
            "LS_PYTHON_PORT",
            "500m",
            "1Gi",
            "100m",
            "256Mi"
        ));
    }

    public void register(LanguageServerConfig config) {
        configs.put(config.languageKey(), config);
    }

    public Optional<LanguageServerConfig> get(String languageKey) {
        return Optional.ofNullable(configs.get(languageKey));
    }

    public Optional<LanguageServerConfig> getForAppDefinition(AppDefinition appDef) {
        Optional<String> imageOpt = LanguageServerConfig.getImageFromAppDefinition(appDef);
        if (imageOpt.isEmpty()) {
            return Optional.empty();
        }

        String image = imageOpt.get();
        Map<String, String> options = appDef.getSpec().getOptions();

        Optional<String> explicitLang = LanguageServerConfig.getLanguageFromAppDefinition(appDef);
        String languageKey = explicitLang.orElseGet(() -> detectLanguageFromImage(image));

        return get(languageKey)
            .map(baseConfig -> baseConfig.withImage(image).withOverrides(options))
            .or(() -> Optional.of(createGenericConfig(languageKey, image, options)));
    }

    private String detectLanguageFromImage(String image) {
        String lowerImage = image.toLowerCase();
        if (lowerImage.contains("java") || lowerImage.contains("jdt")) {
            return "java";
        }
        if (lowerImage.contains("rust") || lowerImage.contains("rust-analyzer")) {
            return "rust";
        }
        if (lowerImage.contains("python") || lowerImage.contains("pyright") || lowerImage.contains("pylsp")) {
            return "python";
        }
        if (lowerImage.contains("typescript") || lowerImage.contains("tsserver")) {
            return "typescript";
        }
        if (lowerImage.contains("clangd") || lowerImage.contains("ccls")) {
            return "cpp";
        }
        return "unknown";
    }

    private LanguageServerConfig createGenericConfig(String languageKey, String image, Map<String, String> options) {
        String envPrefix = "LS_" + languageKey.toUpperCase();
        return new LanguageServerConfig(
            languageKey,
            image,
            LanguageServerConfig.STANDARD_LS_PORT,
            envPrefix + "_HOST",
            envPrefix + "_PORT",
            options != null ? options.getOrDefault(LanguageServerConfig.OPTION_LS_CPU_LIMIT, "500m") : "500m",
            options != null ? options.getOrDefault(LanguageServerConfig.OPTION_LS_MEMORY_LIMIT, "1Gi") : "1Gi",
            options != null ? options.getOrDefault(LanguageServerConfig.OPTION_LS_CPU_REQUEST, "100m") : "100m",
            options != null ? options.getOrDefault(LanguageServerConfig.OPTION_LS_MEMORY_REQUEST, "256Mi") : "256Mi"
        );
    }

    public boolean hasConfig(String languageKey) {
        return configs.containsKey(languageKey);
    }
}
