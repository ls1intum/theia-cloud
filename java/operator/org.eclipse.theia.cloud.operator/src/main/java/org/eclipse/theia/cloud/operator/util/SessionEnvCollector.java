package org.eclipse.theia.cloud.operator.util;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Secret;

/**
 * Collects environment variables for a session from various sources: direct env vars, ConfigMaps, and Secrets.
 */
@Singleton
public class SessionEnvCollector {

    private static final Logger LOGGER = LogManager.getLogger(SessionEnvCollector.class);

    @Inject
    private TheiaCloudClient client;

    /**
     * Collects all custom environment variables for a session.
     * 
     * @param session       The session to collect env vars for
     * @param correlationId For logging/tracing
     * @return Map of environment variable names to values
     */
    public Map<String, String> collect(Session session, String correlationId) {
        Map<String, String> result = new HashMap<>();

        // Collect direct env vars
        Map<String, String> directEnvVars = session.getSpec().getEnvVars();
        if (directEnvVars != null && !directEnvVars.isEmpty()) {
            result.putAll(directEnvVars);
            LOGGER.debug(formatLogMessage(correlationId, "Collected " + directEnvVars.size() + " direct env vars"));
        }

        // Resolve env vars from ConfigMaps
        List<String> configMapNames = session.getSpec().getEnvVarsFromConfigMaps();
        if (configMapNames != null) {
            for (String configMapName : configMapNames) {
                resolveConfigMap(configMapName, result, correlationId);
            }
        }

        // Resolve env vars from Secrets
        List<String> secretNames = session.getSpec().getEnvVarsFromSecrets();
        if (secretNames != null) {
            for (String secretName : secretNames) {
                resolveSecret(secretName, result, correlationId);
            }
        }

        LOGGER.info(formatLogMessage(correlationId,
                "Collected " + result.size() + " total env vars for session " + session.getSpec().getName()));

        return result;
    }

    private void resolveConfigMap(String configMapName, Map<String, String> result, String correlationId) {
        try {
            ConfigMap configMap = client.kubernetes().configMaps().inNamespace(client.namespace())
                    .withName(configMapName).get();

            if (configMap == null) {
                LOGGER.warn(formatLogMessage(correlationId, "ConfigMap not found: " + configMapName));
                return;
            }

            Map<String, String> data = configMap.getData();
            if (data != null && !data.isEmpty()) {
                result.putAll(data);
                LOGGER.debug(formatLogMessage(correlationId,
                        "Resolved " + data.size() + " env vars from ConfigMap: " + configMapName));
            }
        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to resolve ConfigMap: " + configMapName), e);
        }
    }

    private void resolveSecret(String secretName, Map<String, String> result, String correlationId) {
        try {
            Secret secret = client.kubernetes().secrets().inNamespace(client.namespace()).withName(secretName).get();

            if (secret == null) {
                LOGGER.warn(formatLogMessage(correlationId, "Secret not found: " + secretName));
                return;
            }

            Map<String, String> data = secret.getData();
            if (data != null && !data.isEmpty()) {
                // Secret data is base64 encoded
                for (Map.Entry<String, String> entry : data.entrySet()) {
                    String decodedValue = new String(Base64.getDecoder().decode(entry.getValue()));
                    result.put(entry.getKey(), decodedValue);
                }
                LOGGER.debug(formatLogMessage(correlationId,
                        "Resolved " + data.size() + " env vars from Secret: " + secretName));
            }
        } catch (Exception e) {
            LOGGER.error(formatLogMessage(correlationId, "Failed to resolve Secret: " + secretName), e);
        }
    }
}
