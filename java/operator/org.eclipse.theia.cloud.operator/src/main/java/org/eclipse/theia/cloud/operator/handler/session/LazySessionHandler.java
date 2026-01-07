/********************************************************************************
 * Copyright (C) 2022-2025 EclipseSource, Lockular, Ericsson, STMicroelectronics and 
 * others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.handler.session;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;
import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatMetric;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.OperatorStatus;
import org.eclipse.theia.cloud.common.k8s.resource.ResourceStatus;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionStatus;
import org.eclipse.theia.cloud.common.k8s.resource.workspace.Workspace;
import org.eclipse.theia.cloud.common.util.LabelsUtil;
import org.eclipse.theia.cloud.common.util.TheiaCloudError;
import org.eclipse.theia.cloud.common.util.WorkspaceUtil;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.eclipse.theia.cloud.operator.ingress.IngressManager;
import org.eclipse.theia.cloud.operator.util.K8sResourceFactory;
import org.eclipse.theia.cloud.operator.util.K8sUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudK8sUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudPersistentVolumeUtil;

import com.google.inject.Inject;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimVolumeSource;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * A {@link SessionHandler} that creates resources on-demand (lazy start).
 * 
 * Uses {@link K8sResourceFactory} for resource creation and
 * {@link IngressManager} for ingress operations.
 */
public class LazySessionHandler implements SessionHandler {

    private static final Logger LOGGER = LogManager.getLogger(LazySessionHandler.class);
    protected static final String USER_DATA = "user-data";

    @Inject
    protected TheiaCloudOperatorArguments arguments;

    @Inject
    protected TheiaCloudClient client;

    @Inject
    protected K8sResourceFactory resourceFactory;

    @Inject
    protected IngressManager ingressManager;

    @Override
    public boolean sessionAdded(Session session, String correlationId) {
        try {
            return doSessionAdded(session, correlationId);
        } catch (Throwable ex) {
            LOGGER.error(formatLogMessage(correlationId,
                    "An unexpected exception occurred while adding Session: " + session), ex);
            client.sessions().updateStatus(correlationId, session, status -> {
                status.setOperatorStatus(OperatorStatus.ERROR);
                status.setOperatorMessage(
                        "Unexpected error. Please check the logs for correlationId: " + correlationId);
            });
            return false;
        }
    }

