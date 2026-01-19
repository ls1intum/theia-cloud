/********************************************************************************
 * Copyright (C) 2025 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.util;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.bandwidth.BandwidthLimiter;
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.eclipse.theia.cloud.operator.ingress.IngressPathProvider;
import org.eclipse.theia.cloud.operator.replacements.DeploymentTemplateReplacements;
import org.eclipse.theia.cloud.operator.util.OwnershipManager.OwnerContext;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;

import static org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool.APPDEFINITION_GENERATION_LABEL;

/**
 * Factory for creating Kubernetes resources from templates. Centralizes the pattern: load template → replace
 * placeholders → add owner → apply.
 */
@Singleton
public class K8sResourceFactory {

    private static final Logger LOGGER = LogManager.getLogger(K8sResourceFactory.class);

    @Inject
    private TheiaCloudClient client;

    @Inject
    private TheiaCloudOperatorArguments arguments;

    @Inject
    private IngressPathProvider pathProvider;

    @Inject
    private BandwidthLimiter bandwidthLimiter;

    @Inject
    private DeploymentTemplateReplacements deploymentReplacements;

    // ========== Service Creation ==========

    /**
     * Creates a service for a prewarmed (eager) instance.
     */
    public Optional<Service> createServiceForEagerInstance(AppDefinition appDef, int instance,
            Map<String, String> labels, String correlationId) {

        Map<String, String> replacements = TheiaCloudServiceUtil.getServiceReplacements(client.namespace(), appDef,
                instance);

        String template = arguments.isUseKeycloak() ? AddedHandlerUtil.TEMPLATE_SERVICE_YAML
                : AddedHandlerUtil.TEMPLATE_SERVICE_WITHOUT_AOUTH2_PROXY_YAML;

        Map<String, String> labelsWithGeneration = addGenerationLabel(labels, appDef);

        return createService(template, replacements, OwnerContext.of(appDef.getMetadata().getName(),
                appDef.getMetadata().getUid(), AppDefinition.API, AppDefinition.KIND), labelsWithGeneration,
                correlationId);
    }

    /**
     * Creates an internal service for a prewarmed (eager) instance.
     */
    public Optional<Service> createInternalServiceForEagerInstance(AppDefinition appDef, int instance,
            Map<String, String> labels, String correlationId) {

        Map<String, String> replacements = TheiaCloudServiceUtil.getInternalServiceReplacements(client.namespace(),
                appDef, instance);

        Map<String, String> labelsWithGeneration = addGenerationLabel(labels, appDef);

        return createService(AddedHandlerUtil.TEMPLATE_INTERNAL_SERVICE_YAML, replacements,
                OwnerContext.of(appDef.getMetadata().getName(), appDef.getMetadata().getUid(), AppDefinition.API,
                        AppDefinition.KIND),
                labelsWithGeneration, correlationId);
    }

    /**
     * Creates a service for a lazy session.
     */
    public Optional<Service> createServiceForLazySession(Session session, AppDefinition appDef,
            Map<String, String> labels, String correlationId) {

        Map<String, String> replacements = TheiaCloudServiceUtil.getServiceReplacements(client.namespace(), session,
                appDef.getSpec());

        String template = arguments.isUseKeycloak() ? AddedHandlerUtil.TEMPLATE_SERVICE_YAML
                : AddedHandlerUtil.TEMPLATE_SERVICE_WITHOUT_AOUTH2_PROXY_YAML;

        return createService(template, replacements, OwnerContext.of(session.getMetadata().getName(),
                session.getMetadata().getUid(), Session.API, Session.KIND), labels, correlationId);
    }

    /**
     * Creates an internal service for a lazy session.
     */
    public Optional<Service> createInternalServiceForLazySession(Session session, AppDefinition appDef,
            Map<String, String> labels, String correlationId) {

        Map<String, String> replacements = TheiaCloudServiceUtil.getInternalServiceReplacements(client.namespace(),
                session, appDef.getSpec());

        return createService(
                AddedHandlerUtil.TEMPLATE_INTERNAL_SERVICE_YAML, replacements, OwnerContext
                        .of(session.getMetadata().getName(), session.getMetadata().getUid(), Session.API, Session.KIND),
                labels, correlationId);
    }

    // ========== Deployment Creation ==========

