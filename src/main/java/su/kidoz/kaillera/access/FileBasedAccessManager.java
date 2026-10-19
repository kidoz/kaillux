package su.kidoz.kaillera.access;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.NoSuchElementException;

import su.kidoz.util.EmuLinkerExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import su.kidoz.kaillera.access.parser.AccessConfigParser;
import su.kidoz.kaillera.access.parser.ParseResult;
import su.kidoz.kaillera.access.store.AccessRuleStore;
import su.kidoz.kaillera.access.store.TemporaryRuleStore;

/**
 * File-based access manager that loads rules from a configuration file and
 * supports hot-reload. Delegates to specialized stores for rule management.
 *
 * <p>
 * Runs a background thread that periodically:
 * <ul>
 * <li>Purges expired temporary rules
 * <li>Refreshes DNS resolutions
 * <li>Checks for config file changes (hot-reload)
 * </ul>
 */
public class FileBasedAccessManager implements AccessManager, Runnable, SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(FileBasedAccessManager.class);
    private static final String DEFAULT_ACCESS_FILE = "access.cfg";
    private static final int MAINTENANCE_INTERVAL_MS = 60_000;

    private final EmuLinkerExecutor threadPool;
    private final AccessConfigParser parser;
    private final AccessRuleStore ruleStore;
    private final TemporaryRuleStore tempStore;

    private volatile boolean isRunning = false;
    private volatile boolean stopFlag = false;

    private File accessFile;
    private long lastLoadModifiedTime = -1;

    public FileBasedAccessManager(EmuLinkerExecutor threadPool)
            throws NoSuchElementException, FileNotFoundException {
        this(threadPool, null);
    }

    public FileBasedAccessManager(EmuLinkerExecutor threadPool, String accessFilePath)
            throws NoSuchElementException, FileNotFoundException {
        this.threadPool = threadPool;
        this.parser = new AccessConfigParser();
        this.ruleStore = new AccessRuleStore();
        this.tempStore = new TemporaryRuleStore();

        resolveAccessFile(accessFilePath);
        loadAccess();
        // Thread is started via SmartLifecycle.start(), not in constructor
    }

    private void resolveAccessFile(String accessFilePath) throws FileNotFoundException {
        if (accessFilePath != null && !accessFilePath.isEmpty()) {
            accessFile = new File(accessFilePath);
        } else {
            File cwdFile = new File(DEFAULT_ACCESS_FILE);
            if (cwdFile.exists() && cwdFile.canRead()) {
                accessFile = cwdFile;
                log.info("Using access file from current directory: {}", cwdFile.getAbsolutePath());
            } else {
                URL url = FileBasedAccessManager.class.getResource("/" + DEFAULT_ACCESS_FILE);
                if (url == null) {
                    throw new FileNotFoundException("Access file not found. Place "
                            + DEFAULT_ACCESS_FILE
                            + " in the application directory or specify path via configuration.");
                }
                try {
                    if ("file".equals(url.getProtocol())) {
                        accessFile = new File(url.toURI());
                    } else {
                        // Nested-jar URLs are not hierarchical, so new File(uri) throws
                        // IllegalArgumentException. Extract the packaged resource to a
                        // temporary file that the parser can read.
                        accessFile = extractClasspathAccessFile(url);
                    }
                } catch (URISyntaxException e) {
                    throw new FileNotFoundException(
                            "Cannot read access file from classpath in packaged deployment. "
                                    + "Place " + DEFAULT_ACCESS_FILE
                                    + " in the application directory.");
                }
            }
        }

        if (!accessFile.exists()) {
            throw new FileNotFoundException("Access file not found: " + accessFile.getPath());
        }
        if (!accessFile.canRead()) {
            throw new FileNotFoundException("Cannot read access file: " + accessFile.getPath());
        }

        log.info("Loading access rules from: {}", accessFile.getAbsolutePath());
    }

    private File extractClasspathAccessFile(URL url) throws FileNotFoundException {
        try (InputStream in = url.openStream()) {
            Path tempFile = Files.createTempFile("kaillux-access-", ".cfg");
            Files.copy(in, tempFile, StandardCopyOption.REPLACE_EXISTING);
            File file = tempFile.toFile();
            file.deleteOnExit();
            log.info("Extracted packaged access file to: {}", file.getAbsolutePath());
            return file;
        } catch (IOException e) {
            FileNotFoundException failure = new FileNotFoundException(
                    "Cannot read access file from classpath in packaged deployment. Place "
                            + DEFAULT_ACCESS_FILE + " in the application directory.");
            failure.initCause(e);
            throw failure;
        }
    }

    @Override
    public synchronized void start() {
        if (isRunning) {
            log.debug("FileBasedAccessManager start request ignored: already running!");
            return;
        }

        log.debug("FileBasedAccessManager thread starting (ThreadPool: {}/{})",
                threadPool.getActiveCount(), threadPool.getPoolSize());
        stopFlag = false;
        threadPool.execute(this);
        log.info("FileBasedAccessManager started");
    }

    @Override
    public boolean isRunning() {
        return isRunning;
    }

    @Override
    public synchronized void stop() {
        log.debug("FileBasedAccessManager thread received stop request!");

        if (!isRunning()) {
            log.debug("FileBasedAccessManager thread stop request ignored: not running!");
            return;
        }

        stopFlag = true;
        ruleStore.clear();
        tempStore.clear();
        log.info("FileBasedAccessManager stopped");
    }

    @Override
    public int getPhase() {
        // Phase 0: Must be ready first (access rules needed by other components)
        return 0;
    }

    @Override
    public void run() {
        isRunning = true;
        log.debug("FileBasedAccessManager thread running...");

        try {
            while (!stopFlag) {
                try {
                    Thread.sleep(MAINTENANCE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    log.error("Sleep Interrupted!", e);
                }

                if (stopFlag) {
                    break;
                }

                synchronized (this) {
                    tempStore.purgeExpired();
                    ruleStore.refreshDns();
                    checkReload();
                }
            }
        } catch (Throwable e) {
            if (!stopFlag) {
                log.error("FileBasedAccessManager thread caught unexpected exception: " + e, e);
            }
        } finally {
            isRunning = false;
            log.debug("FileBasedAccessManager thread exiting...");
        }
    }

    private void checkReload() {
        if (accessFile != null && accessFile.lastModified() > lastLoadModifiedTime) {
            loadAccess();
        }
    }

    private synchronized void loadAccess() {
        if (accessFile == null) {
            return;
        }

        log.info("Reloading permissions...");
        lastLoadModifiedTime = accessFile.lastModified();

        try {
            ParseResult result = parser.parse(accessFile);
            ruleStore.update(result);
            log.info("Loaded {} rules ({} errors)", result.totalRules(), result.errorCount());
        } catch (IOException e) {
            log.error("Failed to load access file: {}", e.getMessage(), e);
        }
    }

    @Override
    public void addTempBan(String addressPattern, int minutes) {
        tempStore.addBan(addressPattern, minutes);
    }

    @Override
    public void addTempAdmin(String addressPattern, int minutes) {
        tempStore.addAdmin(addressPattern, minutes);
    }

    @Override
    public void addSilenced(String addressPattern, int minutes) {
        tempStore.addSilence(addressPattern, minutes);
    }

    @Override
    public synchronized String getAnnouncement(InetAddress address) {
        checkReload();
        return ruleStore.getUserAnnouncement(address.getHostAddress());
    }

    @Override
    public synchronized int getAccess(InetAddress address) {
        checkReload();
        String userAddress = address.getHostAddress();

        if (tempStore.hasTemporaryAdmin(userAddress)) {
            return ACCESS_ADMIN;
        }

        return ruleStore.getUserAccessLevel(userAddress);
    }

    @Override
    public synchronized boolean clearTemp(InetAddress address) {
        return tempStore.clearForAddress(address.getHostAddress());
    }

    @Override
    public synchronized boolean isSilenced(InetAddress address) {
        checkReload();
        return tempStore.isSilenced(address.getHostAddress());
    }

    @Override
    public synchronized boolean isAddressAllowed(InetAddress address) {
        checkReload();
        String userAddress = address.getHostAddress();

        if (tempStore.isBanned(userAddress)) {
            return false;
        }

        return ruleStore.isAddressAllowed(userAddress);
    }

    @Override
    public synchronized boolean isEmulatorAllowed(String emulator) {
        checkReload();
        return ruleStore.isEmulatorAllowed(emulator);
    }

    @Override
    public synchronized boolean isGameAllowed(String game) {
        checkReload();
        return ruleStore.isGameAllowed(game);
    }
}
