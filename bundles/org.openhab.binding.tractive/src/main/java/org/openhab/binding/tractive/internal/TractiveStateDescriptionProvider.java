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

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.type.DynamicStateDescriptionProvider;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.StateDescriptionFragmentBuilder;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Overrides a channel's static {@code readOnly} state description at runtime, per Thing. Used for
 * {@code profile#height}/{@code weight}: writable in thing-types.xml, but only actually writable for a pet's
 * owner account -- {@link org.openhab.binding.tractive.internal.handler.TractiveTrackerHandler#applyProfile}
 * calls {@link #setReadOnly} every time it learns the account's {@code read_only} status, so MainUI reflects it
 * without waiting for a command to be rejected.
 *
 * @author Erik De Boeck - Initial contribution
 */
@Component(service = { DynamicStateDescriptionProvider.class, TractiveStateDescriptionProvider.class })
@NonNullByDefault
public class TractiveStateDescriptionProvider implements DynamicStateDescriptionProvider {

    private final Logger logger = LoggerFactory.getLogger(TractiveStateDescriptionProvider.class);
    private final Map<ChannelUID, Boolean> readOnlyOverrides = new ConcurrentHashMap<>();

    /**
     * Records whether {@code channelUID} should be treated as read-only, overriding its static thing-types.xml
     * declaration. Takes effect the next time the framework asks for that channel's state description.
     */
    public void setReadOnly(ChannelUID channelUID, boolean readOnly) {
        logger.trace("Setting readOnly={} for channel {}", readOnly, channelUID);
        readOnlyOverrides.put(channelUID, readOnly);
    }

    @Override
    public @Nullable StateDescription getStateDescription(Channel channel,
            @Nullable StateDescription originalStateDescription, @Nullable Locale locale) {
        Boolean readOnly = readOnlyOverrides.get(channel.getUID());
        if (readOnly == null || originalStateDescription == null) {
            return null;
        }
        return StateDescriptionFragmentBuilder.create(originalStateDescription).withReadOnly(readOnly).build()
                .toStateDescription();
    }
}
