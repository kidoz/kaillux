package su.kidoz.kaillera.controller.v086.protocol;

import java.nio.ByteBuffer;

import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.messaging.ParseException;
import su.kidoz.util.EmuUtil;

public abstract class GameChat extends V086Message {
    public static final byte ID = 0x08;

    private String userName;
    private String message;

    public GameChat(int messageNumber, String userName, String message)
            throws MessageFormatException {
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
        // Lengths are byte counts: writeBodyTo() writes the strings in the wire
        // charset, so counting UTF-16 chars under-reports multi-byte characters and
        // lets the message overflow the output buffer.
        return (userName.getBytes(charset).length + message.getBytes(charset).length + 2);
    }

    public void writeBodyTo(ByteBuffer buffer) {
        EmuUtil.writeString(buffer, userName, 0x00, charset);
        EmuUtil.writeString(buffer, message, 0x00, charset);
    }

    public static GameChat parse(int messageNumber, ByteBuffer buffer)
            throws ParseException, MessageFormatException {
        if (buffer.remaining() < 3)
            throw new ParseException("Failed byte count validation!");

        String userName = EmuUtil.readString(buffer, 0x00, charset);

        if (buffer.remaining() < 2)
            throw new ParseException("Failed byte count validation!");

        String message = EmuUtil.readString(buffer, 0x00, charset);

        if (userName.isEmpty())
            return new GameChat_Request(messageNumber, message);
        else
            return new GameChat_Notification(messageNumber, userName, message);
    }
}
