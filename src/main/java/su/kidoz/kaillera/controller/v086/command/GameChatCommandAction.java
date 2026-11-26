package su.kidoz.kaillera.controller.v086.command;

import su.kidoz.kaillera.controller.v086.action.FatalActionException;
import su.kidoz.kaillera.controller.v086.action.V086Action;
import su.kidoz.kaillera.controller.v086.annotation.V086Command;

import java.util.concurrent.atomic.AtomicInteger;

import su.kidoz.kaillera.model.validation.InputValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import su.kidoz.kaillera.controller.v086.V086ClientHandler;
import su.kidoz.kaillera.controller.v086.protocol.GameChat;
import su.kidoz.kaillera.controller.v086.protocol.GameChat_Request;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.exception.GameChatException;

/**
 * Command handler for inbound game chat requests from clients.
 *
 * <p>
 * Processes GameChat_Request messages, handles game owner commands, and
 * delegates chat to the domain model. Outbound event rendering is handled by
 * {@link GameChatEventRenderer}.
 */
@Component
@V086Command(messageId = GameChat.ID)
public final class GameChatCommandAction implements V086Action {
    public static final String ADMIN_COMMAND_ESCAPE_STRING = "/";

    private static final Logger log = LoggerFactory.getLogger(GameChatCommandAction.class);
    private static final String DESC = "GameChatCommandAction";

    private final AtomicInteger actionCount = new AtomicInteger(0);
    private final GameOwnerCommandAction gameOwnerCommandAction;

    public GameChatCommandAction(final GameOwnerCommandAction gameOwnerCommandAction) {
        this.gameOwnerCommandAction = gameOwnerCommandAction;
    }

    public int getActionPerformedCount() {
        return actionCount.get();
    }

    public String toString() {
        return DESC;
    }

    @Override
    public void performAction(final V086Message message, final V086ClientHandler clientHandler)
            throws FatalActionException {
        if (!(message instanceof GameChat_Request)) {
            throw new FatalActionException("Received incorrect instance of GameChat: " + message);
        }

        actionCount.incrementAndGet();

        GameChat_Request gameChatMessage = (GameChat_Request) message;

        try {
            clientHandler.getUser().gameChat(gameChatMessage.getMessage(),
                    gameChatMessage.getNumber());
        } catch (GameChatException e) {
            log.debug("Failed to send game chat message: "
                    + InputValidator.sanitizeForLog(e.getMessage()));
        }

        if (((GameChat) message).getMessage().startsWith(ADMIN_COMMAND_ESCAPE_STRING)) {
            try {
                gameOwnerCommandAction.performAction(message, clientHandler);
            } catch (FatalActionException e) {
                log.warn("GameOwner command failed, processing as chat: "
                        + InputValidator.sanitizeForLog(e.getMessage()));
            }
        }
    }
}
