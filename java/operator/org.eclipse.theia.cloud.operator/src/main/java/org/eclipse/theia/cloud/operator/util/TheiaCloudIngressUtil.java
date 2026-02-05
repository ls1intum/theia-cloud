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
package org.eclipse.theia.cloud.operator.util;

import java.util.Optional;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;

import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;

public final class TheiaCloudIngressUtil {

    private static final ResourceDefinitionContext HTTP_ROUTE_CONTEXT = new ResourceDefinitionContext.Builder()
            .withGroup("gateway.networking.k8s.io")
            .withVersion("v1")
            .withPlural("httproutes")
            .withKind("HTTPRoute")
            .withNamespaced(true)
            .build();

    private TheiaCloudIngressUtil() {
    }

    public static boolean checkForExistingIngressAndAddOwnerReferencesIfMissing(NamespacedKubernetesClient client,
            String namespace, AppDefinition appDefinition, String correlationId) {
        Optional<GenericKubernetesResource> existingRouteWithParentAppDefinition = K8sUtil.getExistingHttpRoute(
                client, namespace, appDefinition.getMetadata().getName(), appDefinition.getMetadata().getUid());
        if (existingRouteWithParentAppDefinition.isPresent()) {
            return true;
        }
        Optional<GenericKubernetesResource> route = K8sUtil.getExistingHttpRoute(client, namespace,
                appDefinition.getSpec().getIngressname());
        if (route.isPresent()) {
            OwnerReference ownerReference = new OwnerReference();
            ownerReference.setApiVersion(HasMetadata.getApiVersion(AppDefinition.class));
            ownerReference.setKind(AppDefinition.KIND);
            ownerReference.setName(appDefinition.getMetadata().getName());
            ownerReference.setUid(appDefinition.getMetadata().getUid());
            addOwnerReferenceToHttpRoute(client, namespace, route.get(), ownerReference);
        }
        return route.isPresent();
    }

    public static String getIngressName(AppDefinition appDefinition) {
        return appDefinition.getSpec().getIngressname();
    }

    public static void addOwnerReferenceToHttpRoute(NamespacedKubernetesClient client, String namespace,
            GenericKubernetesResource route, OwnerReference ownerReference) {
        client.genericKubernetesResources(HTTP_ROUTE_CONTEXT).inNamespace(namespace)
                .withName(route.getMetadata().getName())
                .edit(resource -> {
                    resource.getMetadata().getOwnerReferences().add(ownerReference);
                    return resource;
                });
    }
}
