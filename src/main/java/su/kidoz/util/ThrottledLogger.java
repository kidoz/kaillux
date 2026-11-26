package su.kidoz.util;

import java.util.function.LongSupplier;

import org.slf4j.Logger;

/**
 * Writes a bounded number of WARN and ERROR events per time window.
 *
 * <p>
 * Untrusted peers decide how many packets they send, so one WARN per rejected
 * packet is a log-flood and CPU amplifier. The first {@code limit} events of
 * each window are written; the rest are counted and reported in a single line
 * when the window ends. Create one instance per call site and keep it for the
 * life of the class or object that owns it.
 */
public final class ThrottledLogger {
    /** Default number of events written per window. */
    public static final int DEFAULT_LIMIT = 10;

    /** Default window length in milliseconds. */
    public static final long DEFAULT_WINDOW_MILLIS = 60_000;

    private final Logger log;
    private final String name;
    private final int limit;
    private final long windowMillis;
    private final LongSupplier clock;

    private long windowStart;
    private int written;
    private long suppressed;

    /**
     * Creates a throttle that writes {@link #DEFAULT_LIMIT} events per minute.
     *
     * @param log
     *            the logger events are written to
     * @param name
     *            short description used in the suppression line
     */
    public ThrottledLogger(Logger log, String name) {
        this(log, name, DEFAULT_LIMIT, DEFAULT_WINDOW_MILLIS);
    }

    /**
     * Creates a throttle with an explicit limit and window.
     *
     * @param log
     *            the logger events are written to
     * @param name
     *            short description used in the suppression line
     * @param limit
     *            events written per window
     * @param windowMillis
     *            window length in milliseconds
     */
    public ThrottledLogger(Logger log, String name, int limit, long windowMillis) {
        this(log, name, limit, windowMillis, System::currentTimeMillis);
    }

    /**
     * Creates a throttle whose clock can be controlled, for tests.
     *
     * @param log
     *            the logger events are written to
     * @param name
     *            short description used in the suppression line
     * @param limit
     *            events written per window
     * @param windowMillis
     *            window length in milliseconds
     * @param clock
     *            supplies the current time in milliseconds
     */
    ThrottledLogger(Logger log, String name, int limit, long windowMillis, LongSupplier clock) {
        if (limit < 0 || windowMillis <= 0) {
            throw new IllegalArgumentException("limit must be >= 0 and windowMillis > 0");
        }
        this.log = log;
        this.name = name;
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /**
     * Writes a WARN when this call site is still within its budget.
     *
     * @param format
     *            the SLF4J message format
     * @param args
     *            the format arguments
     */
    public void warn(String format, Object... args) {
        if (admit()) {
            log.warn(format, args);
        }
    }

    /**
     * Writes an ERROR when this call site is still within its budget.
     *
     * @param format
     *            the SLF4J message format
     * @param args
     *            the format arguments
     */
    public void error(String format, Object... args) {
        if (admit()) {
            log.error(format, args);
        }
    }

    /**
     * Writes an INFO when this call site is still within its budget.
     *
     * @param format
     *            the SLF4J message format
     * @param args
     *            the format arguments
     */
    public void info(String format, Object... args) {
        if (admit()) {
            log.info(format, args);
        }
    }

    /**
     * Writes a DEBUG when this call site is still within its budget.
     *
     * @param format
     *            the SLF4J message format
     * @param args
     *            the format arguments
     */
    public void debug(String format, Object... args) {
        if (admit()) {
            log.debug(format, args);
        }
    }

    /**
     * Writes an ERROR with a stack trace when this call site is still within its
     * budget.
     *
     * @param format
     *            the SLF4J message format
     * @param arg
     *            the single format argument
     * @param thrown
     *            the exception to log
     */
    public void error(String format, Object arg, Throwable thrown) {
        if (admit()) {
            log.error(format, arg, thrown);
        }
    }

    private boolean admit() {
        long report;
        boolean allowed;
        synchronized (this) {
            long now = clock.getAsLong();
            if (now - windowStart >= windowMillis) {
                report = suppressed;
                windowStart = now;
                written = 0;
                suppressed = 0;
            } else {
                report = 0;
            }
            if (written < limit) {
                written++;
                allowed = true;
            } else {
                suppressed++;
                allowed = false;
            }
        }
        if (report > 0) {
            log.warn("{}: {} further events suppressed in the last window", name, report);
        }
        return allowed;
    }
}
