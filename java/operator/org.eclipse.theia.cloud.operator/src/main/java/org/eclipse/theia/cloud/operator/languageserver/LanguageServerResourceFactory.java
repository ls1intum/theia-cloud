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

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.tracing.Tracing;
import org.eclipse.theia.cloud.operator.util.JavaResourceUtil;
import org.eclipse.theia.cloud.operator.util.K8sUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudPersistentVolumeUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.sentry.ISpan;
import io.sentry.SpanStatus;

@Singleton
public class LanguageServerResourceFactory {

    private static final Logger LOGGER = LogManager.getLogger(LanguageServerResourceFactory.class);

    private static final String TEMPLATE_LS_DEPLOYMENT = "/templateLanguageServerDeployment.yaml";
    private static final String TEMPLATE_LS_SERVICE = "/templateLanguageServerService.yaml";

    private static final String PLACEHOLDER_NAME = "PLACEHOLDER_NAME";
    private static final String PLACEHOLDER_NAMESPACE = "PLACEHOLDER_NAMESPACE";
    private static final String PLACEHOLDER_SESSION = "PLACEHOLDER_SESSION";
    private static final String PLACEHOLDER_IMAGE = "PLACEHOLDER_IMAGE";
    private static final String PLACEHOLDER_PORT = "PLACEHOLDER_PORT";
    private static final String PLACEHOLDER_CPU_LIMIT = "PLACEHOLDER_CPU_LIMIT";
    private static final String PLACEHOLDER_MEMORY_LIMIT = "PLACEHOLDER_MEMORY_LIMIT";
    private static final String PLACEHOLDER_CPU_REQUEST = "PLACEHOLDER_CPU_REQUEST";
    private static final String PLACEHOLDER_MEMORY_REQUEST = "PLACEHOLDER_MEMORY_REQUEST";

    @Inject
    private TheiaCloudClient client;

    public Optional<Deployment> createDeployment(
            Session session,
            LanguageServerConfig config,
            Optional<String> pvcName,
            AppDefinitionSpec appDefSpec,
            String correlationId) {

        String sessionName = session.getMetadata().getName();
        String sessionUID = session.getMetadata().getUid();
        String deploymentName = getDeploymentName(session);

        ISpan span = Tracing.childSpan("ls.deployment.create", "Create LS deployment");
        span.setTag("language", config.languageKey());
        span.setData("session", sessionName);
        span.setData("deployment_name", deploymentName);

        LOGGER.info(formatLogMessage(correlationId, 
            "[LS] Creating deployment " + deploymentName + " for language " + config.languageKey()));

        try {
            Map<String, String> replacements = new HashMap<>();
            replacements.put(PLACEHOLDER_NAME, deploymentName);
            replacements.put(PLACEHOLDER_NAMESPACE, client.namespace());
            replacements.put(PLACEHOLDER_SESSION, sessionName);
            replacements.put(PLACEHOLDER_IMAGE, config.image());
            replacements.put(PLACEHOLDER_PORT, String.valueOf(config.containerPort()));
            replacements.put(PLACEHOLDER_CPU_LIMIT, config.cpuLimit());
            replacements.put(PLACEHOLDER_MEMORY_LIMIT, config.memoryLimit());
            replacements.put(PLACEHOLDER_CPU_REQUEST, config.cpuRequest());
            replacements.put(PLACEHOLDER_MEMORY_REQUEST, config.memoryRequest());

            String deploymentYaml = JavaResourceUtil.readResourceAndReplacePlaceholders(
                TEMPLATE_LS_DEPLOYMENT, replacements, correlationId);

            Optional<Deployment> deploymentOpt = K8sUtil.loadAndCreateDeploymentWithOwnerReference(
                client.kubernetes(),
                client.namespace(),
                correlationId,
                deploymentYaml,
                Session.API,
                Session.KIND,
                sessionName,
                sessionUID,
                0,
                Collections.emptyMap(),
                dep -> {
                    if (pvcName.isPresent()) {
                        addVolumeClaimToDeployment(dep, pvcName.get(), appDefSpec);
                        LOGGER.info(formatLogMessage(correlationId, 
                            "[LS] Mounted PVC " + pvcName.get() + " to deployment"));
                    }
                });

            if (deploymentOpt.isPresent()) {
                span.setData("deployment_uid", deploymentOpt.get().getMetadata().getUid());
                Tracing.finishSuccess(span);
                LOGGER.info(formatLogMessage(correlationId, 
                    "[LS] Successfully created deployment " + deploymentName));
                return deploymentOpt;
            } else {
                span.setTag("outcome", "failure");
                Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                LOGGER.error(formatLogMessage(correlationId, 
                    "[LS] Failed to create deployment " + deploymentName));
                return Optional.empty();
            }

        } catch (IOException | URISyntaxException e) {
            LOGGER.error(formatLogMessage(correlationId, 
                "[LS] Error creating deployment " + deploymentName), e);
            Tracing.finishError(span, e);
            return Optional.empty();
        }
    }

