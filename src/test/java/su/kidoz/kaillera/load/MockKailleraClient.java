package su.kidoz.kaillera.load;

import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraServer;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.event.ConnectedEvent;
import su.kidoz.kaillera.model.event.DefaultEventDispatcher;
import su.kidoz.kaillera.model.event.EventDispatcher;
import su.kidoz.kaillera.model.event.GameStartedEvent;
import su.kidoz.kaillera.model.event.KailleraEvent;
import su.kidoz.kaillera.model.event.KailleraEventListener;
import su.kidoz.kaillera.model.event.UserJoinedGameEvent;

/**
 * A simulated Kaillera client for load testing.
 *
 * <p>
 * This client can connect to a server, perform operations, and track events
 * received. Supports metrics collection and latency injection for realistic
 * load testing.
 */
public class MockKailleraClient implements KailleraEventListener {

    private final String name;
    private final int clientId;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean loggedIn = new AtomicBoolean(false);
    private final AtomicBoolean inGame = new AtomicBoolean(false);
    private final AtomicBoolean gameStarted = new AtomicBoolean(false);
    private final AtomicInteger eventsReceived = new AtomicInteger(0);
    private final CopyOnWriteArrayList<KailleraEvent> events = new CopyOnWriteArrayList<>();

    private KailleraUser user;
    private KailleraGame currentGame;
    private CountDownLatch loginLatch;
    private CountDownLatch gameLatch;
    private CountDownLatch gameStartLatch;

    private LoadTestMetrics metrics;
    private volatile long injectedLatencyMs = 0;

    public MockKailleraClient(String name, int clientId) {
        this.name = name;
        this.clientId = clientId;
    }

    public String getName() {
        return name;
    }

    public int getClientId() {
        return clientId;
    }

    public boolean isConnected() {
        return connected.get();
    }

    public boolean isLoggedIn() {
        return loggedIn.get();
    }

    public boolean isInGame() {
        return inGame.get();
    }

    public boolean isGameStarted() {
        return gameStarted.get();
    }

    public int getEventsReceived() {
        return eventsReceived.get();
    }

    public KailleraUser getUser() {
        return user;
    }

    public KailleraGame getCurrentGame() {
        return currentGame;
    }

    /** Sets a shared metrics collector for this client. */
    public void setMetrics(LoadTestMetrics metrics) {
        this.metrics = metrics;
    }

    /** Sets injected latency in milliseconds (simulates network delay). */
    public void setInjectedLatency(long latencyMs) {
        this.injectedLatencyMs = latencyMs;
    }

