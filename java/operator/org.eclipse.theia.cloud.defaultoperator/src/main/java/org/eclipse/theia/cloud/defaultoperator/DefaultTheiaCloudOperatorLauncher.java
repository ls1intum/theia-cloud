/********************************************************************************
 * Copyright (C) 2023 EclipseSource and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License v. 2.0 are satisfied: GNU General Public License, version 2
 * with the GNU Classpath Exception which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 ********************************************************************************/
package org.eclipse.theia.cloud.defaultoperator;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.theia.cloud.operator.LeaderElectionTheiaCloudOperatorLauncher;
import org.eclipse.theia.cloud.operator.TheiaCloudOperatorArguments;
import org.eclipse.theia.cloud.operator.di.AbstractTheiaCloudOperatorModule;

import io.sentry.Sentry;

public class DefaultTheiaCloudOperatorLauncher extends LeaderElectionTheiaCloudOperatorLauncher {

    private static final Logger LOGGER = LogManager.getLogger(DefaultTheiaCloudOperatorLauncher.class);

    public static void main(String[] args) {
        try {
            Sentry.configureScope(scope -> {
                scope.setTag("component", "operator");
            });
            new DefaultTheiaCloudOperatorLauncher().runMain(args);
        } catch (InterruptedException e) {
            LOGGER.error("Operator interrupted", e);
            Thread.currentThread().interrupt();
            System.exit(1);
        } catch (Throwable t) {
            LOGGER.error("Fatal error starting operator", t);
            t.printStackTrace();
            System.exit(1);
        }
    }

    @Override
    public AbstractTheiaCloudOperatorModule createModule(TheiaCloudOperatorArguments arguments) {
        return new DefaultTheiaCloudOperatorModule(arguments);
    }

}