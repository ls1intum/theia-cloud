package org.eclipse.theia.cloud.operator.di;

import org.eclipse.theia.cloud.common.k8s.client.CredentialBridgeClient;
import org.eclipse.theia.cloud.common.k8s.client.DefaultCredentialBridgeClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

/**
 * Guice provider for {@link CredentialBridgeClient}.
 */
@Singleton
public class CredentialBridgeClientProvider implements Provider<CredentialBridgeClient> {

    @Inject
    private TheiaCloudClient theiaCloudClient;

    @Override
    public CredentialBridgeClient get() {
        return new DefaultCredentialBridgeClient(theiaCloudClient);
    }
}

