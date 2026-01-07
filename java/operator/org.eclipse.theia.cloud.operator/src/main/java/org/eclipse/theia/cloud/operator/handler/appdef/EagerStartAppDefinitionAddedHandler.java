package org.eclipse.theia.cloud.operator.handler.appdef;

import static org.eclipse.theia.cloud.common.util.LogMessageUtil.formatLogMessage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinition;
import org.eclipse.theia.cloud.common.k8s.resource.appdefinition.AppDefinitionSpec;
import org.eclipse.theia.cloud.operator.pool.PrewarmedResourcePool;
import org.eclipse.theia.cloud.operator.util.TheiaCloudIngressUtil;

import com.google.inject.Inject;

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
        LOGGER.info(formatLogMessage(correlationId, "Handling " + spec));

        String appDefinitionResourceName = appDefinition.getMetadata().getName();

        // Verify ingress exists
        if (!TheiaCloudIngressUtil.checkForExistingIngressAndAddOwnerReferencesIfMissing(client.kubernetes(),
                client.namespace(), appDefinition, correlationId)) {
            LOGGER.error(formatLogMessage(correlationId,
                    "Expected ingress '" + spec.getIngressname() + "' for app definition '" + appDefinitionResourceName
                            + "' does not exist. Abort handling app definition."));
            return false;
        }

        LOGGER.trace(formatLogMessage(correlationId, "Ingress available"));

        // Ensure pool has minimum capacity
        return pool.ensureCapacity(appDefinition, spec.getMinInstances(), correlationId);
    }

    @Override
    public boolean appDefinitionDeleted(AppDefinition appDefinition, String correlationId) {
        LOGGER.info(formatLogMessage(correlationId, "Deleting resources for " + appDefinition.getSpec()));

        // Release all pool resources
        return pool.releaseAll(appDefinition, correlationId);
    }

    @Override
    public boolean appDefinitionModified(AppDefinition appDefinition, String correlationId) {
        AppDefinitionSpec spec = appDefinition.getSpec();
        LOGGER.info(formatLogMessage(correlationId, "Reconciling " + spec));

        String appDefinitionResourceName = appDefinition.getMetadata().getName();

        // Verify ingress exists
        if (!TheiaCloudIngressUtil.checkForExistingIngressAndAddOwnerReferencesIfMissing(client.kubernetes(),
                client.namespace(), appDefinition, correlationId)) {
            LOGGER.error(formatLogMessage(correlationId, "Expected ingress '" + spec.getIngressname()
                    + "' for app definition '" + appDefinitionResourceName + "' does not exist. Abort handling."));
            return false;
        }

        // Reconcile pool to target instance count
        return pool.reconcile(appDefinition, spec.getMinInstances(), correlationId);
    }
}
