package su.kidoz.kaillera.controller.v086.protocol;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import su.kidoz.kaillera.controller.messaging.ByteBufferMessage;
import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.messaging.ParseException;
import su.kidoz.util.EmuUtil;
import su.kidoz.util.UnsignedUtil;

/**
 * Container for multiple V086 protocol messages in a single UDP packet.
 *
 * <p>
 * Message bundling is used to improve efficiency by packing multiple protocol
 * messages into a single UDP datagram, reducing network overhead. This is
 * particularly important for game data messages which are sent at high
 * frequency during gameplay.
 *
 * <h2>Bundle Format</h2>
 *
 * <pre>
 * +---------------+------------+------------+-----+------------+
 * | Message Count | Message 1  | Message 2  | ... | Message N  |
 * |   (1 byte)    | (variable) | (variable) |     | (variable) |
 * +---------------+------------+------------+-----+------------+
 * </pre>
 *
 * <ul>
 * <li><b>Message Count</b>: Number of messages in the bundle (1-32)</li>
 * <li><b>Messages</b>: Concatenated {@link V086Message} instances, each with
 * their own header (number, length, ID) and body</li>
 * </ul>
 *
 * <h2>Constraints</h2>
 * <ul>
 * <li>Minimum buffer size: 5 bytes (1 count + 4 bytes for smallest message
 * header)</li>
 * <li>Maximum messages per bundle: 32</li>
 * <li>Each message requires at least 6 bytes (2 number + 2 length + 1 ID + 1
 * body)</li>
 * </ul>
 *
 * <h2>Message Filtering</h2>
 * <p>
 * When parsing, a {@code lastMessageID} can be specified to filter out
 * already-processed messages. Messages with numbers less than or equal to this
 * value are skipped for efficiency.
 *
 * @see V086Message
 */
public class V086Bundle extends ByteBufferMessage {
    public static final String DESC = "Kaillera v.086 Message Bundle";

    /** Minimum buffer length required to parse a bundle header */
    private static final int MIN_BUFFER_LENGTH = 5;

    /** Maximum number of messages allowed per bundle */
    private static final int MAX_MESSAGE_COUNT = 32;

    /** Minimum bytes per message header (message number + length) */
    private static final int MESSAGE_HEADER_SIZE = 6;

    protected V086Message[] messages;
    protected int numToWrite;
    protected int length = -1;

    public V086Bundle(V086Message[] messages) {
        this(messages, Integer.MAX_VALUE);
    }

    public V086Bundle(V086Message[] messages, int numToWrite) {
        this.numToWrite = messages.length;
        if (numToWrite < this.numToWrite)
            this.numToWrite = numToWrite;

        this.messages = messages;
    }

    public String getDescription() {
        return DESC;
    }

    public int getNumMessages() {
        return numToWrite;
    }

    public V086Message[] getMessages() {
        return messages;
    }

    public int getLength() {
        if (length == -1) {
            length = 1; // Bundle count byte, followed by the messages
            for (int i = 0; i < numToWrite; i++) {
                if (messages[i] == null) {
                    break;
                }
                // Include the per-message number/length header.
                length += messages[i].getLength() + 4;
            }
        }
        return length;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(DESC + " (" + numToWrite + " messages) (" + getLength() + " bytes)");
        sb.append(EmuUtil.LB);
        for (int i = 0; i < numToWrite; i++) {
            if (messages[i] == null)
                break;

            sb.append("\tMessage " + (i + 1) + ": " + messages[i].toString() + EmuUtil.LB);
        }
        return sb.toString();
    }

    public void writeTo(ByteBuffer buffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        if (buffer.remaining() < 1) {
            log.warn(
                    "Ran out of output buffer space, consider increasing the controllers.v086.bufferSize setting!");
            return;
        }

        // Determine how many messages fit before writing the count byte. Writing the
        // count first and then skipping an oversized message would leave receivers
        // with a bundle that claims more messages than it contains.
        int count = 0;
        int available = buffer.remaining() - 1;
        for (int i = 0; i < numToWrite; i++) {
            if (messages[i] == null)
                break;

            int required = messages[i].getLength() + 4;
            if (required > available)
                break;

            available -= required;
            count++;
        }

        if (count < numToWrite) {
            log.warn("Only " + count + " of " + numToWrite
                    + " outbound messages fit in the output buffer, consider increasing the controllers.v086.bufferSize setting!");
        }

        // no real need for unsigned
        // UnsignedUtil.putUnsignedByte(buffer, numToWrite);
        buffer.put((byte) count);
        for (int i = 0; i < count; i++) {
            messages[i].writeTo(buffer);
        }
    }

    public static V086Bundle parse(ByteBuffer buffer)
            throws ParseException, V086BundleFormatException, MessageFormatException {
        return parse(buffer, -1);
    }

    public static V086Bundle parse(ByteBuffer buffer, int lastMessageID)
            throws ParseException, V086BundleFormatException, MessageFormatException {
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        if (buffer.limit() < MIN_BUFFER_LENGTH)
            throw new V086BundleFormatException("Invalid buffer length: " + buffer.limit());

        int messageCount = buffer.get();

        if (messageCount <= 0 || messageCount > MAX_MESSAGE_COUNT)
            throw new V086BundleFormatException("Invalid message count: " + messageCount);

        if (buffer.limit() < (1 + (messageCount * MESSAGE_HEADER_SIZE)))
            throw new V086BundleFormatException("Invalid bundle length: " + buffer.limit());

        V086Message[] messages = new V086Message[messageCount];
        int parsedCount;
        for (parsedCount = 0; parsedCount < messageCount; parsedCount++) {
            // Validate buffer has enough bytes for message header (2 bytes number + 2 bytes
            // length)
            if (buffer.remaining() < 4)
                throw new V086BundleFormatException(
                        "Bundle appears truncated, remaining = " + buffer.remaining());

            int messageNumber = UnsignedUtil.getUnsignedShort(buffer);
            // Note: messageNumber range check removed - getUnsignedShort always returns
            // 0-65535

            if (!isNewerMessage(messageNumber, lastMessageID)) {
                // buffer.position((buffer.position() + messageLength));

                // will break here instead of looking for more messages, should improve speed
                // slightly
                // there shouldn't be any more valid messages anyway if we just found the last
                // ID
                break;
            }

            // no real need for unsigned
            short messageLength = buffer.getShort();
            if (messageLength < 2 || messageLength > buffer.remaining()
                    || messageLength > buffer.limit())
                throw new ParseException("Invalid message length: " + messageLength);

            // messages are purposely added in reverse order here so it's faster to read out
            // in the controller
            // messages.push(V086Message.parse(messageNumber, messageLength, buffer));
            // messages.addFirst(V086Message.parse(messageNumber, messageLength, buffer));
            // messages.add(V086Message.parse(messageNumber, messageLength, buffer));
            try {
                messages[parsedCount] = V086Message.parse(messageNumber, messageLength, buffer);
            } catch (BufferUnderflowException e) {
                throw new ParseException("Message appears truncated", e);
            }
        }

        return new V086Bundle(messages, parsedCount);
    }

    /**
     * Compares 16-bit message numbers with wrap-around. Message numbers restart at
     * zero after 0xFFFF, so a plain numeric comparison stops accepting messages
     * after the first wrap.
     */
    private static boolean isNewerMessage(int candidate, int last) {
        if (last < 0)
            return true;

        int diff = (candidate - last) & 0xFFFF;
        return diff > 0 && diff < 0x8000;
    }
}
