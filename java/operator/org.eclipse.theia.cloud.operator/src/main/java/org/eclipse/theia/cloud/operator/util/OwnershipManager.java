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
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Resource;

/**
 * Centralized manager for Kubernetes owner reference operations. Consolidates ownership logic from
 * OwnerReferenceChecker and TheiaCloudHandlerUtil.
 */
public final class OwnershipManager {

    private static final Logger LOGGER = LogManager.getLogger(OwnershipManager.class);

    private OwnershipManager() {
    }

    /**
     * Context representing an owner entity.
     */
    public static class OwnerContext {
        private final String name;
        private final String uid;
        private final String apiVersion;
        private final String kind;

        public OwnerContext(String name, String uid, String apiVersion, String kind) {
            this.name = name;
            this.uid = uid;
            this.apiVersion = apiVersion;
            this.kind = kind;
        }

        public String getName() {
            return name;
        }

        public String getUid() {
            return uid;
        }

        public String getApiVersion() {
            return apiVersion;
        }

        public String getKind() {
            return kind;
        }

        public OwnerReference toOwnerReference() {
            OwnerReference ref = new OwnerReference();
            ref.setApiVersion(apiVersion);
            ref.setKind(kind);
            ref.setName(name);
            ref.setUid(uid);
            return ref;
        }

        public static OwnerContext of(String name, String uid) {
            return new OwnerContext(name, uid, null, null);
        }

        public static OwnerContext of(String name, String uid, String apiVersion, String kind) {
            return new OwnerContext(name, uid, apiVersion, kind);
        }
    }

    // ========== Ownership Queries ==========

    /**
     * Checks if a resource is owned solely by the specified owner (no other owners).
     */
    public static boolean isOwnedSolelyBy(HasMetadata resource, String ownerName, String ownerUID) {
        if (resource.getMetadata() == null) {
            return false;
        }
        List<OwnerReference> refs = resource.getMetadata().getOwnerReferences();
        if (refs == null || refs.isEmpty()) {
            return false;
        }
        if (refs.size() != 1) {
            return false;
        }
        OwnerReference ref = refs.get(0);
        return ownerUID.equals(ref.getUid()) && ownerName.equals(ref.getName());
    }

    /**
     * Checks if a resource is owned solely by the specified owner.
     */
    public static boolean isOwnedSolelyBy(HasMetadata resource, OwnerContext owner) {
        return isOwnedSolelyBy(resource, owner.getName(), owner.getUid());
    }

    /**
     * Checks if a resource has the specified owner as one of its owners.
     */
    public static boolean hasOwner(HasMetadata resource, String ownerName, String ownerUID) {
        if (resource.getMetadata() == null) {
            return false;
        }
        List<OwnerReference> refs = resource.getMetadata().getOwnerReferences();
        if (refs == null || refs.isEmpty()) {
            return false;
        }
        return refs.stream().anyMatch(ref -> ownerUID.equals(ref.getUid()) && ownerName.equals(ref.getName()));
    }

    /**
     * Checks if a resource has the specified owner as one of its owners.
     */
    public static boolean hasOwner(HasMetadata resource, OwnerContext owner) {
        return hasOwner(resource, owner.getName(), owner.getUid());
    }

    /**
     * Checks if a resource has the specified owner AND at least one other owner.
     */
    public static boolean hasAdditionalOwners(HasMetadata resource, String ownerName, String ownerUID) {
        if (resource.getMetadata() == null) {
            return false;
        }
        List<OwnerReference> refs = resource.getMetadata().getOwnerReferences();
        if (refs == null || refs.size() < 2) {
            return false;
        }
        boolean hasThisOwner = refs.stream()
                .anyMatch(ref -> ownerUID.equals(ref.getUid()) && ownerName.equals(ref.getName()));
        boolean hasOtherOwner = refs.stream()
                .anyMatch(ref -> !(ownerUID.equals(ref.getUid()) && ownerName.equals(ref.getName())));
        return hasThisOwner && hasOtherOwner;
    }

