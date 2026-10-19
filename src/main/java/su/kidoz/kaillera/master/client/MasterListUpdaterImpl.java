package su.kidoz.kaillera.master.client;

import su.kidoz.config.MasterListConfig;
import su.kidoz.kaillera.controller.connectcontroller.ConnectController;
import su.kidoz.kaillera.master.PublicServerInformation;
import su.kidoz.kaillera.master.StatsCollector;
import su.kidoz.kaillera.model.KailleraServer;
import su.kidoz.release.ReleaseInfo;
import su.kidoz.util.EmuLinkerExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.web.client.RestClient;

public class MasterListUpdaterImpl implements MasterListUpdater, Runnable, SmartLifecycle {
    private static final Logger log = LoggerFactory.getLogger(MasterListUpdaterImpl.class);

    private final EmuLinkerExecutor threadPool;
    private final ConnectController connectController;
    private final KailleraServer kailleraServer;
    private final StatsCollector statsCollector;
    private final ReleaseInfo releaseInfo;

    private final PublicServerInformation publicInfo;

    private final boolean touchKaillera;
    private final boolean touchEmulinker;

    private EmuLinkerMasterUpdateTask emulinkerMasterTask;
    private KailleraMasterUpdateTask kailleraMasterTask;

    private volatile boolean stopFlag = false;
    private volatile boolean isRunning = false;

    public MasterListUpdaterImpl(MasterListConfig config, EmuLinkerExecutor threadPool,
            ConnectController connectController, KailleraServer kailleraServer,
            StatsCollector statsCollector, ReleaseInfo releaseInfo, RestClient restClient) {
        this.threadPool = threadPool;
        this.connectController = connectController;
        this.kailleraServer = kailleraServer;
        this.statsCollector = statsCollector;
        this.releaseInfo = releaseInfo;

        this.touchKaillera = config.isTouchKaillera();
        this.touchEmulinker = config.isTouchEmulinker();

        if (touchKaillera || touchEmulinker) {
            publicInfo = new PublicServerInformation(config);
        } else {
            publicInfo = null;
        }

        if (touchKaillera)
            kailleraMasterTask = new KailleraMasterUpdateTask(publicInfo, connectController,
                    kailleraServer, statsCollector, restClient);

        if (touchEmulinker)
            emulinkerMasterTask = new EmuLinkerMasterUpdateTask(publicInfo, connectController,
                    kailleraServer, releaseInfo, restClient, config.getEmulinkerMasterUrl());
    }

    @Override
    public synchronized boolean isRunning() {
        return isRunning;
    }

    public synchronized String toString() {
        return "MasterListUpdaterImpl[touchKaillera=" + touchKaillera + " touchEmulinker="
                + touchEmulinker + "]";
    }

    @Override
    public synchronized void start() {
        if (isRunning) {
            log.debug("MasterListUpdater start request ignored: already running!");
            return;
        }

        if (publicInfo != null) {
            log.debug("MasterListUpdater thread starting (ThreadPool:" + threadPool.getActiveCount()
                    + "/" + threadPool.getPoolSize() + ")");
            stopFlag = false;
            threadPool.execute(this);
            log.info("MasterListUpdater started");
        } else {
            log.info("MasterListUpdater not started (no master servers configured)");
        }
    }

    @Override
    public synchronized void stop() {
        log.debug("MasterListUpdater thread received stop request!");

        if (!isRunning()) {
            log.debug("MasterListUpdater thread stop request ignored: not running!");
            return;
        }

        stopFlag = true;
        log.info("MasterListUpdater stopped");
    }

    @Override
    public int getPhase() {
        // Phase 40: Master registration, after server is fully running
        return 40;
    }

    @Override
    public void run() {
        isRunning = true;
        log.debug("MasterListUpdater thread running...");

        try {
            while (!stopFlag) {
                try {
                    Thread.sleep(60000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                if (stopFlag)
                    break;

                log.info("MasterListUpdater touching masters...");

                // Keep the updater thread alive if a single touch fails.
                if (emulinkerMasterTask != null) {
                    try {
                        emulinkerMasterTask.touchMaster();
                    } catch (Exception e) {
                        log.error("Failed to touch EmuLinker master list", e);
                    }
                }

                if (kailleraMasterTask != null) {
                    try {
                        kailleraMasterTask.touchMaster();
                    } catch (Exception e) {
                        log.error("Failed to touch Kaillera master list", e);
                    }
                } else if (statsCollector != null) {
                    // No Kaillera updater consumes the list; discard it to avoid growth.
                    statsCollector.clearStartedGamesList();
                }
            }
        } finally {
            isRunning = false;
            log.debug("MasterListUpdater thread exiting...");
        }
    }
}
