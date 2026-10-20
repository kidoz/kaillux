package su.kidoz.kaillera.controller.v086.command;

import su.kidoz.kaillera.controller.v086.action.FatalActionException;
import su.kidoz.kaillera.controller.v086.action.V086Action;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.NoSuchElementException;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import su.kidoz.kaillera.access.AccessManager;
import su.kidoz.kaillera.controller.messaging.MessageFormatException;
import su.kidoz.kaillera.controller.v086.V086ClientHandler;
import su.kidoz.kaillera.controller.v086.protocol.Chat;
import su.kidoz.kaillera.controller.v086.protocol.InformationMessage;
import su.kidoz.kaillera.controller.v086.protocol.V086Message;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraServer;
import su.kidoz.kaillera.model.KailleraUser;
import su.kidoz.kaillera.model.exception.ActionException;
import su.kidoz.release.ReleaseInfo;
import su.kidoz.util.EmuLang;
import su.kidoz.util.WildcardStringPattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
public final class AdminCommandAction implements V086Action {
    public static final String COMMAND_ANNOUNCE = "/announce";
    public static final String COMMAND_ANNOUNCEALL = "/announceall";
    public static final String COMMAND_ANNOUNCEGAME = "/announcegame";
    public static final String COMMAND_BAN = "/ban";
    public static final String COMMAND_CLEAR = "/clear";
    public static final String COMMAND_CLOSEGAME = "/closegame";
    public static final String COMMAND_FINDGAME = "/findgame";
    public static final String COMMAND_FINDUSER = "/finduser";
    public static final String COMMAND_HELP = "/help";
    public static final String COMMAND_KICK = "/kick";
    public static final String COMMAND_SILENCE = "/silence";
    public static final String COMMAND_TEMPADMIN = "/tempadmin";
    public static final String COMMAND_VERSION = "/version";

    private static final Logger log = LoggerFactory.getLogger(AdminCommandAction.class);
    private static final String DESC = "AdminCommandAction";

    private final AtomicInteger actionCount = new AtomicInteger(0);

