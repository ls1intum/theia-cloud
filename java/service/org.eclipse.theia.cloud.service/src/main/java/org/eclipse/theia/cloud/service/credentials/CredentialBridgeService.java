package org.eclipse.theia.cloud.service.credentials;

import org.eclipse.theia.cloud.common.k8s.client.CredentialBridgeClient;
import org.eclipse.theia.cloud.common.k8s.client.DefaultCredentialBridgeClient;
import org.eclipse.theia.cloud.service.K8sUtil;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Service for managing credential bridge operations.
 */
@ApplicationScoped
public class CredentialBridgeService {

    private final CredentialBridgeClient client;

    @Inject
    public CredentialBridgeService(K8sUtil k8sUtil) {
        this.client = new DefaultCredentialBridgeClient(k8sUtil.CLIENT);
    }

    public CredentialBridgeClient getClient() {
        return client;
    }
}

