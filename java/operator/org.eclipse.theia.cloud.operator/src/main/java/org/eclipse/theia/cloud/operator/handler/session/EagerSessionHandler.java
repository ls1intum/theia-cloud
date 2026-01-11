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
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.eclipse.theia.cloud.operator.ingress.IngressManager;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool.PoolInstance;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool.ReservationOutcome;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool.ReservationResult;

import com.google.inject.Inject;

import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.client.KubernetesClientException;

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
        LOGGER.info(formatLogMessage(correlationId, "Handling sessionAdded " + spec));

        // Find app definition
        String appDefinitionID = spec.getAppDefinition();
        Optional<AppDefinition> appDefOpt = client.appDefinitions().get(appDefinitionID);
        if (appDefOpt.isEmpty()) {
            LOGGER.error(formatLogMessage(correlationId, "No App Definition with name " + appDefinitionID + " found."));
            return EagerSessionAddedOutcome.ERROR;
        }
        AppDefinition appDef = appDefOpt.get();

        // Find ingress
        Optional<Ingress> ingressOpt = ingressManager.getIngress(appDef, correlationId);
        if (ingressOpt.isEmpty()) {
            LOGGER.error(
                    formatLogMessage(correlationId, "No Ingress for app definition " + appDefinitionID + " found."));
            return EagerSessionAddedOutcome.ERROR;
        }
        Ingress ingress = ingressOpt.get();

        // Reserve an instance from the pool
        ReservationResult reservation = pool.reserveInstance(session, appDef, correlationId);
        if (reservation.getOutcome() == ReservationOutcome.NO_CAPACITY) {
            return EagerSessionAddedOutcome.NO_CAPACITY;
        }
        if (reservation.getOutcome() == ReservationOutcome.ERROR) {
            return EagerSessionAddedOutcome.ERROR;
        }

        PoolInstance instance = reservation.getInstance().get();

        // Annotate session with start strategy and instance ID
        annotateSession(session, correlationId, instance.getInstanceId());

        // Complete session setup (labels, deployment ownership, email config)
        if (!pool.completeSessionSetup(session, appDef, instance, correlationId)) {
            return EagerSessionAddedOutcome.ERROR;
        }

        // Add ingress rule
        String host;
        try {
            host = ingressManager.addRuleForEagerSession(ingress, instance.getExternalService(), appDef,
                    instance.getInstanceId(), correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while editing ingress"), e);
            return EagerSessionAddedOutcome.ERROR;
        }

        // Update session URL
        try {
            AddedHandlerUtil.updateSessionURLAsync(client.sessions(), session, client.namespace(), host, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while updating session URL"), e);
            return EagerSessionAddedOutcome.ERROR;
        }

        return EagerSessionAddedOutcome.HANDLED;
    }

    @Override
    public boolean sessionDeleted(Session session, String correlationId) {
        SessionSpec spec = session.getSpec();
        LOGGER.info(formatLogMessage(correlationId, "Handling sessionDeleted " + spec));

        // Find app definition
        String appDefinitionID = spec.getAppDefinition();
        Optional<AppDefinition> appDefOpt = client.appDefinitions().get(appDefinitionID);
        if (appDefOpt.isEmpty()) {
            LOGGER.info(formatLogMessage(correlationId,
                    "No App Definition found. Resources will be cleaned up by K8s garbage collection."));
            return true;
        }
        AppDefinition appDef = appDefOpt.get();

        // Find ingress
        Optional<Ingress> ingressOpt = ingressManager.getIngress(appDef, correlationId);
        if (ingressOpt.isEmpty()) {
            LOGGER.error(
                    formatLogMessage(correlationId, "No Ingress for app definition " + appDefinitionID + " found."));
            return false;
        }

        // Get instance ID from session annotation
        Integer instanceId = getInstanceIdFromAnnotation(session);
        if (instanceId == null) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Session missing instance-id annotation. Cannot determine which instance to release."));
            return false;
        }

        // Remove ingress rule
        try {
            ingressManager.removeRuleForEagerSession(ingressOpt.get(), appDef, instanceId, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while removing ingress rule"), e);
            return false;
        }

        // Release instance back to pool
        boolean success = pool.releaseInstance(session, appDef, correlationId);

        // Reconcile the specific instance
        pool.reconcileInstance(appDef, instanceId, correlationId);

        return success;
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
