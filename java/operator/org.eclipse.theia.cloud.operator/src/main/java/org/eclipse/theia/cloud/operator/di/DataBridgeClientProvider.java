package org.eclipse.theia.cloud.operator.di;

import org.eclipse.theia.cloud.common.k8s.client.DataBridgeClient;
import org.eclipse.theia.cloud.common.k8s.client.DefaultDataBridgeClient;
import org.eclipse.theia.cloud.common.k8s.client.TheiaCloudClient;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;

/**
 * Guice provider for {@link DataBridgeClient}.
 */
@Singleton
public class DataBridgeClientProvider implements Provider<DataBridgeClient> {

    @Inject
    private TheiaCloudClient theiaCloudClient;

    @Override
    public DataBridgeClient get() {
        return new DefaultDataBridgeClient(theiaCloudClient);
    }
}

