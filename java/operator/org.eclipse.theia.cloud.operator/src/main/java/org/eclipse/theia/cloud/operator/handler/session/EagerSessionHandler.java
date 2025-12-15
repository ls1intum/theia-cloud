/********************************************************************************
 * Copyright (C) 2022 EclipseSource, Lockular, Ericsson, STMicroelectronics and 
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

import java.time.Instant;
import java.util.Comparator;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.util.LabelsUtil;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.eclipse.theia.cloud.operator.ingress.IngressPathProvider;
import org.eclipse.theia.cloud.operator.util.K8sUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudConfigMapUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudDeploymentUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudHandlerUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudK8sUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudServiceUtil;

import com.google.inject.Inject;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceList;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressPath;
import io.fabric8.kubernetes.api.model.networking.v1.HTTPIngressRuleValue;
import io.fabric8.kubernetes.api.model.networking.v1.Ingress;
import io.fabric8.kubernetes.api.model.networking.v1.IngressBackend;
import io.fabric8.kubernetes.api.model.networking.v1.IngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.IngressServiceBackend;
import io.fabric8.kubernetes.api.model.networking.v1.ServiceBackendPort;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.ServiceResource;

/**
 * A {@link SessionAddedHandler} that relies on the fact that the app definition handler created spare deployments to
 * use.
 */
public class EagerSessionHandler implements SessionHandler {

    public static final String EAGER_START_REFRESH_ANNOTATION = "theia-cloud.io/eager-start-refresh";
    public static final String SESSION_START_STRATEGY_ANNOTATION = "theia-cloud.io/session-start-strategy";
    public static final String SESSION_START_STRATEGY_EAGER = "eager";

    private static final Logger LOGGER = LogManager.getLogger(EagerSessionHandler.class);

    public static enum EagerSessionAddedOutcome {
        HANDLED,
        NO_CAPACITY,
        ERROR
    }

    protected static class ReservedServicePair {
        public final Service externalService;
        public final Service internalService;
        public final int instance;
        public final boolean alreadyReserved;

        public ReservedServicePair(Service externalService, Service internalService, int instance,
                boolean alreadyReserved) {
            this.externalService = externalService;
            this.internalService = internalService;
            this.instance = instance;
            this.alreadyReserved = alreadyReserved;
        }
    }

    protected static class ReserveServicePairResult {
        public final EagerSessionAddedOutcome outcome;
        public final ReservedServicePair pair;

        public ReserveServicePairResult(EagerSessionAddedOutcome outcome, ReservedServicePair pair) {
            this.outcome = outcome;
            this.pair = pair;
        }

        public static ReserveServicePairResult handled(ReservedServicePair pair) {
            return new ReserveServicePairResult(EagerSessionAddedOutcome.HANDLED, pair);
        }

        public static ReserveServicePairResult noCapacity() {
            return new ReserveServicePairResult(EagerSessionAddedOutcome.NO_CAPACITY, null);
        }

        public static ReserveServicePairResult error() {
            return new ReserveServicePairResult(EagerSessionAddedOutcome.ERROR, null);
        }
    }

    @Inject
    private TheiaCloudClient client;

    @Inject
    protected IngressPathProvider ingressPathProvider;

    @Inject
    protected TheiaCloudOperatorArguments arguments;

    @Override
    public boolean sessionAdded(Session session, String correlationId) {
        return trySessionAdded(session, correlationId) == EagerSessionAddedOutcome.HANDLED;
    }

