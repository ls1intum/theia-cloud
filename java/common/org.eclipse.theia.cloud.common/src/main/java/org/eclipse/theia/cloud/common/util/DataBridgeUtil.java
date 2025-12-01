package org.eclipse.theia.cloud.common.util;

import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;

import java.util.Optional;

/**
 * Utility class for data bridge operations.
 */
public final class DataBridgeUtil {

    public static final String DATA_BRIDGE_PORT_OPTION = "dataBridgePort";
    public static final String DATA_BRIDGE_ENABLED_OPTION = "dataBridgeEnabled";
    public static final int DEFAULT_DATA_BRIDGE_PORT = 16281;
    private static final String DATA_PATH = "/data";
    private static final String HEALTH_PATH = "/health";

    private DataBridgeUtil() {
        // Utility class
    }

    /**
     * Checks if data bridge is enabled for this app definition.
     * When enabled, env vars are injected at runtime via the data bridge
     * instead of being set as container environment variables.
     * 
     * @param appDefSpec The app definition specification
     * @return true if data bridge is enabled
     */
    public static boolean isDataBridgeEnabled(AppDefinitionSpec appDefSpec) {
        // if (appDefSpec.getOptions() == null) {
        //     return false;
        // }
        // String value = appDefSpec.getOptions().get(DATA_BRIDGE_ENABLED_OPTION);
        // return "true".equalsIgnoreCase(value);
        // TODO: Adapt this logic to remove hardcoding
        return true;
    }

    /**
     * Gets the data bridge port from app definition options. Defaults to
     * 16281 if not specified.
     * 
     * @param appDefSpec The app definition specification
     * @return The data bridge port number
     */
    public static int getDataBridgePort(AppDefinitionSpec appDefSpec) {
        if (appDefSpec.getOptions() != null
                && appDefSpec.getOptions().containsKey(DATA_BRIDGE_PORT_OPTION)) {
            try {
                return Integer.parseInt(appDefSpec.getOptions().get(DATA_BRIDGE_PORT_OPTION));
            } catch (NumberFormatException e) {
                return DEFAULT_DATA_BRIDGE_PORT;
            }
        }
        return DEFAULT_DATA_BRIDGE_PORT;
    }

    /**
     * Constructs the data injection URL for a session. Format:
     * http://{service-ip}:{port}/credentials
     * 
     * @param serviceIP The service cluster IP
     * @param port      The data bridge port
     * @return Optional containing the URL, or empty if service IP is invalid
     */
    public static Optional<String> getDataInjectionURL(String serviceIP, int port) {
        if (serviceIP == null || serviceIP.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("http://" + serviceIP + ":" + port + DATA_PATH);
    }

    /**
     * Constructs the health check URL for a session.
     * 
     * @param serviceIP The service cluster IP
     * @param port      The data bridge port
     * @return Optional containing the URL, or empty if service IP is invalid
     */
    public static Optional<String> getHealthCheckURL(String serviceIP, int port) {
        if (serviceIP == null || serviceIP.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of("http://" + serviceIP + ":" + port + HEALTH_PATH);
    }
}
