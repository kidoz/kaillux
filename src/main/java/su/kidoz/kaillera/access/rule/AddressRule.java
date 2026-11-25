package su.kidoz.kaillera.access.rule;

import su.kidoz.kaillera.access.AccessManager;

import su.kidoz.kaillera.access.pattern.DnsResolvingPattern;

/**
 * Unified rule for IP address-based access control. Supports both user access
 * levels (normal/elevated/admin) and simple allow/deny rules.
 *
 * <p>
 * This class unifies the previous UserAccess and AddressAccess inner classes,
 * eliminating ~70% code duplication.
 */
public final class AddressRule {

    /**
     * Type of address rule.
     */
    public enum Type {
        /** User access level rule (normal, elevated, admin) with optional message */
        USER,
        /** IP allow/deny rule */
        ADDRESS
    }

    private final Type type;
    private final DnsResolvingPattern pattern;
    private final int accessLevel;
    private final boolean allowed;
    private final String message;

    private AddressRule(Type type, DnsResolvingPattern pattern, int accessLevel, boolean allowed,
            String message) {
        this.type = type;
        this.pattern = pattern;
        this.accessLevel = accessLevel;
        this.allowed = allowed;
        this.message = message;
    }

    /**
     * Creates a user access rule with a specific access level.
     *
     * @param patternString
     *            pipe-separated IP patterns with optional DNS entries
     * @param accessLevel
     *            one of AccessManager.ACCESS_NORMAL/ELEVATED/ADMIN
     * @param message
     *            optional announcement message (may be null)
     * @return the user access rule
     */
    public static AddressRule forUser(String patternString, int accessLevel, String message) {
        return new AddressRule(Type.USER, new DnsResolvingPattern(patternString), accessLevel, true,
                message);
    }

    /**
     * Creates an IP address allow/deny rule.
     *
     * @param patternString
     *            pipe-separated IP patterns with optional DNS entries
     * @param allowed
     *            true to allow, false to deny
     * @return the address access rule
     */
    public static AddressRule forAddress(String patternString, boolean allowed) {
        return new AddressRule(Type.ADDRESS, new DnsResolvingPattern(patternString),
                AccessManager.ACCESS_NORMAL, allowed, null);
    }

    /**
     * Returns the rule type.
     */
    public Type getType() {
        return type;
    }

    /**
     * Checks if the given address matches this rule's pattern.
     *
     * @param address
     *            the IP address to check
     * @return true if the address matches
     */
    public boolean matches(String address) {
        return pattern.matches(address);
    }

    /**
     * Returns the access level for USER type rules.
     */
    public int getAccessLevel() {
        return accessLevel;
    }

    /**
     * Returns whether access is allowed for ADDRESS type rules.
     */
    public boolean isAllowed() {
        return allowed;
    }

    /**
     * Returns the announcement message for USER type rules (may be null).
     */
    public String getMessage() {
        return message;
    }

    /**
     * Refreshes DNS resolutions for this rule. Should be called periodically.
     */
    public void refreshDns() {
        pattern.refreshDns();
    }

    /**
     * Returns whether this rule has DNS entries that need periodic refresh.
     */
    public boolean hasDnsEntries() {
        return pattern.hasDnsEntries();
    }

    @Override
    public String toString() {
        String effect = type == Type.USER
                ? AccessManager.getAccessName(accessLevel)
                : (allowed ? "allow" : "deny");
        return type.name().toLowerCase() + " rule for " + pattern + " (" + effect + ")";
    }
}
