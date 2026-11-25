package su.kidoz.kaillera.model.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static su.kidoz.kaillera.access.AccessManager.ACCESS_NORMAL;
import static su.kidoz.kaillera.model.impl.ServerMaintenanceTask.MaintenanceAction.NONE;
import static su.kidoz.kaillera.model.impl.ServerMaintenanceTask.MaintenanceAction.REMOVE_TIMEOUT;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.model.impl.ServerMaintenanceTask.MaintenanceAction;
import su.kidoz.kaillera.model.impl.ServerMaintenanceTask.UserQuitRequest;

@DisplayName("ServerMaintenanceTask Tests")
class ServerMaintenanceTaskTest {

    private final UserManager userManager = mock(UserManager.class);

    private final AccessManager accessManager = mock(AccessManager.class);

    private final List<UserQuitRequest> quits = new ArrayList<>();

    private ServerMaintenanceTask task(int maxPing) {
        when(accessManager.isEmulatorAllowed(any())).thenReturn(true);
        return new ServerMaintenanceTask(userManager, accessManager, maxPing, 190, 900, quits::add);
    }

    /** A session of the given age. */
    private static KailleraUserImpl session(boolean loggedIn, boolean startedLogin,
            long ageMillis) {
        KailleraUserImpl user = mock(KailleraUserImpl.class);
        long now = System.currentTimeMillis();
        when(user.isLoggedIn()).thenReturn(loggedIn);
        when(user.hasStartedLogin()).thenReturn(startedLogin);
        when(user.getConnectTime()).thenReturn(now - ageMillis);
        when(user.getLastKeepAlive()).thenReturn(now);
        when(user.getLastActivity()).thenReturn(now);
        return user;
    }

    @Test
    @DisplayName("a HELLO that never starts logging in expires after about 1.5 seconds")
    void aHelloThatNeverStartsLoggingInExpiresQuickly() {
        ServerMaintenanceTask task = task(250);

        assertEquals(NONE, task.checkUser(session(false, false, 1400), ACCESS_NORMAL));
        assertEquals(REMOVE_TIMEOUT, task.checkUser(session(false, false, 1600), ACCESS_NORMAL));
    }

    @Test
    @DisplayName("a handshake that stalled half way gets the full connection timeout")
    void aStalledHandshakeGetsTheFullTimeout() {
        ServerMaintenanceTask task = task(250);

        // maxPing * 15 = 3750 ms
        assertEquals(NONE, task.checkUser(session(false, true, 3600), ACCESS_NORMAL));
        assertEquals(REMOVE_TIMEOUT, task.checkUser(session(false, true, 3800), ACCESS_NORMAL));
    }

    @Test
    @DisplayName("both timeouts follow maxPing")
    void bothTimeoutsFollowMaxPing() {
        ServerMaintenanceTask slow = task(1000);

        // 1000 * 6 = 6000 ms, and 1000 * 15 = 15000 ms
        assertEquals(NONE, slow.checkUser(session(false, false, 5000), ACCESS_NORMAL));
        assertEquals(REMOVE_TIMEOUT, slow.checkUser(session(false, false, 6200), ACCESS_NORMAL));
        assertEquals(NONE, slow.checkUser(session(false, true, 14000), ACCESS_NORMAL));
        assertEquals(REMOVE_TIMEOUT, slow.checkUser(session(false, true, 16000), ACCESS_NORMAL));
    }

    @Test
    @DisplayName("a logged-in user is never removed for a connection timeout")
    void aLoggedInUserIsNeverRemovedForAConnectionTimeout() {
        MaintenanceAction action = task(250).checkUser(session(true, true, 100_000), ACCESS_NORMAL);

        assertEquals(NONE, action);
    }

    @Test
    @DisplayName("stop() wakes the sleeping thread instead of waiting out the interval")
    void stopWakesTheSleepingThread() throws Exception {
        ServerMaintenanceTask task = task(1000); // 3 seconds between passes
        when(userManager.isEmpty()).thenReturn(true);
        Thread thread = new Thread(task, "maintenance-test");
        thread.start();
        try {
            Thread.sleep(100);
            assertTrue(thread.isAlive(), "the task must be in its first sleep");

            task.stop();

            thread.join(1000);
            assertFalse(thread.isAlive(), "stop() must end the task at once");
        } finally {
            task.stop();
            thread.interrupt();
        }
    }
}
