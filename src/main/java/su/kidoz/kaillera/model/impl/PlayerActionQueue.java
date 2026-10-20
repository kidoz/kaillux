package su.kidoz.kaillera.model.impl;

import java.util.function.IntPredicate;

/**
 * Per-player ring buffer of game input actions.
 *
 * <p>
 * Every player has their own queue, and every player assembles frames by
 * reading from all queues. Readers therefore advance independently through the
 * same byte stream, which requires per-reader read positions and an absolute
 * write position. Absolute positions (instead of modulo indices) make a full
 * buffer distinguishable from an empty one and let the writer refuse to
 * overwrite actions that a reader has not consumed yet.
 */
public class PlayerActionQueue {
    private final int gameBufferSize;
    private final int gameTimeoutMillis;

    private final int thisPlayerNumber;
    private final KailleraUserImpl thisPlayer;
    private volatile boolean synched = false;
    private volatile PlayerTimeoutException lastTimeout;

    private final byte[] array;
    private final long[] readPositions;
    private final IntPredicate readerActive;
    private long writePosition = 0;

    public PlayerActionQueue(int playerNumber, KailleraUserImpl player, int numPlayers,
            int gameBufferSize, int gameTimeoutMillis, boolean capture) {
        this(playerNumber, player, numPlayers, gameBufferSize, gameTimeoutMillis, capture,
                readerIndex -> true);
    }

    public PlayerActionQueue(int playerNumber, KailleraUserImpl player, int numPlayers,
            int gameBufferSize, int gameTimeoutMillis, boolean capture, IntPredicate readerActive) {
        this.thisPlayerNumber = playerNumber;
        this.thisPlayer = player;
        this.gameBufferSize = gameBufferSize;
        this.gameTimeoutMillis = gameTimeoutMillis;
        this.readerActive = readerActive;

        array = new byte[gameBufferSize];
        readPositions = new long[numPlayers];
    }

    public int getPlayerNumber() {
        return thisPlayerNumber;
    }

    public KailleraUserImpl getPlayer() {
        return thisPlayer;
    }

    public synchronized void setSynched(boolean synched) {
        this.synched = synched;

        if (!synched) {
            notifyAll();
        }
    }

    public boolean isSynched() {
        return synched;
    }

    public void setLastTimeout(PlayerTimeoutException e) {
        this.lastTimeout = e;
    }

    public PlayerTimeoutException getLastTimeout() {
        return lastTimeout;
    }

    /**
     * Adds actions to the queue.
     *
     * <p>
     * The writer waits up to the configured game timeout for enough space to become
     * available. Waiting never overwrites actions that a reader has not consumed
     * yet.
     *
     * @param actions
     *            the action bytes to enqueue
     * @return true if the actions were enqueued, false if the queue is not synched,
     *         the payload can never fit, or the wait timed out
     */
    public synchronized boolean addActions(byte[] actions) {
        if (!synched || actions.length > gameBufferSize)
            return false;

        long deadline = System.currentTimeMillis() + gameTimeoutMillis;
        while ((writePosition - minReadPosition()) + actions.length > gameBufferSize) {
            if (!synched)
                return false;

            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0)
                return false;

            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        for (byte action : actions) {
            array[(int) (writePosition % gameBufferSize)] = action;
            writePosition++;
        }

        notifyAll();
        lastTimeout = null;
        return true;
    }

    public synchronized void getAction(int playerNumber, byte[] actions, int location,
            int actionLength) throws PlayerTimeoutException {
        // Validate array bounds to prevent ArrayIndexOutOfBoundsException
        if (playerNumber < 1 || playerNumber > readPositions.length) {
            throw new IllegalArgumentException(
                    "Invalid playerNumber: " + playerNumber + ", max: " + readPositions.length);
        }
        if (location < 0 || actionLength < 0 || location + actionLength > actions.length) {
            throw new IllegalArgumentException("Invalid array bounds: location=" + location
                    + ", actionLength=" + actionLength + ", array.length=" + actions.length);
        }

        int readIndex = playerNumber - 1;

        // Use while loop to handle spurious wakeups
        long deadline = System.currentTimeMillis() + gameTimeoutMillis;
        long available = writePosition - readPositions[readIndex];
        while (available < actionLength && synched) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            try {
                wait(remaining);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            available = writePosition - readPositions[readIndex];
        }

        if (available >= actionLength) {
            long readPosition = readPositions[readIndex];
            for (int i = 0; i < actionLength; i++) {
                actions[(location + i)] = array[(int) (readPosition % gameBufferSize)];
                readPosition++;
            }
            readPositions[readIndex] = readPosition;
            // Wake writers waiting for readers to make room.
            notifyAll();
            return;
        }

        // No committed bytes. Returning here used to look like a successful read
        // and let each assembler send its own zero-filled slot.
        if (!synched) {
            throw new QueueDesyncedException(thisPlayerNumber);
        }

        throw new PlayerTimeoutException(thisPlayerNumber, thisPlayer);
    }

    private long minReadPosition() {
        if (readPositions.length == 0)
            return writePosition;

        // Only readers that are still participating constrain capacity. A player
        // who has been marked desynched stops consuming its peers' queues; counting
        // its frozen position would eventually block every writer.
        long min = writePosition;
        boolean found = false;
        for (int i = 0; i < readPositions.length; i++) {
            if (!readerActive.test(i))
                continue;
            if (!found || readPositions[i] < min) {
                min = readPositions[i];
                found = true;
            }
        }
        return found ? min : writePosition;
    }
}
