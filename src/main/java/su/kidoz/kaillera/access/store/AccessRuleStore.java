package su.kidoz.kaillera.access.store;

import java.util.List;

import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.access.parser.ParseResult;
import su.kidoz.kaillera.access.rule.AddressRule;
import su.kidoz.kaillera.access.rule.NameRule;

/**
 * Holds the permanent access rules loaded from configuration.
 *
 * <p>
 * The rules are read on every HELLO, login and chat message, and replaced
 * whenever the configuration file changes. A reader must never see the rules
 * halfway through such a replacement: with the lists empty for a moment, every
 * address, game and emulator would be allowed and every user would read as
 * {@link AccessManager#ACCESS_NORMAL}. So a whole rule set is published with
 * one volatile write, and until that happens the previous set answers every
 * check.
 */
public class AccessRuleStore {

    /** One consistent set of rules. */
    private record RuleSet(List<AddressRule> userRules, List<AddressRule> addressRules,
            List<NameRule> gameRules, List<NameRule> emulatorRules) {
        static final RuleSet NONE = new RuleSet(List.of(), List.of(), List.of(), List.of());
    }

    private volatile RuleSet rules = RuleSet.NONE;

    /**
     * Replaces all rules with those of a parse result.
     *
     * @param result
     *            the parsed rules
     */
    public void update(ParseResult result) {
        rules = new RuleSet(List.copyOf(result.userRules()), List.copyOf(result.addressRules()),
                List.copyOf(result.gameRules()), List.copyOf(result.emulatorRules()));
    }

    /**
     * Removes all rules.
     */
    public void clear() {
        rules = RuleSet.NONE;
    }

    /**
     * Refreshes DNS resolutions for all rules that have DNS entries.
     */
    public void refreshDns() {
        RuleSet current = rules;
        for (AddressRule rule : current.userRules()) {
            if (rule.hasDnsEntries()) {
                rule.refreshDns();
            }
        }
        for (AddressRule rule : current.addressRules()) {
            if (rule.hasDnsEntries()) {
                rule.refreshDns();
            }
        }
    }

    /**
     * Finds the access level for a user address.
     *
     * @param userAddress
     *            the IP address to check
     * @return the access level, or ACCESS_NORMAL if no rule matches
     */
    public int getUserAccessLevel(String userAddress) {
        for (AddressRule rule : rules.userRules()) {
            if (rule.matches(userAddress)) {
                return rule.getAccessLevel();
            }
        }
        return AccessManager.ACCESS_NORMAL;
    }

    /**
     * Finds the announcement message for a user address.
     *
     * @param userAddress
     *            the IP address to check
     * @return the message, or null if no rule matches or rule has no message
     */
    public String getUserAnnouncement(String userAddress) {
        for (AddressRule rule : rules.userRules()) {
            if (rule.matches(userAddress)) {
                return rule.getMessage();
            }
        }
        return null;
    }

    /**
     * Checks if an address is allowed by address rules. Returns true if no rule
     * matches (default allow).
     *
     * @param userAddress
     *            the IP address to check
     * @return true if allowed, false if denied
     */
    public boolean isAddressAllowed(String userAddress) {
        for (AddressRule rule : rules.addressRules()) {
            if (rule.matches(userAddress)) {
                return rule.isAllowed();
            }
        }
        return true;
    }

    /**
     * Checks if a game is allowed. Returns true if no rule matches (default allow).
     *
     * @param gameName
     *            the game name to check
     * @return true if allowed, false if denied
     */
    public boolean isGameAllowed(String gameName) {
        for (NameRule rule : rules.gameRules()) {
            if (rule.matches(gameName)) {
                return rule.isAllowed();
            }
        }
        return true;
    }

    /**
     * Checks if an emulator is allowed. Returns true if no rule matches (default
     * allow).
     *
     * @param emulatorName
     *            the emulator name to check
     * @return true if allowed, false if denied
     */
    public boolean isEmulatorAllowed(String emulatorName) {
        for (NameRule rule : rules.emulatorRules()) {
            if (rule.matches(emulatorName)) {
                return rule.isAllowed();
            }
        }
        return true;
    }

    /**
     * Returns the user rules currently in effect (for testing/debugging).
     */
    public List<AddressRule> getUserRules() {
        return rules.userRules();
    }

    /**
     * Returns the address rules currently in effect (for testing/debugging).
     */
    public List<AddressRule> getAddressRules() {
        return rules.addressRules();
    }

    /**
     * Returns the game rules currently in effect (for testing/debugging).
     */
    public List<NameRule> getGameRules() {
        return rules.gameRules();
    }

    /**
     * Returns the emulator rules currently in effect (for testing/debugging).
     */
    public List<NameRule> getEmulatorRules() {
        return rules.emulatorRules();
    }
}
