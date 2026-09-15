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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.lenient;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.types.StateDescription;
import org.openhab.core.types.StateDescriptionFragmentBuilder;

/**
 * Unit tests for {@link TractiveStateDescriptionProvider}.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveStateDescriptionProviderTest {

    private static final ThingUID THING_UID = new ThingUID(new ThingTypeUID("tractive", "dog-6"), "Samson");
    private static final ChannelUID HEIGHT_CHANNEL_UID = new ChannelUID(THING_UID, "profile#height");

    @Mock
    private @NonNullByDefault({}) Channel channel;

    private @NonNullByDefault({}) TractiveStateDescriptionProvider provider;

    @BeforeEach
    void setUp() {
        provider = new TractiveStateDescriptionProvider();
        lenient().when(channel.getUID()).thenReturn(HEIGHT_CHANNEL_UID);
    }

    @Test
    void getStateDescriptionReturnsNullWhenNoOverrideSet() {
        StateDescription original = StateDescriptionFragmentBuilder.create().withPattern("%.2f %unit%").build()
                .toStateDescription();

        assertNull(provider.getStateDescription(channel, original, null));
    }

    @Test
    void getStateDescriptionReturnsNullWhenOriginalIsNull() {
        provider.setReadOnly(HEIGHT_CHANNEL_UID, true);

        assertNull(provider.getStateDescription(channel, null, null));
    }

    @Test
    void setReadOnlyTrueOverridesToReadOnlyPreservingPattern() {
        StateDescription original = StateDescriptionFragmentBuilder.create().withPattern("%.2f %unit%").build()
                .toStateDescription();
        provider.setReadOnly(HEIGHT_CHANNEL_UID, true);

        StateDescription result = provider.getStateDescription(channel, original, null);

        assertNotNull(result);
        assertTrue(result.isReadOnly());
        assertEquals("%.2f %unit%", result.getPattern());
    }

    @Test
    void setReadOnlyFalseOverridesToWritable() {
        StateDescription original = StateDescriptionFragmentBuilder.create().withReadOnly(true).build()
                .toStateDescription();
        provider.setReadOnly(HEIGHT_CHANNEL_UID, false);

        StateDescription result = provider.getStateDescription(channel, original, null);

        assertNotNull(result);
        assertFalse(result.isReadOnly());
    }

    @Test
    void getStateDescriptionOnlyAffectsTheChannelItWasSetFor() {
        ChannelUID otherChannelUid = new ChannelUID(THING_UID, "profile#weight");
        StateDescription original = StateDescriptionFragmentBuilder.create().build().toStateDescription();
        provider.setReadOnly(otherChannelUid, true);

        assertNull(provider.getStateDescription(channel, original, null));
    }
}
