package su.kidoz.kaillera.access;

import java.net.InetAddress;

/**
 * An AccessManager is used retrieve, check, and store user access levels and
 * permissions, and game and emulator filters. This interface defines the static
 * access levels and methods that an AccessManager must implement. How the
 * access permissions are stored, checked, and manipulated is left to the
 * implementation class.<br>
 * <br>
 * Most of the main Kaillux components are passed a handle to the current
 * AccessManager and make calls to it upon user interactions.<br>
 * <br>
 * AccessManager is a top-level Kaillux component; its implementation class is
 * loaded via Spring upon startup.
 *
 */
public interface AccessManager {
    int ACCESS_BANNED = 0;
    int ACCESS_NORMAL = 1;
    int ACCESS_ELEVATED = 2;
    int ACCESS_ADMIN = 3;

    /**
     * Returns the name of an access level, or "Unknown(n)" for an invalid value.
     *
     * @param access
     *            one of the ACCESS_ constants
     * @return the display name of the access level
     */
    static String getAccessName(int access) {
        return switch (access) {
            case ACCESS_BANNED -> "Banned";
            case ACCESS_NORMAL -> "Normal";
            case ACCESS_ELEVATED -> "Elevated";
            case ACCESS_ADMIN -> "Admin";
            default -> "Unknown(" + access + ")";
        };
    }

    /**
     * Checks if address is allowed to connect.
     *
     * @param address
     *            IP Address of client
     * @return true if address is allowed to connect
     */
    boolean isAddressAllowed(InetAddress address);

    /**
     * Checks if address is silenced
     *
     * @param address
     *            IP Address of client
     * @return true if address is silenced
     */
    boolean isSilenced(InetAddress address);

    /**
     * Checks if client's emulator is allowed (not filtered)
     *
     * @param emulator
     *            Emulator name of client
     * @return true if emulator is allowed
     */
    boolean isEmulatorAllowed(String emulator);

    /**
     * Checks if client's game (ROM) is allowed (not filtered)
     *
     * @param game
     *            Game name of client
     * @return true if game is allowed
     */
    boolean isGameAllowed(String game);

    /**
     * Returns the client's assigned access level
     *
     * @param address
     *            IP Address of client
     * @return The access level or the default access level if not found
     */
    int getAccess(InetAddress address);

    /**
     * Returns a login announcement string
     *
     * @param address
     *            IP Address of client
     * @return The login announcement, null if not defined
     */
    String getAnnouncement(InetAddress address);

    /**
     * Temporairly adds a user to the nanned list using a pattern algorythm defined
     * by the AccessManager implementation. While active,
     * <code>isAddressAllowed</code> should return false, and <code>getAccess</code>
     * should return <code>ACCESS_BANNED</code>.
     *
     * @param pattern
     *            A pattern to match to an address
     * @param minutes
     *            Number of minutes this ban is valid from the time of addition
     */
    void addTempBan(String pattern, int minutes);

    /**
     * Temporairly adds a user to the admin list using a pattern algorythm defined
     * by the AccessManager implementation. While active, <code>getAccess</code>
     * should return <code>ACCESS_ADMIN</code>.
     *
     * @param pattern
     *            A pattern to match to an address
     * @param minutes
     *            Number of minutes this grant is valid from the time of addition
     */
    void addTempAdmin(String pattern, int minutes);

    /**
     * Temporairly adds a user to the silenced list using a pattern algorythm
     * defined by the AccessManager implementation. While active,
     * <code>isSilenced</code> should return <code>true</code>.
     *
     * @param pattern
     *            A pattern to match to an address
     * @param minutes
     *            Number of minutes this grant is valid from the time of addition
     */
    void addSilenced(String pattern, int minutes);

    boolean clearTemp(InetAddress address);
}
