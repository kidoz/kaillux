package su.kidoz.kaillera.access.parser;

import java.util.List;

import su.kidoz.kaillera.access.rule.AddressRule;
import su.kidoz.kaillera.access.rule.NameRule;

/**
 * Immutable result of parsing an access configuration file. Contains all parsed
 * rules organized by type.
 *
 * @param userRules
 *            user access level rules (USER type AddressRules)
 * @param addressRules
 *            IP allow/deny rules (ADDRESS type AddressRules)
 * @param gameRules
 *            game allow/deny rules
 * @param emulatorRules
 *            emulator allow/deny rules
 * @param errorCount
 *            number of parsing errors encountered
 */
public record ParseResult(List<AddressRule> userRules, List<AddressRule> addressRules,
        List<NameRule> gameRules, List<NameRule> emulatorRules, int errorCount) {

    public ParseResult {
        userRules = List.copyOf(userRules);
        addressRules = List.copyOf(addressRules);
        gameRules = List.copyOf(gameRules);
        emulatorRules = List.copyOf(emulatorRules);
    }

    public List<AddressRule> userRules() {
        return List.copyOf(userRules);
    }

    public List<AddressRule> addressRules() {
        return List.copyOf(addressRules);
    }

    public List<NameRule> gameRules() {
        return List.copyOf(gameRules);
    }

    public List<NameRule> emulatorRules() {
        return List.copyOf(emulatorRules);
    }

    /**
     * Returns the total number of rules parsed.
     */
    public int totalRules() {
        return userRules.size() + addressRules.size() + gameRules.size() + emulatorRules.size();
    }
}
