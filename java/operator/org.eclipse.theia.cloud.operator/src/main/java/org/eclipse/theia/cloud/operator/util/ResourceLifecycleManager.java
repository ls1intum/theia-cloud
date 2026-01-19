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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.operator.util.OwnershipManager.OwnerContext;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.sentry.ISpan;
import io.sentry.Sentry;
import io.sentry.SpanStatus;

/**
 * Unified manager for Kubernetes resource lifecycle operations.
 * Handles reconciliation (create/delete/update) and ownership release.
 * 
 * Replaces the separate ResourceReconciler and ResourceDeletionManager classes.
 */
public final class ResourceLifecycleManager {

    private static final Logger LOGGER = LogManager.getLogger(ResourceLifecycleManager.class);

    private ResourceLifecycleManager() {
    }

    /**
     * Result of a reconciliation operation.
     */
    public static class ReconcileResult {
        private final boolean success;
        private final int created;
        private final int deleted;
        private final int recreated;
        private final int skipped;

        private ReconcileResult(boolean success, int created, int deleted, int recreated, int skipped) {
            this.success = success;
            this.created = created;
            this.deleted = deleted;
            this.recreated = recreated;
            this.skipped = skipped;
        }

        public boolean isSuccess() {
            return success;
        }

        public int getCreated() {
            return created;
        }

        public int getDeleted() {
            return deleted;
        }

        public int getRecreated() {
            return recreated;
        }

        public int getSkipped() {
            return skipped;
        }

        public static ReconcileResult success(int created, int deleted, int recreated, int skipped) {
            return new ReconcileResult(true, created, deleted, recreated, skipped);
        }

        public static ReconcileResult failure(int created, int deleted, int recreated, int skipped) {
            return new ReconcileResult(false, created, deleted, recreated, skipped);
        }
    }

    /**
     * Context for reconciliation operations.
     * 
     * @param <T> the resource type
     */
    public static class ReconcileContext<T extends HasMetadata> {
        private final String correlationId;
        private final List<T> existingResources;
        private final Set<Integer> missingIds;
        private final int targetCount;
        private final OwnerContext owner;
        private final Function<T, Resource<T>> resourceAccessor;
        private final Function<T, Integer> idExtractor;
        private final String resourceTypeName;
        private final Consumer<Integer> createResource;
        private final Consumer<T> recreateResource;
        private final Predicate<T> shouldRecreate;

        private ReconcileContext(Builder<T> builder) {
            this.correlationId = builder.correlationId;
            this.existingResources = builder.existingResources;
            this.missingIds = builder.missingIds;
            this.targetCount = builder.targetCount;
            this.owner = builder.owner;
            this.resourceAccessor = builder.resourceAccessor;
            this.idExtractor = builder.idExtractor;
            this.resourceTypeName = builder.resourceTypeName;
            this.createResource = builder.createResource;
            this.recreateResource = builder.recreateResource;
            this.shouldRecreate = builder.shouldRecreate;
        }

        public static <T extends HasMetadata> Builder<T> builder() {
            return new Builder<>();
        }

        public static class Builder<T extends HasMetadata> {
            private String correlationId;
            private List<T> existingResources = new ArrayList<>();
            private Set<Integer> missingIds = Set.of();
            private int targetCount;
            private OwnerContext owner;
            private Function<T, Resource<T>> resourceAccessor;
            private Function<T, Integer> idExtractor;
            private String resourceTypeName = "resource";
            private Consumer<Integer> createResource = id -> {
            };
            private Consumer<T> recreateResource;
            private Predicate<T> shouldRecreate = r -> false;

            public Builder<T> correlationId(String correlationId) {
                this.correlationId = correlationId;
                return this;
            }

            public Builder<T> existingResources(List<T> existingResources) {
                this.existingResources = existingResources != null ? existingResources : new ArrayList<>();
                return this;
            }

            public Builder<T> missingIds(Set<Integer> missingIds) {
                this.missingIds = missingIds != null ? missingIds : Set.of();
                return this;
            }

            public Builder<T> targetCount(int targetCount) {
                this.targetCount = targetCount;
                return this;
            }

            public Builder<T> owner(OwnerContext owner) {
                this.owner = owner;
                return this;
            }

            public Builder<T> owner(String name, String uid) {
                this.owner = OwnerContext.of(name, uid);
                return this;
            }

