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
import su.kidoz.kaillera.controller.v086.protocol.CreateGame;
import su.kidoz.kaillera.controller.v086.protocol.CreateGame_Request;
import su.kidoz.kaillera.controller.v086.protocol.InformationMessage;
import su.kidoz.kaillera.controller.v086.protocol.QuitGame_Notification;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.exception.CreateGameException;
import su.kidoz.kaillera.model.exception.FloodException;
import su.kidoz.kaillera.model.validation.InputValidator;
import su.kidoz.util.EmuLang;

/**
 * Command handler for inbound create game requests from clients.
 *
 * <p>
 * Processes CreateGame_Request messages and delegates to the domain model.
 * Outbound event rendering is handled by {@link CreateGameEventRenderer}.
 */
@Component
@V086Command(messageId = CreateGame.ID)
public final class CreateGameCommandAction implements V086Action {
    private static final Logger log = LoggerFactory.getLogger(CreateGameCommandAction.class);
    private static final String DESC = "CreateGameCommandAction";

    private final AtomicInteger actionCount = new AtomicInteger(0);

    public int getActionPerformedCount() {
        return actionCount.get();
    }

    public String toString() {
        return DESC;
    }

    @Override
    public void performAction(final V086Message message, final V086ClientHandler clientHandler)
            throws FatalActionException {
        if (!(message instanceof CreateGame_Request)) {
            throw new FatalActionException("Received incorrect instance of CreateGame: " + message);
        }

        actionCount.incrementAndGet();

        CreateGame createGameMessage = (CreateGame) message;

        try {
            clientHandler.getUser().createGame(createGameMessage.getRomName());
        } catch (CreateGameException e) {
            log.info("Create Game Denied: " + clientHandler.getUser() + ": "
                    + InputValidator.sanitizeForLog(createGameMessage.getRomName()));

            try {
                clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                        "server",
                        EmuLang.getString("CreateGameAction.CreateGameDenied", e.getMessage())));
                clientHandler.send(new QuitGame_Notification(clientHandler.getNextMessageNumber(),
                        clientHandler.getUser().getName(), clientHandler.getUser().getID()));
            } catch (MessageFormatException e2) {
                log.error("Failed to construct message: " + e.getMessage(), e);
            }
        } catch (FloodException e) {
            log.info("Create Game Denied: " + clientHandler.getUser() + ": "
                    + InputValidator.sanitizeForLog(createGameMessage.getRomName()));

            try {
                clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                        "server",
                        EmuLang.getString("CreateGameAction.CreateGameDeniedFloodControl")));
                clientHandler.send(new QuitGame_Notification(clientHandler.getNextMessageNumber(),
                        clientHandler.getUser().getName(), clientHandler.getUser().getID()));
            } catch (MessageFormatException e2) {
                log.error("Failed to construct message: " + e.getMessage(), e);
            }
        }
    }
}
