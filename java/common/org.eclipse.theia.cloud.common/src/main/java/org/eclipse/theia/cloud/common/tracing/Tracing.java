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
}
