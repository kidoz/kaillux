package su.kidoz.kaillera.model.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("InputValidator Tests")
class InputValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {"plain text", "\u4e2d\u6587 player", "a|b&c=d", "emoji \uD83D\uDE00"})
    @DisplayName("printable text has no illegal characters")
    void printableTextHasNoIllegalCharacters(String text) {
        assertFalse(InputValidator.hasIllegalCharacters(text));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "line\nbreak", "tab\there", "del\u007F", "zero\u200Bwidth",
            "bidi\u202Eoverride", "bidi\u2066isolated", "line\u0085separator"})
    @DisplayName("text that could forge or reorder what a client or the log shows is illegal")
    void textThatCouldForgeOrReorderIsIllegal(String text) {
        assertTrue(InputValidator.hasIllegalCharacters(text));
    }

    @Test
    @DisplayName("null is illegal")
    void nullIsIllegal() {
        assertTrue(InputValidator.hasIllegalCharacters(null));
    }

    @Test
    @DisplayName("sanitizeForLog turns line breaks into escape sequences")
    void sanitizeForLogTurnsLineBreaksIntoEscapeSequences() {
        assertEquals("bye\\nFORGED LOG LINE\\r\\x1B[31m",
                InputValidator.sanitizeForLog("bye\nFORGED LOG LINE\r\u001B[31m"));
    }

    @Test
    @DisplayName("sanitizeForLog escapes invisible and line-breaking characters")
    void sanitizeForLogEscapesInvisibleCharacters() {
        assertEquals("a\\u200Bb\\u202Ec\\u2028d\\uFEFF",
                InputValidator.sanitizeForLog("a\u200Bb\u202Ec\u2028d\uFEFF"));
    }

    @Test
    @DisplayName("sanitizeForLog caps the length")
    void sanitizeForLogCapsTheLength() {
        String sanitized = InputValidator.sanitizeForLog("x".repeat(5000));

        assertEquals(256, sanitized.length());
        assertTrue(sanitized.endsWith("..."));
    }

    @Test
    @DisplayName("sanitizeForLog marks null and empty input")
    void sanitizeForLogMarksMissingInput() {
        assertEquals("<null>", InputValidator.sanitizeForLog(null));
        assertEquals("<empty>", InputValidator.sanitizeForLog(""));
    }

    @Test
    @DisplayName("sanitize removes what must not be shown")
    void sanitizeRemovesWhatMustNotBeShown() {
        assertEquals("Alice", InputValidator.sanitize("Al\u200Bice\n"));
        assertEquals(null, InputValidator.sanitize(null));
    }
}
