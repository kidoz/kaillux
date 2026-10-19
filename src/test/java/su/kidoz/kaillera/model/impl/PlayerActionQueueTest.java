package su.kidoz.kaillera.model.impl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Tests for PlayerActionQueue, particularly thread safety fixes.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PlayerActionQueue Tests")
class PlayerActionQueueTest {

    private static final int BUFFER_SIZE = 1024;
    private static final int TIMEOUT_MILLIS = 100;
    private static final int NUM_PLAYERS = 2;

    @Mock
    private KailleraUserImpl player;

    private PlayerActionQueue queue;

    @BeforeEach
    void setUp() {
        queue = new PlayerActionQueue(1, player, NUM_PLAYERS, BUFFER_SIZE, TIMEOUT_MILLIS, false);
    }

    @Nested
    @DisplayName("Basic functionality")
    class BasicFunctionality {

        @Test
        @DisplayName("should return correct player number")
        void shouldReturnPlayerNumber() {
            assertEquals(1, queue.getPlayerNumber());
        }

        @Test
        @DisplayName("should return player")
        void shouldReturnPlayer() {
            assertEquals(player, queue.getPlayer());
        }

        @Test
        @DisplayName("should not be synched initially")
        void shouldNotBeSynchedInitially() {
            assertFalse(queue.isSynched());
        }

        @Test
        @DisplayName("should set synched state")
        void shouldSetSynchedState() {
            queue.setSynched(true);
            assertTrue(queue.isSynched());

            queue.setSynched(false);
            assertFalse(queue.isSynched());
        }
    }

    @Nested
    @DisplayName("Action handling")
    class ActionHandling {

        @Test
        @DisplayName("should add and retrieve actions when synched")
        void shouldAddAndRetrieveActionsWhenSynched() throws PlayerTimeoutException {
            queue.setSynched(true);

            byte[] actions = new byte[]{1, 2, 3, 4};
            queue.addActions(actions);

            byte[] result = new byte[4];
            queue.getAction(1, result, 0, 4);

            assertArrayEquals(actions, result);
        }

        @Test
        @DisplayName("should ignore actions when not synched")
        void shouldIgnoreActionsWhenNotSynched() throws PlayerTimeoutException {
            assertFalse(queue.isSynched());

            byte[] actions = new byte[]{1, 2, 3, 4};
            queue.addActions(actions);

            // Should not throw and should not block
            byte[] result = new byte[4];
            queue.getAction(1, result, 0, 4);

            // Result should be zeros since no actions were added
            assertArrayEquals(new byte[4], result);
        }

        @Test
        @DisplayName("should throw timeout when synched and no data")
        void shouldThrowTimeoutWhenSynchedAndNoData() {
            queue.setSynched(true);

            assertThrows(PlayerTimeoutException.class, () -> {
                byte[] result = new byte[4];
                queue.getAction(1, result, 0, 4);
            });
        }
    }

    @Nested
    @DisplayName("Thread safety")
    class ThreadSafety {

        @Test
        @DisplayName("should handle concurrent add and get operations")
        void shouldHandleConcurrentAddAndGet() throws InterruptedException {
            final int numIterations = 100;
            final int actionSize = 4;
            final ExecutorService executor = Executors.newFixedThreadPool(4);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final CountDownLatch doneLatch = new CountDownLatch(2);
            final AtomicInteger successCount = new AtomicInteger(0);
            final AtomicInteger errorCount = new AtomicInteger(0);

            queue.setSynched(true);

            // Producer thread
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < numIterations; i++) {
                        byte[] actions = new byte[actionSize];
                        for (int j = 0; j < actionSize; j++) {
                            actions[j] = (byte) ((i + j) % 128);
                        }
                        queue.addActions(actions);
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });

