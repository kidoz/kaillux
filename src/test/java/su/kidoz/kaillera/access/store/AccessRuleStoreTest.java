package su.kidoz.kaillera.access.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static su.kidoz.kaillera.access.AccessManager.ACCESS_ADMIN;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import su.kidoz.kaillera.access.parser.ParseResult;
import su.kidoz.kaillera.access.rule.AddressRule;
import su.kidoz.kaillera.access.rule.NameRule;

@DisplayName("AccessRuleStore Tests")
class AccessRuleStoreTest {

    @Test
    @Timeout(30)
    @DisplayName("every single check sees a complete rule set while the rules are replaced")
    void everyCheckSeesACompleteRuleSetWhileTheRulesAreReplaced() throws Exception {
        AccessRuleStore store = new AccessRuleStore();
        store.update(rulesOf("0"));

        AtomicBoolean stop = new AtomicBoolean();
        // The first check that found its rule missing.
        AtomicReference<String> missing = new AtomicReference<>();
        Runnable writer = () -> {
            for (int i = 1; !stop.get(); i++) {
                store.update(rulesOf(Integer.toString(i & 1)));
            }
        };
        Runnable reader = () -> {
            while (!stop.get() && missing.get() == null) {
                // One lookup at a time: each must be answered from a complete set.
                // Clearing the lists and refilling them left a rule missing for a
                // moment, which lets every address, game and emulator through.
                if (store.getUserAccessLevel("10.9.0.1") != ACCESS_ADMIN) {
                    missing.compareAndSet(null, "user");
                } else if (store.isAddressAllowed("10.9.1.1")) {
                    missing.compareAndSet(null, "address");
                } else if (store.isGameAllowed("sentinel-game")) {
                    missing.compareAndSet(null, "game");
                } else if (store.isEmulatorAllowed("sentinel-emulator")) {
                    missing.compareAndSet(null, "emulator");
                }
            }
        };

        Thread writerA = new Thread(writer, "rules-writer-a");
        Thread writerB = new Thread(writer, "rules-writer-b");
        Thread readerA = new Thread(reader, "rules-reader-a");
        Thread readerB = new Thread(reader, "rules-reader-b");
        for (Thread thread : List.of(writerA, writerB, readerA, readerB)) {
            thread.start();
        }
        Thread.sleep(500);
        stop.set(true);
        for (Thread thread : List.of(writerA, writerB, readerA, readerB)) {
            thread.join(5000);
        }

        assertNull(missing.get(), "a lookup was answered without its rule");
    }

    @Test
    @DisplayName("clear removes every rule at once")
    void clearRemovesEveryRuleAtOnce() {
        AccessRuleStore store = new AccessRuleStore();
        store.update(rulesOf("0"));

        store.clear();

        assertEquals(su.kidoz.kaillera.access.AccessManager.ACCESS_NORMAL,
                store.getUserAccessLevel("10.9.0.1"));
        assertTrue(store.isAddressAllowed("10.9.1.1"));
        assertTrue(store.isGameAllowed("sentinel-game"));
        assertTrue(store.isEmulatorAllowed("sentinel-emulator"));
    }

    @Test
    @DisplayName("the rule lists are the ones that were published")
    void theRuleListsAreTheOnesThatWerePublished() {
        AccessRuleStore store = new AccessRuleStore();
        store.update(rulesOf("0"));

        assertEquals(2, store.getUserRules().size());
        assertEquals(2, store.getAddressRules().size());
        assertEquals(2, store.getGameRules().size());
        assertEquals(2, store.getEmulatorRules().size());
    }

    /**
     * Two rules per category: one the same in every generation, and one that
     * differs, so that replacing the rules really changes something.
     */
    private static ParseResult rulesOf(String generation) {
        return new ParseResult(
                List.of(AddressRule.forUser("10.9.0.*", ACCESS_ADMIN, null),
                        AddressRule.forUser("10.0." + generation + ".*", ACCESS_ADMIN, null)),
                List.of(AddressRule.forAddress("10.9.1.*", false),
                        AddressRule.forAddress("10.1." + generation + ".*", false)),
                List.of(NameRule.forGame("sentinel-game", false),
                        NameRule.forGame("game-" + generation, false)),
                List.of(NameRule.forEmulator("sentinel-emulator", false),
                        NameRule.forEmulator("emulator-" + generation, false)),
                0);
    }
}
