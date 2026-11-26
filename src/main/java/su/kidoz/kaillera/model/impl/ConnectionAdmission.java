package su.kidoz.kaillera.model.impl;

import java.net.InetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.kidoz.config.ServerConfig;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.model.exception.ServerFullException;
import su.kidoz.util.AddressRateLimiter;
import su.kidoz.util.EmuLang;
import su.kidoz.util.ThrottledLogger;

/**
 * Decides whether a new HELLO may take a connecting slot.
 *
 * <p>
 * Logged-in users and in-progress handshakes are counted separately.
 * Per-address and per-second limits keep one source from filling the server.
 */
public final class ConnectionAdmission {
    private static final Logger log = LoggerFactory.getLogger(ConnectionAdmission.class);
    // One line per denied HELLO is a log flood for anyone who can send packets.
    private static final ThrottledLogger DENIED_LOG = new ThrottledLogger(log, "denied handshake");
    private final AddressRateLimiter helloLimiter = new AddressRateLimiter();

    /**
     * Accepts the connection or throws when a cap or the hello rate is exceeded.
     *
     * <p>
     * Callers must hold the connection lock so the counts cannot change between the
     * check and inserting the new user.
     */
    public void admit(InetAddress address, int access, int loggedIn, int loggedInFromAddress,
            int connecting, int connectingFromAddress, ServerConfig config)
            throws ServerFullException {
        int maxUsers = config.getMaxUsers();
        if (maxUsers > 0 && loggedIn >= maxUsers && access <= AccessManager.ACCESS_NORMAL) {
            deny(address, "logged-in user limit reached");
        }

        int maxUsersPerAddress = config.getMaxUsersPerAddress();
        if (maxUsersPerAddress > 0 && loggedInFromAddress >= maxUsersPerAddress
                && access <= AccessManager.ACCESS_NORMAL) {
            deny(address, "per-address logged-in user limit reached");
        }

        int maxConnecting = config.getMaxConnecting();
        if (maxConnecting > 0 && connecting >= maxConnecting) {
            deny(address, "connecting-user limit reached");
        }

        int perAddress = config.getMaxConnectingPerAddress();
        if (perAddress > 0 && connectingFromAddress >= perAddress) {
            deny(address, "per-address connecting limit reached");
        }

        if (!helloLimiter.allow(address, config.getHelloRatePerSecond())) {
            deny(address, "hello rate limit reached");
        }
    }

    private static void deny(InetAddress address, String reason) throws ServerFullException {
        String host = address == null ? "unknown" : address.getHostAddress();
        DENIED_LOG.warn("Connection from {} denied: {}", host, reason);
        throw new ServerFullException(
                EmuLang.getString("KailleraServerImpl.LoginDeniedServerFull"));
    }

}
