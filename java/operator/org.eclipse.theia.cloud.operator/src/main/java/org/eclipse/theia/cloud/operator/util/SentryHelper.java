/********************************************************************************
 * Copyright (C) 2025 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.operator.util;

import java.util.function.Supplier;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.Sentry;
import io.sentry.SpanStatus;

/**
 * Helper class for consistent Sentry instrumentation across the operator codebase.
 * Uses span tags for aggregation - avoiding direct metrics API calls.
 * 
 * Tag conventions for aggregation queries:
 * - session.strategy: eager | lazy | lazy-fallback
 * - session.outcome: success | error | no_capacity
 * - resource.type: service | deployment | configmap | ingress
 * - resource.operation: create | delete | update | reserve | release
 * - pool.outcome: success | no_capacity | error
 */
public final class SentryHelper {

    private SentryHelper() {
    }

    // ========== Transaction Starters ==========

    /**
     * Starts a transaction for session handling with standard tags.
     */
    public static ITransaction startSessionTransaction(String action, String sessionName, String appDef,
            String user, String correlationId) {
        ITransaction tx = Sentry.startTransaction("session." + action, "session");
        tx.setTag("session.name", sessionName);
        tx.setTag("app_definition", appDef);
        tx.setTag("user", user);
        tx.setData("correlation_id", correlationId);
        return tx;
    }

    /**
     * Starts a transaction for app definition handling.
     */
    public static ITransaction startAppDefTransaction(String action, String appDefName, int minInstances,
            String correlationId) {
        ITransaction tx = Sentry.startTransaction("appdef." + action, "appdef");
        tx.setTag("app_definition", appDefName);
        tx.setData("min_instances", minInstances);
        tx.setData("correlation_id", correlationId);
        return tx;
    }

    /**
     * Starts a transaction for pool operations.
     */
    public static ITransaction startPoolTransaction(String action, String appDefName, String correlationId) {
        ITransaction tx = Sentry.startTransaction("pool." + action, "pool");
        tx.setTag("app_definition", appDefName);
        tx.setData("correlation_id", correlationId);
        return tx;
    }

    // ========== Span Creation ==========

    /**
     * Creates a child span from the current span or starts a new transaction.
     */
    public static ISpan startSpan(String operation, String description) {
        ISpan currentSpan = Sentry.getSpan();
        if (currentSpan != null) {
            return currentSpan.startChild(operation, description);
        }
        return Sentry.startTransaction(operation, description);
    }

    /**
     * Creates a span for a K8s resource operation with standard tags.
     */
    public static ISpan startResourceSpan(ISpan parent, String resourceType, String operation, String resourceName) {
        ISpan span = parent.startChild("k8s." + resourceType + "." + operation,
                operation + " " + resourceType + " " + resourceName);
        span.setTag("resource.type", resourceType);
        span.setTag("resource.operation", operation);
        span.setData("resource.name", resourceName);
        return span;
    }

    /**
     * Creates a span for pool operations.
     */
    public static ISpan startPoolSpan(ISpan parent, String operation, String appDefName, Integer instanceId) {
        String desc = instanceId != null ? operation + " instance " + instanceId : operation;
        ISpan span = parent.startChild("pool." + operation, desc);
        span.setTag("app_definition", appDefName);
        span.setTag("pool.operation", operation);
        if (instanceId != null) {
            span.setData("instance_id", instanceId);
        }
        return span;
    }

    /**
     * Creates a span for reconciliation operations.
     */
    public static ISpan startReconcileSpan(ISpan parent, String resourceType, int targetCount) {
        ISpan span = parent.startChild("reconcile." + resourceType, "Reconcile " + resourceType);
        span.setTag("resource.type", resourceType);
        span.setData("target_count", targetCount);
        return span;
    }

    // ========== Traced Execution Helpers ==========

    /**
     * Runs an operation within a span, handling success/error status automatically.
     */
    public static <T> T traced(String operation, String description, Supplier<T> action) {
        ISpan span = startSpan(operation, description);
        try {
            T result = action.get();
            span.setStatus(SpanStatus.OK);
            return result;
        } catch (Exception e) {
            span.setStatus(SpanStatus.INTERNAL_ERROR);
            span.setThrowable(e);
            Sentry.captureException(e);
            throw e;
        } finally {
            span.finish();
        }
    }

    /**
     * Runs a void operation within a span.
     */
    public static void traced(String operation, String description, Runnable action) {
        traced(operation, description, () -> {
            action.run();
            return null;
        });
    }

