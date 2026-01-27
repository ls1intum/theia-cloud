/********************************************************************************
 * Copyright (C) 2025 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.common.tracing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.sentry.BaggageHeader;
import io.sentry.ISpan;
import io.sentry.Sentry;
import io.sentry.SentryTraceHeader;

/**
 * Trace context for propagating distributed traces across service boundaries via Kubernetes annotations.
 * Stores the full sentry-trace header and baggage for proper trace continuation using Sentry.continueTrace().
 * 
 * The sentry-trace header format is: {trace_id}-{span_id}-{sampled}
 * - trace_id: 32 hex chars (128 bits)
 * - span_id: 16 hex chars (64 bits)
 * - sampled: optional, "1" (sampled), "0" (not sampled), or empty (defer)
 */
public final class TraceContext {

    /** Annotation key for the full sentry-trace header */
    private static final String ANNOTATION_SENTRY_TRACE = "theia-cloud.io/sentry-trace";
    
    /** Annotation key for the baggage header (Dynamic Sampling Context) */
    private static final String ANNOTATION_BAGGAGE = "theia-cloud.io/baggage";

    private final String sentryTrace;
    private final String baggage;

    private TraceContext(String sentryTrace, String baggage) {
        this.sentryTrace = sentryTrace;
        this.baggage = baggage;
    }

    /**
     * Returns the sentry-trace header value.
     * Format: {trace_id}-{span_id}-{sampled}
     */
    public String getSentryTrace() {
        return sentryTrace;
    }

    /**
     * Returns the baggage header value (may be null).
     */
    public String getBaggage() {
        return baggage;
    }

    /**
     * Returns the baggage as a list (for Sentry.continueTrace compatibility).
     */
    public List<String> getBaggageList() {
        if (baggage == null || baggage.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> list = new ArrayList<>(1);
        list.add(baggage);
        return list;
    }

    /**
     * Extracts the trace ID from the sentry-trace header.
     */
    public String getTraceId() {
        if (sentryTrace == null) {
            return null;
        }
        String[] parts = sentryTrace.split("-");
        return parts.length > 0 ? parts[0] : null;
    }

    /**
     * Extracts the span ID from the sentry-trace header.
     */
    public String getSpanId() {
        if (sentryTrace == null) {
            return null;
        }
        String[] parts = sentryTrace.split("-");
        return parts.length > 1 ? parts[1] : null;
    }

    /**
     * Extracts trace context from the current Sentry span/transaction.
     * Uses Sentry's built-in methods to get properly formatted headers.
     */
    public static Optional<TraceContext> fromCurrent() {
        ISpan current = Sentry.getSpan();
        if (current == null) {
            return Optional.empty();
        }

        // Get the sentry-trace header using Sentry's API
        SentryTraceHeader traceHeader = Sentry.getTraceparent();
        if (traceHeader == null) {
            return Optional.empty();
        }

        String sentryTrace = traceHeader.getValue();
        
        // Get baggage header for Dynamic Sampling Context
        BaggageHeader baggageHeader = Sentry.getBaggage();
        String baggage = baggageHeader != null ? baggageHeader.getValue() : null;

        return Optional.of(new TraceContext(sentryTrace, baggage));
    }

    /**
     * Extracts trace context from Kubernetes resource annotations.
     */
    public static Optional<TraceContext> fromAnnotations(Map<String, String> annotations) {
        if (annotations == null) {
            return Optional.empty();
        }

        String sentryTrace = annotations.get(ANNOTATION_SENTRY_TRACE);
        if (sentryTrace == null || sentryTrace.isEmpty()) {
            return Optional.empty();
        }

        String baggage = annotations.get(ANNOTATION_BAGGAGE);
        return Optional.of(new TraceContext(sentryTrace, baggage));
    }

    /**
     * Extracts trace context from Kubernetes ObjectMeta annotations.
     */
    public static Optional<TraceContext> fromMetadata(ObjectMeta metadata) {
        if (metadata == null || metadata.getAnnotations() == null) {
            return Optional.empty();
        }
        return fromAnnotations(metadata.getAnnotations());
    }

    /**
     * Converts trace context to Kubernetes annotations map.
     */
    public Map<String, String> toAnnotations() {
        Map<String, String> annotations = new HashMap<>();
        annotations.put(ANNOTATION_SENTRY_TRACE, sentryTrace);
        if (baggage != null && !baggage.isEmpty()) {
            annotations.put(ANNOTATION_BAGGAGE, baggage);
        }
        return annotations;
    }

    /**
     * Merges trace context annotations into an existing annotations map.
     */
    public void mergeInto(Map<String, String> annotations) {
        if (annotations == null) {
            return;
        }
        annotations.put(ANNOTATION_SENTRY_TRACE, sentryTrace);
        if (baggage != null && !baggage.isEmpty()) {
            annotations.put(ANNOTATION_BAGGAGE, baggage);
        }
    }

    @Override
    public String toString() {
        return "TraceContext{sentryTrace=" + sentryTrace + ", baggage=" + (baggage != null ? "present" : "null") + "}";
    }
}
