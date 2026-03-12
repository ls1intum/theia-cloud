/********************************************************************************
 * Copyright (C) 2026 EclipseSource and others.
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
package org.eclipse.theia.cloud.service.admin.appdefinition;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

@Schema(name = "AppDefinitionScaling", description = "Scaling configuration of an app definition.")
public class AppDefinitionScaling {

    @Schema(description = "The K8S resource name of the app definition.", required = true)
    public String appDefinitionName;

    @Schema(description = "The minimum number of instances to run.", required = true)
    public int minInstances;

    @Schema(description = "The maximum number of instances to run. Null means unbounded.", required = false)
    public Integer maxInstances;

    public AppDefinitionScaling() {
    }

    public AppDefinitionScaling(String appDefinitionName, int minInstances, Integer maxInstances) {
        this.appDefinitionName = appDefinitionName;
        this.minInstances = minInstances;
        this.maxInstances = maxInstances;
    }
}
