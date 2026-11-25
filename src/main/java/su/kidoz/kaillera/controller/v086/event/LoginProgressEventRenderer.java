package su.kidoz.kaillera.controller.v086.event;

import su.kidoz.kaillera.controller.v086.action.V086UserEventHandler;
import su.kidoz.kaillera.controller.v086.annotation.V086UserEvent;

import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.model.KailleraServer;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.event.InfoMessageEvent;
import su.kidoz.kaillera.model.event.LoginProgressEvent;
import su.kidoz.kaillera.model.event.UserEvent;
import su.kidoz.kaillera.model.event.UserJoinedEvent;
import su.kidoz.util.EmuLang;

import su.kidoz.kaillera.controller.v086.V086ClientHandler;
import su.kidoz.kaillera.model.LoginNotificationState;

/**
 * Handles LoginProgressEvent to drive the login notification state machine.
 *
 * <p>
 * This action replaces the Thread.sleep() delays in the login flow by using
 * event chaining. Each state processes its work and queues the next state as an
 * event.
 */
@Component
@V086UserEvent(eventType = LoginProgressEvent.class)
public final class LoginProgressEventRenderer implements V086UserEventHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginProgressEventRenderer.class);
    private static final String DESC = "LoginProgressEventRenderer";

    private final AtomicInteger handledCount = new AtomicInteger(0);

    public LoginProgressEventRenderer() {
    }

    public int getHandledEventCount() {
        return handledCount.get();
    }

    @Override
    public String toString() {
        return DESC;
    }

    @Override
    public void handleEvent(UserEvent event, V086ClientHandler clientHandler) {
        handledCount.incrementAndGet();

        if (!(event instanceof LoginProgressEvent progressEvent)) {
            log.error("Expected LoginProgressEvent but got: " + event.getClass());
            return;
        }

        KailleraUser user = progressEvent.getUser();
        LoginNotificationState state = progressEvent.getState();

        log.debug("{} processing login state: {}", user, state);

        try {
            switch (state) {
                case CONNECTED -> processConnectedState(progressEvent, user);
                case USER_JOINED -> processUserJoinedState(progressEvent, user);
                case MESSAGES_SENT -> processMessagesSentState(progressEvent, user);
                case ADMIN_INFO -> processAdminInfoState(progressEvent, user);
                case COMPLETE -> {
                    // Nothing to do, login complete
                    log.debug("{} login notification sequence complete", user);
                }
                default -> throw new IllegalStateException("Unexpected state: " + state);
            }
        } catch (Exception e) {
            log.error("Error processing login state {} for {}: {}", state, user, e.getMessage(), e);
        }
    }

    private void processConnectedState(LoginProgressEvent event, KailleraUser user) {
        // Send UserJoinedEvent to all logged-in users
        KailleraServer server = event.getServer();
        server.addEvent(new UserJoinedEvent(server, user));

        // Queue next state
        LoginProgressEvent next = event.nextEvent();
        if (next != null) {
            user.addEvent(next);
        }
    }

    private void processUserJoinedState(LoginProgressEvent event, KailleraUser user) {
        // Send login messages to this user
        for (String loginMessage : event.getLoginMessages()) {
            user.addEvent(new InfoMessageEvent(user, loginMessage));
        }

        int access = event.getAccessLevel();
        if (access > AccessManager.ACCESS_NORMAL) {
            log.info("{} logged in successfully with {} access!", user,
                    AccessManager.getAccessName(access));
        } else {
            log.info("{} logged in successfully", user);
        }

        // Check for announcement
        KailleraServer server = event.getServer();
        String announcement = server.getAccessManager()
                .getAnnouncement(user.getSocketAddress().getAddress());
        if (announcement != null) {
            server.announce(announcement, false);
        }

        // Queue next state
        LoginProgressEvent next = event.nextEvent();
        if (next != null) {
            user.addEvent(next);
        }
    }

    private void processMessagesSentState(LoginProgressEvent event, KailleraUser user) {
        int access = event.getAccessLevel();

        // Send admin welcome message if applicable
        if (access == AccessManager.ACCESS_ADMIN) {
            user.addEvent(new InfoMessageEvent(user,
                    EmuLang.getString("KailleraServerImpl.AdminWelcomeMessage")));
        }

        // Queue next state
        LoginProgressEvent next = event.nextEvent();
        if (next != null) {
            user.addEvent(next);
        }
    }

    private void processAdminInfoState(LoginProgressEvent event, KailleraUser user) {
        int access = event.getAccessLevel();

        // Send Kaillux client-specific info
        if (user.isEmuLinkerClient()) {
            user.addEvent(new InfoMessageEvent(user, ":ACCESS=" + user.getAccessStr()));

            if (access == AccessManager.ACCESS_ADMIN) {
                sendAdminUserInfo(event, user);
            }
        }

        // Login complete - no next state needed
    }

    private void sendAdminUserInfo(LoginProgressEvent event, KailleraUser admin) {
        KailleraServer server = event.getServer();
        StringBuilder sb = new StringBuilder();
        sb.append(":USERINFO=");
        int sbCount = 0;

        for (KailleraUser u3 : server.getUsers()) {
            if (!u3.isLoggedIn()) {
                continue;
            }

            sb.append(u3.getID());
            sb.append(",");
            sb.append(u3.getConnectSocketAddress().getAddress().getHostAddress());
            sb.append(",");
            sb.append(u3.getAccessStr());
            sb.append(";");
            sbCount++;

            if (sb.length() > 300) {
                admin.addEvent(new InfoMessageEvent(admin, sb.toString()));
                sb = new StringBuilder();
                sb.append(":USERINFO=");
                sbCount = 0;
            }
        }

        if (sbCount > 0) {
            admin.addEvent(new InfoMessageEvent(admin, sb.toString()));
        }
    }
}
