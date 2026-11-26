package su.kidoz.kaillera.model.impl;

import java.util.Date;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.kidoz.kaillera.model.AutoFireDetector;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.master.StatsCollector;
import su.kidoz.kaillera.metrics.GameMetricsCollector;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.event.AllReadyEvent;
import su.kidoz.kaillera.model.event.GameChatEvent;
import su.kidoz.kaillera.model.event.GameDataEvent;
import su.kidoz.kaillera.model.event.GameEvent;
import su.kidoz.kaillera.model.event.GameInfoEvent;
import su.kidoz.kaillera.model.event.GameStartedEvent;
import su.kidoz.kaillera.model.event.GameStatusChangedEvent;
import su.kidoz.kaillera.model.event.GameTimeoutEvent;
import su.kidoz.kaillera.model.event.PlayerDesynchEvent;
import su.kidoz.kaillera.model.event.UserDroppedGameEvent;
import su.kidoz.kaillera.model.event.UserJoinedGameEvent;
import su.kidoz.kaillera.model.event.UserQuitGameEvent;
import su.kidoz.kaillera.model.exception.CloseGameException;
import su.kidoz.kaillera.model.exception.DropGameException;
import su.kidoz.kaillera.model.exception.GameChatException;
import su.kidoz.kaillera.model.exception.GameDataException;
import su.kidoz.kaillera.model.exception.GameKickException;
import su.kidoz.kaillera.model.exception.JoinGameException;
import su.kidoz.kaillera.model.exception.QuitGameException;
import su.kidoz.kaillera.model.exception.StartGameException;
import su.kidoz.kaillera.model.exception.UserReadyException;
import su.kidoz.kaillera.model.validation.InputValidator;
import su.kidoz.util.EmuLang;
import su.kidoz.util.EmuLinkerExecutor;
import su.kidoz.util.ThrottledLogger;

public final class KailleraGameImpl implements KailleraGame {
    private static final Logger log = LoggerFactory.getLogger(KailleraGameImpl.class);
    // A logged-in client can send denied requests at packet rate, so these warnings
    // must stay bounded.
    private static final ThrottledLogger DENIED_LOG = new ThrottledLogger(log,
            "denied game request");
    private static final ThrottledLogger DROPPED_LOG = new ThrottledLogger(log,
            "player drop request");

    // ReadWriteLock for concurrent read access, exclusive write access
    private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Lock readLock = rwLock.readLock();
    private final Lock writeLock = rwLock.writeLock();

    private final int id;
    private final String romName;
    private final String toString;
    private final Date startDate;

    private final int bufferSize;
    private final int timeoutMillis;
    private final int desynchTimeouts;

    private final KailleraServerImpl server;
    private final KailleraUserImpl owner;
    private List<KailleraUserImpl> players = new CopyOnWriteArrayList<KailleraUserImpl>();
    private StatsCollector statsCollector;
    private GameMetricsCollector gameMetricsCollector;

    private List<Integer> kickedUsers = new CopyOnWriteArrayList<Integer>();

    private volatile int status = KailleraGame.STATUS_WAITING;
    private volatile boolean synched = false;
    private volatile int actionsPerMessage;
    private volatile PlayerActionQueue[] playerActionQueues;
    // Every player must use the same action stride, or the combined frame is
    // misaligned for everyone after the first slot.
    private volatile int acceptedBytesPerAction = -1;
    private AutoFireDetector autoFireDetector;

    // Frame assembly waits for every player's input. It must never run on the
    // sender's UDP receive thread, or one lagging player stalls that client's
    // keepalives, chat, and new input. Tasks run serially per game so frames are
    // delivered in submission order.
    private final Queue<Runnable> assemblyTasks = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean assemblyDrainScheduled = new AtomicBoolean();

    public KailleraGameImpl(int gameID, String romName, KailleraUserImpl owner,
            KailleraServerImpl server, int bufferSize, int timeoutMillis, int desynchTimeouts) {
        this.id = gameID;
        this.romName = romName;
        this.owner = owner;
        this.server = server;
        this.actionsPerMessage = owner.getConnectionType();
        this.bufferSize = bufferSize;
        this.timeoutMillis = timeoutMillis;
        this.desynchTimeouts = desynchTimeouts;

        String displayName = InputValidator.sanitize(romName);
        if (displayName == null || displayName.isEmpty())
            displayName = "?";
        toString = "Game" + id + "("
                + (displayName.length() > 15 ? (displayName.substring(0, 15) + "...") : displayName)
                + ")";
        startDate = new Date();

        statsCollector = server.getStatsCollector();
        gameMetricsCollector = server.getGameMetricsCollector();
        autoFireDetector = server.getAutoFireDetector(this);
    }

