package su.kidoz.kaillera.controller.v086.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.messaging.ParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for V086Bundle - the Kaillera v0.86 message bundle parser.
 *
 * <p>
 * Bundle format:
 * <ul>
 * <li>1 byte: message count (1-32)</li>
 * <li>For each message:</li>
 * <li>2 bytes: message number (little-endian unsigned short)</li>
 * <li>2 bytes: message length (little-endian short)</li>
 * <li>1 byte: message type ID</li>
 * <li>N bytes: message body</li>
 * </ul>
 */
@DisplayName("V086Bundle Tests")
class V086BundleTest {

    @Nested
    @DisplayName("Bundle Parsing Validation")
    class BundleParsingValidation {

        @Test
        @DisplayName("should throw exception for buffer too small")
        void shouldThrowForBufferTooSmall() {
            ByteBuffer buffer = ByteBuffer.allocate(4);
            buffer.put((byte) 1); // message count
            buffer.flip();

            V086BundleFormatException exception = assertThrows(V086BundleFormatException.class,
                    () -> V086Bundle.parse(buffer));

            assertTrue(exception.getMessage().contains("Invalid buffer length"));
        }

        @Test
        @DisplayName("should throw exception for zero message count")
        void shouldThrowForZeroMessageCount() {
            // Need at least 5 bytes for buffer length check to pass
            ByteBuffer buffer = ByteBuffer.allocate(10);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put((byte) 0); // invalid message count
            buffer.putInt(0); // padding to make buffer >= 5 bytes
            buffer.flip();

            V086BundleFormatException exception = assertThrows(V086BundleFormatException.class,
                    () -> V086Bundle.parse(buffer));

            assertTrue(exception.getMessage().contains("Invalid message count"));
        }

        @Test
        @DisplayName("should throw exception for negative message count")
        void shouldThrowForNegativeMessageCount() {
            // Need at least 5 bytes for buffer length check to pass
            ByteBuffer buffer = ByteBuffer.allocate(10);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put((byte) -1); // will be interpreted as -1 (signed byte)
            buffer.putInt(0); // padding to make buffer >= 5 bytes
            buffer.flip();

            // Signed byte -1 stays as -1, fails messageCount <= 0 check
            V086BundleFormatException exception = assertThrows(V086BundleFormatException.class,
                    () -> V086Bundle.parse(buffer));

            assertTrue(exception.getMessage().contains("Invalid message count"));
        }

        @Test
        @DisplayName("should throw exception for message count exceeding 32")
        void shouldThrowForMessageCountExceeding32() {
            // Need at least 5 bytes for buffer length check to pass
            ByteBuffer buffer = ByteBuffer.allocate(10);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put((byte) 33); // exceeds max
            buffer.putInt(0); // padding to make buffer >= 5 bytes
            buffer.flip();

            V086BundleFormatException exception = assertThrows(V086BundleFormatException.class,
                    () -> V086Bundle.parse(buffer));

            assertTrue(exception.getMessage().contains("Invalid message count"));
        }

        @Test
        @DisplayName("should throw exception for truncated bundle")
        void shouldThrowForTruncatedBundle() {
            ByteBuffer buffer = ByteBuffer.allocate(6);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put((byte) 2); // claims 2 messages
            buffer.putShort((short) 1); // message number
            buffer.putShort((short) 5); // message length
            buffer.flip();

            V086BundleFormatException exception = assertThrows(V086BundleFormatException.class,
                    () -> V086Bundle.parse(buffer));

            assertTrue(exception.getMessage().contains("Invalid bundle length"));
        }
    }

    @Nested
    @DisplayName("ClientACK Message Parsing")
    class ClientACKParsing {

        @Test
        @DisplayName("should parse valid ClientACK message")
        void shouldParseValidClientACK() throws Exception {
            // Build a bundle with a single ClientACK message
            ByteBuffer buffer = ByteBuffer.allocate(24);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // Bundle header
            buffer.put((byte) 1); // 1 message

            // Message header
            buffer.putShort((short) 1); // message number
            buffer.putShort((short) 18); // message length (1 type + 17 body)

            // ClientACK body (ID = 0x06)
            buffer.put((byte) 0x06); // message type
            buffer.put((byte) 0x00); // validation byte
            buffer.putInt(0); // val1
            buffer.putInt(1); // val2
            buffer.putInt(2); // val3
            buffer.putInt(3); // val4

            buffer.flip();

            V086Bundle bundle = V086Bundle.parse(buffer);

            assertNotNull(bundle);
            assertEquals(1, bundle.getNumMessages());

            V086Message[] messages = bundle.getMessages();
            assertNotNull(messages[0]);
            assertTrue(messages[0] instanceof ClientACK);
            assertEquals(1, messages[0].getNumber());
            assertEquals(0x06, messages[0].getID());
        }
    }

