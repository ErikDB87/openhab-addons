/*
 * Copyright (c) 2010-2026 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.tractive.internal;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.automation.annotation.RuleAction;
import org.openhab.core.thing.binding.ThingActions;
import org.openhab.core.thing.binding.ThingActionsScope;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ServiceScope;

/**
 * ThingActions for the Tractive Dog 6 thing. Provides rule-action triggers
 * for on-demand data refresh outside the normal polling schedule.
 *
 * @author Erik De Boeck - Initial contribution
 */
@Component(scope = ServiceScope.PROTOTYPE)
@ThingActionsScope(name = "tractive")
@NonNullByDefault
public class TractiveDog6Actions implements ThingActions {

    private @Nullable TractiveDog6Handler handler;

    @Override
    public void setThingHandler(ThingHandler handler) {
        if (handler instanceof TractiveDog6Handler dog6Handler) {
            this.handler = dog6Handler;
        }
    }

    @Override
    public @Nullable ThingHandler getThingHandler() {
        return handler;
    }

    /** Triggers an immediate position and hardware report refresh. */
    @RuleAction(label = "Refresh position", description = "Triggers an immediate position data refresh outside the polling schedule")
    public void refreshPosition() {
        TractiveDog6Handler h = handler;
        if (h != null) {
            h.refreshPosition();
        }
    }

    /** Triggers an immediate health overview refresh. */
    @RuleAction(label = "Refresh health overview", description = "Triggers an immediate health overview refresh outside the polling schedule")
    public void refreshHealthOverview() {
        TractiveDog6Handler h = handler;
        if (h != null) {
            h.refreshHealthOverview();
        }
    }
}