    public AdminCommandAction() {
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
        Chat chatMessage = (Chat) message;
        String chat = chatMessage.getMessage();
        KailleraServer server = clientHandler.getController().getServer();
        AccessManager accessManager = server.getAccessManager();
        KailleraUser user = clientHandler.getUser();
        if (user == null || !user.isLoggedIn())
            throw new FatalActionException(
                    "Admin Command Denied: " + user + " is not logged in: " + chat);

        InetSocketAddress peer = clientHandler.getRemoteSocketAddress();
        InetAddress handshake = clientHandler.getRemoteInetAddress();
        if (peer == null || handshake == null || !handshake.equals(peer.getAddress()))
            throw new FatalActionException(
                    "Admin Command Denied: " + user + " peer is not the connected client: " + chat);

        if (accessManager.getAccess(peer.getAddress()) != AccessManager.ACCESS_ADMIN)
            throw new FatalActionException(
                    "Admin Command Denied: " + user + " does not have Admin access: " + chat);

        log.info(user + ": Admin Command: " + chat);

        try {
            if (chat.startsWith(COMMAND_HELP)) {
                processHelp(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_FINDUSER)) {
                processFindUser(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_FINDGAME)) {
                processFindGame(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_CLOSEGAME)) {
                processCloseGame(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_KICK)) {
                processKick(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_BAN)) {
                processBan(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_ANNOUNCEGAME)) {
                processGameAnnounce(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_ANNOUNCE)) {
                processAnnounce(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_SILENCE)) {
                processSilence(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_TEMPADMIN)) {
                processTempAdmin(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_VERSION)) {
                processVersion(chat, server, user, clientHandler);
            } else if (chat.startsWith(COMMAND_CLEAR)) {
                processClear(chat, server, user, clientHandler);
            } else
                throw new ActionException("Invalid Command: " + chat);
        } catch (ActionException e) {
            log.info("Admin Command Failed: " + user + ": " + chat);

            try {
                clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                        "server", EmuLang.getString("AdminCommandAction.Failed", e.getMessage())));
            } catch (MessageFormatException e2) {
                log.error("Failed to construct InformationMessage message: " + e.getMessage(), e);
            }
        } catch (MessageFormatException e) {
            log.error("Failed to construct message: " + e.getMessage(), e);
        }
    }

    private void processHelp(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.AdminCommands")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpVersion")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpTempAdmin")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpKick")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpBan")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpSilence")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpClear")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpCloseGame")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpAnnounce")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpAnnounceAll")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpAnnounceGame")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpFindUser")));
        clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(), "server",
                EmuLang.getString("AdminCommandAction.HelpFindGame")));
    }

    private void processFindUser(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        int space = message.indexOf(' ');
        if (space < 0)
            throw new ActionException(EmuLang.getString("AdminCommandAction.FindUserError"));

        int foundCount = 0;
        WildcardStringPattern pattern = new WildcardStringPattern(message.substring(space + 1));
        for (KailleraUser user : server.getUsers()) {
            if (!user.isLoggedIn())
                continue;

            if (pattern.match(user.getName())) {
                StringBuilder sb = new StringBuilder();
                sb.append(user.getID());
                sb.append(": ");
                sb.append(user.getPing());
                sb.append("ms ");
                sb.append(user.getConnectSocketAddress().getAddress().getHostAddress());
                sb.append(" ");
                sb.append(user.getName());
                clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                        "server", sb.toString()));
                foundCount++;
            }
        }

        if (foundCount == 0)
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", EmuLang.getString("AdminCommandAction.NoUsersFound")));
    }

    private void processFindGame(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        int space = message.indexOf(' ');
        if (space < 0)
            throw new ActionException(EmuLang.getString("AdminCommandAction.FindGameError"));

        int foundCount = 0;
        WildcardStringPattern pattern = new WildcardStringPattern(message.substring(space + 1));
        for (KailleraGame game : server.getGames()) {
            if (pattern.match(game.getRomName())) {
                StringBuilder sb = new StringBuilder();
                sb.append(game.getID());
                sb.append(": ");
                sb.append(game.getOwner().getName());
                sb.append(" ");
                sb.append(game.getRomName());
                clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                        "server", sb.toString()));
                foundCount++;
            }
        }

        if (foundCount == 0)
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", EmuLang.getString("AdminCommandAction.NoGamesFound")));
    }

    private void processSilence(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        try (Scanner scanner = new Scanner(message).useDelimiter(" ")) {
            scanner.next();
            int userID = scanner.nextInt();
            int minutes = scanner.nextInt();

            KailleraUser user = server.getUser(userID);
            if (user == null) {
                throw new ActionException(
                        EmuLang.getString("AdminCommandAction.UserNotFound") + userID);
            }

            if (user.getID() == admin.getID()) {
                throw new ActionException(
                        EmuLang.getString("AdminCommandAction.CanNotSilenceSelf"));
            }

            int access = server.getAccessManager()
                    .getAccess(user.getConnectSocketAddress().getAddress());
            if (access == AccessManager.ACCESS_ADMIN) {
                throw new ActionException(
                        EmuLang.getString("AdminCommandAction.CanNotSilenceAdmin"));
            }

            server.getAccessManager().addSilenced(
                    user.getConnectSocketAddress().getAddress().getHostAddress(), minutes);
            server.announce(
                    EmuLang.getString("AdminCommandAction.Silenced", minutes, user.getName()),
                    false);
        } catch (NoSuchElementException e) {
            throw new ActionException(EmuLang.getString("AdminCommandAction.SilenceError"));
        }
    }

    private void processKick(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        try (Scanner scanner = new Scanner(message).useDelimiter(" ")) {
            scanner.next();
            int userID = scanner.nextInt();

            KailleraUser user = server.getUser(userID);
            if (user == null) {
                throw new ActionException(
                        EmuLang.getString("AdminCommandAction.UserNotFound", userID));
            }

            if (user.getID() == admin.getID()) {
                throw new ActionException(EmuLang.getString("AdminCommandAction.CanNotKickSelf"));
            }

            int access = server.getAccessManager()
                    .getAccess(user.getConnectSocketAddress().getAddress());
            if (access == AccessManager.ACCESS_ADMIN) {
                throw new ActionException(EmuLang.getString("AdminCommandAction.CanNotKickAdmin"));
            }

            user.quit(EmuLang.getString("AdminCommandAction.QuitKicked"));
        } catch (NoSuchElementException e) {
            throw new ActionException(EmuLang.getString("AdminCommandAction.KickError"));
        }
    }

    private void processCloseGame(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        try (Scanner scanner = new Scanner(message).useDelimiter(" ")) {
            scanner.next();
            int gameID = scanner.nextInt();

            KailleraGame game = server.getGame(gameID);
            if (game == null) {
                throw new ActionException(
                        EmuLang.getString("AdminCommandAction.GameNotFound", gameID));
            }

            KailleraUser owner = game.getOwner();
            int access = server.getAccessManager()
                    .getAccess(owner.getConnectSocketAddress().getAddress());

            if (access == AccessManager.ACCESS_ADMIN && owner.isLoggedIn()) {
                throw new ActionException(
                        EmuLang.getString("AdminCommandAction.CanNotCloseAdminGame"));
            }

            owner.quitGame();
        } catch (NoSuchElementException e) {
            throw new ActionException(EmuLang.getString("AdminCommandAction.CloseGameError"));
        }
    }

    private void processBan(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        try (Scanner scanner = new Scanner(message).useDelimiter(" ")) {
            scanner.next();
            int userID = scanner.nextInt();
            int minutes = scanner.nextInt();

            KailleraUser user = server.getUser(userID);
            if (user == null) {
                throw new ActionException(
                        EmuLang.getString("AdminCommandAction.UserNotFound", userID));
            }

            if (user.getID() == admin.getID()) {
                throw new ActionException(EmuLang.getString("AdminCommandAction.CanNotBanSelf"));
            }

            int access = server.getAccessManager()
                    .getAccess(user.getConnectSocketAddress().getAddress());
            if (access == AccessManager.ACCESS_ADMIN) {
                throw new ActionException(EmuLang.getString("AdminCommandAction.CanNotBanAdmin"));
            }

            user.quit(EmuLang.getString("AdminCommandAction.QuitBanned"));

            server.getAccessManager().addTempBan(
                    user.getConnectSocketAddress().getAddress().getHostAddress(), minutes);
            server.announce(EmuLang.getString("AdminCommandAction.Banned", minutes, user.getName()),
                    false);
        } catch (NoSuchElementException e) {
            throw new ActionException(EmuLang.getString("AdminCommandAction.BanError"));
        }
    }

    private void processTempAdmin(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        try (Scanner scanner = new Scanner(message).useDelimiter(" ")) {
            scanner.next();
            int userID = scanner.nextInt();
            int minutes = scanner.nextInt();

            KailleraUser user = server.getUser(userID);
            if (user == null) {
                throw new ActionException(
                        EmuLang.getString("AdminCommandAction.UserNotFound", userID));
            }

            if (user.getID() == admin.getID()) {
                throw new ActionException(EmuLang.getString("AdminCommandAction.AlreadyAdmin"));
            }

            int access = server.getAccessManager()
                    .getAccess(user.getConnectSocketAddress().getAddress());
            if (access == AccessManager.ACCESS_ADMIN) {
                throw new ActionException(EmuLang.getString("AdminCommandAction.UserAlreadyAdmin"));
            }

            server.getAccessManager().addTempAdmin(
                    user.getConnectSocketAddress().getAddress().getHostAddress(), minutes);
            server.announce(EmuLang.getString("AdminCommandAction.TempAdminGranted", minutes,
                    user.getName()), false);
        } catch (NoSuchElementException e) {
            throw new ActionException(EmuLang.getString("AdminCommandAction.TempAdminError"));
        }
    }

    private void processAnnounce(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        int space = message.indexOf(' ');
        if (space < 0)
            throw new ActionException(EmuLang.getString("AdminCommandAction.AnnounceError"));

        boolean all = false;
        if (message.startsWith(COMMAND_ANNOUNCEALL))
            all = true;

        String announcement = message.substring(space + 1);
        if (announcement.startsWith(":"))
            announcement = announcement.substring(1); // this protects against people screwing up
                                                      // the emulinker
                                                      // supraclient

        server.announce(announcement, all);
    }

    private void processGameAnnounce(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        try (Scanner scanner = new Scanner(message).useDelimiter(" ")) {
            scanner.next();
            int gameID = scanner.nextInt();

            StringBuilder sb = new StringBuilder();
            while (scanner.hasNext()) {
                sb.append(scanner.next());
                sb.append(" ");
            }

            KailleraGame game = server.getGame(gameID);
            if (game == null) {
                throw new ActionException(
                        EmuLang.getString("AdminCommandAction.GameNoutFound") + gameID);
            }

            game.announce(sb.toString());
        } catch (NoSuchElementException e) {
            throw new ActionException(EmuLang.getString("AdminCommandAction.AnnounceGameError"));
        }
    }

    private void processClear(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        int space = message.indexOf(' ');
        if (space < 0)
            throw new ActionException(EmuLang.getString("AdminCommandAction.ClearError"));

        String addressStr = message.substring(space + 1);
        InetAddress inetAddr = null;
        try {
            inetAddr = InetAddress.getByName(addressStr);
        } catch (Exception e) {
            throw new ActionException(
                    EmuLang.getString("AdminCommandAction.ClearAddressFormatError"));
        }

        if (server.getAccessManager().clearTemp(inetAddr))
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", EmuLang.getString("AdminCommandAction.ClearSuccess")));
        else
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", EmuLang.getString("AdminCommandAction.ClearNotFound")));
    }

    private void processVersion(String message, KailleraServer server, KailleraUser admin,
            V086ClientHandler clientHandler) throws ActionException, MessageFormatException {
        try {
            ReleaseInfo releaseInfo = server.getReleaseInfo();
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", "VERSION: " + releaseInfo.getVersionString()));
            sleep(20);

            Properties props = System.getProperties();
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", "JAVAVER: " + props.getProperty("java.version")));
            sleep(20);
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", "JAVAVEND: " + props.getProperty("java.vendor")));
            sleep(20);
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", "OSNAME: " + props.getProperty("os.name")));
            sleep(20);
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", "OSARCH: " + props.getProperty("os.arch")));
            sleep(20);
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", "OSVER: " + props.getProperty("os.version")));
            sleep(20);

            Runtime runtime = Runtime.getRuntime();
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", "NUMPROCS: " + runtime.availableProcessors()));
            sleep(20);
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", "FREEMEM: " + runtime.freeMemory()));
            sleep(20);
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", "MAXMEM: " + runtime.maxMemory()));
            sleep(20);
            clientHandler.send(new InformationMessage(clientHandler.getNextMessageNumber(),
                    "server", "TOTMEM: " + runtime.totalMemory()));
            sleep(20);
            // Note: Hostname and username removed for security - could aid attackers
        } catch (NoSuchElementException e) {
            throw new ActionException(EmuLang.getString("AdminCommandAction.VersionError"));
        }
    }

    private void sleep(int ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