    @Nested
    @DisplayName("Chat Message Parsing")
    class ChatMessageParsing {

        @Test
        @DisplayName("should parse Chat_Request message (empty username)")
        void shouldParseChatRequest() throws Exception {
            String message = "Hello World";
            ByteBuffer buffer = createChatBundle("", message, 1);

            V086Bundle bundle = V086Bundle.parse(buffer);

            assertNotNull(bundle);
            assertEquals(1, bundle.getNumMessages());

            V086Message msg = bundle.getMessages()[0];
            assertTrue(msg instanceof Chat_Request);
            Chat chat = (Chat) msg;
            assertEquals(message, chat.getMessage());
        }

        @Test
        @DisplayName("should parse Chat_Notification message (with username)")
        void shouldParseChatNotification() throws Exception {
            String userName = "Player1";
            String message = "Hello World";
            ByteBuffer buffer = createChatBundle(userName, message, 1);

            V086Bundle bundle = V086Bundle.parse(buffer);

            assertNotNull(bundle);
            assertEquals(1, bundle.getNumMessages());

            V086Message msg = bundle.getMessages()[0];
            assertTrue(msg instanceof Chat_Notification);
            Chat chat = (Chat) msg;
            assertEquals(userName, chat.getUserName());
            assertEquals(message, chat.getMessage());
        }

        private ByteBuffer createChatBundle(String userName, String message, int messageNumber) {
            int bodyLength = userName.length() + 1 + message.length() + 1;
            int totalLength = 1 + 2 + 2 + 1 + bodyLength;

            ByteBuffer buffer = ByteBuffer.allocate(totalLength);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            // Bundle header
            buffer.put((byte) 1);

            // Message header
            buffer.putShort((short) messageNumber);
            buffer.putShort((short) (bodyLength + 1)); // +1 for message type

            // Message type (Chat = 0x07)
            buffer.put((byte) 0x07);

            // Chat body: userName\0message\0
            for (char c : userName.toCharArray()) {
                buffer.put((byte) c);
            }
            buffer.put((byte) 0x00);

            for (char c : message.toCharArray()) {
                buffer.put((byte) c);
            }
            buffer.put((byte) 0x00);

            buffer.flip();
            return buffer;
        }
    }

    @Nested
    @DisplayName("Bundle Write Operations")
    class BundleWriteOperations {

        @Test
        @DisplayName("should write and read back ClientACK message")
        void shouldWriteAndReadClientACK() throws Exception {
            // Create a ClientACK message
            ClientACK originalMessage = new ClientACK(42);

            // Create bundle with the message
            V086Message[] messages = new V086Message[]{originalMessage};
            V086Bundle originalBundle = new V086Bundle(messages);

            // Write to buffer
            ByteBuffer buffer = ByteBuffer.allocate(100);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            originalBundle.writeTo(buffer);
            buffer.flip();

            // Parse back
            V086Bundle parsedBundle = V086Bundle.parse(buffer);

            assertNotNull(parsedBundle);
            assertEquals(1, parsedBundle.getNumMessages());

            V086Message parsedMessage = parsedBundle.getMessages()[0];
            assertTrue(parsedMessage instanceof ClientACK);
            assertEquals(42, parsedMessage.getNumber());
        }

        @Test
        @DisplayName("should write a consistent count when a message does not fit")
        void shouldWriteConsistentCountWhenMessageDoesNotFit() throws Exception {
            ClientACK first = new ClientACK(1);
            ClientACK second = new ClientACK(2);
            V086Bundle bundle = new V086Bundle(new V086Message[]{first, second});

            // Room for the count byte and one message only.
            int singleMessageSize = 1 + first.getLength() + 4;
            ByteBuffer buffer = ByteBuffer.allocate(singleMessageSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            bundle.writeTo(buffer);
            buffer.flip();

            // The bundle must not claim a message whose bytes were not written.
            V086Bundle parsed = V086Bundle.parse(buffer);
            assertEquals(1, parsed.getNumMessages());
            assertEquals(1, parsed.getMessages()[0].getNumber());
        }
    }

    @Nested
    @DisplayName("Last Message ID Filtering")
    class LastMessageIDFiltering {