    public Optional<Service> createService(
            Session session,
            LanguageServerConfig config,
            String correlationId) {

        String sessionName = session.getMetadata().getName();
        String sessionUID = session.getMetadata().getUid();
        String serviceName = getServiceName(session);

        ISpan span = Tracing.childSpan("ls.service.create", "Create LS service");
        span.setTag("language", config.languageKey());
        span.setData("session", sessionName);
        span.setData("service_name", serviceName);

        LOGGER.info(formatLogMessage(correlationId, 
            "[LS] Creating service " + serviceName + " for language " + config.languageKey()));

        try {
            Map<String, String> replacements = new HashMap<>();
            replacements.put(PLACEHOLDER_NAME, serviceName);
            replacements.put(PLACEHOLDER_NAMESPACE, client.namespace());
            replacements.put(PLACEHOLDER_SESSION, sessionName);
            replacements.put(PLACEHOLDER_PORT, String.valueOf(config.containerPort()));

            String serviceYaml = JavaResourceUtil.readResourceAndReplacePlaceholders(
                TEMPLATE_LS_SERVICE, replacements, correlationId);

            Optional<Service> serviceOpt = K8sUtil.loadAndCreateServiceWithOwnerReference(
                client.kubernetes(),
                client.namespace(),
                correlationId,
                serviceYaml,
                Session.API,
                Session.KIND,
                sessionName,
                sessionUID,
                0,
                Collections.emptyMap());

            if (serviceOpt.isPresent()) {
                span.setData("service_uid", serviceOpt.get().getMetadata().getUid());
                Tracing.finishSuccess(span);
                LOGGER.info(formatLogMessage(correlationId, 
                    "[LS] Successfully created service " + serviceName));
                return serviceOpt;
            } else {
                span.setTag("outcome", "failure");
                Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                LOGGER.error(formatLogMessage(correlationId, 
                    "[LS] Failed to create service " + serviceName));
                return Optional.empty();
            }

        } catch (IOException | URISyntaxException e) {
            LOGGER.error(formatLogMessage(correlationId, 
                "[LS] Error creating service " + serviceName), e);
            Tracing.finishError(span, e);
            return Optional.empty();
        }
    }

    public void injectEnvVarsIntoTheia(
            Deployment theiaDeployment,
            Session session,
            LanguageServerConfig config,
            AppDefinition appDef,
            String correlationId) {

        String serviceName = getServiceName(session);

        ISpan span = Tracing.childSpan("ls.inject_env", "Inject LS env vars");
        span.setTag("language", config.languageKey());
        span.setData("service_name", serviceName);

        LOGGER.info(formatLogMessage(correlationId, 
            "[LS] Injecting env vars for language " + config.languageKey() + " -> " + serviceName));

        try {
            String containerName = appDef.getSpec().getName();
            Optional<Integer> containerIdx = findContainerIndex(theiaDeployment, containerName);

            if (containerIdx.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId, 
                    "[LS] Could not find container " + containerName + " in deployment"));
                span.setTag("outcome", "container_not_found");
                Tracing.finish(span, SpanStatus.NOT_FOUND);
                return;
            }

            Container container = theiaDeployment.getSpec().getTemplate().getSpec()
                .getContainers().get(containerIdx.get());
            List<EnvVar> envVars = container.getEnv();

            envVars.removeIf(e -> 
                e.getName().equals(config.hostEnvVar()) ||
                e.getName().equals(config.portEnvVar()) ||
                e.getName().equals("LS_HOST") ||
                e.getName().equals("LS_PORT") ||
                e.getName().equals("LS_LANGUAGE"));

            envVars.add(new EnvVarBuilder()
                .withName(config.hostEnvVar())
                .withValue(serviceName)
                .build());
            envVars.add(new EnvVarBuilder()
                .withName(config.portEnvVar())
                .withValue(String.valueOf(config.containerPort()))
                .build());

            envVars.add(new EnvVarBuilder()
                .withName("LS_HOST")
                .withValue(serviceName)
                .build());
            envVars.add(new EnvVarBuilder()
                .withName("LS_PORT")
                .withValue(String.valueOf(config.containerPort()))
                .build());
            envVars.add(new EnvVarBuilder()
                .withName("LS_LANGUAGE")
                .withValue(config.languageKey())
                .build());

