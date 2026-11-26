package su.kidoz.kaillera.controller.v086;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Thread-safe implementation of {@link PortAllocator} using a concurrent queue.
 *
 * <p>
 * Ports are initialized from a contiguous range in a random order. A blind
 * spoofing client cannot spray a small set of predictable ports and hit the
 * session it wants; it must guess which port was assigned.
 */
public final class PortAllocatorImpl implements PortAllocator {

    private final Queue<Integer> portQueue;

    /**
     * Creates a port allocator for the specified range.
     *
     * @param startPort
     *            the first port in the range (inclusive)
     * @param count
     *            the number of ports to allocate
     */
    public PortAllocatorImpl(int startPort, int count) {
        List<Integer> ports = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            ports.add(startPort + i);
        }
        Collections.shuffle(ports, new SecureRandom());
        this.portQueue = new ConcurrentLinkedQueue<>(ports);
    }

    @Override
    public OptionalInt allocate() {
        Integer port = portQueue.poll();
        return port != null ? OptionalInt.of(port) : OptionalInt.empty();
    }

    @Override
    public void release(int port) {
        portQueue.add(port);
    }

    @Override
    public int availableCount() {
        return portQueue.size();
    }
}