    public int getID() {
        return id;
    }

    public String getRomName() {
        return romName;
    }

    public Date getStartDate() {
        return new Date(startDate.getTime());
    }

    public KailleraUser getOwner() {
        return owner;
    }

    public int getPlayerNumber(KailleraUser user) {
        int index = players.indexOf(user);
        if (index < 0) {
            return -1; // Player not found
        }
        return index + 1;
    }

    public KailleraUser getPlayer(int playerNumber) {
        if (playerNumber <= 0 || playerNumber > players.size()) {
            log.error(this + ": getPlayer(" + playerNumber + ") failed! (size = " + players.size()
                    + ")");
            return null;
        }

        try {
            return players.get(playerNumber - 1);
        } catch (IndexOutOfBoundsException e) {
            log.error(this + ": getPlayer(" + playerNumber + ") index out of bounds", e);
            return null;
        }
    }

    public int getNumPlayers() {
        return players.size();
    }

    public List<KailleraUserImpl> getPlayers() {
        return List.copyOf(players);
    }

    public int getStatus() {
        return status;
    }

    public boolean isSynched() {
        return synched;
    }

    public KailleraServerImpl getServer() {
        return server;
    }

    void setStatus(int status) {
        this.status = status;
        server.addEvent(new GameStatusChangedEvent(server, this));
    }

    public String getClientType() {
        return getOwner().getClientType();
    }

    public String toString() {
        return toString;
    }

    public String toDetailedString() {
        StringBuilder sb = new StringBuilder();
        sb.append("KailleraGame[id=");
        sb.append(getID());
        sb.append(" romName=");
        sb.append(getRomName());
        sb.append(" owner=");
        sb.append(getOwner());
        sb.append(" numPlayers=");
        sb.append(getNumPlayers());
        sb.append(" status=");
        sb.append(KailleraGame.getStatusName(getStatus()));
        sb.append("]");
        return sb.toString();
    }

    int getPlayingCount() {
        int count = 0;
        for (KailleraUserImpl player : players) {
            if (player.getStatus() == KailleraUser.STATUS_PLAYING)
                count++;
        }

        return count;
    }

    int getSynchedCount() {
        if (playerActionQueues == null)
            return 0;

        int count = 0;
        for (int i = 0; i < playerActionQueues.length; i++) {
            if (playerActionQueues[i].isSynched())
                count++;
        }

        return count;

        // return dataQueues.size();
        // return readyCount;
    }

    @Override
    public void addEvent(GameEvent event) {
        for (KailleraUserImpl player : players)
            player.addEvent(event);
    }

    @Override
    public AutoFireDetector getAutoFireDetector() {
        return autoFireDetector;
    }

    public void chat(KailleraUser user, String message) throws GameChatException {
        readLock.lock();
        try {
            if (!players.contains(user)) {
                DENIED_LOG.warn("{}: game chat denied: not in {}", user, this);
                throw new GameChatException(
                        EmuLang.getString("KailleraGameImpl.GameChatErrorNotInGame"));
            }

            if (message == null || message.trim().isEmpty()) {
                throw new GameChatException("Empty message");
            }

            int maxLength = server.getMaxChatLength();
            if (maxLength > 0 && message.length() > maxLength) {
                throw new GameChatException("Chat message too long");
            }

            // The lobby rules apply inside a game as well. Without them a game member
            // can put control characters into every member's client and into the log,
            // and can relay messages at packet rate.
            int access = server.getAccessManager().getAccess(user.getSocketAddress().getAddress());

            // Only elevated and admin clients are exempt from the rules below. A
            // banned session is not: it loses the exemptions the moment the ban
            // applies, not when the maintenance pass disconnects it.
            if (access < AccessManager.ACCESS_NORMAL) {
                DENIED_LOG.warn("{}: game chat denied: Banned", user);
                throw new GameChatException(EmuLang.getString("KailleraServerImpl.DeniedBanned"));
            }

            if (access <= AccessManager.ACCESS_NORMAL) {
                // Silence applies to game chat too; the lobby and game paths must not
                // diverge on who is allowed to speak.
                if (server.getAccessManager().isSilenced(user.getSocketAddress().getAddress())) {
                    DENIED_LOG.warn("{}: game chat denied: Silenced", user);
                    throw new GameChatException(
                            EmuLang.getString("KailleraServerImpl.ChatDeniedSilenced"));
                }

                KailleraUserImpl sender = (KailleraUserImpl) user;
                int floodSeconds = server.getChatFloodTime();
                if (floodSeconds > 0 && System.currentTimeMillis()
                        - sender.getLastGameChatTime() < floodSeconds * 1000L) {
                    DENIED_LOG.warn("{}: game chat denied: flood control", user);
                    throw new GameChatException(
                            EmuLang.getString("KailleraServerImpl.ChatDeniedFloodControl"));
                }

                if (InputValidator.hasIllegalCharacters(message)) {
                    DENIED_LOG.warn("{}: game chat denied: illegal characters", user);
                    throw new GameChatException(
                            EmuLang.getString("KailleraServerImpl.ChatDeniedIllegalCharacters"));
                }
                sender.setLastGameChatTime(System.currentTimeMillis());
            }

            log.info(user + ", " + this + " gamechat: " + InputValidator.sanitizeForLog(message));
            addEvent(new GameChatEvent(this, user, message));
        } finally {
            readLock.unlock();
        }
    }

