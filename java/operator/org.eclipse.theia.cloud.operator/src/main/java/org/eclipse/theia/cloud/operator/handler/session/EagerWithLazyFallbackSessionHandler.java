/**
 * Copyright (C) 2025.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.theia.cloud.operator.handler.session;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.session.Session;

import com.google.inject.Inject;

/**
 * Tries to handle a session with {@link EagerSessionHandler} first. If there is no prewarmed capacity left, falls back
 * to {@link LazySessionHandler}.
 */
public class EagerWithLazyFallbackSessionHandler implements SessionHandler {

    public static final String SESSION_START_STRATEGY_ANNOTATION = "theia-cloud.io/session-start-strategy";
    public static final String SESSION_START_STRATEGY_EAGER = "eager";
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
        EagerSessionHandler.EagerSessionAddedOutcome eagerOutcome = eager.trySessionAdded(session, correlationId);
        if (eagerOutcome == EagerSessionHandler.EagerSessionAddedOutcome.HANDLED) {
            return true;
        }
        if (eagerOutcome == EagerSessionHandler.EagerSessionAddedOutcome.ERROR) {
            return false;
        }

        LOGGER.info(formatLogMessage(correlationId,
                "No prewarmed capacity left. Falling back to lazy session handling."));

        boolean lazyResult = lazy.sessionAdded(session, correlationId);
        if (lazyResult) {
            annotateSessionStrategy(session, correlationId, SESSION_START_STRATEGY_LAZY_FALLBACK);
        }
        return lazyResult;
    }

    @Override
    public boolean sessionDeleted(Session session, String correlationId) {
        String strategy = Optional.ofNullable(session.getMetadata())
                .map(m -> m.getAnnotations())
                .map(a -> a.get(SESSION_START_STRATEGY_ANNOTATION))
                .orElse(null);

        if (SESSION_START_STRATEGY_EAGER.equals(strategy)) {
            return eager.sessionDeleted(session, correlationId);
        }
        return lazy.sessionDeleted(session, correlationId);
    }

    private void annotateSessionStrategy(Session session, String correlationId, String strategy) {
        String name = session.getMetadata().getName();
        client.sessions().edit(correlationId, name, s -> {
            Map<String, String> annotations = s.getMetadata().getAnnotations();
            if (annotations == null) {
                annotations = new HashMap<>();
                s.getMetadata().setAnnotations(annotations);
            }
            annotations.put(SESSION_START_STRATEGY_ANNOTATION, strategy);
        });
    }
}

