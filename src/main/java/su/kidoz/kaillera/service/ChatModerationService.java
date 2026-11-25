package su.kidoz.kaillera.service;

import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.exception.ChatException;
import su.kidoz.kaillera.model.exception.FloodException;
import su.kidoz.kaillera.model.validation.InputValidator;
import su.kidoz.util.EmuLang;
import su.kidoz.util.ThrottledLogger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for validating chat messages and enforcing chat moderation rules.
 * Handles silencing, flood control, and illegal character detection.
 */
public class ChatModerationService {
    private static final Logger log = LoggerFactory.getLogger(ChatModerationService.class);
    private static final ThrottledLogger DENIED_LOG = new ThrottledLogger(log, "denied lobby chat");

    private final AccessManager accessManager;
    private final int chatFloodTime;
    private final int maxChatLength;

    public ChatModerationService(final AccessManager accessManager, final int chatFloodTime,
            final int maxChatLength) {
        this.accessManager = accessManager;
        this.chatFloodTime = chatFloodTime;
        this.maxChatLength = maxChatLength;
    }

    /**
     * Validates that the user is allowed to chat and the message is valid.
     *
     * @param user
     *            the user sending the message
     * @param message
     *            the chat message
     * @return the trimmed and validated message
     * @throws ChatException
     *             if chat is denied
     * @throws FloodException
     *             if chat flood control triggered
     */
    public String validateChat(final KailleraUser user, final String message)
            throws ChatException, FloodException {
        if (!user.isLoggedIn()) {
            log.debug(user + " chat failed: Not logged in");
            throw new ChatException(EmuLang.getString("KailleraServerImpl.NotLoggedIn"));
        }

        final int access = accessManager.getAccess(user.getSocketAddress().getAddress());

        // Only elevated and admin clients are exempt from the rules below. A banned
        // session is not: it loses the exemptions the moment the ban applies, not
        // when the maintenance pass gets around to disconnecting it.
        if (access < AccessManager.ACCESS_NORMAL) {
            DENIED_LOG.warn("{}: chat denied: Banned", user);
            throw new ChatException(EmuLang.getString("KailleraServerImpl.DeniedBanned"));
        }

        // Check if user is silenced
        if (access <= AccessManager.ACCESS_NORMAL
                && accessManager.isSilenced(user.getSocketAddress().getAddress())) {
            DENIED_LOG.warn("{}: chat denied: Silenced", user);
            throw new ChatException(EmuLang.getString("KailleraServerImpl.ChatDeniedSilenced"));
        }

        // Check flood control
        if (access <= AccessManager.ACCESS_NORMAL && chatFloodTime > 0) {
            final long lastChatTime = user.getLastChatTime();
            if ((System.currentTimeMillis() - lastChatTime) < (chatFloodTime * 1000L)) {
                // Reached once per packet by a client that keeps retrying.
                log.debug("{}: chat denied: Flood", user);
                throw new FloodException(
                        EmuLang.getString("KailleraServerImpl.ChatDeniedFloodControl"));
            }
        }

        final String trimmedMessage = message.trim();
        if (trimmedMessage.isEmpty()) {
            return ""; // Empty messages are silently ignored
        }

        // Validate message content for clients that are not exempt
        if (access <= AccessManager.ACCESS_NORMAL) {
            if (InputValidator.hasIllegalCharacters(trimmedMessage)) {
                DENIED_LOG.warn("{}: chat denied: Illegal characters in message", user);
                throw new ChatException(
                        EmuLang.getString("KailleraServerImpl.ChatDeniedIllegalCharacters"));
            }

            if (maxChatLength > 0 && trimmedMessage.length() > maxChatLength) {
                DENIED_LOG.warn("{}: chat denied: Message Length > {}", user, maxChatLength);
                throw new ChatException(
                        EmuLang.getString("KailleraServerImpl.ChatDeniedMessageTooLong"));
            }
        }

        return trimmedMessage;
    }
}
