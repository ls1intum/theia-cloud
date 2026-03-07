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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

class AppDefinitionAdminApiTokenFilterTests {

    private AppDefinitionAdminApiTokenFilter fixture;
    private RequestContextStub requestContext;

    @BeforeEach
    void setUp() {
        fixture = new AppDefinitionAdminApiTokenFilter();
        fixture.logger = Logger.getLogger(AppDefinitionAdminApiTokenFilterTests.class);
        requestContext = new RequestContextStub();
    }

    @Test
    void filter_matchingBearerToken_proceed() {
        fixture.applicationProperties = new TestApplicationProperties("token-2");
        requestContext.authorizationHeader = "Bearer token-2";

        fixture.filter(requestContext.asRequestContext());

        assertEquals(null, requestContext.abortResponse);
    }

    @Test
    void filter_missingAuthorizationHeader_abortUnauthorized() {
        fixture.applicationProperties = new TestApplicationProperties("token-1");

        fixture.filter(requestContext.asRequestContext());

        assertAbort(Response.Status.UNAUTHORIZED, "Bearer admin API token required.");
    }

    @Test
    void filter_invalidBearerToken_abortForbidden() {
        fixture.applicationProperties = new TestApplicationProperties("token-1");
        requestContext.authorizationHeader = "Bearer wrong-token";

        fixture.filter(requestContext.asRequestContext());

        assertAbort(Response.Status.FORBIDDEN, "Valid admin API token required.");
    }

    @Test
    void filter_missingConfiguredTokens_abortForbidden() {
        fixture.applicationProperties = new TestApplicationProperties("");
        requestContext.authorizationHeader = "Bearer token-1";

        fixture.filter(requestContext.asRequestContext());

        assertAbort(Response.Status.FORBIDDEN, "Admin API token authentication is not configured.");
    }

    private void assertAbort(Response.Status expectedStatus, String expectedMessage) {
        Response response = requestContext.abortResponse;
        assertEquals(expectedStatus.getStatusCode(), response.getStatus());
        assertEquals(expectedMessage, response.getEntity());
    }

    private static final class TestApplicationProperties extends ApplicationProperties {
        private final String token;

        private TestApplicationProperties(String token) {
            this.token = token;
        }

        @Override
        public String getAdminApiToken() {
            return token;
        }
    }

    private static final class RequestContextStub {
        private String authorizationHeader;
        private Response abortResponse;

        private ContainerRequestContext asRequestContext() {
            UriInfo uriInfo = (UriInfo) Proxy.newProxyInstance(UriInfo.class.getClassLoader(), new Class<?>[] { UriInfo.class },
                    (proxy, method, args) -> {
                        if ("getPath".equals(method.getName())) {
                            return "service/admin/appdefinition";
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });

            return (ContainerRequestContext) Proxy.newProxyInstance(ContainerRequestContext.class.getClassLoader(),
                    new Class<?>[] { ContainerRequestContext.class }, (proxy, method, args) -> {
                        return switch (method.getName()) {
                            case "getMethod" -> "GET";
                            case "getUriInfo" -> uriInfo;
                            case "getHeaderString" -> HttpHeaders.AUTHORIZATION.equals(args[0]) ? authorizationHeader : null;
                            case "abortWith" -> {
                                abortResponse = (Response) args[0];
                                yield null;
                            }
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    });
        }
    }
}
