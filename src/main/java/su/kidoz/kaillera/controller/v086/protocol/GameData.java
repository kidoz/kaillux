package su.kidoz.kaillera.controller.v086.protocol;

import java.nio.ByteBuffer;

import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.messaging.ParseException;
import su.kidoz.util.EmuUtil;
import su.kidoz.util.UnsignedUtil;

/**
 * Represents a game data message containing player input for one or more
 * frames.
 *
 * <p>
 * This is the primary message used during active gameplay to synchronize input
 * across all players. Each player sends their controller input data, and the
 * server broadcasts all inputs to maintain frame-perfect synchronization
 * between emulators.
 *
 * <h2>Message Format (ID: 0x12)</h2>
 *
 * <pre>
 * +------------+-------------+------------------+
 * | Validation | Data Length |     Game Data    |
 * |  (1 byte)  |  (2 bytes)  |    (variable)    |
 * +------------+-------------+------------------+
 * </pre>
 *
 * <ul>
 * <li><b>Validation byte</b>: Always 0x00</li>
 * <li><b>Data Length</b>: 16-bit unsigned length of game data
 * (little-endian)</li>
 * <li><b>Game Data</b>: Raw input bytes (button states, analog values,
 * etc.)</li>
 * </ul>
 *
 * <h2>Data Size</h2>
 * <p>
 * The game data size depends on the connection type and number of actions per
 * message:
 * <ul>
 * <li>Connection type 1 (LAN): 1 action per message</li>
 * <li>Connection type 2 (Excellent): 2 actions per message</li>
 * <li>Connection type 3 (Good): 3 actions per message</li>
 * <li>Higher types: More actions for higher latency connections</li>
 * </ul>
 *
 * <h2>Direction</h2>
 * <p>
 * Client → Server → All Players (broadcast)
 *
 * @see CachedGameData
 * @see su.kidoz.kaillera.controller.v086.command.GameDataCommandAction
 */
public class GameData extends V086Message {
    public static final byte ID = 0x12;
    public static final String DESC = "Game Data";

    private byte[] gameData;

    public GameData(int messageNumber, byte[] gameData) throws MessageFormatException {
        super(messageNumber);

        if (gameData.length <= 0 || gameData.length > 0xFFFF)
            throw new MessageFormatException("Invalid " + getDescription()
                    + " format: gameData.remaining() = " + gameData.length);

        this.gameData = gameData.clone();
    }

    public byte getID() {
        return ID;
    }

    public String getDescription() {
        return DESC;
    }

    public byte[] getGameData() {
        return gameData.clone();
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(getInfoString());
        sb.append("[gameData=");
        sb.append(EmuUtil.arrayToString(gameData, ','));
        sb.append("]");
        return sb.toString();
    }

    public int getBodyLength() {
        return (gameData.length + 3);
    }

    public void writeBodyTo(ByteBuffer buffer) {
        buffer.put((byte) 0x00);
        UnsignedUtil.putUnsignedShort(buffer, gameData.length);
        buffer.put(gameData);
    }

    public static GameData parse(int messageNumber, ByteBuffer buffer)
            throws ParseException, MessageFormatException {
        if (buffer.remaining() < 4)
            throw new ParseException("Failed byte count validation!");

        buffer.get(); // Skip validation byte (removed to increase speed)
        // if (b != 0x00)
        // throw new MessageFormatException("Invalid " + DESC + " format: byte 0 = " +
        // EmuUtil.byteToHex(b));

        int dataSize = UnsignedUtil.getUnsignedShort(buffer);
        if (dataSize <= 0 || dataSize > buffer.remaining())
            throw new MessageFormatException("Invalid " + DESC + " format: dataSize = " + dataSize);

        byte[] gameData = new byte[dataSize];
        buffer.get(gameData);

        return new GameData(messageNumber, gameData);
    }
}
