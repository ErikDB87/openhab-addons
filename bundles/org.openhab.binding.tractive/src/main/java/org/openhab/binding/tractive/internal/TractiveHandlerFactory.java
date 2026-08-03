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

import static org.openhab.binding.tractive.internal.TractiveBindingConstants.*;

import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.openhab.binding.tractive.internal.handler.TractiveAccountHandler;
import org.openhab.binding.tractive.internal.handler.TractiveDog6Handler;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;

/**
 * The {@link TractiveHandlerFactory} creates handlers for the Tractive account bridge
 * and the Dog 6 tracker thing.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@Component(configurationPid = "binding.tractive", service = ThingHandlerFactory.class)
public class TractiveHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES_UIDS = Set.of(THING_TYPE_ACCOUNT, THING_TYPE_DOG6);

    private final HttpClient httpClient;

    /**
     * Creates the handler factory, retrieving the shared openHAB HTTP client via OSGi.
     */
    @Activate
    public TractiveHandlerFactory(@Reference HttpClientFactory httpClientFactory) {
        this.httpClient = httpClientFactory.getCommonHttpClient();
    }

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES_UIDS.contains(thingTypeUID);
    }

    @Override
    protected @Nullable ThingHandler createHandler(Thing thing) {
        ThingTypeUID thingTypeUID = thing.getThingTypeUID();

        if (THING_TYPE_ACCOUNT.equals(thingTypeUID)) {
            return new TractiveAccountHandler((Bridge) thing, httpClient);
        }
        if (THING_TYPE_DOG6.equals(thingTypeUID)) {
            return new TractiveDog6Handler(thing);
        }

        return null;
    }
}