    /**
     * Creates a deployment for a prewarmed (eager) instance.
     */
    public Optional<Deployment> createDeploymentForEagerInstance(AppDefinition appDef, int instance,
            Map<String, String> labels, String correlationId) {

        Map<String, String> replacements = deploymentReplacements.getReplacements(client.namespace(), appDef, instance);

        String template = arguments.isUseKeycloak() ? AddedHandlerUtil.TEMPLATE_DEPLOYMENT_YAML
                : AddedHandlerUtil.TEMPLATE_DEPLOYMENT_WITHOUT_AOUTH2_PROXY_YAML;

        Map<String, String> labelsWithGeneration = addGenerationLabel(labels, appDef);

        return createDeployment(template, replacements, OwnerContext.of(appDef.getMetadata().getName(),
                appDef.getMetadata().getUid(), AppDefinition.API, AppDefinition.KIND), labelsWithGeneration,
                deployment -> {
                    bandwidthLimiter.limit(deployment, appDef.getSpec().getDownlinkLimit(),
                            appDef.getSpec().getUplinkLimit(), correlationId);
                    AddedHandlerUtil.removeEmptyResources(deployment);
                    if (appDef.getSpec().getPullSecret() != null && !appDef.getSpec().getPullSecret().isEmpty()) {
                        AddedHandlerUtil.addImagePullSecret(deployment, appDef.getSpec().getPullSecret());
                    }
                }, correlationId);
    }

    /**
     * Creates a deployment for a lazy session.
     */
    public Optional<Deployment> createDeploymentForLazySession(Session session, AppDefinition appDef,
            Optional<String> pvName, Map<String, String> labels, Consumer<Deployment> volumeHandler,
            String correlationId) {

        Map<String, String> replacements = deploymentReplacements.getReplacements(client.namespace(), appDef, session);

        String template = arguments.isUseKeycloak() ? AddedHandlerUtil.TEMPLATE_DEPLOYMENT_YAML
                : AddedHandlerUtil.TEMPLATE_DEPLOYMENT_WITHOUT_AOUTH2_PROXY_YAML;

        return createDeployment(template, replacements, OwnerContext.of(session.getMetadata().getName(),
                session.getMetadata().getUid(), Session.API, Session.KIND), labels, deployment -> {
                    // Apply session-specific labels to pod template
                    Map<String, String> podLabels = deployment.getSpec().getTemplate().getMetadata().getLabels();
                    if (podLabels == null) {
                        podLabels = new HashMap<>();
                        deployment.getSpec().getTemplate().getMetadata().setLabels(podLabels);
                    }
                    podLabels.putAll(labels);

                    // Handle volume if provided
                    if (pvName.isPresent() && volumeHandler != null) {
                        volumeHandler.accept(deployment);
                    }

                    bandwidthLimiter.limit(deployment, appDef.getSpec().getDownlinkLimit(),
                            appDef.getSpec().getUplinkLimit(), correlationId);
                    AddedHandlerUtil.removeEmptyResources(deployment);
                    AddedHandlerUtil.addCustomEnvVarsToDeploymentFromSession(correlationId, deployment, session,
                            appDef);

                    if (appDef.getSpec().getPullSecret() != null && !appDef.getSpec().getPullSecret().isEmpty()) {
                        AddedHandlerUtil.addImagePullSecret(deployment, appDef.getSpec().getPullSecret());
                    }
                }, correlationId);
    }

    // ========== ConfigMap Creation ==========

    /**
     * Creates a proxy config map for a prewarmed (eager) instance.
     */
    public Optional<ConfigMap> createProxyConfigMapForEagerInstance(AppDefinition appDef, int instance,
            Map<String, String> labels, String correlationId) {

        Map<String, String> replacements = TheiaCloudConfigMapUtil.getProxyConfigMapReplacements(client.namespace(),
                appDef, instance);

        String host = arguments.getInstancesHost() + pathProvider.getPath(appDef, instance);
        int port = appDef.getSpec().getPort();

        Map<String, String> labelsWithGeneration = addGenerationLabel(labels, appDef);

        return createConfigMap(AddedHandlerUtil.TEMPLATE_CONFIGMAP_YAML, replacements,
                OwnerContext.of(appDef.getMetadata().getName(), appDef.getMetadata().getUid(), AppDefinition.API,
                        AppDefinition.KIND),
                labelsWithGeneration, configMap -> AddedHandlerUtil.updateProxyConfigMap(client.kubernetes(),
                        client.namespace(), configMap, host, port),
                correlationId);
    }

    /**
     * Creates an email config map for a prewarmed (eager) instance.
     */
    public Optional<ConfigMap> createEmailConfigMapForEagerInstance(AppDefinition appDef, int instance,
            Map<String, String> labels, String correlationId) {

        Map<String, String> replacements = TheiaCloudConfigMapUtil.getEmailConfigMapReplacements(client.namespace(),
                appDef, instance);

        Map<String, String> labelsWithGeneration = addGenerationLabel(labels, appDef);

        return createConfigMap(AddedHandlerUtil.TEMPLATE_CONFIGMAP_EMAILS_YAML, replacements,
                OwnerContext.of(appDef.getMetadata().getName(), appDef.getMetadata().getUid(), AppDefinition.API,
                        AppDefinition.KIND),
                labelsWithGeneration, null, correlationId);
    }