            public Builder<T> resourceAccessor(Function<T, Resource<T>> resourceAccessor) {
                this.resourceAccessor = resourceAccessor;
                return this;
            }

            public Builder<T> idExtractor(Function<T, Integer> idExtractor) {
                this.idExtractor = idExtractor;
                return this;
            }

            public Builder<T> resourceTypeName(String resourceTypeName) {
                this.resourceTypeName = resourceTypeName;
                return this;
            }

            public Builder<T> createResource(Consumer<Integer> createResource) {
                this.createResource = createResource;
                return this;
            }

            public Builder<T> recreateResource(Consumer<T> recreateResource) {
                this.recreateResource = recreateResource;
                return this;
            }

            public Builder<T> shouldRecreate(Predicate<T> shouldRecreate) {
                this.shouldRecreate = shouldRecreate;
                return this;
            }

            public ReconcileContext<T> build() {
                return new ReconcileContext<>(this);
            }
        }
    }

    /**
     * Reconciles resources to match the target state.
     * 
     * Operations performed:
     * 1. Creates resources for missing IDs (1 to targetCount)
     * 2. Deletes resources with IDs > targetCount (if solely owned)
     * 3. Recreates resources that need updating (if solely owned)
     * 
     * Resources with multiple owners are handled specially:
     * - If we're one of multiple owners and resource should be deleted, we remove our owner ref
     * - If we're one of multiple owners and resource needs recreation, we skip it
     */
    public static <T extends HasMetadata> ReconcileResult reconcile(ReconcileContext<T> ctx) {
        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null
                ? parentSpan.startChild("lifecycle.reconcile", "Reconcile " + ctx.resourceTypeName)
                : null;

        if (span != null) {
            span.setTag("resource.type", ctx.resourceTypeName);
            span.setData("target_count", ctx.targetCount);
            span.setData("existing_count", ctx.existingResources.size());
            span.setData("missing_count", ctx.missingIds.size());
        }

        boolean success = true;
        int created = 0;
        int deleted = 0;
        int recreated = 0;
        int skipped = 0;

        try {
            // Step 1: Create missing resources
            for (int instanceId : ctx.missingIds) {
                ISpan createSpan = span != null
                        ? span.startChild("lifecycle.create", "Create " + ctx.resourceTypeName + " " + instanceId)
                        : null;
                if (createSpan != null) {
                    createSpan.setTag("resource.type", ctx.resourceTypeName);
                    createSpan.setTag("resource.operation", "create");
                    createSpan.setData("instance_id", instanceId);
                }

                try {
                    ctx.createResource.accept(instanceId);
                    created++;
                    if (createSpan != null) {
                        SentryHelper.finishSuccess(createSpan);
                    }
                } catch (Exception e) {
                    LOGGER.error(formatLogMessage(ctx.correlationId,
                            "Failed to create " + ctx.resourceTypeName + " for instance " + instanceId), e);
                    success = false;
                    if (createSpan != null) {
                        SentryHelper.finishError(createSpan, e);
                    }
                }
            }

            // Step 2: Process existing resources
            for (T resource : ctx.existingResources) {
                Integer id = ctx.idExtractor.apply(resource);
                if (id == null) {
                    LOGGER.warn(formatLogMessage(ctx.correlationId, "Cannot extract ID from " + ctx.resourceTypeName
                            + " " + resource.getMetadata().getName()));
                    skipped++;
                    continue;
                }

                String resourceName = resource.getMetadata().getName();

                // Case A: Resource ID > target count → should be deleted
                if (id > ctx.targetCount) {
                    if (OwnershipManager.isOwnedSolelyBy(resource, ctx.owner)) {
                        ISpan deleteSpan = span != null
                                ? span.startChild("lifecycle.delete", "Delete excess " + ctx.resourceTypeName)
                                : null;
                        if (deleteSpan != null) {
                            deleteSpan.setTag("resource.type", ctx.resourceTypeName);
                            deleteSpan.setTag("resource.operation", "delete");
                            deleteSpan.setData("resource_name", resourceName);
                            deleteSpan.setData("instance_id", id);
                            deleteSpan.setTag("delete.reason", "excess");
                        }

                        try {
                            ctx.resourceAccessor.apply(resource).delete();
                            deleted++;
                            LOGGER.info(formatLogMessage(ctx.correlationId,
                                    "Deleted excess " + ctx.resourceTypeName + " " + resourceName));
                            if (deleteSpan != null) {
                                SentryHelper.finishSuccess(deleteSpan);
                            }
                        } catch (Exception e) {
                            LOGGER.error(formatLogMessage(ctx.correlationId,
                                    "Failed to delete " + ctx.resourceTypeName + " " + resourceName), e);
                            success = false;
                            if (deleteSpan != null) {
                                SentryHelper.finishError(deleteSpan, e);
                            }
                        }
                    } else if (OwnershipManager.hasAdditionalOwners(resource, ctx.owner)) {
                        ISpan removeOwnerSpan = span != null
                                ? span.startChild("lifecycle.remove_owner", "Remove owner from " + ctx.resourceTypeName)
                                : null;
                        if (removeOwnerSpan != null) {
                            removeOwnerSpan.setTag("resource.type", ctx.resourceTypeName);
                            removeOwnerSpan.setTag("resource.operation", "remove_owner");
                            removeOwnerSpan.setData("resource_name", resourceName);
                        }

                        try {
                            ctx.resourceAccessor.apply(resource).edit(r -> {
                                OwnershipManager.removeOwner(r, ctx.owner, ctx.correlationId);
                                return r;
                            });
                            skipped++;
                            LOGGER.info(formatLogMessage(ctx.correlationId, "Removed owner from " + ctx.resourceTypeName
                                    + " " + resourceName + " (has other owners)"));
                            if (removeOwnerSpan != null) {
                                SentryHelper.finishSuccess(removeOwnerSpan);
                            }
                        } catch (Exception e) {
                            LOGGER.error(formatLogMessage(ctx.correlationId,
                                    "Failed to remove owner from " + ctx.resourceTypeName + " " + resourceName), e);
                            success = false;
                            if (removeOwnerSpan != null) {
                                SentryHelper.finishError(removeOwnerSpan, e);
                            }
                        }
                    } else {
                        skipped++;
                    }
                }
                // Case B: Resource needs recreation
                else if (ctx.shouldRecreate.test(resource)) {
                    if (OwnershipManager.isOwnedSolelyBy(resource, ctx.owner)) {
                        ISpan recreateSpan = span != null
                                ? span.startChild("lifecycle.recreate", "Recreate " + ctx.resourceTypeName)
                                : null;
                        if (recreateSpan != null) {
                            recreateSpan.setTag("resource.type", ctx.resourceTypeName);
                            recreateSpan.setTag("resource.operation", "recreate");
                            recreateSpan.setData("resource_name", resourceName);
                            recreateSpan.setData("instance_id", id);
                        }

                        try {
                            ctx.resourceAccessor.apply(resource).delete();
                            if (ctx.recreateResource != null) {
                                ctx.recreateResource.accept(resource);
                            }
                            recreated++;
                            LOGGER.info(formatLogMessage(ctx.correlationId,
                                    "Recreated " + ctx.resourceTypeName + " " + resourceName));
                            if (recreateSpan != null) {
                                SentryHelper.finishSuccess(recreateSpan);
                            }
                        } catch (Exception e) {
                            LOGGER.error(formatLogMessage(ctx.correlationId,
                                    "Failed to recreate " + ctx.resourceTypeName + " " + resourceName), e);
                            success = false;
                            if (recreateSpan != null) {
                                SentryHelper.finishError(recreateSpan, e);
                            }
                        }
                    } else {
                        skipped++;
                        LOGGER.debug(formatLogMessage(ctx.correlationId, "Skipping recreation of "
                                + ctx.resourceTypeName + " " + resourceName + " (has other owners)"));
                    }
                }
                // Case C: Resource is fine, no action needed
            }

            if (span != null) {
                span.setData("created", created);
                span.setData("deleted", deleted);
                span.setData("recreated", recreated);
                span.setData("skipped", skipped);
                span.setTag("outcome", success ? "success" : "failure");
                span.setTag("had_changes", (created + deleted + recreated) > 0 ? "true" : "false");
                span.setStatus(success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
                span.finish();
            }

            return success ? ReconcileResult.success(created, deleted, recreated, skipped)
                    : ReconcileResult.failure(created, deleted, recreated, skipped);

        } catch (Exception e) {
            if (span != null) {
                SentryHelper.finishError(span, e);
            }
            throw e;
        }
    }

    /**
     * Releases ownership of resources without deleting them.
     * Useful when an owner is being deleted but resources may have other owners.
     * 
     * For each resource:
     * - If solely owned by us → delete (K8s GC would do this anyway, but explicit is clearer)
     * - If we're one of multiple owners → remove our owner reference
     * - If not our resource → skip
     */
    public static <T extends HasMetadata> boolean releaseOwnership(
            List<T> resources,
            OwnerContext owner,
            Function<T, Resource<T>> resourceAccessor,
            String resourceTypeName,
            String correlationId) {

        ISpan parentSpan = Sentry.getSpan();
        ISpan span = parentSpan != null
                ? parentSpan.startChild("lifecycle.release_ownership", "Release " + resourceTypeName + " ownership")
                : null;

        if (span != null) {
            span.setTag("resource.type", resourceTypeName);
            span.setData("resource_count", resources.size());
        }

        boolean success = true;
        int deleted = 0;
        int released = 0;
        int skipped = 0;

        try {
            for (T resource : resources) {
                String resourceName = resource.getMetadata().getName();

                if (OwnershipManager.isOwnedSolelyBy(resource, owner)) {
                    ISpan deleteSpan = span != null
                            ? span.startChild("lifecycle.delete_sole_owned", "Delete " + resourceTypeName)
                            : null;
                    if (deleteSpan != null) {
                        deleteSpan.setTag("resource.type", resourceTypeName);
                        deleteSpan.setTag("resource.operation", "delete");
                        deleteSpan.setData("resource_name", resourceName);
                        deleteSpan.setTag("delete.reason", "sole_owner");
                    }

                    try {
                        resourceAccessor.apply(resource).delete();
                        deleted++;
                        LOGGER.info(formatLogMessage(correlationId,
                                "Deleted " + resourceTypeName + " " + resourceName + " (sole owner)"));
                        if (deleteSpan != null) {
                            SentryHelper.finishSuccess(deleteSpan);
                        }
                    } catch (Exception e) {
                        LOGGER.error(formatLogMessage(correlationId,
                                "Failed to delete " + resourceTypeName + " " + resourceName), e);
                        success = false;
                        if (deleteSpan != null) {
                            SentryHelper.finishError(deleteSpan, e);
                        }
                    }
                } else if (OwnershipManager.hasAdditionalOwners(resource, owner)) {
                    ISpan removeSpan = span != null
                            ? span.startChild("lifecycle.remove_owner_ref", "Remove owner from " + resourceTypeName)
                            : null;
                    if (removeSpan != null) {
                        removeSpan.setTag("resource.type", resourceTypeName);
                        removeSpan.setTag("resource.operation", "remove_owner");
                        removeSpan.setData("resource_name", resourceName);
                    }

                    try {
                        resourceAccessor.apply(resource).edit(r -> {
                            OwnershipManager.removeOwner(r, owner, correlationId);
                            return r;
                        });
                        released++;
                        LOGGER.info(formatLogMessage(correlationId,
                                "Removed owner from " + resourceTypeName + " " + resourceName));
                        if (removeSpan != null) {
                            SentryHelper.finishSuccess(removeSpan);
                        }
                    } catch (Exception e) {
                        LOGGER.error(formatLogMessage(correlationId,
                                "Failed to remove owner from " + resourceTypeName + " " + resourceName), e);
                        success = false;
                        if (removeSpan != null) {
                            SentryHelper.finishError(removeSpan, e);
                        }
                    }
                } else {
                    skipped++;
                }
            }

            if (span != null) {
                span.setData("deleted", deleted);
                span.setData("released", released);
                span.setData("skipped", skipped);
                span.setTag("outcome", success ? "success" : "failure");
                span.setStatus(success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
                span.finish();
            }

            return success;

        } catch (Exception e) {
            if (span != null) {
                SentryHelper.finishError(span, e);
            }
            throw e;
        }
    }

    /**
     * Simplified release that skips certain resources.
     */
    public static <T extends HasMetadata> boolean releaseOwnership(
            List<T> resources,
            OwnerContext owner,
            Function<T, Resource<T>> resourceAccessor,
            String resourceTypeName,
            Predicate<T> skipPredicate,
            String correlationId) {

        List<T> filtered = resources.stream()
                .filter(r -> skipPredicate == null || !skipPredicate.test(r))
                .toList();

        return releaseOwnership(filtered, owner, resourceAccessor, resourceTypeName, correlationId);
    }
}
