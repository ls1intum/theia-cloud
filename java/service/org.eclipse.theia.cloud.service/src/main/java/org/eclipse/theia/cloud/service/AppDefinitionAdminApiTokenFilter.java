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
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * ContainerRequestFilter protecting app definition admin endpoints with a bearer token.
 */
@Provider
@AdminApiTokenProtected
@Priority(Priorities.AUTHORIZATION)
public class AppDefinitionAdminApiTokenFilter implements ContainerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

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

        String authorizationHeader = requestContext.getHeaderString(HttpHeaders.AUTHORIZATION);
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            logger.infov("Blocked access to {0} {1}: missing bearer token.", requestContext.getMethod(),
                    requestContext.getUriInfo().getPath());
            abort(requestContext, Response.Status.UNAUTHORIZED, "Bearer admin API token required.");
            return;
        }

        String presentedToken = authorizationHeader.substring(BEARER_PREFIX.length());
        if (presentedToken.isEmpty() || !configuredToken.equals(presentedToken)) {
            logger.infov("Blocked access to {0} {1}: invalid bearer token.", requestContext.getMethod(),
                    requestContext.getUriInfo().getPath());
            abort(requestContext, Response.Status.FORBIDDEN, "Valid admin API token required.");
        }
    }

    private void abort(ContainerRequestContext requestContext, Response.Status status, String entity) {
        requestContext.abortWith(Response.status(status).entity(entity).build());
    }
}
