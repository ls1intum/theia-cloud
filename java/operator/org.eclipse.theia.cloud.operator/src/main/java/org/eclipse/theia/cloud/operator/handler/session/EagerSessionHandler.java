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

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.util.DataBridgeUtil;
import org.eclipse.theia.cloud.operator.databridge.AsyncDataInjector;
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.eclipse.theia.cloud.operator.ingress.IngressManager;
import org.eclipse.theia.cloud.operator.languageserver.LanguageServerManager;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool;
import org.eclipse.theia.cloud.operator.util.SentryHelper;
import org.eclipse.theia.cloud.operator.util.SessionEnvCollector;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool.PoolInstance;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool.ReservationOutcome;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool.ReservationResult;

import com.google.inject.Inject;

import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.sentry.ISpan;
import io.sentry.Sentry;
import io.sentry.SpanStatus;

/**
 * A {@link SessionHandler} that uses prewarmed instances from the pool. This handler delegates to
 * {@link PrewarmedResourcePool} for instance management and {@link IngressManager} for ingress operations.
 */
public class EagerSessionHandler implements SessionHandler {

    private static final Logger LOGGER = LogManager.getLogger(EagerSessionHandler.class);

    public static final String SESSION_START_STRATEGY_ANNOTATION = "theia-cloud.io/session-start-strategy";
    public static final String SESSION_START_STRATEGY_EAGER = "eager";
    public static final String SESSION_INSTANCE_ID_ANNOTATION = "theia-cloud.io/instance-id";

    /**
     * Outcome of trying to handle a session with eager start.
     */
    public enum EagerSessionAddedOutcome {
        HANDLED, NO_CAPACITY, ERROR
    }

    @Inject
    private TheiaCloudClient client;

    @Inject
    private PrewarmedResourcePool pool;

    @Inject
    private IngressManager ingressManager;

    @Inject
    private LanguageServerManager languageServerManager;

    @Inject
    private SessionEnvCollector sessionEnvCollector;

    @Inject
    private AsyncDataInjector asyncDataInjector;

    @Override
    public boolean sessionAdded(Session session, String correlationId) {
        return trySessionAdded(session, correlationId) == EagerSessionAddedOutcome.HANDLED;
    }

    /**
     * Tries to handle a session using eager start. Returns the outcome so callers can fall back to lazy start if
     * needed.
     */
    public EagerSessionAddedOutcome trySessionAdded(Session session, String correlationId) {
        SessionSpec spec = session.getSpec();
        String sessionName = spec.getName();
        String appDefinitionID = spec.getAppDefinition();

        // Get current span from parent transaction
        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null
                ? parentSpan.startChild("eager.setup", "Eager session setup")
                : Sentry.startTransaction("eager.setup", "session");

        span.setData("session_name", sessionName);
        span.setData("app_definition", appDefinitionID);
        span.setData("user", spec.getUser());

        LOGGER.info(formatLogMessage(correlationId, "Handling sessionAdded " + spec));

        try {
            // Find app definition
            ISpan appDefSpan = span.startChild("eager.find_appdef", "Find app definition");
            Optional<AppDefinition> appDefOpt = client.appDefinitions().get(appDefinitionID);
            if (appDefOpt.isEmpty()) {
                LOGGER.error(
                        formatLogMessage(correlationId, "No App Definition with name " + appDefinitionID + " found."));
                SentryHelper.finishWithOutcome(appDefSpan, "not_found", SpanStatus.NOT_FOUND);
                SentryHelper.finishWithOutcome(span, "error", SpanStatus.NOT_FOUND);
                return EagerSessionAddedOutcome.ERROR;
            }
            AppDefinition appDef = appDefOpt.get();
            SentryHelper.finishSuccess(appDefSpan);

            // Find ingress
            ISpan ingressSpan = span.startChild("eager.find_ingress", "Find ingress");
            Optional<Ingress> ingressOpt = ingressManager.getIngress(appDef, correlationId);
            if (ingressOpt.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId,
                        "No Ingress for app definition " + appDefinitionID + " found."));
                SentryHelper.finishWithOutcome(ingressSpan, "not_found", SpanStatus.NOT_FOUND);
                SentryHelper.finishWithOutcome(span, "error", SpanStatus.NOT_FOUND);
                return EagerSessionAddedOutcome.ERROR;
            }
            Ingress ingress = ingressOpt.get();
            SentryHelper.finishSuccess(ingressSpan);

