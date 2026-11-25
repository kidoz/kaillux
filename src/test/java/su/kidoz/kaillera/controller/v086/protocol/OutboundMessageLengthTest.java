package su.kidoz.kaillera.controller.v086.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import su.kidoz.kaillera.controller.messaging.MessageFormatException;

/**
 * Checks that outbound messages describe themselves in encoded bytes.
 *
 * <p>
 * The length field goes on the wire and decides how much buffer space a message
 * needs. Counting characters instead under-reports multi-byte text, which put a
 * wrong length on the wire and let a resend overflow the output buffer.
 */
@DisplayName("Outbound message lengths")
class OutboundMessageLengthTest {

    /** Three bytes in UTF-8 for every character. */
    private static final String CJK = "\u4e2d";

    private static final int BUNDLE_HEADER_BYTES = 4;

    /** Every outbound message class that carries text. */
    private static Stream<V086Message> messages() throws MessageFormatException {
        String name = CJK.repeat(10);
        String text = CJK.repeat(40);
        return Stream.of(new Chat_Notification(1, name, text),
                new GameChat_Notification(2, name, text), new Quit_Notification(3, name, 7, text),
                new ConnectionRejected(4, name, 7, text), new InformationMessage(5, "server", text),
                new CreateGame_Notification(6, name, text, "client" + CJK, 3, 0),
                new JoinGame_Notification(7, 3, 0, name, 20, 7, (byte) 1),
                new PlayerDrop_Notification(8, name, (byte) 2),
                new QuitGame_Notification(9, name, 7), new UserJoined(10, name, 7, 20, (byte) 1),
                new PlayerInformation(11,
                        List.of(new PlayerInformation.Player(name, 20, 7, (byte) 1))),
                new ServerStatus(12,
                        List.of(new ServerStatus.User(name, 20, (byte) 1, 7, (byte) 1)),
                        List.of(new ServerStatus.Game(text, 3, "client" + CJK, name, "1/2",
                                (byte) 0))));
    }

    @Test
    @DisplayName("the length field equals the bytes written, for non-ASCII text")
    void theLengthFieldEqualsTheBytesWritten() throws Exception {
        List<V086Message> messages = messages().toList();

        for (V086Message message : messages) {
            ByteBuffer buffer = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);

            message.writeTo(buffer);

            String type = message.getClass().getSimpleName();
            assertEquals(message.getLength() + BUNDLE_HEADER_BYTES, buffer.position(),
                    type + " must write exactly the bytes it reports");
            assertEquals(buffer.position() - BUNDLE_HEADER_BYTES, declaredLength(buffer),
                    type + " must declare its encoded byte count on the wire");
        }
    }

    @Test
    @DisplayName("a bundle never claims more messages than it writes")
    void aBundleNeverClaimsMoreMessagesThanItWrites() throws Exception {
        // Three of these fit in the production buffer of 2048 bytes, five do not.
        V086Message[] five = new V086Message[5];
        for (int i = 0; i < five.length; i++) {
            five[i] = new Chat_Notification(i + 1, CJK.repeat(45), CJK.repeat(150));
        }
        ByteBuffer buffer = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);

        new V086Bundle(five).writeTo(buffer);
        buffer.flip();

        int claimed = Byte.toUnsignedInt(buffer.get(0));
        assertTrue(claimed >= 1 && claimed < five.length,
                "the bundle must hold only the messages that fit, but claims " + claimed);

        V086Bundle parsed = V086Bundle.parse(buffer, -1);
        assertEquals(claimed, parsed.getNumMessages(),
                "the count on the wire must match what was written");
    }

    @Test
    @DisplayName("a message that cannot fit is left out instead of overflowing")
    void aMessageThatCannotFitIsLeftOutInsteadOfOverflowing() throws MessageFormatException {
        V086Message tooLarge = new GameData(1, new byte[3000]);
        ByteBuffer buffer = ByteBuffer.allocate(2048).order(ByteOrder.LITTLE_ENDIAN);

        new V086Bundle(new V086Message[]{tooLarge}).writeTo(buffer);
        buffer.flip();

        assertEquals(1, buffer.remaining(), "only the count byte is written");
        assertEquals(0, Byte.toUnsignedInt(buffer.get(0)), "the count says: no messages");
    }

    private static int declaredLength(ByteBuffer buffer) {
        return (buffer.get(2) & 0xFF) | ((buffer.get(3) & 0xFF) << 8);
    }
}
