/********************************************************************************
 * Copyright (C) 2025 EclipseSource and others.
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
package org.eclipse.theia.cloud.service.admin.appdefinition;

import java.util.List;
import java.util.Optional;

import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.service.AdminOnly;
import org.eclipse.theia.cloud.service.ApplicationProperties;
import org.eclipse.theia.cloud.service.BaseResource;
import org.eclipse.theia.cloud.service.K8sUtil;
import org.eclipse.theia.cloud.service.appdefinition.AppDefinitionListRequest;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Resource for admin operations on app definitions.
 */
@Path("/service/admin/appdefinition")
@AdminOnly
public class AppDefinitionAdminResource extends BaseResource {

    @Inject
    private K8sUtil k8sUtil;

    @Inject
    public AppDefinitionAdminResource(ApplicationProperties applicationProperties) {
        super(applicationProperties);
    }

    @Operation(summary = "List scaling settings for all app definitions", description = "Lists minInstances and maxInstances for all app definitions.")
    @Parameter(name = "appId", description = "The app id used for service request validation.")
    @GET
    @Path("/{appId}")
    @Produces(MediaType.APPLICATION_JSON)
    public List<AppDefinitionScaling> list(@PathParam("appId") String appId) {
        evaluateRequest(new AppDefinitionListRequest(appId));
        return k8sUtil.listAppDefinitionResources().stream().map(this::toScaling).toList();
    }

    @Operation(summary = "Get scaling settings for an app definition", description = "Returns minInstances and maxInstances for a specific app definition.")
    @Parameter(name = "appDefinitionName", description = "The K8S resource name of the app definition.")
    @Parameter(name = "appId", description = "The app id used for service request validation.")
    @GET
    @Path("/{appDefinitionName}/{appId}")
    @Produces(MediaType.APPLICATION_JSON)
    public AppDefinitionScaling get(@PathParam("appDefinitionName") String appDefinitionName,
            @PathParam("appId") String appId) {
        evaluateRequest(new AppDefinitionGetRequest(appId));
        AppDefinition appDefinition = k8sUtil.getAppDefinition(appDefinitionName)
                .orElseThrow(() -> new NotFoundException("App definition does not exist."));
        return toScaling(appDefinition);
    }

    @Operation(summary = "Updates an app definition", description = "Updates an app definition's properties. Allowed properties to update are defined by AppDefinitionUpdateRequest.")
    @Parameter(name = "appDefinitionName", description = "The K8S resource name of the app definition to update.")
    @PATCH
    @Path("/{appDefinitionName}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public AppDefinition update(@PathParam("appDefinitionName") String appDefinitionName,
            AppDefinitionUpdateRequest request) {
        if (request == null) {
            throw new BadRequestException("Request body is required.");
        }
        String correlationId = evaluateRequest(request);
        Optional<AppDefinition> appDefinition = k8sUtil.getAppDefinition(appDefinitionName);
        if (appDefinition.isEmpty()) {
            throw new NotFoundException("App definition does not exist.");
        }
        validateUpdateRequest(request, appDefinition.get().getSpec());

        info(correlationId, "Update app definition " + request);
        try {
            return k8sUtil.editAppDefinition(correlationId, appDefinitionName, appDef -> {
                AppDefinitionSpec spec = appDef.getSpec();
                if (request.minInstances != null) {
                    spec.setMinInstances(request.minInstances);
                }
                if (request.maxInstances != null) {
                    spec.setMaxInstances(request.maxInstances);
                }
            });
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            error(correlationId, "Failed to update app definition ", e);
            throw new InternalServerErrorException(
                    "Failed to update app definition. See the service logs for more details.");
        }
    }

    private void validateUpdateRequest(AppDefinitionUpdateRequest request, AppDefinitionSpec existingSpec) {
        if (request.minInstances == null && request.maxInstances == null) {
            throw new BadRequestException("At least one of minInstances or maxInstances must be set.");
        }

        int resultingMin = request.minInstances != null ? request.minInstances : existingSpec.getMinInstances();
        Integer currentMax = existingSpec.getMaxInstances();
        int resultingMax = request.maxInstances != null ? request.maxInstances : (currentMax != null ? currentMax : 0);

        if (resultingMin < 0) {
            throw new BadRequestException("minInstances must be greater than or equal to 0.");
        }
        if (resultingMax < 0) {
            throw new BadRequestException("maxInstances must be greater than or equal to 0.");
        }
        if (resultingMin > resultingMax) {
            throw new BadRequestException("minInstances must be less than or equal to maxInstances.");
        }
    }

    private AppDefinitionScaling toScaling(AppDefinition appDefinition) {
        String resourceName = appDefinition.getMetadata() != null ? appDefinition.getMetadata().getName() : null;
        AppDefinitionSpec spec = appDefinition.getSpec();
        return new AppDefinitionScaling(resourceName != null ? resourceName : spec.getName(), spec.getMinInstances(),
                spec.getMaxInstances() != null ? spec.getMaxInstances() : 0);
    }
}
