package su.kidoz.kaillera.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static su.kidoz.kaillera.access.AccessManager.ACCESS_NORMAL;
import static org.mockito.Mockito.mock;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import su.kidoz.util.EmuLinkerExecutor;

@DisplayName("FileBasedAccessManager Tests")
class FileBasedAccessManagerTest {

    private static final String LOOPBACK_WARNING = "gives Admin access to connections from localhost";

    @TempDir
    Path directory;

    private Path accessFile;

    /**
     * Distinguishes successive versions of the file even on coarse file systems.
     */
    private final AtomicInteger version = new AtomicInteger();

    // The maintenance thread is not started; the tests drive reloads themselves.
    private final EmuLinkerExecutor executor = mock(EmuLinkerExecutor.class);

    private FileBasedAccessManager load(String... lines) throws Exception {
        accessFile = directory.resolve("access.cfg");
        write(lines);
        return new FileBasedAccessManager(executor, accessFile.toString());
    }

    private void write(String... lines) throws Exception {
        Files.writeString(accessFile, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        accessFile.toFile()
                .setLastModified(System.currentTimeMillis() + version.incrementAndGet() * 2000);
    }

    private static InetAddress address(String literal) throws Exception {
        return InetAddress.getByName(literal);
    }

    @Test
    @DisplayName("a temporary ban reads as banned, not as a normal user")
    void aTemporaryBanReadsAsBanned() throws Exception {
        FileBasedAccessManager manager = load("ipaddress,ALLOW,*", "user,NORMAL,*");
        InetAddress client = address("198.51.100.4");

        assertEquals(ACCESS_NORMAL, manager.getAccess(client));

        manager.addTempBan("198.51.100.*", 5);

        assertEquals(AccessManager.ACCESS_BANNED, manager.getAccess(client),
                "other sessions of the address must lose their normal access too");
        assertFalse(manager.isAddressAllowed(client));
    }

    @Test
    @DisplayName("an IPv6 temporary ban covers the whole /64")
    void anIpv6TemporaryBanCoversThe64() throws Exception {
        FileBasedAccessManager manager = load("ipaddress,ALLOW,*", "user,NORMAL,*");

        manager.addTempBan("2001:db8:1:2::1", 5);

        assertEquals(AccessManager.ACCESS_BANNED, manager.getAccess(address("2001:db8:1:2::abcd")),
                "rotating the interface identifier must not escape the ban");
        assertEquals(ACCESS_NORMAL, manager.getAccess(address("2001:db8:1:3::1")),
                "a different /64 must stay allowed");
    }

    @Test
    @DisplayName("a file with a bad line keeps the last rules that parsed cleanly")
    void aFileWithABadLineKeepsThePreviousRules() throws Exception {
        FileBasedAccessManager manager = load("ipaddress,DENY,10.6.6.*", "ipaddress,ALLOW,*");
        assertFalse(manager.isAddressAllowed(address("10.6.6.1")));

        write("ipaddress,DENY,10.9.9.*", "this line is not a rule");
        assertTrue(manager.reloadIfChanged());

        assertFalse(manager.isAddressAllowed(address("10.6.6.1")),
                "the last good rule set must stay active");
        assertTrue(manager.isAddressAllowed(address("10.9.9.1")),
                "the unparsable file must not be applied");
        assertFalse(manager.reloadIfChanged(),
                "the file must not be read again until it changes once more");

        write("ipaddress,DENY,10.7.7.*", "ipaddress,ALLOW,*");
        assertTrue(manager.reloadIfChanged());
        assertTrue(manager.isAddressAllowed(address("10.6.6.1")));
        assertFalse(manager.isAddressAllowed(address("10.7.7.1")));
    }

    @Test
    @DisplayName("startup refuses an access file with unparsable lines")
    void startupRefusesUnparsableLines() {
        assertThrows(IllegalStateException.class,
                () -> load("nonsense", "ipaddress,DENY,10.6.6.*", "ipaddress,ALLOW,*"));
    }

    @Test
    @DisplayName("an IPv6 rule written the short way matches the address as the server reports it")
    void anIpv6RuleWrittenTheShortWayMatches() throws Exception {
        FileBasedAccessManager manager = load("ipaddress,DENY,2001:db8::1", "ipaddress,ALLOW,*");

        // Java reports the address uncompressed; the rule used to miss it.
        assertFalse(manager.isAddressAllowed(address("2001:db8::1")));
        assertEquals("2001:db8:0:0:0:0:0:1", address("2001:db8::1").getHostAddress());
        assertTrue(manager.isAddressAllowed(address("2001:db8::2")));
    }

    @Test
    @DisplayName("an admin rule for loopback is flagged when the rules load")
    void anAdminRuleForLoopbackIsFlagged() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(FileBasedAccessManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            load("user,ADMIN,127.0.0.1,Local admin", "user,ADMIN,::1,", "user,ADMIN,192.0.2.5,",
                    "ipaddress,ALLOW,*");

            List<String> warnings = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.toList());
            assertEquals(2, warnings.stream().filter(m -> m.contains(LOOPBACK_WARNING)).count(),
                    "both loopback admin rules must be flagged: " + warnings);
        } finally {
            logger.detachAppender(appender);
        }
    }
}
