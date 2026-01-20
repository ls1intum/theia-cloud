package org.eclipse.theia.cloud.service.databridge;

import org.eclipse.theia.cloud.common.k8s.client.DataBridgeClient;
import org.eclipse.theia.cloud.common.k8s.client.DefaultDataBridgeClient;
import org.eclipse.theia.cloud.service.K8sUtil;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Service for managing data bridge operations.
 */
@ApplicationScoped
public class DataBridgeService {

    private final DataBridgeClient client;

    @Inject
    public DataBridgeService(K8sUtil k8sUtil) {
        this.client = new DefaultDataBridgeClient(k8sUtil.CLIENT);
    }

    public DataBridgeClient getClient() {
        return client;
    }
}

