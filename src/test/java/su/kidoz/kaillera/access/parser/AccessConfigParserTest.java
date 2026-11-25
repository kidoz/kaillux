package su.kidoz.kaillera.access.parser;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static su.kidoz.kaillera.access.AccessManager.ACCESS_ADMIN;
import static su.kidoz.kaillera.access.AccessManager.ACCESS_ELEVATED;
import static su.kidoz.kaillera.access.AccessManager.ACCESS_NORMAL;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import su.kidoz.kaillera.access.store.AccessRuleStore;

@DisplayName("AccessConfigParser Tests")
class AccessConfigParserTest {

    @TempDir
    Path directory;

    private final AccessConfigParser parser = new AccessConfigParser();

    private ParseResult parse(String... lines) throws IOException {
        Path file = directory.resolve("access.cfg");
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        return parser.parse(file.toFile());
    }

    @Test
    @DisplayName("rules may be indented")
    void rulesMayBeIndented() throws IOException {
        // An indented line used to be read from the wrong offset and then dropped,
        // which silently removed its rule.
        ParseResult result = parse("ipaddress,DENY,10.6.6.*", " ipaddress,DENY,10.7.7.*",
                "  user,ADMIN,192.0.2.9,", "\tuser,ELEVATED,192.0.2.10,", "# comment", "",
                "ipaddress,ALLOW,*");

        assertEquals(0, result.errorCount());

        AccessRuleStore store = new AccessRuleStore();
        store.update(result);
        assertFalse(store.isAddressAllowed("10.6.6.1"));
        assertFalse(store.isAddressAllowed("10.7.7.1"));
        assertTrue(store.isAddressAllowed("10.8.8.1"));
        assertEquals(ACCESS_ADMIN, store.getUserAccessLevel("192.0.2.9"));
        assertEquals(ACCESS_ELEVATED, store.getUserAccessLevel("192.0.2.10"));
        assertEquals(ACCESS_NORMAL, store.getUserAccessLevel("192.0.2.11"));
    }

    @Test
    @DisplayName("lines that cannot be parsed are counted, and the other lines load")
    void linesThatCannotBeParsedAreCounted() throws IOException {
        ParseResult result = parse("user,BANANA,192.0.2.9,", "user,ADMIN", "game,ALLOW,*",
                "ipaddress,DENY,10.6.6.*");

        assertEquals(2, result.errorCount());

        AccessRuleStore store = new AccessRuleStore();
        store.update(result);
        assertFalse(store.isAddressAllowed("10.6.6.1"), "the valid line must load");
        assertTrue(store.isGameAllowed("anything"));
        assertEquals(ACCESS_NORMAL, store.getUserAccessLevel("192.0.2.9"));
    }

    @Test
    @DisplayName("a dns rule matches every address its host resolves to")
    void aDnsRuleMatchesEveryAddressOfItsHost() throws IOException {
        ParseResult result = parse("user,ADMIN,dns:localhost,Welcome");

        AccessRuleStore store = new AccessRuleStore();
        store.update(result);

        for (InetAddress loopback : InetAddress.getAllByName("localhost")) {
            String address = loopback.getHostAddress();
            assertEquals(ACCESS_ADMIN, store.getUserAccessLevel(address), address);
            assertEquals("Welcome", store.getUserAnnouncement(address));
        }
        assertEquals(ACCESS_NORMAL, store.getUserAccessLevel("192.0.2.1"));
    }

    @Test
    @DisplayName("the file is read as UTF-8, whatever the platform default is")
    void theFileIsReadAsUtf8() throws IOException {
        ParseResult result = parse("game,DENY,*\u9a6c\u91cc\u5965*");

        AccessRuleStore store = new AccessRuleStore();
        store.update(result);

        assertFalse(store.isGameAllowed("\u8d85\u7ea7\u9a6c\u91cc\u5965"));
        assertTrue(store.isGameAllowed("Tetris"));
    }
}
