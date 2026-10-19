package su.kidoz.kaillera.load;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.messaging.ParseException;
import su.kidoz.kaillera.controller.v086.protocol.AllReady;
import su.kidoz.kaillera.controller.v086.protocol.CachedGameData;
import su.kidoz.kaillera.controller.v086.protocol.Chat_Request;
import su.kidoz.kaillera.controller.v086.protocol.ClientACK;
import su.kidoz.kaillera.controller.v086.protocol.CreateGame_Notification;
import su.kidoz.kaillera.controller.v086.protocol.CreateGame_Request;
import su.kidoz.kaillera.controller.v086.protocol.GameChat_Notification;
import su.kidoz.kaillera.controller.v086.protocol.GameChat_Request;
import su.kidoz.kaillera.controller.v086.protocol.GameData;
import su.kidoz.kaillera.controller.v086.protocol.GameKick;
import su.kidoz.kaillera.controller.v086.protocol.GameStatus;
import su.kidoz.kaillera.controller.v086.protocol.InformationMessage;
import su.kidoz.kaillera.controller.v086.protocol.JoinGame_Notification;
import su.kidoz.kaillera.controller.v086.protocol.JoinGame_Request;
import su.kidoz.kaillera.controller.v086.protocol.KeepAlive;
import su.kidoz.kaillera.controller.v086.protocol.PlayerDrop_Notification;
import su.kidoz.kaillera.controller.v086.protocol.PlayerDrop_Request;
import su.kidoz.kaillera.controller.v086.protocol.QuitGame_Notification;
import su.kidoz.kaillera.controller.v086.protocol.QuitGame_Request;
import su.kidoz.kaillera.controller.v086.protocol.Quit_Request;
import su.kidoz.kaillera.controller.v086.protocol.ServerACK;
import su.kidoz.kaillera.controller.v086.protocol.ServerStatus;
import su.kidoz.kaillera.controller.v086.protocol.StartGame_Notification;
import su.kidoz.kaillera.controller.v086.protocol.StartGame_Request;
import su.kidoz.kaillera.controller.v086.protocol.UserInformation;
import su.kidoz.kaillera.controller.v086.protocol.UserJoined;
import su.kidoz.kaillera.controller.v086.protocol.V086Bundle;
import su.kidoz.kaillera.controller.v086.protocol.V086BundleFormatException;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.util.ClientGameDataCache;

/**
 * UDP-based Kaillera v0.86 client for integration and load tests.
 *
 * <p>
 * Implements the protocol used by the server: HELLO handshake, login, lobby
 * chat, keep-alives, quit, and the complete game flow (create, join, start,
 * ready, game data, cached game data, game chat, drop, kick, quit). Incoming
 * bundles are parsed with the server's own {@link V086Bundle} and message
 * classes, so tests exercise the real wire format in both directions.
 */