    public EagerSessionAddedOutcome trySessionAdded(Session session, String correlationId) {
        SessionSpec spec = session.getSpec();
        LOGGER.info(formatLogMessage(correlationId, "Handling sessionAdded " + spec));

        String sessionResourceName = session.getMetadata().getName();
        String sessionResourceUID = session.getMetadata().getUid();

        String appDefinitionID = spec.getAppDefinition();
        String userEmail = spec.getUser();

        /* find app definition for session */
        Optional<AppDefinition> appDefinition = client.appDefinitions().get(appDefinitionID);
        if (appDefinition.isEmpty()) {
            LOGGER.error(formatLogMessage(correlationId, "No App Definition with name " + appDefinitionID + " found."));
            return EagerSessionAddedOutcome.ERROR;
        }

        String appDefinitionResourceName = appDefinition.get().getMetadata().getName();
        String appDefinitionResourceUID = appDefinition.get().getMetadata().getUid();
        int port = appDefinition.get().getSpec().getPort();

        /* find ingress */
        Optional<Ingress> ingress = K8sUtil.getExistingIngress(client.kubernetes(), client.namespace(),
                appDefinitionResourceName, appDefinitionResourceUID);
        if (ingress.isEmpty()) {
            LOGGER.error(
                    formatLogMessage(correlationId, "No Ingress for app definition " + appDefinitionID + " found."));
            return EagerSessionAddedOutcome.ERROR;
        }

        /* reserve a matching external+internal service pair (same instance) */
        ReserveServicePairResult reserveServicePair = reserveServicePair(client.kubernetes(), client.namespace(),
                appDefinitionResourceName, appDefinitionResourceUID, appDefinitionID, sessionResourceName,
                sessionResourceUID, correlationId);
        if (reserveServicePair.outcome != EagerSessionAddedOutcome.HANDLED) {
            return reserveServicePair.outcome;
        }

        Service externalServiceToUse = reserveServicePair.pair.externalService;
        Service internalServiceToUse = reserveServicePair.pair.internalService;
        int instance = reserveServicePair.pair.instance;

        annotateSessionStrategy(session, correlationId, SESSION_START_STRATEGY_EAGER);

        try {
            client.services().inNamespace(client.namespace()).withName(externalServiceToUse.getMetadata().getName())
                    .edit(service -> {
                        LOGGER.debug("Setting session labels");
                        Map<String, String> labels = service.getMetadata().getLabels();
                        if (labels == null) {
                            labels = new HashMap<>();
                            service.getMetadata().setLabels(labels);
                        }
                        Map<String, String> newLabels = LabelsUtil.createSessionLabels(session, appDefinition.get());
                        labels.putAll(newLabels);
                        return service;
                    });
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Error while adding labels to service " + (externalServiceToUse.getMetadata().getName())), e);
            return EagerSessionAddedOutcome.ERROR;
        }

        try {
            client.services().inNamespace(client.namespace())
                    .withName(internalServiceToUse.getMetadata().getName()).edit(service -> {
                        LOGGER.debug("Setting session labels on internal service");
                        Map<String, String> labels = service.getMetadata().getLabels();
                        if (labels == null) {
                            labels = new HashMap<>();
                            service.getMetadata().setLabels(labels);
                        }
                        Map<String, String> newLabels = LabelsUtil.createSessionLabels(session, appDefinition.get());
                        labels.putAll(newLabels);
                        return service;
                    });
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while adding labels to internal service "
                    + (internalServiceToUse.getMetadata().getName())), e);
            return EagerSessionAddedOutcome.ERROR;
        }

        /* get the deployment for the service and add as owner */
        final String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDefinition.get(), instance);
        try {
            client.kubernetes().apps().deployments().withName(deploymentName).edit(deployment -> TheiaCloudHandlerUtil
                    .addOwnerReferenceToItem(correlationId, sessionResourceName, sessionResourceUID, deployment));
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while editing deployment "
                    + (appDefinitionID + TheiaCloudDeploymentUtil.DEPLOYMENT_NAME + instance)), e);
            return EagerSessionAddedOutcome.ERROR;
        }

        if (arguments.isUseKeycloak()) {
            /* add user to allowed emails */
            try {
                client.kubernetes().configMaps()
                        .withName(TheiaCloudConfigMapUtil.getEmailConfigName(appDefinition.get(), instance))
                        .edit(configmap -> {
                            configmap.setData(Collections
                                    .singletonMap(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST, userEmail));
                            return configmap;
                        });

            } catch (KubernetesClientException e) {
                LOGGER.error(
                        formatLogMessage(correlationId,
                                "Error while editing email configmap "
                                        + (appDefinitionID + TheiaCloudConfigMapUtil.CONFIGMAP_EMAIL_NAME + instance)),
                        e);
                return EagerSessionAddedOutcome.ERROR;
            }

            // Add/update annotation to the session pod to trigger a sync with the Kubelet.
            // Otherwise, the pod might not be updated with the new email list for the OAuth proxy in time.
            // This is the case because ConfigMap changes are not propagated to the pod immediately but during a
            // periodic sync. See
            // https://kubernetes.io/docs/concepts/configuration/configmap/#mounted-configmaps-are-updated-automatically
            // NOTE that this is still not a one hundred percent guarantee that the pod is updated in time.
            try {
                LOGGER.info(formatLogMessage(correlationId, "Adding update annotation to pods..."));
                client.kubernetes().pods().list().getItems().forEach(pod -> {
                    // Use startsWith because the actual owner is the deployment's ReplicaSet
                    // whose name starts with the deployment name
                    if (pod.getMetadata().getOwnerReferences().stream()
                            .anyMatch(or -> or.getName().startsWith(deploymentName))) {

                        LOGGER.debug(formatLogMessage(correlationId,
                                "Adding update annotation to pod " + pod.getMetadata().getName()));
                        pod.getMetadata().getAnnotations().put(EAGER_START_REFRESH_ANNOTATION,
                                Instant.now().toString());
                        // Apply the changes
                        PodResource podResource = client.pods().withName(pod.getMetadata().getName());
                        podResource.edit(p -> pod);
                        LOGGER.debug(formatLogMessage(correlationId,
                                "Added update annotation to pod " + pod.getMetadata().getName()));
                    }
                });
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Error while editing pod annotations"), e);
                return EagerSessionAddedOutcome.ERROR;
            }
        }

        /* adjust the ingress */
        String host;
        try {
            host = updateIngress(ingress, Optional.of(externalServiceToUse), appDefinitionID, instance, port,
                    appDefinition.get(),
                    correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Error while editing ingress " + ingress.get().getMetadata().getName()), e);
            return EagerSessionAddedOutcome.ERROR;
        }

        /* Update session resource */
        try {
            AddedHandlerUtil.updateSessionURLAsync(client.sessions(), session, client.namespace(), host, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(
                    formatLogMessage(correlationId, "Error while editing session " + session.getMetadata().getName()),
                    e);
            return EagerSessionAddedOutcome.ERROR;
        }

        return EagerSessionAddedOutcome.HANDLED;
    }

    protected void annotateSessionStrategy(Session session, String correlationId, String strategy) {
        String name = session.getMetadata().getName();
        client.sessions().edit(correlationId, name, s -> {
            Map<String, String> annotations = s.getMetadata().getAnnotations();
            if (annotations == null) {
                annotations = new HashMap<>();
                s.getMetadata().setAnnotations(annotations);
            }
            annotations.put(SESSION_START_STRATEGY_ANNOTATION, strategy);
        });
    }

    protected void rollbackServiceReservation(Service service, String sessionResourceName, String sessionResourceUID,
            String correlationId) {
        try {
            client.services().withName(service.getMetadata().getName()).edit(s -> {
                TheiaCloudHandlerUtil.removeOwnerReferenceFromItem(correlationId, sessionResourceName,
                        sessionResourceUID, s);
                return s;
            });
        } catch (KubernetesClientException e) {
            LOGGER.warn(formatLogMessage(correlationId,
                    "Failed to roll back service reservation for " + service.getMetadata().getName()), e);
        }
    }

    protected synchronized ReserveServicePairResult reserveServicePair(NamespacedKubernetesClient k8sClient,
            String namespace, String appDefinitionResourceName, String appDefinitionResourceUID, String appDefinitionID,
            String sessionResourceName, String sessionResourceUID, String correlationId) {

        List<Service> existingServices = K8sUtil.getExistingServices(k8sClient, namespace, appDefinitionResourceName,
                appDefinitionResourceUID);

        List<Service> externalServices = existingServices.stream()
                .filter(service -> !service.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
        List<Service> internalServices = existingServices.stream()
                .filter(service -> service.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());

        Optional<Service> alreadyReservedExternal = TheiaCloudServiceUtil.getServiceOwnedBySession(sessionResourceName,
                sessionResourceUID, externalServices);
        Optional<Service> alreadyReservedInternal = TheiaCloudServiceUtil.getServiceOwnedBySession(sessionResourceName,
                sessionResourceUID, internalServices);

        if (alreadyReservedExternal.isPresent() && alreadyReservedInternal.isPresent()) {
            Integer extInstance = parseInstanceId(alreadyReservedExternal.get());
            Integer intInstance = parseInstanceId(alreadyReservedInternal.get());
            if (extInstance == null || intInstance == null || extInstance.intValue() != intInstance.intValue()) {
                LOGGER.error(formatLogMessage(correlationId,
                        "Session has already reserved services but instances do not match. external="
                                + alreadyReservedExternal.get().getMetadata().getName() + ", internal="
                                + alreadyReservedInternal.get().getMetadata().getName()));
                return ReserveServicePairResult.error();
            }
            return ReserveServicePairResult.handled(new ReservedServicePair(alreadyReservedExternal.get(),
                    alreadyReservedInternal.get(), extInstance.intValue(), true));
        }

        Map<Integer, Service> externalByInstance = new HashMap<>();
        for (Service service : externalServices) {
            Integer id = parseInstanceId(service);
            if (id != null) {
                externalByInstance.putIfAbsent(id, service);
            }
        }
        Map<Integer, Service> internalByInstance = new HashMap<>();
        for (Service service : internalServices) {
            Integer id = parseInstanceId(service);
            if (id != null) {
                internalByInstance.putIfAbsent(id, service);
            }
        }

        // Partial reservation: try to complete it or roll back.
        if (alreadyReservedExternal.isPresent() ^ alreadyReservedInternal.isPresent()) {
            Service reserved = alreadyReservedExternal.isPresent() ? alreadyReservedExternal.get()
                    : alreadyReservedInternal.get();
            Integer instance = parseInstanceId(reserved);
            if (instance == null) {
                LOGGER.error(formatLogMessage(correlationId,
                        "Cannot determine instance id from reserved service name: "
                                + reserved.getMetadata().getName()));
                rollbackServiceReservation(reserved, sessionResourceName, sessionResourceUID, correlationId);
                return ReserveServicePairResult.error();
            }

            // Find counterpart service by instance
            Service counterpart = alreadyReservedExternal.isPresent() ? internalByInstance.get(instance)
                    : externalByInstance.get(instance);

            if (counterpart == null || !TheiaCloudServiceUtil.isUnusedService(counterpart)) {
                LOGGER.warn(formatLogMessage(correlationId,
                        "Session has a partial reservation; rolling back to avoid stuck pool slot. reserved="
                                + reserved.getMetadata().getName()));
                rollbackServiceReservation(reserved, sessionResourceName, sessionResourceUID, correlationId);
                return ReserveServicePairResult.noCapacity();
            }

            try {
                k8sClient.services().inNamespace(namespace).withName(counterpart.getMetadata().getName())
                        .edit(service -> TheiaCloudHandlerUtil.addOwnerReferenceToItem(correlationId,
                                sessionResourceName, sessionResourceUID, service));
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId,
                        "Error while completing partial reservation by editing service "
                                + counterpart.getMetadata().getName()),
                        e);
                rollbackServiceReservation(reserved, sessionResourceName, sessionResourceUID, correlationId);
                return ReserveServicePairResult.error();
            }

            Service ext = alreadyReservedExternal.isPresent() ? reserved : counterpart;
            Service in = alreadyReservedInternal.isPresent() ? reserved : counterpart;
            return ReserveServicePairResult.handled(new ReservedServicePair(ext, in, instance, true));
        }

        // Compute available instances (must have both services unused)
        List<Integer> availableInstanceIds = externalByInstance.entrySet().stream()
                .filter(e -> TheiaCloudServiceUtil.isUnusedService(e.getValue()))
                .filter(e -> {
                    Service internal = internalByInstance.get(e.getKey());
                    return internal != null && TheiaCloudServiceUtil.isUnusedService(internal);
                })
                .map(Entry::getKey)
                .sorted(Comparator.naturalOrder())
                .collect(Collectors.toList());

        if (availableInstanceIds.isEmpty()) {
            LOGGER.info(formatLogMessage(correlationId,
                    "No prewarmed service pairs available for app definition " + appDefinitionID));
            return ReserveServicePairResult.noCapacity();
        }

        int chosenInstance = availableInstanceIds.get(0);
        Service chosenExternal = externalByInstance.get(chosenInstance);
        Service chosenInternal = internalByInstance.get(chosenInstance);

        // Reserve both services; rollback if only one reservation succeeds
        try {
            k8sClient.services().inNamespace(namespace).withName(chosenExternal.getMetadata().getName())
                    .edit(service -> TheiaCloudHandlerUtil.addOwnerReferenceToItem(correlationId, sessionResourceName,
                            sessionResourceUID, service));
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Error while reserving external service " + chosenExternal.getMetadata().getName()), e);
            return ReserveServicePairResult.error();
        }

        try {
            k8sClient.services().inNamespace(namespace).withName(chosenInternal.getMetadata().getName())
                    .edit(service -> TheiaCloudHandlerUtil.addOwnerReferenceToItem(correlationId, sessionResourceName,
                            sessionResourceUID, service));
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Error while reserving internal service " + chosenInternal.getMetadata().getName()), e);
            rollbackServiceReservation(chosenExternal, sessionResourceName, sessionResourceUID, correlationId);
            return ReserveServicePairResult.error();
        }

        return ReserveServicePairResult.handled(
                new ReservedServicePair(chosenExternal, chosenInternal, chosenInstance, false));
    }

    protected Integer parseInstanceId(Service service) {
        String id = TheiaCloudK8sUtil.extractIdFromName(service.getMetadata());
        if (id == null || id.isBlank()) {
            return null;
        }
        try {
            return Integer.valueOf(id);
        } catch (NumberFormatException e) {
            return null;
        }
    }


    protected synchronized String updateIngress(Optional<Ingress> ingress, Optional<Service> serviceToUse,
            String appDefinitionID, int instance, int port, AppDefinition appDefinition, String correlationId) {
        final String host = arguments.getInstancesHost();
        String path = ingressPathProvider.getPath(appDefinition, instance);
        client.ingresses().edit(correlationId, ingress.get().getMetadata().getName(),
                ingressToUpdate -> addIngressRule(ingressToUpdate, serviceToUse.get(), host, port, path));
        return host + path + "/";
    }

    protected Ingress addIngressRule(Ingress ingress, Service serviceToUse, String host, int port, String path) {
        IngressRule ingressRule = new IngressRule();
        ingress.getSpec().getRules().add(ingressRule);

        ingressRule.setHost(host);

        HTTPIngressRuleValue http = new HTTPIngressRuleValue();
        ingressRule.setHttp(http);

        HTTPIngressPath httpIngressPath = new HTTPIngressPath();
        http.getPaths().add(httpIngressPath);
        httpIngressPath.setPath(path + AddedHandlerUtil.INGRESS_REWRITE_PATH);
        httpIngressPath.setPathType(AddedHandlerUtil.INGRESS_PATH_TYPE);

        IngressBackend ingressBackend = new IngressBackend();
        httpIngressPath.setBackend(ingressBackend);

        IngressServiceBackend ingressServiceBackend = new IngressServiceBackend();
        ingressBackend.setService(ingressServiceBackend);
        ingressServiceBackend.setName(serviceToUse.getMetadata().getName());

        ServiceBackendPort serviceBackendPort = new ServiceBackendPort();
        ingressServiceBackend.setPort(serviceBackendPort);
        serviceBackendPort.setNumber(port);

        return ingress;
    }

    @Override
    public boolean sessionDeleted(Session session, String correlationId) {
        SessionSpec spec = session.getSpec();
        LOGGER.info(formatLogMessage(correlationId, "Handling sessionDeleted " + spec));

        // Find app definition for session. If it's not there anymore, we don't need to clean up because the resources
        // are deleted by Kubernetes garbage collection.
        String appDefinitionID = spec.getAppDefinition();
        Optional<AppDefinition> appDefinition = client.appDefinitions().get(appDefinitionID);
        if (appDefinition.isEmpty()) {
            LOGGER.info(formatLogMessage(correlationId, "No App Definition with name " + appDefinitionID
                    + " found. Thus, no cleanup is needed because associated resources are deleted by Kubernets garbage collecion."));
            return true;
        }

        // Find external and internal services by first filtering all services by the session's corresponding session
        // labels (as added in
        // sessionCreated) and then checking if the service has an owner reference to the session
        String sessionResourceName = session.getMetadata().getName();
        String sessionResourceUID = session.getMetadata().getUid();
        Map<String, String> sessionLabels = LabelsUtil.createSessionLabels(session, appDefinition.get());
        // Filtering by withLabels(sessionLabels) because the method requires an exact match of the labels.
        // Additional labels on the service prevent a match and the service has an additional app label.
        // Thus, filter by each session label separately.
        // We rely on the fact that the session labels are unique for each session.
        // We cannot rely on owner references because they might have been cleaned up automatically by Kubernetes.
        // While this should not happen, it did on Minikube.
        FilterWatchListDeletable<Service, ServiceList, ServiceResource<Service>> servicesFilter = client.services();
        for (Entry<String, String> entry : sessionLabels.entrySet()) {
            servicesFilter = servicesFilter.withLabel(entry.getKey(), entry.getValue());
        }
        List<Service> services = servicesFilter.list().getItems();
        if (services.isEmpty()) {
            LOGGER.error(formatLogMessage(correlationId, "No Services owned by session " + spec.getName() + " found."));
            return false;
        }

        // Separate external and internal services
        List<Service> externalServices = services.stream()
                .filter(service -> !service.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
        List<Service> internalServices = services.stream()
                .filter(service -> service.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());

        if (externalServices.size() != 1) {
            LOGGER.error(formatLogMessage(correlationId, "Expected exactly one external service owned by session "
                    + spec.getName() + " but found " + externalServices.size()));
            return false;
        }
        if (internalServices.size() != 1) {
            LOGGER.error(formatLogMessage(correlationId, "Expected exactly one internal service owned by session "
                    + spec.getName() + " but found " + internalServices.size()));
            return false;
        }

        Service ownedService = externalServices.get(0);
        Service ownedInternalService = internalServices.get(0);
        String serviceName = ownedService.getMetadata().getName();

        // Remove owner reference and user specific labels from the service
        // Allow retries because in rare cases the update fails. It is not clear why but might be caused by the owner
        // reference being removed by Kubernetes garbage collection.
        // The retries aim to stabilize the clean up process.
        Service cleanedService = null;
        int editServiceAttempts = 0;
        boolean editServiceSuccess = false;
        while (editServiceAttempts < 3 && !editServiceSuccess) {
            try {
                cleanedService = client.services().withName(serviceName).edit(service -> {
                    TheiaCloudHandlerUtil.removeOwnerReferenceFromItem(correlationId, sessionResourceName,
                            sessionResourceUID, service);
                    service.getMetadata().getLabels().keySet().removeAll(LabelsUtil.getSessionSpecificLabelKeys());
                    return service;
                });
                LOGGER.info(formatLogMessage(correlationId,
                        "Removed owner reference and user-specific session labels from service: " + serviceName));
                editServiceSuccess = true;
            } catch (KubernetesClientException e) {
                editServiceAttempts++;
                if (editServiceAttempts < 3) {
                    LOGGER.warn(
                            formatLogMessage(correlationId,
                                    "Attempt " + editServiceAttempts + " failed while editing service " + serviceName),
                            e);
                } else {
                    LOGGER.error(formatLogMessage(correlationId, "Error while editing service " + serviceName
                            + " after " + editServiceAttempts + " attempts"), e);
                    return false;
                }
            }
        }

        // Remove owner reference and user specific labels from the internal service
        String internalServiceName = ownedInternalService.getMetadata().getName();
        int editInternalServiceAttempts = 0;
        boolean editInternalServiceSuccess = false;
        while (editInternalServiceAttempts < 3 && !editInternalServiceSuccess) {
            try {
                client.services().withName(internalServiceName).edit(service -> {
                    TheiaCloudHandlerUtil.removeOwnerReferenceFromItem(correlationId, sessionResourceName,
                            sessionResourceUID, service);
                    service.getMetadata().getLabels().keySet().removeAll(LabelsUtil.getSessionSpecificLabelKeys());
                    return service;
                });
                LOGGER.info(formatLogMessage(correlationId,
                        "Removed owner reference and user-specific session labels from internal service: "
                                + internalServiceName));
                editInternalServiceSuccess = true;
            } catch (KubernetesClientException e) {
                editInternalServiceAttempts++;
                if (editInternalServiceAttempts < 3) {
                    LOGGER.warn(formatLogMessage(correlationId, "Attempt " + editInternalServiceAttempts
                            + " failed while editing internal service " + internalServiceName), e);
                } else {
                    LOGGER.error(formatLogMessage(correlationId, "Error while editing internal service "
                            + internalServiceName + " after " + editInternalServiceAttempts + " attempts"), e);
                    return false;
                }
            }
        }
        Integer instance = TheiaCloudServiceUtil.getId(correlationId, appDefinition.get(), cleanedService);

        // Cleanup ingress rule to prevent further traffic to the session pod
        Optional<Ingress> ingress = K8sUtil.getExistingIngress(client.kubernetes(), client.namespace(),
                appDefinition.get().getMetadata().getName(), appDefinition.get().getMetadata().getUid());
        if (ingress.isEmpty()) {
            LOGGER.error(
                    formatLogMessage(correlationId, "No Ingress for app definition " + appDefinitionID + " found."));
            return false;
        }
        // Remove ingress rule
        try {
            removeIngressRule(correlationId, appDefinition.get(), ingress.get(), instance);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Error while editing ingress " + ingress.get().getMetadata().getName()), e);
            return false;
        }

        // Remove owner reference from deployment
        if (instance == null) {
            LOGGER.error(formatLogMessage(correlationId, "Error while getting instance from Service"));
            return false;
        }
        final String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDefinition.get(), instance);
        try {
            client.kubernetes().apps().deployments().withName(deploymentName).edit(deployment -> TheiaCloudHandlerUtil
                    .removeOwnerReferenceFromItem(correlationId, sessionResourceName, sessionResourceUID, deployment));
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while editing deployment "
                    + (appDefinitionID + TheiaCloudDeploymentUtil.DEPLOYMENT_NAME + instance)), e);
            return false;
        }

        // Remove user from allowed emails in config map
        try {
            client.kubernetes().configMaps()
                    .withName(TheiaCloudConfigMapUtil.getEmailConfigName(appDefinition.get(), instance))
                    .edit(configmap -> {
                        configmap.setData(
                                Collections.singletonMap(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST, null));
                        return configmap;
                    });
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while editing email configmap "
                    + (appDefinitionID + TheiaCloudConfigMapUtil.CONFIGMAP_EMAIL_NAME + instance)), e);
            return false;
        }

        // Delete the pod to clean temporary workspace files. The deployment recreates a fresh pod automatically.
        try {
            Optional<Pod> pod = client.kubernetes().pods().list().getItems().stream()
                    .filter(p -> p.getMetadata().getName().startsWith(deploymentName)).findAny();
            if (pod.isPresent()) {
                LOGGER.info(formatLogMessage(correlationId, "Deleting pod " + pod.get().getMetadata().getName()));
                client.pods().withName(pod.get().getMetadata().getName()).delete();
            }
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while deleting pod"), e);
            return false;
        }

        return true;
    }

    protected synchronized void removeIngressRule(String correlationId, AppDefinition appDefinition, Ingress ingress,
            Integer instance) throws KubernetesClientException {
        final String ruleHttpPath = ingressPathProvider.getPath(appDefinition, instance)
                + AddedHandlerUtil.INGRESS_REWRITE_PATH;
        client.ingresses().resource(ingress.getMetadata().getName()).edit(ingressToUpdate -> {
            ingressToUpdate.getSpec().getRules().removeIf(rule -> {
                if (rule.getHttp() == null) {
                    LOGGER.warn(formatLogMessage(correlationId,
                            "Error while removing ingress rule: The rule's HTTP block is null"));
                    return false;
                }
                return rule.getHttp().getPaths().stream().anyMatch(httpPath -> ruleHttpPath.equals(httpPath.getPath()));
            });
            return ingressToUpdate;
        });
    }
}
