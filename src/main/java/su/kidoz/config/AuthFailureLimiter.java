package su.kidoz.config;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import su.kidoz.util.ThrottledLogger;

/**
 * Answers 401 at once for a client address that keeps presenting wrong Basic
 * credentials.
 *
 * <p>
 * Every attempt that reaches the password check costs one BCrypt hash, tens of
 * milliseconds of CPU, whether or not the password is right. Without a limit,
 * any client that can reach the admin port can spend the server's CPU as fast
 * as it can send requests. This filter sits in front of authentication and
 * short-circuits an address that failed {@link #DEFAULT_MAX_FAILURES} times
 * within {@link #DEFAULT_WINDOW_MILLIS}. A request that succeeds clears the
 * address.
 *
 * <p>
 * Requests without credentials pass through untouched: they cost nothing to
 * refuse. Addresses come from the connection, so behind a reverse proxy every
 * client shares one budget.
 */
public final class AuthFailureLimiter extends OncePerRequestFilter {
    /**
     * Failed attempts an address may make per window before it is refused outright.
     */
    static final int DEFAULT_MAX_FAILURES = 10;

    /** Window over which failures are counted. */
    static final long DEFAULT_WINDOW_MILLIS = 60_000;

    /** Entry cap: a flood must not grow the table without bound. */
    private static final int MAX_TRACKED_CLIENTS = 4096;

    private static final String BASIC_PREFIX = "Basic ";

    private static final String IPV4_MAPPED_PREFIX = "::ffff:";

    private static final Logger log = LoggerFactory.getLogger(AuthFailureLimiter.class);
    private static final ThrottledLogger LOCKOUT_LOG = new ThrottledLogger(log,
            "admin authentication lockout");

    private final int maxFailures;
    private final long windowMillis;
    private final int maxTrackedClients;
    private final Set<String> trustedProxies;
    private final LongSupplier clock;
    private final Map<String, Window> failures = new ConcurrentHashMap<>();

    /**
     * Creates a limiter with the default limit of ten failures per minute.
     */
    public AuthFailureLimiter() {
        this(DEFAULT_MAX_FAILURES, DEFAULT_WINDOW_MILLIS, MAX_TRACKED_CLIENTS, Set.of(),
                System::currentTimeMillis);
    }

    /**
     * Creates a limiter that honours X-Forwarded-For only from the given proxies.
     *
     * @param trustedProxies
     *            addresses allowed to assert a client address
     */
    public AuthFailureLimiter(Set<String> trustedProxies) {
        this(DEFAULT_MAX_FAILURES, DEFAULT_WINDOW_MILLIS, MAX_TRACKED_CLIENTS,
                Set.copyOf(trustedProxies), System::currentTimeMillis);
    }

    AuthFailureLimiter(int maxFailures, long windowMillis, LongSupplier clock) {
        this(maxFailures, windowMillis, MAX_TRACKED_CLIENTS, Set.of(), clock);
    }

    AuthFailureLimiter(int maxFailures, long windowMillis, int maxTrackedClients,
            Set<String> trustedProxies, LongSupplier clock) {
        if (maxFailures < 1 || windowMillis <= 0 || maxTrackedClients < 1) {
            throw new IllegalArgumentException(
                    "maxFailures and maxTrackedClients must be >= 1 and windowMillis > 0");
        }
        this.maxFailures = maxFailures;
        this.windowMillis = windowMillis;
        this.maxTrackedClients = maxTrackedClients;
        this.trustedProxies = Set.copyOf(trustedProxies);
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String client = trackedClient(request);
        if (client == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (isLocked(client)) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            // Authentication answers 401 whenever presented credentials are rejected,
            // on public paths as well as protected ones.
            if (response.getStatus() == HttpServletResponse.SC_UNAUTHORIZED) {
                recordFailure(client);
            } else {
                failures.remove(client);
            }
        }
    }

