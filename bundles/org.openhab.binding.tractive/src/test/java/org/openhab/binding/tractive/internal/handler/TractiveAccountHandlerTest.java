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
package org.openhab.binding.tractive.internal.handler;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.binding.tractive.internal.TractiveBindingConstants;
import org.openhab.binding.tractive.internal.config.TractiveAccountConfiguration;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.ThingHandlerCallback;

/**
 * Unit tests for {@link TractiveAccountHandler#authenticate} -- previously entirely untested -- exercised through
 * its three consuming call sites: {@link TractiveAccountHandler#initializeBridge} (via {@code initialize()} and the
 * reflection-mocked {@code scheduler}, same pattern as {@link TractiveTrackerHandlerPollGatingTest}), the private
 * {@code checkAndRefreshToken()} (invoked directly via reflection, since it has no public entry point and doesn't
 * need the scheduler), and the public {@link TractiveAccountHandler#refreshToken}. Covers the three
 * {@code authenticate()} failure branches (a real {@code InterruptedException}, a Jetty {@code TimeoutException},
 * and {@code ExecutionException} -- see CLAUDE.md's "Checked-exception hierarchy" entry for why the first is
 * deliberately silent at every call site), a non-200 and a structurally-incomplete 200 response, the bridge auth
 * retry backoff doubling, and the thread-safe re-auth guard ({@code knownToken} mismatch is a silent no-op). Also
 * covers {@code handleTokenRefreshFailure()}'s tolerance logic: a transient {@code IOException} from
 * {@code checkAndRefreshToken()} / {@code refreshToken()} is retried silently and only escalates to
 * {@code OFFLINE}/{@code COMMUNICATION_ERROR} after {@code TOKEN_REFRESH_FAILURES_BEFORE_OFFLINE} consecutive
 * failures, immediately when the token is within the expiry buffer, or immediately for a {@code RuntimeException}.
 *
 * @author Erik De Boeck - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class TractiveAccountHandlerTest {

    private static final ThingUID BRIDGE_UID = new ThingUID(TractiveBindingConstants.THING_TYPE_ACCOUNT, "gert");

    @Mock
    private @NonNullByDefault({}) Bridge bridgeThing;
    @Mock
    private @NonNullByDefault({}) ThingHandlerCallback callback;
    @Mock
    private @NonNullByDefault({}) HttpClient httpClient;
    @Mock
    private @NonNullByDefault({}) Request request;
    @Mock
    private @NonNullByDefault({}) ContentResponse response;
    @Mock
    private @NonNullByDefault({}) ScheduledExecutorService mockScheduler;
    private @NonNullByDefault({}) TractiveAccountHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        lenient().when(bridgeThing.getUID()).thenReturn(BRIDGE_UID);
        lenient().when(bridgeThing.getConfiguration())
                .thenReturn(new Configuration(Map.of("email", "user@example.com", "password", "hunter2")));
        lenient().when(httpClient.newRequest(anyString())).thenReturn(request);
        lenient().when(request.method(HttpMethod.POST)).thenReturn(request);
        lenient().when(request.header(anyString(), anyString())).thenReturn(request);
        lenient().when(request.content(any())).thenReturn(request);
        lenient().when(request.send()).thenReturn(response);
        lenient().when(mockScheduler.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class)))
                .thenAnswer(inv -> mock(ScheduledFuture.class));

        handler = new TractiveAccountHandler(bridgeThing, httpClient);
        handler.setCallback(callback);

        Field schedulerField = BaseThingHandler.class.getDeclaredField("scheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(handler, mockScheduler);
    }

    // -- helpers -------------------------------------------------------------------------------

    private void stubSuccessfulAuthResponse() {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString())
                .thenReturn("{\"access_token\":\"tok123\",\"user_id\":\"user123\",\"expires_at\":9999999999}");
    }

    /**
     * Captures and runs the single {@code scheduler.schedule(..., 0, SECONDS)} call queued by
     * {@code initialize()}. Only valid to call once per test, immediately after {@code handler.initialize()} --
     * later {@code schedule(..., 0, SECONDS)} calls (e.g. the channel loop, on a successful auth) are deliberately
     * not captured here.
     */
    private void runInitialSchedule() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(task.capture(), eq(0L), eq(TimeUnit.SECONDS));
        task.getValue().run();
    }

    private void setPrivateField(String name, Object value) throws Exception {
        Field field = TractiveAccountHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(handler, value);
    }

    private void invokeCheckAndRefreshToken() throws Exception {
        Method method = TractiveAccountHandler.class.getDeclaredMethod("checkAndRefreshToken");
        method.setAccessible(true);
        method.invoke(handler);
    }

    private TractiveAccountConfiguration validConfig() {
        TractiveAccountConfiguration config = new TractiveAccountConfiguration();
        config.email = "user@example.com";
        config.password = "hunter2";
        return config;
    }

    private boolean isOffline(ThingStatusInfo info, ThingStatusDetail detail) {
        return info.getStatus() == ThingStatus.OFFLINE && info.getStatusDetail() == detail;
    }

    // -- initializeBridge() ---------------------------------------------------------------------

    @Test
    void initializeBridgeBlankCredentialsSetsConfigurationError() {
        when(bridgeThing.getConfiguration()).thenReturn(new Configuration(Map.of()));

        handler.initialize();
        runInitialSchedule();

        verify(callback).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.CONFIGURATION_ERROR)));
        verifyNoInteractions(httpClient);
    }

    @Test
    void initializeBridgeInterruptedDoesNotUpdateStatusOrScheduleRetry() throws Exception {
        when(request.send()).thenThrow(new InterruptedException("simulated interrupt"));

        handler.initialize();
        runInitialSchedule();

        verify(callback, never()).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)
                        || info.getStatus() == ThingStatus.ONLINE));
        // Only the one schedule(..., 0, SECONDS) call from initialize() itself -- no retry queued.
        verify(mockScheduler, times(1)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        assertTrue(Thread.interrupted(), "authenticate() must re-set the thread's interrupt flag");
    }

    @Test
    void initializeBridgeTimeoutSetsOfflineCommunicationErrorAndSchedulesRetry() throws Exception {
        when(request.send()).thenThrow(new TimeoutException("simulated timeout"));

        handler.initialize();
        runInitialSchedule();

        verify(callback).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)));
        // The initial schedule(..., 0, ...) plus one retry at the initial backoff delay (15 s).
        verify(mockScheduler, times(2)).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS));
        verify(mockScheduler).schedule(any(Runnable.class), eq(15L), eq(TimeUnit.SECONDS));
    }

    @Test
    void initializeBridgeExecutionExceptionSetsOfflineCommunicationErrorAndSchedulesRetry() throws Exception {
        when(request.send()).thenThrow(new ExecutionException("simulated failure", new RuntimeException("boom")));

        handler.initialize();
        runInitialSchedule();

        verify(callback).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)));
        verify(mockScheduler).schedule(any(Runnable.class), eq(15L), eq(TimeUnit.SECONDS));
    }

    @Test
    void initializeBridgeNon200ResponseSetsOfflineCommunicationError() {
        when(response.getStatus()).thenReturn(HttpStatus.UNAUTHORIZED_401);
        when(response.getContentAsString()).thenReturn("{\"error\":\"bad credentials\"}");

        handler.initialize();
        runInitialSchedule();

        verify(callback).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)));
    }

    @Test
    void initializeBridgeIncompleteAuthResponseSetsOfflineCommunicationError() {
        when(response.getStatus()).thenReturn(HttpStatus.OK_200);
        when(response.getContentAsString()).thenReturn("{}");

        handler.initialize();
        runInitialSchedule();

        verify(callback).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)));
    }

    @Test
    void initializeBridgeSuccessGoesOnlineAndSchedulesFollowOnWork() {
        stubSuccessfulAuthResponse();

        handler.initialize();
        runInitialSchedule();

        verify(callback).statusUpdated(eq(bridgeThing), argThat(info -> info.getStatus() == ThingStatus.ONLINE));
        // The initial schedule(..., 0, SECONDS) plus startChannelLoop()'s own schedule(..., 0, SECONDS).
        verify(mockScheduler, times(2)).schedule(any(Runnable.class), eq(0L), eq(TimeUnit.SECONDS));
        // checkAndRefreshToken() self-reschedules (like the channel loop) instead of using scheduleWithFixedDelay;
        // 600 = TOKEN_REFRESH_INTERVAL_MINUTES * 60.
        verify(mockScheduler).schedule(any(Runnable.class), eq(600L), eq(TimeUnit.SECONDS));
    }

    @Test
    void initializeBridgeRetryDelayDoublesOnRepeatedFailure() throws Exception {
        when(request.send()).thenThrow(new TimeoutException("simulated timeout"));

        handler.initialize();
        runInitialSchedule(); // 1st failure -> schedules retry at 15 s

        ArgumentCaptor<Runnable> retryOne = ArgumentCaptor.forClass(Runnable.class);
        verify(mockScheduler).schedule(retryOne.capture(), eq(15L), eq(TimeUnit.SECONDS));
        retryOne.getValue().run(); // 2nd failure -> schedules retry at 30 s

        verify(mockScheduler).schedule(any(Runnable.class), eq(30L), eq(TimeUnit.SECONDS));
    }

    // -- checkAndRefreshToken() (private; invoked via reflection) -------------------------------

    @Test
    void checkAndRefreshTokenNoopWhenConfigNull() throws Exception {
        // config field left at its default null -- never gone through a successful initializeBridge().
        invokeCheckAndRefreshToken();

        verifyNoInteractions(httpClient);
        verify(callback, never()).statusUpdated(any(), any());
    }

    @Test
    void checkAndRefreshTokenInterruptedDoesNotUpdateStatus() throws Exception {
        setPrivateField("config", validConfig());
        when(request.send()).thenThrow(new InterruptedException("simulated interrupt"));

        invokeCheckAndRefreshToken();

        verify(callback, never()).statusUpdated(any(), any());
        assertTrue(Thread.interrupted(), "authenticate() must re-set the thread's interrupt flag");
    }

    @Test
    void checkAndRefreshTokenTransientFailureIsRetriedSilentlyUntilThreshold() throws Exception {
        setPrivateField("config", validConfig());
        setPrivateField("expiresAt", System.currentTimeMillis() / 1000 + 31_536_000L); // token nowhere near expiry
        when(request.send()).thenThrow(new TimeoutException("simulated timeout"));

        invokeCheckAndRefreshToken(); // failure 1
        invokeCheckAndRefreshToken(); // failure 2
        verify(callback, never()).statusUpdated(any(), any());

        invokeCheckAndRefreshToken(); // failure 3 -> escalates
        verify(callback, times(1)).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)));
    }

    @Test
    void checkAndRefreshTokenFailureNearExpiryGoesOfflineImmediately() throws Exception {
        setPrivateField("config", validConfig());
        setPrivateField("expiresAt", System.currentTimeMillis() / 1000 + 60L); // inside
                                                                               // TOKEN_EXPIRY_ESCALATION_BUFFER_S
        when(request.send()).thenThrow(new TimeoutException("simulated timeout"));

        invokeCheckAndRefreshToken();

        verify(callback).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)));
    }

    @Test
    void checkAndRefreshTokenRuntimeExceptionGoesOfflineImmediately() throws Exception {
        setPrivateField("config", validConfig());
        setPrivateField("expiresAt", System.currentTimeMillis() / 1000 + 31_536_000L); // expiry buffer is irrelevant
                                                                                       // here
        when(request.send()).thenThrow(new IllegalStateException("unexpected bug"));

        invokeCheckAndRefreshToken();

        verify(callback).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)));
    }

    @Test
    void checkAndRefreshTokenSuccessResetsTheFailureCount() throws Exception {
        setPrivateField("config", validConfig());
        setPrivateField("expiresAt", System.currentTimeMillis() / 1000 + 31_536_000L);
        stubSuccessfulAuthResponse();
        when(request.send()).thenThrow(new TimeoutException("t")).thenThrow(new TimeoutException("t"))
                .thenReturn(response).thenThrow(new TimeoutException("t")).thenThrow(new TimeoutException("t"));

        invokeCheckAndRefreshToken(); // fail 1
        invokeCheckAndRefreshToken(); // fail 2
        invokeCheckAndRefreshToken(); // success -> ONLINE, counter reset to 0
        invokeCheckAndRefreshToken(); // fail 1 (post-reset)
        invokeCheckAndRefreshToken(); // fail 2 (post-reset)

        // Without the reset, the 5th call would be the 3rd consecutive failure and would go OFFLINE.
        verify(callback, never()).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)));
        verify(callback).statusUpdated(eq(bridgeThing), argThat(info -> info.getStatus() == ThingStatus.ONLINE));
    }

    // -- refreshToken() (public) ------------------------------------------------------------------

    @Test
    void refreshTokenNoopWhenConfigNull() {
        handler.refreshToken(null);

        verifyNoInteractions(httpClient);
        verify(callback, never()).statusUpdated(any(), any());
    }

    @Test
    void refreshTokenNoopWhenKnownTokenIsStale() throws Exception {
        // Thread-safe re-auth guard: a caller holding an already-superseded token is a silent no-op --
        // whichever caller wins the race re-authenticates, this one just returns.
        setPrivateField("config", validConfig());
        setPrivateField("accessToken", "current-token");

        handler.refreshToken("stale-token");

        verifyNoInteractions(httpClient);
        verify(callback, never()).statusUpdated(any(), any());
    }

    @Test
    void refreshTokenInterruptedDoesNotUpdateStatus() throws Exception {
        setPrivateField("config", validConfig());
        when(request.send()).thenThrow(new InterruptedException("simulated interrupt"));

        handler.refreshToken(null);

        verify(callback, never()).statusUpdated(any(), any());
        assertTrue(Thread.interrupted(), "authenticate() must re-set the thread's interrupt flag");
    }

    @Test
    void refreshTokenTransientFailureIsRetriedSilentlyUntilThreshold() throws Exception {
        setPrivateField("config", validConfig());
        setPrivateField("expiresAt", System.currentTimeMillis() / 1000 + 31_536_000L);
        when(request.send()).thenThrow(new TimeoutException("simulated timeout"));

        handler.refreshToken(null); // failure 1
        handler.refreshToken(null); // failure 2
        verify(callback, never()).statusUpdated(any(), any());

        handler.refreshToken(null); // failure 3 -> escalates
        verify(callback, times(1)).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)));
    }

    @Test
    void refreshTokenFailureSchedulesItsOwnRetry() throws Exception {
        setPrivateField("config", validConfig());
        setPrivateField("expiresAt", System.currentTimeMillis() / 1000 + 31_536_000L);
        when(request.send()).thenThrow(new TimeoutException("simulated timeout"));

        handler.refreshToken(null);

        // refreshToken() no longer just sets status and returns -- it queues a retry on the shared token task at the
        // initial backoff delay (15 s) instead of leaning on the next proactive tick or 401 to notice.
        verify(mockScheduler).schedule(any(Runnable.class), eq(15L), eq(TimeUnit.SECONDS));
    }

    @Test
    void refreshTokenFailureNearExpiryGoesOfflineImmediately() throws Exception {
        setPrivateField("config", validConfig());
        setPrivateField("expiresAt", System.currentTimeMillis() / 1000 + 60L);
        when(request.send()).thenThrow(new TimeoutException("simulated timeout"));

        handler.refreshToken(null);

        verify(callback).statusUpdated(eq(bridgeThing),
                argThat(info -> isOffline(info, ThingStatusDetail.COMMUNICATION_ERROR)));
    }
}
