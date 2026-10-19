package su.kidoz.kaillera.controller.v086.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.ByteBuffer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import su.kidoz.kaillera.controller.messaging.ByteBufferMessage;

@DisplayName("GameChat Tests")
class GameChatTest {

    @Test
    @DisplayName("body length should count encoded bytes, not UTF-16 characters")
    void bodyLengthShouldCountEncodedBytes() throws Exception {
        String userName = "üser";
        String message = "héllo €";

        GameChat_Notification chat = new GameChat_Notification(1, userName, message);

        int expected = userName.getBytes(ByteBufferMessage.charset).length
                + message.getBytes(ByteBufferMessage.charset).length + 2;
        assertEquals(expected, chat.getBodyLength());

        ByteBuffer buffer = ByteBuffer.allocate(chat.getLength() + 4);
        chat.writeTo(buffer);

        assertEquals(chat.getLength() + 4, buffer.position());
    }
}