    @Override
    public void announce(String announcement) {
        readLock.lock();
        try {
            addEvent(new GameInfoEvent(this, announcement));
        } finally {
            readLock.unlock();
        }
    }

    public void kick(KailleraUser user, int userID) throws GameKickException {
        KailleraUserImpl target = null;

        writeLock.lock();
        try {
            if (!user.equals(getOwner())) {
                DENIED_LOG.warn("{}", user + " kick denied: not the owner of " + this);
                throw new GameKickException(
                        EmuLang.getString("KailleraGameImpl.GameKickDeniedNotGameOwner"));
            }

            if (user.getID() == userID) {
                DENIED_LOG.warn("{}", user + " kick denied: attempt to kick self");
                throw new GameKickException(
                        EmuLang.getString("KailleraGameImpl.GameKickDeniedCannotKickSelf"));
            }

            for (KailleraUserImpl player : players) {
                if (player.getID() == userID) {
                    target = player;
                    kickedUsers.add(userID);
                    break;
                }
            }

            if (target == null) {
                DENIED_LOG.warn("{}",
                        user + " kick failed: user " + userID + " not found in: " + this);
                throw new GameKickException(
                        EmuLang.getString("KailleraGameImpl.GameKickErrorUserNotFound"));
            }
        } finally {
            writeLock.unlock();
        }

        // Call quitGame() without holding the game write lock. quitGame() takes the
        // user monitor, while that user's own quit path takes the user monitor and
        // then this game's write lock; holding the write lock here would invert the
        // order and deadlock.
        log.info(user + " kicked: " + userID + " from " + this);
        try {
            target.quitGame();
        } catch (Exception e) {
            // this shouldn't happen
            log.error("Caught exception while making user quit game! This shouldn't happen!", e);
        }
    }

    public int join(KailleraUser user) throws JoinGameException {
        writeLock.lock();
        try {
            if (players.contains(user)) {
                DENIED_LOG.warn("{}", user + " join game denied: already in " + this);
                throw new JoinGameException(
                        EmuLang.getString("KailleraGameImpl.JoinGameErrorAlreadyInGame"));
            }

            if (user.getSocketAddress() == null) {
                log.error(user + " join game denied: socket address not set");
                throw new JoinGameException("User socket address not initialized");
            }

            int access = server.getAccessManager().getAccess(user.getSocketAddress().getAddress());

            // Only elevated and admin clients are exempt from the rules below. A
            // banned session is not: it loses the exemptions the moment the ban
            // applies, not when the maintenance pass disconnects it.
            if (access < AccessManager.ACCESS_NORMAL) {
                DENIED_LOG.warn("{}: join game denied: Banned", user);
                throw new JoinGameException(EmuLang.getString("KailleraServerImpl.DeniedBanned"));
            }

            if (access <= AccessManager.ACCESS_NORMAL && kickedUsers.contains(user.getID())) {
                DENIED_LOG.warn("{}", user + " join game denied: previously kicked: " + this);
                throw new JoinGameException(
                        EmuLang.getString("KailleraGameImpl.JoinGameDeniedPreviouslyKicked"));
            }

            if (access <= AccessManager.ACCESS_NORMAL
                    && getStatus() != KailleraGame.STATUS_WAITING) {
                DENIED_LOG.warn("{}",
                        user + " join game denied: attempt to join game in progress: " + this);
                throw new JoinGameException(
                        EmuLang.getString("KailleraGameImpl.JoinGameDeniedGameIsInProgress"));
            }

            players.add((KailleraUserImpl) user);
            server.addEvent(new GameStatusChangedEvent(server, this));

            log.info(user + " joined: " + this);
            addEvent(new UserJoinedGameEvent(this, user));

            if (user.equals(owner) && autoFireDetector != null
                    && autoFireDetector.getSensitivity() > 0) {
                announce(EmuLang.getString("KailleraGameImpl.AutofireDetectionOn"));
                announce(EmuLang.getString("KailleraGameImpl.AutofireCurrentSensitivity",
                        autoFireDetector.getSensitivity()));
                announce(EmuLang.getString("KailleraGameImpl.GameHelp"));
            }

            return (players.indexOf(user) + 1);
        } finally {
            writeLock.unlock();
        }
    }

