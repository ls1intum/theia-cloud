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

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.tracing.Tracing;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.sentry.ISpan;
import io.sentry.SpanStatus;

@Singleton
public class LanguageServerManager {

    private static final Logger LOGGER = LogManager.getLogger(LanguageServerManager.class);

    @Inject
    private LanguageServerResourceFactory factory;

    @Inject
    private LanguageServerRegistry registry;

    public boolean requiresLanguageServer(AppDefinition appDef) {
        return LanguageServerConfig.isConfigured(appDef);
    }

    public Optional<LanguageServerConfig> getLanguageServerConfig(AppDefinition appDef) {
        return registry.getForAppDefinition(appDef);
    }

    public boolean createLanguageServer(
            Session session,
            AppDefinition appDef,
            Optional<String> pvcName,
            String correlationId) {

        ISpan span = Tracing.childSpan("ls.create", "Create language server");

        try {
            Optional<LanguageServerConfig> configOpt = getLanguageServerConfig(appDef);
            if (configOpt.isEmpty()) {
                span.setTag("outcome", "not_required");
                Tracing.finishSuccess(span);
                LOGGER.debug(formatLogMessage(correlationId, 
                    "[LS] No language server configured for " + appDef.getSpec().getName()));
                return true;
            }

            LanguageServerConfig config = configOpt.get();
            span.setTag("language", config.languageKey());
            span.setData("session", session.getMetadata().getName());
            span.setData("image", config.image());

            LOGGER.info(formatLogMessage(correlationId, 
                "[LS] Creating language server for session " + session.getMetadata().getName() + 
                " (language: " + config.languageKey() + ", image: " + config.image() + ")"));

            ISpan svcSpan = Tracing.childSpan(span, "ls.service.create", "Create LS service");
            Optional<Service> service = factory.createService(session, config, correlationId);
            if (service.isEmpty()) {
                svcSpan.setTag("outcome", "failure");
                Tracing.finish(svcSpan, SpanStatus.INTERNAL_ERROR);
                Tracing.finishError(span, new RuntimeException("Failed to create LS service"));
                return false;
            }
            Tracing.finishSuccess(svcSpan);

            ISpan depSpan = Tracing.childSpan(span, "ls.deployment.create", "Create LS deployment");
            Optional<Deployment> deployment = factory.createDeployment(
                session, config, pvcName, appDef.getSpec(), correlationId);
            if (deployment.isEmpty()) {
                depSpan.setTag("outcome", "failure");
                Tracing.finish(depSpan, SpanStatus.INTERNAL_ERROR);
                Tracing.finishError(span, new RuntimeException("Failed to create LS deployment"));
                return false;
            }
            Tracing.finishSuccess(depSpan);

            span.setData("ls_service", service.get().getMetadata().getName());
            span.setData("ls_deployment", deployment.get().getMetadata().getName());
            Tracing.finishSuccess(span);

            LOGGER.info(formatLogMessage(correlationId, 
                "[LS] Successfully created language server for session " + session.getMetadata().getName()));

            return true;

        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "[LS] Error creating language server"), e);
            Tracing.finishError(span, e);
            return false;
        }
    }

    public void injectEnvVars(
            Deployment theiaDeployment,
            Session session,
            AppDefinition appDef,
            String correlationId) {

        Optional<LanguageServerConfig> configOpt = getLanguageServerConfig(appDef);
        if (configOpt.isEmpty()) {
            LOGGER.debug(formatLogMessage(correlationId, 
                "[LS] No language server configured, skipping env var injection"));
            return;
        }

        factory.injectEnvVarsIntoTheia(theiaDeployment, session, configOpt.get(), appDef, correlationId);
    }

    public boolean patchEnvVarsIntoExistingDeployment(
            String deploymentName,
            Session session,
            AppDefinition appDef,
            String correlationId) {

        Optional<LanguageServerConfig> configOpt = getLanguageServerConfig(appDef);
        if (configOpt.isEmpty()) {
            LOGGER.debug(formatLogMessage(correlationId,
                "[LS] No language server configured, skipping env var patching"));
            return true;
        }

        return factory.patchEnvVarsIntoExistingDeployment(
            deploymentName, session, configOpt.get(), appDef, correlationId);
    }

    public void deleteLanguageServer(Session session, String correlationId) {
        factory.deleteResources(session, correlationId);
    }
}