    /**
     * Creates a proxy config map for a lazy session.
     */
    public Optional<ConfigMap> createProxyConfigMapForLazySession(Session session, AppDefinition appDef,
            Map<String, String> labels, String correlationId) {

        Map<String, String> replacements = TheiaCloudConfigMapUtil.getProxyConfigMapReplacements(client.namespace(),
                session);

        String host = arguments.getInstancesHost() + pathProvider.getPath(appDef, session);
        int port = appDef.getSpec().getPort();

        return createConfigMap(AddedHandlerUtil.TEMPLATE_CONFIGMAP_YAML, replacements,
                OwnerContext.of(session.getMetadata().getName(), session.getMetadata().getUid(), Session.API,
                        Session.KIND),
                labels, configMap -> AddedHandlerUtil.updateProxyConfigMap(client.kubernetes(), client.namespace(),
                        configMap, host, port),
                correlationId);
    }

    /**
     * Creates an email config map for a lazy session.
     */
    public Optional<ConfigMap> createEmailConfigMapForLazySession(Session session, Map<String, String> labels,
            String correlationId) {

        Map<String, String> replacements = TheiaCloudConfigMapUtil.getEmailConfigMapReplacements(client.namespace(),
                session);

        return createConfigMap(AddedHandlerUtil.TEMPLATE_CONFIGMAP_EMAILS_YAML, replacements,
                OwnerContext
                        .of(session.getMetadata().getName(), session.getMetadata().getUid(), Session.API, Session.KIND),
                labels,
                configMap -> configMap.setData(java.util.Collections.singletonMap(
                        AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST, session.getSpec().getUser())),
                correlationId);
    }

    // ========== Generic Creation Methods ==========

    private Optional<Service> createService(String templatePath, Map<String, String> replacements, OwnerContext owner,
            Map<String, String> labels, String correlationId) {

        String yaml = loadTemplate(templatePath, replacements, correlationId);
        if (yaml == null) {
            return Optional.empty();
        }

        return K8sUtil.loadAndCreateServiceWithOwnerReference(client.kubernetes(), client.namespace(), correlationId,
                yaml, owner.getApiVersion(), owner.getKind(), owner.getName(), owner.getUid(), 0,
                labels != null ? labels : new HashMap<>());
    }

    private Optional<Deployment> createDeployment(String templatePath, Map<String, String> replacements,
            OwnerContext owner, Map<String, String> labels, Consumer<Deployment> postProcessor, String correlationId) {

        String yaml = loadTemplate(templatePath, replacements, correlationId);
        if (yaml == null) {
            return Optional.empty();
        }

        return K8sUtil.loadAndCreateDeploymentWithOwnerReference(client.kubernetes(), client.namespace(), correlationId,
                yaml, owner.getApiVersion(), owner.getKind(), owner.getName(), owner.getUid(), 0,
                labels != null ? labels : new HashMap<>(), postProcessor != null ? postProcessor : d -> {
                });
    }

    private Optional<ConfigMap> createConfigMap(String templatePath, Map<String, String> replacements,
            OwnerContext owner, Map<String, String> labels, Consumer<ConfigMap> postProcessor, String correlationId) {

        String yaml = loadTemplate(templatePath, replacements, correlationId);
        if (yaml == null) {
            return Optional.empty();
        }

        return K8sUtil.loadAndCreateConfigMapWithOwnerReference(client.kubernetes(), client.namespace(), correlationId,
                yaml, owner.getApiVersion(), owner.getKind(), owner.getName(), owner.getUid(), 0,
                labels != null ? labels : new HashMap<>(), postProcessor != null ? postProcessor : cm -> {
                });
    }

    private String loadTemplate(String templatePath, Map<String, String> replacements, String correlationId) {
        try {
            return JavaResourceUtil.readResourceAndReplacePlaceholders(templatePath, replacements, correlationId);
        } catch (IOException | URISyntaxException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error loading template: " + templatePath), e);
            return null;
        }
    }

    /**
     * Adds the AppDefinition generation label to track resource version.
     */
    private Map<String, String> addGenerationLabel(Map<String, String> labels, AppDefinition appDef) {
        Map<String, String> result = labels != null ? new HashMap<>(labels) : new HashMap<>();
        result.put(APPDEFINITION_GENERATION_LABEL, String.valueOf(appDef.getMetadata().getGeneration()));
        return result;
    }
}