    public void start(KailleraUser user) throws StartGameException {
        writeLock.lock();
        try {
            if (!user.equals(getOwner())) {
                DENIED_LOG.warn("{}", user + " start game denied: not the owner of " + this);
                throw new StartGameException(
                        EmuLang.getString("KailleraGameImpl.StartGameDeniedOnlyOwnerMayStart"));
            }

            if (status == KailleraGame.STATUS_SYNCHRONIZING) {
                DENIED_LOG.warn("{}", user + " start game failed: " + this + " status is "
                        + KailleraGame.getStatusName(status));
                throw new StartGameException(
                        EmuLang.getString("KailleraGameImpl.StartGameErrorSynchronizing"));
            } else if (status == KailleraGame.STATUS_PLAYING) {
                DENIED_LOG.warn("{}", user + " start game failed: " + this + " status is "
                        + KailleraGame.getStatusName(status));
                throw new StartGameException(
                        EmuLang.getString("KailleraGameImpl.StartGameErrorStatusIsPlaying"));
            }

            if (user.getSocketAddress() == null) {
                log.error(user + " start game denied: socket address not set");
                throw new StartGameException("User socket address not initialized");
            }

            int access = server.getAccessManager().getAccess(user.getSocketAddress().getAddress());

            // Only elevated and admin clients are exempt from the rule below. A banned
            // session is not: it loses the exemption the moment the ban applies, not
            // when the maintenance pass disconnects it.
            if (access < AccessManager.ACCESS_NORMAL) {
                DENIED_LOG.warn("{}: start game denied: Banned", user);
                throw new StartGameException(EmuLang.getString("KailleraServerImpl.DeniedBanned"));
            }

            if (access <= AccessManager.ACCESS_NORMAL && getNumPlayers() < 2
                    && !server.getAllowSinglePlayer()) {
                DENIED_LOG.warn("{}",
                        user + " start game denied: " + this + " needs at least 2 players");
                throw new StartGameException(EmuLang
                        .getString("KailleraGameImpl.StartGameDeniedSinglePlayerNotAllowed"));
            }

            for (KailleraUser player : players) {
                if (player.getConnectionType() != owner.getConnectionType()) {
                    DENIED_LOG.warn("{}", user + " start game denied: " + this
                            + ": All players must use the same connection type");
                    addEvent(new GameInfoEvent(this, EmuLang.getString(
                            "KailleraGameImpl.StartGameConnectionTypeMismatchInfo",
                            KailleraUser.getConnectionTypeName(owner.getConnectionType()))));
                    throw new StartGameException(EmuLang
                            .getString("KailleraGameImpl.StartGameDeniedConnectionTypeMismatch"));
                }

                if (!player.getClientType().equals(getClientType())) {
                    DENIED_LOG.warn("{}", user + " start game denied: " + this
                            + ": All players must use the same emulator!");
                    addEvent(new GameInfoEvent(this, EmuLang.getString(
                            "KailleraGameImpl.StartGameEmulatorMismatchInfo", getClientType())));
                    throw new StartGameException(
                            EmuLang.getString("KailleraGameImpl.StartGameDeniedEmulatorMismatch"));
                }
            }

            log.info(user + " started: " + this);
            setStatus(KailleraGame.STATUS_SYNCHRONIZING);
            acceptedBytesPerAction = -1;
            assemblyTasks.clear();

            if (autoFireDetector != null)
                autoFireDetector.start(players.size());

            PlayerActionQueue[] queues = new PlayerActionQueue[players.size()];
            for (int i = 0; i < queues.length; i++) {
                KailleraUserImpl player = players.get(i);
                int playerNumber = (i + 1);
                queues[i] = new PlayerActionQueue(playerNumber, player, getNumPlayers(), bufferSize,
                        timeoutMillis, true, readerIndex -> queues[readerIndex].isSynched());
                player.setPlayerNumber(playerNumber);
                log.info(this + ": " + player + " is player number " + playerNumber);

                if (autoFireDetector != null)
                    autoFireDetector.addPlayer(player, playerNumber);
            }
            playerActionQueues = queues;

            if (statsCollector != null)
                statsCollector.gameStarted(server, this);
            if (gameMetricsCollector != null)
                gameMetricsCollector.recordGameStarted(id);

            addEvent(new GameStartedEvent(this));
        } finally {
            writeLock.unlock();
        }
    }

