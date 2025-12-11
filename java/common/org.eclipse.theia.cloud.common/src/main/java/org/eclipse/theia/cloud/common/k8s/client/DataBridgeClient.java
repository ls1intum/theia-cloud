package org.eclipse.theia.cloud.common.k8s.client;

import org.eclipse.theia.cloud.common.k8s.resource.session.DataInjectionResponse;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;

import java.util.Map;
import java.util.Optional;

/**
 * Client for interacting with the data bridge service running in session
 * pods.
 */
public interface DataBridgeClient {

    /**
     * Injects data into a session's data bridge.
     * 
     * @param sessionName   The name of the session
     * @param data          Map of environment variable data to inject
     * @param correlationId For logging/tracing
     * @return Response indicating success or failure, or empty if the session or
     *         bridge is unreachable
     */
    Optional<DataInjectionResponse> injectData(String sessionName, Map<String, String> data,
            String correlationId);

    /**
     * Injects data into a session using the Session object.
     * 
     * @param session       The session object
     * @param data          Map of environment variable data to inject
     * @param correlationId For logging/tracing
     * @return Response indicating success or failure, or empty if the bridge is
     *         unreachable
     */
    Optional<DataInjectionResponse> injectData(Session session, Map<String, String> data,
            String correlationId);

    /**
     * Checks if the data bridge is healthy/reachable.
     * 
     * @param sessionName   The name of the session
     * @param correlationId For logging/tracing
     * @return true if health check succeeds
     */
    boolean healthCheck(String sessionName, String correlationId);
}

