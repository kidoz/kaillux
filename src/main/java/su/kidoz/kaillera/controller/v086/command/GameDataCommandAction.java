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
import su.kidoz.kaillera.controller.v086.protocol.GameData;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.exception.GameDataException;

/**
 * Command handler for inbound game data from clients.
 *
 * <p>
 * Processes GameData messages containing player input and delegates to the
 * domain model. Outbound event rendering is handled by
 * {@link GameDataEventRenderer}.
 */
@Component
@V086Command(messageId = GameData.ID)
public final class GameDataCommandAction implements V086Action {
    private static final Logger log = LoggerFactory.getLogger(GameDataCommandAction.class);
    private static final String DESC = "GameDataCommandAction";

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
        try {
            byte[] data = ((GameData) message).getGameData();
            clientHandler.addClientGameData(data);
            clientHandler.getUser().addGameData(data);
        } catch (GameDataException e) {
            log.debug("Game data error: " + e.getMessage());

            if (e.hasResponse()) {
                try {
                    clientHandler.send(
                            new GameData(clientHandler.getNextMessageNumber(), e.getResponse()));
                } catch (MessageFormatException e2) {
                    log.error("Failed to construct GameData message: " + e2.getMessage(), e2);
                }
            }
        }
    }
}
