package su.kidoz.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@DisplayName("ThrottledLogger Tests")
class ThrottledLoggerTest {

    /** A logger whose events can be inspected. */
    private static final class Captured {
        private final Logger logger;
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

        Captured() {
            logger = (Logger) LoggerFactory
                    .getLogger("kaillux.test.throttle." + Long.toUnsignedString(System.nanoTime()));
            appender.start();
            logger.addAppender(appender);
        }

        List<String> messages() {
            return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.toList());
        }

        List<Level> levels() {
            return appender.list.stream().map(ILoggingEvent::getLevel).collect(Collectors.toList());
        }
    }

    @Test
    @DisplayName("only the first events of a window are written")
    void onlyTheFirstEventsOfAWindowAreWritten() {
        Captured captured = new Captured();
        ThrottledLogger throttled = new ThrottledLogger(captured.logger, "test events", 2, 60_000);

        for (int i = 1; i <= 1000; i++) {
            throttled.warn("event {}", i);
        }

        assertEquals(List.of("event 1", "event 2"), captured.messages());
    }

    @Test
    @DisplayName("the next window reports how many events were suppressed")
    void theNextWindowReportsHowManyEventsWereSuppressed() {
        Captured captured = new Captured();
        AtomicLong now = new AtomicLong(1_000);
        ThrottledLogger throttled = new ThrottledLogger(captured.logger, "test events", 1, 5,
                now::get);

        throttled.warn("first");
        throttled.warn("suppressed");
        now.addAndGet(5);
        throttled.warn("after the window");

        assertEquals(List.of("first", "test events: 1 further events suppressed in the last window",
                "after the window"), captured.messages());
    }

    @Test
    @DisplayName("error and warn share one budget, and a stack trace is kept")
    void errorAndWarnShareOneBudgetAndStackTraceIsKept() {
        Captured captured = new Captured();
        ThrottledLogger throttled = new ThrottledLogger(captured.logger, "test events", 2, 60_000);

        throttled.warn("warn");
        throttled.error("error {}", "detail", new IllegalStateException("cause"));
        throttled.error("never written");

        assertEquals(List.of("warn", "error detail"), captured.messages());
        assertEquals(List.of(Level.WARN, Level.ERROR), captured.levels());
        assertEquals("cause", captured.appender.list.get(1).getThrowableProxy().getMessage());
    }

    @Test
    @DisplayName("a budget of zero writes nothing")
    void aBudgetOfZeroWritesNothing() {
        Captured captured = new Captured();
        ThrottledLogger throttled = new ThrottledLogger(captured.logger, "test events", 0, 100);

        throttled.warn("event");

        assertEquals(List.of(), captured.messages());
    }
}
