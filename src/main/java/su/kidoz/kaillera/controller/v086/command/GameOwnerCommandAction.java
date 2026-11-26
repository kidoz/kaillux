package su.kidoz.kaillera.controller.v086.command;

import su.kidoz.kaillera.controller.v086.action.FatalActionException;
import su.kidoz.kaillera.controller.v086.action.V086Action;

import java.util.StringTokenizer;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.v086.V086ClientHandler;
import su.kidoz.kaillera.controller.v086.protocol.GameChat;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.exception.ActionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import su.kidoz.kaillera.model.validation.InputValidator;
import su.kidoz.util.EmuLang;

@Component
public final class GameOwnerCommandAction implements V086Action {
    public static final String COMMAND_HELP = "/help";
    public static final String COMMAND_DETECTAUTOFIRE = "/detectautofire";

    private static final Logger log = LoggerFactory.getLogger(GameOwnerCommandAction.class);
    private static final String DESC = "GameOwnerCommandAction";

    private final AtomicInteger actionCount = new AtomicInteger(0);

    public GameOwnerCommandAction() {
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
        GameChat chatMessage = (GameChat) message;
        String chat = chatMessage.getMessage();

        KailleraUser user = clientHandler.getUser();
        KailleraGame game = user.getGame();

        if (game == null) {
            throw new FatalActionException("GameOwner Command Failed: Not in a game: " + chat);
        }

        if (!user.equals(game.getOwner())) {
            log.warn("GameOwner Command Denied: Not game owner: " + game + ": " + user + ": "
                    + InputValidator.sanitizeForLog(chat));
            return;
        }

        try {
            if (chat.startsWith(COMMAND_HELP)) {
                processHelp(chat, game, user, clientHandler);
            } else if (chat.startsWith(COMMAND_DETECTAUTOFIRE)) {
                processDetectAutoFire(chat, game, user, clientHandler);
            } else {
                log.info("Unknown GameOwner Command: " + game + ": " + user + ": "
                        + InputValidator.sanitizeForLog(chat));
            }
        } catch (ActionException e) {
            log.info("GameOwner Command Failed: " + game + ": " + user + ": "
                    + InputValidator.sanitizeForLog(chat));
            game.announce(
                    EmuLang.getString("GameOwnerCommandAction.CommandFailed", e.getMessage()));
        } catch (MessageFormatException e) {
            log.error("Failed to construct message: " + e.getMessage(), e);
        }
    }

    private void processHelp(String message, KailleraGame game, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        game.announce(EmuLang.getString("GameOwnerCommandAction.AvailableCommands"));
        game.announce(EmuLang.getString("GameOwnerCommandAction.SetAutofireDetection"));
    }

    private void autoFireHelp(KailleraGame game) {
        if (game.getAutoFireDetector() == null) {
            game.announce(EmuLang.getString("GameOwnerCommandAction.HelpSensitivity"));
            game.announce(EmuLang.getString("GameOwnerCommandAction.HelpDisable"));
            game.announce(EmuLang.getString("GameOwnerCommandAction.HelpCurrentSensitivity", 0)
                    + EmuLang.getString("GameOwnerCommandAction.HelpDisabled"));
            return;
        }
        int cur = game.getAutoFireDetector().getSensitivity();
        game.announce(EmuLang.getString("GameOwnerCommandAction.HelpSensitivity"));
        game.announce(EmuLang.getString("GameOwnerCommandAction.HelpDisable"));
        game.announce(EmuLang.getString("GameOwnerCommandAction.HelpCurrentSensitivity", cur)
                + (cur == 0 ? (EmuLang.getString("GameOwnerCommandAction.HelpDisabled")) : ""));
    }

    private void processDetectAutoFire(String message, KailleraGame game, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        if (game.getStatus() != KailleraGame.STATUS_WAITING) {
            game.announce(EmuLang.getString("GameOwnerCommandAction.AutoFireChangeDeniedInGame"));
            return;
        }

        StringTokenizer st = new StringTokenizer(message, " ");
        if (st.countTokens() != 2) {
            autoFireHelp(game);
            return;
        }

        st.nextToken(); // skip command token
        String sensitivityStr = st.nextToken();
        int sensitivity;

        try {
            sensitivity = Integer.parseInt(sensitivityStr);
        } catch (NumberFormatException e) {
            log.debug("Invalid autofire sensitivity value: {}", sensitivityStr);
            autoFireHelp(game);
            return;
        }

        if (sensitivity > 5 || sensitivity < 0) {
            autoFireHelp(game);
            return;
        }

        if (game.getAutoFireDetector() == null) {
            game.announce(EmuLang.getString("GameOwnerCommandAction.CommandFailed",
                    "Autofire detector not available"));
            return;
        }

        game.getAutoFireDetector().setSensitivity(sensitivity);
        game.announce(
                EmuLang.getString("GameOwnerCommandAction.HelpCurrentSensitivity", sensitivity)
                        + (sensitivity == 0
                                ? (EmuLang.getString("GameOwnerCommandAction.HelpDisabled"))
                                : ""));
    }
}
