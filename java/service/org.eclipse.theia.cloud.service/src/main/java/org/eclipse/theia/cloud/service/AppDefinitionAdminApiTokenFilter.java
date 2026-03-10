/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
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
package org.eclipse.theia.cloud.service;

import org.jboss.logging.Logger;

import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * ContainerRequestFilter protecting app definition admin endpoints with a dedicated admin token header.
 */
@Provider
@AdminApiTokenProtected
@Priority(Priorities.AUTHORIZATION)
public class AppDefinitionAdminApiTokenFilter implements ContainerRequestFilter {

    private static final String ADMIN_API_TOKEN_HEADER = "X-Admin-Api-Token";

    @Inject
    Logger logger;

    @Inject
    ApplicationProperties applicationProperties;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        String configuredToken = applicationProperties.getAdminApiToken();
        if (configuredToken.isEmpty()) {
            logger.warnv("Blocked access to {0} {1}: no admin API token is configured.",
                    requestContext.getMethod(), requestContext.getUriInfo().getPath());
            abort(requestContext, Response.Status.FORBIDDEN, "Admin API token authentication is not configured.");
            return;
        }

        String presentedToken = requestContext.getHeaderString(ADMIN_API_TOKEN_HEADER);
        if (presentedToken == null || presentedToken.isBlank()) {
            logger.infov("Blocked access to {0} {1}: missing admin API token header.", requestContext.getMethod(),
                    requestContext.getUriInfo().getPath());
            abort(requestContext, Response.Status.UNAUTHORIZED, "Admin API token required in X-Admin-Api-Token header.");
            return;
        }

        if (!configuredToken.equals(presentedToken)) {
            logger.infov("Blocked access to {0} {1}: invalid admin API token.", requestContext.getMethod(),
                    requestContext.getUriInfo().getPath());
            abort(requestContext, Response.Status.FORBIDDEN, "Valid admin API token required.");
        }
    }

    private void abort(ContainerRequestContext requestContext, Response.Status status, String entity) {
        requestContext.abortWith(Response.status(status).entity(entity).build());
    }
}
