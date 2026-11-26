package su.kidoz.util;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EmuUtil {
    private static final Logger log = LoggerFactory.getLogger(EmuUtil.class);

    private static final char[] HEX_CHARS = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A',
            'B', 'C', 'D', 'E', 'F'};

    public static final String LB = System.getProperty("line.separator");

    // Thread-safe DateTimeFormatter (Java 25: no ThreadLocal needed, immutable and
    // thread-safe)
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
            .ofPattern("MM/dd/yyyy HH:mm:ss");

    /**
     * Formats a Date using the standard date/time pattern.
     *
     * @param date
     *            the date to format
     * @return formatted date string
     */
    public static String formatDate(Date date) {
        return DATE_FORMATTER.format(date.toInstant().atZone(ZoneId.systemDefault()));
    }

    public static boolean systemIsWindows() {
        if (File.separatorChar == '\\')
            return true;
        return false;
    }

    public static String formatBytes(byte[] data) {
        return formatBytes(data, false);
    }

    public static String formatBytes(byte[] data, boolean allHex) {
        if (data == null)
            return "null";

        if (data.length == 0)
            return "";

        if (allHex)
            return bytesToHex(data, ',');

        int len = data.length;

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < len; i++) {
            if (Character.isLetterOrDigit((char) data[i]) || (data[i] >= 32 && data[i] <= 126))
                sb.append((char) data[i]);
            else
                sb.append(byteToHex(data[i]));

            if (i < (len - 1))
                sb.append(',');
        }

        return sb.toString();
    }

    public static String bytesToHex(byte[] data, char sep) {
        int len = data.length;
        StringBuilder sb = new StringBuilder((len * 3));
        for (int i = 0; i < len; i++) {
            sb.append(byteToHex(data[i]));
            if (i < (len - 1))
                sb.append(sep);
        }

        return sb.toString();
    }

    public static String bytesToHex(byte[] data) {
        if (data == null)
            return "null";

        int len = data.length;
        StringBuilder sb = new StringBuilder((len * 3));
        for (int i = 0; i < len; i++) {
            sb.append(byteToHex(data[i]));
        }

        return sb.toString();
    }

    public static String bytesToHex(byte[] data, int pos, int len) {
        if (data == null)
            return "null";

        StringBuilder sb = new StringBuilder((len * 2));
        for (int i = pos; i < (pos + len); i++) {
            sb.append(byteToHex(data[i]));
        }

        return sb.toString();
    }

    public static String byteToHex(byte b) {
        return Character.toString(HEX_CHARS[((b & 0xf0) >> 4)])
                + Character.toString(HEX_CHARS[(b & 0xf)]);
    }

    public static byte[] hexToByteArray(String hex) throws NumberFormatException {
        if ((hex.length() % 2) != 0)
            throw new NumberFormatException(
                    "The string has the wrong length, not pairs of hex representations.");

        int len = (hex.length() / 2);
        byte[] ba = new byte[len];
        int pos = 0;
        for (int i = 0; i < len; i++) {
            ba[i] = hexToByte(hex.substring(pos, pos + 2).toCharArray());
            pos += 2;
        }

        return ba;
    }

    public static byte hexToByte(char[] hex) throws NumberFormatException {
        if (hex.length != 2)
            throw new NumberFormatException("Invalid number of digits in " + new String(hex));

        int i = 0;
        byte nibble;

        if ((hex[i] >= '0') && (hex[i] <= '9')) {
            nibble = (byte) ((hex[i] - '0') << 4);
        } else if ((hex[i] >= 'A') && (hex[i] <= 'F')) {
            nibble = (byte) ((hex[i] - ('A' - 0x0A)) << 4);
        } else if ((hex[i] >= 'a') && (hex[i] <= 'f')) {
            nibble = (byte) ((hex[i] - ('a' - 0x0A)) << 4);
        } else {
            throw new NumberFormatException(hex[i] + " is not a hexadecimal string.");
        }

        i++;

        if ((hex[i] >= '0') && (hex[i] <= '9')) {
            nibble = (byte) (nibble | (hex[i] - '0'));
        } else if ((hex[i] >= 'A') && (hex[i] <= 'F')) {
            nibble = (byte) (nibble | (hex[i] - ('A' - 0x0A)));
        } else if ((hex[i] >= 'a') && (hex[i] <= 'f')) {
            nibble = (byte) (nibble | (hex[i] - ('a' - 0x0A)));
        } else {
            throw new NumberFormatException(hex[i] + " is not a hexadecimal string.");
        }

        return nibble;
    }

    public static String arrayToString(int[] array, char sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < (array.length - 1))
                sb.append(sep);
        }
        return sb.toString();
    }

    public static String arrayToString(byte[] array, char sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < array.length; i++) {
            sb.append(array[i]);
            if (i < (array.length - 1))
                sb.append(sep);
        }
        return sb.toString();
    }

    public static String formatSocketAddress(SocketAddress sa) {
        return ((InetSocketAddress) sa).getAddress().getHostAddress() + ":"
                + ((InetSocketAddress) sa).getPort();
    }

    public static String dumpBuffer(ByteBuffer buffer) {
        return dumpBuffer(buffer, false);
    }

    public static String dumpBuffer(ByteBuffer buffer, boolean allHex) {
        StringBuilder sb = new StringBuilder();
        buffer.mark();
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (!allHex && Character.isLetterOrDigit((char) b))
                sb.append((char) b);
            else
                sb.append(byteToHex(b));
            if (buffer.hasRemaining())
                sb.append(",");
        }
        buffer.reset();
        return sb.toString();
    }

    public static String readString(ByteBuffer buffer, int stopByte, Charset charset) {
        // Allocate exactly the bytes that belong to this string. Allocating
        // buffer.remaining() made a message with many short strings allocate the
        // rest of the datagram once per string.
        int start = buffer.position();
        int limit = buffer.limit();
        int end = start;
        byte terminator = (byte) stopByte;
        while (end < limit && buffer.get(end) != terminator) {
            end++;
        }

        byte[] bytes = new byte[end - start];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = buffer.get(start + i);
        }

        // Consume the terminator when one was present; otherwise leave the buffer
        // at its limit, matching the old end-of-buffer behaviour.
        buffer.position(end < limit ? end + 1 : end);
        return new String(bytes, charset);
    }

    public static void writeString(ByteBuffer buffer, String s, int stopByte, Charset charset) {
        buffer.put(charset.encode(s));
        buffer.put((byte) stopByte);
    }
}
