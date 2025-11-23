package org.eclipse.theia.cloud.common.util;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;

import java.util.Optional;

/**
 * Utility class for credential bridge operations.
 */
public final class CredentialBridgeUtil {

    public static final String CREDENTIAL_BRIDGE_PORT_OPTION = "credentialBridgePort";
    public static final int DEFAULT_CREDENTIAL_BRIDGE_PORT = 16281;
    private static final String CREDENTIALS_PATH = "/credentials";
    private static final String HEALTH_PATH = "/health";

    private CredentialBridgeUtil() {
        // Utility class
    }

    /**
     * Gets the credential bridge port from app definition options. Defaults to
     * 16281 if not specified.
     * 
     * @param appDefSpec The app definition specification
     * @return The credential bridge port number
     */
    public static int getCredentialBridgePort(AppDefinitionSpec appDefSpec) {
        if (appDefSpec.getOptions() != null
                && appDefSpec.getOptions().containsKey(CREDENTIAL_BRIDGE_PORT_OPTION)) {
            try {
                return Integer.parseInt(appDefSpec.getOptions().get(CREDENTIAL_BRIDGE_PORT_OPTION));
            } catch (NumberFormatException e) {
                return DEFAULT_CREDENTIAL_BRIDGE_PORT;
            }
        }
        return DEFAULT_CREDENTIAL_BRIDGE_PORT;
    }

    /**
     * Constructs the credential injection URL for a session. Format:
     * http://{service-ip}:{port}/credentials
     * 
     * @param serviceIP The service cluster IP
     * @param port      The credential bridge port
     * @return Optional containing the URL, or empty if service IP is invalid
     */
    public static Optional<String> getCredentialInjectionURL(String serviceIP, int port) {
        if (serviceIP == null || serviceIP.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("http://" + serviceIP + ":" + port + CREDENTIALS_PATH);
    }

    /**
     * Constructs the health check URL for a session.
     * 
     * @param serviceIP The service cluster IP
     * @param port      The credential bridge port
     * @return Optional containing the URL, or empty if service IP is invalid
     */
    public static Optional<String> getHealthCheckURL(String serviceIP, int port) {
        if (serviceIP == null || serviceIP.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("http://" + serviceIP + ":" + port + HEALTH_PATH);
    }
}

