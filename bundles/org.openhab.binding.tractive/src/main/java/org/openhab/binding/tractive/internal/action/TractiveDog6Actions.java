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
package org.openhab.binding.tractive.internal.action;

import static org.openhab.binding.tractive.internal.TractiveBindingConstants.BINDING_ID;

import java.time.ZonedDateTime;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.tractive.internal.handler.TractiveDog6Handler;
import org.openhab.core.automation.annotation.ActionInput;
import org.openhab.core.automation.annotation.ActionOutput;
import org.openhab.core.automation.annotation.ActionOutputs;
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
@Component(scope = ServiceScope.PROTOTYPE, service = TractiveDog6Actions.class)
@ThingActionsScope(name = BINDING_ID)
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

    /** Triggers an immediate position report refresh. */
    @RuleAction(label = "Refresh position", description = "Triggers an immediate refresh of the \"Position\" channel group, outside the polling schedule")
    public void refreshPosition() {
        TractiveDog6Handler h = handler;
        if (h != null) {
            h.refreshPosition();
        }
    }

    /** Triggers an immediate health overview refresh. */
    @RuleAction(label = "Refresh health overview", description = "Triggers an immediate refresh of the \"Health\" and \"Dog\" channel groups, outside the polling schedule")
    public void refreshHealthOverview() {
        TractiveDog6Handler h = handler;
        if (h != null) {
            h.refreshHealthOverview();
        }
    }

    /** Triggers an immediate tracker details and hardware report refresh. */
    @RuleAction(label = "Refresh hardware", description = "Triggers an immediate refresh of the \"Hardware\" channel group, outside the polling schedule")
    public void refreshHardware() {
        TractiveDog6Handler h = handler;
        if (h != null) {
            h.refreshHardware();
        }
    }

    /** Returns historical tracker positions within a time window as a JSON array string. */
    @RuleAction(label = "Get historical positions", description = "Fetches tracker positions between two timestamps. Returns a JSON array string under the key \"positions\", or an empty map on failure.")
    @ActionOutputs({ @ActionOutput(name = "positions", type = "java.lang.String") })
    public Map<String, Object> getPositions(
            @ActionInput(name = "from", type = "java.time.ZonedDateTime") @Nullable ZonedDateTime from,
            @ActionInput(name = "to", type = "java.time.ZonedDateTime") @Nullable ZonedDateTime to) {
        TractiveDog6Handler h = handler;
        if (h == null || from == null || to == null) {
            return Map.of();
        }
        String json = h.fetchPositions(from, to);
        return json != null ? Map.of("positions", json) : Map.of();
    }

    /** Triggers an immediate pet-profile refresh. */
    @RuleAction(label = "Refresh profile", description = "Triggers an immediate refresh of the \"Profile\" channel group. Unlike the other refresh actions, this data is never polled automatically — see the README.")
    public void refreshProfile() {
        TractiveDog6Handler h = handler;
        if (h != null) {
            h.refreshProfile();
        }
    }
}
