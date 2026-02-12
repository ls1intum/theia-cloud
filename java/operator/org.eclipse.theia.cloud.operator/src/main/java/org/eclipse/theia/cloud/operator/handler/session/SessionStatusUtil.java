package org.eclipse.theia.cloud.operator.handler.session;

import java.time.Instant;
import java.util.Optional;

import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.OperatorStatus;
import org.eclipse.theia.cloud.common.k8s.resource.ResourceStatus;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;
import org.eclipse.theia.cloud.common.k8s.resource.session.SessionStatus;

public final class SessionStatusUtil {

    public enum PreHandleResult {
        PROCEED, ALREADY_HANDLED, INTERRUPTED, PREVIOUS_ERROR
    }

    private SessionStatusUtil() {
    }

    public static PreHandleResult evaluateStatus(Session session, TheiaCloudClient client, String correlationId,
            Logger logger) {
        Optional<SessionStatus> status = Optional.ofNullable(session.getStatus());
        String operatorStatus = status.map(ResourceStatus::getOperatorStatus).orElse(OperatorStatus.NEW);

        if (OperatorStatus.HANDLED.equals(operatorStatus)) {
            return PreHandleResult.ALREADY_HANDLED;
        }
        if (OperatorStatus.HANDLING.equals(operatorStatus)) {
            markError(client, session, correlationId,
                    "Handling was unexpectedly interrupted. CorrelationId: " + correlationId);
            return PreHandleResult.INTERRUPTED;
        }
        if (OperatorStatus.ERROR.equals(operatorStatus)) {
            return PreHandleResult.PREVIOUS_ERROR;
        }
        return PreHandleResult.PROCEED;
    }

    public static void markHandling(TheiaCloudClient client, Session session, String correlationId) {
        client.sessions().updateStatus(correlationId, session, s -> s.setOperatorStatus(OperatorStatus.HANDLING));
    }

    public static void markHandled(TheiaCloudClient client, Session session, String correlationId, String message) {
        client.sessions().updateStatus(correlationId, session, s -> {
            s.setOperatorStatus(OperatorStatus.HANDLED);
            if (message != null) {
                s.setOperatorMessage(message);
            }
            s.setLastActivity(Instant.now().toEpochMilli());
        });
    }

    public static void markError(TheiaCloudClient client, Session session, String correlationId, String message) {
        client.sessions().updateStatus(correlationId, session, s -> {
            s.setOperatorStatus(OperatorStatus.ERROR);
            s.setOperatorMessage(message);
        });
    }
}