    protected boolean doSessionAdded(Session session, String correlationId) {
        // Session information
        String sessionResourceName = session.getMetadata().getName();
        String sessionResourceUID = session.getMetadata().getUid();

        // Check current session status and ignore if handling failed or finished before
        Optional<SessionStatus> status = Optional.ofNullable(session.getStatus());
        String operatorStatus = status.map(ResourceStatus::getOperatorStatus).orElse(OperatorStatus.NEW);

        if (OperatorStatus.HANDLED.equals(operatorStatus)) {
            LOGGER.trace(formatLogMessage(correlationId,
                    "Session was successfully handled before and is skipped now. Session: " + session));
            return true;
        }
        if (OperatorStatus.HANDLING.equals(operatorStatus)) {
            LOGGER.warn(formatLogMessage(correlationId, "Session handling was interrupted. Setting to ERROR."));
            client.sessions().updateStatus(correlationId, session, s -> {
                s.setOperatorStatus(OperatorStatus.ERROR);
                s.setOperatorMessage("Handling was unexpectedly interrupted. CorrelationId: " + correlationId);
            });
            return false;
        }
        if (OperatorStatus.ERROR.equals(operatorStatus)) {
            LOGGER.warn(formatLogMessage(correlationId, "Session previously errored. Skipping."));
            return false;
        }

        // Set status to handling
        client.sessions().updateStatus(correlationId, session, s -> s.setOperatorStatus(OperatorStatus.HANDLING));

        SessionSpec sessionSpec = session.getSpec();

        // Find app definition
        String appDefinitionID = sessionSpec.getAppDefinition();
        Optional<AppDefinition> appDefOpt = client.appDefinitions().get(appDefinitionID);
        if (appDefOpt.isEmpty()) {
            LOGGER.error(formatLogMessage(correlationId, "No App Definition with name " + appDefinitionID + " found."));
            setSessionError(session, correlationId, "App Definition not found.");
            return false;
        }
        AppDefinition appDef = appDefOpt.get();
        AppDefinitionSpec appDefSpec = appDef.getSpec();

        // Create labels
        Map<String, String> labels = LabelsUtil.createSessionLabels(session, appDef);

        // Check limits
        if (hasMaxInstancesReached(appDef, session, correlationId)) {
            setSessionError(session, correlationId, "Max instances reached.");
            return false;
        }
        if (hasMaxSessionsReached(session, correlationId)) {
            setSessionError(session, correlationId, "Max sessions reached.");
            return false;
        }

        // Get ingress
        Optional<Ingress> ingressOpt = ingressManager.getIngress(appDef, correlationId);
        if (ingressOpt.isEmpty()) {
            setSessionError(session, correlationId, "Ingress not available.");
            return false;
        }

        syncSessionDataToWorkspace(session, correlationId);

        // Check for existing service (idempotency)
        List<Service> existingServices = K8sUtil.getExistingServices(
                client.kubernetes(), client.namespace(), sessionResourceName, sessionResourceUID);
        if (!existingServices.isEmpty()) {
            LOGGER.warn(formatLogMessage(correlationId, "Service already exists for session."));
            setSessionHandled(session, correlationId, "Service already exists.");
            return true;
        }

        // Create service
        Optional<Service> serviceOpt = resourceFactory.createServiceForLazySession(
                session, appDef, labels, correlationId);
        if (serviceOpt.isEmpty()) {
            LOGGER.error(formatLogMessage(correlationId, "Unable to create service for session."));
            setSessionError(session, correlationId, "Failed to create service.");
            return false;
        }

        // Create internal service
        Optional<Service> internalServiceOpt = resourceFactory.createInternalServiceForLazySession(
                session, appDef, labels, correlationId);
        if (internalServiceOpt.isEmpty()) {
            LOGGER.error(formatLogMessage(correlationId, "Unable to create internal service."));
            setSessionError(session, correlationId, "Failed to create internal service.");
            return false;
        }

        // Create configmaps (if using Keycloak)
        if (arguments.isUseKeycloak()) {
            List<ConfigMap> existingConfigMaps = K8sUtil.getExistingConfigMaps(
                    client.kubernetes(), client.namespace(), sessionResourceName, sessionResourceUID);
            if (!existingConfigMaps.isEmpty()) {
                LOGGER.warn(formatLogMessage(correlationId, "ConfigMaps already exist for session."));
                setSessionHandled(session, correlationId, "ConfigMaps already exist.");
                return true;
            }
            resourceFactory.createEmailConfigMapForLazySession(session, labels, correlationId);
            resourceFactory.createProxyConfigMapForLazySession(session, appDef, labels, correlationId);
        }

        // Check for existing deployment (idempotency)
        List<Deployment> existingDeployments = K8sUtil.getExistingDeployments(
                client.kubernetes(), client.namespace(), sessionResourceName, sessionResourceUID);
        if (!existingDeployments.isEmpty()) {
            LOGGER.warn(formatLogMessage(correlationId, "Deployment already exists for session."));
            setSessionHandled(session, correlationId, "Deployment already exists.");
            return true;
        }

        // Create deployment
        Optional<String> storageName = getStorageName(session, correlationId);
        resourceFactory.createDeploymentForLazySession(
                session, appDef, storageName, labels,
                deployment -> storageName.ifPresent(name -> addVolumeClaim(deployment, name, appDefSpec)),
                correlationId);

        // Add ingress rule
        String host;
        try {
            host = ingressManager.addRuleForLazySession(
                    ingressOpt.get(), serviceOpt.get(), session, appDef, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while editing ingress"), e);
            setSessionError(session, correlationId, "Failed to edit ingress.");
            return false;
        }

        // Update session URL
        try {
            AddedHandlerUtil.updateSessionURLAsync(client.sessions(), session, client.namespace(), host, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while updating session URL"), e);
            setSessionError(session, correlationId, "Failed to set session URL.");
            return false;
        }

        setSessionHandled(session, correlationId, null);
        return true;
    }

    @Override
    public synchronized boolean sessionDeleted(Session session, String correlationId) {
        try {
            return doSessionDeleted(session, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Kubernetes API error while deleting session"), e);
            return false;
        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "Unexpected error while deleting session"), e);
            return false;
        }
    }

    protected boolean doSessionDeleted(Session session, String correlationId) {
        SessionSpec sessionSpec = session.getSpec();
        String appDefinitionID = sessionSpec.getAppDefinition();

        Optional<AppDefinition> appDefOpt = client.appDefinitions().get(appDefinitionID);
        if (appDefOpt.isEmpty()) {
            LOGGER.error(formatLogMessage(correlationId,
                    "No App Definition found. Cannot clean up ingress for session " + sessionSpec.getName()));
            return false;
        }
        AppDefinition appDef = appDefOpt.get();

        Optional<Ingress> ingressOpt = ingressManager.getIngress(appDef, correlationId);
        if (ingressOpt.isEmpty()) {
            LOGGER.error(formatLogMessage(correlationId, "No Ingress found for app definition."));
            return false;
        }

        // Remove ingress rules
        boolean success = ingressManager.removeRulesForLazySession(
                ingressOpt.get(), session, appDef, correlationId);

        if (!success) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to remove ingress rules for session"));
            return false;
        }

        LOGGER.info(formatLogMessage(correlationId, "Successfully cleaned up ingress rules for session"));
        return true;
    }

