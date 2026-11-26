package su.kidoz.kaillera.controller.messaging;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class ByteBufferMessage {
    protected static final Logger log = LoggerFactory.getLogger(ByteBufferMessage.class);

    // UTF-8 unless -Demulinker.charset=<charset> names another charset, which
    // keeps behavior the same on every platform.
    public static final Charset charset = configuredCharset();

    private static Charset configuredCharset() {
        Charset charset = StandardCharsets.UTF_8;
        String charsetName = System.getProperty("emulinker.charset");
        if (charsetName != null) {
            try {
                if (Charset.isSupported(charsetName)) {
                    charset = Charset.forName(charsetName);
                } else {
                    log.error("Charset {} is not supported, using UTF-8", charsetName);
                }
            } catch (Exception e) {
                log.error("Failed to load charset {}: {}, using UTF-8", charsetName,
                        e.getMessage());
            }
        }

        log.info("Using character set: {}", charset.displayName());
        return charset;
    }

    private ByteBuffer buffer;

    public abstract int getLength();

    public abstract String getDescription();

    public abstract String toString();

    protected void initBuffer() {
        initBuffer(getLength());
    }

    private void initBuffer(int size) {
        buffer = getBuffer(size);
    }

    public void releaseBuffer() {

    }

    public ByteBuffer toBuffer() {
        initBuffer();
        writeTo(buffer);
        buffer.flip();
        return buffer.duplicate();
    }

    public abstract void writeTo(ByteBuffer buffer);

    public static ByteBuffer getBuffer(int size) {
        return ByteBuffer.allocateDirect(size);
    }

    public static void releaseBuffer(ByteBuffer buffer) {
        // nothing to do since we aren't caching buffers anymore
    }
}
