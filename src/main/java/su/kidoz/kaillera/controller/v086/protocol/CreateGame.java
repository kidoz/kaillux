package su.kidoz.kaillera.controller.v086.protocol;

import java.nio.ByteBuffer;

import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.messaging.ParseException;
import su.kidoz.util.EmuUtil;
import su.kidoz.util.UnsignedUtil;

/**
 * Represents a game creation message.
 *
 * <p>
 * Used when a user creates a new game room on the server. This is an abstract
 * class with two concrete implementations:
 * <ul>
 * <li>{@link CreateGame_Request} - Client requesting to create a game</li>
 * <li>{@link CreateGame_Notification} - Server broadcasting game creation to
 * all users</li>
 * </ul>
 *
 * <h2>Message Format (ID: 0x0A)</h2>
 *
 * <pre>
 * +------------------+------------------+------------------+---------+-------+
 * |    User Name     |     ROM Name     |   Client Type    | Game ID | Val1  |
 * | (null-terminated)| (null-terminated)| (null-terminated)|(2 bytes)|(2 bytes)|
 * +------------------+------------------+------------------+---------+-------+
 * </pre>
 *
 * <ul>
 * <li><b>User Name</b>: Creator's name (empty for requests)</li>
 * <li><b>ROM Name</b>: Name of the game/ROM being played</li>
 * <li><b>Client Type</b>: Emulator identifier (e.g., "MAME", "Project64")</li>
 * <li><b>Game ID</b>: Unique game identifier (0xFFFF for requests)</li>
 * <li><b>Val1</b>: Status flag (0x0000 or 0xFFFF)</li>
 * </ul>
 *
 * <h2>Request vs Notification</h2>
 * <p>
 * Requests are identified by empty userName and gameID/val1 both being 0xFFFF.
 * Notifications contain the full game information including the assigned game
 * ID.
 *
 * <h2>Direction</h2>
 * <ul>
 * <li><b>Request</b>: Client → Server</li>
 * <li><b>Notification</b>: Server → All Clients</li>
 * </ul>
 *
 * @see CreateGame_Request
 * @see CreateGame_Notification
 * @see JoinGame
 * @see CloseGame
 */
public abstract class CreateGame extends V086Message {
    public static final byte ID = 0x0A;

    private String userName;
    private String romName;
    private String clientType;
    private int gameID;
    private int val1;

    public CreateGame(int messageNumber, String userName, String romName, String clientType,
            int gameID, int val1) throws MessageFormatException {
        super(messageNumber);

        if (romName.isEmpty())
            throw new MessageFormatException(
                    "Invalid " + getDescription() + " format: romName.length == 0");

        if (gameID < 0 || gameID > 0xFFFF)
            throw new MessageFormatException("Invalid " + getDescription()
                    + " format: gameID out of acceptable range: " + gameID);

        if (val1 != 0x0000 && val1 != 0xFFFF)
            throw new MessageFormatException("Invalid " + getDescription()
                    + " format: val1 out of acceptable range: " + val1);

        this.userName = userName;
        this.romName = romName;
        this.clientType = clientType;
        this.gameID = gameID;
        this.val1 = val1;
    }

    public byte getID() {
        return ID;
    }

    public abstract String getDescription();

    public String getUserName() {
        return userName;
    }

    public String getRomName() {
        return romName;
    }

    public String getClientType() {
        return clientType;
    }

    public int getGameID() {
        return gameID;
    }

    public int getVal1() {
        return val1;
    }

    public abstract String toString();

    public int getBodyLength() {
        return (encodedLength(userName) + encodedLength(romName) + encodedLength(clientType) + 7);
    }

    public void writeBodyTo(ByteBuffer buffer) {
        EmuUtil.writeString(buffer, userName, 0x00, charset);
        EmuUtil.writeString(buffer, romName, 0x00, charset);
        EmuUtil.writeString(buffer, clientType, 0x00, charset);
        UnsignedUtil.putUnsignedShort(buffer, gameID);
        UnsignedUtil.putUnsignedShort(buffer, val1);
    }

    public static CreateGame parse(int messageNumber, ByteBuffer buffer)
            throws ParseException, MessageFormatException {
        if (buffer.remaining() < 7)
            throw new ParseException("Failed byte count validation!");

        String userName = EmuUtil.readString(buffer, 0x00, charset);

        if (buffer.remaining() < 6)
            throw new ParseException("Failed byte count validation!");

        String romName = EmuUtil.readString(buffer, 0x00, charset);

        if (buffer.remaining() < 5)
            throw new ParseException("Failed byte count validation!");

        String clientType = EmuUtil.readString(buffer, 0x00, charset);

        if (buffer.remaining() < 4)
            throw new ParseException("Failed byte count validation!");

        int gameID = UnsignedUtil.getUnsignedShort(buffer);
        int val1 = UnsignedUtil.getUnsignedShort(buffer);

        if (userName.isEmpty() && gameID == 0xFFFF && val1 == 0xFFFF)
            return new CreateGame_Request(messageNumber, romName);
        else
            return new CreateGame_Notification(messageNumber, userName, romName, clientType, gameID,
                    val1);
    }
}
