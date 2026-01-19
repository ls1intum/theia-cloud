package org.eclipse.theia.cloud.operator.handler.session;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.operator.util.SentryHelper;

import com.google.inject.Inject;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.SpanStatus;

/**
 * Tries to handle a session with {@link EagerSessionHandler} first. If there is no prewarmed capacity left, falls back
 * to {@link LazySessionHandler}.
 */
public class EagerWithLazyFallbackSessionHandler implements SessionHandler {

    /** Value indicating session was started lazily after eager capacity was exhausted. */
    public static final String SESSION_START_STRATEGY_LAZY_FALLBACK = "lazy-fallback";

    private static final Logger LOGGER = LogManager.getLogger(EagerWithLazyFallbackSessionHandler.class);

    @Inject
    private EagerSessionHandler eager;

    @Inject
    private LazySessionHandler lazy;

    @Inject
    private TheiaCloudClient client;

    @Override
    public boolean sessionAdded(Session session, String correlationId) {
        String sessionName = session.getSpec().getName();
        String appDef = session.getSpec().getAppDefinition();
        String user = session.getSpec().getUser();

        ITransaction tx = SentryHelper.startSessionTransaction("added", sessionName, appDef, user, correlationId);

        try {
            // Try eager start first
            ISpan eagerSpan = tx.startChild("session.eager_attempt", "Attempt eager session start");
            eagerSpan.setTag("session.strategy", "eager");

            EagerSessionHandler.EagerSessionAddedOutcome eagerOutcome = eager.trySessionAdded(session, correlationId);
            eagerSpan.setData("eager_outcome", eagerOutcome.name());

            if (eagerOutcome == EagerSessionHandler.EagerSessionAddedOutcome.HANDLED) {
                SentryHelper.finishSuccess(eagerSpan);
                tx.setTag("session.strategy", "eager");
                SentryHelper.finishSuccess(tx);
                return true;
            }

            if (eagerOutcome == EagerSessionHandler.EagerSessionAddedOutcome.ERROR) {
                SentryHelper.finishWithOutcome(eagerSpan, "error", SpanStatus.INTERNAL_ERROR);
                tx.setTag("session.strategy", "eager");
                SentryHelper.finishWithOutcome(tx, "error", SpanStatus.INTERNAL_ERROR);
                return false;
            }

            // NO_CAPACITY - fall back to lazy
            SentryHelper.finishWithOutcome(eagerSpan, "no_capacity", SpanStatus.RESOURCE_EXHAUSTED);
            SentryHelper.tagFallback(tx, "no_prewarmed_capacity");

            LOGGER.info(formatLogMessage(correlationId,
                    "No prewarmed capacity left. Falling back to lazy session handling."));

            ISpan lazySpan = tx.startChild("session.lazy_fallback", "Fallback to lazy session start");
            lazySpan.setTag("session.strategy", "lazy-fallback");
            lazySpan.setTag("fallback.reason", "no_prewarmed_capacity");

            boolean lazyResult = lazy.sessionAdded(session, correlationId);

            if (lazyResult) {
                annotateSessionStrategy(session, correlationId, SESSION_START_STRATEGY_LAZY_FALLBACK);
                SentryHelper.finishSuccess(lazySpan);
                tx.setTag("session.strategy", "lazy-fallback");
                SentryHelper.finishSuccess(tx);
            } else {
                SentryHelper.finishWithOutcome(lazySpan, "failure", SpanStatus.INTERNAL_ERROR);
                tx.setTag("session.strategy", "lazy-fallback");
                SentryHelper.finishWithOutcome(tx, "failure", SpanStatus.INTERNAL_ERROR);
            }

            return lazyResult;

        } catch (Exception e) {
            SentryHelper.captureError(e, "session.added", correlationId);
            SentryHelper.finishError(tx, e);
            throw e;
        }
    }

    @Override
    public boolean sessionDeleted(Session session, String correlationId) {
        String sessionName = session.getSpec().getName();
        String appDef = session.getSpec().getAppDefinition();
        String user = session.getSpec().getUser();

        ITransaction tx = SentryHelper.startSessionTransaction("deleted", sessionName, appDef, user, correlationId);

        try {
            String strategy = Optional.ofNullable(session.getMetadata()).map(m -> m.getAnnotations())
                    .map(a -> a.get(EagerSessionHandler.SESSION_START_STRATEGY_ANNOTATION)).orElse("unknown");

            tx.setTag("session.start_strategy", strategy);

            boolean result;
            if (EagerSessionHandler.SESSION_START_STRATEGY_EAGER.equals(strategy)) {
                ISpan span = tx.startChild("session.eager_cleanup", "Eager session cleanup");
                span.setTag("cleanup.type", "eager");
                result = eager.sessionDeleted(session, correlationId);
                SentryHelper.finishWithOutcome(span, result);
            } else {
                ISpan span = tx.startChild("session.lazy_cleanup", "Lazy session cleanup");
                span.setTag("cleanup.type", "lazy");
                result = lazy.sessionDeleted(session, correlationId);
                SentryHelper.finishWithOutcome(span, result);
            }

            SentryHelper.finishWithOutcome(tx, result);
            return result;

        } catch (Exception e) {
            SentryHelper.captureError(e, "session.deleted", correlationId);
            SentryHelper.finishError(tx, e);
            throw e;
        }
    }

    private void annotateSessionStrategy(Session session, String correlationId, String strategy) {
        String name = session.getMetadata().getName();
        client.sessions().edit(correlationId, name, s -> {
            Map<String, String> annotations = s.getMetadata().getAnnotations();
            if (annotations == null) {
                annotations = new HashMap<>();
                s.getMetadata().setAnnotations(annotations);
            }
            annotations.put(EagerSessionHandler.SESSION_START_STRATEGY_ANNOTATION, strategy);
        });
    }
}