            // Reserve an instance from the pool
            ISpan reserveSpan = span.startChild("eager.reserve_instance", "Reserve pool instance");
            ReservationResult reservation = pool.reserveInstance(session, appDef, correlationId);
            reserveSpan.setTag("pool.outcome", reservation.getOutcome().name().toLowerCase());

            if (reservation.getOutcome() == ReservationOutcome.NO_CAPACITY) {
                SentryHelper.finishWithOutcome(reserveSpan, "no_capacity", SpanStatus.RESOURCE_EXHAUSTED);
                SentryHelper.finishWithOutcome(span, "no_capacity", SpanStatus.RESOURCE_EXHAUSTED);
                return EagerSessionAddedOutcome.NO_CAPACITY;
            }
            if (reservation.getOutcome() == ReservationOutcome.ERROR) {
                SentryHelper.finishWithOutcome(reserveSpan, "error", SpanStatus.INTERNAL_ERROR);
                SentryHelper.finishWithOutcome(span, "error", SpanStatus.INTERNAL_ERROR);
                return EagerSessionAddedOutcome.ERROR;
            }

            PoolInstance instance = reservation.getInstance().get();
            reserveSpan.setData("instance_id", instance.getInstanceId());
            SentryHelper.finishSuccess(reserveSpan);

            // Annotate session with start strategy and instance ID
            ISpan annotateSpan = span.startChild("eager.annotate_session", "Annotate session");
            annotateSession(session, correlationId, instance.getInstanceId());
            SentryHelper.finishSuccess(annotateSpan);

            // Complete session setup (labels, deployment ownership, email config)
            ISpan setupSpan = span.startChild("eager.complete_setup", "Complete session setup");
            setupSpan.setData("instance_id", instance.getInstanceId());
            if (!pool.completeSessionSetup(session, appDef, instance, correlationId)) {
                SentryHelper.finishWithOutcome(setupSpan, "failure", SpanStatus.INTERNAL_ERROR);
                SentryHelper.finishWithOutcome(span, "error", SpanStatus.INTERNAL_ERROR);
                return EagerSessionAddedOutcome.ERROR;
            }
            SentryHelper.finishSuccess(setupSpan);

            // Create language server
            languageServerManager.createLanguageServer(session, appDef, Optional.empty(), correlationId);

            // Patch environment variables into the existing Theia deployment
            languageServerManager.patchEnvVarsIntoExistingDeployment(instance.getDeploymentName(), session, appDef, correlationId);

            // Schedule async credential injection via data bridge (tracked in separate transaction)
            if (DataBridgeUtil.isDataBridgeEnabled(appDef.getSpec())) {
                Map<String, String> envVars = sessionEnvCollector.collect(session, correlationId);
                if (!envVars.isEmpty()) {
                    SentryHelper.breadcrumb("Scheduling data bridge injection with " + envVars.size() + " env vars",
                            "databridge");
                    asyncDataInjector.scheduleInjection(session, envVars, correlationId);
                }
            }

