package su.kidoz.kaillera.master.client;

import java.io.StringReader;
import java.net.URI;
import java.util.Properties;

import su.kidoz.kaillera.controller.connectcontroller.ConnectController;
import su.kidoz.kaillera.master.PublicServerInformation;
import su.kidoz.kaillera.model.KailleraServer;
import su.kidoz.kaillera.model.validation.InputValidator;
import su.kidoz.release.ReleaseInfo;
import su.kidoz.util.EmuUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

public class EmuLinkerMasterUpdateTask implements MasterListUpdateTask {
    private static final Logger log = LoggerFactory.getLogger(EmuLinkerMasterUpdateTask.class);

    private final PublicServerInformation publicInfo;
    private final ConnectController connectController;
    private final KailleraServer kailleraServer;
    private final ReleaseInfo releaseInfo;
    private final RestClient restClient;
    private final String emulinkerMasterUrl;

    public EmuLinkerMasterUpdateTask(PublicServerInformation publicInfo,
            ConnectController connectController, KailleraServer kailleraServer,
            ReleaseInfo releaseInfo, RestClient restClient, String emulinkerMasterUrl) {
        this.publicInfo = publicInfo;
        this.connectController = connectController;
        this.kailleraServer = kailleraServer;
        this.releaseInfo = releaseInfo;
        this.restClient = restClient;
        this.emulinkerMasterUrl = emulinkerMasterUrl;

        if (!emulinkerMasterUrl.startsWith("https://")) {
            log.warn("master-list.emulinker-master-url is not HTTPS ({}). The update notice "
                    + "can be modified by an on-path attacker.", emulinkerMasterUrl);
        }
    }

    public void touchMaster() {
        // The values are operator configuration, not URI syntax: encode them, or an
        // ampersand in the server name turns into extra query parameters.
        URI uri = UriComponentsBuilder.fromUriString(emulinkerMasterUrl)
                .queryParam("serverName", publicInfo.getServerName())
                .queryParam("connectAddress", publicInfo.getConnectAddress())
                .queryParam("location", publicInfo.getLocation())
                .queryParam("website", publicInfo.getWebsite())
                .queryParam("port", connectController.getBindPort())
                .queryParam("connectCount", connectController.getConnectCount())
                .queryParam("numUsers", kailleraServer.getNumUsers())
                .queryParam("maxUsers", kailleraServer.getMaxUsers())
                .queryParam("numGames", kailleraServer.getNumGames())
                .queryParam("maxGames", kailleraServer.getMaxGames())
                .queryParam("version", releaseInfo.getVersionString())
                .queryParam("build", releaseInfo.getBuildNumber())
                .queryParam("isWindows", EmuUtil.systemIsWindows()).encode().build().toUri();

        Properties props = new Properties();

        try {
            String response = restClient.get().uri(uri).retrieve().body(String.class);

            if (response != null) {
                props.load(new StringReader(response));
            }

            log.info("Touching Kaillux master list done");
        } catch (Exception e) {
            log.error("Failed to touch Kaillux master list: " + e.getMessage());
        }

        // The master answers over plain HTTP and is not authenticated, so nothing it
        // sends may be copied into the log unchecked.
        String updateAvailable = props.getProperty("updateAvailable");
        if (updateAvailable != null && updateAvailable.equalsIgnoreCase("true")) {
            String latestVersion = props.getProperty("latest");
            String notes = props.getProperty("notes");
            StringBuilder sb = new StringBuilder();
            sb.append("A new version of Kaillux is available: ");
            sb.append(latestVersion);
            if (notes != null) {
                sb.append(" (");
                sb.append(notes);
                sb.append(")");
            }
            log.warn(InputValidator.sanitizeForLog(sb.toString()));
        }
    }
}