    private void simulateLatency() {
        if (injectedLatencyMs > 0) {
            try {
                Thread.sleep(injectedLatencyMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Connects to the server and waits for login completion.
     */
    public boolean connect(KailleraServer server, long timeoutMs) throws Exception {
        long startTime = System.currentTimeMillis();
        loginLatch = new CountDownLatch(1);

        try {
            simulateLatency();

            InetSocketAddress address = new InetSocketAddress("127.0.0.1",
                    27888 + (clientId % 1000));
            EventDispatcher dispatcher = new DefaultEventDispatcher();
            dispatcher.setListener(this);
            user = server.newConnection(address, "v086", dispatcher);
            connected.set(true);

            // Set the socket address for game operations (normally done by V086Controller)
            user.setSocketAddress(address);
            user.setName(name);
            user.setClientType("LoadTestClient");
            user.setConnectionType(KailleraUser.CONNECTION_TYPE_LAN);
            user.setPing(10);

            simulateLatency();
            user.login();

            boolean success = loginLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            long duration = System.currentTimeMillis() - startTime;

            if (metrics != null) {
                if (success) {
                    metrics.recordSuccess(LoadTestMetrics.Operation.CONNECT, duration);
                } else {
                    metrics.recordFailure(LoadTestMetrics.Operation.CONNECT,
                            LoadTestMetrics.ErrorType.LOGIN_TIMEOUT);
                }
            }
            return success;
        } catch (Exception e) {
            if (metrics != null) {
                metrics.recordFailure(LoadTestMetrics.Operation.CONNECT, e);
            }
            throw e;
        }
    }

    /**
     * Creates a game and returns it.
     */
    public KailleraGame createGame(String romName) throws Exception {
        long startTime = System.currentTimeMillis();

        try {
            if (!loggedIn.get()) {
                throw new IllegalStateException("Not logged in");
            }

            simulateLatency();
            currentGame = user.createGame(romName);
            inGame.set(true);

            long duration = System.currentTimeMillis() - startTime;
            if (metrics != null) {
                metrics.recordSuccess(LoadTestMetrics.Operation.CREATE_GAME, duration);
            }
            return currentGame;
        } catch (Exception e) {
            if (metrics != null) {
                metrics.recordFailure(LoadTestMetrics.Operation.CREATE_GAME, e);
            }
            throw e;
        }
    }

    /**
     * Joins an existing game.
     */
    public KailleraGame joinGame(int gameId, long timeoutMs) throws Exception {
        long startTime = System.currentTimeMillis();

        try {
            if (!loggedIn.get()) {
                throw new IllegalStateException("Not logged in");
            }

            gameLatch = new CountDownLatch(1);
            simulateLatency();
            currentGame = user.joinGame(gameId);
            boolean success = gameLatch.await(timeoutMs, TimeUnit.MILLISECONDS);

            long duration = System.currentTimeMillis() - startTime;
            if (metrics != null) {
                if (success) {
                    metrics.recordSuccess(LoadTestMetrics.Operation.JOIN_GAME, duration);
                } else {
                    metrics.recordFailure(LoadTestMetrics.Operation.JOIN_GAME,
                            LoadTestMetrics.ErrorType.CONNECTION_TIMEOUT);
                }
            }

            if (success) {
                inGame.set(true);
            }
            return currentGame;
        } catch (Exception e) {
            if (metrics != null) {
                metrics.recordFailure(LoadTestMetrics.Operation.JOIN_GAME, e);
            }
            throw e;
        }
    }

    /**
     * Marks player as ready to start the game.
     */
    public void playerReady() throws Exception {
        if (!inGame.get()) {
            throw new IllegalStateException("Not in a game");
        }
        simulateLatency();
        user.playerReady();
    }

    /**
     * Starts the game (owner only) and waits for game started event.
     */
    public boolean startGame(long timeoutMs) throws Exception {
        long startTime = System.currentTimeMillis();

        try {
            if (!inGame.get()) {
                throw new IllegalStateException("Not in a game");
            }

            gameStartLatch = new CountDownLatch(1);
            simulateLatency();
            user.startGame();

            boolean success = gameStartLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            long duration = System.currentTimeMillis() - startTime;

            if (metrics != null) {
                if (success) {
                    metrics.recordSuccess(LoadTestMetrics.Operation.START_GAME, duration);
                } else {
                    metrics.recordFailure(LoadTestMetrics.Operation.START_GAME,
                            LoadTestMetrics.ErrorType.CONNECTION_TIMEOUT);
                }
            }
            return success;
        } catch (Exception e) {
            if (metrics != null) {
                metrics.recordFailure(LoadTestMetrics.Operation.START_GAME, e);
            }
            throw e;
        }
    }

    /**
     * Sends a chat message.
     */
    public void chat(String message) throws Exception {
        long startTime = System.currentTimeMillis();

        try {
            if (!loggedIn.get()) {
                throw new IllegalStateException("Not logged in");
            }

            simulateLatency();
            user.chat(message);

            long duration = System.currentTimeMillis() - startTime;
            if (metrics != null) {
                metrics.recordSuccess(LoadTestMetrics.Operation.CHAT, duration);
            }
        } catch (Exception e) {
            if (metrics != null) {
                metrics.recordFailure(LoadTestMetrics.Operation.CHAT, e);
            }
            throw e;
        }
    }

    /**
     * Drops from the current game.
     */
    public void dropGame() throws Exception {
        if (inGame.get() && currentGame != null) {
            simulateLatency();
            user.dropGame();
            inGame.set(false);
            gameStarted.set(false);
            currentGame = null;
        }
    }

    /**
     * Quits from the server.
     */
    public void quit() throws Exception {
        long startTime = System.currentTimeMillis();

        try {
            if (loggedIn.get()) {
                simulateLatency();
                user.quit("Load test complete");

                long duration = System.currentTimeMillis() - startTime;
                if (metrics != null) {
                    metrics.recordSuccess(LoadTestMetrics.Operation.QUIT, duration);
                }
            }
        } catch (Exception e) {
            if (metrics != null) {
                metrics.recordFailure(LoadTestMetrics.Operation.QUIT, e);
            }
            throw e;
        } finally {
            connected.set(false);
            loggedIn.set(false);
            inGame.set(false);
            gameStarted.set(false);
        }
    }

    // KailleraEventListener implementation

    @Override
    public void actionPerformed(KailleraEvent event) {
        eventsReceived.incrementAndGet();
        events.add(event);

        if (event instanceof ConnectedEvent connectedEvent) {
            if (connectedEvent.getUser().equals(user)) {
                loggedIn.set(true);
                if (loginLatch != null) {
                    loginLatch.countDown();
                }
            }
        } else if (event instanceof UserJoinedGameEvent joinedGameEvent) {
            if (joinedGameEvent.getUser().equals(user)) {
                inGame.set(true);
                if (gameLatch != null) {
                    gameLatch.countDown();
                }
            }
        } else if (event instanceof GameStartedEvent) {
            gameStarted.set(true);
            if (gameStartLatch != null) {
                gameStartLatch.countDown();
            }
        }
    }

    @Override
    public void stop() {
        connected.set(false);
        loggedIn.set(false);
        inGame.set(false);
        gameStarted.set(false);
    }
}