    /**
     * Runs an operation with a boolean result within a span, tagging outcome.
     */
    public static boolean tracedBoolean(String operation, String description, Supplier<Boolean> action) {
        ISpan span = startSpan(operation, description);
        try {
            boolean result = action.get();
            span.setTag("outcome", result ? "success" : "failure");
            span.setStatus(result ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
            return result;
        } catch (Exception e) {
            span.setTag("outcome", "error");
            span.setStatus(SpanStatus.INTERNAL_ERROR);
            span.setThrowable(e);
            Sentry.captureException(e);
            throw e;
        } finally {
            span.finish();
        }
    }

    // ========== Span Finishing Helpers ==========

    /**
     * Finishes a span with success status and outcome tag.
     */
    public static void finishSuccess(ISpan span) {
        if (span != null) {
            span.setTag("outcome", "success");
            span.setStatus(SpanStatus.OK);
            span.finish();
        }
    }

    /**
     * Finishes a span with error status, captures exception.
     */
    public static void finishError(ISpan span, Throwable error) {
        if (span != null) {
            span.setTag("outcome", "error");
            span.setStatus(SpanStatus.INTERNAL_ERROR);
            if (error != null) {
                span.setThrowable(error);
                Sentry.captureException(error);
            }
            span.finish();
        }
    }

    /**
     * Finishes a span based on boolean success, with appropriate outcome tag.
     */
    public static void finishWithOutcome(ISpan span, boolean success) {
        if (span != null) {
            span.setTag("outcome", success ? "success" : "failure");
            span.setStatus(success ? SpanStatus.OK : SpanStatus.INTERNAL_ERROR);
            span.finish();
        }
    }

    /**
     * Finishes a span with a custom outcome tag.
     */
    public static void finishWithOutcome(ISpan span, String outcome, SpanStatus status) {
        if (span != null) {
            span.setTag("outcome", outcome);
            span.setStatus(status);
            span.finish();
        }
    }

    // ========== Session-Specific Helpers ==========

    /**
     * Tags a span with session strategy info for aggregation.
     */
    public static void tagSessionStrategy(ISpan span, String strategy) {
        if (span != null) {
            span.setTag("session.strategy", strategy);
        }
    }

    /**
     * Tags a span for fallback scenario.
     */
    public static void tagFallback(ISpan span, String reason) {
        if (span != null) {
            span.setTag("fallback", "true");
            span.setTag("fallback.reason", reason);
        }
    }

    // ========== Reconcile Result Tagging ==========

    /**
     * Tags a reconcile span with operation counts for aggregation.
     */
    public static void tagReconcileResult(ISpan span, int created, int deleted, int recreated, int skipped,
            boolean success) {
        if (span != null) {
            span.setData("created_count", created);
            span.setData("deleted_count", deleted);
            span.setData("recreated_count", recreated);
            span.setData("skipped_count", skipped);
            span.setTag("outcome", success ? "success" : "failure");
            span.setTag("had_changes", (created + deleted + recreated) > 0 ? "true" : "false");
        }
    }

    // ========== Pool Metrics Tagging ==========

    /**
     * Tags a span with pool capacity info for monitoring.
     */
    public static void tagPoolCapacity(ISpan span, int totalCapacity, int availableCount) {
        if (span != null) {
            span.setData("pool.total_capacity", totalCapacity);
            span.setData("pool.available", availableCount);
            span.setData("pool.utilization_pct",
                    totalCapacity > 0 ? (int) ((1.0 - (double) availableCount / totalCapacity) * 100) : 0);
        }
    }

    /**
     * Tags reservation outcome.
     */
    public static void tagReservationOutcome(ISpan span, String outcome, Integer instanceId) {
        if (span != null) {
            span.setTag("pool.outcome", outcome);
            if (instanceId != null) {
                span.setData("instance_id", instanceId);
            }
        }
    }

    // ========== Error Handling ==========

    /**
     * Captures an error with operation context.
     */
    public static void captureError(Throwable error, String operation, String correlationId) {
        Sentry.configureScope(scope -> {
            scope.setTag("operation", operation);
            scope.setTag("correlation_id", correlationId);
        });
        Sentry.captureException(error);
    }

    /**
     * Captures a K8s resource error with full context.
     */
    public static void captureK8sError(Throwable error, String resourceType, String resourceName,
            String operation, String correlationId) {
        Sentry.configureScope(scope -> {
            scope.setTag("resource.type", resourceType);
            scope.setTag("resource.name", resourceName);
            scope.setTag("operation", operation);
            scope.setTag("correlation_id", correlationId);
        });
        Sentry.captureException(error);
    }

    /**
     * Adds a breadcrumb for debugging flow.
     */
    public static void breadcrumb(String message, String category) {
        Sentry.addBreadcrumb(message, category);
    }
}
