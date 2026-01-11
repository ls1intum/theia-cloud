package org.eclipse.theia.cloud.operator.pool;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.util.LabelsUtil;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.eclipse.theia.cloud.operator.util.K8sResourceFactory;
import org.eclipse.theia.cloud.operator.util.K8sUtil;
import org.eclipse.theia.cloud.operator.util.OwnershipManager;
import org.eclipse.theia.cloud.operator.util.OwnershipManager.OwnerContext;
import org.eclipse.theia.cloud.operator.util.ResourceLifecycleManager;
import org.eclipse.theia.cloud.operator.util.TheiaCloudConfigMapUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudDeploymentUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudHandlerUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudK8sUtil;
import org.eclipse.theia.cloud.operator.util.TheiaCloudServiceUtil;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.PodResource;

/**
 * Manages a pool of prewarmed (eager start) resources for an AppDefinition. Responsibilities: - Creating/scaling the
 * pool of prewarmed instances - Reserving instances for sessions - Releasing instances back to the pool - Cleaning up
 * pool resources
 */
@Singleton
public class PrewarmedResourcePool {

    private static final Logger LOGGER = LogManager.getLogger(PrewarmedResourcePool.class);

    public static final String EAGER_START_REFRESH_ANNOTATION = "theia-cloud.io/eager-start-refresh";
    public static final String APPDEFINITION_GENERATION_LABEL = "theia-cloud.io/appdefinition-generation";

    @Inject
    private TheiaCloudClient client;

    @Inject
    private K8sResourceFactory resourceFactory;

    @Inject
    private TheiaCloudOperatorArguments arguments;

    /**
     * Represents a reserved instance from the pool.
     */
    public static class PoolInstance {
        private final int instanceId;
        private final Service externalService;
        private final Service internalService;
        private final String deploymentName;

        public PoolInstance(int instanceId, Service externalService, Service internalService, String deploymentName) {
            this.instanceId = instanceId;
            this.externalService = externalService;
            this.internalService = internalService;
            this.deploymentName = deploymentName;
        }

        public int getInstanceId() {
            return instanceId;
        }

        public Service getExternalService() {
            return externalService;
        }

        public Service getInternalService() {
            return internalService;
        }

        public String getDeploymentName() {
            return deploymentName;
        }
    }

    /**
     * Result of a reservation attempt.
     */
    public static class ReservationResult {
        private final ReservationOutcome outcome;
        private final PoolInstance instance;

        private ReservationResult(ReservationOutcome outcome, PoolInstance instance) {
            this.outcome = outcome;
            this.instance = instance;
        }

        public ReservationOutcome getOutcome() {
            return outcome;
        }

        public Optional<PoolInstance> getInstance() {
            return Optional.ofNullable(instance);
        }

        public static ReservationResult success(PoolInstance instance) {
            return new ReservationResult(ReservationOutcome.SUCCESS, instance);
        }

        public static ReservationResult noCapacity() {
            return new ReservationResult(ReservationOutcome.NO_CAPACITY, null);
        }

        public static ReservationResult error() {
            return new ReservationResult(ReservationOutcome.ERROR, null);
        }
    }

    public enum ReservationOutcome {
        SUCCESS, NO_CAPACITY, ERROR
    }

    // ========== Pool Management ==========