            // Add ingress rule
            ISpan ingressRuleSpan = span.startChild("eager.add_ingress_rule", "Add ingress rule");
            String host;
            try {
                host = ingressManager.addRuleForEagerSession(ingress, instance.getExternalService(), appDef,
                        instance.getInstanceId(), correlationId);
                ingressRuleSpan.setData("host", host);
                SentryHelper.finishSuccess(ingressRuleSpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Error while editing ingress"), e);
                SentryHelper.finishError(ingressRuleSpan, e);
                SentryHelper.finishWithOutcome(span, "error", SpanStatus.INTERNAL_ERROR);
                return EagerSessionAddedOutcome.ERROR;
            }

            // Schedule async URL availability check (tracked in separate transaction: session.url_availability)
            SentryHelper.breadcrumb("Scheduling URL availability check for " + host, "session");
            AddedHandlerUtil.updateSessionURLAsync(client.sessions(), session, client.namespace(), host, correlationId);

            span.setData("instance_id", instance.getInstanceId());
            SentryHelper.finishSuccess(span);
            return EagerSessionAddedOutcome.HANDLED;

        } catch (Exception e) {
            SentryHelper.finishError(span, e);
            throw e;
        }
    }

    @Override
    public boolean sessionDeleted(Session session, String correlationId) {
        SessionSpec spec = session.getSpec();
        String sessionName = spec.getName();
        String appDefinitionID = spec.getAppDefinition();

        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null
                ? parentSpan.startChild("eager.cleanup", "Eager session cleanup")
                : Sentry.startTransaction("eager.cleanup", "session");

        span.setData("session_name", sessionName);
        span.setData("app_definition", appDefinitionID);

        LOGGER.info(formatLogMessage(correlationId, "Handling sessionDeleted " + spec));

        try {
            // Find app definition
            ISpan appDefSpan = span.startChild("eager.find_appdef", "Find app definition");
            Optional<AppDefinition> appDefOpt = client.appDefinitions().get(appDefinitionID);
            if (appDefOpt.isEmpty()) {
                LOGGER.info(formatLogMessage(correlationId,
                        "No App Definition found. Resources will be cleaned up by K8s garbage collection."));
                SentryHelper.finishWithOutcome(appDefSpan, "not_found", SpanStatus.NOT_FOUND);
                SentryHelper.finishSuccess(span); // This is OK - K8s GC handles it
                return true;
            }
            AppDefinition appDef = appDefOpt.get();
            SentryHelper.finishSuccess(appDefSpan);

            // Find ingress
            ISpan ingressSpan = span.startChild("eager.find_ingress", "Find ingress");
            Optional<Ingress> ingressOpt = ingressManager.getIngress(appDef, correlationId);
            if (ingressOpt.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId,
                        "No Ingress for app definition " + appDefinitionID + " found."));
                SentryHelper.finishWithOutcome(ingressSpan, "not_found", SpanStatus.NOT_FOUND);
                SentryHelper.finishWithOutcome(span, "failure", SpanStatus.INTERNAL_ERROR);
                return false;
            }
            SentryHelper.finishSuccess(ingressSpan);

            // Get instance ID from session annotation
            Integer instanceId = getInstanceIdFromAnnotation(session);
            if (instanceId == null) {
                LOGGER.error(formatLogMessage(correlationId,
                        "Session missing instance-id annotation. Cannot determine which instance to release."));
                span.setTag("error.reason", "missing_instance_id_annotation");
                SentryHelper.finishWithOutcome(span, "failure", SpanStatus.INTERNAL_ERROR);
                return false;
            }
            span.setData("instance_id", instanceId);

            // Remove ingress rule
            ISpan removeIngressSpan = span.startChild("eager.remove_ingress_rule", "Remove ingress rule");
            removeIngressSpan.setData("instance_id", instanceId);
            try {
                ingressManager.removeRuleForEagerSession(ingressOpt.get(), appDef, instanceId, correlationId);
                SentryHelper.finishSuccess(removeIngressSpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Error while removing ingress rule"), e);
                SentryHelper.finishError(removeIngressSpan, e);
                SentryHelper.finishWithOutcome(span, "failure", SpanStatus.INTERNAL_ERROR);
                return false;
            }

            // Release instance back to pool
            ISpan releaseSpan = span.startChild("eager.release_instance", "Release pool instance");
            releaseSpan.setData("instance_id", instanceId);
            boolean success = pool.releaseInstance(session, appDef, correlationId);
            SentryHelper.finishWithOutcome(releaseSpan, success);

            // Reconcile the specific instance
            ISpan reconcileSpan = span.startChild("eager.reconcile_instance", "Reconcile released instance");
            reconcileSpan.setData("instance_id", instanceId);
            pool.reconcileInstance(appDef, instanceId, correlationId);
            SentryHelper.finishSuccess(reconcileSpan);

            SentryHelper.finishWithOutcome(span, success);
            return success;

        } catch (Exception e) {
            SentryHelper.finishError(span, e);
            throw e;
        }
    }

    private void annotateSession(Session session, String correlationId, int instanceId) {
        String name = session.getMetadata().getName();
        client.sessions().edit(correlationId, name, s -> {
            Map<String, String> annotations = s.getMetadata().getAnnotations();
            if (annotations == null) {
                annotations = new HashMap<>();
                s.getMetadata().setAnnotations(annotations);
            }
            annotations.put(SESSION_START_STRATEGY_ANNOTATION, SESSION_START_STRATEGY_EAGER);
            annotations.put(SESSION_INSTANCE_ID_ANNOTATION, String.valueOf(instanceId));
        });
    }

    private Integer getInstanceIdFromAnnotation(Session session) {
        Map<String, String> annotations = session.getMetadata().getAnnotations();
        if (annotations == null) {
            return null;
        }
        String idStr = annotations.get(SESSION_INSTANCE_ID_ANNOTATION);
        if (idStr == null) {
            return null;
        }
        try {
            return Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
