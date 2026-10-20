package su.kidoz.kaillera.model.impl;

import java.net.InetAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.kidoz.config.ServerConfig;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.model.exception.ServerFullException;
import su.kidoz.util.EmuLang;

/**
 * Decides whether a new HELLO may take a connecting slot.
 *
 * <p>
 * Logged-in users and in-progress handshakes are counted separately.
 * Per-address and per-second limits keep one source from filling the server.
 */
public final class ConnectionAdmission {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAdmission.class);
    private static final int STALE_WINDOW_MS = 2_000;
    private static final int MAX_TRACKED_ADDRESSES = 2_048;

    private final ConcurrentHashMap<String, RateWindow> helloWindows = new ConcurrentHashMap<>();

    /**
     * Accepts the connection or throws when a cap or the hello rate is exceeded.
     *
     * <p>
     * Callers must hold the connection lock so the counts cannot change between the
     * check and inserting the new user.
     */
    public void admit(InetAddress address, int access, int loggedIn, int connecting,
            int connectingFromAddress, ServerConfig config) throws ServerFullException {
        int maxUsers = config.getMaxUsers();
        if (maxUsers > 0 && loggedIn >= maxUsers && access <= AccessManager.ACCESS_NORMAL) {
            deny(address, "logged-in user limit reached");
        }

        int maxConnecting = config.getMaxConnecting();
        if (maxConnecting > 0 && connecting >= maxConnecting) {
            deny(address, "connecting-user limit reached");
        }

        int perAddress = config.getMaxConnectingPerAddress();
        if (perAddress > 0 && connectingFromAddress >= perAddress) {
            deny(address, "per-address connecting limit reached");
        }

        if (!allowHello(address, config.getHelloRatePerSecond())) {
            deny(address, "hello rate limit reached");
        }
    }

    private static void deny(InetAddress address, String reason) throws ServerFullException {
        String host = address == null ? "unknown" : address.getHostAddress();
        log.warn("Connection from {} denied: {}", host, reason);
        throw new ServerFullException(
                EmuLang.getString("KailleraServerImpl.LoginDeniedServerFull"));
    }

    private boolean allowHello(InetAddress address, int ratePerSecond) {
        if (ratePerSecond <= 0 || address == null) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (helloWindows.size() > MAX_TRACKED_ADDRESSES) {
            sweep(now);
        }

        int[] observed = new int[1];
        helloWindows.compute(address.getHostAddress(), (key, existing) -> {
            if (existing == null || now - existing.startMs >= 1_000L) {
                observed[0] = 1;
                return new RateWindow(now, 1);
            }
            int next = existing.count + 1;
            observed[0] = next;
            return new RateWindow(existing.startMs, next);
        });
        return observed[0] <= ratePerSecond;
    }

    private void sweep(long now) {
        Iterator<Map.Entry<String, RateWindow>> iterator = helloWindows.entrySet().iterator();
        while (iterator.hasNext()) {
            RateWindow window = iterator.next().getValue();
            if (now - window.startMs >= STALE_WINDOW_MS) {
                iterator.remove();
            }
        }
    }

    private static final class RateWindow {
        private final long startMs;
        private final int count;

        private RateWindow(long startMs, int count) {
            this.startMs = startMs;
            this.count = count;
        }
    }
}
