package org.eclipse.theia.cloud.common.k8s.resource.session;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Request object for injecting data into a session's data bridge.
 */
public class DataInjectionRequest {

    private final Map<String, String> environment;

    /**
     * Creates a data injection request.
     * 
     * @param environment Map of environment variable data to inject
     */
    public DataInjectionRequest(Map<String, String> environment) {
        this.environment = environment != null ? new HashMap<>(environment) : new HashMap<>();
    }

    /**
     * Gets the environment variables to inject.
     * 
     * @return Unmodifiable map of environment variables
     */
    public Map<String, String> getEnvironment() {
        return Collections.unmodifiableMap(environment);
    }

    @Override
    public String toString() {
        // Don't log actual data values for security
        return "DataInjectionRequest{" + "environmentKeys=" + environment.keySet() + '}';
    }
}

