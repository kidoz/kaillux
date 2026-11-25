package su.kidoz.kaillera.master.client;

import java.net.URI;
import java.util.List;

import su.kidoz.kaillera.controller.connectcontroller.ConnectController;
import su.kidoz.kaillera.master.PublicServerInformation;
import su.kidoz.kaillera.master.StatsCollector;
import su.kidoz.kaillera.model.KailleraGame;
import su.kidoz.kaillera.model.KailleraServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

public class KailleraMasterUpdateTask implements MasterListUpdateTask {
    private static final Logger log = LoggerFactory.getLogger(KailleraMasterUpdateTask.class);
    private static final String KAILLERA_MASTER_URL = "http://www.kaillera.com/touch_server.php";

    private final PublicServerInformation publicInfo;
    private final ConnectController connectController;
    private final KailleraServer kailleraServer;
    private final StatsCollector statsCollector;
    private final RestClient restClient;

    public KailleraMasterUpdateTask(PublicServerInformation publicInfo,
            ConnectController connectController, KailleraServer kailleraServer,
            StatsCollector statsCollector, RestClient restClient) {
        this.publicInfo = publicInfo;
        this.connectController = connectController;
        this.kailleraServer = kailleraServer;
        this.statsCollector = statsCollector;
        this.restClient = restClient;
    }

    public void touchMaster() {
        List<String> createdGamesList = statsCollector.drainStartedGamesList();

        StringBuilder createdGames = new StringBuilder();
        for (String game : createdGamesList) {
            createdGames.append(headerField(game));
            createdGames.append("|");
        }

        StringBuilder waitingGames = new StringBuilder();
        for (KailleraGame game : kailleraServer.getGames()) {
            if (game.getStatus() != KailleraGame.STATUS_WAITING)
                continue;

            waitingGames.append(game.getID());
            waitingGames.append("|");
            waitingGames.append(headerField(game.getRomName()));
            waitingGames.append("|");
            waitingGames.append(headerField(game.getOwner().getName()));
            waitingGames.append("|");
            waitingGames.append(headerField(game.getOwner().getClientType()));
            waitingGames.append("|");
            waitingGames.append(game.getNumPlayers());
            waitingGames.append("|");
        }

        // The values are operator configuration, not URI syntax: encode them, or an
        // ampersand in the server name turns into extra query parameters.
        URI uri = UriComponentsBuilder.fromUriString(KAILLERA_MASTER_URL)
                .queryParam("servername", publicInfo.getServerName())
                .queryParam("port", connectController.getBindPort())
                .queryParam("nbusers", kailleraServer.getNumUsers())
                .queryParam("maxconn", kailleraServer.getMaxUsers()).queryParam("version", "0.86")
                .queryParam("nbgames", kailleraServer.getNumGames())
                .queryParam("location", publicInfo.getLocation())
                .queryParam("ip", publicInfo.getConnectAddress())
                .queryParam("url", publicInfo.getWebsite()).encode().build().toUri();

        try {
            restClient.get().uri(uri).header("Kaillera-games", createdGames.toString())
                    .header("Kaillera-wgames", waitingGames.toString()).retrieve()
                    .toBodilessEntity();

            log.info("Touching Kaillera Master done");
        } catch (Exception e) {
            log.error("Failed to touch Kaillera Master: " + e.getMessage());
        }
    }

    /**
     * Makes a value safe inside a {@code Kaillera-games} or {@code Kaillera-wgames}
     * header. The master splits those headers on {@code |}, so a separator or a
     * line break in a game or user name would split or forge a record.
     */
    static String headerField(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(c == '|' || c < 32 || c == 127 ? ' ' : c);
        }
        return sb.toString();
    }
}
