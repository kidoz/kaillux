package su.kidoz.kaillera.access;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import su.kidoz.util.WildcardStringPattern;

/**
 * Restricts in-game admin commands to sources that proved they can receive a
 * response on a trusted channel.
 *
 * <p>
 * Kaillera authorises in-game admins by source IP, and UDP source addresses are
 * spoofable. An off-path attacker can send a HELLO and a login bundle with a
 * privileged address without ever receiving a packet. Requiring a successful
 * HTTP Basic authentication from the same address makes the attacker receive
 * the authenticated response on TCP, which it cannot do blindly.
 *
 * <p>
 * Loopback is always trusted, and operators may list wildcard address patterns
 * for dedicated management networks. Everything else needs a recent successful
 * dashboard login.
 */
public final class InGameAdminGuard {

    private static final ConcurrentHashMap<String, Long> AUTHENTICATED = new ConcurrentHashMap<>();
    private static final int MAX_TRACKED_ADDRESSES = 1_024;

    private static volatile long ttlMillis = 1_800_000L;
    private static volatile List<WildcardStringPattern> allowlist = List.of();

    private InGameAdminGuard() {
    }

    /**
     * Applies the configured trust window and explicit address patterns.
     *
     * @param ttlSeconds
     *            how long an authenticated address stays trusted; zero disables
     *            remembered addresses
     * @param patterns
     *            comma-separated wildcard patterns, or empty
     */
    public static void configure(long ttlSeconds, String patterns) {
        ttlMillis = Math.max(0, ttlSeconds) * 1_000L;

        List<WildcardStringPattern> parsed = new ArrayList<>();
        if (patterns != null) {
            for (String pattern : patterns.split(",")) {
                String trimmed = pattern.trim();
                if (!trimmed.isEmpty()) {
                    parsed.add(new WildcardStringPattern(trimmed));
                }
            }
        }
        allowlist = List.copyOf(parsed);
    }

    /**
     * Records that an address completed HTTP Basic authentication.
     *
     * @param address
     *            the remote address of the authenticated request
     */
    public static void recordAuthenticatedAddress(String address) {
        if (address == null || address.isBlank()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (ttlMillis <= 0) {
            AUTHENTICATED.remove(address);
            return;
        }

        if (AUTHENTICATED.size() >= MAX_TRACKED_ADDRESSES) {
            AUTHENTICATED.entrySet().removeIf(entry -> entry.getValue() < now);
        }
        AUTHENTICATED.put(address, now + ttlMillis);
    }

    /**
     * True when in-game admin commands may be accepted from this source.
     *
     * @param address
     *            the UDP peer address
     * @return true for loopback, configured patterns, or a recently authenticated
     *         address
     */
    public static boolean isTrusted(InetAddress address) {
        if (address == null) {
            return false;
        }
        if (address.isLoopbackAddress()) {
            return true;
        }

        String host = address.getHostAddress();
        for (WildcardStringPattern pattern : allowlist) {
            if (pattern.match(host)) {
                return true;
            }
        }

        Long expiry = AUTHENTICATED.get(host);
        return expiry != null && expiry > System.currentTimeMillis();
    }
}
