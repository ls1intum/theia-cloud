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

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;

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
        String appDef = session.getSpec().getAppDefinition();
        int envVarCount = envVars.size();

        LOGGER.info(formatLogMessage(correlationId, "Scheduling async data injection for session: " + sessionName));

        scheduler.submit(() -> {
            // Start a new transaction for the async operation
            ITransaction tx = Sentry.startTransaction("databridge.inject", "databridge");
            tx.setTag("session.name", sessionName);
            tx.setTag("app_definition", appDef);
            tx.setData("correlation_id", correlationId);
            tx.setData("env_var_count", envVarCount);

            pollHealthThenInject(tx, session, envVars, correlationId, 0);
        });
    }

    private void pollHealthThenInject(ITransaction tx, Session session, Map<String, String> envVars,
            String correlationId, int attempt) {
        String sessionName = session.getSpec().getName();

        if (attempt >= MAX_HEALTH_CHECKS) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Data bridge not ready after " + MAX_HEALTH_CHECKS + "s for session: " + sessionName));

            tx.setTag("outcome", "timeout");
            tx.setData("health_check_attempts", attempt);
            tx.setStatus(SpanStatus.DEADLINE_EXCEEDED);
            tx.finish();
            return;
        }

        // Check if data bridge is healthy
        ISpan healthSpan = tx.startChild("databridge.health_check", "Health check attempt " + (attempt + 1));
        healthSpan.setData("attempt", attempt + 1);

        boolean healthy = dataBridgeClient.healthCheck(sessionName, correlationId);
        healthSpan.setTag("healthy", String.valueOf(healthy));
        healthSpan.setStatus(healthy ? SpanStatus.OK : SpanStatus.UNAVAILABLE);
        healthSpan.finish();

        if (healthy) {
            // Ready - inject data once
            LOGGER.info(formatLogMessage(correlationId,
                    "Data bridge ready for session: " + sessionName + " (attempt " + (attempt + 1) + ")"));

            tx.setData("health_check_attempts", attempt + 1);
            tx.setData("time_to_ready_ms", (attempt + 1) * HEALTH_CHECK_INTERVAL_MS);

            ISpan injectSpan = tx.startChild("databridge.inject_data", "Inject environment data");
            injectSpan.setData("env_var_count", envVars.size());

            try {
                Optional<DataInjectionResponse> response = dataBridgeClient.injectData(session, envVars, correlationId);

                if (response.isEmpty()) {
                    LOGGER.error(formatLogMessage(correlationId,
                            "Data injection failed - no response for session: " + sessionName));
                    injectSpan.setTag("outcome", "no_response");
                    injectSpan.setStatus(SpanStatus.INTERNAL_ERROR);
                    tx.setTag("outcome", "injection_failed");
                    tx.setTag("failure.reason", "no_response");
                    tx.setStatus(SpanStatus.INTERNAL_ERROR);
                } else if (!response.get().isSuccess()) {
                    String error = response.get().getError();
                    LOGGER.error(formatLogMessage(correlationId,
                            "Data injection failed for session: " + sessionName + " - " + error));
                    injectSpan.setTag("outcome", "error");
                    injectSpan.setData("error_message", error);
                    injectSpan.setStatus(SpanStatus.INTERNAL_ERROR);
                    tx.setTag("outcome", "injection_failed");
                    tx.setTag("failure.reason", "api_error");
                    tx.setData("error_message", error);
                    tx.setStatus(SpanStatus.INTERNAL_ERROR);
                } else {
                    LOGGER.info(
                            formatLogMessage(correlationId, "Data injected successfully for session: " + sessionName));
                    injectSpan.setTag("outcome", "success");
                    injectSpan.setStatus(SpanStatus.OK);
                    tx.setTag("outcome", "success");
                    tx.setStatus(SpanStatus.OK);
                }
            } catch (Exception e) {
                LOGGER.error(formatLogMessage(correlationId, "Exception during data injection"), e);
                injectSpan.setTag("outcome", "exception");
                injectSpan.setThrowable(e);
                injectSpan.setStatus(SpanStatus.INTERNAL_ERROR);
                tx.setTag("outcome", "injection_failed");
                tx.setTag("failure.reason", "exception");
                tx.setThrowable(e);
                tx.setStatus(SpanStatus.INTERNAL_ERROR);
                Sentry.captureException(e);
            } finally {
                injectSpan.finish();
                tx.finish();
            }
            return;
        }

        // Not ready - schedule next health check
        if (attempt == 0) {
            LOGGER.debug(formatLogMessage(correlationId,
                    "Data bridge not ready yet for session: " + sessionName + ", polling..."));
        }

        scheduler.schedule(() -> pollHealthThenInject(tx, session, envVars, correlationId, attempt + 1),
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
