package su.kidoz.kaillera.controller.connectcontroller.protocol;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;

import su.kidoz.kaillera.controller.messaging.ByteBufferMessage;
import su.kidoz.kaillera.controller.messaging.MessageFormatException;

public abstract class ConnectMessage extends ByteBufferMessage {
    public static final Charset charset = Charset.forName("ISO-8859-1");

    protected abstract String getID();

    public static ConnectMessage parse(ByteBuffer buffer) throws MessageFormatException {
        String messageStr = null;

        try {
            CharsetDecoder stringDecoder = charset.newDecoder();
            messageStr = stringDecoder.decode(buffer).toString();
        } catch (CharacterCodingException e) {
            throw new MessageFormatException(
                    "Invalid bytes received: failed to decode to a string!", e);
        }

        if (messageStr.startsWith(ConnectMessage_TOO.ID)) {
            return ConnectMessage_TOO.parse(messageStr);
        } else if (messageStr.startsWith(ConnectMessage_HELLOD00D.ID)) {
            return ConnectMessage_HELLOD00D.parse(messageStr);
        } else if (messageStr.startsWith(ConnectMessage_HELLO.ID)) {
            return ConnectMessage_HELLO.parse(messageStr);
        } else if (messageStr.startsWith(ConnectMessage_PING.ID)) {
            return ConnectMessage_PING.parse(messageStr);
        } else if (messageStr.startsWith(ConnectMessage_PONG.ID)) {
            return ConnectMessage_PONG.parse(messageStr);
        }

        buffer.rewind();
        // The bytes are left out of the message: any client can trigger this, and
        // dumping them would multiply the cost of a junk datagram.
        throw new MessageFormatException(
                "Unrecognized connect message: " + buffer.remaining() + " bytes");
    }
}