    public void ready(KailleraUser user, int playerNumber) throws UserReadyException {
        writeLock.lock();
        try {
            if (!players.contains(user)) {
                DENIED_LOG.warn("{}", user + " ready game failed: not in " + this);
                throw new UserReadyException(
                        EmuLang.getString("KailleraGameImpl.ReadyGameErrorNotInGame"));
            }

            if (status != KailleraGame.STATUS_SYNCHRONIZING) {
                DENIED_LOG.warn("{}", user + " ready failed: " + this + " status is "
                        + KailleraGame.getStatusName(status));
                throw new UserReadyException(
                        EmuLang.getString("KailleraGameImpl.ReadyGameErrorIncorrectState"));
            }

            if (playerActionQueues == null) {
                log.error(user + " ready failed: " + this + " playerActionQueues == null!");
                throw new UserReadyException(
                        EmuLang.getString("KailleraGameImpl.ReadyGameErrorInternalError"));
            }

            if (playerNumber < 1 || playerNumber > playerActionQueues.length) {
                log.error(user + " ready failed: invalid playerNumber " + playerNumber);
                throw new UserReadyException(
                        EmuLang.getString("KailleraGameImpl.ReadyGameErrorInternalError"));
            }

            log.info(user + " (player " + playerNumber + ") is ready to play: " + this);
            playerActionQueues[(playerNumber - 1)].setSynched(true);

            // Compare against the queue snapshot taken at start(), not the live
            // player list: a departing player must not make the remaining players
            // look ready with a stale slot count.
            if (getSynchedCount() == playerActionQueues.length) {
                log.info(this + " all players are ready: starting...");

                setStatus(KailleraGame.STATUS_PLAYING);
                synched = true;
                if (gameMetricsCollector != null)
                    gameMetricsCollector.recordPlayersSynced();
                addEvent(new AllReadyEvent(this));
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void drop(KailleraUser user, int playerNumber) throws DropGameException {
        writeLock.lock();
        try {
            if (!players.contains(user)) {
                DENIED_LOG.warn("{}", user + " drop game failed: not in " + this);
                throw new DropGameException(
                        EmuLang.getString("KailleraGameImpl.DropGameErrorNotInGame"));
            }

            if (playerActionQueues == null) {
                log.error(user + " drop failed: " + this + " playerActionQueues == null!");
                throw new DropGameException(
                        EmuLang.getString("KailleraGameImpl.DropGameErrorInternalError"));
            }

            if (playerNumber < 1 || playerNumber > playerActionQueues.length) {
                log.error(user + " drop failed: invalid playerNumber " + playerNumber);
                throw new DropGameException(
                        EmuLang.getString("KailleraGameImpl.DropGameErrorInternalError"));
            }

            DROPPED_LOG.info(user + " dropped: " + this);
            playerActionQueues[(playerNumber - 1)].setSynched(false);
            if (gameMetricsCollector != null)
                gameMetricsCollector.recordPlayerDropped();

            if (getSynchedCount() < 2 && synched) {
                synched = false;
                PlayerActionQueue[] queues = playerActionQueues;
                if (queues != null) {
                    for (PlayerActionQueue q : queues)
                        q.setSynched(false);
                }
                log.info(this + ": game desynched: less than 2 players playing!");
            }

            if (autoFireDetector != null)
                autoFireDetector.stop(playerNumber);

            // A drop during an active round invalidates the queue snapshot. Reset
            // the room instead of continuing to assemble frames with a stale slot.
            abortRound("player dropped during an active round");

            addEvent(new UserDroppedGameEvent(this, user, playerNumber));
        } finally {
            writeLock.unlock();
        }
    }

    public void quit(KailleraUser user, int playerNumber)
            throws DropGameException, QuitGameException, CloseGameException {
        writeLock.lock();
        try {
            if (!players.remove(user)) {
                DENIED_LOG.warn("{}", user + " quit game failed: not in " + this);
                throw new QuitGameException(
                        EmuLang.getString("KailleraGameImpl.QuitGameErrorNotInGame"));
            }

            log.info(user + " quit: " + this);

            // The queue snapshot contains the departed player's slot. Any active
            // round must end now, or the remaining clients decode a malformed frame
            // and the room can never become ready again.
            abortRound("player quit during an active round");

            addEvent(new UserQuitGameEvent(this, user));
        } finally {
            writeLock.unlock();
        }

        // Call server methods outside the lock to avoid potential deadlocks
        if (user.equals(owner))
            server.closeGame(this, user);
        else
            server.addEvent(new GameStatusChangedEvent(server, this));
    }

    void close(KailleraUser user) throws CloseGameException {
        writeLock.lock();
        try {
            if (!user.equals(owner)) {
                DENIED_LOG.warn("{}", user + " close game denied: not the owner of " + this);
                throw new CloseGameException(
                        EmuLang.getString("KailleraGameImpl.CloseGameErrorNotGameOwner"));
            }

            if (synched) {
                synched = false;
                PlayerActionQueue[] queues = playerActionQueues;
                if (queues != null) {
                    for (PlayerActionQueue q : queues)
                        q.setSynched(false);
                }
                log.info(this + ": game desynched: game closed!");
            }

            playerActionQueues = null;
            acceptedBytesPerAction = -1;
            assemblyTasks.clear();

            for (KailleraUserImpl player : players) {
                // Detached players must not stay in STATUS_PLAYING, otherwise
                // createGame()/joinGame() reject them for the rest of the session.
                if (player.getStatus() == KailleraUser.STATUS_PLAYING)
                    player.setStatus(KailleraUser.STATUS_IDLE);
                player.setPlayerNumber(-1);
                player.setGame(null);
            }

            if (autoFireDetector != null)
                autoFireDetector.stop();

            players.clear();
        } finally {
            writeLock.unlock();
        }
    }

    public void droppedPacket(KailleraUser user) {
        writeLock.lock();
        try {
            if (!synched)
                return;

            int playerNumber = user.getPlayerNumber();
            if (playerActionQueues != null && playerNumber >= 1
                    && playerNumber <= playerActionQueues.length
                    && playerActionQueues[(playerNumber - 1)].isSynched()) {
                playerActionQueues[(playerNumber - 1)].setSynched(false);
                log.info(this + ": " + user + ": player desynched: dropped a packet!");
                if (gameMetricsCollector != null)
                    gameMetricsCollector.recordPlayerDesynced();
                addEvent(new PlayerDesynchEvent(this, user, EmuLang.getString(
                        "KailleraGameImpl.DesynchDetectedDroppedPacket", user.getName())));

                if (getSynchedCount() < 2 && synched) {
                    synched = false;
                    PlayerActionQueue[] queues = playerActionQueues;
                    if (queues != null) {
                        for (PlayerActionQueue q : queues)
                            q.setSynched(false);
                    }
                    log.info(this + ": game desynched: less than 2 players synched!");
                }
            }
        } finally {
            writeLock.unlock();
        }
    }

    public void addData(KailleraUser user, int playerNumber, byte[] data) throws GameDataException {
        PlayerActionQueue[] queues;
        int actions;
        int numPlayers;
        int bytesPerAction;

        writeLock.lock();
        try {
            queues = playerActionQueues;
            actions = actionsPerMessage; // local copy for thread-safety

            if (queues == null)
                return;

            if (actions <= 0) {
                log.error(this + ": addData failed: actionsPerMessage is " + actions);
                return;
            }

            numPlayers = queues.length;

            // Validate playerNumber bounds
            if (playerNumber < 1 || playerNumber > numPlayers) {
                log.error(this + ": addData failed: invalid playerNumber " + playerNumber
                        + " (max: " + numPlayers + ")");
                throw new GameDataException("Invalid player number: " + playerNumber, data, actions,
                        playerNumber, numPlayers);
            }

            if (!synched) {
                throw new GameDataException(EmuLang.getString("KailleraGameImpl.DesynchedWarning"),
                        data, actions, playerNumber, numPlayers);
            }

            // Each payload must contain a whole number of actions, and it must be able
            // to fit in the per-player ring buffer. Truncating the stride or letting a
            // single oversized payload wrap the ring corrupts framing for every player.
            if (data.length <= 0 || data.length % actions != 0 || data.length > bufferSize) {
                log.warn(this + ": addData failed: invalid payload length " + data.length
                        + " (actions: " + actions + ", buffer: " + bufferSize + ")");
                throw new GameDataException("Invalid game data length: " + data.length);
            }

            bytesPerAction = data.length / actions;

            // A frame assembled with one player's stride is misaligned for every
            // client that expects another. The first accepted payload fixes the
            // stride for the round; everyone after it must agree.
            int expectedStride = acceptedBytesPerAction;
            if (expectedStride < 0) {
                acceptedBytesPerAction = bytesPerAction;
            } else if (expectedStride != bytesPerAction) {
                log.warn(this + ": addData failed: inconsistent action size " + bytesPerAction
                        + " (expected " + expectedStride + ")");
                throw new GameDataException("Inconsistent game data action size");
            }

            // Check for integer overflow before array allocation
            if (numPlayers > Integer.MAX_VALUE / data.length) {
                log.error(this + ": addData failed: array size overflow");
                throw new GameDataException("Invalid data size");
            }
        } finally {
            writeLock.unlock();
        }

        // Enqueue and assemble outside the game write lock. Holding it while waiting
        // for every player's queue would block the other players' UDP threads from
        // enqueueing their own actions, so the wait could never be satisfied.
        if (!queues[(playerNumber - 1)].addActions(data)) {
            throw new GameDataException(EmuLang.getString("KailleraGameImpl.DesynchedWarning"),
                    data, actions, playerNumber, numPlayers);
        }

        if (autoFireDetector != null)
            autoFireDetector.addData(playerNumber, data, bytesPerAction);

        // Assemble on the game's serial worker, not the sender's receive thread.
        PlayerActionQueue[] queuesForFrame = queues;
        int actionsForFrame = actions;
        int numPlayersForFrame = numPlayers;
        int bytesPerActionForFrame = bytesPerAction;
        byte[] sourceData = data;
        scheduleAssembly(() -> assembleFrame(user, queuesForFrame, actionsForFrame,
                numPlayersForFrame, bytesPerActionForFrame, sourceData, playerNumber));
    }

    private void scheduleAssembly(Runnable task) {
        assemblyTasks.add(task);
        if (!assemblyDrainScheduled.compareAndSet(false, true))
            return;

        EmuLinkerExecutor pool = server.getThreadPool();
        if (pool == null) {
            // Unit tests construct the game with a mocked server and no executor.
            // Production always has the virtual-thread pool.
            drainAssembly();
            return;
        }

        try {
            pool.execute(this::drainAssembly);
        } catch (RuntimeException e) {
            // The pool is shutting down; queued frames can never be delivered.
            assemblyDrainScheduled.set(false);
            assemblyTasks.clear();
            log.debug(this + ": frame assembly rejected during shutdown", e);
        }
    }

    private void drainAssembly() {
        while (true) {
            Runnable task = assemblyTasks.poll();
            if (task == null) {
                assemblyDrainScheduled.set(false);
                // A producer may have added work between poll() and clearing the
                // flag; only one drainer may continue.
                if (assemblyTasks.isEmpty() || !assemblyDrainScheduled.compareAndSet(false, true)) {
                    return;
                }
                continue;
            }

            try {
                task.run();
            } catch (RuntimeException e) {
                log.error(this + ": frame assembly failed", e);
            }
        }
    }

    private void assembleFrame(KailleraUser user, PlayerActionQueue[] queues, int actions,
            int numPlayers, int bytesPerAction, byte[] sourceData, int playerNumber) {
        byte[] response = new byte[numPlayers * sourceData.length];
        // Consecutive timeouts are counted per lagged player. One assembler's
        // wait on player A must not advance the desync count of player B.
        int[] timeoutCounts = new int[numPlayers];

        for (int actionCounter = 0; actionCounter < actions; actionCounter++) {
            for (int playerCounter = 0; playerCounter < numPlayers; playerCounter++) {
                while (synched && playerActionQueues == queues) {
                    try {
                        queues[playerCounter]
                                .getAction(playerNumber, response,
                                        ((actionCounter * (numPlayers * bytesPerAction))
                                                + (playerCounter * bytesPerAction)),
                                        bytesPerAction);
                        timeoutCounts[playerCounter] = 0;
                        break;
                    } catch (QueueDesyncedException e) {
                        // The slot stays the zero it was allocated as. Every
                        // assembler takes this path when that player has no
                        // committed input, so they emit the same frame.
                        break;
                    } catch (PlayerTimeoutException e) {
                        e.setTimeoutNumber(++timeoutCounts[playerCounter]);
                        handleTimeout(e);
                    }
                }
            }
        }

        // A restart or abort replaced the queue array; a response assembled against
        // the old round must not be delivered to the new one.
        if (playerActionQueues != queues)
            return;

        if (!synched) {
            // Preserve the old desync response: reflect the sender's input so the
            // client does not wait forever for a frame that will not arrive.
            GameDataException failure = new GameDataException(
                    EmuLang.getString("KailleraGameImpl.DesynchedWarning"), sourceData, actions,
                    playerNumber, numPlayers);
            if (failure.hasResponse() && user.getGame() == this)
                ((KailleraUserImpl) user).addEvent(new GameDataEvent(this, failure.getResponse()));
            return;
        }

        if (user.getGame() == this)
            ((KailleraUserImpl) user).addEvent(new GameDataEvent(this, response));
    }

    /**
     * Ends the current synchronized round and returns the room to the waiting
     * state. The caller must hold the game write lock.
     *
     * <p>
     * The player list and the queue snapshot are only valid together. A membership
     * change during SYNCHRONIZING or PLAYING invalidates every slot number, so the
     * round is discarded and the room is restartable instead of corrupting frames
     * or waiting forever for a departed player.
     */
    private void abortRound(String reason) {
        boolean active = synched || playerActionQueues != null
                || status == KailleraGame.STATUS_SYNCHRONIZING
                || status == KailleraGame.STATUS_PLAYING;
        if (!active)
            return;

        synched = false;
        PlayerActionQueue[] queues = playerActionQueues;
        playerActionQueues = null;
        acceptedBytesPerAction = -1;
        assemblyTasks.clear();
        if (queues != null) {
            for (PlayerActionQueue q : queues)
                q.setSynched(false);
        }

        if (autoFireDetector != null)
            autoFireDetector.stop();

        for (KailleraUserImpl player : players) {
            player.setPlayerNumber(-1);
            if (player.getStatus() == KailleraUser.STATUS_PLAYING)
                player.setStatus(KailleraUser.STATUS_IDLE);
        }

        if (status != KailleraGame.STATUS_WAITING)
            setStatus(KailleraGame.STATUS_WAITING);

        log.info(this + ": round aborted: " + reason);
    }

    // Called from addData(); acquires the write lock itself.
    private void handleTimeout(PlayerTimeoutException e) {
        writeLock.lock();
        try {
            if (!synched)
                return;

            int playerNumber = e.getPlayerNumber();
            int timeoutNumber = e.getTimeoutNumber();

            if (playerActionQueues == null || playerNumber < 1
                    || playerNumber > playerActionQueues.length) {
                log.error(this + ": handleTimeout: invalid playerNumber " + playerNumber);
                return;
            }

            PlayerActionQueue playerActionQueue = playerActionQueues[(playerNumber - 1)];

            if (!playerActionQueue.isSynched() || e.equals(playerActionQueue.getLastTimeout()))
                return;

            playerActionQueue.setLastTimeout(e);

            KailleraUser player = e.getPlayer();
            if (timeoutNumber < desynchTimeouts) {
                log.info(this + ": " + player + ": Timeout #" + timeoutNumber);
                addEvent(new GameTimeoutEvent(this, player, timeoutNumber));
            } else {
                log.info(this + ": " + player + ": Timeout #" + timeoutNumber);
                playerActionQueue.setSynched(false);
                log.info(this + ": " + player + ": player desynched: Lagged!");
                if (gameMetricsCollector != null)
                    gameMetricsCollector.recordPlayerDesynced();
                addEvent(new PlayerDesynchEvent(this, player, EmuLang.getString(
                        "KailleraGameImpl.DesynchDetectedPlayerLagged", player.getName())));

                if (getSynchedCount() < 2) {
                    synched = false;
                    PlayerActionQueue[] queues = playerActionQueues;
                    if (queues != null) {
                        for (PlayerActionQueue q : queues)
                            q.setSynched(false);
                    }
                    log.info(this + ": game desynched: less than 2 players synched!");
                }
            }
        } finally {
            writeLock.unlock();
        }
    }
}
