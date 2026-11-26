package su.kidoz.kaillera.access;

import java.util.ArrayList;
import java.util.List;
import java.util.StringTokenizer;

import su.kidoz.util.WildcardStringPattern;

/**
 * Base class for time-limited access rules (temporary bans, temporary admin
 * grants, silence). Provides common pattern matching and expiration logic.
 */
public abstract class TimedAccessRule {
    private static final int MILLIS_PER_MINUTE = 60000;

    protected final List<WildcardStringPattern> patterns;
    protected final long startTime;
    protected final int minutes;

    /**
     * Creates a timed access rule with the given pattern and duration.
     *
     * @param addressPattern
     *            pipe-separated pattern string (e.g., "192.168.*|10.0.*")
     * @param minutes
     *            duration in minutes before the rule expires
     */
    protected TimedAccessRule(String addressPattern, int minutes) {
        this.patterns = parsePatterns(addressPattern);
        this.minutes = minutes;
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Parses a pipe-separated pattern string into wildcard patterns.
     *
     * @param patternString
     *            pipe-separated patterns (e.g., "192.168.*|10.0.*")
     * @return list of compiled wildcard patterns
     */
    protected List<WildcardStringPattern> parsePatterns(String patternString) {
        List<WildcardStringPattern> result = new ArrayList<>();
        String normalized = patternString.toLowerCase();
        StringTokenizer tokenizer = new StringTokenizer(normalized, "|");
        while (tokenizer.hasMoreTokens()) {
            result.add(new WildcardStringPattern(tokenizer.nextToken()));
        }
        return List.copyOf(result);
    }

    /**
     * Returns the compiled wildcard patterns.
     */
    public List<WildcardStringPattern> getPatterns() {
        return List.copyOf(patterns);
    }

    /**
     * Returns the time when this rule was created.
     */
    public long getStartTime() {
        return startTime;
    }

    /**
     * Returns the duration in minutes.
     */
    public int getMinutes() {
        return minutes;
    }

    /**
     * Checks if this rule has expired based on current time.
     *
     * @return true if the rule has exceeded its duration
     */
    public boolean isExpired() {
        return System.currentTimeMillis() > (startTime + ((long) minutes * MILLIS_PER_MINUTE));
    }

    /**
     * Checks if the given address matches any of the patterns.
     *
     * @param address
     *            the IP address to check
     * @return true if any pattern matches the address
     */
    public boolean matches(String address) {
        for (WildcardStringPattern pattern : patterns) {
            if (pattern.match(address)) {
                return true;
            }
        }
        return false;
    }
}