    /**
     * Checks if a resource has the specified owner AND at least one other owner.
     */
    public static boolean hasAdditionalOwners(HasMetadata resource, OwnerContext owner) {
        return hasAdditionalOwners(resource, owner.getName(), owner.getUid());
    }

    /**
     * Checks if a resource has no owners.
     */
    public static boolean isOrphan(HasMetadata resource) {
        if (resource.getMetadata() == null) {
            return true;
        }
        List<OwnerReference> refs = resource.getMetadata().getOwnerReferences();
        return refs == null || refs.isEmpty();
    }

    // ========== Ownership Mutations (In-Memory) ==========

    /**
     * Adds an owner reference to a resource (in-memory mutation).
     * 
     * @return the same resource for chaining
     */
    public static <T extends HasMetadata> T addOwner(T resource, OwnerContext owner, String correlationId) {
        if (resource.getMetadata() == null) {
            LOGGER.warn(formatLogMessage(correlationId,
                    "Cannot add owner " + owner.getName() + ": resource metadata is null"));
            return resource;
        }
        LOGGER.info(formatLogMessage(correlationId,
                "Adding owner " + owner.getName() + " to " + resource.getMetadata().getName()));
        if (resource.getMetadata().getOwnerReferences() == null) {
            resource.getMetadata().setOwnerReferences(new ArrayList<>());
        }
        resource.getMetadata().getOwnerReferences().add(owner.toOwnerReference());
        return resource;
    }

    /**
     * Removes an owner reference from a resource (in-memory mutation). Does nothing if the owner is not present.
     * 
     * @return the same resource for chaining
     */
    public static <T extends HasMetadata> T removeOwner(T resource, String ownerName, String ownerUID,
            String correlationId) {
        if (resource.getMetadata() == null) {
            LOGGER.warn(formatLogMessage(correlationId,
                    "Cannot remove owner " + ownerName + ": resource metadata is null"));
            return resource;
        }
        LOGGER.info(formatLogMessage(correlationId,
                "Removing owner " + ownerName + " from " + resource.getMetadata().getName()));
        if (resource.getMetadata().getOwnerReferences() == null) {
            return resource;
        }
        resource.getMetadata().getOwnerReferences()
                .removeIf(ref -> ownerName.equals(ref.getName()) && ownerUID.equals(ref.getUid()));
        return resource;
    }

    /**
     * Removes an owner reference from a resource (in-memory mutation).
     */
    public static <T extends HasMetadata> T removeOwner(T resource, OwnerContext owner, String correlationId) {
        return removeOwner(resource, owner.getName(), owner.getUid(), correlationId);
    }

    // ========== Ownership Mutations (With K8s Client) ==========

    /**
     * Result of an ownership operation.
     */
    public enum OwnershipResult {
        SUCCESS,
        RESOURCE_NOT_FOUND,
        CONFLICT,
        ERROR
    }

    /**
     * Adds an owner reference to a resource via the K8s API.
     */
    public static <T extends HasMetadata> OwnershipResult addOwnerViaApi(
            Function<T, Resource<T>> resourceAccessor,
            T resource,
            OwnerContext owner,
            String correlationId) {
        try {
            resourceAccessor.apply(resource).edit(r -> addOwner(r, owner, correlationId));
            return OwnershipResult.SUCCESS;
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Failed to add owner " + owner.getName() + " to " + resource.getMetadata().getName()), e);
            return OwnershipResult.ERROR;
        }
    }

    /**
     * Removes an owner reference from a resource via the K8s API.
     */
    public static <T extends HasMetadata> OwnershipResult removeOwnerViaApi(
            Function<T, Resource<T>> resourceAccessor,
            T resource,
            OwnerContext owner,
            String correlationId) {
        try {
            resourceAccessor.apply(resource).edit(r -> removeOwner(r, owner, correlationId));
            return OwnershipResult.SUCCESS;
        } catch (KubernetesClientException e) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Failed to remove owner " + owner.getName() + " from " + resource.getMetadata().getName()), e);
            return OwnershipResult.ERROR;
        }
    }
}
