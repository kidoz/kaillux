package su.kidoz.kaillera.access.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.UnknownHostException;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@DisplayName("DnsResolvingPattern Tests")
class DnsResolvingPatternTest {

    /** A rule with a dns: entry. localhost resolves without a network. */
    private static final String PATTERN = "10.0.0.*|dns:localhost";

    private final Logger logger = (Logger) LoggerFactory.getLogger(DnsResolvingPattern.class);

    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach
    void captureLog() {
        // The default level is INFO; DEBUG lines only exist when DEBUG is on.
        logger.setLevel(Level.DEBUG);
        events.start();
        logger.addAppender(events);
    }

    @AfterEach
    void releaseLog() {
        logger.detachAppender(events);
        logger.setLevel(null);
    }

    private List<Level> levels() {
        return events.list.stream().map(ILoggingEvent::getLevel).collect(Collectors.toList());
    }

    @Test
    @DisplayName("a hostname that never resolved leaves the plain patterns working")
    void aHostnameThatNeverResolvedLeavesThePlainPatternsWorking() {
        DnsResolvingPattern pattern = new DnsResolvingPattern(PATTERN);

        assertTrue(pattern.matches("10.0.0.5"));
        assertFalse(pattern.matches("192.0.2.1"));
    }

    @Test
    @DisplayName("a failed lookup with no earlier addresses is reported as a warning")
    void aFailedLookupWithNoEarlierAddressesIsReportedAsAWarning() {
        DnsResolvingPattern pattern = new DnsResolvingPattern(PATTERN);

        List<String> kept = pattern.addressesAfterFailedLookup("kaillux.example",
                new UnknownHostException("name not known"));

        assertTrue(kept.isEmpty());
        assertEquals(List.of(Level.WARN), levels(),
                "a deny rule that names the hostname protects nobody until it resolves");
        assertEquals(
                "Failed to resolve DNS entry to an address: kaillux.example: "
                        + "java.net.UnknownHostException: name not known",
                events.list.get(0).getFormattedMessage());
    }

    @Test
    @DisplayName("a failed later lookup keeps the last addresses and stays quiet")
    void aFailedLaterLookupKeepsTheLastAddressesQuietly() {
        DnsResolvingPattern pattern = new DnsResolvingPattern(PATTERN);
        assertFalse(pattern.getResolvedAddresses().isEmpty(), "localhost must resolve");
        events.list.clear();

        List<String> kept = pattern.addressesAfterFailedLookup("localhost",
                new UnknownHostException("resolver down"));

        assertFalse(kept.isEmpty());
        assertTrue(pattern.getResolvedAddresses().containsAll(kept),
                "the kept addresses are the ones the pattern matched by");
        assertEquals(List.of(Level.DEBUG), levels(),
                "keeping the last known addresses is a detail, not an alarm");
    }
}
