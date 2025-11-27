package org.eclipse.theia.cloud.common.k8s.client;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.session.DataInjectionResponse;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.util.DataBridgeUtil;
import org.json.JSONObject;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Default implementation of {@link DataBridgeClient} using OkHttp.
 */
public class DefaultDataBridgeClient implements DataBridgeClient {

    private static final Logger LOGGER = LogManager.getLogger(DefaultDataBridgeClient.class);

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final TheiaCloudClient theiaCloudClient;

    /**
     * Creates a client with default timeout settings.
     * 
     * @param theiaCloudClient The Theia Cloud client for accessing K8s resources
     */
    public DefaultDataBridgeClient(TheiaCloudClient theiaCloudClient) {
        this(theiaCloudClient, createDefaultHttpClient());
    }

    /**
     * Creates a client with custom HTTP client.
     * 
     * @param theiaCloudClient The Theia Cloud client for accessing K8s resources
     * @param httpClient       Custom HTTP client
     */
    public DefaultDataBridgeClient(TheiaCloudClient theiaCloudClient, OkHttpClient httpClient) {
        this.theiaCloudClient = theiaCloudClient;
        this.httpClient = httpClient;
    }

    private static OkHttpClient createDefaultHttpClient() {
        return new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).build();
    }

    @Override
    public Optional<DataInjectionResponse> injectData(String sessionName, Map<String, String> data,
            String correlationId) {
        Optional<Session> session = theiaCloudClient.sessions().get(sessionName);
        if (session.isEmpty()) {
            LOGGER.warn(formatLogMessage(correlationId, "Session not found: " + sessionName));
            return Optional.empty();
        }
        return injectData(session.get(), data, correlationId);
    }

    @Override
    public Optional<DataInjectionResponse> injectData(Session session, Map<String, String> data,
            String correlationId) {
        String sessionName = session.getSpec().getName();

        // Get the session's service IP
        Optional<String> serviceIP = theiaCloudClient.getClusterIPFromSessionName(sessionName);

        if (serviceIP.isEmpty()) {
            LOGGER.warn(
                    formatLogMessage(correlationId, "Could not resolve service IP for session: " + sessionName));
            return Optional.empty();
        }

        // Get the app definition to find the data bridge port
        String appDefName = session.getSpec().getAppDefinition();
        Optional<AppDefinition> appDef = theiaCloudClient.appDefinitions().get(appDefName);

        if (appDef.isEmpty()) {
            LOGGER.warn(formatLogMessage(correlationId, "App definition not found: " + appDefName));
            return Optional.empty();
        }

        int port = DataBridgeUtil.getDataBridgePort(appDef.get().getSpec());

        // Build the URL
        Optional<String> url = DataBridgeUtil.getDataInjectionURL(serviceIP.get(), port);

        if (url.isEmpty()) {
            LOGGER.warn(formatLogMessage(correlationId, "Could not construct data injection URL"));
            return Optional.empty();
        }

        return performInjection(url.get(), data, correlationId, sessionName);
    }

    private Optional<DataInjectionResponse> performInjection(String url, Map<String, String> data,
            String correlationId, String sessionName) {
        LOGGER.info(formatLogMessage(correlationId, "Injecting data to session: " + sessionName + " at " + url));

        try {
            // Build request body matching the TypeScript schema
            JSONObject requestBody = new JSONObject();
            JSONObject environment = new JSONObject(data);
            requestBody.put("environment", environment);

            RequestBody body = RequestBody.create(JSON_MEDIA_TYPE, requestBody.toString());

            // Build and execute request
            Request request = new Request.Builder().url(url).post(body).build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful()) {
                    LOGGER.info(formatLogMessage(correlationId,
                            "Successfully injected data to: " + sessionName));
                    return Optional
                            .of(DataInjectionResponse.success("Data injected successfully"));
                } else {
                    String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                    LOGGER.warn(formatLogMessage(correlationId, "Failed to inject data. Status: "
                            + response.code() + ", Body: " + errorBody));
                    return Optional.of(DataInjectionResponse
                            .failure("HTTP " + response.code() + ": " + errorBody));
                }
            }
        } catch (IOException e) {
            LOGGER.error(
                    formatLogMessage(correlationId, "IOException while injecting data to: " + sessionName), e);
            return Optional.of(DataInjectionResponse.failure("Network error: " + e.getMessage()));
        }
    }

    @Override
    public boolean healthCheck(String sessionName, String correlationId) {
        Optional<Session> session = theiaCloudClient.sessions().get(sessionName);
        if (session.isEmpty()) {
            return false;
        }

        Optional<String> serviceIP = theiaCloudClient.getClusterIPFromSessionName(sessionName);

        if (serviceIP.isEmpty()) {
            return false;
        }

        // Get port from app definition
        String appDefName = session.get().getSpec().getAppDefinition();
        Optional<AppDefinition> appDef = theiaCloudClient.appDefinitions().get(appDefName);

        int port = appDef.isPresent() ? DataBridgeUtil.getDataBridgePort(appDef.get().getSpec())
                : DataBridgeUtil.DEFAULT_DATA_BRIDGE_PORT;

        Optional<String> url = DataBridgeUtil.getHealthCheckURL(serviceIP.get(), port);

        if (url.isEmpty()) {
            return false;
        }

        try {
            Request request = new Request.Builder().url(url.get()).get().build();

            try (Response response = httpClient.newCall(request).execute()) {
                boolean healthy = response.isSuccessful();
                if (healthy) {
                    LOGGER.debug(formatLogMessage(correlationId, "Health check passed for session: " + sessionName));
                }
                return healthy;
            }
        } catch (IOException e) {
            LOGGER.debug(formatLogMessage(correlationId, "Health check failed for session: " + sessionName), e);
            return false;
        }
    }
}

