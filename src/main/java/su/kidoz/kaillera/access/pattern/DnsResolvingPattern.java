package su.kidoz.kaillera.access.pattern;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import su.kidoz.util.WildcardStringPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pattern matcher that supports both wildcard IP patterns and DNS hostname
 * resolution. Hostnames are periodically resolved to IP addresses to support
 * dynamic DNS.
 *
 * <p>
 * Pattern format: pipe-separated patterns (e.g., "192.168.*|dns:example.com")
 * <ul>
 * <li>Regular patterns: matched using wildcard pattern matching</li>
 * <li>DNS patterns: prefixed with "dns:", resolved to IP addresses</li>
 * </ul>
 */
public class DnsResolvingPattern implements PatternMatcher {

    private static final Logger log = LoggerFactory.getLogger(DnsResolvingPattern.class);
    private static final String DNS_PREFIX = "dns:";

    private final String patternString;
    private final List<WildcardStringPattern> patterns;
    private final List<String> hostNames;

    /** Addresses each hostname last resolved to. */
    private volatile Map<String, List<String>> resolvedByHost = Map.of();

    /** Every resolved address, flat, so that matching is a single set lookup. */
    private volatile Set<String> resolvedAddresses = Set.of();

    /**
     * Creates a pattern matcher from a pipe-separated pattern string.
     *
     * @param patternString
     *            pipe-separated patterns (e.g., "192.168.*|dns:example.com")
     */
    public DnsResolvingPattern(String patternString) {
        this.patternString = patternString;
        List<WildcardStringPattern> tempPatterns = new ArrayList<>();
        List<String> tempHostNames = new ArrayList<>();

        StringTokenizer tokenizer = new StringTokenizer(patternString.toLowerCase(), "|");
        while (tokenizer.hasMoreTokens()) {
            String pattern = tokenizer.nextToken();
            if (pattern.startsWith(DNS_PREFIX)) {
                String hostName = pattern.substring(DNS_PREFIX.length());
                if (!hostName.isEmpty()) {
                    tempHostNames.add(hostName);
                } else {
                    log.warn("Empty DNS hostname in pattern: {}", patternString);
                }
            } else {
                tempPatterns.add(new WildcardStringPattern(normalizeAddressLiteral(pattern)));
            }
        }

        this.patterns = List.copyOf(tempPatterns);
        this.hostNames = List.copyOf(tempHostNames);

        refreshDns();
    }

    /**
     * Rewrites an IPv6 address literal into the form the server compares with. Java
     * reports IPv6 addresses uncompressed, {@code 0:0:0:0:0:0:0:1} rather than
     * {@code ::1}, so a rule written the short way would never match anyone.
     *
     * @param pattern
     *            one lower-case pattern token
     * @return the pattern to match with
     */
    static String normalizeAddressLiteral(String pattern) {
        // A pattern with a wildcard, or with a character that no address literal
        // has, is left alone. Only then is it handed to the address parser, so the
        // call can never turn into a name lookup.
        if (pattern.indexOf(':') < 0 || !isAddressLiteral(pattern)) {
            return pattern;
        }
        try {
            return InetAddress.getByName(pattern).getHostAddress();
        } catch (UnknownHostException e) {
            return pattern;
        }
    }

    private static boolean isAddressLiteral(String pattern) {
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            boolean hexDigit = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hexDigit && c != ':' && c != '.') {
                return false;
            }
        }
        return true;
    }

    /**
     * Refreshes all DNS hostname resolutions. Should be called periodically to
     * handle dynamic DNS changes.
     *
     * <p>
     * A hostname with several records resolves to all of them. When a lookup fails,
     * the hostname keeps the addresses of its last successful lookup: a resolver
     * outage must not turn a {@code dns:} deny rule into an allow, or drop an admin
     * grant.
     */
    public void refreshDns() {
        Map<String, List<String>> resolved = new HashMap<>();
        for (String hostName : hostNames) {
            try {
                List<String> addresses = new ArrayList<>();
                for (InetAddress address : InetAddress.getAllByName(hostName)) {
                    addresses.add(address.getHostAddress());
                }
                resolved.put(hostName, List.copyOf(addresses));
            } catch (UnknownHostException e) {
                resolved.put(hostName, addressesAfterFailedLookup(hostName, e));
            }
        }

        Map<String, List<String>> byHost = Map.copyOf(resolved);
        Set<String> flat = new HashSet<>();
        for (List<String> addresses : byHost.values()) {
            flat.addAll(addresses);
        }
        this.resolvedByHost = byHost;
        this.resolvedAddresses = Set.copyOf(flat);
    }

    /**
     * Returns the addresses to keep for a hostname whose lookup just failed.
     *
     * @param hostName
     *            the hostname that could not be resolved
     * @param cause
     *            the failure
     * @return the addresses of the last successful lookup, or an empty list if
     *         there never was one
     */
    List<String> addressesAfterFailedLookup(String hostName, UnknownHostException cause) {
        List<String> previous = resolvedByHost.get(hostName);
        if (previous != null) {
            log.debug("Failed to resolve DNS entry to an address: {}: {}", hostName,
                    cause.toString());
            return previous;
        }

        // A hostname that never resolved matches no address, so a deny rule that
        // names it protects nobody. That has to be visible when it happens.
        log.warn("Failed to resolve DNS entry to an address: {}: {}", hostName, cause.toString());
        return List.of();
    }

    @Override
    public boolean matches(String address) {
        if (address == null || address.isEmpty()) {
            return false;
        }

        // Check wildcard patterns
        for (WildcardStringPattern pattern : patterns) {
            if (pattern.match(address)) {
                return true;
            }
        }

        // Check resolved DNS addresses
        return resolvedAddresses.contains(address);
    }

    /**
     * Returns the pattern this matcher was created from.
     */
    @Override
    public String toString() {
        return patternString;
    }

    /**
     * Returns the list of hostnames configured for DNS resolution.
     */
    public List<String> getHostNames() {
        return hostNames;
    }

    /**
     * Returns the currently resolved addresses.
     */
    public List<String> getResolvedAddresses() {
        return new ArrayList<>(resolvedAddresses);
    }

    /**
     * Returns whether this pattern has any DNS entries.
     */
    public boolean hasDnsEntries() {
        return !hostNames.isEmpty();
    }
}
