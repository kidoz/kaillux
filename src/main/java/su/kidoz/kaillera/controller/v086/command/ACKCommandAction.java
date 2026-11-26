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
import su.kidoz.kaillera.controller.v086.protocol.ClientACK;
import su.kidoz.kaillera.controller.v086.protocol.ConnectionRejected;
import su.kidoz.kaillera.controller.v086.protocol.ServerACK;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.exception.LoginException;

/**
 * Command handler for inbound ACK messages from clients during login.
 *
 * <p>
 * Processes ACK messages for speed measurement and login completion. Outbound
 * event rendering is handled by {@link ACKEventRenderer}.
 */
@Component
@V086Command(messageId = ClientACK.ID)
public final class ACKCommandAction implements V086Action {
    private static final Logger log = LoggerFactory.getLogger(ACKCommandAction.class);
    private static final String DESC = "ACKCommandAction";
    private static final int NUM_ACKS_FOR_SPEED_TEST = 3;

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
        actionCount.incrementAndGet();

        KailleraUser user = clientHandler.getUser();
        if (user == null || user.isLoggedIn())
            return;

        // ACK before UserInformation has no identity or socket address. Logging in
        // here used to NPE in server.login and leave the connecting slot occupied.
        if (user.getSocketAddress() == null) {
            log.debug("{} sent ClientACK before UserInformation; ignored", user);
            return;
        }

        clientHandler.addSpeedMeasurement();

        if (clientHandler.getSpeedMeasurementCount() > NUM_ACKS_FOR_SPEED_TEST) {
            // Use best (lowest) ping for the user's displayed ping value
            user.setPing(clientHandler.getBestNetworkSpeed());

            log.debug("Calculated " + user + " ping time: average="
                    + clientHandler.getAverageNetworkSpeed() + ", best="
                    + clientHandler.getBestNetworkSpeed());

            try {
                user.login();
            } catch (LoginException e) {
                try {
                    clientHandler.send(new ConnectionRejected(clientHandler.getNextMessageNumber(),
                            user.getName(), user.getID(), e.getMessage()));
                } catch (MessageFormatException e2) {
                    log.error("Failed to construct new ConnectionRejected", e);
                }

                throw new FatalActionException("Login failed: " + e.getMessage());
            }
        } else {
            try {
                clientHandler.send(new ServerACK(clientHandler.getNextMessageNumber()));
            } catch (MessageFormatException e) {
                log.error("Failed to construct new ServerACK", e);
            }
        }
    }
}
