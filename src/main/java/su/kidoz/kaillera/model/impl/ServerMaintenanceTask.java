package su.kidoz.kaillera.model.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.util.EmuLang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles periodic server maintenance tasks such as timeout checking, ban
 * enforcement, and user cleanup. Extracted from KailleraServerImpl.run() to
 * improve separation of concerns.
 */
public class ServerMaintenanceTask implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ServerMaintenanceTask.class);

    // Minimum sleep interval to prevent tight spin loop if maxPing is 0 or
    // misconfigured
    private static final long MIN_SLEEP_INTERVAL_MS = 1000;

    /**
     * A session that never started the login handshake is held at least this long.
     */
    static final long MIN_PRE_LOGIN_TIMEOUT_MS = 1500;

    /** Pre-login timeout, as a multiple of maxPing. */
    static final long PRE_LOGIN_TIMEOUT_PING_FACTOR = 6;

    private final UserManager userManager;
    private final AccessManager accessManager;
    private final int maxPing;
    private final int keepAliveTimeout;
    private final int idleTimeout;
    private final Consumer<UserQuitRequest> quitHandler;

    /** Timeout for a handshake that started but never completed. */
    private final long connectTimeoutMillis;

    /** Timeout for a session that was never followed by a UserInformation. */
    private final long preLoginTimeoutMillis;

    private volatile boolean stopFlag = false;
    private volatile Thread thread;

    /**
     * Request to quit a user with a specific message.
     */
    public record UserQuitRequest(KailleraUserImpl user, String message) {
    }

    public ServerMaintenanceTask(UserManager userManager, AccessManager accessManager, int maxPing,
            int keepAliveTimeout, int idleTimeout, Consumer<UserQuitRequest> quitHandler) {
        this.userManager = userManager;
        this.accessManager = accessManager;
        this.maxPing = maxPing;
        this.keepAliveTimeout = keepAliveTimeout;
        this.idleTimeout = idleTimeout;
        this.quitHandler = quitHandler;
        this.connectTimeoutMillis = maxPing * 15L;
        // A HELLO that is never followed by UserInformation is almost always abandoned
        // or spoofed, and each one holds one of the few connecting slots, so it expires
        // sooner than a handshake that stalled half way.
        this.preLoginTimeoutMillis = Math.max(MIN_PRE_LOGIN_TIMEOUT_MS,
                maxPing * PRE_LOGIN_TIMEOUT_PING_FACTOR);
    }

    public void stop() {
        stopFlag = true;

        Thread current = thread;
        if (current != null && current != Thread.currentThread()) {
            // Wake the thread from its sleep; otherwise shutdown waits out the interval.
            current.interrupt();
        }
    }

    public boolean isStopped() {
        return stopFlag;
    }

    @Override
    public void run() {
        thread = Thread.currentThread();
        log.debug("ServerMaintenanceTask thread running...");

        try {
            while (!stopFlag) {
                try {
                    long sleepTime = Math.max(MIN_SLEEP_INTERVAL_MS, (long) maxPing * 3);
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    // stop() wakes the thread this way.
                    log.debug("Sleep interrupted", e);
                }

                if (stopFlag) {
                    break;
                }

                if (userManager.isEmpty()) {
                    continue;
                }

                performMaintenance();
            }
        } catch (Throwable e) {
            if (!stopFlag) {
                log.error("ServerMaintenanceTask thread caught unexpected exception: " + e, e);
            }
        } finally {
            thread = null;
            log.debug("ServerMaintenanceTask thread exiting...");
        }
    }

    private void performMaintenance() {
        // Copy to avoid ConcurrentModificationException when removing during iteration
        List<KailleraUserImpl> usersSnapshot = new ArrayList<>(userManager.getUsers());
        List<Integer> usersToRemove = new ArrayList<>();

        for (KailleraUserImpl user : usersSnapshot) {
            synchronized (user) {
                int access = accessManager.getAccess(user.getConnectSocketAddress().getAddress());
                user.setAccess(access);

                MaintenanceAction action = checkUser(user, access);
                switch (action) {
                    case REMOVE_TIMEOUT :
                        log.info(user + " connection timeout!");
                        user.stop();
                        usersToRemove.add(user.getID());
                        break;
                    case QUIT_KEEPALIVE :
                        log.info(user + " keepalive timeout!");
                        requestQuit(user,
                                EmuLang.getString("KailleraServerImpl.ForcedQuitPingTimeout"));
                        break;
                    case QUIT_IDLE :
                        log.info(user + " inactivity timeout!");
                        requestQuit(user, EmuLang
                                .getString("KailleraServerImpl.ForcedQuitInactivityTimeout"));
                        break;
                    case QUIT_BANNED :
                        log.info(user + " banned!");
                        requestQuit(user, EmuLang.getString("KailleraServerImpl.ForcedQuitBanned"));
                        break;
                    case QUIT_EMULATOR_RESTRICTED :
                        log.info(user + ": emulator restricted!");
                        requestQuit(user, EmuLang
                                .getString("KailleraServerImpl.ForcedQuitEmulatorRestricted"));
                        break;
                    case NONE :
                    default :
                        break;
                }
            }
        }

        // Remove users after iteration to avoid ConcurrentModificationException
        for (Integer userId : usersToRemove) {
            userManager.removeUser(userId);
        }
    }

    /**
     * Decides what the maintenance pass should do with one user.
     *
     * @param user
     *            the user to check
     * @param access
     *            the user's current access level
     * @return the action to take
     */
    MaintenanceAction checkUser(KailleraUserImpl user, int access) {
        long now = System.currentTimeMillis();

        // Connection timeout (not logged in yet)
        if (!user.isLoggedIn()) {
            long timeout = user.hasStartedLogin() ? connectTimeoutMillis : preLoginTimeoutMillis;
            if ((now - user.getConnectTime()) > timeout) {
                return MaintenanceAction.REMOVE_TIMEOUT;
            }
        }

        // Keepalive timeout
        if (user.isLoggedIn() && (now - user.getLastKeepAlive()) > (keepAliveTimeout * 1000L)) {
            return MaintenanceAction.QUIT_KEEPALIVE;
        }

        // Idle timeout (only for normal users)
        if (idleTimeout > 0 && access == AccessManager.ACCESS_NORMAL && user.isLoggedIn()
                && (now - user.getLastActivity()) > (idleTimeout * 1000L)) {
            return MaintenanceAction.QUIT_IDLE;
        }

        // Banned
        if (user.isLoggedIn() && access < AccessManager.ACCESS_NORMAL) {
            return MaintenanceAction.QUIT_BANNED;
        }

        // Emulator restricted
        if (user.isLoggedIn() && access == AccessManager.ACCESS_NORMAL
                && !accessManager.isEmulatorAllowed(user.getClientType())) {
            return MaintenanceAction.QUIT_EMULATOR_RESTRICTED;
        }

        return MaintenanceAction.NONE;
    }

    private void requestQuit(KailleraUserImpl user, String message) {
        try {
            quitHandler.accept(new UserQuitRequest(user, message));
        } catch (Exception e) {
            log.error("Error forcing " + user + " quit: " + message, e);
        }
    }

    /** What one maintenance pass decides to do with one user. */
    enum MaintenanceAction {
        NONE, REMOVE_TIMEOUT, QUIT_KEEPALIVE, QUIT_IDLE, QUIT_BANNED, QUIT_EMULATOR_RESTRICTED
    }
}