    // ========== Helper Methods ==========

    private void setSessionError(Session session, String correlationId, String message) {
        client.sessions().updateStatus(correlationId, session, s -> {
            s.setOperatorStatus(OperatorStatus.ERROR);
            s.setOperatorMessage(message);
        });
    }

    private void setSessionHandled(Session session, String correlationId, String message) {
        client.sessions().updateStatus(correlationId, session, s -> {
            s.setOperatorStatus(OperatorStatus.HANDLED);
            if (message != null) {
                s.setOperatorMessage(message);
            }
            s.setLastActivity(Instant.now().toEpochMilli());
        });
    }

    protected void syncSessionDataToWorkspace(Session session, String correlationId) {
        if (!session.getSpec().isEphemeral() && session.getSpec().hasAppDefinition()) {
            client.workspaces().edit(correlationId, session.getSpec().getWorkspace(), workspace -> {
                workspace.getSpec().setAppDefinition(session.getSpec().getAppDefinition());
            });
        }
    }

    protected boolean hasMaxInstancesReached(AppDefinition appDef, Session session, String correlationId) {
        if (TheiaCloudK8sUtil.checkIfMaxInstancesReached(
                client.kubernetes(), client.namespace(), session.getSpec(), appDef.getSpec(), correlationId)) {
            LOGGER.info(formatMetric(correlationId, "Max instances reached for " + appDef.getSpec().getName()));
            client.sessions().updateStatus(correlationId, session, status -> {
                status.setError(TheiaCloudError.SESSION_SERVER_LIMIT_REACHED);
            });
            return true;
        }
        return false;
    }

    protected boolean hasMaxSessionsReached(Session session, String correlationId) {
        if (arguments.getSessionsPerUser() == null || arguments.getSessionsPerUser() < 0) {
            return false;
        }
        if (arguments.getSessionsPerUser() == 0) {
            LOGGER.info(formatLogMessage(correlationId, "No sessions allowed for this user."));
            client.sessions().updateStatus(correlationId, session, status -> {
                status.setError(TheiaCloudError.SESSION_USER_NO_SESSIONS);
            });
            return true;
        }
        long userSessions = client.sessions().list(session.getSpec().getUser()).size();
        if (userSessions > arguments.getSessionsPerUser()) {
            LOGGER.info(formatLogMessage(correlationId,
                    "Session limit reached for user: " + arguments.getSessionsPerUser()));
            client.sessions().updateStatus(correlationId, session, status -> {
                status.setError(TheiaCloudError.SESSION_USER_LIMIT_REACHED);
            });
            return true;
        }
        return false;
    }

    protected Optional<String> getStorageName(Session session, String correlationId) {
        if (session.getSpec().isEphemeral()) {
            return Optional.empty();
        }
        Optional<Workspace> workspace = client.workspaces().get(session.getSpec().getWorkspace());
        if (workspace.isEmpty()) {
            LOGGER.info(formatLogMessage(correlationId,
                    "No workspace with name " + session.getSpec().getWorkspace() + " found for session " + session.getSpec().getName()));
            return Optional.empty();
        }
        if (!session.getSpec().getUser().equals(workspace.get().getSpec().getUser())) {
            // the workspace is owned by a different user. do not mount and go ephemeral
            // should get prevented by service, but we need to be sure to not expose data
            LOGGER.error(formatLogMessage(correlationId, "Workspace is owned by " + workspace.get().getSpec().getUser()
                    + ", but requesting user is " + session.getSpec().getUser()));
            return Optional.empty();
        }
        String storageName = WorkspaceUtil.getStorageName(workspace.get());
        if (!client.persistentVolumeClaimsClient().has(storageName)) {
            LOGGER.info(formatLogMessage(correlationId, "No storage found. Using ephemeral storage."));
            return Optional.empty();
        }
        return Optional.of(storageName);
    }

    protected void addVolumeClaim(Deployment deployment, String pvcName, AppDefinitionSpec appDef) {
        PodSpec podSpec = deployment.getSpec().getTemplate().getSpec();

        Volume volume = new Volume();
        podSpec.getVolumes().add(volume);
        volume.setName(USER_DATA);
        PersistentVolumeClaimVolumeSource pvc = new PersistentVolumeClaimVolumeSource();
        volume.setPersistentVolumeClaim(pvc);
        pvc.setClaimName(pvcName);

        Container theiaContainer = TheiaCloudPersistentVolumeUtil.getTheiaContainer(podSpec, appDef);

        VolumeMount volumeMount = new VolumeMount();
        theiaContainer.getVolumeMounts().add(volumeMount);
        volumeMount.setName(USER_DATA);
        volumeMount.setMountPath(TheiaCloudPersistentVolumeUtil.getMountPath(appDef));
    }
}
