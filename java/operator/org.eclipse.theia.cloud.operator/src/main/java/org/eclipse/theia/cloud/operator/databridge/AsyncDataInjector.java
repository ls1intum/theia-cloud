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
import io.sentry.SpanStatus;
import org.eclipse.theia.cloud.common.tracing.TraceContext;
import org.eclipse.theia.cloud.common.tracing.Tracing;

/**
 * Handles asynchronous data injection into sessions via the data bridge. Polls
 * the health endpoint until the data
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
     * Schedules asynchronous data injection for a session. Polls the health
     * endpoint until ready, then injects data
     * once.
     * 
     * @param parentSpan    The parent span to create a child span from for tracing
     * @param session       The session to inject data into
     * @param envVars       The environment variables to inject
     * @param correlationId For logging/tracing
     */
    public void scheduleInjection(ISpan parentSpan, Session session, Map<String, String> envVars,
            String correlationId) {
        String sessionName = session.getSpec().getName();
        String appDef = session.getSpec().getAppDefinition();
        int envVarCount = envVars.size();

        LOGGER.info(formatLogMessage(correlationId, "Scheduling async data injection for session: " + sessionName));

        // Extract trace context BEFORE scheduling - the parent span will be finished by
        // the time scheduler runs
        Optional<TraceContext> traceContext = TraceContext.fromSpan(parentSpan);

        scheduler.submit(() -> {
            // Create a new transaction linked to the same trace (parent span is already
            // finished)
            ISpan span = Tracing.continueTraceAsync(traceContext, "databridge.inject", "Data bridge injection");
            span.setTag("session.name", sessionName);
            span.setTag("app_definition", appDef);
            span.setData("correlation_id", correlationId);
            span.setData("env_var_count", envVarCount);

            pollHealthThenInject(span, session, envVars, correlationId, 0);
        });
    }

    /**
     * Polls the health endpoint and injects data when ready. Recursively schedules
     * itself for retries. Aggregate metrics (health check attempts, outcome) are tracked
     * on the parent span rather than creating individual spans for each health check.
     * 
     * @param span          The parent span from scheduleInjection - aggregate metrics are tracked here
     * @param session       The session to inject data into
     * @param envVars       The environment variables to inject
     * @param correlationId For logging/tracing
     * @param attempt       The current attempt number (0-indexed)
     */
    private void pollHealthThenInject(ISpan span, Session session, Map<String, String> envVars,
            String correlationId, int attempt) {
        String sessionName = session.getSpec().getName();

        if (attempt >= MAX_HEALTH_CHECKS) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Data bridge not ready after " + MAX_HEALTH_CHECKS + "s for session: " + sessionName));

            span.setTag("outcome", "timeout");
            span.setData("health_check_attempts", attempt);
            Tracing.finish(span, SpanStatus.DEADLINE_EXCEEDED);
            return;
        }

        // Check if data bridge is healthy (no individual span, just aggregate metrics)
        boolean healthy = dataBridgeClient.healthCheck(sessionName, correlationId);

        if (healthy) {
            // Ready - inject data once
            LOGGER.info(formatLogMessage(correlationId,
                    "Data bridge ready for session: " + sessionName + " (attempt " + (attempt + 1) + ")"));

            // Track aggregate metrics on the span
            span.setData("health_check_attempts", attempt + 1);
            span.setData("time_to_ready_ms", (attempt + 1) * HEALTH_CHECK_INTERVAL_MS);

            ISpan injectSpan = Tracing.childSpan(span, "databridge.inject_data", "Inject environment data");
            injectSpan.setData("env_var_count", envVars.size());

            try {
                Optional<DataInjectionResponse> response = dataBridgeClient.injectData(session, envVars, correlationId);

                if (response.isEmpty()) {
                    LOGGER.error(formatLogMessage(correlationId,
                            "Data injection failed - no response for session: " + sessionName));
                    injectSpan.setTag("outcome", "no_response");
                    Tracing.finish(injectSpan, SpanStatus.INTERNAL_ERROR);
                    span.setTag("outcome", "injection_failed");
                    span.setTag("failure.reason", "no_response");
                    Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                } else if (!response.get().isSuccess()) {
                    String error = response.get().getError();
                    LOGGER.error(formatLogMessage(correlationId,
                            "Data injection failed for session: " + sessionName + " - " + error));
                    injectSpan.setTag("outcome", "error");
                    injectSpan.setData("error_message", error);
                    Tracing.finish(injectSpan, SpanStatus.INTERNAL_ERROR);
                    span.setTag("outcome", "injection_failed");
                    span.setTag("failure.reason", "api_error");
                    span.setData("error_message", error);
                    Tracing.finish(span, SpanStatus.INTERNAL_ERROR);
                } else {
                    LOGGER.info(
                            formatLogMessage(correlationId, "Data injected successfully for session: " + sessionName));
                    injectSpan.setTag("outcome", "success");
                    Tracing.finishSuccess(injectSpan);
                    span.setTag("outcome", "success");
                    Tracing.finishSuccess(span);
                }
            } catch (Exception e) {
                LOGGER.error(formatLogMessage(correlationId, "Exception during data injection"), e);
                injectSpan.setTag("outcome", "exception");
                Tracing.finishError(injectSpan, e);
                span.setTag("outcome", "injection_failed");
                span.setTag("failure.reason", "exception");
                Tracing.finishError(span, e);
            }
            return;
        }

        // Not ready - schedule next health check
        if (attempt == 0) {
            LOGGER.debug(formatLogMessage(correlationId,
                    "Data bridge not ready yet for session: " + sessionName + ", polling..."));
        }

        scheduler.schedule(() -> pollHealthThenInject(span, session, envVars, correlationId, attempt + 1),
                HEALTH_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Shuts down the scheduler. Should be called when the operator is shutting
     * down.
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
