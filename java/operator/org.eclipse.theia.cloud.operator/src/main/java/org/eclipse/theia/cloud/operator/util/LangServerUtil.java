package org.eclipse.theia.cloud.operator.util;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.operator.handler.AddedHandlerUtil;
import org.eclipse.theia.cloud.operator.handler.session.EagerSessionHandler;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.client.NamespacedKubernetesClient;
import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

public class LangServerUtil {

    private static final Logger LOGGER = org.apache.logging.log4j.LogManager.getLogger(LangServerUtil.class);

    public static void createAndApplyLSService(NamespacedKubernetesClient client, String namespace, String correlationId,
            String sessionResourceName, String sessionResourceUID, AppDefinition appDefinition) {
        LOGGER.info(formatLogMessage(correlationId, "Creating LS service for session " + sessionResourceName));
        Map<String, String> replacements = new HashMap<>();
        replacements.put("placeholder-servicename", "ls-" + sessionResourceName);
        replacements.put("placeholder-app", "ls-" + sessionResourceName);
        replacements.put("placeholder-namespace", namespace);
        replacements.put("placeholder-session", sessionResourceName);
        LangServerDetails lsDetails = getLangServerDetails(appDefinition.getSpec().getOptions().get("langserver-image"), appDefinition);
        replacements.put("placeholder-java-port", lsDetails.getPort());
        replacements.put("placeholder-rust-port", lsDetails.getPort());

        String serviceYaml;
        try {
            serviceYaml = JavaResourceUtil.readResourceAndReplacePlaceholders(AddedHandlerUtil.TEMPLATE_LS_SERVICE_YAML,
                    replacements, correlationId);
        } catch (IOException | URISyntaxException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while adjusting LS service template"), e);
            return;
        }

        K8sUtil.loadAndCreateServiceWithOwnerReference(client, namespace, correlationId, serviceYaml,
                "theia.cloud/v1beta9", "Session", sessionResourceName, sessionResourceUID, 0,
                Collections.emptyMap());
        LOGGER.info(formatLogMessage(correlationId, "Successfully created LS service for session " + sessionResourceName));
    }

    public static void createAndApplyLSDeployment(NamespacedKubernetesClient client, String namespace, String correlationId,
            String sessionResourceName, String sessionResourceUID, AppDefinition appDefinition,
            String lsImage) {
        LOGGER.info(formatLogMessage(correlationId, "Creating LS deployment for session " + sessionResourceName));
        Map<String, String> replacements = new HashMap<>();
        replacements.put("placeholder-depname", "ls-" + sessionResourceName);
        replacements.put("placeholder-app", "ls-" + sessionResourceName);
        replacements.put("placeholder-namespace", namespace);
        replacements.put("placeholder-image", lsImage);
        LangServerDetails lsDetails = getLangServerDetails(lsImage, appDefinition);
        replacements.put("placeholder-java-port", lsDetails.getPort());
        replacements.put("placeholder-rust-port", lsDetails.getPort());


        String deploymentYaml;
        try {
            deploymentYaml = JavaResourceUtil.readResourceAndReplacePlaceholders(
                    AddedHandlerUtil.TEMPLATE_LS_DEPLOYMENT_YAML, replacements, correlationId);
        } catch (IOException | URISyntaxException e) {
            LOGGER.error(formatLogMessage(correlationId, "Error while adjusting LS deployment template"), e);
            return;
        }

        K8sUtil.loadAndCreateDeploymentWithOwnerReference(client, namespace, correlationId, deploymentYaml,
                "theia.cloud/v1beta9", "Session", sessionResourceName, sessionResourceUID, 0,
                Collections.emptyMap(), deployment -> {
                    // No extra processing needed for now
                });
        LOGGER.info(formatLogMessage(correlationId, "Successfully created LS deployment for session " + sessionResourceName));
    }

    public static void updateTheiaDeploymentWithLangServerEnvVars(io.fabric8.kubernetes.api.model.apps.Deployment deployment, String sessionResourceName, String lsImage, AppDefinition appDefinition) {
        LOGGER.info(formatLogMessage("N/A", "Updating Theia deployment with LS env vars for session " + sessionResourceName));
        String lsServiceName = "ls-" + sessionResourceName;
        String lsHost = lsServiceName;
        LangServerDetails lsDetails = getLangServerDetails(lsImage, appDefinition);

        List<EnvVar> envVars = deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv();
        envVars.removeIf(e -> e.getName().equals(AddedHandlerUtil.ENV_LS_JAVA_HOST)
                || e.getName().equals(AddedHandlerUtil.ENV_LS_JAVA_PORT)
                || e.getName().equals(AddedHandlerUtil.ENV_LS_RUST_HOST)
                || e.getName().equals(AddedHandlerUtil.ENV_LS_RUST_PORT));

        envVars.add(new EnvVarBuilder().withName(lsDetails.getEnvHostKey()).withValue(lsHost).build());
        envVars.add(
                new EnvVarBuilder().withName(lsDetails.getEnvPortKey()).withValue(lsDetails.getPort()).build());
        LOGGER.info(formatLogMessage("N/A", "Successfully updated Theia deployment with LS env vars for session " + sessionResourceName));
    }

    public static void deleteLangServerResources(NamespacedKubernetesClient client, String sessionResourceName, String correlationId) {
        LOGGER.info(formatLogMessage(correlationId, "Deleting LS resources for session " + sessionResourceName));
        String lsResourceName = "ls-" + sessionResourceName;
        try {
            client.services().withName(lsResourceName).delete();
            client.apps().deployments().withName(lsResourceName).delete();
            LOGGER.info(formatLogMessage(correlationId, "Deleted LS resources: " + lsResourceName));
        } catch (io.fabric8.kubernetes.client.KubernetesClientException e) {
            LOGGER.warn(formatLogMessage(correlationId, "Error while deleting LS resources (might be already gone)"), e);
        }
    }


    private static LangServerDetails getLangServerDetails(String lsImage, AppDefinition appDefinition) {
        String lsPort;
        String envHostKey;
        String envPortKey;

        if (lsImage.contains("rust")) {
            lsPort = appDefinition.getSpec().getOptions().getOrDefault("langserver-rust-port", "5555");
            envHostKey = AddedHandlerUtil.ENV_LS_RUST_HOST;
            envPortKey = AddedHandlerUtil.ENV_LS_RUST_PORT;
        } else { // Default to Java
            lsPort = appDefinition.getSpec().getOptions().getOrDefault("langserver-java-port", "5556");
            envHostKey = AddedHandlerUtil.ENV_LS_JAVA_HOST;
            envPortKey = AddedHandlerUtil.ENV_LS_JAVA_PORT;
        }
        return new LangServerDetails(lsPort, envHostKey, envPortKey);
    }

    private static class LangServerDetails {
        private final String port;
        private final String envHostKey;
        private final String envPortKey;

        public LangServerDetails(String port, String envHostKey, String envPortKey) {
            this.port = port;
            this.envHostKey = envHostKey;
            this.envPortKey = envPortKey;
        }

        public String getPort() {
            return port;
        }

        public String getEnvHostKey() {
            return envHostKey;
        }

        public String getEnvPortKey() {
            return envPortKey;
        }
    }
}
