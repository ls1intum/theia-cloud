package org.eclipse.theia.cloud.common.k8s.client;

import org.eclipse.theia.cloud.common.k8s.resource.session.CredentialInjectionResponse;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;

import java.util.Map;
import java.util.Optional;

/**
 * Client for interacting with the credential bridge service running in session
 * pods.
 */
public interface CredentialBridgeClient {

    /**
     * Injects credentials into a session's credential bridge.
     * 
     * @param sessionName   The name of the session
     * @param credentials   Map of environment variable credentials to inject
     * @param correlationId For logging/tracing
     * @return Response indicating success or failure, or empty if the session or
     *         bridge is unreachable
     */
    Optional<CredentialInjectionResponse> injectCredentials(String sessionName, Map<String, String> credentials,
            String correlationId);

    /**
     * Injects credentials into a session using the Session object.
     * 
     * @param session       The session object
     * @param credentials   Map of environment variable credentials to inject
     * @param correlationId For logging/tracing
     * @return Response indicating success or failure, or empty if the bridge is
     *         unreachable
     */
    Optional<CredentialInjectionResponse> injectCredentials(Session session, Map<String, String> credentials,
            String correlationId);

    /**
     * Checks if the credential bridge is healthy/reachable.
     * 
     * @param sessionName   The name of the session
     * @param correlationId For logging/tracing
     * @return true if health check succeeds
     */
    boolean healthCheck(String sessionName, String correlationId);
}

