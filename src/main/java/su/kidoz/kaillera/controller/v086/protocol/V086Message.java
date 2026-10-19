package su.kidoz.kaillera.controller.v086.protocol;

import java.nio.ByteBuffer;

import su.kidoz.kaillera.controller.messaging.ByteBufferMessage;
import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.messaging.ParseException;
import su.kidoz.util.EmuUtil;
import su.kidoz.util.UnsignedUtil;

/**
 * Abstract base class for all Kaillera V086 protocol messages.
 *
 * <p>
 * The V086 protocol is the primary communication protocol used by Kaillera
 * clients and servers for multiplayer emulator gaming over UDP. Each message
 * has a header containing a sequence number, length, and message type ID,
 * followed by a type-specific body.
 *
 * <h2>Message Structure</h2>
 *
 * <pre>
 * +----------------+----------------+--------+-----------------+
 * | Message Number | Message Length | Msg ID |   Message Body  |
 * |   (2 bytes)    |   (2 bytes)    |(1 byte)|   (variable)    |
 * +----------------+----------------+--------+-----------------+
 * </pre>
 *
 * <ul>
 * <li><b>Message Number</b>: 16-bit unsigned sequence number (0x0000-0xFFFF),
 * used for ordering and deduplication</li>
 * <li><b>Message Length</b>: 16-bit unsigned length of the message body plus
 * the message ID byte</li>
 * <li><b>Message ID</b>: Single byte identifying the message type
 * (0x01-0x17)</li>
 * <li><b>Message Body</b>: Type-specific payload, varies by message type</li>
 * </ul>
 *
 * <h2>Byte Ordering</h2>
 * <p>
 * All multi-byte integer values use <b>little-endian</b> byte ordering.
 *
 * <h2>Message Types</h2>
 * <p>
 * The protocol defines 23 message types (IDs 0x01-0x17):
 * <table>
 * <tr>
 * <th>ID</th>
 * <th>Message</th>
 * <th>Description</th>
 * </tr>
 * <tr>
 * <td>0x01</td>
 * <td>{@link Quit}</td>
 * <td>User quit notification</td>
 * </tr>
 * <tr>
 * <td>0x02</td>
 * <td>{@link UserJoined}</td>
 * <td>User joined server</td>
 * </tr>
 * <tr>
 * <td>0x03</td>
 * <td>{@link UserInformation}</td>
 * <td>User info update</td>
 * </tr>
 * <tr>
 * <td>0x04</td>
 * <td>{@link ServerStatus}</td>
 * <td>Server status broadcast</td>
 * </tr>
 * <tr>
 * <td>0x05</td>
 * <td>{@link ServerACK}</td>
 * <td>Server acknowledgment</td>
 * </tr>
 * <tr>
 * <td>0x06</td>
 * <td>{@link ClientACK}</td>
 * <td>Client acknowledgment</td>
 * </tr>
 * <tr>
 * <td>0x07</td>
 * <td>{@link Chat}</td>
 * <td>Chat message</td>
 * </tr>
 * <tr>
 * <td>0x08</td>
 * <td>{@link GameChat}</td>
 * <td>In-game chat message</td>
 * </tr>
 * <tr>
 * <td>0x09</td>
 * <td>{@link KeepAlive}</td>
 * <td>Keep-alive ping</td>
 * </tr>
 * <tr>
 * <td>0x0A</td>
 * <td>{@link CreateGame}</td>
 * <td>Game creation</td>
 * </tr>
 * <tr>
 * <td>0x0B</td>
 * <td>{@link QuitGame}</td>
 * <td>Player quit game</td>
 * </tr>
 * <tr>
 * <td>0x0C</td>
 * <td>{@link JoinGame}</td>
 * <td>Player join game</td>
 * </tr>
 * <tr>
 * <td>0x0D</td>
 * <td>{@link PlayerInformation}</td>
 * <td>Player info</td>
 * </tr>
 * <tr>
 * <td>0x0E</td>
 * <td>{@link GameStatus}</td>
 * <td>Game status update</td>
 * </tr>
 * <tr>
 * <td>0x0F</td>
 * <td>{@link GameKick}</td>
 * <td>Player kicked from game</td>
 * </tr>
 * <tr>
 * <td>0x10</td>
 * <td>{@link CloseGame}</td>
 * <td>Game closed</td>
 * </tr>
 * <tr>
 * <td>0x11</td>
 * <td>{@link StartGame}</td>
 * <td>Game started</td>
 * </tr>
 * <tr>
 * <td>0x12</td>
 * <td>{@link GameData}</td>
 * <td>Input frame data</td>
 * </tr>
 * <tr>
 * <td>0x13</td>
 * <td>{@link CachedGameData}</td>
 * <td>Cached input data</td>
 * </tr>
 * <tr>
 * <td>0x14</td>
 * <td>{@link PlayerDrop}</td>
 * <td>Player packet loss</td>
 * </tr>
 * <tr>
 * <td>0x15</td>
 * <td>{@link AllReady}</td>
 * <td>All players ready</td>
 * </tr>
 * <tr>
 * <td>0x16</td>
 * <td>{@link ConnectionRejected}</td>
 * <td>Connection rejected</td>
 * </tr>
 * <tr>
 * <td>0x17</td>
 * <td>{@link InformationMessage}</td>
 * <td>Server info message</td>
 * </tr>
 * </table>
 *
 * <h2>Message Bundling</h2>
 * <p>
 * Multiple messages can be bundled into a single UDP packet using
 * {@link V086Bundle}. Bundles contain a 1-byte header with the message count
 * (1-32), followed by the concatenated messages.
 *
 * @see V086Bundle
 * @see ByteBufferMessage
 */
