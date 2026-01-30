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
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerManager;
import org.eclipse.theia.cloud.operator.util.K8sResourceFactory;
import org.eclipse.theia.cloud.operator.util.K8sUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudK8sUtil;
import org.eclipse.theia.cloud.operator.util.SentryHelper;
import org.eclipse.theia.cloud.operator.util.TheiaCloudPersistentVolumeUtil;

import com.google.inject.Inject;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;

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

    @Inject
    protected LanguageServerManager languageServerManager;

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

        // Start Sentry transaction for lazy session handling
        ITransaction tx = Sentry.startTransaction("session.added", "lazy");
        tx.setTag("session.name", sessionResourceName);
        tx.setTag("session.strategy", "lazy");
        tx.setData("correlation_id", correlationId);

        try {
            // Check current session status and ignore if handling failed or finished before
            Optional<SessionStatus> status = Optional.ofNullable(session.getStatus());
            String operatorStatus = status.map(ResourceStatus::getOperatorStatus).orElse(OperatorStatus.NEW);

            if (OperatorStatus.HANDLED.equals(operatorStatus)) {
                LOGGER.trace(formatLogMessage(correlationId,
                        "Session was successfully handled before and is skipped now. Session: " + session));
                tx.setTag("outcome", "already_handled");
                tx.setStatus(SpanStatus.OK);
                tx.finish();
                return true;
            }
            if (OperatorStatus.HANDLING.equals(operatorStatus)) {
                LOGGER.warn(formatLogMessage(correlationId, "Session handling was interrupted. Setting to ERROR."));
                client.sessions().updateStatus(correlationId, session, s -> {
                    s.setOperatorStatus(OperatorStatus.ERROR);
                    s.setOperatorMessage("Handling was unexpectedly interrupted. CorrelationId: " + correlationId);
                });
                tx.setTag("outcome", "interrupted");
                tx.setStatus(SpanStatus.ABORTED);
                tx.finish();
                return false;
            }
            if (OperatorStatus.ERROR.equals(operatorStatus)) {
                LOGGER.warn(formatLogMessage(correlationId, "Session previously errored. Skipping."));
                tx.setTag("outcome", "previous_error");
                tx.setStatus(SpanStatus.ABORTED);
                tx.finish();
                return false;
            }

            // Set status to handling
            client.sessions().updateStatus(correlationId, session, s -> s.setOperatorStatus(OperatorStatus.HANDLING));

            SessionSpec sessionSpec = session.getSpec();

            // Find app definition
            ISpan appDefSpan = tx.startChild("lazy.find_appdef", "Find app definition");
            String appDefinitionID = sessionSpec.getAppDefinition();
            tx.setTag("app_definition", appDefinitionID);
            Optional<AppDefinition> appDefOpt = client.appDefinitions().get(appDefinitionID);
            if (appDefOpt.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId,
                        "No App Definition with name " + appDefinitionID + " found."));
                setSessionError(session, correlationId, "App Definition not found.");
                SentryHelper.finishWithOutcome(appDefSpan, "not_found", SpanStatus.NOT_FOUND);
                tx.setTag("outcome", "appdef_not_found");
                tx.setStatus(SpanStatus.NOT_FOUND);
                tx.finish();
                return false;
            }
            AppDefinition appDef = appDefOpt.get();
            AppDefinitionSpec appDefSpec = appDef.getSpec();
            SentryHelper.finishSuccess(appDefSpan);

            // Create labels
            Map<String, String> labels = LabelsUtil.createSessionLabels(session, appDef);

            // Check limits
            ISpan limitsSpan = tx.startChild("lazy.check_limits", "Check instance and session limits");
            if (hasMaxInstancesReached(appDef, session, correlationId)) {
                setSessionError(session, correlationId, "Max instances reached.");
                SentryHelper.finishWithOutcome(limitsSpan, "max_instances", SpanStatus.RESOURCE_EXHAUSTED);
                tx.setTag("outcome", "max_instances_reached");
                tx.setStatus(SpanStatus.RESOURCE_EXHAUSTED);
                tx.finish();
                return false;
            }
            if (hasMaxSessionsReached(session, correlationId)) {
                setSessionError(session, correlationId, "Max sessions reached.");
                SentryHelper.finishWithOutcome(limitsSpan, "max_sessions", SpanStatus.RESOURCE_EXHAUSTED);
                tx.setTag("outcome", "max_sessions_reached");
                tx.setStatus(SpanStatus.RESOURCE_EXHAUSTED);
                tx.finish();
                return false;
            }
            SentryHelper.finishSuccess(limitsSpan);

            // Get ingress
            ISpan ingressSpan = tx.startChild("lazy.get_ingress", "Get ingress for app definition");
            Optional<Ingress> ingressOpt = ingressManager.getIngress(appDef, correlationId);
            if (ingressOpt.isEmpty()) {
                setSessionError(session, correlationId, "Ingress not available.");
                SentryHelper.finishWithOutcome(ingressSpan, "not_found", SpanStatus.NOT_FOUND);
                tx.setTag("outcome", "ingress_not_found");
                tx.setStatus(SpanStatus.NOT_FOUND);
                tx.finish();
                return false;
            }
            SentryHelper.finishSuccess(ingressSpan);

            syncSessionDataToWorkspace(session, correlationId);

            // Check for existing service (idempotency)
            List<Service> existingServices = K8sUtil.getExistingServices(
                    client.kubernetes(), client.namespace(), sessionResourceName, sessionResourceUID);
            if (!existingServices.isEmpty()) {
                LOGGER.warn(formatLogMessage(correlationId, "Service already exists for session."));
                setSessionHandled(session, correlationId, "Service already exists.");
                tx.setTag("outcome", "idempotent_service_exists");
                tx.setStatus(SpanStatus.OK);
                tx.finish();
                return true;
            }

            // Create service
            ISpan serviceSpan = tx.startChild("lazy.create_service", "Create service");
            Optional<Service> serviceOpt = resourceFactory.createServiceForLazySession(
                    session, appDef, labels, correlationId);
            if (serviceOpt.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId, "Unable to create service for session."));
                setSessionError(session, correlationId, "Failed to create service.");
                SentryHelper.finishWithOutcome(serviceSpan, "failed", SpanStatus.INTERNAL_ERROR);
                tx.setTag("outcome", "service_creation_failed");
                tx.setStatus(SpanStatus.INTERNAL_ERROR);
                tx.finish();
                return false;
            }
            SentryHelper.finishSuccess(serviceSpan);

            // Create internal service
            ISpan internalServiceSpan = tx.startChild("lazy.create_internal_service", "Create internal service");
            Optional<Service> internalServiceOpt = resourceFactory.createInternalServiceForLazySession(
                    session, appDef, labels, correlationId);
            if (internalServiceOpt.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId, "Unable to create internal service."));
                setSessionError(session, correlationId, "Failed to create internal service.");
                SentryHelper.finishWithOutcome(internalServiceSpan, "failed", SpanStatus.INTERNAL_ERROR);
                tx.setTag("outcome", "internal_service_failed");
                tx.setStatus(SpanStatus.INTERNAL_ERROR);
                tx.finish();
                return false;
            }
            SentryHelper.finishSuccess(internalServiceSpan);

            // Create configmaps (if using Keycloak)
            if (arguments.isUseKeycloak()) {
                ISpan configMapSpan = tx.startChild("lazy.create_configmaps", "Create OAuth2 configmaps");
                List<ConfigMap> existingConfigMaps = K8sUtil.getExistingConfigMaps(
                        client.kubernetes(), client.namespace(), sessionResourceName, sessionResourceUID);
                if (!existingConfigMaps.isEmpty()) {
                    LOGGER.warn(formatLogMessage(correlationId, "ConfigMaps already exist for session."));
                    setSessionHandled(session, correlationId, "ConfigMaps already exist.");
                    SentryHelper.finishWithOutcome(configMapSpan, "already_exists", SpanStatus.OK);
                    tx.setTag("outcome", "idempotent_configmaps_exist");
                    tx.setStatus(SpanStatus.OK);
                    tx.finish();
                    return true;
                }
                resourceFactory.createEmailConfigMapForLazySession(session, labels, correlationId);
                resourceFactory.createProxyConfigMapForLazySession(session, appDef, labels, correlationId);
                SentryHelper.finishSuccess(configMapSpan);
            }

            // Check for existing deployment (idempotency)
            List<Deployment> existingDeployments = K8sUtil.getExistingDeployments(
                    client.kubernetes(), client.namespace(), sessionResourceName, sessionResourceUID);
            if (!existingDeployments.isEmpty()) {
                LOGGER.warn(formatLogMessage(correlationId, "Deployment already exists for session."));
                setSessionHandled(session, correlationId, "Deployment already exists.");
                tx.setTag("outcome", "idempotent_deployment_exists");
                tx.setStatus(SpanStatus.OK);
                tx.finish();
                return true;
            }

            // Create deployment
            ISpan deploymentSpan = tx.startChild("lazy.create_deployment", "Create deployment");
            Optional<String> storageName = getStorageName(session, correlationId);
            deploymentSpan.setData("has_storage", storageName.isPresent());
            resourceFactory.createDeploymentForLazySession(
                    session, appDef, storageName, labels,
                    deployment -> {
                        storageName.ifPresent(name -> addVolumeClaim(deployment, name, appDefSpec));
                        languageServerManager.injectEnvVars(deployment, session, appDef, correlationId);
                    },
                    correlationId);
            SentryHelper.finishSuccess(deploymentSpan);

            // Create language server
            languageServerManager.createLanguageServer(session, appDef, storageName, correlationId);

            // Add ingress rule
            ISpan ingressRuleSpan = tx.startChild("lazy.add_ingress_rule", "Add ingress rule");
            String host;
            try {
                host = ingressManager.addRuleForLazySession(
                        ingressOpt.get(), serviceOpt.get(), session, appDef, correlationId);
                ingressRuleSpan.setData("host", host);
                SentryHelper.finishSuccess(ingressRuleSpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Error while editing ingress"), e);
                setSessionError(session, correlationId, "Failed to edit ingress.");
                SentryHelper.finishError(ingressRuleSpan, e);
                tx.setTag("outcome", "ingress_rule_failed");
                tx.setStatus(SpanStatus.INTERNAL_ERROR);
                tx.finish();
                return false;
            }

            // Schedule async URL availability check (tracked in separate transaction: session.url_availability)
            SentryHelper.breadcrumb("Scheduling URL availability check for " + host, "session");
            AddedHandlerUtil.updateSessionURLAsync(client.sessions(), session, client.namespace(), host, correlationId);

            setSessionHandled(session, correlationId, null);
            tx.setTag("outcome", "success");
            tx.setStatus(SpanStatus.OK);
            tx.finish();
            return true;

        } catch (Exception e) {
            tx.setThrowable(e);
            tx.setTag("outcome", "error");
            tx.setStatus(SpanStatus.INTERNAL_ERROR);
            Sentry.captureException(e);
            tx.finish();
            throw e;
        }
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
        String sessionName = session.getMetadata().getName();
        String appDefinitionID = sessionSpec.getAppDefinition();

        // Start Sentry transaction for lazy session deletion
        ITransaction tx = Sentry.startTransaction("session.deleted", "lazy");
        tx.setTag("session.name", sessionName);
        tx.setTag("session.strategy", "lazy");
        tx.setTag("app_definition", appDefinitionID);
        tx.setData("correlation_id", correlationId);

        try {
            ISpan appDefSpan = tx.startChild("lazy.find_appdef", "Find app definition");
            Optional<AppDefinition> appDefOpt = client.appDefinitions().get(appDefinitionID);
            if (appDefOpt.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId,
                        "No App Definition found. Cannot clean up ingress for session " + sessionSpec.getName()));
                SentryHelper.finishWithOutcome(appDefSpan, "not_found", SpanStatus.NOT_FOUND);
                tx.setTag("outcome", "appdef_not_found");
                tx.setStatus(SpanStatus.NOT_FOUND);
                tx.finish();
                return false;
            }
            AppDefinition appDef = appDefOpt.get();
            SentryHelper.finishSuccess(appDefSpan);

            ISpan ingressSpan = tx.startChild("lazy.get_ingress", "Get ingress for cleanup");
            Optional<Ingress> ingressOpt = ingressManager.getIngress(appDef, correlationId);
            if (ingressOpt.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId, "No Ingress found for app definition."));
                SentryHelper.finishWithOutcome(ingressSpan, "not_found", SpanStatus.NOT_FOUND);
                tx.setTag("outcome", "ingress_not_found");
                tx.setStatus(SpanStatus.NOT_FOUND);
                tx.finish();
                return false;
            }
            SentryHelper.finishSuccess(ingressSpan);

            // Remove ingress rules
            ISpan removeRulesSpan = tx.startChild("lazy.remove_ingress_rules", "Remove ingress rules");
            boolean success = ingressManager.removeRulesForLazySession(
                    ingressOpt.get(), session, appDef, correlationId);

            if (!success) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to remove ingress rules for session"));
                SentryHelper.finishWithOutcome(removeRulesSpan, "failed", SpanStatus.INTERNAL_ERROR);
                tx.setTag("outcome", "remove_rules_failed");
                tx.setStatus(SpanStatus.INTERNAL_ERROR);
                tx.finish();
                return false;
            }
            SentryHelper.finishSuccess(removeRulesSpan);

            LOGGER.info(formatLogMessage(correlationId, "Successfully cleaned up ingress rules for session"));

            // Cleanup language server
            languageServerManager.deleteLanguageServer(session, correlationId);

            tx.setTag("outcome", "success");
            tx.setStatus(SpanStatus.OK);
            tx.finish();
            return true;

        } catch (Exception e) {
            tx.setThrowable(e);
            tx.setTag("outcome", "error");
            tx.setStatus(SpanStatus.INTERNAL_ERROR);
            Sentry.captureException(e);
            tx.finish();
            throw e;
        }
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
