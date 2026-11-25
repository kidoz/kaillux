package su.kidoz.kaillera.model.validation;

/**
 * Centralized input validation for Kaillera protocol messages. Provides
 * consistent validation rules to prevent security issues like injection attacks
 * and data corruption.
 */
public final class InputValidator {

    private InputValidator() {
        // Utility class
    }

    /**
     * Maximum length for user names to prevent memory abuse.
     */
    public static final int MAX_USERNAME_LENGTH = 64;

    /**
     * Maximum length for game/ROM names.
     */
    public static final int MAX_GAMENAME_LENGTH = 128;

    /**
     * Maximum length for chat messages.
     */
    public static final int MAX_CHAT_LENGTH = 512;

    /**
     * Maximum length for client type/emulator name.
     */
    public static final int MAX_CLIENT_NAME_LENGTH = 64;

    /**
     * Validates a string does not contain control characters or dangerous Unicode.
     *
     * @param str
     *            the string to validate
     * @return true if the string is safe, false otherwise
     */
    public static boolean isValidString(String str) {
        if (str == null) {
            return true;
        }
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (!isValidCharacter(c)) {
                return false;
            }
        }
        return true;
    }

    /**
     * The single content rule for text a client sends: user names, chat, game names
     * and quit messages.
     *
     * @param str
     *            the string to check
     * @return true if the string is null, empty, or contains a character clients
     *         must not send
     */
    public static boolean hasIllegalCharacters(String str) {
        return str == null || str.isEmpty() || !isValidString(str);
    }

    /**
     * Checks if a character is valid (not a control character or dangerous
     * Unicode).
     *
     * @param c
     *            the character to check
     * @return true if the character is valid
     */
    public static boolean isValidCharacter(char c) {
        // Reject control characters (< 32), DEL (127), and ISO control chars
        if (c < 32 || c == 127 || Character.isISOControl(c)) {
            return false;
        }
        // Reject Unicode direction override characters (potential spoofing)
        if (isDirectionOverride(c)) {
            return false;
        }
        // Reject zero-width characters (potential spoofing)
        if (isZeroWidth(c)) {
            return false;
        }
        return true;
    }

    /**
     * Unicode direction override characters: they let a name or message render in a
     * different order than it reads.
     */
    private static boolean isDirectionOverride(char c) {
        return c == '\u202A' || c == '\u202B' || c == '\u202C' || c == '\u202D' || c == '\u202E'
                || c == '\u2066' || c == '\u2067' || c == '\u2068' || c == '\u2069';
    }

    /**
     * Zero-width characters: invisible, so two different names can look alike.
     */
    private static boolean isZeroWidth(char c) {
        return c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\uFEFF';
    }

    /**
     * Sanitizes a string by removing invalid characters.
     *
     * @param str
     *            the string to sanitize
     * @return the sanitized string
     */
    public static String sanitize(String str) {
        if (str == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (isValidCharacter(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Validates a username.
     *
     * @param username
     *            the username to validate
     * @param maxLength
     *            the maximum allowed length (0 for no limit)
     * @return null if valid, error message if invalid
     */
    public static String validateUsername(String username, int maxLength) {
        if (username == null || username.trim().isEmpty()) {
            return "Username cannot be empty";
        }
        if (maxLength > 0 && username.length() > maxLength) {
            return "Username too long (max " + maxLength + " characters)";
        }
        if (!isValidString(username)) {
            return "Username contains invalid characters";
        }
        return null;
    }

    /**
     * Validates a game/ROM name.
     *
     * @param gameName
     *            the game name to validate
     * @param maxLength
     *            the maximum allowed length (0 for no limit)
     * @return null if valid, error message if invalid
     */
    public static String validateGameName(String gameName, int maxLength) {
        if (gameName == null || gameName.trim().isEmpty()) {
            return "Game name cannot be empty";
        }
        if (maxLength > 0 && gameName.length() > maxLength) {
            return "Game name too long (max " + maxLength + " characters)";
        }
        if (!isValidString(gameName)) {
            return "Game name contains invalid characters";
        }
        return null;
    }

    /**
     * Validates a chat message.
     *
     * @param message
     *            the message to validate
     * @param maxLength
     *            the maximum allowed length (0 for no limit)
     * @return null if valid, error message if invalid
     */
    public static String validateChatMessage(String message, int maxLength) {
        if (message == null || message.trim().isEmpty()) {
            return "Message cannot be empty";
        }
        if (maxLength > 0 && message.length() > maxLength) {
            return "Message too long (max " + maxLength + " characters)";
        }
        if (!isValidString(message)) {
            return "Message contains invalid characters";
        }
        return null;
    }

    /**
     * Validates a connection type byte value.
     *
     * @param connectionType
     *            the connection type to validate
     * @return null if valid, error message if invalid
     */
    public static String validateConnectionType(byte connectionType) {
        if (connectionType < 1 || connectionType > 6) {
            return "Invalid connection type: " + connectionType;
        }
        return null;
    }

    /**
     * Validates a ping value.
     *
     * @param ping
     *            the ping to validate
     * @param maxPing
     *            the maximum allowed ping (0 for no limit)
     * @return null if valid, error message if invalid
     */
    public static String validatePing(int ping, int maxPing) {
        if (ping < 0) {
            return "Invalid ping: " + ping;
        }
        if (maxPing > 0 && ping > maxPing) {
            return "Ping too high: " + ping + " > " + maxPing;
        }
        return null;
    }

    /**
     * Validates a user ID.
     *
     * @param userId
     *            the user ID to validate
     * @return null if valid, error message if invalid
     */
    public static String validateUserId(int userId) {
        if (userId <= 0 || userId > 0xFFFF) {
            return "Invalid user ID: " + userId;
        }
        return null;
    }

    /**
     * Validates a game ID.
     *
     * @param gameId
     *            the game ID to validate
     * @return null if valid, error message if invalid
     */
    public static String validateGameId(int gameId) {
        if (gameId <= 0 || gameId > 0xFFFF) {
            return "Invalid game ID: " + gameId;
        }
        return null;
    }

    /**
     * Makes a string safe to write to the log, so that client-controlled text
     * cannot forge or reorder log entries. Newlines and control characters become
     * escape sequences, and the result is capped in length.
     *
     * @param str
     *            the string to sanitize for logging
     * @return the sanitized string safe for logging
     */
    public static String sanitizeForLog(String str) {
        if (str == null) {
            return "<null>";
        }
        if (str.isEmpty()) {
            return "<empty>";
        }
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 32 || c == 127 || Character.isISOControl(c)) {
                sb.append("\\x").append(String.format("%02X", (int) c));
            } else if (isDirectionOverride(c) || isZeroWidth(c) || c == '\u2028' || c == '\u2029') {
                // Invisible or line-breaking characters that some viewers render
                // as a new line or use to reorder the surrounding text.
                sb.append("\\u").append(String.format("%04X", (int) c));
            } else {
                sb.append(c);
            }
        }
        // Limit length to prevent log flooding
        if (sb.length() > 256) {
            return sb.substring(0, 253) + "...";
        }
        return sb.toString();
    }

    /**
     * Validates a network port number.
     *
     * @param port
     *            the port to validate
     * @return null if valid, error message if invalid
     */
    public static String validatePort(int port) {
        if (port < 1 || port > 65535) {
            return "Invalid port: " + port + " (must be 1-65535)";
        }
        return null;
    }
}
