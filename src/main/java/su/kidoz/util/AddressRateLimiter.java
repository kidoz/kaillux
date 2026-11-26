package su.kidoz.util;

import java.net.InetAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts events per source address in one-second windows.
 *
 * <p>
 * Both the UDP connect port and the handshake admission path need this check,
 * and an internet-facing server may see spoofed source addresses. The table is
 * therefore bounded and stale windows are swept when it fills.
 */
public final class AddressRateLimiter {

    private static final long WINDOW_MILLIS = 1_000L;
    private static final long STALE_WINDOW_MILLIS = 2_000L;
    private static final int MAX_TRACKED_ADDRESSES = 2_048;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    /**
     * Returns true when this event fits in the caller's per-second budget.
     *
     * @param address
     *            the source address, or null for an unknown source
     * @param ratePerSecond
     *            the allowed events per second; zero or less disables the limit
     * @return true when the event is allowed
     */
    public boolean allow(InetAddress address, int ratePerSecond) {
        if (ratePerSecond <= 0 || address == null) {
            return true;
        }

        long now = System.currentTimeMillis();
        if (windows.size() > MAX_TRACKED_ADDRESSES) {
            sweep(now);
        }

        int[] observed = new int[1];
        windows.compute(address.getHostAddress(), (key, existing) -> {
            if (existing == null || now - existing.startMs >= WINDOW_MILLIS) {
                observed[0] = 1;
                return new Window(now, 1);
            }
            int next = existing.count + 1;
            observed[0] = next;
            return new Window(existing.startMs, next);
        });
        return observed[0] <= ratePerSecond;
    }

    private void sweep(long now) {
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().getValue();
            if (now - window.startMs >= STALE_WINDOW_MILLIS) {
                iterator.remove();
            }
        }
    }

    private static final class Window {
        private final long startMs;
        private final int count;

        private Window(long startMs, int count) {
            this.startMs = startMs;
            this.count = count;
        }
    }
}
