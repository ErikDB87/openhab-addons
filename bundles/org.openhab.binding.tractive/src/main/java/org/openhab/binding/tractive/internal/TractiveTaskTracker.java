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

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Tracks {@link Future} instances so they can all be cancelled together on disposal.
 * Thread-safe; safe to call from any thread.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
public class TractiveTaskTracker {

    private final CopyOnWriteArrayList<Future<?>> tasks = new CopyOnWriteArrayList<>();

    public void track(Future<?> future) {
        tasks.add(future);
    }

    public void cancelAll() {
        for (Future<?> task : tasks) {
            task.cancel(true);
        }
        tasks.clear();
    }
}
