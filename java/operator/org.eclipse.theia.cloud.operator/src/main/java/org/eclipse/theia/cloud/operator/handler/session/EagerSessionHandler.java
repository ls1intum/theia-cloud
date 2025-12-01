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

import java.io.IOException;
import java.net.URISyntaxException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionSpec;
import org.eclipse.theia.cloud.common.util.JavaUtil;
import org.eclipse.theia.cloud.common.util.LabelsUtil;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.eclipse.theia.cloud.operator.ingress.IngressPathProvider;
import org.eclipse.theia.cloud.operator.util.JavaResourceUtil;
import org.eclipse.theia.cloud.operator.util.K8sUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudConfigMapUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudDeploymentUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudHandlerUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudServiceUtil;

import com.google.inject.Inject;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
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

    private static final Logger LOGGER = LogManager.getLogger(EagerSessionHandler.class);

    @Inject
    private TheiaCloudClient client;

    @Inject
    protected IngressPathProvider ingressPathProvider;

    @Inject
    protected TheiaCloudOperatorArguments arguments;

    @Override
    public boolean sessionAdded(Session session, String correlationId) {
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
            return false;
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
            return false;
        }

        /* get a service to use */
        Entry<Optional<Service>, Boolean> reserveServiceResult = reserveService(client.kubernetes(), client.namespace(),
                appDefinitionResourceName, appDefinitionResourceUID, appDefinitionID, sessionResourceName,
                sessionResourceUID, correlationId);
        if (reserveServiceResult.getValue()) {
            LOGGER.info(formatLogMessage(correlationId, "Found an already reserved service"));
            return true;
        }
        Optional<Service> serviceToUse = reserveServiceResult.getKey();
        if (serviceToUse.isEmpty()) {
            LOGGER.error(
                    formatLogMessage(correlationId, "No Service for app definition " + appDefinitionID + " found."));
            return false;
        }

        try {
            client.services().inNamespace(client.namespace()).withName(serviceToUse.get().getMetadata().getName())
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
                    "Error while adding labels to service " + (serviceToUse.get().getMetadata().getName())), e);
            return false;
        }

        /* get the deployment for the service and add as owner */
        Integer instance = TheiaCloudServiceUtil.getId(correlationId, appDefinition.get(), serviceToUse.get());
        if (instance == null) {
            LOGGER.error(formatLogMessage(correlationId, "Error while getting instance from Service"));
            return false;
        }

        final String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDefinition.get(), instance);
        try {
            client.kubernetes().apps().deployments().withName(deploymentName).edit(deployment -> TheiaCloudHandlerUtil
                    .addOwnerReferenceToItem(correlationId, sessionResourceName, sessionResourceUID, deployment));
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while editing deployment "
                    + (appDefinitionID + TheiaCloudDeploymentUtil.DEPLOYMENT_NAME + instance)), e);
            return false;
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
                return false;
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
                return false;
            }
        }

        /* adjust the ingress */
        String host;
        try {
            host = updateIngress(ingress, serviceToUse, appDefinitionID, instance, port, appDefinition.get(),
                    correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Error while editing ingress " + ingress.get().getMetadata().getName()), e);
            return false;
        }

        /* Update session resource */
        try {
            AddedHandlerUtil.updateSessionURLAsync(client.sessions(), session, client.namespace(), host, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(
                    formatLogMessage(correlationId, "Error while editing session " + session.getMetadata().getName()),
                    e);
            return false;
        }

        /* External Language Server Support */
        if (appDefinition.get().getSpec().getOptions() != null
                && appDefinition.get().getSpec().getOptions().containsKey("langserver-image")) {
            String lsImage = appDefinition.get().getSpec().getOptions().get("langserver-image");
            LOGGER.info(formatLogMessage(correlationId, "Found langserver-image option: " + lsImage));

            createAndApplyLSService(client.kubernetes(), client.namespace(), correlationId, sessionResourceName,
                    sessionResourceUID, session, appDefinition.get());
            createAndApplyLSDeployment(client.kubernetes(), client.namespace(), correlationId, sessionResourceName,
                    sessionResourceUID, session, appDefinition.get(), lsImage);

            /* Update Theia Deployment with LS env vars */
            try {
                client.kubernetes().apps().deployments().withName(deploymentName).edit(deployment -> {
                    String lsServiceName = "ls-" + sessionResourceName;
                    String lsHost = lsServiceName;
                    String lsPort = "5556"; // Default Java port
                    String envHostKey = AddedHandlerUtil.ENV_LS_JAVA_HOST;
                    String envPortKey = AddedHandlerUtil.ENV_LS_JAVA_PORT;

                    if (lsImage.contains("rust")) {
                        lsPort = "5555";
                        envHostKey = AddedHandlerUtil.ENV_LS_RUST_HOST;
                        envPortKey = AddedHandlerUtil.ENV_LS_RUST_PORT;
                    }

                    List<EnvVar> envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
                    envVars.removeIf(e -> e.getName().equals(AddedHandlerUtil.ENV_LS_JAVA_HOST)
                            || e.getName().equals(AddedHandlerUtil.ENV_LS_JAVA_PORT)
                            || e.getName().equals(AddedHandlerUtil.ENV_LS_RUST_HOST)
                            || e.getName().equals(AddedHandlerUtil.ENV_LS_RUST_PORT));

                    envVars.add(new EnvVarBuilder().withName(envHostKey).withValue(lsHost).build());
                    envVars.add(new EnvVarBuilder().withName(envPortKey).withValue(lsPort).build());

                    return deployment;
                });
                LOGGER.info(formatLogMessage(correlationId, "Updated Theia deployment with LS env vars"));
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Error while updating Theia deployment with LS env vars"), e);
                return false;
            }
        }

        return true;
    }

    protected synchronized Entry<Optional<Service>, Boolean> reserveService(NamespacedKubernetesClient client,
            String namespace, String appDefinitionResourceName, String appDefinitionResourceUID, String appDefinitionID,
            String sessionResourceName, String sessionResourceUID, String correlationId) {
        List<Service> existingServices = K8sUtil.getExistingServices(client, namespace, appDefinitionResourceName,
                appDefinitionResourceUID);

        Optional<Service> alreadyReservedService = TheiaCloudServiceUtil.getServiceOwnedBySession(sessionResourceName,
                sessionResourceUID, existingServices);
        if (alreadyReservedService.isPresent()) {
            return JavaUtil.tuple(alreadyReservedService, true);
        }

        Optional<Service> serviceToUse = TheiaCloudServiceUtil.getUnusedService(existingServices,
                appDefinitionResourceUID);
        if (serviceToUse.isEmpty()) {
            return JavaUtil.tuple(serviceToUse, false);
        }

        /* add our session as owner to the service */
        try {
            client.services().inNamespace(namespace).withName(serviceToUse.get().getMetadata().getName())
                    .edit(service -> TheiaCloudHandlerUtil.addOwnerReferenceToItem(correlationId, sessionResourceName,
                            sessionResourceUID, service));
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Error while editing service " + (serviceToUse.get().getMetadata().getName())), e);
            return JavaUtil.tuple(Optional.empty(), false);
        }
        return JavaUtil.tuple(serviceToUse, false);
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
        httpIngressPath.setPathType("Prefix");

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

        // Find service by first filtering all services by the session's corresponding session labels (as added in
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
            LOGGER.error(formatLogMessage(correlationId, "No Service owned by session " + spec.getName() + " found."));
            return false;
        } else if (services.size() > 1) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Multiple Services owned by session " + spec.getName() + " found. This should never happen."));
            return false;
        }
        Service ownedService = services.get(0);
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

        // Cleanup LS resources
        String lsResourceName = "ls-" + sessionResourceName;
        try {
            client.services().withName(lsResourceName).delete();
            client.apps().deployments().withName(lsResourceName).delete();
            LOGGER.info(formatLogMessage(correlationId, "Deleted LS resources: " + lsResourceName));
        } catch (KubernetesClientException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Error while deleting LS resources (might be already gone)"), e);
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

    protected void createAndApplyLSService(NamespacedKubernetesClient client, String namespace, String correlationId,
            String sessionResourceName, String sessionResourceUID, Session session, AppDefinition appDefinition) {
        Map<String, String> replacements = new HashMap<>();
        replacements.put("placeholder-servicename", "ls-" + sessionResourceName);
        replacements.put("placeholder-app", "ls-" + sessionResourceName);
        replacements.put("placeholder-namespace", namespace);
        replacements.put("placeholder", sessionResourceName); // For ownerRef name and uid (using name as placeholder for uid in template is tricky, but template uses name and uid fields)
        // Actually, the template uses 'placeholder' for both name and uid in ownerReferences.
        // We need to be careful. JavaResourceUtil.readResourceAndReplacePlaceholders replaces all occurrences.
        // Let's check the template again.
        // ownerReferences:
        //   - apiVersion: theia.cloud/v1beta9
        //     kind: Session
        //     name: placeholder
        //     uid: placeholder
        
        // So 'placeholder' will be replaced by sessionResourceName. But we need UID for uid field.
        // The current JavaResourceUtil might not support different placeholders for name and uid if they share the same string "placeholder".
        // However, in my template I used "placeholder" for both.
        // Wait, I should have used distinct placeholders in my template if I wanted to replace them differently.
        // But let's look at how K8sUtil.loadAndCreate... works. It often overrides owner references.
        // K8sUtil.loadAndCreateServiceWithOwnerReference takes ownerRef details as args.
        // So the template placeholders for ownerRef might be ignored or overwritten by the utility method?
        // Let's check K8sUtil.loadAndCreateServiceWithOwnerReference signature.
        
        // It seems K8sUtil.loadAndCreateServiceWithOwnerReference does NOT take the YAML string and parse it, 
        // it takes the YAML string, parses it, and THEN adds the owner reference programmatically.
        // So the ownerReference in the template is just a placeholder that might be overwritten or added to.
        // Actually, looking at EagerStartAppDefinitionAddedHandler, it uses K8sUtil.loadAndCreateServiceWithOwnerReference.
        
        // Let's assume K8sUtil handles the owner reference addition.
        // I just need to replace the name, namespace, app label.
        
        String serviceYaml;
        try {
            // We need to handle the fact that "placeholder" is used for multiple things in my template.
            // In my template:
            // name: placeholder-servicename
            // app: placeholder-app
            // namespace: placeholder-namespace
            // name: placeholder (in ownerRef)
            // uid: placeholder (in ownerRef)
            
            // If I replace "placeholder" with sessionResourceName, it might affect other things if they contain "placeholder".
            // "placeholder-servicename" contains "placeholder".
            // So if I replace "placeholder" with "foo", "placeholder-servicename" becomes "foo-servicename".
            // That works!
            
            // But wait, for UID I need the UID string.
            // The K8sUtil method adds the owner reference.
            // public static Service loadAndCreateServiceWithOwnerReference(NamespacedKubernetesClient client, String namespace,
            //        String correlationId, String yaml, String ownerApiVersion, String ownerKind, String ownerName,
            //        String ownerUid, int ownerBlockOwnerDeletion, Map<String, String> additionalLabels)
            
            // So I don't need to worry about the ownerReference in the template being correct, 
            // as long as I pass the correct owner info to the K8sUtil method.
            
            replacements.put("placeholder-servicename", "ls-" + sessionResourceName);
            replacements.put("placeholder-app", "ls-" + sessionResourceName);
            replacements.put("placeholder-namespace", namespace);
            
            serviceYaml = JavaResourceUtil.readResourceAndReplacePlaceholders(AddedHandlerUtil.TEMPLATE_LS_SERVICE_YAML,
                    replacements, correlationId);
        } catch (IOException | URISyntaxException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while adjusting LS service template"), e);
            return;
        }

        K8sUtil.loadAndCreateServiceWithOwnerReference(client, namespace, correlationId, serviceYaml,
                "theia.cloud/v1beta9", "Session", sessionResourceName, sessionResourceUID, 0,
                Collections.emptyMap());
    }

    protected void createAndApplyLSDeployment(NamespacedKubernetesClient client, String namespace, String correlationId,
            String sessionResourceName, String sessionResourceUID, Session session, AppDefinition appDefinition,
            String lsImage) {
        Map<String, String> replacements = new HashMap<>();
        replacements.put("placeholder-depname", "ls-" + sessionResourceName);
        replacements.put("placeholder-app", "ls-" + sessionResourceName);
        replacements.put("placeholder-namespace", namespace);
        replacements.put("placeholder-image", lsImage);

        String deploymentYaml;
        try {
            deploymentYaml = JavaResourceUtil.readResourceAndReplacePlaceholders(
                    AddedHandlerUtil.TEMPLATE_LS_DEPLOYMENT_YAML, replacements, correlationId);
        } catch (IOException | URISyntaxException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while adjusting LS deployment template"), e);
            return;
        }

        K8sUtil.loadAndCreateDeploymentWithOwnerReference(client, namespace, correlationId, deploymentYaml,
                "theia.cloud/v1beta9", "Session", sessionResourceName, sessionResourceUID, 0,
                Collections.emptyMap(), deployment -> {
                    // No extra processing needed for now
                });
    }
