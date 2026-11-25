package su.kidoz.kaillera.controller.v086.protocol;

import java.nio.ByteBuffer;

import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.messaging.ParseException;
import su.kidoz.util.EmuUtil;

/**
 * Represents a chat message in the server lobby.
 *
 * <p>
 * Chat messages allow users to communicate in the main server lobby (outside of
 * games). This is an abstract class with two concrete implementations:
 * <ul>
 * <li>{@link Chat_Request} - Client sending a chat message (userName is
 * empty)</li>
 * <li>{@link Chat_Notification} - Server broadcasting a chat to all users</li>
 * </ul>
 *
 * <h2>Message Format (ID: 0x07)</h2>
 *
 * <pre>
 * +------------------+------------------+
 * |    User Name     |     Message      |
 * | (null-terminated)| (null-terminated)|
 * +------------------+------------------+
 * </pre>
 *
 * <ul>
 * <li><b>User Name</b>: Sender's name (empty for requests, filled for
 * notifications)</li>
 * <li><b>Message</b>: The chat message text, null-terminated</li>
 * </ul>
 *
 * <h2>Character Encoding</h2>
 * <p>
 * Uses ISO-8859-1 (Latin-1) encoding for compatibility with original Kaillera
 * clients.
 *
 * <h2>Direction</h2>
 * <ul>
 * <li><b>Request</b>: Client → Server (userName empty)</li>
 * <li><b>Notification</b>: Server → All Clients (userName filled)</li>
 * </ul>
 *
 * @see Chat_Request
 * @see Chat_Notification
 * @see GameChat
 */
public abstract class Chat extends V086Message {
    public static final byte ID = 0x07;

    private String userName;
    private String message;

    public Chat(int messageNumber, String userName, String message) throws MessageFormatException {
        super(messageNumber);

        this.userName = userName;
        this.message = message;
    }

    public byte getID() {
        return ID;
    }

    public abstract String getDescription();

    public String getUserName() {
        return userName;
    }

    public String getMessage() {
        return message;
    }

    public abstract String toString();

    public int getBodyLength() {
        return (encodedLength(userName) + encodedLength(message) + 2);
    }

    public void writeBodyTo(ByteBuffer buffer) {
        EmuUtil.writeString(buffer, userName, 0x00, charset);
        EmuUtil.writeString(buffer, message, 0x00, charset);
    }

    public static Chat parse(int messageNumber, ByteBuffer buffer)
            throws ParseException, MessageFormatException {
        if (buffer.remaining() < 3)
            throw new ParseException("Failed byte count validation!");

        String userName = EmuUtil.readString(buffer, 0x00, charset);

        if (buffer.remaining() < 2)
            throw new ParseException("Failed byte count validation!");

        String message = EmuUtil.readString(buffer, 0x00, charset);

        if (userName.isEmpty())
            return new Chat_Request(messageNumber, message);
        else
            return new Chat_Notification(messageNumber, userName, message);
    }
}