    /**
     * Ensures the pool has the specified minimum number of instances. Creates missing resources (services, configmaps,
     * deployments).
     */
    public boolean ensureCapacity(AppDefinition appDef, int minInstances, String correlationId) {
        LOGGER.info(formatLogMessage(correlationId,
                "Ensuring pool capacity: " + minInstances + " for " + appDef.getSpec().getName()));

        String ownerName = appDef.getMetadata().getName();
        String ownerUID = appDef.getMetadata().getUid();
        Map<String, String> labels = new HashMap<>();

        // Get existing resources
        List<Service> existingServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(), ownerName,
                ownerUID);
        List<Deployment> existingDeployments = K8sUtil.getExistingDeployments(client.kubernetes(), client.namespace(),
                ownerName, ownerUID);
        List<ConfigMap> existingConfigMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(), client.namespace(),
                ownerName, ownerUID);

        // Compute missing IDs
        Set<Integer> missingServiceIds = TheiaCloudServiceUtil.computeIdsOfMissingServices(appDef, correlationId,
                minInstances, existingServices);
        Set<Integer> missingDeploymentIds = TheiaCloudDeploymentUtil.computeIdsOfMissingDeployments(appDef,
                correlationId, minInstances, existingDeployments);

        boolean success = true;

        // Create missing services
        for (int instance : missingServiceIds) {
            success &= resourceFactory.createServiceForEagerInstance(appDef, instance, labels, correlationId)
                    .isPresent();
            success &= resourceFactory.createInternalServiceForEagerInstance(appDef, instance, labels, correlationId)
                    .isPresent();
        }

        // Create missing configmaps (if using Keycloak)
        if (arguments.isUseKeycloak()) {
            List<ConfigMap> proxyConfigMaps = existingConfigMaps.stream()
                    .filter(cm -> "proxy".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                    .collect(Collectors.toList());
            List<ConfigMap> emailConfigMaps = existingConfigMaps.stream()
                    .filter(cm -> "emails".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                    .collect(Collectors.toList());

            Set<Integer> missingProxyIds = TheiaCloudConfigMapUtil.computeIdsOfMissingProxyConfigMaps(appDef,
                    correlationId, minInstances, proxyConfigMaps);
            Set<Integer> missingEmailIds = TheiaCloudConfigMapUtil.computeIdsOfMissingEmailConfigMaps(appDef,
                    correlationId, minInstances, emailConfigMaps);

            for (int instance : missingProxyIds) {
                success &= resourceFactory.createProxyConfigMapForEagerInstance(appDef, instance, labels, correlationId)
                        .isPresent();
            }
            for (int instance : missingEmailIds) {
                success &= resourceFactory.createEmailConfigMapForEagerInstance(appDef, instance, labels, correlationId)
                        .isPresent();
            }
        }

        // Create missing deployments
        for (int instance : missingDeploymentIds) {
            success &= resourceFactory.createDeploymentForEagerInstance(appDef, instance, labels, correlationId)
                    .isPresent();
        }

        return success;
    }

    /**
     * Reconciles the pool to match the target instance count. Creates missing instances, removes excess instances
     * (respecting ownership).
     */
    public boolean reconcile(AppDefinition appDef, int targetInstances, String correlationId) {
        LOGGER.info(formatLogMessage(correlationId, "Reconciling pool to " + targetInstances + " instances"));

        String ownerName = appDef.getMetadata().getName();
        String ownerUID = appDef.getMetadata().getUid();
        OwnerContext owner = OwnerContext.of(ownerName, ownerUID, AppDefinition.API, AppDefinition.KIND);
        Map<String, String> labels = new HashMap<>();

        boolean success = true;

        // Reconcile services - must be done separately because the reconciler expects 1 resource per ID
        List<Service> existingServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(), ownerName,
                ownerUID);
        List<Service> externalServices = existingServices.stream()
                .filter(s -> !s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
        List<Service> internalServices = existingServices.stream()
                .filter(s -> s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
        Set<Integer> missingServiceIds = TheiaCloudServiceUtil.computeIdsOfMissingServices(appDef, correlationId,
                targetInstances, existingServices);

        // Reconcile external services
        success &= ResourceLifecycleManager.reconcile(ResourceLifecycleManager.ReconcileContext.<Service> builder()
                .correlationId(correlationId).existingResources(externalServices).missingIds(missingServiceIds)
                .targetCount(targetInstances).owner(owner)
                .resourceAccessor(s -> client.kubernetes().services().inNamespace(client.namespace()).resource(s))
                .idExtractor(s -> TheiaCloudServiceUtil.getId(correlationId, appDef, s)).resourceTypeName("service")
                .createResource(instance -> {
                    resourceFactory.createServiceForEagerInstance(appDef, instance, labels, correlationId);
                }).shouldRecreate(s -> OwnershipManager.isOwnedSolelyBy(s, owner)).recreateResource(s -> {
                    Integer id = TheiaCloudServiceUtil.getId(correlationId, appDef, s);
                    if (id != null) {
                        resourceFactory.createServiceForEagerInstance(appDef, id, labels, correlationId);
                    }
                }).build()).isSuccess();

        // Reconcile internal services
        success &= ResourceLifecycleManager.reconcile(ResourceLifecycleManager.ReconcileContext.<Service> builder()
                .correlationId(correlationId).existingResources(internalServices).missingIds(missingServiceIds)
                .targetCount(targetInstances).owner(owner)
                .resourceAccessor(s -> client.kubernetes().services().inNamespace(client.namespace()).resource(s))
                .idExtractor(s -> TheiaCloudServiceUtil.getId(correlationId, appDef, s))
                .resourceTypeName("internal service").createResource(instance -> {
                    resourceFactory.createInternalServiceForEagerInstance(appDef, instance, labels, correlationId);
                }).shouldRecreate(s -> OwnershipManager.isOwnedSolelyBy(s, owner)).recreateResource(s -> {
                    Integer id = TheiaCloudServiceUtil.getId(correlationId, appDef, s);
                    if (id != null) {
                        resourceFactory.createInternalServiceForEagerInstance(appDef, id, labels, correlationId);
                    }
                }).build()).isSuccess();

        // Reconcile configmaps (if using Keycloak)
        if (arguments.isUseKeycloak()) {
            List<ConfigMap> existingConfigMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);
            List<ConfigMap> proxyConfigMaps = existingConfigMaps.stream()
                    .filter(cm -> "proxy".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                    .collect(Collectors.toList());
            List<ConfigMap> emailConfigMaps = existingConfigMaps.stream()
                    .filter(cm -> "emails".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                    .collect(Collectors.toList());

            Set<Integer> missingProxyIds = TheiaCloudConfigMapUtil.computeIdsOfMissingProxyConfigMaps(appDef,
                    correlationId, targetInstances, proxyConfigMaps);
            Set<Integer> missingEmailIds = TheiaCloudConfigMapUtil.computeIdsOfMissingEmailConfigMaps(appDef,
                    correlationId, targetInstances, emailConfigMaps);

            success &= ResourceLifecycleManager.reconcile(ResourceLifecycleManager.ReconcileContext
                    .<ConfigMap> builder().correlationId(correlationId).existingResources(proxyConfigMaps)
                    .missingIds(missingProxyIds).targetCount(targetInstances).owner(owner)
                    .resourceAccessor(
                            cm -> client.kubernetes().configMaps().inNamespace(client.namespace()).resource(cm))
                    .idExtractor(cm -> TheiaCloudConfigMapUtil.getProxyId(correlationId, appDef, cm))
                    .resourceTypeName("proxy configmap")
                    .createResource(instance -> resourceFactory.createProxyConfigMapForEagerInstance(appDef, instance,
                            labels, correlationId))
                    .shouldRecreate(cm -> OwnershipManager.isOwnedSolelyBy(cm, owner)).recreateResource(cm -> {
                        Integer id = TheiaCloudConfigMapUtil.getProxyId(correlationId, appDef, cm);
                        if (id != null) {
                            resourceFactory.createProxyConfigMapForEagerInstance(appDef, id, labels, correlationId);
                        }
                    }).build()).isSuccess();

            success &= ResourceLifecycleManager.reconcile(ResourceLifecycleManager.ReconcileContext
                    .<ConfigMap> builder().correlationId(correlationId).existingResources(emailConfigMaps)
                    .missingIds(missingEmailIds).targetCount(targetInstances).owner(owner)
                    .resourceAccessor(
                            cm -> client.kubernetes().configMaps().inNamespace(client.namespace()).resource(cm))
                    .idExtractor(cm -> TheiaCloudConfigMapUtil.getEmailId(correlationId, appDef, cm))
                    .resourceTypeName("email configmap")
                    .createResource(instance -> resourceFactory.createEmailConfigMapForEagerInstance(appDef, instance,
                            labels, correlationId))
                    .shouldRecreate(cm -> OwnershipManager.isOwnedSolelyBy(cm, owner)).recreateResource(cm -> {
                        Integer id = TheiaCloudConfigMapUtil.getEmailId(correlationId, appDef, cm);
                        if (id != null) {
                            resourceFactory.createEmailConfigMapForEagerInstance(appDef, id, labels, correlationId);
                        }
                    }).build()).isSuccess();
        }

        // Reconcile deployments
        List<Deployment> existingDeployments = K8sUtil.getExistingDeployments(client.kubernetes(), client.namespace(),
                ownerName, ownerUID);
        Set<Integer> missingDeploymentIds = TheiaCloudDeploymentUtil.computeIdsOfMissingDeployments(appDef,
                correlationId, targetInstances, existingDeployments);

        success &= ResourceLifecycleManager.reconcile(ResourceLifecycleManager.ReconcileContext.<Deployment> builder()
                .correlationId(correlationId).existingResources(existingDeployments).missingIds(missingDeploymentIds)
                .targetCount(targetInstances).owner(owner)
                .resourceAccessor(
                        d -> client.kubernetes().apps().deployments().inNamespace(client.namespace()).resource(d))
                .idExtractor(d -> TheiaCloudDeploymentUtil.getId(correlationId, appDef, d))
                .resourceTypeName("deployment")
                .createResource(instance -> resourceFactory.createDeploymentForEagerInstance(appDef, instance, labels,
                        correlationId))
                .shouldRecreate(d -> OwnershipManager.isOwnedSolelyBy(d, owner)).recreateResource(d -> {
                    Integer id = TheiaCloudDeploymentUtil.getId(correlationId, appDef, d);
                    if (id != null) {
                        resourceFactory.createDeploymentForEagerInstance(appDef, id, labels, correlationId);
                    }
                }).build()).isSuccess();

        return success;
    }

    /**
     * Reconciles a single instance after session release. - If instanceId > minInstances → delete all resources for
     * this instance - If resource generation != current AppDefinition generation → recreate - Otherwise → do nothing
     */
    public void reconcileInstance(AppDefinition appDef, int instanceId, String correlationId) {
        int minInstances = appDef.getSpec().getMinInstances();
        long currentGeneration = appDef.getMetadata().getGeneration();

        String ownerName = appDef.getMetadata().getName();
        String ownerUID = appDef.getMetadata().getUid();

        LOGGER.info(formatLogMessage(correlationId, "Reconciling instance " + instanceId + " (minInstances="
                + minInstances + ", generation=" + currentGeneration + ")"));

        // Find resources for this instance
        List<Service> allServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(), ownerName,
                ownerUID);
        List<Deployment> allDeployments = K8sUtil.getExistingDeployments(client.kubernetes(), client.namespace(),
                ownerName, ownerUID);
        List<ConfigMap> allConfigMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(), client.namespace(),
                ownerName, ownerUID);

        // Filter to just this instance (includes both external and internal services)
        List<Service> instanceServices = allServices.stream().filter(s -> instanceId == parseInstanceIdOrDefault(s, -1))
                .collect(Collectors.toList());
        List<Deployment> instanceDeployments = allDeployments.stream()
                .filter(d -> instanceId == parseDeploymentInstanceIdOrDefault(appDef, d, -1))
                .collect(Collectors.toList());
        List<ConfigMap> instanceConfigMaps = allConfigMaps.stream()
                .filter(cm -> instanceId == parseConfigMapInstanceIdOrDefault(appDef, cm, -1))
                .collect(Collectors.toList());

        if (instanceId > minInstances) {
            // Instance is outside pool size - delete everything
            LOGGER.info(formatLogMessage(correlationId,
                    "Instance " + instanceId + " exceeds minInstances (" + minInstances + "), deleting"));
            deleteInstanceResources(instanceServices, instanceDeployments, instanceConfigMaps, correlationId);
            return;
        }

        // Check if any resource is outdated (generation mismatch)
        boolean outdated = isOutdated(instanceServices, currentGeneration)
                || isOutdated(instanceDeployments, currentGeneration)
                || isOutdated(instanceConfigMaps, currentGeneration);

        if (outdated) {
            LOGGER.info(
                    formatLogMessage(correlationId, "Instance " + instanceId + " has outdated resources, recreating"));
            deleteInstanceResources(instanceServices, instanceDeployments, instanceConfigMaps, correlationId);
            createInstanceResources(appDef, instanceId, correlationId);
            return;
        }

        LOGGER.info(formatLogMessage(correlationId, "Instance " + instanceId + " is up-to-date, no action needed"));
    }

    private boolean isOutdated(List<? extends HasMetadata> resources, long currentGeneration) {
        for (var resource : resources) {
            Map<String, String> labels = resource.getMetadata().getLabels();
            if (labels == null) {
                return true; // No labels means outdated
            }
            String genLabel = labels.get(APPDEFINITION_GENERATION_LABEL);
            if (genLabel == null) {
                return true; // No generation label means outdated
            }
            try {
                long resourceGen = Long.parseLong(genLabel);
                if (resourceGen != currentGeneration) {
                    return true;
                }
            } catch (NumberFormatException e) {
                return true; // Invalid generation means outdated
            }
        }
        return false;
    }

    private void deleteInstanceResources(List<Service> services, List<Deployment> deployments,
            List<ConfigMap> configMaps, String correlationId) {
        for (Service s : services) {
            try {
                client.kubernetes().services().inNamespace(client.namespace()).resource(s).delete();
                LOGGER.trace(formatLogMessage(correlationId, "Deleted service " + s.getMetadata().getName()));
            } catch (KubernetesClientException e) {
                LOGGER.warn(formatLogMessage(correlationId, "Failed to delete service " + s.getMetadata().getName()),
                        e);
            }
        }
        for (Deployment d : deployments) {
            try {
                client.kubernetes().apps().deployments().inNamespace(client.namespace()).resource(d).delete();
                LOGGER.trace(formatLogMessage(correlationId, "Deleted deployment " + d.getMetadata().getName()));
            } catch (KubernetesClientException e) {
                LOGGER.warn(formatLogMessage(correlationId, "Failed to delete deployment " + d.getMetadata().getName()),
                        e);
            }
        }
        for (ConfigMap cm : configMaps) {
            try {
                client.kubernetes().configMaps().inNamespace(client.namespace()).resource(cm).delete();
                LOGGER.trace(formatLogMessage(correlationId, "Deleted configmap " + cm.getMetadata().getName()));
            } catch (KubernetesClientException e) {
                LOGGER.warn(formatLogMessage(correlationId, "Failed to delete configmap " + cm.getMetadata().getName()),
                        e);
            }
        }
    }

    private void createInstanceResources(AppDefinition appDef, int instanceId, String correlationId) {
        Map<String, String> labels = new HashMap<>();

        // Create services
        resourceFactory.createServiceForEagerInstance(appDef, instanceId, labels, correlationId);
        resourceFactory.createInternalServiceForEagerInstance(appDef, instanceId, labels, correlationId);

        // Create configmaps (if using Keycloak)
        if (arguments.isUseKeycloak()) {
            resourceFactory.createProxyConfigMapForEagerInstance(appDef, instanceId, labels, correlationId);
            resourceFactory.createEmailConfigMapForEagerInstance(appDef, instanceId, labels, correlationId);
        }

        // Create deployment
        resourceFactory.createDeploymentForEagerInstance(appDef, instanceId, labels, correlationId);
    }

    private int parseInstanceIdOrDefault(Service service, int defaultValue) {
        Integer id = parseInstanceId(service);
        return id != null ? id : defaultValue;
    }

    private int parseDeploymentInstanceIdOrDefault(AppDefinition appDef, Deployment deployment, int defaultValue) {
        Integer id = TheiaCloudDeploymentUtil.getId(null, appDef, deployment);
        return id != null ? id : defaultValue;
    }

    private int parseConfigMapInstanceIdOrDefault(AppDefinition appDef, ConfigMap configMap, int defaultValue) {
        // Try proxy first, then email
        Integer id = TheiaCloudConfigMapUtil.getProxyId(null, appDef, configMap);
        if (id != null) {
            return id;
        }
        id = TheiaCloudConfigMapUtil.getEmailId(null, appDef, configMap);
        return id != null ? id : defaultValue;
    }

    /**
     * Releases all pool resources for an app definition (used during deletion).
     */
    public boolean releaseAll(AppDefinition appDef, String correlationId) {
        LOGGER.info(formatLogMessage(correlationId, "Releasing all pool resources for " + appDef.getSpec().getName()));

        String ownerName = appDef.getMetadata().getName();
        String ownerUID = appDef.getMetadata().getUid();
        OwnerContext owner = OwnerContext.of(ownerName, ownerUID);

        boolean success = true;

        List<Service> services = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(), ownerName,
                ownerUID);
        success &= ResourceLifecycleManager.releaseOwnership(services, owner,
                s -> client.kubernetes().services().inNamespace(client.namespace()).resource(s), "service",
                correlationId);

        List<ConfigMap> configMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(), client.namespace(), ownerName,
                ownerUID);
        success &= ResourceLifecycleManager.releaseOwnership(configMaps, owner,
                cm -> client.kubernetes().configMaps().inNamespace(client.namespace()).resource(cm), "configmap",
                correlationId);

        List<Deployment> deployments = K8sUtil.getExistingDeployments(client.kubernetes(), client.namespace(),
                ownerName, ownerUID);
        success &= ResourceLifecycleManager.releaseOwnership(deployments, owner,
                d -> client.kubernetes().apps().deployments().inNamespace(client.namespace()).resource(d), "deployment",
                correlationId);

        return success;
    }

    // ========== Instance Reservation ==========

    /**
     * Reserves a prewarmed instance for a session. Adds the session as an owner of the instance's resources.
     */
    public synchronized ReservationResult reserveInstance(Session session, AppDefinition appDef, String correlationId) {
        LOGGER.info(formatLogMessage(correlationId,
                "Attempting to reserve instance for session " + session.getMetadata().getName()));

        String appDefOwnerName = appDef.getMetadata().getName();
        String appDefOwnerUID = appDef.getMetadata().getUid();
        String sessionName = session.getMetadata().getName();
        String sessionUID = session.getMetadata().getUid();

        // Get all services for this app definition
        List<Service> existingServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(),
                appDefOwnerName, appDefOwnerUID);

        // Separate external and internal services
        List<Service> externalServices = existingServices.stream()
                .filter(s -> !s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
        List<Service> internalServices = existingServices.stream()
                .filter(s -> s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());

        // Check if session already has a reservation
        Optional<Service> alreadyReservedExternal = TheiaCloudServiceUtil.getServiceOwnedBySession(sessionName,
                sessionUID, externalServices);
        Optional<Service> alreadyReservedInternal = TheiaCloudServiceUtil.getServiceOwnedBySession(sessionName,
                sessionUID, internalServices);

        if (alreadyReservedExternal.isPresent() && alreadyReservedInternal.isPresent()) {
            // Already fully reserved
            Integer extId = parseInstanceId(alreadyReservedExternal.get());
            Integer intId = parseInstanceId(alreadyReservedInternal.get());
            if (extId == null || intId == null || !extId.equals(intId)) {
                LOGGER.error(formatLogMessage(correlationId, "Reservation mismatch for session " + sessionName));
                return ReservationResult.error();
            }
            String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, extId);
            return ReservationResult.success(new PoolInstance(extId, alreadyReservedExternal.get(),
                    alreadyReservedInternal.get(), deploymentName));
        }

        // Build instance maps
        Map<Integer, Service> externalByInstance = buildInstanceMap(externalServices);
        Map<Integer, Service> internalByInstance = buildInstanceMap(internalServices);

        // Handle partial reservation
        if (alreadyReservedExternal.isPresent() ^ alreadyReservedInternal.isPresent()) {
            return handlePartialReservation(session, appDef, alreadyReservedExternal, alreadyReservedInternal,
                    externalByInstance, internalByInstance, correlationId);
        }

        // Find available instance (both services must be unused)
        List<Integer> availableIds = externalByInstance.entrySet().stream()
                .filter(e -> TheiaCloudServiceUtil.isUnusedService(e.getValue())).filter(e -> {
                    Service internal = internalByInstance.get(e.getKey());
                    return internal != null && TheiaCloudServiceUtil.isUnusedService(internal);
                }).map(Map.Entry::getKey).sorted(Comparator.naturalOrder()).collect(Collectors.toList());

        if (availableIds.isEmpty()) {
            LOGGER.info(formatLogMessage(correlationId, "No prewarmed instances available"));
            return ReservationResult.noCapacity();
        }

        int chosenInstance = availableIds.get(0);
        Service chosenExternal = externalByInstance.get(chosenInstance);
        Service chosenInternal = internalByInstance.get(chosenInstance);

        // Reserve both services
        try {
            reserveService(chosenExternal, sessionName, sessionUID, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to reserve external service"), e);
            return ReservationResult.error();
        }

        try {
            reserveService(chosenInternal, sessionName, sessionUID, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to reserve internal service"), e);
            rollbackReservation(chosenExternal, sessionName, sessionUID, correlationId);
            return ReservationResult.error();
        }

        String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, chosenInstance);
        return ReservationResult
                .success(new PoolInstance(chosenInstance, chosenExternal, chosenInternal, deploymentName));
    }

    /**
     * Completes the session setup after reservation. Adds session labels, reserves deployment, configures email config.
     */
    public boolean completeSessionSetup(Session session, AppDefinition appDef, PoolInstance instance,
            String correlationId) {

        String sessionName = session.getMetadata().getName();
        String sessionUID = session.getMetadata().getUid();

        Map<String, String> sessionLabels = LabelsUtil.createSessionLabels(session, appDef);

        // Add labels to services
        try {
            addSessionLabelsToService(instance.getExternalService(), sessionLabels, correlationId);
            addSessionLabelsToService(instance.getInternalService(), sessionLabels, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to add session labels to services"), e);
            return false;
        }

        // Reserve deployment
        try {
            client.kubernetes().apps().deployments().withName(instance.getDeploymentName()).edit(
                    d -> TheiaCloudHandlerUtil.addOwnerReferenceToItem(correlationId, sessionName, sessionUID, d));
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to reserve deployment"), e);
            return false;
        }

        // Configure email config (if using Keycloak)
        if (arguments.isUseKeycloak()) {
            String emailConfigName = TheiaCloudConfigMapUtil.getEmailConfigName(appDef, instance.getInstanceId());
            try {
                client.kubernetes().configMaps().withName(emailConfigName).edit(cm -> {
                    cm.setData(Collections.singletonMap(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST,
                            session.getSpec().getUser()));
                    return cm;
                });
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to configure email config"), e);
                return false;
            }

            // Trigger pod refresh
            refreshPods(instance.getDeploymentName(), correlationId);
        }

        return true;
    }

    /**
     * Releases an instance back to the pool (used when session ends). Removes session ownership and clears
     * session-specific data.
     */
    public boolean releaseInstance(Session session, AppDefinition appDef, String correlationId) {
        LOGGER.info(
                formatLogMessage(correlationId, "Releasing instance for session " + session.getMetadata().getName()));

        String sessionName = session.getMetadata().getName();
        String sessionUID = session.getMetadata().getUid();

        // Find services owned by this session
        Map<String, String> sessionLabels = LabelsUtil.createSessionLabels(session, appDef);
        List<Service> services = findServicesByLabels(sessionLabels, correlationId);

        if (services.isEmpty()) {
            LOGGER.error(
                    formatLogMessage(correlationId, "No services found for session " + session.getSpec().getName()));
            return false;
        }

        List<Service> externalServices = services.stream().filter(s -> !s.getMetadata().getName().endsWith("-int"))
                .collect(Collectors.toList());
        List<Service> internalServices = services.stream().filter(s -> s.getMetadata().getName().endsWith("-int"))
                .collect(Collectors.toList());

        if (externalServices.size() != 1 || internalServices.size() != 1) {
            LOGGER.error(formatLogMessage(correlationId, "Expected 1 external and 1 internal service, found "
                    + externalServices.size() + " and " + internalServices.size()));
            return false;
        }

        Service externalService = externalServices.get(0);
        Service internalService = internalServices.get(0);

        // Get instance ID
        Integer instanceId = parseInstanceId(externalService);
        if (instanceId == null) {
            LOGGER.error(formatLogMessage(correlationId, "Cannot determine instance ID from service"));
            return false;
        }

        // Clean up services
        boolean success = true;
        success &= cleanupService(externalService, sessionName, sessionUID, correlationId);
        success &= cleanupService(internalService, sessionName, sessionUID, correlationId);

        // Clean up deployment
        String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, instanceId);
        try {
            client.kubernetes().apps().deployments().withName(deploymentName).edit(
                    d -> TheiaCloudHandlerUtil.removeOwnerReferenceFromItem(correlationId, sessionName, sessionUID, d));
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to clean up deployment"), e);
            success = false;
        }

        // Clear email config
        if (arguments.isUseKeycloak()) {
            String emailConfigName = TheiaCloudConfigMapUtil.getEmailConfigName(appDef, instanceId);
            try {
                client.kubernetes().configMaps().withName(emailConfigName).edit(cm -> {
                    cm.setData(Collections.singletonMap(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST, null));
                    return cm;
                });
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to clear email config"), e);
                success = false;
            }
        }

        // Delete pod to reset state
        deletePod(deploymentName, correlationId);

        return success;
    }

    // ========== Helper Methods ==========

    private Map<Integer, Service> buildInstanceMap(List<Service> services) {
        Map<Integer, Service> map = new HashMap<>();
        for (Service s : services) {
            Integer id = parseInstanceId(s);
            if (id != null) {
                map.putIfAbsent(id, s);
            }
        }
        return map;
    }

    private Integer parseInstanceId(Service service) {
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

    private ReservationResult handlePartialReservation(Session session, AppDefinition appDef,
            Optional<Service> reservedExternal, Optional<Service> reservedInternal, Map<Integer, Service> externalMap,
            Map<Integer, Service> internalMap, String correlationId) {

        String sessionName = session.getMetadata().getName();
        String sessionUID = session.getMetadata().getUid();

        Service reserved = reservedExternal.orElseGet(reservedInternal::get);
        Integer instance = parseInstanceId(reserved);
        if (instance == null) {
            LOGGER.error(formatLogMessage(correlationId, "Cannot parse instance from partially reserved service"));
            rollbackReservation(reserved, sessionName, sessionUID, correlationId);
            return ReservationResult.error();
        }

        Service counterpart = reservedExternal.isPresent() ? internalMap.get(instance) : externalMap.get(instance);
        if (counterpart == null || !TheiaCloudServiceUtil.isUnusedService(counterpart)) {
            LOGGER.warn(formatLogMessage(correlationId, "Partial reservation cannot be completed, rolling back"));
            rollbackReservation(reserved, sessionName, sessionUID, correlationId);
            return ReservationResult.noCapacity();
        }

        try {
            reserveService(counterpart, sessionName, sessionUID, correlationId);
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to complete partial reservation"), e);
            rollbackReservation(reserved, sessionName, sessionUID, correlationId);
            return ReservationResult.error();
        }

        Service ext = reservedExternal.orElse(counterpart);
        Service in = reservedInternal.orElse(counterpart);
        String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, instance);
        return ReservationResult.success(new PoolInstance(instance, ext, in, deploymentName));
    }

    private void reserveService(Service service, String sessionName, String sessionUID, String correlationId) {
        client.services().inNamespace(client.namespace()).withName(service.getMetadata().getName())
                .edit(s -> TheiaCloudHandlerUtil.addOwnerReferenceToItem(correlationId, sessionName, sessionUID, s));
    }

    private void rollbackReservation(Service service, String sessionName, String sessionUID, String correlationId) {
        try {
            client.services().withName(service.getMetadata().getName()).edit(s -> {
                TheiaCloudHandlerUtil.removeOwnerReferenceFromItem(correlationId, sessionName, sessionUID, s);
                return s;
            });
        } catch (KubernetesClientException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Failed to rollback reservation"), e);
        }
    }

    private void addSessionLabelsToService(Service service, Map<String, String> labels, String correlationId) {
        client.services().inNamespace(client.namespace()).withName(service.getMetadata().getName()).edit(s -> {
            Map<String, String> existing = s.getMetadata().getLabels();
            if (existing == null) {
                existing = new HashMap<>();
                s.getMetadata().setLabels(existing);
            }
            existing.putAll(labels);
            return s;
        });
    }

    private boolean cleanupService(Service service, String sessionName, String sessionUID, String correlationId) {
        int attempts = 0;
        while (attempts < 3) {
            try {
                client.services().withName(service.getMetadata().getName()).edit(s -> {
                    TheiaCloudHandlerUtil.removeOwnerReferenceFromItem(correlationId, sessionName, sessionUID, s);
                    s.getMetadata().getLabels().keySet().removeAll(LabelsUtil.getSessionSpecificLabelKeys());
                    return s;
                });
                return true;
            } catch (KubernetesClientException e) {
                attempts++;
                if (attempts >= 3) {
                    LOGGER.error(formatLogMessage(correlationId, "Failed to cleanup service after 3 attempts"), e);
                    return false;
                }
            }
        }
        return false;
    }

    private List<Service> findServicesByLabels(Map<String, String> labels, String correlationId) {
        // Build label selector string: "key1=value1,key2=value2"
        String labelSelector = labels.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        return client.kubernetes().services().inNamespace(client.namespace()).withLabelSelector(labelSelector).list()
                .getItems();
    }

    private void refreshPods(String deploymentName, String correlationId) {
        try {
            client.kubernetes().pods().list().getItems().forEach(pod -> {
                if (pod.getMetadata().getOwnerReferences().stream()
                        .anyMatch(or -> or.getName().startsWith(deploymentName))) {
                    pod.getMetadata().getAnnotations().put(EAGER_START_REFRESH_ANNOTATION, Instant.now().toString());
                    PodResource podResource = client.pods().withName(pod.getMetadata().getName());
                    podResource.edit(p -> pod);
                }
            });
        } catch (KubernetesClientException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Failed to refresh pods"), e);
        }
    }

    private void deletePod(String deploymentName, String correlationId) {
        try {
            Optional<Pod> pod = client.kubernetes().pods().list().getItems().stream()
                    .filter(p -> p.getMetadata().getName().startsWith(deploymentName)).findAny();
            if (pod.isPresent()) {
                LOGGER.info(formatLogMessage(correlationId, "Deleting pod " + pod.get().getMetadata().getName()));
                client.pods().withName(pod.get().getMetadata().getName()).delete();
            }
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to delete pod"), e);
        }
    }
}