            // Consumer thread
            executor.submit(() -> {
                try {
                    startLatch.await();
                    Thread.sleep(10); // Let producer get ahead
                    for (int i = 0; i < numIterations; i++) {
                        try {
                            byte[] result = new byte[actionSize];
                            queue.getAction(1, result, 0, actionSize);
                            successCount.incrementAndGet();
                        } catch (PlayerTimeoutException e) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });

            startLatch.countDown();
            assertTrue(doneLatch.await(10, TimeUnit.SECONDS));
            executor.shutdown();

            // Most operations should succeed
            assertTrue(successCount.get() > numIterations / 2, "Expected more successes than "
                    + numIterations / 2 + ", got " + successCount.get());
        }

        @Test
        @DisplayName("should safely setSynched from multiple threads")
        void shouldSafelySetSynchedFromMultipleThreads() throws InterruptedException {
            final int numThreads = 10;
            final int numIterations = 100;
            final ExecutorService executor = Executors.newFixedThreadPool(numThreads);
            final CountDownLatch startLatch = new CountDownLatch(1);
            final CountDownLatch doneLatch = new CountDownLatch(numThreads);

            for (int t = 0; t < numThreads; t++) {
                final boolean setValue = t % 2 == 0;
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < numIterations; i++) {
                            queue.setSynched(setValue);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            startLatch.countDown();
            assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
            executor.shutdown();

            // Should complete without exceptions - the final state is indeterminate
            // but no exceptions should be thrown
        }
    }

    @Nested
    @DisplayName("Timeout handling")
    class TimeoutHandling {

        @Test
        @DisplayName("should store and retrieve last timeout")
        void shouldStoreAndRetrieveLastTimeout() {
            PlayerTimeoutException e = new PlayerTimeoutException(1, player);
            queue.setLastTimeout(e);
            assertEquals(e, queue.getLastTimeout());
        }

        @Test
        @DisplayName("addActions should clear last timeout")
        void addActionsShouldClearLastTimeout() {
            queue.setSynched(true);
            PlayerTimeoutException e = new PlayerTimeoutException(1, player);
            queue.setLastTimeout(e);

            queue.addActions(new byte[]{1, 2, 3, 4});

            assertNull(queue.getLastTimeout());
        }
    }

    @Nested
    @DisplayName("Ring buffer capacity")
    class RingBufferCapacity {

        @Test
        @DisplayName("should read a completely full buffer instead of timing out")
        void shouldReadFullBuffer() throws PlayerTimeoutException {
            queue.setSynched(true);

            byte[] actions = new byte[BUFFER_SIZE];
            for (int i = 0; i < actions.length; i++)
                actions[i] = (byte) i;

            assertTrue(queue.addActions(actions));

            byte[] result = new byte[BUFFER_SIZE];
            queue.getAction(1, result, 0, BUFFER_SIZE);

            assertArrayEquals(actions, result);
        }

        @Test
        @DisplayName("should not overwrite unconsumed actions")
        void shouldNotOverwriteUnconsumedActions() throws PlayerTimeoutException {
            queue.setSynched(true);

            byte[] first = new byte[BUFFER_SIZE];
            for (int i = 0; i < first.length; i++)
                first[i] = (byte) (i + 1);
            assertTrue(queue.addActions(first));

            // No reader has consumed anything yet, so a second payload cannot fit.
            assertFalse(queue.addActions(new byte[]{1, 2, 3, 4}));

            byte[] result = new byte[BUFFER_SIZE];
            queue.getAction(1, result, 0, BUFFER_SIZE);
            assertArrayEquals(first, result);
        }

        @Test
        @DisplayName("should reject a payload larger than the buffer")
        void shouldRejectOversizedPayload() {
            queue.setSynched(true);

            assertFalse(queue.addActions(new byte[BUFFER_SIZE + 1]));
        }

        @Test
        @DisplayName("should accept new actions after readers make room")
        void shouldWriteAgainAfterReadersMakeRoom() throws PlayerTimeoutException {
            queue.setSynched(true);

            byte[] first = new byte[BUFFER_SIZE - 3];
            assertTrue(queue.addActions(first));

            byte[] second = new byte[]{9, 8, 7, 6};
            // The queue is too full until every reader consumes the first payload.
            assertFalse(queue.addActions(second));

            byte[] result = new byte[first.length];
            queue.getAction(1, result, 0, result.length);
            queue.getAction(2, result, 0, result.length);
            assertArrayEquals(first, result);

            assertTrue(queue.addActions(second));
            byte[] secondResult = new byte[second.length];
            queue.getAction(1, secondResult, 0, second.length);
            assertArrayEquals(second, secondResult);
        }
    }
}
