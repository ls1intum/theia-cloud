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

import java.util.function.Supplier;
import java.util.Optional;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;
import io.sentry.TransactionContext;
import io.sentry.TransactionOptions;

/**
 * Minimal, elegant tracing API. Always creates child spans when a parent exists.
 * Pass spans explicitly in method signatures for clarity.
 */
public final class Tracing {

    private Tracing() {
    }

    /**
     * Starts a new transaction (root span of a trace).
     */
    public static ITransaction startTransaction(String operation, String description) {
        return Sentry.startTransaction(operation, description);
    }

    /**
     * Creates a child span from a parent (transaction or span).
     * If parent is null, starts a new transaction instead.
     */
    public static ISpan childSpan(ISpan parent, String operation, String description) {
        if (parent != null) {
            return parent.startChild(operation, description);
        }
        return Sentry.startTransaction(operation, description);
    }

    /**
     * Creates a child span from a transaction.
     */
    public static ISpan childSpan(ITransaction parent, String operation, String description) {
        if (parent != null) {
            return parent.startChild(operation, description);
        }
        return Sentry.startTransaction(operation, description);
    }

    /**
     * Creates a child span from the current active span/transaction.
     * If none exists, starts a new transaction.
     */
    public static ISpan childSpan(String operation, String description) {
        ISpan current = Sentry.getSpan();
        return childSpan(current, operation, description);
    }

    /**
     * Continues a trace from a TraceContext by creating a properly linked transaction.
     * Uses Sentry.continueTrace() to properly connect traces across service boundaries.
     * 
     * This enables distributed tracing where:
     * - The Service creates a session with trace context in annotations
     * - The Operator extracts the context and continues the same trace
     * - Both transactions appear connected in Sentry's trace view
     */
    public static ISpan continueTrace(TraceContext context, String name, String operation) {
        // Use Sentry.continueTrace() to properly parse and store trace context
        TransactionContext txContext = Sentry.continueTrace(
            context.getSentryTrace(), 
            context.getBaggageList()
        );

        if (txContext != null) {
            // Create transaction options to bind to scope
            TransactionOptions options = new TransactionOptions();
            options.setBindToScope(true);
            
            // Start transaction with the continued context
            ITransaction tx = Sentry.startTransaction(txContext, options);
            tx.setName(name);
            tx.setOperation(operation);
            return tx;
        }

        // Fallback: if continuation fails, start new transaction with reference tags
        ITransaction tx = Sentry.startTransaction(name, operation);
        return tx;
    }

    /**
     * Finishes a span with the given status.
     */
    public static void finish(ISpan span, SpanStatus status) {
        if (span != null) {
            span.setStatus(status);
            span.finish();
        }
    }

    /**
     * Finishes a span with success status.
     */
    public static void finishSuccess(ISpan span) {
        finish(span, SpanStatus.OK);
    }

    /**
     * Finishes a span with error status and captures exception.
     */
    public static void finishError(ISpan span, Throwable error) {
        if (span != null) {
            span.setStatus(SpanStatus.INTERNAL_ERROR);
            if (error != null) {
                span.setThrowable(error);
                Sentry.captureException(error);
            }
            span.finish();
        }
    }

    /**
     * Executes a supplier within a span, automatically finishing it.
     */
    public static <T> T traced(ISpan parent, String operation, String description, Supplier<T> action) {
        ISpan span = childSpan(parent, operation, description);
        try {
            T result = action.get();
            finishSuccess(span);
            return result;
        } catch (Exception e) {
            finishError(span, e);
            throw e;
        }
    }

    /**
     * Executes a runnable within a span, automatically finishing it.
     */
    public static void traced(ISpan parent, String operation, String description, Runnable action) {
        traced(parent, operation, description, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Continues a trace for async operations that run after the parent span is finished.
     * Creates a new transaction linked to the same trace as the parent.
     * 
     * IMPORTANT: For async operations scheduled via executors, the parent span will typically
     * be finished before the async task runs. You cannot create child spans from finished spans.
     * Instead, extract TraceContext from the parent span BEFORE scheduling, then call this
     * method in the async task to create a linked transaction.
     * 
     * Usage pattern:
     * <pre>
     * // Before scheduling async task (while span is still active)
     * Optional<TraceContext> traceContext = TraceContext.fromSpan(parentSpan);
     * 
     * executor.submit(() -> {
     *     // In async task (parent span may be finished)
     *     ISpan span = Tracing.continueTraceAsync(traceContext, "async.operation", "Async operation");
     *     try {
     *         // do work
     *         Tracing.finishSuccess(span);
     *     } catch (Exception e) {
     *         Tracing.finishError(span, e);
     *     }
     * });
     * </pre>
     * 
     * @param traceContext The trace context extracted from the parent span (use TraceContext.fromSpan())
     * @param name The name for the new transaction
     * @param operation The operation name
     * @return A new transaction linked to the same trace, or a standalone transaction if context is empty
     */
    public static ISpan continueTraceAsync(Optional<TraceContext> traceContext, String name, String operation) {
        if (traceContext.isPresent()) {
            return continueTrace(traceContext.get(), name, operation);
        }
        // Fallback: start a new unlinked transaction
        TransactionOptions options = new TransactionOptions();
        options.setBindToScope(true);
        return Sentry.startTransaction(name, operation, options);
    }
}