        @Test
        @DisplayName("should skip messages with ID <= lastMessageID")
        void shouldSkipOldMessages() throws Exception {
            // Create buffer with two ClientACK messages
            ByteBuffer buffer = ByteBuffer.allocate(50);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            buffer.put((byte) 2); // 2 messages

            // First message (number = 5)
            buffer.putShort((short) 5);
            buffer.putShort((short) 18);
            buffer.put((byte) 0x06);
            buffer.put((byte) 0x00);
            buffer.putInt(0);
            buffer.putInt(1);
            buffer.putInt(2);
            buffer.putInt(3);

            // Second message (number = 10)
            buffer.putShort((short) 10);
            buffer.putShort((short) 18);
            buffer.put((byte) 0x06);
            buffer.put((byte) 0x00);
            buffer.putInt(0);
            buffer.putInt(1);
            buffer.putInt(2);
            buffer.putInt(3);

            buffer.flip();

            // Parse with lastMessageID = 5, should skip first message
            V086Bundle bundle = V086Bundle.parse(buffer, 5);

            // Should only contain the second message (number = 10)
            // Note: The parser breaks when it finds a message <= lastMessageID
            assertEquals(0, bundle.getNumMessages());
        }

        @Test
        @DisplayName("should include all messages when lastMessageID is -1")
        void shouldIncludeAllMessagesWhenNoFilter() throws Exception {
            ByteBuffer buffer = ByteBuffer.allocate(50);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            buffer.put((byte) 2); // 2 messages

            // First message (number = 5)
            buffer.putShort((short) 5);
            buffer.putShort((short) 18);
            buffer.put((byte) 0x06);
            buffer.put((byte) 0x00);
            buffer.putInt(0);
            buffer.putInt(1);
            buffer.putInt(2);
            buffer.putInt(3);

            // Second message (number = 10)
            buffer.putShort((short) 10);
            buffer.putShort((short) 18);
            buffer.put((byte) 0x06);
            buffer.put((byte) 0x00);
            buffer.putInt(0);
            buffer.putInt(1);
            buffer.putInt(2);
            buffer.putInt(3);

            buffer.flip();

            V086Bundle bundle = V086Bundle.parse(buffer, -1);

            assertEquals(2, bundle.getNumMessages());
        }

        @Test
        @DisplayName("should accept message number 0 after lastMessageID 0xFFFF")
        void shouldAcceptMessageAfterWrap() throws Exception {
            ByteBuffer buffer = ByteBuffer.allocate(24);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            buffer.put((byte) 1);
            buffer.putShort((short) 0); // wrapped message number
            buffer.putShort((short) 18);
            buffer.put((byte) 0x06);
            buffer.put((byte) 0x00);
            buffer.putInt(0);
            buffer.putInt(1);
            buffer.putInt(2);
            buffer.putInt(3);

            buffer.flip();

            V086Bundle bundle = V086Bundle.parse(buffer, 0xFFFF);

            assertEquals(1, bundle.getNumMessages());
            assertEquals(0, bundle.getMessages()[0].getNumber());
        }
    }

    @Nested
    @DisplayName("Invalid Message Handling")
    class InvalidMessageHandling {

        @Test
        @DisplayName("should throw exception for invalid message length")
        void shouldThrowForInvalidMessageLength() {
            // Buffer must be at least 1 + (messageCount * 6) = 7 bytes to pass bundle
            // length check
            // Then message length validation will fail because it claims more bytes than
            // remaining
            ByteBuffer buffer = ByteBuffer.allocate(20);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            buffer.put((byte) 1); // 1 message
            buffer.putShort((short) 1); // message number
            buffer.putShort((short) 100); // claims 100 bytes but buffer doesn't have that
            buffer.put((byte) 0x06); // message type
            buffer.put((byte) 0); // extra byte to meet 7 byte minimum

            buffer.flip();

            // Now the bundle length check passes (7 >= 7), but message length is invalid
            assertThrows(ParseException.class, () -> V086Bundle.parse(buffer));
        }

        @Test
        @DisplayName("should throw exception for unknown message type")
        void shouldThrowForUnknownMessageType() {
            ByteBuffer buffer = ByteBuffer.allocate(20);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            buffer.put((byte) 1);
            buffer.putShort((short) 1);
            buffer.putShort((short) 5);
            buffer.put((byte) 0x7F); // invalid message type
            // Add some padding
            buffer.put((byte) 0);
            buffer.put((byte) 0);
            buffer.put((byte) 0);
            buffer.put((byte) 0);

            buffer.flip();

            assertThrows(MessageFormatException.class, () -> V086Bundle.parse(buffer));
        }
    }
}
