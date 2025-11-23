package org.eclipse.theia.cloud.common.k8s.resource.session;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Request object for injecting credentials into a session's credential bridge.
 */
public class CredentialInjectionRequest {

    private final Map<String, String> environment;

    /**
     * Creates a credential injection request.
     * 
     * @param environment Map of environment variable credentials to inject
     */
    public CredentialInjectionRequest(Map<String, String> environment) {
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
        // Don't log actual credential values for security
        return "CredentialInjectionRequest{" + "environmentKeys=" + environment.keySet() + '}';
    }
}

