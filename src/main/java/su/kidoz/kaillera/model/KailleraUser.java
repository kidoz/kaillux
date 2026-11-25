package su.kidoz.kaillera.model;

import java.net.InetSocketAddress;

import su.kidoz.kaillera.model.event.KailleraEvent;
import su.kidoz.kaillera.model.event.KailleraEventListener;
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

/**
 * Represents a connected user in the Kaillera server. Manages user state,
 * profile information, and provides methods for server and game interactions.
 *
 * <p>
 * Users transition through several states:
 * <ul>
 * <li>{@link #STATUS_CONNECTING} - Initial connection, awaiting login</li>
 * <li>{@link #STATUS_IDLE} - Logged in, in server lobby</li>
 * <li>{@link #STATUS_PLAYING} - Participating in a game</li>
 * </ul>
 *
 * <p>
 * Connection types indicate the user's self-reported network quality, which
 * affects game synchronization behavior.
 *
 * @see KailleraServer
 * @see KailleraGame
 */
public interface KailleraUser {
    /** LAN connection - lowest latency, best for local network play. */
    byte CONNECTION_TYPE_LAN = 1;
    /** Excellent internet connection. */
    byte CONNECTION_TYPE_EXCELLENT = 2;
    /** Good internet connection. */
    byte CONNECTION_TYPE_GOOD = 3;
    /** Average internet connection. */
    byte CONNECTION_TYPE_AVERAGE = 4;
    /** Low quality internet connection. */
    byte CONNECTION_TYPE_LOW = 5;
    /** Bad internet connection - highest latency tolerance. */
    byte CONNECTION_TYPE_BAD = 6;

    /**
     * Returns the connection type name for the given type, or "Unknown(n)" for
     * invalid values.
     */
    static String getConnectionTypeName(int connectionType) {
        return switch (connectionType) {
            case 0 -> "DISABLED";
            case CONNECTION_TYPE_LAN -> "Lan";
            case CONNECTION_TYPE_EXCELLENT -> "Excellent";
            case CONNECTION_TYPE_GOOD -> "Good";
            case CONNECTION_TYPE_AVERAGE -> "Average";
            case CONNECTION_TYPE_LOW -> "Low";
            case CONNECTION_TYPE_BAD -> "Bad";
            default -> "Unknown(" + connectionType + ")";
        };
    }

    /** User is currently playing in a game. */
    byte STATUS_PLAYING = 0;
    /** User is in the server lobby, not in a game. */
    byte STATUS_IDLE = 1;
    /** User is connecting but has not completed login. */
    byte STATUS_CONNECTING = 2;

    /**
     * Returns the name of a status value as shown to administrators and in logs, or
     * "Unknown(n)" for an invalid value.
     *
     * @param status
     *            one of the STATUS_ constants
     * @return the display name of the status
     */
    static String getStatusName(int status) {
        return switch (status) {
            case STATUS_PLAYING -> "Playing";
            case STATUS_IDLE -> "Idle";
            case STATUS_CONNECTING -> "Connecting";
            default -> "Unknown(" + status + ")";
        };
    }

    int getID();

    InetSocketAddress getConnectSocketAddress();

    String getProtocol();

    long getConnectTime();

    int getStatus();

    String getName();

    void setName(String name);

    String getClientType();

    boolean isEmuLinkerClient();

    void setClientType(String clientType);

    byte getConnectionType();

    void setConnectionType(byte connectionType);

    InetSocketAddress getSocketAddress();

    void setSocketAddress(InetSocketAddress clientSocketAddress);

    int getPing();

    void setPing(int ping);

    void login() throws PingTimeException, ClientAddressException, ConnectionTypeException,
            UserNameException, LoginException;

    long getLastActivity();

    void updateLastActivity();

    void updateLastKeepAlive();

    long getLastKeepAlive();

    boolean isLoggedIn();

    KailleraServer getServer();

    KailleraEventListener getListener();

    void chat(String message) throws ChatException, FloodException;

    KailleraGame createGame(String romName) throws CreateGameException, FloodException;

    void quit(String message)
            throws QuitException, DropGameException, QuitGameException, CloseGameException;

    KailleraGame joinGame(int gameID) throws JoinGameException;

    /**
     * Returns the game this user is currently in.
     *
     * @return the current game, or null if not in a game
     */
    KailleraGame getGame();

    int getPlayerNumber();

    void startGame() throws StartGameException;

    void gameChat(String message, int messageID) throws GameChatException;

    void gameKick(int userID) throws GameKickException;

    void playerReady() throws UserReadyException;

    void addGameData(byte[] data) throws GameDataException;

    void dropGame() throws DropGameException;

    void quitGame() throws DropGameException, QuitGameException, CloseGameException;

    void droppedPacket();

    void stop();

    /**
     * Returns the access level of this user.
     *
     * @return the access level from AccessManager
     * @see su.kidoz.kaillera.access.AccessManager
     */
    int getAccess();

    /**
     * Returns a human-readable string representing the user's access level.
     *
     * @return the access level description (e.g., "Normal", "Admin", "SuperAdmin")
     */
    String getAccessStr();

    /**
     * Adds an event to this user's event queue for processing.
     *
     * @param event
     *            the event to add
     */
    void addEvent(KailleraEvent event);

    /**
     * Returns the current size of the event queue.
     *
     * @return number of events waiting to be processed
     */
    int getEventQueueSize();

    /**
     * Returns the total count of dropped events since connection.
     *
     * @return count of events that could not be queued
     */
    int getDroppedEventsCount();

    /**
     * Returns the timestamp of the user's last chat message.
     *
     * @return the last chat time in milliseconds since epoch
     */
    long getLastChatTime();
}
