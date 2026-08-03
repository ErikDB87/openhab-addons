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
 * Thrown when no keep-alive message has been received from the Tractive channel within the
 * expected window, indicating the connection has gone stale and should be re-established.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveKeepAliveTimeoutException extends IOException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception with the given message.
     */
    public TractiveKeepAliveTimeoutException(String message) {
        super(message);
    }
}