public final class UdpKailleraClient implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(UdpKailleraClient.class);

    // Protocol constants
    private static final String HELLO_PREFIX = "HELLO";
    private static final String HELLOD00D_PREFIX = "HELLOD00D";
    private static final String PROTOCOL_VERSION = "0.83";
    private static final int MAX_PACKET_SIZE = 2048;
    private static final int CACHE_SIZE = 256;

    // Connection types
    public static final byte CONNECTION_LAN = 1;
    public static final byte CONNECTION_EXCELLENT = 2;
    public static final byte CONNECTION_GOOD = 3;
    public static final byte CONNECTION_AVERAGE = 4;
    public static final byte CONNECTION_LOW = 5;
    public static final byte CONNECTION_BAD = 6;

    private final String serverHost;
    private final int connectPort;
    private final String clientName;
    private final String clientType;
    private final byte connectionType;

    private DatagramChannel channel;
    private InetSocketAddress serverAddress;
    private int assignedPort;
    private final AtomicInteger messageNumber = new AtomicInteger(0);
    private volatile boolean connected = false;
    private volatile boolean loggedIn = false;

    private final ByteBuffer sendBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
    private final ByteBuffer receiveBuffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
    private final ClientGameDataCache receivedDataCache = new ClientGameDataCache(CACHE_SIZE);

    // Session state
    private volatile int userId = -1;
    private volatile int createdGameId = -1;
    private volatile int gameId = -1;
    private volatile String gameRomName;
    private volatile boolean inGame = false;
    private volatile boolean gameStarted = false;
    private volatile boolean allReady = false;
    private volatile boolean quitGameReceived = false;
    private volatile int playerNumber = -1;
    private volatile int numPlayers = -1;
    private volatile byte gameStatus = -1;
    private volatile String lastInformationMessage;
    private volatile String lastDroppedPlayer;
    private volatile int lastDroppedPlayerNumber = -1;
    private volatile String lastQuitGameUserName;
    private volatile int lastQuitGameUserID = -1;

    private final List<byte[]> receivedGameData = new CopyOnWriteArrayList<>();
    private final List<String> gameChatMessages = new CopyOnWriteArrayList<>();

    public UdpKailleraClient(String serverHost, int connectPort, String clientName) {
        this(serverHost, connectPort, clientName, "LoadTestClient/1.0", CONNECTION_LAN);
    }

    public UdpKailleraClient(String serverHost, int connectPort, String clientName,
            String clientType, byte connectionType) {
        this.serverHost = serverHost;
        this.connectPort = connectPort;
        this.clientName = clientName;
        this.clientType = clientType;
        this.connectionType = connectionType;
    }

    /**
     * Connects to the server and performs the HELLO handshake.
     *
     * @param timeoutMs
     *            timeout in milliseconds
     * @return true if connected successfully
     */
    public boolean connect(long timeoutMs) throws IOException {
        // Use IPv4 explicitly to avoid IPv6 issues
        channel = DatagramChannel.open(StandardProtocolFamily.INET);
        channel.configureBlocking(true);
        channel.socket().setSoTimeout((int) timeoutMs);

        // Bind to a local port so we can receive responses
        channel.bind(null);
        log.debug("{}: Bound to local port {}", clientName,
                ((InetSocketAddress) channel.getLocalAddress()).getPort());

        // Send HELLO to connect port
        InetSocketAddress connectAddress = new InetSocketAddress(serverHost, connectPort);
        sendBuffer.clear();
        sendBuffer
                .put((HELLO_PREFIX + PROTOCOL_VERSION + "\0").getBytes(StandardCharsets.US_ASCII));
        sendBuffer.flip();
        channel.send(sendBuffer, connectAddress);

        log.debug("{}: Sent HELLO to {}:{}", clientName, serverHost, connectPort);

        // Receive HELLOD00D with assigned port
        receiveBuffer.clear();
        try {
            channel.receive(receiveBuffer);
        } catch (ClosedChannelException e) {
            log.debug("{}: Channel closed during receive", clientName);
            return false;
        }
        receiveBuffer.flip();

        if (receiveBuffer.remaining() == 0) {
            log.error("{}: No response received (timeout)", clientName);
            return false;
        }

        byte[] responseBytes = new byte[receiveBuffer.remaining()];
        receiveBuffer.get(responseBytes);
        String response = new String(responseBytes, StandardCharsets.US_ASCII);

        log.debug("{}: Response string: {}", clientName, response.replace("\0", "\\0"));

        if (!response.startsWith(HELLOD00D_PREFIX)) {
            log.error("{}: Invalid response: {}", clientName, response);
            return false;
        }

        // Parse assigned port
        String portStr = response.substring(HELLOD00D_PREFIX.length());
        if (portStr.endsWith("\0")) {
            portStr = portStr.substring(0, portStr.length() - 1);
        }
        assignedPort = Integer.parseInt(portStr);

        serverAddress = new InetSocketAddress(serverHost, assignedPort);
        connected = true;

        log.debug("{}: Connected, assigned port {}", clientName, assignedPort);
        return true;
    }

    /**
     * Logs in to the server with user information.
     *
     * @param timeoutMs
     *            timeout in milliseconds
     * @return true if login successful
     */
    public boolean login(long timeoutMs) throws IOException, MessageFormatException {
        if (!connected) {
            throw new IllegalStateException("Not connected");
        }

        sendMessages(
                new UserInformation(nextMessageNumber(), clientName, clientType, connectionType));

        return waitUntil(() -> loggedIn, timeoutMs);
    }

    /**
     * Waits until this client learns its server-assigned user ID.
     */
    public boolean waitForUserId(long timeoutMs) throws IOException {
        return waitUntil(() -> userId >= 0, timeoutMs);
    }

    /**
     * Sends a lobby chat message.
     */
    public void chat(String message) throws IOException, MessageFormatException {
        requireLoggedIn();
        sendMessages(new Chat_Request(nextMessageNumber(), message));
    }

    // Game flow - send side

    public void createGame(String romName) throws IOException, MessageFormatException {
        requireLoggedIn();
        createdGameId = -1;
        sendMessages(new CreateGame_Request(nextMessageNumber(), romName));
    }

    /**
     * Creates a game and waits for the server's CreateGame notification.
     *
     * @return the assigned game ID, or -1 on timeout
     */
    public int createGame(String romName, long timeoutMs)
            throws IOException, MessageFormatException {
        createGame(romName);
        // Creating a game also joins the owner to it, but the two notifications can
        // arrive in separate bundles.
        return waitUntil(() -> createdGameId >= 0 && inGame, timeoutMs) ? createdGameId : -1;
    }

    public boolean waitForGameCreated(long timeoutMs) throws IOException {
        return waitUntil(() -> createdGameId >= 0, timeoutMs);
    }

    public void joinGame(int targetGameId) throws IOException, MessageFormatException {
        requireLoggedIn();
        inGame = false;
        sendMessages(new JoinGame_Request(nextMessageNumber(), targetGameId, connectionType));
    }

    public boolean joinGame(int targetGameId, long timeoutMs)
            throws IOException, MessageFormatException {
        joinGame(targetGameId);
        return waitUntil(() -> inGame, timeoutMs);
    }

    public boolean waitForInGame(long timeoutMs) throws IOException {
        return waitUntil(() -> inGame, timeoutMs);
    }

    public void startGame() throws IOException, MessageFormatException {
        requireInGame();
        gameStarted = false;
        sendMessages(new StartGame_Request(nextMessageNumber()));
    }

    public boolean startGame(long timeoutMs) throws IOException, MessageFormatException {
        startGame();
        return waitUntil(() -> gameStarted, timeoutMs);
    }

    public boolean waitForGameStarted(long timeoutMs) throws IOException {
        return waitUntil(() -> gameStarted, timeoutMs);
    }

    public void playerReady() throws IOException, MessageFormatException {
        requireInGame();
        allReady = false;
        sendMessages(new AllReady(nextMessageNumber()));
    }

    public boolean playerReady(long timeoutMs) throws IOException, MessageFormatException {
        playerReady();
        return waitUntil(() -> allReady, timeoutMs);
    }

    public boolean waitForAllReady(long timeoutMs) throws IOException {
        return waitUntil(() -> allReady, timeoutMs);
    }

    /**
     * Sends one GameData message. The payload length must be a multiple of the
     * game's actions-per-message (one for LAN connections).
     */
    public void sendGameData(byte[] data) throws IOException, MessageFormatException {
        requireInGame();
        sendMessages(new GameData(nextMessageNumber(), data));
    }

    /**
     * Replays a previously sent payload using the server-side client cache.
     *
     * @param key
     *            cache key (0 is the first payload sent after the game started)
     */
    public void sendCachedGameData(int key) throws IOException, MessageFormatException {
        requireInGame();
        sendMessages(new CachedGameData(nextMessageNumber(), key));
    }

    public void gameChat(String message) throws IOException, MessageFormatException {
        requireInGame();
        sendMessages(new GameChat_Request(nextMessageNumber(), message));
    }

    public void dropGame() throws IOException, MessageFormatException {
        requireInGame();
        sendMessages(new PlayerDrop_Request(nextMessageNumber()));
    }

    public void quitGame() throws IOException, MessageFormatException {
        requireInGame();
        sendMessages(new QuitGame_Request(nextMessageNumber()));
    }

    public void gameKick(int targetUserId) throws IOException, MessageFormatException {
        requireInGame();
        sendMessages(new GameKick(nextMessageNumber(), targetUserId));
    }

    /**
     * Sends a quit message and closes the connection.
     */
    public void quit(String message) throws IOException, MessageFormatException {
        if (!connected) {
            return;
        }

        try {
            sendMessages(new Quit_Request(nextMessageNumber(), message));
            log.debug("{}: Sent QUIT", clientName);
        } finally {
            connected = false;
            loggedIn = false;
        }
    }

    // Wait helpers for inbound events

    public boolean waitForGameDataCount(int count, long timeoutMs) throws IOException {
        return waitUntil(() -> receivedGameData.size() >= count, timeoutMs);
    }

    public boolean waitForGameChat(int count, long timeoutMs) throws IOException {
        return waitUntil(() -> gameChatMessages.size() >= count, timeoutMs);
    }

    public boolean waitForDroppedPlayer(long timeoutMs) throws IOException {
        return waitUntil(() -> lastDroppedPlayer != null, timeoutMs);
    }

    public boolean waitForGameQuit(long timeoutMs) throws IOException {
        return waitUntil(() -> quitGameReceived, timeoutMs);
    }

    public boolean waitForPlayerQuit(String playerName, long timeoutMs) throws IOException {
        return waitUntil(() -> playerName.equals(lastQuitGameUserName), timeoutMs);
    }

    // Receive and dispatch

    /**
     * Receives and processes incoming messages.
     *
     * @return true if a bundle was processed
     */
    public boolean receiveAndProcess() throws IOException {
        receiveBuffer.clear();

        SocketAddress from;
        try {
            from = channel.receive(receiveBuffer);
        } catch (SocketTimeoutException | ClosedChannelException e) {
            return false;
        }

        if (from == null) {
            return false;
        }

        receiveBuffer.flip();
        if (!receiveBuffer.hasRemaining()) {
            return false;
        }

        try {
            V086Bundle bundle = V086Bundle.parse(receiveBuffer, -1);
            // Bundles are written newest message first; dispatch oldest first so
            // state transitions apply in protocol order.
            for (int i = bundle.getNumMessages() - 1; i >= 0; i--) {
                handleMessage(bundle.getMessages()[i]);
            }
        } catch (ParseException | V086BundleFormatException | MessageFormatException e) {
            log.warn("{}: failed to parse incoming bundle: {}", clientName, e.getMessage());
            return false;
        }

        return true;
    }

    private void handleMessage(V086Message message) throws IOException, MessageFormatException {
        switch (message.getID()) {
            case ServerACK.ID :
                // The server performs a multi-round speed test: every ClientACK is
                // answered with another ServerACK until enough measurements exist.
                sendMessages(new ClientACK(nextMessageNumber()));
                break;

            case ServerStatus.ID :
                // ConnectedEvent (rendered as ServerStatus) is sent only after the
                // server has completed the login handshake.
                loggedIn = true;
                break;

            case KeepAlive.ID :
                sendMessages(new KeepAlive(nextMessageNumber()));
                break;

            case UserJoined.ID :
                if (message instanceof UserJoined joined
                        && joined.getUserName().equals(clientName)) {
                    userId = joined.getUserID();
                    loggedIn = true;
                }
                break;

            case CreateGame_Notification.ID :
                if (message instanceof CreateGame_Notification created
                        && created.getUserName().equals(clientName)) {
                    createdGameId = created.getGameID();
                    gameId = createdGameId;
                    gameRomName = created.getRomName();
                }
                break;

            case JoinGame_Notification.ID :
                if (message instanceof JoinGame_Notification joinedGame
                        && isSelf(joinedGame.getUserID(), joinedGame.getUserName())) {
                    gameId = joinedGame.getGameID();
                    inGame = true;
                }
                break;

            case GameStatus.ID :
                if (message instanceof GameStatus status
                        && (gameId < 0 || status.getGameID() == gameId)) {
                    gameId = status.getGameID();
                    gameStatus = status.getGameStatus();
                    numPlayers = status.getNumPlayers();
                    if (gameStatus == KailleraGame.STATUS_WAITING) {
                        gameStarted = false;
                        allReady = false;
                    }
                }
                break;

            case StartGame_Notification.ID :
                if (message instanceof StartGame_Notification started) {
                    gameStarted = true;
                    playerNumber = started.getPlayerNumber();
                    numPlayers = started.getNumPlayers();
                }
                break;

            case AllReady.ID :
                allReady = true;
                break;

            case GameData.ID :
                if (message instanceof GameData data) {
                    byte[] payload = data.getGameData();
                    receivedDataCache.add(payload);
                    receivedGameData.add(payload);
                }
                break;

            case CachedGameData.ID :
                if (message instanceof CachedGameData cached) {
                    byte[] payload = receivedDataCache.get(cached.getKey());
                    if (payload != null) {
                        receivedGameData.add(payload);
                    } else {
                        log.warn("{}: server referenced unknown cached game data key {}",
                                clientName, cached.getKey());
                    }
                }
                break;

            case GameChat_Notification.ID :
                if (message instanceof GameChat_Notification chat) {
                    gameChatMessages.add(chat.getUserName() + ": " + chat.getMessage());
                }
                break;

            case PlayerDrop_Notification.ID :
                if (message instanceof PlayerDrop_Notification drop) {
                    lastDroppedPlayer = drop.getUserName();
                    lastDroppedPlayerNumber = drop.getPlayerNumber();
                }
                break;

            case QuitGame_Notification.ID :
                if (message instanceof QuitGame_Notification quitGame) {
                    lastQuitGameUserName = quitGame.getUserName();
                    lastQuitGameUserID = quitGame.getUserID();
                    if (isSelf(quitGame.getUserID(), quitGame.getUserName())) {
                        quitGameReceived = true;
                        inGame = false;
                        gameStarted = false;
                        allReady = false;
                    }
                }
                break;

            case InformationMessage.ID :
                if (message instanceof InformationMessage info) {
                    lastInformationMessage = info.getMessage();
                }
                break;

            default :
                break;
        }
    }

    private void sendMessages(V086Message... messages) throws IOException {
        if (serverAddress == null) {
            throw new IllegalStateException("Not connected");
        }

        V086Bundle bundle = new V086Bundle(messages);
        ByteBuffer buffer = ByteBuffer.allocate(MAX_PACKET_SIZE);
        bundle.writeTo(buffer);
        buffer.flip();
        channel.send(buffer, serverAddress);
    }

    private int nextMessageNumber() {
        return messageNumber.incrementAndGet() & 0xFFFF;
    }

    private boolean waitUntil(BooleanSupplier condition, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (!condition.getAsBoolean()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }

            channel.socket().setSoTimeout((int) Math.min(remaining, 200));
            receiveAndProcess();
        }

        return true;
    }

    private boolean isSelf(int messageUserId, String messageUserName) {
        return (userId >= 0 && messageUserId == userId) || clientName.equals(messageUserName);
    }

    private void requireLoggedIn() {
        if (!loggedIn) {
            throw new IllegalStateException("Not logged in");
        }
    }

    private void requireInGame() {
        requireLoggedIn();
        if (!inGame) {
            throw new IllegalStateException("Not in a game");
        }
    }

    // State accessors

    public boolean isConnected() {
        return connected;
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    public String getClientName() {
        return clientName;
    }

    public int getAssignedPort() {
        return assignedPort;
    }

    public int getUserId() {
        return userId;
    }

    public int getCreatedGameId() {
        return createdGameId;
    }

    public int getGameId() {
        return gameId;
    }

    public String getGameRomName() {
        return gameRomName;
    }

    public boolean isInGame() {
        return inGame;
    }

    public boolean isGameStarted() {
        return gameStarted;
    }

    public boolean isAllReady() {
        return allReady;
    }

    public int getPlayerNumber() {
        return playerNumber;
    }

    public int getNumPlayers() {
        return numPlayers;
    }

    public byte getGameStatus() {
        return gameStatus;
    }

    public byte[] getLastGameData() {
        return receivedGameData.isEmpty()
                ? null
                : receivedGameData.get(receivedGameData.size() - 1);
    }

    public List<byte[]> getReceivedGameData() {
        return List.copyOf(receivedGameData);
    }

    public List<String> getGameChatMessages() {
        return List.copyOf(gameChatMessages);
    }

    public String getLastInformationMessage() {
        return lastInformationMessage;
    }

    public String getLastDroppedPlayer() {
        return lastDroppedPlayer;
    }

    public int getLastDroppedPlayerNumber() {
        return lastDroppedPlayerNumber;
    }

    public String getLastQuitGameUserName() {
        return lastQuitGameUserName;
    }

    public int getLastQuitGameUserID() {
        return lastQuitGameUserID;
    }

    @Override
    public void close() throws IOException {
        try {
            if (connected) {
                try {
                    quit("Client closing");
                } catch (MessageFormatException e) {
                    log.warn("{}: failed to build quit message: {}", clientName, e.getMessage());
                }
            }
        } finally {
            if (channel != null) {
                channel.close();
            }
        }
    }
}
