package su.kidoz.kaillera.model;

import java.net.InetSocketAddress;
import java.util.Collection;

import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.model.event.EventDispatcher;
import su.kidoz.kaillera.model.event.ServerEvent;
import su.kidoz.kaillera.model.exception.ChatException;
import su.kidoz.kaillera.model.exception.ClientAddressException;
import su.kidoz.kaillera.model.exception.CloseGameException;
import su.kidoz.kaillera.model.exception.ConnectionTypeException;
import su.kidoz.kaillera.model.exception.CreateGameException;
import su.kidoz.kaillera.model.exception.DropGameException;
import su.kidoz.kaillera.model.exception.FloodException;
import su.kidoz.kaillera.model.exception.LoginException;
import su.kidoz.kaillera.model.exception.NewConnectionException;
import su.kidoz.kaillera.model.exception.PingTimeException;
import su.kidoz.kaillera.model.exception.QuitException;
import su.kidoz.kaillera.model.exception.QuitGameException;
import su.kidoz.kaillera.model.exception.ServerFullException;
import su.kidoz.kaillera.model.exception.UserNameException;
import su.kidoz.release.ReleaseInfo;

/**
 * Core interface for the Kaillera server. Manages user connections, game
 * sessions, and server-wide chat for multiplayer emulator gaming over UDP.
 *
 * <p>
 * The Kaillera protocol enables real-time synchronization of emulator input
 * across network players, allowing multiplayer gaming on classic console and
 * arcade emulators.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 * <li>{@link #start()} - Begin accepting connections</li>
 * <li>{@link #newConnection} - Client initiates connection</li>
 * <li>{@link #login} - Client authenticates and joins lobby</li>
 * <li>{@link #createGame} / join game - Users enter game sessions</li>
 * <li>{@link #quit} - User disconnects</li>
 * <li>{@link #stop()} - Shutdown server</li>
 * </ol>
 *
 * @see KailleraUser
 * @see KailleraGame
 */
public interface KailleraServer {

    /**
     * Starts the server, enabling it to accept new connections.
     */
    void start();

    /**
     * Stops the server, disconnecting all users and closing all games.
     */
    void stop();

    /**
     * Returns release and version information for this server.
     *
     * @return the release info containing version string and build number
     */
    ReleaseInfo getReleaseInfo();

    /**
     * Returns the current number of connected users.
     *
     * @return count of users currently connected
     */
    int getNumUsers();

    /**
     * Returns the current number of active games.
     *
     * @return count of games currently active
     */
    int getNumGames();

    /**
     * Returns the maximum number of users allowed on this server.
     *
     * @return maximum user capacity, or 0 for unlimited
     */
    int getMaxUsers();

    /**
     * Returns the maximum number of concurrent games allowed.
     *
     * @return maximum game capacity, or 0 for unlimited
     */
    int getMaxGames();

    /**
     * Returns the maximum allowed ping time in milliseconds.
     *
     * @return maximum ping threshold for connection acceptance
     */
    int getMaxPing();

    /**
     * Returns all currently connected users.
     *
     * @return unmodifiable collection of connected users
     */
    Collection<? extends KailleraUser> getUsers();

    /**
     * Returns all currently active games.
     *
     * @return unmodifiable collection of active games
     */
    Collection<? extends KailleraGame> getGames();

    /**
     * Retrieves a user by their unique ID.
     *
     * @param userID
     *            the user's unique identifier
     * @return the user, or null if not found
     */
    KailleraUser getUser(int userID);

    /**
     * Retrieves a game by its unique ID.
     *
     * @param gameID
     *            the game's unique identifier
     * @return the game, or null if not found
     */
    KailleraGame getGame(int gameID);

    /**
     * Accepts a new client connection and creates a user session.
     *
     * @param clientSocketAddress
     *            the client's network address
     * @param protocol
     *            the protocol version string (e.g., "v086")
     * @param eventDispatcher
     *            dispatcher for routing events to the protocol handler
     * @return the newly created user
     * @throws ServerFullException
     *             if the server has reached maximum capacity
     * @throws NewConnectionException
     *             if connection cannot be established
     */
    KailleraUser newConnection(InetSocketAddress clientSocketAddress, String protocol,
            EventDispatcher eventDispatcher) throws ServerFullException, NewConnectionException;

    /**
     * Authenticates a user and completes their login to the server lobby.
     *
     * @param user
     *            the user to log in
     * @throws PingTimeException
     *             if ping exceeds maximum allowed
     * @throws ClientAddressException
     *             if address validation fails
     * @throws ConnectionTypeException
     *             if connection type is not allowed
     * @throws UserNameException
     *             if username is invalid or banned
     * @throws LoginException
     *             for other login failures
     */
    void login(KailleraUser user) throws PingTimeException, ClientAddressException,
            ConnectionTypeException, UserNameException, LoginException;

    /**
     * Broadcasts a chat message from a user to all connected users.
     *
     * @param user
     *            the user sending the message
     * @param message
     *            the chat message text
     * @throws ChatException
     *             if chat is not allowed (e.g., user silenced)
     * @throws FloodException
     *             if user is sending messages too quickly
     */
    void chat(KailleraUser user, String message) throws ChatException, FloodException;

    /**
     * Creates a new game session for the specified ROM.
     *
     * @param user
     *            the user creating the game (becomes owner)
     * @param romName
     *            the name of the ROM/game to play
     * @return the newly created game
     * @throws CreateGameException
     *             if game creation fails
     * @throws FloodException
     *             if user is creating games too quickly
     */
    KailleraGame createGame(KailleraUser user, String romName)
            throws CreateGameException, FloodException;

    /**
     * Disconnects a user from the server.
     *
     * @param user
     *            the user to disconnect
     * @param message
     *            quit message to display to other users
     * @throws QuitException
     *             if quit operation fails
     * @throws DropGameException
     *             if dropping from game fails
     * @throws QuitGameException
     *             if quitting game fails
     * @throws CloseGameException
     *             if closing owned game fails
     */
    void quit(KailleraUser user, String message)
            throws QuitException, DropGameException, QuitGameException, CloseGameException;

    /**
     * Returns the access manager for checking user permissions.
     *
     * @return the access manager instance
     */
    AccessManager getAccessManager();

    /**
     * Adds a server event to be broadcast to all listeners.
     *
     * @param event
     *            the server event to broadcast
     */
    void addEvent(ServerEvent event);

    /**
     * Broadcasts an announcement message to all connected users.
     *
     * @param message
     *            the announcement text
     * @param gamesAlso
     *            if true, also announce to users in games
     */
    void announce(String message, boolean gamesAlso);
}
