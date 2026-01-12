package org.eclipse.theia.cloud.operator.databridge;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.DataBridgeClient;
import org.eclipse.theia.cloud.common.k8s.resource.session.DataInjectionResponse;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;

import com.google.inject.Inject;
import com.google.inject.Singleton;

/**
 * Handles asynchronous data injection into sessions via the data bridge. Polls the health endpoint until the data
 * bridge is ready, then injects data once.
 */
@Singleton
public class AsyncDataInjector {

    private static final Logger LOGGER = LogManager.getLogger(AsyncDataInjector.class);

    /** Maximum number of health check attempts (1 per second for 60 seconds) */
    private static final int MAX_HEALTH_CHECKS = 60;

    /** Interval between health checks in milliseconds */
    private static final int HEALTH_CHECK_INTERVAL_MS = 1000;

    private final ScheduledExecutorService scheduler;

    @Inject
    private DataBridgeClient dataBridgeClient;

    public AsyncDataInjector() {
        this.scheduler = Executors.newScheduledThreadPool(4);
    }

    /**
     * Schedules asynchronous data injection for a session. Polls the health endpoint until ready, then injects data
     * once.
     * 
     * @param session       The session to inject data into
     * @param envVars       The environment variables to inject
     * @param correlationId For logging/tracing
     */
    public void scheduleInjection(Session session, Map<String, String> envVars, String correlationId) {
        String sessionName = session.getSpec().getName();
        LOGGER.info(formatLogMessage(correlationId, "Scheduling async data injection for session: " + sessionName));

        scheduler.submit(() -> pollHealthThenInject(session, envVars, correlationId, 0));
    }

    private void pollHealthThenInject(Session session, Map<String, String> envVars, String correlationId, int attempt) {
        String sessionName = session.getSpec().getName();

        if (attempt >= MAX_HEALTH_CHECKS) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Data bridge not ready after " + MAX_HEALTH_CHECKS + "s for session: " + sessionName));
            return;
        }

        // Check if data bridge is healthy
        if (dataBridgeClient.healthCheck(sessionName, correlationId)) {
            // Ready - inject data once
            LOGGER.info(formatLogMessage(correlationId,
                    "Data bridge ready for session: " + sessionName + " (attempt " + (attempt + 1) + ")"));

            Optional<DataInjectionResponse> response = dataBridgeClient.injectData(session, envVars, correlationId);

            if (response.isEmpty()) {
                LOGGER.error(formatLogMessage(correlationId,
                        "Data injection failed - no response for session: " + sessionName));
            } else if (!response.get().isSuccess()) {
                LOGGER.error(formatLogMessage(correlationId,
                        "Data injection failed for session: " + sessionName + " - " + response.get().getError()));
            } else {
                LOGGER.info(formatLogMessage(correlationId, "Data injected successfully for session: " + sessionName));
            }
            return;
        }

        // Not ready - schedule next health check
        if (attempt == 0) {
            LOGGER.debug(formatLogMessage(correlationId,
                    "Data bridge not ready yet for session: " + sessionName + ", polling..."));
        }

        scheduler.schedule(() -> pollHealthThenInject(session, envVars, correlationId, attempt + 1),
                HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Shuts down the scheduler. Should be called when the operator is shutting down.
     */
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
