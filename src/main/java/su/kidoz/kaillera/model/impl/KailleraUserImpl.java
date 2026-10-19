package su.kidoz.kaillera.model.impl;

import java.net.InetSocketAddress;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.event.AllReadyEvent;
import su.kidoz.kaillera.model.event.GameDataEvent;
import su.kidoz.kaillera.model.event.GameStartedEvent;
import su.kidoz.kaillera.model.event.KailleraEvent;
import su.kidoz.kaillera.model.event.EventDispatcher;
import su.kidoz.kaillera.model.event.KailleraEventListener;
import su.kidoz.kaillera.model.event.UserQuitEvent;
import su.kidoz.kaillera.model.event.UserQuitGameEvent;
import su.kidoz.kaillera.model.exception.ChatException;
import su.kidoz.kaillera.model.exception.ClientAddressException;
import su.kidoz.kaillera.model.exception.CloseGameException;
import su.kidoz.kaillera.model.exception.ConnectionTypeException;
import su.kidoz.kaillera.model.exception.CreateGameException;
import su.kidoz.kaillera.model.exception.DropGameException;
import su.kidoz.kaillera.model.exception.FloodException;
import su.kidoz.kaillera.model.exception.GameChatException;
import su.kidoz.kaillera.model.exception.GameDataException;
import su.kidoz.kaillera.model.exception.GameKickException;
import su.kidoz.kaillera.model.exception.JoinGameException;
import su.kidoz.kaillera.model.exception.LoginException;
import su.kidoz.kaillera.model.exception.PingTimeException;
import su.kidoz.kaillera.model.exception.QuitException;
import su.kidoz.kaillera.model.exception.QuitGameException;
import su.kidoz.kaillera.model.exception.StartGameException;
import su.kidoz.kaillera.model.exception.UserNameException;
import su.kidoz.kaillera.model.exception.UserReadyException;
import su.kidoz.util.EmuLang;
import su.kidoz.util.EmuUtil;
import su.kidoz.util.Executable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class KailleraUserImpl implements KailleraUser, Executable {
    private static final Logger log = LoggerFactory.getLogger(KailleraUserImpl.class);
    private static final String EMULINKER_CLIENT_NAME = "Emulinker Suprclient";

    private final KailleraServerImpl server;
    private volatile KailleraGameImpl game;

    private final int id;
    private volatile String name;
    private final String protocol;
    private volatile String clientType;
    private volatile byte connectionType;
    private volatile int ping;
    private final InetSocketAddress connectSocketAddress;
    private volatile InetSocketAddress clientSocketAddress;
    private volatile int status;
    private volatile boolean loggedIn;
    private volatile String toString;
    private volatile int access;
    private volatile boolean emulinkerClient;

    private final long connectTime;
    private volatile long lastActivity;
    private volatile long lastKeepAlive;
    private volatile long lastChatTime;
    private volatile long lastCreateGameTime;
    private volatile long lastTimeout;

    private volatile int playerNumber = -1;

    private volatile long gameDataErrorTime = -1;

    private volatile boolean isRunning = false;
    private volatile boolean stopFlag = false;

    private static final int MAX_EVENT_QUEUE_SIZE = 2000;
    private static final int DROPPED_EVENTS_LOG_THRESHOLD = 10;
    private static final int QUEUE_WARNING_THRESHOLD = (int) (MAX_EVENT_QUEUE_SIZE * 0.8);

    private final EventDispatcher eventDispatcher;
    private final BlockingQueue<KailleraEvent> eventQueue = new LinkedBlockingQueue<>(
            MAX_EVENT_QUEUE_SIZE);
    private volatile int droppedEventsCount = 0;

    public KailleraUserImpl(int userID, String protocol, InetSocketAddress connectSocketAddress,
            EventDispatcher eventDispatcher, KailleraServerImpl server) {
        this.id = userID;
        this.protocol = protocol;
        this.connectSocketAddress = connectSocketAddress;
        this.server = server;
        this.eventDispatcher = eventDispatcher;

        toString = "User" + userID + "(" + connectSocketAddress.getAddress().getHostAddress() + ")";

        lastChatTime = 0;
        lastCreateGameTime = 0;
        lastTimeout = 0;
        connectTime = System.currentTimeMillis();
        lastActivity = connectTime;
        lastKeepAlive = connectTime;
    }

    public int getID() {
        return id;
    }

    public InetSocketAddress getConnectSocketAddress() {
        return connectSocketAddress;
    }

    public String getProtocol() {
        return protocol;
    }

    public long getConnectTime() {
        return connectTime;
    }

    public int getStatus() {
        return status;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public void setLoggedIn() {
        loggedIn = true;
    }

    public void setLoggedIn(boolean loggedIn) {
        this.loggedIn = loggedIn;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
        toString = "User" + id + "(" + (name.length() > 15 ? (name.substring(0, 15) + "...") : name)
                + "/" + connectSocketAddress.getAddress().getHostAddress() + ")";
    }

    public String getClientType() {
        return clientType;
    }

    public void setClientType(String clientType) {
        this.clientType = clientType;
        if (clientType != null && clientType.startsWith(EMULINKER_CLIENT_NAME))
            emulinkerClient = true;
    }

    public boolean isEmuLinkerClient() {
        return emulinkerClient;
    }

    public byte getConnectionType() {
        return connectionType;
    }

    public void setConnectionType(byte connectionType) {
        this.connectionType = connectionType;
    }

    public InetSocketAddress getSocketAddress() {
        return clientSocketAddress;
    }

    public void setSocketAddress(InetSocketAddress clientSocketAddress) {
        this.clientSocketAddress = clientSocketAddress;
    }

    public int getPing() {
        return ping;
    }

    public void setPing(int ping) {
        this.ping = ping;
    }

    public long getLastActivity() {
        return lastActivity;
    }

    public long getLastKeepAlive() {
        return lastKeepAlive;
    }

    public void updateLastKeepAlive() {
        this.lastKeepAlive = System.currentTimeMillis();
    }

    public KailleraEventListener getListener() {
        return eventDispatcher.getListener();
    }

    public KailleraServerImpl getServer() {
        return server;
    }

    @Override
    public KailleraGame getGame() {
        return game;
    }

    protected void setGame(KailleraGameImpl game) {
        this.game = game;
        if (game == null)
            playerNumber = -1;
    }

    protected void setStatus(int status) {
        this.status = status;
    }

    @Override
    public long getLastChatTime() {
        return lastChatTime;
    }

    protected long getLastCreateGameTime() {
        return lastCreateGameTime;
    }

    protected long getLastTimeout() {
        return lastTimeout;
    }

    protected void setLastTimeout() {
        lastTimeout = System.currentTimeMillis();
    }

    @Override
    public int getAccess() {
        return access;
    }

    @Override
    public String getAccessStr() {
        return AccessManager.ACCESS_NAMES[access];
    }

    public void setAccess(int access) {
        this.access = access;
    }

    public int getPlayerNumber() {
        return playerNumber;
    }

    protected void setPlayerNumber(int playerNumber) {
        this.playerNumber = playerNumber;
    }

    public synchronized void updateLastActivity() {
        long now = System.currentTimeMillis();
        lastActivity = now;
        lastKeepAlive = now;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj instanceof KailleraUserImpl other) {
            return other.getID() == getID();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return id;
    }

    public String toString() {
        return toString;
    }

    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("KailleraUserImpl[id=");
        sb.append(getID());
        sb.append(" protocol=");
        sb.append(getProtocol());
        sb.append(" status=");
        int statusIdx = getStatus();
        sb.append(statusIdx >= 0 && statusIdx < KailleraUser.STATUS_NAMES.length
                ? KailleraUser.STATUS_NAMES[statusIdx]
                : "Unknown(" + statusIdx + ")");
        sb.append(" name=");
        sb.append(getName());
        sb.append(" clientType=");
        sb.append(getClientType());
        sb.append(" ping=");
        sb.append(getPing());
        sb.append(" connectionType=");
        int connTypeIdx = getConnectionType();
        sb.append(KailleraUser.getConnectionTypeName(connTypeIdx));
        sb.append(" remoteAddress=");
        sb.append((getSocketAddress() == null
                ? EmuUtil.formatSocketAddress(getConnectSocketAddress())
                : EmuUtil.formatSocketAddress(getSocketAddress())));
        sb.append("]");
        return sb.toString();
    }

    public void stop() {
        synchronized (this) {
            if (!isRunning) {
                log.debug(this + "  thread stop request ignored: not running!");
                return;
            }

            if (stopFlag) {
                log.debug(this + "  thread stop request ignored: already stopping!");
                return;
            }

            stopFlag = true;
        }

        // Sleep outside synchronized block to avoid blocking other threads
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("Stop sleep interrupted", e);
        }

        synchronized (this) {
            addEvent(new StopFlagEvent());
        }

        KailleraEventListener l = eventDispatcher.getListener();
        if (l != null) {
            l.stop();
        }
    }

    public synchronized void droppedPacket() {
        if (game != null)
            game.droppedPacket(this);
    }

    public boolean isRunning() {
        return isRunning;
    }

    // server actions

    public synchronized void login() throws PingTimeException, ClientAddressException,
            ConnectionTypeException, UserNameException, LoginException {
        updateLastActivity();
        server.login(this);
    }

    public synchronized void chat(String message) throws ChatException, FloodException {
        updateLastActivity();
        server.chat(this, message);
        lastChatTime = System.currentTimeMillis();
    }

    public synchronized void gameKick(int userID) throws GameKickException {
        updateLastActivity();

        if (game == null) {
            log.warn(this + " kick User " + userID + " failed: Not in a game");
            throw new GameKickException(EmuLang.getString("KailleraUserImpl.KickErrorNotInGame"));
        }

        game.kick(this, userID);
    }

    public synchronized KailleraGame createGame(String romName)
            throws CreateGameException, FloodException {
        updateLastActivity();

        if (getStatus() == KailleraUser.STATUS_PLAYING) {
            log.warn(this + " create game failed: User status is Playing!");
            throw new CreateGameException(
                    EmuLang.getString("KailleraUserImpl.CreateGameErrorAlreadyInGame"));
        } else if (getStatus() == KailleraUser.STATUS_CONNECTING) {
            log.warn(this + " create game failed: User status is Connecting!");
            throw new CreateGameException(
                    EmuLang.getString("KailleraUserImpl.CreateGameErrorNotFullyConnected"));
        }

        KailleraGame game = server.createGame(this, romName);
        lastCreateGameTime = System.currentTimeMillis();
        return game;
    }

    public synchronized void quit(String message)
            throws QuitException, DropGameException, QuitGameException, CloseGameException {
        updateLastActivity();
        server.quit(this, message);
        loggedIn = false;
    }

    public synchronized KailleraGame joinGame(int gameID) throws JoinGameException {
        updateLastActivity();

        if (game != null) {
            log.warn(this + " join game failed: Already in: " + game);
            throw new JoinGameException(
                    EmuLang.getString("KailleraUserImpl.JoinGameErrorAlreadyInGame"));
        }
        if (getStatus() == KailleraUser.STATUS_PLAYING) {
            log.warn(this + " join game failed: User status is Playing!");
            throw new JoinGameException(
                    EmuLang.getString("KailleraUserImpl.JoinGameErrorAnotherGameRunning"));
        } else if (getStatus() == KailleraUser.STATUS_CONNECTING) {
            log.warn(this + " join game failed: User status is Connecting!");
            throw new JoinGameException(
                    EmuLang.getString("KailleraUserImpl.JoinGameErrorNotFullConnected"));
        }

        KailleraGameImpl game = (KailleraGameImpl) server.getGame(gameID);
        if (game == null) {
            log.warn(this + " join game failed: Game " + gameID + " does not exist!");
            throw new JoinGameException(
                    EmuLang.getString("KailleraUserImpl.JoinGameErrorDoesNotExist"));
        }

        playerNumber = game.join(this);
        setGame(game);

        gameDataErrorTime = -1;

        return game;
    }

    // game actions
    public synchronized void gameChat(String message, int messageID) throws GameChatException {
        updateLastActivity();

        // Capture volatile field to local variable for thread-safety
        KailleraGameImpl currentGame = game;

        if (currentGame == null) {
            log.warn(this + " game chat failed: Not in a game");
            throw new GameChatException(
                    EmuLang.getString("KailleraUserImpl.GameChatErrorNotInGame"));
        }

        currentGame.chat(this, message);
    }

    public synchronized void dropGame() throws DropGameException {
        updateLastActivity();
        setStatus(KailleraUser.STATUS_IDLE);

        // Capture volatile fields to local variables for thread-safety
        KailleraGameImpl currentGame = game;
        int currentPlayerNumber = playerNumber;

        if (currentGame != null)
            currentGame.drop(this, currentPlayerNumber);
        else
            log.debug(this + " drop game failed: Not in a game");
    }

    public synchronized void quitGame()
            throws DropGameException, QuitGameException, CloseGameException {
        updateLastActivity();

        // Capture volatile fields to local variables for thread-safety
        KailleraGameImpl currentGame = game;
        int currentPlayerNumber = playerNumber;

        if (currentGame == null) {
            log.debug(this + " quit game failed: Not in a game");
            // throw new QuitGameException("You are not in a game!");
            // A game may have been closed underneath us, leaving the user detached
            // while still marked as playing. Let them create/join games again.
            if (status == KailleraUser.STATUS_PLAYING)
                setStatus(KailleraUser.STATUS_IDLE);
            return;
        }

        if (status == KailleraUser.STATUS_PLAYING) {
            currentGame.drop(this, currentPlayerNumber);
            setStatus(KailleraUser.STATUS_IDLE);
        }

        currentGame.quit(this, currentPlayerNumber);

        if (status != KailleraUser.STATUS_IDLE)
            setStatus(KailleraUser.STATUS_IDLE);

        setGame(null);
        addEvent(new UserQuitGameEvent(currentGame, this));
    }

    public synchronized void startGame() throws StartGameException {
        updateLastActivity();

        // Capture volatile field to local variable for thread-safety
        KailleraGameImpl currentGame = game;

        if (currentGame == null) {
            log.warn(this + " start game failed: Not in a game");
            throw new StartGameException(
                    EmuLang.getString("KailleraUserImpl.StartGameErrorNotInGame"));
        }

        currentGame.start(this);
    }

    public synchronized void playerReady() throws UserReadyException {
        updateLastActivity();

        // Capture volatile fields to local variables for thread-safety
        KailleraGameImpl currentGame = game;
        int currentPlayerNumber = playerNumber;

        if (currentGame == null) {
            log.warn(this + " player ready failed: Not in a game");
            throw new UserReadyException(
                    EmuLang.getString("KailleraUserImpl.PlayerReadyErrorNotInGame"));
        }

        currentGame.ready(this, currentPlayerNumber);
    }

    public void addGameData(byte[] data) throws GameDataException {
        updateLastActivity();

        // Capture volatile fields to local variables for thread-safety
        KailleraGameImpl currentGame = game;
        int currentPlayerNumber = playerNumber;

        try {
            if (currentGame == null)
                throw new GameDataException(
                        EmuLang.getString("KailleraUserImpl.GameDataErrorNotInGame"), data,
                        getConnectionType(), 1, 1);
            currentGame.addData(this, currentPlayerNumber, data);
            gameDataErrorTime = 0;
        } catch (GameDataException e) {
            // this should be warn level, but it creates tons of lines in the log
            log.debug(this + " add game data failed: " + e.getMessage());

            // i'm going to reflect the game data packet back at the user to prevent game
            // lockups,
            // but this uses extra bandwidth, so we'll set a counter to prevent people from
            // leaving
            // games running for a long time in this state

            if (gameDataErrorTime > 0) {
                if ((System.currentTimeMillis() - gameDataErrorTime) > 30000) // give the user time
                                                                              // to close the game
                {
                    // this should be warn level, but it creates tons of lines in the log
                    log.debug(this + ": error game data exceeds drop timeout!");
                    // e.setReflectData(false);
                    throw new GameDataException(e.getMessage());
                } else {
                    // e.setReflectData(true);
                    throw e;
                }
            } else {
                gameDataErrorTime = System.currentTimeMillis();
                // e.setReflectData(true);
                throw e;
            }
        }
    }

    private static final int CRITICAL_EVENT_TIMEOUT_MS = 100;

    @Override
    public void addEvent(KailleraEvent event) {
        if (event == null) {
            log.error(this + ": ignoring null event!");
            return;
        }

        // Critical events get timeout-based blocking to reduce drop probability
        boolean isCritical = event instanceof GameStartedEvent || event instanceof AllReadyEvent
                || event instanceof GameDataEvent;

        boolean added = false;
        if (isCritical) {
            try {
                added = eventQueue.offer(event, CRITICAL_EVENT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn(this + ": interrupted while adding critical event: "
                        + event.getClass().getSimpleName());
                return;
            }
        } else {
            added = eventQueue.offer(event);
        }

        if (!added) {
            droppedEventsCount++;
            if (isCritical) {
                log.error(this + ": CRITICAL event queue full after " + CRITICAL_EVENT_TIMEOUT_MS
                        + "ms timeout, dropping: " + event.getClass().getSimpleName());
            } else if (droppedEventsCount <= DROPPED_EVENTS_LOG_THRESHOLD) {
                log.warn(this + ": event queue full, dropping: " + event.getClass().getSimpleName()
                        + " (dropped " + droppedEventsCount + " events)");
            } else if (droppedEventsCount == DROPPED_EVENTS_LOG_THRESHOLD + 1) {
                log.warn(this + ": suppressing further event drop warnings");
            }
        } else {
            // Reset counter on successful add
            droppedEventsCount = 0;

            // Warn when queue is approaching capacity
            int currentSize = eventQueue.size();
            if (currentSize >= QUEUE_WARNING_THRESHOLD) {
                log.warn("{}: event queue at {}% capacity ({}/{})", this,
                        (currentSize * 100) / MAX_EVENT_QUEUE_SIZE, currentSize,
                        MAX_EVENT_QUEUE_SIZE);
            }
        }
    }

    @Override
    public int getEventQueueSize() {
        return eventQueue.size();
    }

    @Override
    public int getDroppedEventsCount() {
        return droppedEventsCount;
    }

    public void run() {
        isRunning = true;
        log.debug(this + " thread running...");

        try {
            while (!stopFlag) {
                KailleraEvent event = eventQueue.poll(200, TimeUnit.MILLISECONDS);

                if (event == null)
                    continue;
                else if (event instanceof StopFlagEvent)
                    break;

                try {
                    eventDispatcher.dispatch(event);
                } catch (RuntimeException e) {
                    // One failed event (for example a malformed outbound message) must
                    // not terminate this user's entire event-processing thread.
                    log.error(this + " failed to dispatch event: " + event, e);
                }

                if (event instanceof GameStartedEvent) {
                    setStatus(KailleraUser.STATUS_PLAYING);
                } else if (event instanceof UserQuitEvent quitEvent
                        && quitEvent.getUser().equals(this)) {
                    stop();
                }
            }
        } catch (InterruptedException e) {
            log.error(this + " thread interrupted!");
        } catch (Throwable e) {
            log.error(this + " thread caught unexpected exception!", e);
        } finally {
            isRunning = false;
            log.debug(this + " thread exiting...");
        }
    }

    private static final class StopFlagEvent implements KailleraEvent {
        public String toString() {
            return "StopFlagEvent";
        }
    }

}
