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
package org.openhab.binding.tractive.internal.channel;

import java.io.IOException;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Thrown when the Tractive channel connect request itself returns HTTP 401 or 403, indicating the
 * access token used to open it has been rejected. This is distinct from a routine connection drop, since
 * re-authenticating (rather than just retrying with the same token) is what actually recovers this.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveChannelAuthException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with the given message.
     */
    public TractiveChannelAuthException(String message) {
        super(message);
    }
}
