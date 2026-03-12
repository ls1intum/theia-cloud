package org.eclipse.theia.cloud.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;

import org.jboss.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.ws.rs.container.ContainerRequestContext;
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
    void filter_matchingAdminApiTokenHeader_proceed() {
        fixture.applicationProperties = new TestApplicationProperties("token-2");
        requestContext.adminApiTokenHeader = "token-2";

        fixture.filter(requestContext.asRequestContext());

        assertEquals(null, requestContext.abortResponse);
    }

    @Test
    void filter_missingAdminApiTokenHeader_abortUnauthorized() {
        fixture.applicationProperties = new TestApplicationProperties("token-1");

        fixture.filter(requestContext.asRequestContext());

        assertAbort(Response.Status.UNAUTHORIZED, "Admin API token required in X-Admin-Api-Token header.");
    }

    @Test
    void filter_invalidAdminApiTokenHeader_abortForbidden() {
        fixture.applicationProperties = new TestApplicationProperties("token-1");
        requestContext.adminApiTokenHeader = "wrong-token";

        fixture.filter(requestContext.asRequestContext());

        assertAbort(Response.Status.FORBIDDEN, "Valid admin API token required.");
    }

    @Test
    void filter_missingConfiguredTokens_abortForbidden() {
        fixture.applicationProperties = new TestApplicationProperties("");
        requestContext.adminApiTokenHeader = "token-1";

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
        private String adminApiTokenHeader;
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
                            case "getHeaderString" -> "X-Admin-Api-Token".equals(args[0]) ? adminApiTokenHeader : null;
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