            Tracing.finishSuccess(span);
            LOGGER.info(formatLogMessage(correlationId, 
                "[LS] Successfully injected env vars: " + config.hostEnvVar() + "=" + serviceName));

        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, 
                "[LS] Error injecting env vars"), e);
            Tracing.finishError(span, e);
        }
    }

    public void deleteResources(Session session, String correlationId) {
        String resourceName = getDeploymentName(session);

        ISpan span = Tracing.childSpan("ls.delete", "Delete LS resources");
        span.setData("resource_name", resourceName);

        LOGGER.info(formatLogMessage(correlationId, "[LS] Deleting resources: " + resourceName));

        try {
            client.kubernetes().apps().deployments().withName(resourceName).delete();
            client.kubernetes().services().withName(resourceName).delete();

            Tracing.finishSuccess(span);
            LOGGER.info(formatLogMessage(correlationId, "[LS] Deleted resources: " + resourceName));

        } catch (Exception e) {
            LOGGER.debug(formatLogMessage(correlationId, 
                "[LS] Resources already deleted or not found: " + resourceName));
            Tracing.finishSuccess(span);
        }
    }

    public boolean patchEnvVarsIntoExistingDeployment(
            String deploymentName,
            Session session,
            LanguageServerConfig config,
            AppDefinition appDef,
            String correlationId) {

        String serviceName = getServiceName(session);

        ISpan span = Tracing.childSpan("ls.patch_env", "Patch LS env vars into existing deployment");
        span.setTag("language", config.languageKey());
        span.setData("deployment", deploymentName);
        span.setData("service_name", serviceName);

        LOGGER.info(formatLogMessage(correlationId,
            "[LS] Patching env vars into deployment " + deploymentName + " for language " + config.languageKey()));

        try {
            client.kubernetes().apps().deployments()
                .withName(deploymentName)
                .edit(deployment -> {
                    String containerName = appDef.getSpec().getName();
                    Optional<Integer> containerIdx = findContainerIndex(deployment, containerName);

                    if (containerIdx.isEmpty()) {
                        LOGGER.error(formatLogMessage(correlationId,
                            "[LS] Could not find container " + containerName + " in deployment " + deploymentName));
                        return deployment;
                    }

                    Container container = deployment.getSpec().getTemplate().getSpec()
                        .getContainers().get(containerIdx.get());
                    List<EnvVar> envVars = container.getEnv();

                    envVars.removeIf(e ->
                        e.getName().equals(config.hostEnvVar()) ||
                        e.getName().equals(config.portEnvVar()) ||
                        e.getName().equals("LS_HOST") ||
                        e.getName().equals("LS_PORT") ||
                        e.getName().equals("LS_LANGUAGE"));

                    envVars.add(new EnvVarBuilder()
                        .withName(config.hostEnvVar())
                        .withValue(serviceName)
                        .build());
                    envVars.add(new EnvVarBuilder()
                        .withName(config.portEnvVar())
                        .withValue(String.valueOf(config.containerPort()))
                        .build());
                    envVars.add(new EnvVarBuilder()
                        .withName("LS_HOST")
                        .withValue(serviceName)
                        .build());
                    envVars.add(new EnvVarBuilder()
                        .withName("LS_PORT")
                        .withValue(String.valueOf(config.containerPort()))
                        .build());
                    envVars.add(new EnvVarBuilder()
                        .withName("LS_LANGUAGE")
                        .withValue(config.languageKey())
                        .build());

                    return deployment;
                });

            Tracing.finishSuccess(span);
            LOGGER.info(formatLogMessage(correlationId,
                "[LS] Successfully patched env vars: " + config.hostEnvVar() + "=" + serviceName));
            return true;

        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId,
                "[LS] Error patching env vars into " + deploymentName), e);
            Tracing.finishError(span, e);
            return false;
        }
    }

    private void addVolumeClaimToDeployment(Deployment deployment, String pvcName, AppDefinitionSpec appDefSpec) {
        var podSpec = deployment.getSpec().getTemplate().getSpec();

        Volume volume = new Volume();
        volume.setName("workspace-data");
        PersistentVolumeClaimVolumeSource pvcSource = new PersistentVolumeClaimVolumeSource();
        pvcSource.setClaimName(pvcName);
        volume.setPersistentVolumeClaim(pvcSource);
        podSpec.getVolumes().add(volume);

        Container lsContainer = podSpec.getContainers().get(0);
        
        String mountPath = TheiaCloudPersistentVolumeUtil.getMountPath(appDefSpec);
        
        VolumeMount volumeMount = new VolumeMount();
        volumeMount.setName("workspace-data");
        volumeMount.setMountPath(mountPath);
        lsContainer.getVolumeMounts().add(volumeMount);
        
        lsContainer.getEnv().add(new EnvVarBuilder()
            .withName("WORKSPACE_PATH")
            .withValue(mountPath)
            .build());
    }

    private Optional<Integer> findContainerIndex(Deployment deployment, String containerName) {
        List<Container> containers = deployment.getSpec().getTemplate().getSpec().getContainers();
        for (int i = 0; i < containers.size(); i++) {
            if (containers.get(i).getName().equals(containerName)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    public static String getDeploymentName(Session session) {
        return session.getMetadata().getName() + "-ls";
    }

    public static String getServiceName(Session session) {
        return session.getMetadata().getName() + "-ls";
    }
}
