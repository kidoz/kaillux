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
import su.kidoz.kaillera.controller.v086.protocol.CachedGameData;
import su.kidoz.kaillera.controller.v086.protocol.GameChat_Notification;
import su.kidoz.kaillera.controller.v086.protocol.GameData;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.exception.GameDataException;

@Component
@V086Command(messageId = CachedGameData.ID)
public final class CachedGameDataAction implements V086Action {
    private static final Logger log = LoggerFactory.getLogger(CachedGameDataAction.class);
    private static final String DESC = "CachedGameDataAction";

    private final AtomicInteger actionCount = new AtomicInteger(0);

    public CachedGameDataAction() {
    }

    public int getActionPerformedCount() {
        return actionCount.get();
    }

    public String toString() {
        return DESC;
    }

    @Override
    public void performAction(V086Message message, V086ClientHandler clientHandler)
            throws FatalActionException {
        try {
            int key = ((CachedGameData) message).getKey();
            byte[] data = clientHandler.getClientGameDataCache().get(key);
            if (data == null) {
                log.debug("{}: cached game data key {} is not in the cache", clientHandler, key);
                try {
                    clientHandler.send(new GameChat_Notification(
                            clientHandler.getNextMessageNumber(), "Error",
                            "Game Data Error! Cached key not found. Game state may be inconsistent."));
                } catch (MessageFormatException e) {
                    log.error("Failed to construct GameChat_Notification", e);
                }
                return;
            }
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
        } catch (IndexOutOfBoundsException e) {
            // The key is the client's own choice: an unknown one is a client bug or a
            // probe, not a server fault.
            log.debug("{}: cached game data key {} is not in the cache", clientHandler,
                    ((CachedGameData) message).getKey());

            // This may not always be the best thing to do...
            try {
                clientHandler.send(new GameChat_Notification(clientHandler.getNextMessageNumber(),
                        "Error", "Game Data Error!  Game state will be inconsistent!"));
            } catch (MessageFormatException e2) {
                log.error("Failed to construct new GameChat_Notification", e);
            }
        }
    }
}
