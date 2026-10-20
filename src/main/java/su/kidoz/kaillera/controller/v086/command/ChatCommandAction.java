package su.kidoz.kaillera.controller.v086.command;

import su.kidoz.kaillera.controller.v086.action.FatalActionException;
import su.kidoz.kaillera.controller.v086.action.V086Action;
import su.kidoz.kaillera.controller.v086.annotation.V086Command;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.v086.V086ClientHandler;
import su.kidoz.kaillera.controller.v086.protocol.Chat;
import su.kidoz.kaillera.controller.v086.protocol.Chat_Request;
import su.kidoz.kaillera.controller.v086.protocol.InformationMessage;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.exception.ActionException;
import su.kidoz.util.EmuLang;

/**
 * Command handler for inbound chat messages from clients.
 *
 * <p>
 * Processes Chat_Request messages, handles admin commands, and delegates chat
 * to the domain model. This class handles only the inbound (command) side of
 * chat; outbound event rendering is handled by {@link ChatEventRenderer}.
 */
@Component
@V086Command(messageId = Chat.ID)
public final class ChatCommandAction implements V086Action {
    public static final String ADMIN_COMMAND_ESCAPE_STRING = "/";

    private static final Logger log = LoggerFactory.getLogger(ChatCommandAction.class);
    private static final String DESC = "ChatCommandAction";

    private final AtomicInteger actionCount = new AtomicInteger(0);
    private final AdminCommandAction adminCommandAction;

    public ChatCommandAction(final AdminCommandAction adminCommandAction) {
        this.adminCommandAction = adminCommandAction;
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
        if (!(message instanceof Chat_Request)) {
            throw new FatalActionException("Received incorrect instance of Chat: " + message);
        }

        KailleraUser user = clientHandler.getUser();
        if (user != null && user.isLoggedIn()
                && ((Chat) message).getMessage().startsWith(ADMIN_COMMAND_ESCAPE_STRING)) {
            try {
                adminCommandAction.performAction(message, clientHandler);
                return;
            } catch (FatalActionException e) {
                log.warn("Admin command failed, processing as chat: " + e.getMessage());
            }
        }

        actionCount.incrementAndGet();

        try {
            clientHandler.getUser().chat(((Chat) message).getMessage());
        } catch (ActionException e) {
            log.info("Chat Denied: " + clientHandler.getUser() + ": "
                    + ((Chat) message).getMessage());

            try {
                clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                        "server", EmuLang.getString("ChatAction.ChatDenied", e.getMessage())));
            } catch (MessageFormatException e2) {
                log.error("Failed to construct InformationMessage message: " + e.getMessage(), e);
            }
        }
    }
}