    /**
     * Returns the bucket for a request that presents Basic credentials, or null
     * when it presents none.
     *
     * @param request
     *            the inbound request
     * @return the bucket key, or null
     */
    private String trackedClient(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization == null
                || !authorization.regionMatches(true, 0, BASIC_PREFIX, 0, BASIC_PREFIX.length())) {
            return null;
        }
        return bucketKey(clientAddress(request));
    }

    /**
     * Resolves the client address. X-Forwarded-For is honoured only when the
     * connection comes from a configured proxy; otherwise any client could pick
     * someone else's bucket and lock them out.
     */
    private String clientAddress(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        if (remote == null || trustedProxies.isEmpty() || !trustedProxies.contains(remote)) {
            return remote;
        }

        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded == null || forwarded.isBlank()) {
            return remote;
        }

        int comma = forwarded.indexOf(',');
        String first = (comma >= 0 ? forwarded.substring(0, comma) : forwarded).trim();
        return first.isEmpty() ? remote : first;
    }

    /**
     * Buckets an address. An IPv6 network is a /64, so a client that owns one has
     * as many addresses as it likes: only the prefix is counted.
     *
     * @param remoteAddress
     *            the connection's remote address
     * @return the bucket key
     */
    static String bucketKey(String remoteAddress) {
        if (remoteAddress == null) {
            return "unknown";
        }

        String address = remoteAddress;
        if (address.length() > IPV4_MAPPED_PREFIX.length() && address.regionMatches(true, 0,
                IPV4_MAPPED_PREFIX, 0, IPV4_MAPPED_PREFIX.length())) {
            address = address.substring(IPV4_MAPPED_PREFIX.length());
        }

        int colons = 0;
        for (int i = 0; i < address.length(); i++) {
            if (address.charAt(i) == ':' && ++colons == 4) {
                return address.substring(0, i) + "/64";
            }
        }
        return address;
    }

    private boolean isLocked(String client) {
        Window window = failures.get(client);
        if (window == null) {
            return false;
        }
        return window.failures(clock.getAsLong(), windowMillis) >= maxFailures;
    }

    private void recordFailure(String client) {
        if (failures.size() >= maxTrackedClients && !failures.containsKey(client) && !sweep()) {
            // The table is full of live windows. Evict the oldest rather than
            // stopping the limit: a distributed attacker must not be able to make
            // every new address unlimited.
            evictOldest();
        }

        Window window = failures.computeIfAbsent(client, key -> new Window());
        boolean locked = window.record(clock.getAsLong(), windowMillis, maxFailures);
        if (locked) {
            LOCKOUT_LOG.warn(
                    "Admin authentication from {} failed {} times within {} ms; further "
                            + "attempts from it are refused until the window passes",
                    client, maxFailures, windowMillis);
        }
    }

    /**
     * Drops windows that have expired.
     *
     * @return true when the table is below its cap afterwards
     */
    private boolean sweep() {
        long now = clock.getAsLong();
        Iterator<Map.Entry<String, Window>> iterator = failures.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expired(now, windowMillis)) {
                iterator.remove();
            }
        }
        return failures.size() < maxTrackedClients;
    }

    /** Removes the window with the oldest start time. */
    private void evictOldest() {
        String oldestKey = null;
        long oldestStart = Long.MAX_VALUE;
        for (Map.Entry<String, Window> entry : failures.entrySet()) {
            long start = entry.getValue().startTime();
            if (start < oldestStart) {
                oldestStart = start;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            failures.remove(oldestKey);
        }
    }

    /** Failure count of one client inside one window. */
    private static final class Window {
        private long start;
        private int failures;

        private synchronized int failures(long now, long windowMillis) {
            roll(now, windowMillis);
            return failures;
        }

        private synchronized boolean record(long now, long windowMillis, int maxFailures) {
            roll(now, windowMillis);
            return ++failures == maxFailures;
        }

        private synchronized boolean expired(long now, long windowMillis) {
            return now - start >= windowMillis;
        }

        private synchronized long startTime() {
            return start;
        }

        private void roll(long now, long windowMillis) {
            if (now - start >= windowMillis) {
                start = now;
                failures = 0;
            }
        }
    }
}
