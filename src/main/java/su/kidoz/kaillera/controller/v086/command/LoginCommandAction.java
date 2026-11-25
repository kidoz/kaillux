package su.kidoz.kaillera.controller.v086.command;

import su.kidoz.kaillera.controller.v086.action.FatalActionException;
import su.kidoz.kaillera.controller.v086.action.V086Action;
import su.kidoz.kaillera.controller.v086.annotation.V086Command;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.v086.V086ClientHandler;
import su.kidoz.kaillera.controller.v086.protocol.ServerACK;
import su.kidoz.kaillera.controller.v086.protocol.UserInformation;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.KailleraUser;

/**
 * Command handler for inbound login (UserInformation) messages from clients.
 *
 * <p>
 * Processes the initial login request, sets user info, and starts the speed
 * test. Outbound event rendering is handled by {@link LoginEventRenderer}.
 */
@Component
@V086Command(messageId = UserInformation.ID)
public final class LoginCommandAction implements V086Action {
    private static final Logger log = LoggerFactory.getLogger(LoginCommandAction.class);
    private static final String DESC = "LoginCommandAction";

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

        UserInformation userInfo = (UserInformation) message;
        KailleraUser user = clientHandler.getUser();
        if (user == null) {
            throw new FatalActionException("User not initialized for login");
        }

        if (user.isLoggedIn()) {
            // Identity is fixed once the login handshake completes. Applying it again
            // would skip every name and client-type check in LoginValidator.
            log.debug("{} sent {} after login; ignored", user, UserInformation.DESC);
            return;
        }

        InetSocketAddress socketAddress = clientHandler.getRemoteSocketAddress();
        if (socketAddress == null) {
            throw new FatalActionException("Remote socket address not available");
        }

        user.setName(userInfo.getUserName());
        user.setClientType(userInfo.getClientType());
        user.setSocketAddress(socketAddress);
        user.setConnectionType(userInfo.getConnectionType());
        clientHandler.startSpeedTest();

        try {
            clientHandler.send(new ServerACK(clientHandler.getNextMessageNumber()));
        } catch (MessageFormatException e) {
            log.error("Failed to construct ServerACK message: " + e.getMessage(), e);
        }
    }
}
