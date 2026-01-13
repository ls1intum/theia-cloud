package org.eclipse.theia.cloud.operator.handler.appdef;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool;
import org.eclipse.theia.cloud.operator.util.SentryHelper;
import org.eclipse.theia.cloud.operator.util.TheiaCloudIngressUtil;

import com.google.inject.Inject;

import io.sentry.ISpan;
import io.sentry.ITransaction;
import io.sentry.SpanStatus;

/**
 * A {@link AppDefinitionHandler} that manages a pool of prewarmed deployments for eager start sessions. This handler
 * delegates pool management to {@link PrewarmedResourcePool}.
 */
public class EagerStartAppDefinitionAddedHandler implements AppDefinitionHandler {

    private static final Logger LOGGER = LogManager.getLogger(EagerStartAppDefinitionAddedHandler.class);

    @Inject
    private TheiaCloudClient client;

    @Inject
    private PrewarmedResourcePool pool;

    @Override
    public boolean appDefinitionAdded(AppDefinition appDefinition, String correlationId) {
        AppDefinitionSpec spec = appDefinition.getSpec();
        String appDefName = appDefinition.getMetadata().getName();
        int minInstances = spec.getMinInstances();

        ITransaction tx = SentryHelper.startAppDefTransaction("added", appDefName, minInstances, correlationId);
        tx.setTag("appdef.action", "added");

        LOGGER.info(formatLogMessage(correlationId, "Handling " + spec));

        try {
            // Verify ingress exists
            ISpan ingressSpan = tx.startChild("appdef.verify_ingress", "Verify ingress exists");
            ingressSpan.setData("ingress_name", spec.getIngressname());

            if (!TheiaCloudIngressUtil.checkForExistingIngressAndAddOwnerReferencesIfMissing(client.kubernetes(),
                    client.namespace(), appDefinition, correlationId)) {
                LOGGER.error(formatLogMessage(correlationId,
                        "Expected ingress '" + spec.getIngressname() + "' for app definition '" + appDefName
                                + "' does not exist. Abort handling app definition."));
                SentryHelper.finishWithOutcome(ingressSpan, "not_found", SpanStatus.NOT_FOUND);
                tx.setTag("error.reason", "ingress_not_found");
                SentryHelper.finishWithOutcome(tx, "failure", SpanStatus.NOT_FOUND);
                return false;
            }
            SentryHelper.finishSuccess(ingressSpan);

            LOGGER.trace(formatLogMessage(correlationId, "Ingress available"));

            // Ensure pool has minimum capacity
            ISpan poolSpan = tx.startChild("appdef.ensure_capacity", "Ensure pool capacity");
            poolSpan.setData("min_instances", minInstances);

            boolean success = pool.ensureCapacity(appDefinition, minInstances, correlationId);

            poolSpan.setTag("pool.operation", "ensure_capacity");
            SentryHelper.finishWithOutcome(poolSpan, success);

            tx.setData("min_instances", minInstances);
            SentryHelper.finishWithOutcome(tx, success);
            return success;

        } catch (Exception e) {
            SentryHelper.captureError(e, "appdef.added", correlationId);
            SentryHelper.finishError(tx, e);
            throw e;
        }
    }

    @Override
    public boolean appDefinitionDeleted(AppDefinition appDefinition, String correlationId) {
        AppDefinitionSpec spec = appDefinition.getSpec();
        String appDefName = appDefinition.getMetadata().getName();

        ITransaction tx = SentryHelper.startAppDefTransaction("deleted", appDefName, spec.getMinInstances(),
                correlationId);
        tx.setTag("appdef.action", "deleted");

        LOGGER.info(formatLogMessage(correlationId, "Deleting resources for " + spec));

        try {
            // Release all pool resources
            ISpan releaseSpan = tx.startChild("appdef.release_all", "Release all pool resources");
            releaseSpan.setTag("pool.operation", "release_all");

            boolean success = pool.releaseAll(appDefinition, correlationId);

            SentryHelper.finishWithOutcome(releaseSpan, success);
            SentryHelper.finishWithOutcome(tx, success);
            return success;

        } catch (Exception e) {
            SentryHelper.captureError(e, "appdef.deleted", correlationId);
            SentryHelper.finishError(tx, e);
            throw e;
        }
    }

    @Override
    public boolean appDefinitionModified(AppDefinition appDefinition, String correlationId) {
        AppDefinitionSpec spec = appDefinition.getSpec();
        String appDefName = appDefinition.getMetadata().getName();
        int minInstances = spec.getMinInstances();

        ITransaction tx = SentryHelper.startAppDefTransaction("modified", appDefName, minInstances, correlationId);
        tx.setTag("appdef.action", "modified");
        tx.setData("generation", appDefinition.getMetadata().getGeneration());

        LOGGER.info(formatLogMessage(correlationId, "Reconciling " + spec));

        try {
            // Verify ingress exists
            ISpan ingressSpan = tx.startChild("appdef.verify_ingress", "Verify ingress exists");
            ingressSpan.setData("ingress_name", spec.getIngressname());

            if (!TheiaCloudIngressUtil.checkForExistingIngressAndAddOwnerReferencesIfMissing(client.kubernetes(),
                    client.namespace(), appDefinition, correlationId)) {
                LOGGER.error(formatLogMessage(correlationId, "Expected ingress '" + spec.getIngressname()
                        + "' for app definition '" + appDefName + "' does not exist. Abort handling."));
                SentryHelper.finishWithOutcome(ingressSpan, "not_found", SpanStatus.NOT_FOUND);
                tx.setTag("error.reason", "ingress_not_found");
                SentryHelper.finishWithOutcome(tx, "failure", SpanStatus.NOT_FOUND);
                return false;
            }
            SentryHelper.finishSuccess(ingressSpan);

            // Reconcile pool to target instance count
            ISpan reconcileSpan = tx.startChild("appdef.reconcile_pool", "Reconcile pool to target");
            reconcileSpan.setData("target_instances", minInstances);
            reconcileSpan.setTag("pool.operation", "reconcile");

            boolean success = pool.reconcile(appDefinition, minInstances, correlationId);

            SentryHelper.finishWithOutcome(reconcileSpan, success);
            SentryHelper.finishWithOutcome(tx, success);
            return success;

        } catch (Exception e) {
            SentryHelper.captureError(e, "appdef.modified", correlationId);
            SentryHelper.finishError(tx, e);
            throw e;
        }
    }
}
