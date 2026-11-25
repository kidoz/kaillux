package su.kidoz.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("UnsignedUtil Tests")
class UnsignedUtilTest {

    @Test
    @DisplayName("writeUnsignedShort writes big-endian by default, as its reader expects")
    void writeUnsignedShortWritesBigEndianByDefault() {
        byte[] bytes = new byte[4];

        // This used to call itself without end, ending in a stack overflow.
        UnsignedUtil.writeUnsignedShort(0xBEEF, bytes, 1);

        assertEquals(0xBEEF, UnsignedUtil.readUnsignedShort(bytes, 1));
        assertEquals((byte) 0xBE, bytes[1]);
        assertEquals((byte) 0xEF, bytes[2]);
        assertEquals(0, bytes[0]);
        assertEquals(0, bytes[3]);
    }

    @Test
    @DisplayName("the little-endian form round-trips and leaves the neighbours alone")
    void theLittleEndianFormRoundTrips() {
        byte[] bytes = new byte[4];

        UnsignedUtil.writeUnsignedShort(0x1234, bytes, 1, true);

        assertEquals(0x1234, UnsignedUtil.readUnsignedShort(bytes, 1, true));
        assertEquals((byte) 0x34, bytes[1]);
        assertEquals((byte) 0x12, bytes[2]);
    }

    @Test
    @DisplayName("writeUnsignedInt round-trips, including values with the top bit set")
    void writeUnsignedIntRoundTrips() {
        byte[] bytes = new byte[10];

        UnsignedUtil.writeUnsignedInt(0xDEADBEEFL, bytes, 2);
        UnsignedUtil.writeUnsignedInt(0x7F123456L, bytes, 6, true);

        assertEquals(0xDEADBEEFL, UnsignedUtil.readUnsignedInt(bytes, 2));
        assertEquals(0x7F123456L, UnsignedUtil.readUnsignedInt(bytes, 6, true));
    }
}