public abstract class V086Message extends ByteBufferMessage {
    protected int number;
    protected byte messageType;

    protected V086Message(int number) throws MessageFormatException {
        if (number < 0 || number > 0xFFFF)
            throw new MessageFormatException(
                    "Invalid " + getDescription() + " format: Invalid message number: " + number);

        if (messageType < 0 || messageType > 0x17)
            throw new MessageFormatException("Invalid " + getDescription()
                    + " format: Invalid message type: " + messageType);

        this.number = number;
    }

    public int getNumber() {
        return number;
    }

    public abstract byte getID();

    public abstract String getDescription();

    public int getLength() {
        return (getBodyLength() + 1);
        // return (getBodyLength() + 5);
    }

    public abstract int getBodyLength();

    protected String getInfoString() {
        return (getNumber() + ":" + EmuUtil.byteToHex(getID()) + "/" + getDescription());
    }

    public void writeTo(ByteBuffer buffer) {
        int len = getLength();
        // getLength() covers the body plus the message ID; the wire format also
        // prefixes a 4-byte number/length header.
        int required = len + 4;
        if (required > buffer.remaining()) {
            log.warn(
                    "Ran out of output buffer space, consider increasing the controllers.v086.bufferSize setting!");
        } else {
            UnsignedUtil.putUnsignedShort(buffer, getNumber());
            // there no realistic reason to use unsigned here since a single packet can't be
            // that large
            buffer.mark();
            UnsignedUtil.putUnsignedShort(buffer, len);
            // buffer.putShort((short)getLength());
            buffer.put(getID());
            writeBodyTo(buffer);
        }
    }

    protected abstract void writeBodyTo(ByteBuffer buffer);

    public static V086Message parse(int messageNumber, int messageLength, ByteBuffer buffer)
            throws ParseException, MessageFormatException {
        byte messageType = buffer.get();

        // removed to increase speed
        // if (messageType < 0 || messageType > 0x17)
        // throw new MessageFormatException("Invalid message type: " + messageType);

        V086Message message = null;
        switch (messageType) {
            case Quit.ID : // 01
                message = Quit.parse(messageNumber, buffer);
                break;

            case UserJoined.ID : // 02
                message = UserJoined.parse(messageNumber, buffer);
                break;

            case UserInformation.ID : // 03
                message = UserInformation.parse(messageNumber, buffer);
                break;

            case ServerStatus.ID : // 04
                message = ServerStatus.parse(messageNumber, buffer);
                break;

            case ServerACK.ID : // 05
                message = ServerACK.parse(messageNumber, buffer);
                break;

            case ClientACK.ID : // 06
                message = ClientACK.parse(messageNumber, buffer);
                break;

            case Chat.ID : // 07
                message = Chat.parse(messageNumber, buffer);
                break;

            case GameChat.ID : // 08
                message = GameChat.parse(messageNumber, buffer);
                break;

            case KeepAlive.ID : // 09
                message = KeepAlive.parse(messageNumber, buffer);
                break;

            case CreateGame.ID : // 0A
                message = CreateGame.parse(messageNumber, buffer);
                break;

            case QuitGame.ID : // 0B
                message = QuitGame.parse(messageNumber, buffer);
                break;

            case JoinGame.ID : // 0C
                message = JoinGame.parse(messageNumber, buffer);
                break;

            case PlayerInformation.ID : // 0D
                message = PlayerInformation.parse(messageNumber, buffer);
                break;

            case GameStatus.ID : // 0E
                message = GameStatus.parse(messageNumber, buffer);
                break;

            case GameKick.ID : // 0F
                message = GameKick.parse(messageNumber, buffer);
                break;

            case CloseGame.ID : // 10
                message = CloseGame.parse(messageNumber, buffer);
                break;

            case StartGame.ID : // 11
                message = StartGame.parse(messageNumber, buffer);
                break;

            case GameData.ID : // 12
                message = GameData.parse(messageNumber, buffer);
                break;

            case CachedGameData.ID : // 13
                message = CachedGameData.parse(messageNumber, buffer);
                break;

            case PlayerDrop.ID : // 14
                message = PlayerDrop.parse(messageNumber, buffer);
                break;

            case AllReady.ID : // 15
                message = AllReady.parse(messageNumber, buffer);
                break;

            case ConnectionRejected.ID : // 16
                message = ConnectionRejected.parse(messageNumber, buffer);
                break;

            case InformationMessage.ID : // 17
                message = InformationMessage.parse(messageNumber, buffer);
                break;

            default :
                throw new MessageFormatException("Invalid message type: " + messageType);
        }

        // removed to improve speed
        if (message.getLength() != messageLength)
            // throw new ParseException("Bundle contained length " + messageLength + " !=
            // parsed length " + message.getLength());
            log.debug("Bundle contained length " + messageLength + " !=  parsed length "
                    + message.getLength());

        return message;
    }
}
