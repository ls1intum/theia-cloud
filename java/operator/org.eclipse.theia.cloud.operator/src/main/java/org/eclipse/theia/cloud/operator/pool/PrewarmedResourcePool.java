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
import org.eclipse.theia.cloud.operator.util.SentryHelper;
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
import io.sentry.ISpan;
import io.sentry.Sentry;
import io.sentry.SpanStatus;

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
        String appDefName = appDef.getSpec().getName();
        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null
                ? parentSpan.startChild("pool.ensure_capacity", "Ensure pool capacity")
                : Sentry.startTransaction("pool.ensure_capacity", "pool");

        span.setTag("app_definition", appDefName);
        span.setData("min_instances", minInstances);

        LOGGER.info(formatLogMessage(correlationId, "Ensuring pool capacity: " + minInstances + " for " + appDefName));

        try {
            String ownerName = appDef.getMetadata().getName();
            String ownerUID = appDef.getMetadata().getUid();
            Map<String, String> labels = new HashMap<>();

            // Get existing resources
            ISpan fetchSpan = span.startChild("pool.fetch_existing", "Fetch existing resources");
            List<Service> existingServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);
            List<Deployment> existingDeployments = K8sUtil.getExistingDeployments(client.kubernetes(),
                    client.namespace(), ownerName, ownerUID);
            List<ConfigMap> existingConfigMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);

            fetchSpan.setData("existing_services", existingServices.size());
            fetchSpan.setData("existing_deployments", existingDeployments.size());
            fetchSpan.setData("existing_configmaps", existingConfigMaps.size());
            SentryHelper.finishSuccess(fetchSpan);

            // Compute missing IDs
            Set<Integer> missingServiceIds = TheiaCloudServiceUtil.computeIdsOfMissingServices(appDef, correlationId,
                    minInstances, existingServices);
            Set<Integer> missingDeploymentIds = TheiaCloudDeploymentUtil.computeIdsOfMissingDeployments(appDef,
                    correlationId, minInstances, existingDeployments);

            span.setData("missing_service_ids", missingServiceIds.size());
            span.setData("missing_deployment_ids", missingDeploymentIds.size());

            boolean success = true;

            // Create missing services
            if (!missingServiceIds.isEmpty()) {
                ISpan serviceSpan = span.startChild("pool.create_services", "Create missing services");
                serviceSpan.setData("count", missingServiceIds.size());
                int created = 0;
                int failed = 0;
                for (int instance : missingServiceIds) {
                    ISpan svcSpan = serviceSpan.startChild("k8s.service.create", "Create service " + instance);
                    svcSpan.setData("instance_id", instance);
                    boolean extOk = resourceFactory
                            .createServiceForEagerInstance(appDef, instance, labels, correlationId).isPresent();
                    boolean intOk = resourceFactory
                            .createInternalServiceForEagerInstance(appDef, instance, labels, correlationId).isPresent();
                    if (extOk && intOk) {
                        created++;
                        SentryHelper.finishSuccess(svcSpan);
                    } else {
                        failed++;
                        success = false;
                        SentryHelper.finishWithOutcome(svcSpan, "failure", SpanStatus.INTERNAL_ERROR);
                    }
                }
                serviceSpan.setData("created", created);
                serviceSpan.setData("failed", failed);
                SentryHelper.finishWithOutcome(serviceSpan, failed == 0);
            }

            // Create missing configmaps (if using Keycloak)
            if (arguments.isUseKeycloak()) {
                List<ConfigMap> proxyConfigMaps = existingConfigMaps.stream().filter(
                        cm -> "proxy".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                        .collect(Collectors.toList());
                List<ConfigMap> emailConfigMaps = existingConfigMaps.stream().filter(
                        cm -> "emails".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                        .collect(Collectors.toList());

                Set<Integer> missingProxyIds = TheiaCloudConfigMapUtil.computeIdsOfMissingProxyConfigMaps(appDef,
                        correlationId, minInstances, proxyConfigMaps);
                Set<Integer> missingEmailIds = TheiaCloudConfigMapUtil.computeIdsOfMissingEmailConfigMaps(appDef,
                        correlationId, minInstances, emailConfigMaps);

                if (!missingProxyIds.isEmpty() || !missingEmailIds.isEmpty()) {
                    ISpan cmSpan = span.startChild("pool.create_configmaps", "Create missing configmaps");
                    cmSpan.setData("missing_proxy", missingProxyIds.size());
                    cmSpan.setData("missing_email", missingEmailIds.size());

                    for (int instance : missingProxyIds) {
                        success &= resourceFactory
                                .createProxyConfigMapForEagerInstance(appDef, instance, labels, correlationId)
                                .isPresent();
                    }
                    for (int instance : missingEmailIds) {
                        success &= resourceFactory
                                .createEmailConfigMapForEagerInstance(appDef, instance, labels, correlationId)
                                .isPresent();
                    }
                    SentryHelper.finishWithOutcome(cmSpan, success);
                }
            }

            // Create missing deployments
            if (!missingDeploymentIds.isEmpty()) {
                ISpan deploySpan = span.startChild("pool.create_deployments", "Create missing deployments");
                deploySpan.setData("count", missingDeploymentIds.size());
                int created = 0;
                int failed = 0;
                for (int instance : missingDeploymentIds) {
                    ISpan depSpan = deploySpan.startChild("k8s.deployment.create", "Create deployment " + instance);
                    depSpan.setData("instance_id", instance);
                    if (resourceFactory.createDeploymentForEagerInstance(appDef, instance, labels, correlationId)
                            .isPresent()) {
                        created++;
                        SentryHelper.finishSuccess(depSpan);
                    } else {
                        failed++;
                        success = false;
                        SentryHelper.finishWithOutcome(depSpan, "failure", SpanStatus.INTERNAL_ERROR);
                    }
                }
                deploySpan.setData("created", created);
                deploySpan.setData("failed", failed);
                SentryHelper.finishWithOutcome(deploySpan, failed == 0);
            }

            SentryHelper.finishWithOutcome(span, success);
            return success;

        } catch (Exception e) {
            SentryHelper.finishError(span, e);
            throw e;
        }
    }

    /**
     * Reconciles the pool to match the target instance count. Creates missing instances, removes excess instances
     * (respecting ownership).
     */
    public boolean reconcile(AppDefinition appDef, int targetInstances, String correlationId) {
        String appDefName = appDef.getSpec().getName();
        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null ? parentSpan.startChild("pool.reconcile", "Reconcile pool")
                : Sentry.startTransaction("pool.reconcile", "pool");

        span.setTag("app_definition", appDefName);
        span.setData("target_instances", targetInstances);
        span.setData("generation", appDef.getMetadata().getGeneration());

        LOGGER.info(formatLogMessage(correlationId, "Reconciling pool to " + targetInstances + " instances"));

        try {
            String ownerName = appDef.getMetadata().getName();
            String ownerUID = appDef.getMetadata().getUid();
            OwnerContext owner = OwnerContext.of(ownerName, ownerUID, AppDefinition.API, AppDefinition.KIND);
            Map<String, String> labels = new HashMap<>();

            boolean success = true;

            // Reconcile services
            ISpan serviceSpan = span.startChild("pool.reconcile_services", "Reconcile services");
            List<Service> existingServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);
            List<Service> externalServices = existingServices.stream()
                    .filter(s -> !s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
            List<Service> internalServices = existingServices.stream()
                    .filter(s -> s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
            Set<Integer> missingServiceIds = TheiaCloudServiceUtil.computeIdsOfMissingServices(appDef, correlationId,
                    targetInstances, existingServices);

            serviceSpan.setData("existing_external", externalServices.size());
            serviceSpan.setData("existing_internal", internalServices.size());
            serviceSpan.setData("missing", missingServiceIds.size());

            // Reconcile external services
            ResourceLifecycleManager.ReconcileResult extResult = ResourceLifecycleManager
                    .reconcile(ResourceLifecycleManager.ReconcileContext.<Service> builder()
                            .correlationId(correlationId).existingResources(externalServices)
                            .missingIds(missingServiceIds).targetCount(targetInstances).owner(owner)
                            .resourceAccessor(
                                    s -> client.kubernetes().services().inNamespace(client.namespace()).resource(s))
                            .idExtractor(s -> TheiaCloudServiceUtil.getId(correlationId, appDef, s))
                            .resourceTypeName("service").createResource(instance -> {
                                resourceFactory.createServiceForEagerInstance(appDef, instance, labels, correlationId);
                            }).shouldRecreate(s -> OwnershipManager.isOwnedSolelyBy(s, owner)).recreateResource(s -> {
                                Integer id = TheiaCloudServiceUtil.getId(correlationId, appDef, s);
                                if (id != null) {
                                    resourceFactory.createServiceForEagerInstance(appDef, id, labels, correlationId);
                                }
                            }).build());
            success &= extResult.isSuccess();

            // Reconcile internal services
            ResourceLifecycleManager.ReconcileResult intResult = ResourceLifecycleManager
                    .reconcile(ResourceLifecycleManager.ReconcileContext.<Service> builder()
                            .correlationId(correlationId).existingResources(internalServices)
                            .missingIds(missingServiceIds).targetCount(targetInstances).owner(owner)
                            .resourceAccessor(
                                    s -> client.kubernetes().services().inNamespace(client.namespace()).resource(s))
                            .idExtractor(s -> TheiaCloudServiceUtil.getId(correlationId, appDef, s))
                            .resourceTypeName("internal service").createResource(instance -> {
                                resourceFactory.createInternalServiceForEagerInstance(appDef, instance, labels,
                                        correlationId);
                            }).shouldRecreate(s -> OwnershipManager.isOwnedSolelyBy(s, owner)).recreateResource(s -> {
                                Integer id = TheiaCloudServiceUtil.getId(correlationId, appDef, s);
                                if (id != null) {
                                    resourceFactory.createInternalServiceForEagerInstance(appDef, id, labels,
                                            correlationId);
                                }
                            }).build());
            success &= intResult.isSuccess();

            SentryHelper.tagReconcileResult(serviceSpan, extResult.getCreated() + intResult.getCreated(),
                    extResult.getDeleted() + intResult.getDeleted(),
                    extResult.getRecreated() + intResult.getRecreated(),
                    extResult.getSkipped() + intResult.getSkipped(), extResult.isSuccess() && intResult.isSuccess());
            serviceSpan.finish();

            // Reconcile configmaps (if using Keycloak)
            if (arguments.isUseKeycloak()) {
                ISpan cmSpan = span.startChild("pool.reconcile_configmaps", "Reconcile configmaps");
                List<ConfigMap> existingConfigMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(),
                        client.namespace(), ownerName, ownerUID);
                List<ConfigMap> proxyConfigMaps = existingConfigMaps.stream().filter(
                        cm -> "proxy".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                        .collect(Collectors.toList());
                List<ConfigMap> emailConfigMaps = existingConfigMaps.stream().filter(
                        cm -> "emails".equals(cm.getMetadata().getLabels().get("theia-cloud.io/template-purpose")))
                        .collect(Collectors.toList());

                Set<Integer> missingProxyIds = TheiaCloudConfigMapUtil.computeIdsOfMissingProxyConfigMaps(appDef,
                        correlationId, targetInstances, proxyConfigMaps);
                Set<Integer> missingEmailIds = TheiaCloudConfigMapUtil.computeIdsOfMissingEmailConfigMaps(appDef,
                        correlationId, targetInstances, emailConfigMaps);

                ResourceLifecycleManager.ReconcileResult proxyResult = ResourceLifecycleManager
                        .reconcile(ResourceLifecycleManager.ReconcileContext.<ConfigMap> builder()
                                .correlationId(correlationId).existingResources(proxyConfigMaps)
                                .missingIds(missingProxyIds).targetCount(targetInstances).owner(owner)
                                .resourceAccessor(cm -> client.kubernetes().configMaps().inNamespace(client.namespace())
                                        .resource(cm))
                                .idExtractor(cm -> TheiaCloudConfigMapUtil.getProxyId(correlationId, appDef, cm))
                                .resourceTypeName("proxy configmap")
                                .createResource(instance -> resourceFactory.createProxyConfigMapForEagerInstance(appDef,
                                        instance, labels, correlationId))
                                .shouldRecreate(cm -> OwnershipManager.isOwnedSolelyBy(cm, owner))
                                .recreateResource(cm -> {
                                    Integer id = TheiaCloudConfigMapUtil.getProxyId(correlationId, appDef, cm);
                                    if (id != null) {
                                        resourceFactory.createProxyConfigMapForEagerInstance(appDef, id, labels,
                                                correlationId);
                                    }
                                }).build());
                success &= proxyResult.isSuccess();

                ResourceLifecycleManager.ReconcileResult emailResult = ResourceLifecycleManager
                        .reconcile(ResourceLifecycleManager.ReconcileContext.<ConfigMap> builder()
                                .correlationId(correlationId).existingResources(emailConfigMaps)
                                .missingIds(missingEmailIds).targetCount(targetInstances).owner(owner)
                                .resourceAccessor(cm -> client.kubernetes().configMaps().inNamespace(client.namespace())
                                        .resource(cm))
                                .idExtractor(cm -> TheiaCloudConfigMapUtil.getEmailId(correlationId, appDef, cm))
                                .resourceTypeName("email configmap")
                                .createResource(instance -> resourceFactory.createEmailConfigMapForEagerInstance(appDef,
                                        instance, labels, correlationId))
                                .shouldRecreate(cm -> OwnershipManager.isOwnedSolelyBy(cm, owner))
                                .recreateResource(cm -> {
                                    Integer id = TheiaCloudConfigMapUtil.getEmailId(correlationId, appDef, cm);
                                    if (id != null) {
                                        resourceFactory.createEmailConfigMapForEagerInstance(appDef, id, labels,
                                                correlationId);
                                    }
                                }).build());
                success &= emailResult.isSuccess();

                SentryHelper.tagReconcileResult(cmSpan, proxyResult.getCreated() + emailResult.getCreated(),
                        proxyResult.getDeleted() + emailResult.getDeleted(),
                        proxyResult.getRecreated() + emailResult.getRecreated(),
                        proxyResult.getSkipped() + emailResult.getSkipped(),
                        proxyResult.isSuccess() && emailResult.isSuccess());
                cmSpan.finish();
            }

            // Reconcile deployments
            ISpan deploySpan = span.startChild("pool.reconcile_deployments", "Reconcile deployments");
            List<Deployment> existingDeployments = K8sUtil.getExistingDeployments(client.kubernetes(),
                    client.namespace(), ownerName, ownerUID);
            Set<Integer> missingDeploymentIds = TheiaCloudDeploymentUtil.computeIdsOfMissingDeployments(appDef,
                    correlationId, targetInstances, existingDeployments);

            deploySpan.setData("existing", existingDeployments.size());
            deploySpan.setData("missing", missingDeploymentIds.size());

            ResourceLifecycleManager.ReconcileResult deployResult = ResourceLifecycleManager
                    .reconcile(ResourceLifecycleManager.ReconcileContext.<Deployment> builder()
                            .correlationId(correlationId).existingResources(existingDeployments)
                            .missingIds(missingDeploymentIds).targetCount(targetInstances).owner(owner)
                            .resourceAccessor(d -> client.kubernetes().apps().deployments()
                                    .inNamespace(client.namespace()).resource(d))
                            .idExtractor(d -> TheiaCloudDeploymentUtil.getId(correlationId, appDef, d))
                            .resourceTypeName("deployment")
                            .createResource(instance -> resourceFactory.createDeploymentForEagerInstance(appDef,
                                    instance, labels, correlationId))
                            .shouldRecreate(d -> OwnershipManager.isOwnedSolelyBy(d, owner)).recreateResource(d -> {
                                Integer id = TheiaCloudDeploymentUtil.getId(correlationId, appDef, d);
                                if (id != null) {
                                    resourceFactory.createDeploymentForEagerInstance(appDef, id, labels, correlationId);
                                }
                            }).build());
            success &= deployResult.isSuccess();

            SentryHelper.tagReconcileResult(deploySpan, deployResult.getCreated(), deployResult.getDeleted(),
                    deployResult.getRecreated(), deployResult.getSkipped(), deployResult.isSuccess());
            deploySpan.finish();

            SentryHelper.finishWithOutcome(span, success);
            return success;

        } catch (Exception e) {
            SentryHelper.finishError(span, e);
            throw e;
        }
    }

    /**
     * Reconciles a single instance after session release. - If instanceId > minInstances → delete all resources for
     * this instance - If resource generation != current AppDefinition generation → recreate - Otherwise → do nothing
     */
    public void reconcileInstance(AppDefinition appDef, int instanceId, String correlationId) {
        String appDefName = appDef.getSpec().getName();
        int minInstances = appDef.getSpec().getMinInstances();
        long currentGeneration = appDef.getMetadata().getGeneration();

        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null
                ? parentSpan.startChild("pool.reconcile_instance", "Reconcile instance " + instanceId)
                : Sentry.startTransaction("pool.reconcile_instance", "pool");

        span.setTag("app_definition", appDefName);
        span.setData("instance_id", instanceId);
        span.setData("min_instances", minInstances);
        span.setData("generation", currentGeneration);

        try {
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

            // Filter to just this instance
            List<Service> instanceServices = allServices.stream()
                    .filter(s -> instanceId == parseInstanceIdOrDefault(s, -1)).collect(Collectors.toList());
            List<Deployment> instanceDeployments = allDeployments.stream()
                    .filter(d -> instanceId == parseDeploymentInstanceIdOrDefault(appDef, d, -1))
                    .collect(Collectors.toList());
            List<ConfigMap> instanceConfigMaps = allConfigMaps.stream()
                    .filter(cm -> instanceId == parseConfigMapInstanceIdOrDefault(appDef, cm, -1))
                    .collect(Collectors.toList());

            span.setData("found_services", instanceServices.size());
            span.setData("found_deployments", instanceDeployments.size());
            span.setData("found_configmaps", instanceConfigMaps.size());

            if (instanceId > minInstances) {
                // Instance is outside pool size - delete everything
                span.setTag("action", "delete_excess");
                LOGGER.info(formatLogMessage(correlationId,
                        "Instance " + instanceId + " exceeds minInstances (" + minInstances + "), deleting"));

                ISpan deleteSpan = span.startChild("pool.delete_excess_instance", "Delete excess instance");
                deleteInstanceResources(instanceServices, instanceDeployments, instanceConfigMaps, correlationId);
                SentryHelper.finishSuccess(deleteSpan);
                SentryHelper.finishSuccess(span);
                return;
            }

            // Check if any resource is outdated (generation mismatch)
            boolean outdated = isOutdated(instanceServices, currentGeneration)
                    || isOutdated(instanceDeployments, currentGeneration)
                    || isOutdated(instanceConfigMaps, currentGeneration);

            if (outdated) {
                span.setTag("action", "recreate_outdated");
                LOGGER.info(formatLogMessage(correlationId,
                        "Instance " + instanceId + " has outdated resources, recreating"));

                ISpan recreateSpan = span.startChild("pool.recreate_outdated_instance", "Recreate outdated instance");
                deleteInstanceResources(instanceServices, instanceDeployments, instanceConfigMaps, correlationId);
                createInstanceResources(appDef, instanceId, correlationId);
                SentryHelper.finishSuccess(recreateSpan);
                SentryHelper.finishSuccess(span);
                return;
            }

            span.setTag("action", "no_action");
            LOGGER.info(formatLogMessage(correlationId, "Instance " + instanceId + " is up-to-date, no action needed"));
            SentryHelper.finishSuccess(span);

        } catch (Exception e) {
            SentryHelper.finishError(span, e);
            throw e;
        }
    }

    private boolean isOutdated(List<? extends HasMetadata> resources, long currentGeneration) {
        for (var resource : resources) {
            Map<String, String> labels = resource.getMetadata().getLabels();
            if (labels == null) {
                return true;
            }
            String genLabel = labels.get(APPDEFINITION_GENERATION_LABEL);
            if (genLabel == null) {
                return true;
            }
            try {
                long resourceGen = Long.parseLong(genLabel);
                if (resourceGen != currentGeneration) {
                    return true;
                }
            } catch (NumberFormatException e) {
                return true;
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

        resourceFactory.createServiceForEagerInstance(appDef, instanceId, labels, correlationId);
        resourceFactory.createInternalServiceForEagerInstance(appDef, instanceId, labels, correlationId);

        if (arguments.isUseKeycloak()) {
            resourceFactory.createProxyConfigMapForEagerInstance(appDef, instanceId, labels, correlationId);
            resourceFactory.createEmailConfigMapForEagerInstance(appDef, instanceId, labels, correlationId);
        }

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
        String appDefName = appDef.getSpec().getName();
        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null ? parentSpan.startChild("pool.release_all", "Release all pool resources")
                : Sentry.startTransaction("pool.release_all", "pool");

        span.setTag("app_definition", appDefName);

        LOGGER.info(formatLogMessage(correlationId, "Releasing all pool resources for " + appDefName));

        try {
            String ownerName = appDef.getMetadata().getName();
            String ownerUID = appDef.getMetadata().getUid();
            OwnerContext owner = OwnerContext.of(ownerName, ownerUID);

            boolean success = true;

            ISpan svcSpan = span.startChild("pool.release_services", "Release services");
            List<Service> services = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(), ownerName,
                    ownerUID);
            svcSpan.setData("count", services.size());
            success &= ResourceLifecycleManager.releaseOwnership(services, owner,
                    s -> client.kubernetes().services().inNamespace(client.namespace()).resource(s), "service",
                    correlationId);
            SentryHelper.finishWithOutcome(svcSpan, success);

            ISpan cmSpan = span.startChild("pool.release_configmaps", "Release configmaps");
            List<ConfigMap> configMaps = K8sUtil.getExistingConfigMaps(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);
            cmSpan.setData("count", configMaps.size());
            boolean cmSuccess = ResourceLifecycleManager.releaseOwnership(configMaps, owner,
                    cm -> client.kubernetes().configMaps().inNamespace(client.namespace()).resource(cm), "configmap",
                    correlationId);
            success &= cmSuccess;
            SentryHelper.finishWithOutcome(cmSpan, cmSuccess);

            ISpan deploySpan = span.startChild("pool.release_deployments", "Release deployments");
            List<Deployment> deployments = K8sUtil.getExistingDeployments(client.kubernetes(), client.namespace(),
                    ownerName, ownerUID);
            deploySpan.setData("count", deployments.size());
            boolean deploySuccess = ResourceLifecycleManager.releaseOwnership(deployments, owner,
                    d -> client.kubernetes().apps().deployments().inNamespace(client.namespace()).resource(d),
                    "deployment", correlationId);
            success &= deploySuccess;
            SentryHelper.finishWithOutcome(deploySpan, deploySuccess);

            SentryHelper.finishWithOutcome(span, success);
            return success;

        } catch (Exception e) {
            SentryHelper.finishError(span, e);
            throw e;
        }
    }

    // ========== Instance Reservation ==========

    /**
     * Reserves a prewarmed instance for a session. Adds the session as an owner of the instance's resources.
     */
    public synchronized ReservationResult reserveInstance(Session session, AppDefinition appDef, String correlationId) {
        String sessionName = session.getSpec().getName();
        String appDefName = appDef.getSpec().getName();

        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null ? parentSpan.startChild("pool.reserve", "Reserve pool instance")
                : Sentry.startTransaction("pool.reserve", "pool");

        span.setTag("app_definition", appDefName);
        span.setData("session_name", sessionName);

        LOGGER.info(formatLogMessage(correlationId, "Attempting to reserve instance for session " + sessionName));

        try {
            String appDefOwnerName = appDef.getMetadata().getName();
            String appDefOwnerUID = appDef.getMetadata().getUid();
            String sessionUID = session.getMetadata().getUid();

            // Get all services for this app definition
            List<Service> existingServices = K8sUtil.getExistingServices(client.kubernetes(), client.namespace(),
                    appDefOwnerName, appDefOwnerUID);

            // Separate external and internal services
            List<Service> externalServices = existingServices.stream()
                    .filter(s -> !s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());
            List<Service> internalServices = existingServices.stream()
                    .filter(s -> s.getMetadata().getName().endsWith("-int")).collect(Collectors.toList());

            // Track pool capacity
            int totalCapacity = externalServices.size();
            int availableCount = (int) externalServices.stream().filter(s -> TheiaCloudServiceUtil.isUnusedService(s))
                    .count();
            SentryHelper.tagPoolCapacity(span, totalCapacity, availableCount);

            // Check if session already has a reservation
            Optional<Service> alreadyReservedExternal = TheiaCloudServiceUtil.getServiceOwnedBySession(sessionName,
                    sessionUID, externalServices);
            Optional<Service> alreadyReservedInternal = TheiaCloudServiceUtil.getServiceOwnedBySession(sessionName,
                    sessionUID, internalServices);

            if (alreadyReservedExternal.isPresent() && alreadyReservedInternal.isPresent()) {
                Integer extId = parseInstanceId(alreadyReservedExternal.get());
                Integer intId = parseInstanceId(alreadyReservedInternal.get());
                if (extId == null || intId == null || !extId.equals(intId)) {
                    LOGGER.error(formatLogMessage(correlationId, "Reservation mismatch for session " + sessionName));
                    SentryHelper.tagReservationOutcome(span, "error", null);
                    SentryHelper.finishWithOutcome(span, "error", SpanStatus.INTERNAL_ERROR);
                    return ReservationResult.error();
                }
                String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, extId);
                span.setTag("reservation.type", "already_reserved");
                SentryHelper.tagReservationOutcome(span, "success", extId);
                SentryHelper.finishSuccess(span);
                return ReservationResult.success(new PoolInstance(extId, alreadyReservedExternal.get(),
                        alreadyReservedInternal.get(), deploymentName));
            }

            // Build instance maps
            Map<Integer, Service> externalByInstance = buildInstanceMap(externalServices);
            Map<Integer, Service> internalByInstance = buildInstanceMap(internalServices);

            // Handle partial reservation
            if (alreadyReservedExternal.isPresent() ^ alreadyReservedInternal.isPresent()) {
                span.setTag("reservation.type", "partial_recovery");
                ReservationResult result = handlePartialReservation(session, appDef, alreadyReservedExternal,
                        alreadyReservedInternal, externalByInstance, internalByInstance, correlationId);
                SentryHelper.tagReservationOutcome(span, result.getOutcome().name().toLowerCase(),
                        result.getInstance().map(PoolInstance::getInstanceId).orElse(null));
                SentryHelper.finishWithOutcome(span,
                        result.getOutcome() == ReservationOutcome.SUCCESS ? "success"
                                : result.getOutcome().name().toLowerCase(),
                        result.getOutcome() == ReservationOutcome.SUCCESS ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
                return result;
            }

            // Find available instance
            List<Integer> availableIds = externalByInstance.entrySet().stream()
                    .filter(e -> TheiaCloudServiceUtil.isUnusedService(e.getValue())).filter(e -> {
                        Service internal = internalByInstance.get(e.getKey());
                        return internal != null && TheiaCloudServiceUtil.isUnusedService(internal);
                    }).map(Map.Entry::getKey).sorted(Comparator.naturalOrder()).collect(Collectors.toList());

            if (availableIds.isEmpty()) {
                LOGGER.info(formatLogMessage(correlationId, "No prewarmed instances available"));
                span.setTag("reservation.type", "no_capacity");
                SentryHelper.tagReservationOutcome(span, "no_capacity", null);
                SentryHelper.finishWithOutcome(span, "no_capacity", SpanStatus.RESOURCE_EXHAUSTED);
                return ReservationResult.noCapacity();
            }

            int chosenInstance = availableIds.get(0);
            Service chosenExternal = externalByInstance.get(chosenInstance);
            Service chosenInternal = internalByInstance.get(chosenInstance);

            span.setTag("reservation.type", "new");
            span.setData("chosen_instance", chosenInstance);

            // Reserve both services
            ISpan reserveExtSpan = span.startChild("pool.reserve_external_service", "Reserve external service");
            try {
                reserveService(chosenExternal, sessionName, sessionUID, correlationId);
                SentryHelper.finishSuccess(reserveExtSpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to reserve external service"), e);
                SentryHelper.finishError(reserveExtSpan, e);
                SentryHelper.tagReservationOutcome(span, "error", null);
                SentryHelper.finishWithOutcome(span, "error", SpanStatus.INTERNAL_ERROR);
                return ReservationResult.error();
            }

            ISpan reserveIntSpan = span.startChild("pool.reserve_internal_service", "Reserve internal service");
            try {
                reserveService(chosenInternal, sessionName, sessionUID, correlationId);
                SentryHelper.finishSuccess(reserveIntSpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to reserve internal service"), e);
                SentryHelper.finishError(reserveIntSpan, e);
                rollbackReservation(chosenExternal, sessionName, sessionUID, correlationId);
                SentryHelper.tagReservationOutcome(span, "error", null);
                SentryHelper.finishWithOutcome(span, "error", SpanStatus.INTERNAL_ERROR);
                return ReservationResult.error();
            }

            String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, chosenInstance);
            SentryHelper.tagReservationOutcome(span, "success", chosenInstance);
            SentryHelper.finishSuccess(span);
            return ReservationResult
                    .success(new PoolInstance(chosenInstance, chosenExternal, chosenInternal, deploymentName));

        } catch (Exception e) {
            SentryHelper.finishError(span, e);
            throw e;
        }
    }

    /**
     * Completes the session setup after reservation. Adds session labels, reserves deployment, configures email config.
     */
    public boolean completeSessionSetup(Session session, AppDefinition appDef, PoolInstance instance,
            String correlationId) {

        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null ? parentSpan.startChild("pool.complete_setup", "Complete session setup")
                : Sentry.startTransaction("pool.complete_setup", "pool");

        span.setData("session_name", session.getSpec().getName());
        span.setData("instance_id", instance.getInstanceId());

        try {
            String sessionName = session.getMetadata().getName();
            String sessionUID = session.getMetadata().getUid();

            Map<String, String> sessionLabels = LabelsUtil.createSessionLabels(session, appDef);

            // Add labels to services
            ISpan labelSpan = span.startChild("pool.add_session_labels", "Add session labels to services");
            try {
                addSessionLabelsToService(instance.getExternalService(), sessionLabels, correlationId);
                addSessionLabelsToService(instance.getInternalService(), sessionLabels, correlationId);
                SentryHelper.finishSuccess(labelSpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to add session labels to services"), e);
                SentryHelper.finishError(labelSpan, e);
                SentryHelper.finishWithOutcome(span, "failure", SpanStatus.INTERNAL_ERROR);
                return false;
            }

            // Reserve deployment
            ISpan deploySpan = span.startChild("pool.reserve_deployment", "Reserve deployment");
            try {
                client.kubernetes().inNamespace(session.getMetadata().getNamespace()).apps().deployments().withName(instance.getDeploymentName()).edit(
                        d -> TheiaCloudHandlerUtil.addOwnerReferenceToItem(correlationId, sessionName, sessionUID, d));
                SentryHelper.finishSuccess(deploySpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to reserve deployment"), e);
                SentryHelper.finishError(deploySpan, e);
                SentryHelper.finishWithOutcome(span, "failure", SpanStatus.INTERNAL_ERROR);
                return false;
            }

            // Configure email config (if using Keycloak)
            if (arguments.isUseKeycloak()) {
                ISpan emailSpan = span.startChild("pool.configure_email", "Configure email config");
                String emailConfigName = TheiaCloudConfigMapUtil.getEmailConfigName(appDef, instance.getInstanceId());
                try {
                    client.kubernetes().inNamespace(session.getMetadata().getNamespace()).configMaps().withName(emailConfigName).edit(cm -> {
                        cm.setData(Collections.singletonMap(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST,
                                session.getSpec().getUser()));
                        return cm;
                    });
                    SentryHelper.finishSuccess(emailSpan);
                } catch (KubernetesClientException e) {
                    LOGGER.error(formatLogMessage(correlationId, "Failed to configure email config"), e);
                    SentryHelper.finishError(emailSpan, e);
                    SentryHelper.finishWithOutcome(span, "failure", SpanStatus.INTERNAL_ERROR);
                    return false;
                }

                // Trigger pod refresh
                ISpan refreshSpan = span.startChild("pool.refresh_pods", "Trigger pod refresh");
                refreshPods(instance.getDeploymentName(), correlationId);
                SentryHelper.finishSuccess(refreshSpan);
            }

            SentryHelper.finishSuccess(span);
            return true;

        } catch (Exception e) {
            SentryHelper.finishError(span, e);
            throw e;
        }
    }

    /**
     * Releases an instance back to the pool (used when session ends). Removes session ownership and clears
     * session-specific data.
     */
    public boolean releaseInstance(Session session, AppDefinition appDef, String correlationId) {
        String sessionName = session.getSpec().getName();
        String appDefName = appDef.getSpec().getName();

        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null ? parentSpan.startChild("pool.release_instance", "Release pool instance")
                : Sentry.startTransaction("pool.release_instance", "pool");

        span.setTag("app_definition", appDefName);
        span.setData("session_name", sessionName);

        LOGGER.info(formatLogMessage(correlationId, "Releasing instance for session " + sessionName));

        try {
            String sessionUID = session.getMetadata().getUid();

            // Find services owned by this session
            Map<String, String> sessionLabels = LabelsUtil.createSessionLabels(session, appDef);
            List<Service> services = findServicesByLabels(sessionLabels, correlationId);

            if (services.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId,
                        "No services found for session " + session.getSpec().getName()));
                span.setTag("error.reason", "no_services_found");
                SentryHelper.finishWithOutcome(span, "failure", SpanStatus.NOT_FOUND);
                return false;
            }

            List<Service> externalServices = services.stream().filter(s -> !s.getMetadata().getName().endsWith("-int"))
                    .collect(Collectors.toList());
            List<Service> internalServices = services.stream().filter(s -> s.getMetadata().getName().endsWith("-int"))
                    .collect(Collectors.toList());

            if (externalServices.size() != 1 || internalServices.size() != 1) {
                LOGGER.error(formatLogMessage(correlationId, "Expected 1 external and 1 internal service, found "
                        + externalServices.size() + " and " + internalServices.size()));
                span.setTag("error.reason", "service_count_mismatch");
                span.setData("external_count", externalServices.size());
                span.setData("internal_count", internalServices.size());
                SentryHelper.finishWithOutcome(span, "failure", SpanStatus.INTERNAL_ERROR);
                return false;
            }

            Service externalService = externalServices.get(0);
            Service internalService = internalServices.get(0);

            Integer instanceId = parseInstanceId(externalService);
            if (instanceId == null) {
                LOGGER.error(formatLogMessage(correlationId, "Cannot determine instance ID from service"));
                span.setTag("error.reason", "cannot_parse_instance_id");
                SentryHelper.finishWithOutcome(span, "failure", SpanStatus.INTERNAL_ERROR);
                return false;
            }
            span.setData("instance_id", instanceId);

            boolean success = true;

            // Clean up services
            ISpan svcSpan = span.startChild("pool.cleanup_services", "Clean up services");
            success &= cleanupService(externalService, sessionName, sessionUID, correlationId);
            success &= cleanupService(internalService, sessionName, sessionUID, correlationId);
            SentryHelper.finishWithOutcome(svcSpan, success);

            // Clean up deployment
            ISpan deploySpan = span.startChild("pool.cleanup_deployment", "Clean up deployment");
            String deploymentName = TheiaCloudDeploymentUtil.getDeploymentName(appDef, instanceId);
            try {
                client.kubernetes().apps().deployments().withName(deploymentName).edit(d -> TheiaCloudHandlerUtil
                        .removeOwnerReferenceFromItem(correlationId, sessionName, sessionUID, d));
                SentryHelper.finishSuccess(deploySpan);
            } catch (KubernetesClientException e) {
                LOGGER.error(formatLogMessage(correlationId, "Failed to clean up deployment"), e);
                SentryHelper.finishError(deploySpan, e);
                success = false;
            }

            // Clear email config
            if (arguments.isUseKeycloak()) {
                ISpan emailSpan = span.startChild("pool.clear_email_config", "Clear email config");
                String emailConfigName = TheiaCloudConfigMapUtil.getEmailConfigName(appDef, instanceId);
                try {
                    client.kubernetes().configMaps().withName(emailConfigName).edit(cm -> {
                        cm.setData(Collections.singletonMap(AddedHandlerUtil.FILENAME_AUTHENTICATED_EMAILS_LIST, null));
                        return cm;
                    });
                    SentryHelper.finishSuccess(emailSpan);
                } catch (KubernetesClientException e) {
                    LOGGER.error(formatLogMessage(correlationId, "Failed to clear email config"), e);
                    SentryHelper.finishError(emailSpan, e);
                    success = false;
                }
            }

            // Delete pod to reset state
            ISpan podSpan = span.startChild("pool.delete_pod", "Delete pod to reset state");
            deletePod(deploymentName, correlationId);
            SentryHelper.finishSuccess(podSpan);

            SentryHelper.finishWithOutcome(span, success);
            return success;

        } catch (Exception e) {
            SentryHelper.finishError(span, e);
            throw e;
        }
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
            client.services().inNamespace(client.namespace()).withName(service.getMetadata().getName()).edit(s -> {
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
                client.services().inNamespace(service.getMetadata().getNamespace()).withName(service.getMetadata().getName()).edit(s -> {
                    TheiaCloudHandlerUtil.removeOwnerReferenceFromItem(correlationId, sessionName, sessionUID, s);
                    if (s.getMetadata().getLabels() != null) {
                        s.getMetadata().getLabels().keySet().removeAll(LabelsUtil.getSessionSpecificLabelKeys());
                    }
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
        String labelSelector = labels.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining(","));
        return client.kubernetes().services().inNamespace(client.namespace()).withLabelSelector(labelSelector).list()
                .getItems();
    }

    private void refreshPods(String deploymentName, String correlationId) {
        try {
            // Get deployment to extract selector labels
            Deployment deployment = client.kubernetes().apps().deployments().inNamespace(client.namespace())
                    .withName(deploymentName).get();
            if (deployment == null || deployment.getSpec() == null || deployment.getSpec().getSelector() == null
                    || deployment.getSpec().getSelector().getMatchLabels() == null) {
                return;
            }

            // Build label selector from deployment's matchLabels
            Map<String, String> selectorLabels = deployment.getSpec().getSelector().getMatchLabels();
            String labelSelector = selectorLabels.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(","));

            // Query pods using label selector
            client.kubernetes().pods().inNamespace(client.namespace()).withLabelSelector(labelSelector).list()
                    .getItems().forEach(pod -> {
                        // Null-check metadata and ownerReferences before streaming
                        if (pod.getMetadata() == null || pod.getMetadata().getOwnerReferences() == null) {
                            return;
                        }
                        // Check if pod belongs to this deployment via owner references
                        if (pod.getMetadata().getOwnerReferences().stream()
                                .anyMatch(or -> or.getName().startsWith(deploymentName))) {
                            // Null-check and initialize annotations before mutating
                            if (pod.getMetadata().getAnnotations() == null) {
                                pod.getMetadata().setAnnotations(new HashMap<>());
                            }
                            pod.getMetadata().getAnnotations().put(EAGER_START_REFRESH_ANNOTATION,
                                    Instant.now().toString());
                            PodResource podResource = client.pods().inNamespace(client.namespace())
                                    .withName(pod.getMetadata().getName());
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
