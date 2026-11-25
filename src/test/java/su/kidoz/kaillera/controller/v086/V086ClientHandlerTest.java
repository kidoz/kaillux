package su.kidoz.kaillera.controller.v086;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static su.kidoz.kaillera.controller.v086.V086ClientHandler.isLoginPhaseMessage;
import static su.kidoz.kaillera.controller.v086.V086ClientHandler.isNewerMessage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.v086.protocol.AllReady;
import su.kidoz.kaillera.controller.v086.protocol.CachedGameData;
import su.kidoz.kaillera.controller.v086.protocol.Chat_Request;
import su.kidoz.kaillera.controller.v086.protocol.ClientACK;
import su.kidoz.kaillera.controller.v086.protocol.CreateGame_Request;
import su.kidoz.kaillera.controller.v086.protocol.GameChat_Request;
import su.kidoz.kaillera.controller.v086.protocol.GameData;
import su.kidoz.kaillera.controller.v086.protocol.GameKick;
import su.kidoz.kaillera.controller.v086.protocol.JoinGame_Request;
import su.kidoz.kaillera.controller.v086.protocol.KeepAlive;
import su.kidoz.kaillera.controller.v086.protocol.PlayerDrop_Request;
import su.kidoz.kaillera.controller.v086.protocol.QuitGame_Request;
import su.kidoz.kaillera.controller.v086.protocol.Quit_Request;
import su.kidoz.kaillera.controller.v086.protocol.StartGame_Request;
import su.kidoz.kaillera.controller.v086.protocol.UserInformation;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;

/**
 * Unit tests for the message classification in V086ClientHandler.
 *
 * <p>
 * These call the handler's own static methods. Driving its receive loop needs a
 * bound port and the rest of the server; ProtocolE2ETest covers that.
 */
@DisplayName("V086ClientHandler Tests")
class V086ClientHandlerTest {

    @Nested
    @DisplayName("Message Number Logic")
    class MessageNumberLogic {

        @Test
        @DisplayName("should detect newer message with simple increment")
        void shouldDetectNewerMessageSimpleIncrement() {
            assertTrue(isNewerMessage(10, 5));
        }

        @Test
        @DisplayName("should detect older message")
        void shouldDetectOlderMessage() {
            assertFalse(isNewerMessage(5, 10));
        }

        @Test
        @DisplayName("should handle wrap-around at 16-bit boundary")
        void shouldHandleWrapAround() {
            // Difference: (5 - 0xFFFE) & 0xFFFF = 7, which is < 0x8000
            assertTrue(isNewerMessage(5, 0xFFFE));
        }

        @Test
        @DisplayName("should detect stale message after wrap")
        void shouldDetectStaleMessageAfterWrap() {
            assertFalse(isNewerMessage(0xFFFE, 5));
        }

        @Test
        @DisplayName("should treat half the number space ahead as too far to be newer")
        void shouldTreatHalfTheNumberSpaceAsNotNewer() {
            assertFalse(isNewerMessage(0x8000, 0));
            assertTrue(isNewerMessage(0x7FFF, 0));
        }

        @Test
        @DisplayName("should handle same message number")
        void shouldHandleSameMessageNumber() {
            assertFalse(isNewerMessage(100, 100));
        }

        @Test
        @DisplayName("should handle initial message (last = -1)")
        void shouldHandleInitialMessage() {
            assertTrue(isNewerMessage(0, -1));
            assertTrue(isNewerMessage(1000, -1));
        }

        @Test
        @DisplayName("should handle message number zero")
        void shouldHandleMessageNumberZero() {
            assertTrue(isNewerMessage(0, 0xFFFF));
        }
    }

    @Nested
    @DisplayName("Login phase gate")
    class LoginPhaseGate {

        @Test
        @DisplayName("should let the login handshake and leaving through")
        void shouldLetTheLoginHandshakeAndLeavingThrough() throws MessageFormatException {
            assertTrue(isLoginPhaseMessage(new UserInformation(1, "name", "client", (byte) 1)));
            assertTrue(isLoginPhaseMessage(new ClientACK(1)));
            assertTrue(isLoginPhaseMessage(new Quit_Request(1, "bye")));
        }

        @Test
        @DisplayName("should stop every other client command before login")
        void shouldStopEveryOtherClientCommandBeforeLogin() throws MessageFormatException {
            V086Message[] commands = {new Chat_Request(1, "hi"), new CreateGame_Request(1, "rom"),
                    new JoinGame_Request(1, 1, (byte) 1), new KeepAlive(1), new QuitGame_Request(1),
                    new StartGame_Request(1), new GameChat_Request(1, "hi"), new GameKick(1, 2),
                    new AllReady(1), new CachedGameData(1, 0), new GameData(1, new byte[]{1}),
                    new PlayerDrop_Request(1),};

            for (V086Message command : commands) {
                assertFalse(isLoginPhaseMessage(command),
                        command.getClass().getSimpleName() + " must wait for the login");
            }
        }
    }
}
